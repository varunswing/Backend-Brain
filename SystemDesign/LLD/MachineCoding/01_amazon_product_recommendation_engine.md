# Amazon Product Recommendation Engine - System Design Interview

## Problem Statement
*"Design a product recommendation system like Amazon's 'Customers who bought this also bought' or 'Recommended for you' features. The system should provide personalized product recommendations to millions of users in real-time."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What types of recommendations do we need?" → Personalized recommendations, similar products, trending products, cold-start for new users
- "What recommendation algorithms should we support?" → Collaborative filtering, content-based, hybrid approaches
- "Do we need real-time recommendations?" → Yes, recommendations should update based on recent user behavior
- "What platforms need recommendations?" → Web, mobile apps, email campaigns, push notifications
- "Do we need A/B testing capabilities?" → Yes, to test different algorithms and recommendation strategies

**Non-Functional Requirements:**
- "What's our user scale?" → 100M+ active users, 10M+ products in catalog
- "What's the expected latency?" → <100ms for real-time recommendations
- "What's our availability requirement?" → 99.9% uptime (recommendations are critical for revenue)
- "Consistency requirements?" → Eventual consistency is acceptable for recommendations
- "How many recommendation requests per second?" → 50K+ peak RPS during sales events

### Requirements Summary:
- **Users**: 100M+ active users globally
- **Products**: 10M+ products with rich metadata
- **Recommendations**: Personalized, similar products, trending items
- **Latency**: <100ms response time
- **Throughput**: 50K+ RPS peak load
- **Algorithms**: Collaborative filtering, content-based, hybrid ML models

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Daily Active Users: 50M
Actions per user per day: 20 (views, clicks, purchases)
Peak traffic multiplier: 3x during sales
Peak RPS = (50M × 20 × 3) / 86,400 = ~35K RPS
Recommendation requests = 70% of total = ~25K RPS
```

### Storage Estimation:
```
User profiles: 100M users × 2KB = 200GB
Product catalog: 10M products × 5KB = 50GB
User interactions: 50M × 20 × 500 bytes × 30 days = 15TB
ML model features: 100M × 1KB = 100GB
Precomputed recommendations: 50M × 100 recs × 50 bytes = 250GB
Total storage: ~16TB (with indexes ~20TB)
```

### Memory Requirements:
```
Active user sessions: 5M concurrent × 1KB = 5GB
Hot product data: 1M popular products × 5KB = 5GB
Recommendation cache: 10M × 100 recs × 50 bytes = 50GB
ML model serving: 20GB for models in memory
Total memory: ~80GB Redis cluster
```

---

## Phase 3: High-Level Architecture (12 minutes)

### Step 1: Simple Architecture
```
[Client Apps] → [Load Balancer] → [Recommendation API] → [ML Models] → [Database]
```

### Step 2: Complete Architecture

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Mobile Apps  │  │Web Client   │  │Admin Panel  │
│- iOS/Android│  │- React SPA  │  │- ML Ops     │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│                API Gateway                      │
│- Authentication  - Rate Limiting  - Routing    │
└─────────────────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│Recommendation│ │User Behavior │ │Product       │
│   Service    │ │   Service    │ │ Catalog      │
│              │ │              │ │ Service      │
│- Real-time   │ │- Event Stream│ │- Product     │
│- Batch Recs  │ │- User Profile│ │  Info        │
│- A/B Testing │ │- Interactions│ │- Metadata    │
└──────────────┘ └──────────────┘ └──────────────┘
        │               │               │
        └───────────────┼───────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│          ML Model Service                       │
│                                                 │
│ ┌─────────────┐  ┌─────────────┐  ┌──────────┐  │
│ │Collaborative│  │Content-Based│  │ Feature  │  │
│ │ Filtering   │  │  Algorithm  │  │  Store   │  │
│ │             │  │             │  │          │  │
│ │- User-Item  │  │- Product    │  │- User    │  │
│ │  Matrix     │  │  Similarity │  │ Features │  │
│ │- Deep       │  │- Category   │  │- Product │  │
│ │ Learning    │  │  Matching   │  │ Features │  │
│ └─────────────┘  └─────────────┘  └──────────┘  │
└─────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│                 Data Layer                      │
│                                                 │
│ ┌─────────────┐  ┌─────────────┐  ┌──────────┐  │
│ │ PostgreSQL  │  │    Redis    │  │  Kafka   │  │
│ │- Users      │  │- Hot Recs   │  │- Events  │  │
│ │- Products   │  │- User Cache │  │- Stream  │  │
│ │- Interactions│ │- Features   │  │- ML Jobs │  │
│ └─────────────┘  └─────────────┘  └──────────┘  │
└─────────────────────────────────────────────────┘
```

### Key Components:
- **Recommendation Service**: Core business logic for generating recommendations
- **ML Model Service**: Serves trained models (collaborative filtering, content-based)
- **User Behavior Service**: Tracks user interactions and builds profiles
- **Product Catalog Service**: Manages product information and metadata
- **Feature Store**: Centralized features for ML models

---

## Phase 4: Database Design (8 minutes)

### Core Entities:
- Users (profiles, preferences, demographics)
- Products (catalog, metadata, categories)
- Interactions (clicks, views, purchases, ratings)
- Recommendations (precomputed, cached results)

### PostgreSQL Schema:

```sql
-- Users table
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    demographics JSONB DEFAULT '{}',       -- age, gender, location
    preferences JSONB DEFAULT '{}',        -- categories, brands, price range
    last_active TIMESTAMP,
    
    INDEX idx_users_last_active (last_active)
);

-- Products table
CREATE TABLE products (
    product_id UUID PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    category_id UUID NOT NULL,
    brand VARCHAR(100),
    price DECIMAL(10,2),
    rating DECIMAL(2,1) DEFAULT 0,
    features JSONB DEFAULT '{}',           -- color, size, material
    status VARCHAR(20) DEFAULT 'active',
    
    INDEX idx_products_category (category_id),
    INDEX idx_products_price (price),
    INDEX idx_products_rating (rating DESC)
);

-- User interactions (critical for ML)
CREATE TABLE user_interactions (
    interaction_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(user_id),
    product_id UUID REFERENCES products(product_id),
    interaction_type VARCHAR(20) NOT NULL,  -- view, click, purchase, rating
    interaction_value INTEGER,              -- rating 1-5, quantity
    timestamp TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_interactions_user_time (user_id, timestamp DESC),
    INDEX idx_interactions_product_time (product_id, timestamp DESC)
);

-- Precomputed recommendations
CREATE TABLE user_recommendations (
    user_id UUID REFERENCES users(user_id),
    product_id UUID REFERENCES products(product_id),
    score DECIMAL(5,4) NOT NULL,          -- recommendation confidence
    algorithm VARCHAR(50) NOT NULL,       -- which algorithm generated this
    generated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_user_recs (user_id, score DESC),
    UNIQUE(user_id, product_id)
);
```

### Redis Caching Strategy:

```javascript
// Hot user recommendations
"user_recs:{user_id}": [
  {"product_id": "uuid", "score": 0.95, "reason": "similar_users"},
  {"product_id": "uuid", "score": 0.87, "reason": "same_category"}
]

// User features for ML
"user_features:{user_id}": {
  "category_preferences": {"electronics": 0.8, "books": 0.3},
  "recent_interactions": [...]
}

// Product similarity cache
"similar_products:{product_id}": [
  {"product_id": "uuid", "similarity": 0.92}
]
```

### Design Decisions:
- **UUIDs**: Better for distributed systems and sharding
- **JSONB**: Flexible schema for evolving product features
- **Time-based indexing**: Optimized for recent interaction queries
- **Composite indexes**: Fast user timeline and product popularity queries

---

## Phase 5: Critical Flow - Generate Recommendations (8 minutes)

### Most Critical Flow: Real-time Recommendation Generation

**1. Request Processing**
```
User visits homepage → API Gateway → Recommendation Service

1. Check Redis cache for user recommendations
2. If cache miss, generate recommendations using ML models
3. Cache results and return to user
```

**2. ML Model Inference**
```
For cache miss, ML Model Service:

1. Fetch user features (demographics, interaction history)
2. Run parallel inference:
   - Collaborative filtering: Find similar users → their liked products
   - Content-based: User preferences → similar product features
   - Popularity: Trending products in user's categories
3. Combine scores using weighted ensemble
4. Return top 50 scored products
```

**3. Recommendation Assembly**
```
Recommendation Service:

1. Filter out-of-stock and inappropriate products
2. Apply business rules (promotions, margins)
3. Diversify results (different categories, price ranges)
4. Cache final recommendations in Redis (30-min TTL)
5. Return top 20 with metadata and explanations
```

### Technical Challenges:

**Cold Start Problem:**
- "New users get popular products in their demographic/location"
- "Quick onboarding questionnaire to gather initial preferences"

**Real-time Updates:**
- "Kafka streams update user features as they browse"
- "Cache invalidation on significant actions (purchase, high rating)"

**Performance:**
- "ML inference <50ms using TensorFlow Serving"
- "Cache hit rate >90% for returning users"

**Scalability:**
- "Horizontal scaling of ML model servers"
- "Precompute recommendations for inactive users in batch jobs"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **ML Model Inference**: CPU-intensive scoring at scale
2. **User Interaction Writes**: High write volume during peak traffic
3. **Recommendation Cache**: Memory limits for millions of cached recommendations

### Scaling Solutions:

**ML Scaling:**
- Horizontal scaling with TensorFlow Serving auto-scaling
- Model optimization: quantization and caching
- Batch processing for inactive users

**Database Scaling:**
- Read replicas for user/product data
- Sharding interactions table by user_id
- Time-based partitioning for old interactions

**Cache Scaling:**
- Redis cluster with consistent hashing
- Multi-layer cache: app-level → Redis → database
- Smart cache eviction based on user activity

**Geographic Distribution:**
- Regional ML model serving
- Local feature stores
- CDN for popular recommendations

### Trade-offs:
- **Accuracy vs. Latency**: Real-time vs. precomputed recommendations
- **Personalization vs. Performance**: Complex models vs. simple collaborative filtering
- **Storage vs. Compute**: Cache everything vs. compute on-demand

---

## Success Metrics:
- **Click-through Rate**: >3% on recommendations
- **Conversion Rate**: >1.5% from recommendations to purchases  
- **P95 Latency**: <100ms for recommendation API
- **Cache Hit Rate**: >90% for returning users
- **Revenue Impact**: 15%+ of revenue from recommendations

**🎯 This design showcases ML systems, real-time processing, and handling massive scale while maintaining sub-100ms performance.**
