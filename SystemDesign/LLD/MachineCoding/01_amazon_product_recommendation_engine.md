# Amazon Product Recommendation Engine - LLD Study Document

---

## 1. Problem Statement

Design a product recommendation system similar to Amazon's "Customers who bought this also bought" and "Recommended for you" features. The system must provide personalized product recommendations to users based on their behavior, preferences, and product attributes while supporting multiple recommendation algorithms and real-time updates.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement | Description |
|----|-------------|-------------|
| FR1 | Personalized Recommendations | Generate "Recommended for you" based on user's interaction history |
| FR2 | Similar Products | Show "Customers who bought this also bought" for a given product |
| FR3 | Trending Products | Display trending/popular items in user's categories |
| FR4 | Algorithm Selection | Support multiple algorithms: collaborative filtering, content-based, trending |
| FR5 | User Interaction Tracking | Record views, clicks, purchases, ratings for recommendation generation |
| FR6 | Cold Start Handling | Provide fallback recommendations for new users or products |
| FR7 | Real-time Updates | Recommendations should reflect recent user behavior |

### Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR1 | Latency | <100ms for recommendation API response |
| NFR2 | Scalability | Support 100M+ users, 10M+ products |
| NFR3 | Availability | 99.9% uptime |
| NFR4 | Extensibility | Easy to add new recommendation algorithms |
| NFR5 | Testability | Components should be loosely coupled for unit testing |

---

## 3. Database Design with Explanations

```sql
/*
 * TABLE: users
 *
 * WHY this table exists:
 *   - Core entity for personalization. Every recommendation is tied to a user.
 *   - Stores user profile data needed for content-based and demographic filtering.
 *
 * WHY each foreign key/relationship:
 *   - No FKs in users table (it's a root entity). Referenced by user_interactions,
 *     user_recommendations for user-centric queries.
 *
 * WHY this structure over alternatives:
 *   - UUID over auto-increment: Better for distributed systems, no sequential ID collision.
 *   - JSONB for demographics/preferences: Schema flexibility for evolving attributes
 *     without migrations. Alternative: separate tables would require joins and
 *     schema changes for new preference types.
 *
 * WHY these indexes:
 *   - idx_users_last_active: Batch jobs need to find active users for precomputation.
 *   - idx_users_email: Unique constraint for login/auth.
 */
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    demographics JSONB DEFAULT '{}',
    preferences JSONB DEFAULT '{}',
    last_active TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_users_last_active (last_active),
    INDEX idx_users_email (email)
);

/*
 * TABLE: products
 *
 * WHY this table exists:
 *   - Product catalog for recommendations. Content-based algorithms need product
 *     attributes (category, brand, features) for similarity computation.
 *
 * WHY each foreign key/relationship:
 *   - category_id: Self-referential or to categories table. Enables category-based
 *     filtering and "similar products in same category" logic.
 *
 * WHY this structure over alternatives:
 *   - JSONB for features: Products have varying attributes (electronics vs clothing).
 *     EAV schema would be complex; JSONB allows flexible schema per product type.
 *
 * WHY these indexes:
 *   - idx_products_category: Content-based recommendations filter by category.
 *   - idx_products_rating: Trending/popular products often sorted by rating.
 *   - idx_products_status: Exclude inactive products from recommendations.
 */
CREATE TABLE products (
    product_id UUID PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    category_id UUID NOT NULL,
    brand VARCHAR(100),
    price DECIMAL(10,2),
    rating DECIMAL(2,1) DEFAULT 0,
    features JSONB DEFAULT '{}',
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_products_category (category_id),
    INDEX idx_products_rating (rating DESC),
    INDEX idx_products_status (status)
);

/*
 * TABLE: user_interactions
 *
 * WHY this table exists:
 *   - Core input for collaborative filtering. "Users who bought X also bought Y"
 *     requires knowing which users interacted with which products.
 *   - Implicit feedback (views, clicks) and explicit feedback (ratings, purchases).
 *
 * WHY each foreign key/relationship:
 *   - user_id -> users: Every interaction belongs to a user. Enables user-centric
 *     queries (e.g., "get all interactions for user X").
 *   - product_id -> products: Every interaction is about a product. Enables
 *     product-centric queries (e.g., "who viewed product Y").
 *
 * WHY this structure over alternatives:
 *   - Single table for all interaction types vs separate tables: Simpler queries,
 *     one place for all user-product relationships. Alternative (separate tables)
 *     would require UNION for collaborative filtering.
 *
 * WHY these indexes:
 *   - idx_interactions_user_time: Collaborative filtering fetches user's recent
 *     interactions ordered by time. Most common query pattern.
 *   - idx_interactions_product_time: "Similar users" often computed by product
 *     co-occurrence. Need to find users who interacted with product X.
 *   - idx_interactions_type: Filter by interaction type (purchase vs view).
 */
CREATE TABLE user_interactions (
    interaction_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    product_id UUID NOT NULL REFERENCES products(product_id),
    interaction_type VARCHAR(20) NOT NULL,
    interaction_value INTEGER,
    timestamp TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_interactions_user_time (user_id, timestamp DESC),
    INDEX idx_interactions_product_time (product_id, timestamp DESC),
    INDEX idx_interactions_type (interaction_type)
);

/*
 * TABLE: user_recommendations
 *
 * WHY this table exists:
 *   - Store precomputed recommendations for offline/batch processing. Reduces
 *     real-time latency by serving cached results.
 *   - Enables A/B testing of algorithms (algorithm column tracks which algorithm
 *     generated each recommendation).
 *
 * WHY each foreign key/relationship:
 *   - user_id -> users: Recommendations are per-user. Primary lookup key.
 *   - product_id -> products: Each recommendation points to a product. Must
 *     validate product exists and is active.
 *
 * WHY this structure over alternatives:
 *   - Denormalized (user_id, product_id, score) vs storing in Redis only:
 *     Persistence for audit, analytics, and cache warm-up. Redis is primary
 *     cache; DB is source of truth for batch-computed results.
 *
 * WHY these indexes:
 *   - idx_user_recs_user_score: Fetch top N recommendations for user, ordered by score.
 *   - UNIQUE(user_id, product_id): One recommendation per user-product pair per
 *     algorithm run. Prevents duplicates.
 */
CREATE TABLE user_recommendations (
    user_id UUID NOT NULL REFERENCES users(user_id),
    product_id UUID NOT NULL REFERENCES products(product_id),
    score DECIMAL(5,4) NOT NULL,
    algorithm VARCHAR(50) NOT NULL,
    generated_at TIMESTAMP DEFAULT NOW(),
    
    PRIMARY KEY (user_id, product_id),
    INDEX idx_user_recs_user_score (user_id, score DESC)
);

/*
 * TABLE: product_similarities
 *
 * WHY this table exists:
 *   - Precomputed "similar products" for content-based and item-based CF.
 *   - "Customers who bought X also bought Y" requires product-to-product
 *     similarity scores. Computing on-the-fly is too slow.
 *
 * WHY each foreign key/relationship:
 *   - product_id -> products: Source product for similarity.
 *   - similar_product_id -> products: Target similar product. Both must exist
 *     in catalog.
 *
 * WHY this structure over alternatives:
 *   - Storing full similarity matrix (NxN): Too large for 10M products.
 *   - Storing top-K per product: This table does exactly that. Sparse storage.
 *
 * WHY these indexes:
 *   - idx_similarities_product: Primary lookup: "Get similar products for X".
 *   - idx_similarities_product_score: Order by similarity score for top-K.
 */
CREATE TABLE product_similarities (
    product_id UUID NOT NULL REFERENCES products(product_id),
    similar_product_id UUID NOT NULL REFERENCES products(product_id),
    similarity_score DECIMAL(5,4) NOT NULL,
    algorithm VARCHAR(50) NOT NULL,
    computed_at TIMESTAMP DEFAULT NOW(),
    
    PRIMARY KEY (product_id, similar_product_id),
    INDEX idx_similarities_product_score (product_id, similarity_score DESC),
    CHECK (product_id != similar_product_id)
);
```

---

## 4. Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `RecommendationStrategy` interface, `CollaborativeFilteringStrategy`, `ContentBasedStrategy`, `TrendingStrategy` | Different algorithms (CF, content-based, trending) are interchangeable. Strategy allows runtime selection without changing client code. |
| **Factory** | `RecommenderFactory` | Creates the appropriate recommender based on context (user history, product context). Encapsulates creation logic. |
| **Observer** | `UserBehaviorObserver`, `InteractionEventPublisher` | When user interacts (view, purchase), observers (cache invalidation, real-time rec update) react. Loose coupling between behavior tracking and recommendation refresh. |
| **Builder** | `RecommendationRequest.Builder` | RecommendationRequest has many optional params (userId, productId, limit, algorithm, etc.). Builder avoids telescoping constructors. |
| **Dependency Injection** | Service constructors | Services receive dependencies via constructor. Enables testing with mocks and follows DIP. |
| **Repository** | `UserRepository`, `ProductRepository`, etc. | Abstracts data access. Services depend on interfaces, not concrete DB implementations. |

---

## 5. SOLID Principles Applied

| Principle | How |
|-----------|-----|
| **S - Single Responsibility** | `CollaborativeFilteringStrategy` only does CF logic. `RecommendationService` orchestrates, doesn't implement algorithms. |
| **O - Open/Closed** | New algorithms added by implementing `RecommendationStrategy`; no changes to existing strategy classes. |
| **L - Liskov Substitution** | Any `RecommendationStrategy` implementation can replace another in `RecommendationEngine` without breaking behavior. |
| **I - Interface Segregation** | `RecommendationStrategy` has one method `getRecommendations`. No fat interfaces. |
| **D - Dependency Inversion** | `RecommendationService` depends on `RecommendationStrategy` interface and repositories (abstractions), not concrete implementations. |

---

## 6. Code Implementation in Java

### Enums

```java
/**
 * WHY enum for InteractionType:
 *   - Type-safe: Compiler prevents invalid values (e.g., "veiw" typo).
 *   - Centralized: All valid interaction types in one place.
 *   - Extensible: Add new types (e.g., WISHLIST) without changing callers.
 */
public enum InteractionType {
    VIEW,      // Product page view - weak signal
    CLICK,     // Add to cart, click - medium signal
    PURCHASE,  // Bought - strong signal for collaborative filtering
    RATING     // Explicit rating - strongest signal
}

/**
 * WHY enum for RecommendationAlgorithm:
 *   - Matches Strategy implementations. Factory uses this to select strategy.
 *   - Persisted in user_recommendations.algorithm for analytics.
 */
public enum RecommendationAlgorithm {
    COLLABORATIVE_FILTERING,  // Users who bought X also bought Y
    CONTENT_BASED,            // Similar product attributes
    TRENDING                  // Popular in category/time window
}
```

### Model Classes

```java
/**
 * OOP: Encapsulation - private fields, public getters.
 * Immutability: No setters after construction. Safe for concurrent access.
 */
public final class Product {
    private final UUID productId;
    private final String title;
    private final UUID categoryId;
    private final String brand;
    private final BigDecimal price;
    private final double rating;
    private final Map<String, Object> features;
    private final String status;

    private Product(Builder builder) {
        this.productId = builder.productId;
        this.title = builder.title;
        this.categoryId = builder.categoryId;
        this.brand = builder.brand;
        this.price = builder.price;
        this.rating = builder.rating;
        this.features = Collections.unmodifiableMap(new HashMap<>(builder.features));
        this.status = builder.status;
    }

    public UUID getProductId() { return productId; }
    public String getTitle() { return title; }
    public UUID getCategoryId() { return categoryId; }
    public String getBrand() { return brand; }
    public BigDecimal getPrice() { return price; }
    public double getRating() { return rating; }
    public Map<String, Object> getFeatures() { return features; }
    public String getStatus() { return status; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID productId;
        private String title;
        private UUID categoryId;
        private String brand;
        private BigDecimal price;
        private double rating;
        private Map<String, Object> features = new HashMap<>();
        private String status = "active";

        public Builder productId(UUID id) { this.productId = id; return this; }
        public Builder title(String t) { this.title = t; return this; }
        public Builder categoryId(UUID c) { this.categoryId = c; return this; }
        public Builder brand(String b) { this.brand = b; return this; }
        public Builder price(BigDecimal p) { this.price = p; return this; }
        public Builder rating(double r) { this.rating = r; return this; }
        public Builder features(Map<String, Object> f) { this.features = f; return this; }
        public Builder status(String s) { this.status = s; return this; }
        public Product build() { return new Product(this); }
    }
}

/**
 * OOP: Composition - RecommendationResult contains List<ProductScore>.
 * Immutability: Unmodifiable list prevents external modification.
 */
public final class RecommendationResult {
    private final List<ProductScore> recommendations;
    private final RecommendationAlgorithm algorithm;
    private final long generatedAtMs;

    public RecommendationResult(List<ProductScore> recommendations,
                                 RecommendationAlgorithm algorithm) {
        this.recommendations = Collections.unmodifiableList(new ArrayList<>(recommendations));
        this.algorithm = algorithm;
        this.generatedAtMs = System.currentTimeMillis();
    }

    public List<ProductScore> getRecommendations() { return recommendations; }
    public RecommendationAlgorithm getAlgorithm() { return algorithm; }
    public long getGeneratedAtMs() { return generatedAtMs; }
}

public final class ProductScore {
    private final UUID productId;
    private final double score;
    private final String reason;

    public ProductScore(UUID productId, double score, String reason) {
        this.productId = productId;
        this.score = score;
        this.reason = reason;
    }
    public UUID getProductId() { return productId; }
    public double getScore() { return score; }
    public String getReason() { return reason; }
}
```

### Builder for RecommendationRequest

```java
/**
 * Builder Pattern: RecommendationRequest has many optional parameters.
 * WHY Builder: Avoids telescoping constructors and improves readability.
 * Immutability: Built once, never modified.
 */
public final class RecommendationRequest {
    private final UUID userId;
    private final UUID productId;       // For "similar products" context
    private final int limit;
    private final RecommendationAlgorithm algorithm;
    private final Set<UUID> excludeProductIds;

    private RecommendationRequest(Builder builder) {
        this.userId = builder.userId;
        this.productId = builder.productId;
        this.limit = builder.limit;
        this.algorithm = builder.algorithm;
        this.excludeProductIds = Collections.unmodifiableSet(
            new HashSet<>(builder.excludeProductIds));
    }

    public UUID getUserId() { return userId; }
    public Optional<UUID> getProductId() { return Optional.ofNullable(productId); }
    public int getLimit() { return limit; }
    public RecommendationAlgorithm getAlgorithm() { return algorithm; }
    public Set<UUID> getExcludeProductIds() { return excludeProductIds; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID userId;
        private UUID productId;
        private int limit = 20;
        private RecommendationAlgorithm algorithm = RecommendationAlgorithm.COLLABORATIVE_FILTERING;
        private Set<UUID> excludeProductIds = new HashSet<>();

        public Builder userId(UUID id) { this.userId = id; return this; }
        public Builder productId(UUID id) { this.productId = id; return this; }
        public Builder limit(int l) { this.limit = l; return this; }
        public Builder algorithm(RecommendationAlgorithm a) { this.algorithm = a; return this; }
        public Builder excludeProductIds(Set<UUID> ids) { this.excludeProductIds = ids; return this; }
        public Builder excludeProduct(UUID id) { this.excludeProductIds.add(id); return this; }
        public RecommendationRequest build() { return new RecommendationRequest(this); }
    }
}
```

### Strategy Pattern - Recommendation Algorithms

```java
/**
 * Strategy Pattern: Interface for interchangeable recommendation algorithms.
 * WHY: Open/Closed - new algorithms can be added without modifying existing code.
 */
public interface RecommendationStrategy {
    RecommendationResult getRecommendations(RecommendationRequest request);
    RecommendationAlgorithm getAlgorithm();
}

/**
 * Strategy Implementation: Collaborative Filtering.
 * "Users who bought X also bought Y" - find similar users, aggregate their purchases.
 */
public class CollaborativeFilteringStrategy implements RecommendationStrategy {
    private final UserInteractionRepository interactionRepo;
    private final ProductRepository productRepo;
    private final ProductSimilarityRepository similarityRepo;

    // SOLID (D): Depends on abstractions (repositories), not concrete DB
    public CollaborativeFilteringStrategy(UserInteractionRepository interactionRepo,
                                          ProductRepository productRepo,
                                          ProductSimilarityRepository similarityRepo) {
        this.interactionRepo = interactionRepo;
        this.productRepo = productRepo;
        this.similarityRepo = similarityRepo;
    }

    @Override
    public RecommendationResult getRecommendations(RecommendationRequest request) {
        List<UUID> userProductIds = interactionRepo.getProductIdsByUserId(
            request.getUserId(), 50);
        if (userProductIds.isEmpty()) {
            return getTrendingFallback(request);
        }
        Map<UUID, Double> scoredProducts = new HashMap<>();
        for (UUID productId : userProductIds) {
            List<ProductScore> similar = similarityRepo.getSimilarProducts(productId, 10);
            for (ProductScore ps : similar) {
                scoredProducts.merge(ps.getProductId(), ps.getScore(), Double::sum);
            }
        }
        List<ProductScore> top = scoredProducts.entrySet().stream()
            .filter(e -> !request.getExcludeProductIds().contains(e.getKey()))
            .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed())
            .limit(request.getLimit())
            .map(e -> new ProductScore(e.getKey(), e.getValue(), "similar_users"))
            .collect(Collectors.toList());
        return new RecommendationResult(top, getAlgorithm());
    }

    private RecommendationResult getTrendingFallback(RecommendationRequest request) {
        return new TrendingStrategy(productRepo).getRecommendations(request);
    }

    @Override
    public RecommendationAlgorithm getAlgorithm() {
        return RecommendationAlgorithm.COLLABORATIVE_FILTERING;
    }
}

/**
 * Strategy Implementation: Content-Based.
 * Similar products by attributes (category, brand, features).
 */
public class ContentBasedStrategy implements RecommendationStrategy {
    private final ProductRepository productRepo;
    private final ProductSimilarityRepository similarityRepo;

    public ContentBasedStrategy(ProductRepository productRepo,
                                ProductSimilarityRepository similarityRepo) {
        this.productRepo = productRepo;
        this.similarityRepo = similarityRepo;
    }

    @Override
    public RecommendationResult getRecommendations(RecommendationRequest request) {
        UUID contextProductId = request.getProductId().orElse(null);
        if (contextProductId == null) {
            return getFromUserHistory(request);
        }
        List<ProductScore> similar = similarityRepo.getSimilarProducts(
            contextProductId, request.getLimit());
        List<ProductScore> filtered = similar.stream()
            .filter(ps -> !request.getExcludeProductIds().contains(ps.getProductId()))
            .collect(Collectors.toList());
        return new RecommendationResult(filtered, getAlgorithm());
    }

    private RecommendationResult getFromUserHistory(RecommendationRequest request) {
        // Fallback: no product context - use trending
        return new TrendingStrategy(productRepo).getRecommendations(request);
    }

    @Override
    public RecommendationAlgorithm getAlgorithm() {
        return RecommendationAlgorithm.CONTENT_BASED;
    }
}

/**
 * Strategy Implementation: Trending.
 * Popular products in category, sorted by rating and recent sales.
 */
public class TrendingStrategy implements RecommendationStrategy {
    private final ProductRepository productRepo;

    public TrendingStrategy(ProductRepository productRepo) {
        this.productRepo = productRepo;
    }

    @Override
    public RecommendationResult getRecommendations(RecommendationRequest request) {
        List<Product> trending = productRepo.getTrendingProducts(request.getLimit());
        List<ProductScore> scores = trending.stream()
            .filter(p -> !request.getExcludeProductIds().contains(p.getProductId()))
            .map(p -> new ProductScore(p.getProductId(), p.getRating(), "trending"))
            .collect(Collectors.toList());
        return new RecommendationResult(scores, getAlgorithm());
    }

    @Override
    public RecommendationAlgorithm getAlgorithm() {
        return RecommendationAlgorithm.TRENDING;
    }
}
```

### Factory Pattern

```java
/**
 * Factory Pattern: Creates the appropriate RecommendationStrategy based on context.
 * WHY: Encapsulates strategy selection logic. Client doesn't need to know which
 * strategy to use. Supports algorithm selection by request or fallback rules.
 */
public class RecommenderFactory {
    private final Map<RecommendationAlgorithm, RecommendationStrategy> strategies;

    public RecommenderFactory(Map<RecommendationAlgorithm, RecommendationStrategy> strategies) {
        this.strategies = new HashMap<>(strategies);
    }

    public RecommendationStrategy getRecommender(RecommendationRequest request) {
        RecommendationStrategy strategy = strategies.get(request.getAlgorithm());
        if (strategy == null) {
            strategy = strategies.get(RecommendationAlgorithm.COLLABORATIVE_FILTERING);
        }
        return strategy;
    }
}
```

### Observer Pattern

```java
/**
 * Observer Pattern: Notify listeners when user behavior changes.
 * WHY: Loose coupling. Recommendation cache doesn't need to know about
 * interaction recording. When user purchases, we invalidate cache.
 */
public interface UserBehaviorObserver {
    void onUserInteraction(UUID userId, UUID productId, InteractionType type);
}

/**
 * Observer: Invalidates recommendation cache when user interacts.
 * Enables real-time recommendation updates.
 */
public class CacheInvalidationObserver implements UserBehaviorObserver {
    private final RecommendationCache cache;

    public CacheInvalidationObserver(RecommendationCache cache) {
        this.cache = cache;
    }

    @Override
    public void onUserInteraction(UUID userId, UUID productId, InteractionType type) {
        if (type == InteractionType.PURCHASE || type == InteractionType.RATING) {
            cache.invalidate(userId);
        }
    }
}

/**
 * Subject: Publishes interaction events to observers.
 */
public class InteractionEventPublisher {
    private final List<UserBehaviorObserver> observers = new ArrayList<>();

    public void addObserver(UserBehaviorObserver observer) {
        observers.add(observer);
    }

    public void publish(UUID userId, UUID productId, InteractionType type) {
        for (UserBehaviorObserver o : observers) {
            o.onUserInteraction(userId, productId, type);
        }
    }
}
```

### Service with Dependency Injection

```java
/**
 * RecommendationService: Orchestrates recommendation flow.
 * SOLID (S): Single responsibility - orchestration only, no algorithm logic.
 * SOLID (D): Constructor injection - depends on RecommenderFactory, Cache, Repos.
 */
public class RecommendationService {
    private final RecommenderFactory recommenderFactory;
    private final RecommendationCache cache;
    private final UserRepository userRepository;

    // Dependency Injection: All dependencies injected via constructor
    public RecommendationService(RecommenderFactory recommenderFactory,
                                 RecommendationCache cache,
                                 UserRepository userRepository) {
        this.recommenderFactory = recommenderFactory;
        this.cache = cache;
        this.userRepository = userRepository;
    }

    public RecommendationResult getRecommendations(RecommendationRequest request) {
        Optional<RecommendationResult> cached = cache.get(request.getUserId(), request.getAlgorithm());
        if (cached.isPresent()) {
            return cached.get();
        }
        RecommendationStrategy strategy = recommenderFactory.getRecommender(request);
        RecommendationResult result = strategy.getRecommendations(request);
        cache.put(request.getUserId(), request.getAlgorithm(), result);
        return result;
    }
}

/**
 * Interfaces for repositories - Dependency Inversion.
 */
public interface UserInteractionRepository {
    List<UUID> getProductIdsByUserId(UUID userId, int limit);
}

public interface ProductRepository {
    List<Product> getTrendingProducts(int limit);
}

public interface ProductSimilarityRepository {
    List<ProductScore> getSimilarProducts(UUID productId, int limit);
}

public interface RecommendationCache {
    Optional<RecommendationResult> get(UUID userId, RecommendationAlgorithm algorithm);
    void put(UUID userId, RecommendationAlgorithm algorithm, RecommendationResult result);
    void invalidate(UUID userId);
}
```

---

## 7. Edge Cases & Tests

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import java.math.BigDecimal;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RecommendationEngineTest {

    private RecommendationService recommendationService;
    private InMemoryUserInteractionRepository interactionRepo;
    private InMemoryProductRepository productRepo;
    private InMemoryProductSimilarityRepository similarityRepo;
    private InMemoryRecommendationCache cache;

    @BeforeEach
    void setUp() {
        interactionRepo = new InMemoryUserInteractionRepository();
        productRepo = new InMemoryProductRepository();
        similarityRepo = new InMemoryProductSimilarityRepository();
        cache = new InMemoryRecommendationCache();

        Map<RecommendationAlgorithm, RecommendationStrategy> strategies = Map.of(
            RecommendationAlgorithm.COLLABORATIVE_FILTERING,
                new CollaborativeFilteringStrategy(interactionRepo, productRepo, similarityRepo),
            RecommendationAlgorithm.CONTENT_BASED,
                new ContentBasedStrategy(productRepo, similarityRepo),
            RecommendationAlgorithm.TRENDING,
                new TrendingStrategy(productRepo)
        );
        RecommenderFactory factory = new RecommenderFactory(strategies);
        recommendationService = new RecommendationService(factory, cache, new InMemoryUserRepository());
    }

    @Test
    @DisplayName("Cold start: New user with no interactions gets trending products")
    void coldStart_NewUser_ReturnsTrendingProducts() {
        UUID newUserId = UUID.randomUUID();
        productRepo.addProduct(createProduct("P1", "Electronics"));
        productRepo.addProduct(createProduct("P2", "Electronics"));

        RecommendationRequest request = RecommendationRequest.builder()
            .userId(newUserId)
            .algorithm(RecommendationAlgorithm.COLLABORATIVE_FILTERING)
            .limit(5)
            .build();

        RecommendationResult result = recommendationService.getRecommendations(request);

        assertFalse(result.getRecommendations().isEmpty());
        assertEquals(RecommendationAlgorithm.TRENDING, result.getAlgorithm());
    }

    @Test
    @DisplayName("Similar products: Product context returns content-based recommendations")
    void similarProducts_WithProductId_ReturnsContentBasedRecs() {
        UUID productId = UUID.randomUUID();
        UUID similarId = UUID.randomUUID();
        similarityRepo.addSimilarity(productId, similarId, 0.95);

        RecommendationRequest request = RecommendationRequest.builder()
            .userId(UUID.randomUUID())
            .productId(productId)
            .algorithm(RecommendationAlgorithm.CONTENT_BASED)
            .limit(10)
            .build();

        RecommendationResult result = recommendationService.getRecommendations(request);

        assertTrue(result.getRecommendations().stream()
            .anyMatch(ps -> ps.getProductId().equals(similarId)));
        assertEquals(RecommendationAlgorithm.CONTENT_BASED, result.getAlgorithm());
    }

    @Test
    @DisplayName("Exclude products: Requested exclusions are not in results")
    void excludeProducts_RequestExcludes_NotInResults() {
        UUID excludeId = UUID.randomUUID();
        productRepo.addProduct(createProduct(excludeId, "P1", "Electronics"));
        productRepo.addProduct(createProduct("P2", "Electronics"));

        RecommendationRequest request = RecommendationRequest.builder()
            .userId(UUID.randomUUID())
            .algorithm(RecommendationAlgorithm.TRENDING)
            .limit(10)
            .excludeProduct(excludeId)
            .build();

        RecommendationResult result = recommendationService.getRecommendations(request);

        assertTrue(result.getRecommendations().stream()
            .noneMatch(ps -> ps.getProductId().equals(excludeId)));
    }

    @Test
    @DisplayName("Cache hit: Second request returns cached result")
    void cacheHit_SecondRequest_ReturnsCachedResult() {
        UUID userId = UUID.randomUUID();
        RecommendationRequest request = RecommendationRequest.builder()
            .userId(userId)
            .algorithm(RecommendationAlgorithm.TRENDING)
            .limit(5)
            .build();

        RecommendationResult first = recommendationService.getRecommendations(request);
        RecommendationResult second = recommendationService.getRecommendations(request);

        assertEquals(first.getGeneratedAtMs(), second.getGeneratedAtMs());
        assertEquals(first.getRecommendations().size(), second.getRecommendations().size());
    }

    @Test
    @DisplayName("Empty catalog: No products returns empty recommendations")
    void emptyCatalog_NoProducts_ReturnsEmptyList() {
        productRepo.clear();
        RecommendationRequest request = RecommendationRequest.builder()
            .userId(UUID.randomUUID())
            .algorithm(RecommendationAlgorithm.TRENDING)
            .limit(5)
            .build();

        RecommendationResult result = recommendationService.getRecommendations(request);

        assertTrue(result.getRecommendations().isEmpty());
    }

    @Test
    @DisplayName("Observer: Purchase invalidates cache")
    void observer_Purchase_InvalidatesCache() {
        UUID userId = UUID.randomUUID();
        cache.put(userId, RecommendationAlgorithm.COLLABORATIVE_FILTERING,
            new RecommendationResult(List.of(), RecommendationAlgorithm.COLLABORATIVE_FILTERING));

        CacheInvalidationObserver observer = new CacheInvalidationObserver(cache);
        observer.onUserInteraction(userId, UUID.randomUUID(), InteractionType.PURCHASE);

        assertTrue(cache.get(userId, RecommendationAlgorithm.COLLABORATIVE_FILTERING).isEmpty());
    }

    @Test
    @DisplayName("Limit respected: Result size does not exceed request limit")
    void limitRespected_RequestLimit5_ReturnsAtMost5() {
        for (int i = 0; i < 10; i++) {
            productRepo.addProduct(createProduct("P" + i, "Cat"));
        }
        RecommendationRequest request = RecommendationRequest.builder()
            .userId(UUID.randomUUID())
            .algorithm(RecommendationAlgorithm.TRENDING)
            .limit(5)
            .build();

        RecommendationResult result = recommendationService.getRecommendations(request);

        assertTrue(result.getRecommendations().size() <= 5);
    }

    @Test
    @DisplayName("Builder: RecommendationRequest builds correctly with optional fields")
    void builder_OptionalFields_DefaultsApplied() {
        UUID userId = UUID.randomUUID();
        RecommendationRequest request = RecommendationRequest.builder()
            .userId(userId)
            .build();

        assertEquals(userId, request.getUserId());
        assertTrue(request.getProductId().isEmpty());
        assertEquals(20, request.getLimit());
        assertEquals(RecommendationAlgorithm.COLLABORATIVE_FILTERING, request.getAlgorithm());
    }

    // Helper methods
    private Product createProduct(String title, String category) {
        return createProduct(UUID.randomUUID(), title, category);
    }
    private Product createProduct(UUID id, String title, String category) {
        return Product.builder()
            .productId(id)
            .title(title)
            .categoryId(UUID.randomUUID())
            .price(BigDecimal.TEN)
            .rating(4.5)
            .build();
    }
}
```

---

## 8. Summary

| Category | Items |
|----------|-------|
| **Design Patterns** | Strategy (algorithms), Factory (recommender creation), Observer (behavior → cache invalidation), Builder (RecommendationRequest), Repository (data access), Dependency Injection |
| **SOLID** | S: Strategy per algorithm; O: New strategies without modifying existing; L: Strategies interchangeable; I: Lean interfaces; D: Services depend on abstractions |
| **OOP Concepts** | Encapsulation (private fields), Composition (RecommendationResult contains ProductScore list), Immutability (final fields, unmodifiable collections) |
| **DB Tables** | users, products, user_interactions, user_recommendations, product_similarities |
| **Key Algorithms** | CollaborativeFilteringStrategy, ContentBasedStrategy, TrendingStrategy |
