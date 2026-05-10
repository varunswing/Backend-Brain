# URL Shortener Service - LLD / Machine Coding Interview

## 1. Problem Statement

Design a URL shortening service (like TinyURL or Bit.ly) that converts long URLs into compact short links, redirects users to the original destination, and tracks basic analytics. The system must handle hash collisions, support multiple ID generation strategies, and manage URL expiration policies.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR1 | Shorten a long URL and return a short code | Must |
| FR2 | Redirect short code to original long URL (301/302) | Must |
| FR3 | Support custom short codes (aliases) | Should |
| FR4 | Track click count and access analytics | Should |
| FR5 | Support URL expiration (never, time-based, access-count) | Should |
| FR6 | User accounts for link management | Could |
| FR7 | Custom domains per user | Could |

### Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR1 | Redirect latency | < 100ms (P95) |
| NFR2 | Shorten latency | < 200ms |
| NFR3 | Handle hash collisions | Retry with alternative strategy |
| NFR4 | Scalability | Read-heavy (100:1 read:write) |
| NFR5 | Availability | 99.9% uptime |

---

## 3. Database Design with Explanations

### Why Base62 Encoding?

- **Character set**: `[a-zA-Z0-9]` = 62 chars → URL-safe, no special chars
- **Compactness**: 6 chars = 62^6 ≈ 56B combinations; 7 chars ≈ 3.5T
- **Readability**: Shorter than hex (Base16) for same numeric range
- **Collision-free**: When backed by unique ID (counter/snowflake), no collisions

### Why Separate Analytics Table?

- **Write amplification**: Clicks far exceed creations (100:1); separate table avoids locking main `urls` table
- **Query isolation**: Analytics queries (aggregations, time-series) don't slow redirect lookups
- **Retention**: Can archive/delete old analytics without touching URL mappings
- **Schema flexibility**: Analytics schema can evolve independently (geo, device, referrer)

### SQL Schema

```sql
-- WHY: Core mapping of short_code → long_url. Primary lookup for redirects.
-- WHY short_code PK: Direct lookup by short code (most common operation).
CREATE TABLE urls (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,  -- WHY: Surrogate key for FK, internal use
    short_code      VARCHAR(20) NOT NULL UNIQUE,        -- WHY UNIQUE: One short code maps to one URL
    long_url        TEXT NOT NULL,                      -- Original URL (TEXT for long URLs)
    user_id         BIGINT NULL,                        -- WHY NULL: Anonymous URLs allowed
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE, EXPIRED, DISABLED
    expires_at      TIMESTAMP NULL,                     -- NULL = never expires
    access_count_limit INT NULL,                        -- Expire after N clicks (NULL = no limit)
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    -- WHY FK: Referential integrity; cascade rules for user deletion
    CONSTRAINT fk_urls_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL,
    INDEX idx_short_code (short_code),                  -- WHY: Primary lookup (may be covered by UNIQUE)
    INDEX idx_user_created (user_id, created_at DESC)   -- WHY: User dashboard "my links" queries
) ENGINE=InnoDB;

-- WHY: Decoupled analytics for high-volume click tracking.
-- WHY separate table: Avoids write contention on urls; enables time-series queries.
CREATE TABLE url_analytics (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    short_code      VARCHAR(20) NOT NULL,
    accessed_at     TIMESTAMP NOT NULL,
    ip_address      VARCHAR(45) NULL,                   -- IPv6 support
    user_agent      VARCHAR(512) NULL,
    referer         VARCHAR(512) NULL,

    -- WHY FK: Ensure we only track valid short codes; optional for performance at scale
    CONSTRAINT fk_analytics_url FOREIGN KEY (short_code) REFERENCES urls(short_code) ON DELETE CASCADE,
    INDEX idx_short_code_date (short_code, accessed_at),  -- WHY: Time-range analytics per URL
    INDEX idx_accessed_at (accessed_at)                  -- WHY: Retention/cleanup by date
) ENGINE=InnoDB;

-- WHY: User accounts for link ownership, rate limits, custom domains.
CREATE TABLE users (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    email           VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_email (email)
) ENGINE=InnoDB;

-- WHY: Allow users to use their own domain (e.g., go.company.com instead of bit.ly).
-- WHY separate table: Many-to-one (many domains per user); flexible for enterprise.
CREATE TABLE custom_domains (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         BIGINT NOT NULL,
    domain          VARCHAR(255) NOT NULL,               -- e.g., go.company.com
    is_verified     BOOLEAN DEFAULT FALSE,              -- DNS/ownership verification
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_custom_domains_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    UNIQUE KEY uk_domain (domain),                      -- WHY: One domain per account
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB;
```

---

## 4. Design Patterns

| Pattern | Where Used | Purpose |
|---------|------------|---------|
| **Strategy** | `IdGenerationStrategy` (Base62, MD5, Snowflake, NanoId) | Swap ID generation algorithm without changing client code; supports collision fallback |
| **Strategy** | `ExpirationStrategy` (Never, TimeBased, AccessCount) | Encapsulate expiration logic; add new policies without modifying core service |
| **Factory** | `UrlShortenerFactory` | Create configured `UrlShortenerService` with injected strategies; centralize wiring |
| **Observer** | `UrlAccessObserver` (analytics, click count) | Decouple redirect logic from analytics; add listeners without changing redirect flow |
| **Singleton** | Cache / ID generator (e.g., Snowflake) | Single instance for counter/state to avoid duplicates |

---

## 5. SOLID Principles

| Principle | Application |
|-----------|-------------|
| **S**ingle Responsibility | `UrlShortenerService` orchestrates; `IdGenerationStrategy` generates IDs; `ExpirationStrategy` checks expiry; `UrlAccessObserver` tracks analytics |
| **O**pen/Closed | New ID strategies (e.g., UUID) or expiration policies added by implementing interfaces, not modifying existing code |
| **L**iskov Substitution | Any `IdGenerationStrategy` or `ExpirationStrategy` implementation can replace another without breaking behavior |
| **I**nterface Segregation | Small interfaces: `IdGenerationStrategy`, `ExpirationStrategy`, `UrlAccessObserver` — clients depend only on what they need |
| **D**ependency Inversion | `UrlShortenerService` depends on abstractions (interfaces), not concrete ID/expiration implementations |

---

## 6. Code Implementation in Java

### 6.1 Enums

```java
/**
 * WHY Enum: Type-safe status; prevents invalid values.
 * ACTIVE = redirect works; EXPIRED = past expiry; DISABLED = manually turned off.
 */
public enum UrlStatus {
    ACTIVE,
    EXPIRED,
    DISABLED
}
```

### 6.2 Models with Encapsulation

```java
import java.time.Instant;

/**
 * WHY encapsulation: ShortUrl owns expiry logic; callers use isExpired() instead of
 * duplicating checks. Expiry can be time-based OR access-count-based.
 */
public class ShortUrl {
    private final String shortCode;
    private final String longUrl;
    private final Long userId;
    private UrlStatus status;
    private final Instant expiresAt;
    private final Integer accessCountLimit;
    private int accessCount;
    private final Instant createdAt;

    public ShortUrl(String shortCode, String longUrl, Long userId,
                    Instant expiresAt, Integer accessCountLimit, int accessCount, Instant createdAt) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.userId = userId;
        this.status = UrlStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.accessCountLimit = accessCountLimit;
        this.accessCount = accessCount;
        this.createdAt = createdAt;
    }

    public String getShortCode() { return shortCode; }
    public String getLongUrl() { return longUrl; }
    public Long getUserId() { return userId; }
    public UrlStatus getStatus() { return status; }
    public void setStatus(UrlStatus status) { this.status = status; }
    public int getAccessCount() { return accessCount; }

    /** WHY: Centralized expiry check — supports both time and access-count. */
    public boolean isExpired(ExpirationStrategy strategy) {
        return strategy.isExpired(this);
    }

    public void incrementAccessCount() {
        this.accessCount++;
    }

    public Instant getExpiresAt() { return expiresAt; }
    public Integer getAccessCountLimit() { return accessCountLimit; }
}
```

### 6.3 Strategy: IdGenerationStrategy

```java
/**
 * STRATEGY PATTERN: Swap ID generation algorithms.
 * WHY: Different strategies for scale (Snowflake), collision resistance (NanoId),
 * or determinism (MD5 for same URL → same short code).
 */
public interface IdGenerationStrategy {
    String generate(String longUrl, String customAlias);
}

/** WHY Base62: Compact, URL-safe. Uses counter/snowflake for uniqueness. */
public class Base62EncodingStrategy implements IdGenerationStrategy {
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    @Override
    public String generate(String longUrl, String customAlias) {
        if (customAlias != null && !customAlias.isEmpty()) return customAlias;
        long id = System.nanoTime(); // In production: use distributed counter or Snowflake
        return toBase62(id);
    }

    /** WHY static: Reusable by MD5HashStrategy, SnowflakeIdStrategy. */
    public static String toBase62(long num) {
        if (num == 0) return String.valueOf(BASE62.charAt(0));
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.append(BASE62.charAt((int) (num % 62)));
            num /= 62;
        }
        return sb.reverse().toString();
    }
}

/** WHY MD5: Same long URL → same short code (idempotent). Collision risk; use with retry. */
public class MD5HashStrategy implements IdGenerationStrategy {
    @Override
    public String generate(String longUrl, String customAlias) {
        if (customAlias != null && !customAlias.isEmpty()) return customAlias;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(longUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            long num = Math.abs(java.nio.ByteBuffer.wrap(hash).getLong());
            String encoded = Base62EncodingStrategy.toBase62(num);
            return encoded.length() >= 7 ? encoded.substring(0, 7) : encoded;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}

/** WHY Snowflake: Distributed unique IDs; no collision. */
public class SnowflakeIdStrategy implements IdGenerationStrategy {
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    @Override
    public String generate(String longUrl, String customAlias) {
        if (customAlias != null && !customAlias.isEmpty()) return customAlias;
        long id = nextId();
        return Base62EncodingStrategy.toBase62(id);
    }

    private synchronized long nextId() {
        long ts = System.currentTimeMillis();
        if (ts == lastTimestamp) sequence++;
        else { lastTimestamp = ts; sequence = 0; }
        return (ts << 22) | sequence;
    }
}

/** WHY NanoId: Cryptographically random; very low collision probability. */
public class NanoIdStrategy implements IdGenerationStrategy {
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    @Override
    public String generate(String longUrl, String customAlias) {
        if (customAlias != null && !customAlias.isEmpty()) return customAlias;
        StringBuilder sb = new StringBuilder(7);
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < 7; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(62)));
        }
        return sb.toString();
    }
}
```

### 6.4 Strategy: ExpirationStrategy

```java
/**
 * STRATEGY PATTERN: Encapsulate expiration rules.
 * WHY: Add NeverExpire, TimeBased, AccessCount without changing ShortUrl or service.
 */
public interface ExpirationStrategy {
    boolean isExpired(ShortUrl url);
}

public class NeverExpireStrategy implements ExpirationStrategy {
    @Override
    public boolean isExpired(ShortUrl url) {
        return url.getStatus() == UrlStatus.DISABLED;
    }
}

public class TimeBasedExpiryStrategy implements ExpirationStrategy {
    @Override
    public boolean isExpired(ShortUrl url) {
        if (url.getStatus() == UrlStatus.DISABLED) return true;
        return url.getExpiresAt() != null && Instant.now().isAfter(url.getExpiresAt());
    }
}

public class AccessCountExpiryStrategy implements ExpirationStrategy {
    @Override
    public boolean isExpired(ShortUrl url) {
        if (url.getStatus() == UrlStatus.DISABLED) return true;
        Integer limit = url.getAccessCountLimit();
        return limit != null && url.getAccessCount() >= limit;
    }
}
```

### 6.5 Observer: UrlAccessObserver

```java
/**
 * OBSERVER PATTERN: Decouple redirect from analytics/click counting.
 * WHY: Add new observers (fraud detection, logging) without modifying redirect logic.
 */
public interface UrlAccessObserver {
    void onUrlAccessed(ShortUrl url, String ipAddress, String userAgent, String referer);
}

/** Observer: Persist analytics events. */
public class AnalyticsTrackingObserver implements UrlAccessObserver {
    private final UrlAnalyticsRepository analyticsRepo;

    public AnalyticsTrackingObserver(UrlAnalyticsRepository analyticsRepo) {
        this.analyticsRepo = analyticsRepo;
    }

    @Override
    public void onUrlAccessed(ShortUrl url, String ipAddress, String userAgent, String referer) {
        analyticsRepo.recordAccess(url.getShortCode(), ipAddress, userAgent, referer);
    }
}

/** Observer: Increment click count (can be async in production). */
public class ClickCountingObserver implements UrlAccessObserver {
    private final UrlRepository urlRepo;

    public ClickCountingObserver(UrlRepository urlRepo) {
        this.urlRepo = urlRepo;
    }

    @Override
    public void onUrlAccessed(ShortUrl url, String ipAddress, String userAgent, String referer) {
        url.incrementAccessCount();
        urlRepo.updateAccessCount(url.getShortCode(), url.getAccessCount());
    }
}
```

### 6.6 Factory: UrlShortenerFactory

```java
/**
 * FACTORY PATTERN: Centralize creation of UrlShortenerService with correct dependencies.
 * WHY: Single place to wire strategies and observers; easy to create different configs.
 */
public class UrlShortenerFactory {
    public static UrlShortenerService createDefault() {
        IdGenerationStrategy idStrategy = new Base62EncodingStrategy();
        ExpirationStrategy expiryStrategy = new TimeBasedExpiryStrategy();
        List<UrlAccessObserver> observers = List.of(
            new ClickCountingObserver(new UrlRepositoryImpl()),
            new AnalyticsTrackingObserver(new UrlAnalyticsRepositoryImpl())
        );
        return new UrlShortenerService(idStrategy, expiryStrategy, observers, new UrlRepositoryImpl());
    }

    public static UrlShortenerService createWithCollisionFallback() {
        // Primary: Base62; fallback: NanoId on collision
        IdGenerationStrategy idStrategy = new CollisionAwareIdStrategy(
            new Base62EncodingStrategy(),
            new NanoIdStrategy()
        );
        return createWithStrategy(idStrategy);
    }

    private static UrlShortenerService createWithStrategy(IdGenerationStrategy idStrategy) {
        return new UrlShortenerService(idStrategy,
            new TimeBasedExpiryStrategy(),
            List.of(new ClickCountingObserver(new UrlRepositoryImpl())),
            new UrlRepositoryImpl());
    }
}
```

### 6.7 UrlShortenerService (Orchestrator with DI)

```java
import java.util.List;

/**
 * Orchestrator with Dependency Injection.
 * WHY DI: Testable (mock repos/strategies); Open/Closed for new strategies.
 */
public class UrlShortenerService {
    private final IdGenerationStrategy idStrategy;
    private final ExpirationStrategy expirationStrategy;
    private final List<UrlAccessObserver> observers;
    private final UrlRepository urlRepo;
    private final int maxCollisionRetries = 3;

    public UrlShortenerService(IdGenerationStrategy idStrategy,
                               ExpirationStrategy expirationStrategy,
                               List<UrlAccessObserver> observers,
                               UrlRepository urlRepo) {
        this.idStrategy = idStrategy;
        this.expirationStrategy = expirationStrategy;
        this.observers = observers;
        this.urlRepo = urlRepo;
    }

    /**
     * Shorten URL with hash collision handling.
     * WHY retry: MD5/Base62 can collide; retry with new ID or fallback strategy.
     */
    public String shorten(String longUrl, String customAlias, Long userId) {
        validateUrl(longUrl);
        String shortCode = null;
        for (int i = 0; i < maxCollisionRetries; i++) {
            shortCode = idStrategy.generate(longUrl, customAlias);
            if (urlRepo.findByShortCode(shortCode).isEmpty()) {
                break;
            }
            if (customAlias != null) {
                throw new IllegalArgumentException("Custom alias already taken: " + customAlias);
            }
            shortCode = null; // Collision; retry
        }
        if (shortCode == null) throw new RuntimeException("Failed to generate unique short code after retries");

        ShortUrl shortUrl = new ShortUrl(shortCode, longUrl, userId, null, null, 0, java.time.Instant.now());
        urlRepo.save(shortUrl);
        return shortCode;
    }

    /**
     * Redirect: lookup, check expiry, notify observers, return long URL.
     * WHY cache: In production, cache shortCode→longUrl for <100ms latency.
     */
    public String redirect(String shortCode, String ipAddress, String userAgent, String referer) {
        ShortUrl url = urlRepo.findByShortCode(shortCode)
            .orElseThrow(() -> new IllegalArgumentException("Short code not found: " + shortCode));

        if (url.isExpired(expirationStrategy)) {
            throw new IllegalStateException("URL expired or disabled");
        }

        // Notify observers (analytics, click count) — can be async
        for (UrlAccessObserver o : observers) {
            o.onUrlAccessed(url, ipAddress, userAgent, referer);
        }

        return url.getLongUrl();
    }

    private void validateUrl(String longUrl) {
        if (longUrl == null || longUrl.isBlank()) throw new IllegalArgumentException("URL cannot be empty");
        if (!longUrl.startsWith("http://") && !longUrl.startsWith("https://")) {
            throw new IllegalArgumentException("URL must start with http:// or https://");
        }
    }
}
```

### 6.8 Collision Handling & Redirect Caching

```java
/**
 * Hash collision handling: Primary strategy + fallback.
 * WHY: Base62/MD5 can collide; NanoId/Snowflake as fallback ensures success.
 */
public class CollisionAwareIdStrategy implements IdGenerationStrategy {
    private final IdGenerationStrategy primary;
    private final IdGenerationStrategy fallback;

    public CollisionAwareIdStrategy(IdGenerationStrategy primary, IdGenerationStrategy fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public String generate(String longUrl, String customAlias) {
        return primary.generate(longUrl, customAlias);
    }

    public String generateWithFallback(String longUrl, String customAlias, boolean collisionDetected) {
        return collisionDetected ? fallback.generate(longUrl, customAlias) : primary.generate(longUrl, customAlias);
    }
}
```

```java
/**
 * Redirect caching: In-memory or Redis cache for shortCode → longUrl.
 * WHY: Redirects are read-heavy; cache reduces DB load and latency to <100ms.
 */
public class CachedUrlRepository implements UrlRepository {
    private final UrlRepository delegate;
    private final java.util.Map<String, ShortUrl> cache = new java.util.concurrent.ConcurrentHashMap<>();

    public CachedUrlRepository(UrlRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public java.util.Optional<ShortUrl> findByShortCode(String shortCode) {
        return java.util.Optional.ofNullable(cache.computeIfAbsent(shortCode,
            k -> delegate.findByShortCode(k).orElse(null)));
    }

    @Override
    public void save(ShortUrl url) {
        delegate.save(url);
        cache.put(url.getShortCode(), url);
    }
}
```

### 6.9 Repository Interfaces (Stubs)

```java
public interface UrlRepository {
    java.util.Optional<ShortUrl> findByShortCode(String shortCode);
    void save(ShortUrl url);
    void updateAccessCount(String shortCode, int count);
}

public interface UrlAnalyticsRepository {
    void recordAccess(String shortCode, String ip, String userAgent, String referer);
}
```

---

## 7. Edge Cases & Tests

| # | Edge Case | Expected Behavior | Test |
|---|-----------|-------------------|------|
| 1 | Empty or invalid URL | Reject with `IllegalArgumentException` | `shorten("", null, null)` → exception |
| 2 | Custom alias already taken | Reject with clear error | `shorten(url, "taken", userId)` when "taken" exists → exception |
| 3 | Hash collision (same short code generated) | Retry with new ID or fallback strategy | Mock repo to return existing for first 2 calls → third succeeds |
| 4 | Expired URL redirect | Return error / custom expiry page | `redirect("expired_code")` → exception or 410 Gone |
| 5 | Access-count limit reached | Treat as expired on next redirect | Create URL with limit=1; redirect twice → second fails |
| 6 | Short code not found | 404 or `IllegalArgumentException` | `redirect("nonexistent")` → exception |
| 7 | Null or blank custom alias | Use auto-generated code | `shorten(url, null, userId)` → valid short code |
| 8 | Concurrent shorten of same long URL | Both succeed with different short codes | Two threads `shorten(sameUrl)` → two distinct codes |

### Sample Test Snippets

```java
@Test
void shorten_rejectsEmptyUrl() {
    assertThrows(IllegalArgumentException.class, () -> service.shorten("", null, null));
}

@Test
void redirect_throwsWhenExpired() {
    ShortUrl url = createExpiredUrl();
    when(repo.findByShortCode("x")).thenReturn(Optional.of(url));
    assertThrows(IllegalStateException.class, () -> service.redirect("x", null, null, null));
}

@Test
void observersNotifiedOnRedirect() {
    UrlAccessObserver mockObserver = mock(UrlAccessObserver.class);
    service = new UrlShortenerService(idStrategy, expiryStrategy, List.of(mockObserver), repo);
    when(repo.findByShortCode("abc")).thenReturn(Optional.of(validUrl));
    service.redirect("abc", "1.2.3.4", "Mozilla", "https://google.com");
    verify(mockObserver).onUrlAccessed(any(), eq("1.2.3.4"), eq("Mozilla"), eq("https://google.com"));
}
```

---

## 8. Summary

| Aspect | Key Takeaway |
|--------|--------------|
| **Problem** | Shorten URLs, redirect, analytics; handle collisions and expiration |
| **DB** | `urls` (mapping), `url_analytics` (clicks), `users`, `custom_domains`; Base62 for compact codes; separate analytics for write isolation |
| **Patterns** | Strategy (ID gen, expiration), Factory (service wiring), Observer (analytics) |
| **SOLID** | SRP per component; O/C via interfaces; DIP via constructor injection |
| **Collision** | Retry + fallback strategy (e.g., NanoId) when primary collides |
| **Performance** | Cache shortCode→longUrl for redirects; async analytics |
| **Tests** | Invalid URL, alias taken, collision, expired, not found, concurrent shorten |
