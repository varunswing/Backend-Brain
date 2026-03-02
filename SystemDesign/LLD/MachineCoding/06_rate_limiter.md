# Rate Limiter - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database/Storage Design with Explanations](#database-design-with-explanations)
4. [Class Diagram](#class-diagram)
5. [Design Patterns Used](#design-patterns-used)
6. [SOLID Principles Applied](#solid-principles-applied)
7. [Code Implementation](#code-implementation)
8. [Request Flow](#request-flow)
9. [Edge Cases & Tests](#edge-cases--tests)

---

## Problem Statement

Design a rate limiter that throttles API requests based on configurable rules. Support multiple algorithms (token bucket, sliding window, fixed window), per-user/IP/API-key limits, and different tiers (free vs premium). Add < 1ms latency overhead.

---

## Requirements

### Functional Requirements
1. Rate limit by user ID, IP address, or API key
2. Support multiple algorithms (token bucket, sliding window, fixed window)
3. Configurable limits per endpoint and user tier
4. Return 429 with Retry-After header when limit exceeded
5. Dynamic rule updates without restart
6. Fail-open behavior (allow traffic if rate limiter is down)

### Non-Functional Requirements
- < 1ms latency overhead per request
- 1M+ rate limit checks/second
- 99.99% accuracy
- Distributed across multiple nodes

---

## Database/Storage Design with Explanations

```sql
-- ============================================================================
-- TABLE: rate_limit_rules
-- WHY: We need a CONFIGURABLE system, not hardcoded limits. This table stores
--      the rules that define HOW rate limiting works for each endpoint.
--      Without this table, changing limits means code changes and redeployment.
--      With this table, an admin can change limits via API in real-time.
--
-- WHY in a database (not just config file)?
--   1. Rules change at runtime (admin dashboard, A/B testing)
--   2. Multiple rate limiter nodes need the SAME rules (consistency)
--   3. Audit trail: who changed what rule when
-- ============================================================================
CREATE TABLE rate_limit_rules (
    rule_id UUID PRIMARY KEY,
    endpoint_pattern VARCHAR(200) NOT NULL,  -- "/api/v1/users/*" (supports wildcards)
    http_method VARCHAR(10),                 -- GET, POST, or NULL for all methods
    
    -- Algorithm configuration
    algorithm VARCHAR(30) NOT NULL,          -- TOKEN_BUCKET, SLIDING_WINDOW, FIXED_WINDOW
    
    -- Limits
    max_requests INTEGER NOT NULL,           -- e.g., 100 requests
    window_size_seconds INTEGER NOT NULL,    -- e.g., per 60 seconds
    burst_capacity INTEGER,                  -- For token bucket: max burst size
    
    -- Scope
    limit_by VARCHAR(20) NOT NULL,           -- USER_ID, IP_ADDRESS, API_KEY
    
    is_enabled BOOLEAN DEFAULT true,
    priority INTEGER DEFAULT 0,              -- Higher priority rules override lower
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? When a request comes in, we lookup rules by endpoint pattern.
-- This index makes that O(log n) instead of scanning all rules.
CREATE INDEX idx_rules_endpoint ON rate_limit_rules(endpoint_pattern, http_method)
    WHERE is_enabled = true;

-- ============================================================================
-- TABLE: tier_limits
-- WHY: Different user tiers (FREE, PREMIUM, ENTERPRISE) have different limits.
--      This table maps tiers to multipliers so we don't need separate rules
--      for each tier. ONE rule + tier multiplier = limits for all tiers.
--
-- RELATIONSHIP: tier_limits → rate_limit_rules (Many-to-One)
--   WHY? Each rule can have different limits per tier.
--   e.g., "/api/search": FREE=10/min, PREMIUM=100/min, ENTERPRISE=1000/min
--
-- WHY a separate table instead of JSONB in rate_limit_rules?
--   1. Queryable: "Show me all PREMIUM tier limits"
--   2. Updatable: Change premium limits without touching the rule
--   3. Normalized: No duplicate tier data across rules
-- ============================================================================
CREATE TABLE tier_limits (
    tier_limit_id UUID PRIMARY KEY,
    rule_id UUID NOT NULL REFERENCES rate_limit_rules(rule_id) ON DELETE CASCADE,
    tier_name VARCHAR(30) NOT NULL,         -- FREE, PREMIUM, ENTERPRISE
    max_requests INTEGER NOT NULL,          -- Override the rule's default
    burst_capacity INTEGER,
    
    UNIQUE(rule_id, tier_name)
);

-- ============================================================================
-- TABLE: rate_limit_violations
-- WHY: For analytics, abuse detection, and auditing.
--      When a request is rate-limited, we log it. This lets us:
--      1. Detect abuse patterns (same IP hitting limits repeatedly)
--      2. Alert on anomalies (sudden spike in violations)
--      3. Tune limits (if 30% of users hit limits, limits are too low)
--
-- WHY a table and not just metrics?
--   Metrics tell you "429s increased 50%". This table tells you
--   "user-123 at IP 1.2.3.4 hit /api/search 500 times in 1 minute".
-- ============================================================================
CREATE TABLE rate_limit_violations (
    violation_id BIGSERIAL PRIMARY KEY,
    rule_id UUID REFERENCES rate_limit_rules(rule_id),
    identifier VARCHAR(200) NOT NULL,       -- user_id, IP, or API key that was limited
    endpoint VARCHAR(200) NOT NULL,
    violation_count INTEGER DEFAULT 1,
    window_start TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? Query pattern: "Show me top violators in the last hour"
CREATE INDEX idx_violations_time ON rate_limit_violations(created_at DESC);
CREATE INDEX idx_violations_identifier ON rate_limit_violations(identifier, created_at DESC);
```

### Redis Data Structures (Primary Storage for Counters)

```
WHY Redis for counters (not PostgreSQL)?
1. Sub-millisecond latency (rate limiter MUST add < 1ms overhead)
2. Atomic operations (INCR, SETNX are naturally atomic)
3. Built-in TTL (counters auto-expire, no cleanup jobs needed)
4. 100K+ ops/second per node (PostgreSQL does ~10K for writes)

Redis Key Patterns:
- Token Bucket:    "tb:{user_id}:{endpoint}" → Hash {tokens, last_refill}
- Sliding Window:  "sw:{user_id}:{endpoint}" → Sorted Set of timestamps
- Fixed Window:    "fw:{user_id}:{endpoint}:{window}" → Counter
```

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | RateLimitAlgorithm (TokenBucket, SlidingWindow, FixedWindow) | Core pattern. Different algorithms, same interface. Swap per endpoint without changing middleware. |
| **Chain of Responsibility** | RateLimitChain | Multiple rules can apply to one request (global + per-user + per-endpoint). Chain evaluates each in order. |
| **Factory** | RateLimiterFactory | Create the right algorithm from config. Decouples creation from usage. |
| **Decorator** | LoggingRateLimiter, MetricsRateLimiter | Add logging/metrics to any algorithm without modifying it. |
| **Singleton** | RateLimiterRegistry | One registry of all active limiters. Thread-safe access. |

## SOLID Principles Applied

| Principle | How Applied |
|-----------|------------|
| **S** | `TokenBucketLimiter` only does token bucket logic. `RateLimitMiddleware` only intercepts requests. `RuleService` only manages rules. No class does two things. |
| **O** | Adding a new algorithm (e.g., Leaky Bucket) = implement `RateLimitAlgorithm`. ZERO changes to middleware or factory. |
| **L** | Any `RateLimitAlgorithm` implementation works in place of another. `RateLimitMiddleware` doesn't know or care which algorithm runs. |
| **I** | `RateLimitAlgorithm` has ONE method: `isAllowed()`. It doesn't force implementors to handle metrics, logging, or config. Those are separate concerns. |
| **D** | `RateLimitMiddleware` depends on `RateLimitAlgorithm` (interface), not `TokenBucketLimiter` (class). Injected, not created. |

---

## Code Implementation

### Core Interface

```java
// ============================================================================
// INTERFACE: RateLimitAlgorithm
// PATTERN: Strategy — This is the heart of the rate limiter.
// Each algorithm (Token Bucket, Sliding Window, Fixed Window) implements
// this interface. The middleware doesn't know which algorithm it's using.
//
// SOLID (I): One method. That's it. Implementors only need to answer
//   "is this request allowed?" No bloat.
// SOLID (L): Any implementation is substitutable. Middleware works with all.
// ============================================================================
public interface RateLimitAlgorithm {

    /**
     * Check if a request is allowed for the given identifier.
     * @param identifier Unique key (user ID, IP, API key)
     * @return Result containing whether request is allowed and metadata
     */
    RateLimitResult isAllowed(String identifier);
}

/**
 * Result of a rate limit check. Immutable record.
 * WHY a record? It's a simple data carrier. No behavior, no mutation.
 * Records give us equals(), hashCode(), toString() for free.
 */
public record RateLimitResult(
    boolean allowed,
    int remainingRequests,
    long retryAfterMs,         // -1 if allowed, otherwise ms until next window
    String algorithm            // For debugging/headers
) {
    public static RateLimitResult allowed(int remaining, String algo) {
        return new RateLimitResult(true, remaining, -1, algo);
    }

    public static RateLimitResult denied(long retryAfterMs, String algo) {
        return new RateLimitResult(false, 0, retryAfterMs, algo);
    }
}
```

### Algorithm Implementations

```java
// ============================================================================
// TOKEN BUCKET ALGORITHM
// HOW: Bucket starts full of tokens. Each request consumes 1 token.
//      Tokens refill at a fixed rate. If bucket is empty → rejected.
// PROS: Allows bursts (bucket can be full), smooths over time.
// CONS: Memory per user (bucket state).
// USE WHEN: APIs that tolerate short bursts but need overall rate control.
//
// SOLID (S): Only implements token bucket logic. No logging, no metrics.
// ============================================================================
public class TokenBucketLimiter implements RateLimitAlgorithm {

    private final int maxTokens;        // Bucket capacity (burst size)
    private final double refillRate;    // Tokens added per second
    
    // State: stored in-memory for single-node, Redis for distributed
    private final ConcurrentHashMap<String, TokenBucket> buckets;

    public TokenBucketLimiter(int maxTokens, double refillRate) {
        this.maxTokens = maxTokens;
        this.refillRate = refillRate;
        this.buckets = new ConcurrentHashMap<>();
    }

    @Override
    public RateLimitResult isAllowed(String identifier) {
        TokenBucket bucket = buckets.computeIfAbsent(
            identifier, k -> new TokenBucket(maxTokens, refillRate));
        
        // synchronized inside TokenBucket for thread safety
        if (bucket.tryConsume()) {
            return RateLimitResult.allowed(bucket.getAvailableTokens(), "token_bucket");
        }
        
        long retryAfterMs = (long) (1000.0 / refillRate); // Time for 1 token to refill
        return RateLimitResult.denied(retryAfterMs, "token_bucket");
    }

    /**
     * Inner class: Represents one user's token bucket.
     * Thread-safe via synchronized methods.
     */
    private static class TokenBucket {
        private double tokens;
        private final int maxTokens;
        private final double refillRate;
        private long lastRefillTimestamp;

        TokenBucket(int maxTokens, double refillRate) {
            this.tokens = maxTokens; // Start full
            this.maxTokens = maxTokens;
            this.refillRate = refillRate;
            this.lastRefillTimestamp = System.nanoTime();
        }

        /**
         * Try to consume one token. Refills based on elapsed time first.
         * WHY refill on access (lazy refill)?
         *   Because we don't want a background thread per bucket.
         *   With 10M users, that's 10M timers. Instead, we calculate
         *   how many tokens to add based on elapsed time since last access.
         */
        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        synchronized int getAvailableTokens() {
            refill();
            return (int) tokens;
        }

        private void refill() {
            long now = System.nanoTime();
            double elapsed = (now - lastRefillTimestamp) / 1_000_000_000.0; // seconds
            double tokensToAdd = elapsed * refillRate;
            tokens = Math.min(maxTokens, tokens + tokensToAdd);
            lastRefillTimestamp = now;
        }
    }
}

// ============================================================================
// SLIDING WINDOW LOG ALGORITHM
// HOW: Store timestamp of every request. Count requests in the last N seconds.
//      If count >= limit → rejected.
// PROS: Most accurate. No boundary issues.
// CONS: Memory-heavy (stores every timestamp). O(n) cleanup.
// USE WHEN: Accuracy is critical and traffic per key is moderate.
//
// In production: Use Redis Sorted Set (ZRANGEBYSCORE for window query).
// ============================================================================
public class SlidingWindowLogLimiter implements RateLimitAlgorithm {

    private final int maxRequests;
    private final long windowSizeMs;
    private final ConcurrentHashMap<String, Deque<Long>> requestLogs;

    public SlidingWindowLogLimiter(int maxRequests, long windowSizeMs) {
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.requestLogs = new ConcurrentHashMap<>();
    }

    @Override
    public RateLimitResult isAllowed(String identifier) {
        Deque<Long> log = requestLogs.computeIfAbsent(
            identifier, k -> new ConcurrentLinkedDeque<>());
        
        long now = System.currentTimeMillis();
        long windowStart = now - windowSizeMs;

        // Remove timestamps outside the window (cleanup old entries)
        while (!log.isEmpty() && log.peekFirst() <= windowStart) {
            log.pollFirst();
        }

        if (log.size() < maxRequests) {
            log.addLast(now);
            int remaining = maxRequests - log.size();
            return RateLimitResult.allowed(remaining, "sliding_window_log");
        }

        // Calculate when the oldest request in window will expire
        long oldestInWindow = log.peekFirst();
        long retryAfterMs = (oldestInWindow + windowSizeMs) - now;
        return RateLimitResult.denied(Math.max(retryAfterMs, 0), "sliding_window_log");
    }
}

// ============================================================================
// FIXED WINDOW COUNTER ALGORITHM
// HOW: Divide time into fixed windows (e.g., 1-minute blocks).
//      Count requests in current window. If count >= limit → rejected.
// PROS: Simple, low memory (just one counter per key per window).
// CONS: Boundary problem: 100 requests at end of window 1 + 100 at start
//       of window 2 = 200 in a 1-second span while limit is 100/min.
// USE WHEN: Simplicity matters more than perfect accuracy.
// ============================================================================
public class FixedWindowLimiter implements RateLimitAlgorithm {

    private final int maxRequests;
    private final long windowSizeMs;
    private final ConcurrentHashMap<String, WindowCounter> counters;

    public FixedWindowLimiter(int maxRequests, long windowSizeMs) {
        this.maxRequests = maxRequests;
        this.windowSizeMs = windowSizeMs;
        this.counters = new ConcurrentHashMap<>();
    }

    @Override
    public RateLimitResult isAllowed(String identifier) {
        long currentWindow = System.currentTimeMillis() / windowSizeMs;
        String key = identifier + ":" + currentWindow;

        WindowCounter counter = counters.computeIfAbsent(
            key, k -> new WindowCounter(currentWindow));

        int count = counter.incrementAndGet();

        if (count <= maxRequests) {
            return RateLimitResult.allowed(maxRequests - count, "fixed_window");
        }

        long windowEnd = (currentWindow + 1) * windowSizeMs;
        long retryAfterMs = windowEnd - System.currentTimeMillis();
        return RateLimitResult.denied(retryAfterMs, "fixed_window");
    }

    /**
     * Clean up old windows periodically (call from scheduled task).
     */
    public void cleanup() {
        long currentWindow = System.currentTimeMillis() / windowSizeMs;
        counters.entrySet().removeIf(e -> e.getValue().window < currentWindow - 1);
    }

    private static class WindowCounter {
        final long window;
        private final AtomicInteger count = new AtomicInteger(0);

        WindowCounter(long window) { this.window = window; }
        int incrementAndGet() { return count.incrementAndGet(); }
    }
}
```

### Factory Pattern

```java
// ============================================================================
// PATTERN: Factory — Creates the right algorithm from configuration.
// WHY? The RateLimitMiddleware shouldn't know HOW to construct each algorithm.
//   It just says "give me a limiter for this rule" and the factory handles it.
// SOLID (O): Add new algorithm → add new case. Don't change middleware.
// SOLID (S): Factory only creates objects. No business logic.
// ============================================================================
public class RateLimiterFactory {

    public RateLimitAlgorithm create(RateLimitRule rule) {
        return switch (rule.getAlgorithm()) {
            case TOKEN_BUCKET -> new TokenBucketLimiter(
                rule.getBurstCapacity(),
                (double) rule.getMaxRequests() / rule.getWindowSizeSeconds()
            );
            case SLIDING_WINDOW -> new SlidingWindowLogLimiter(
                rule.getMaxRequests(),
                rule.getWindowSizeSeconds() * 1000L
            );
            case FIXED_WINDOW -> new FixedWindowLimiter(
                rule.getMaxRequests(),
                rule.getWindowSizeSeconds() * 1000L
            );
        };
    }
}

public enum Algorithm {
    TOKEN_BUCKET,
    SLIDING_WINDOW,
    FIXED_WINDOW
}
```

### Decorator Pattern

```java
// ============================================================================
// PATTERN: Decorator — Add behavior to any algorithm WITHOUT modifying it.
// WHY? We want logging and metrics for ALL algorithms. Instead of adding
//   logging code inside TokenBucketLimiter, SlidingWindowLimiter, etc.
//   (violating SRP and OCP), we WRAP any algorithm with decorators.
//
// SOLID (O): New cross-cutting concerns (e.g., alerting) = new decorator.
//   Existing algorithms are NEVER modified.
// SOLID (S): LoggingRateLimiter only logs. MetricsRateLimiter only records metrics.
// ============================================================================
public class LoggingRateLimiter implements RateLimitAlgorithm {
    
    private final RateLimitAlgorithm delegate;  // The actual algorithm (wrapped)
    private final Logger logger = LoggerFactory.getLogger(LoggingRateLimiter.class);

    public LoggingRateLimiter(RateLimitAlgorithm delegate) {
        this.delegate = delegate;
    }

    @Override
    public RateLimitResult isAllowed(String identifier) {
        RateLimitResult result = delegate.isAllowed(identifier);
        
        if (!result.allowed()) {
            logger.warn("Rate limited: {} via {} (retry after {}ms)",
                identifier, result.algorithm(), result.retryAfterMs());
        }
        
        return result;
    }
}

public class MetricsRateLimiter implements RateLimitAlgorithm {
    
    private final RateLimitAlgorithm delegate;
    private final AtomicLong allowedCount = new AtomicLong(0);
    private final AtomicLong deniedCount = new AtomicLong(0);

    public MetricsRateLimiter(RateLimitAlgorithm delegate) {
        this.delegate = delegate;
    }

    @Override
    public RateLimitResult isAllowed(String identifier) {
        RateLimitResult result = delegate.isAllowed(identifier);
        
        if (result.allowed()) {
            allowedCount.incrementAndGet();
        } else {
            deniedCount.incrementAndGet();
        }
        
        return result;
    }

    public long getAllowedCount() { return allowedCount.get(); }
    public long getDeniedCount() { return deniedCount.get(); }
    public double getDenialRate() {
        long total = allowedCount.get() + deniedCount.get();
        return total == 0 ? 0 : (double) deniedCount.get() / total;
    }
}
```

### Chain of Responsibility

```java
// ============================================================================
// PATTERN: Chain of Responsibility
// WHY? A single request may be subject to MULTIPLE rate limit rules:
//   1. Global limit: 1000 RPS across all users
//   2. Per-user limit: 100 requests/minute for this user
//   3. Per-endpoint limit: 10 requests/minute for /api/upload
// The chain evaluates each rule. If ANY rule denies, the request is rejected.
// SOLID (O): Add new rule → add link to chain. No changes to existing links.
// ============================================================================
public class RateLimitChain {
    
    private final List<RateLimitRule> rules;
    private final RateLimiterFactory factory;
    private final Map<String, RateLimitAlgorithm> activeLimiters;

    public RateLimitChain(List<RateLimitRule> rules, RateLimiterFactory factory) {
        this.rules = rules.stream()
            .sorted(Comparator.comparingInt(RateLimitRule::getPriority).reversed())
            .collect(Collectors.toList());
        this.factory = factory;
        this.activeLimiters = new ConcurrentHashMap<>();
    }

    /**
     * Evaluate all applicable rules for this request.
     * First denial wins (short-circuit).
     */
    public RateLimitResult evaluate(String endpoint, String identifier) {
        for (RateLimitRule rule : rules) {
            if (rule.matches(endpoint)) {
                RateLimitAlgorithm limiter = activeLimiters.computeIfAbsent(
                    rule.getRuleId(),
                    id -> factory.create(rule)
                );
                
                String key = identifier + ":" + endpoint;
                RateLimitResult result = limiter.isAllowed(key);
                
                if (!result.allowed()) {
                    return result; // Short-circuit: first denial stops the chain
                }
            }
        }
        
        return RateLimitResult.allowed(Integer.MAX_VALUE, "none");
    }
}
```

### Middleware (Integration Point)

```java
// ============================================================================
// RateLimitMiddleware — The integration point with the web framework.
// Intercepts every request, runs rate limit check, adds response headers.
//
// SOLID (S): Only does request interception + rate limit check.
//   Doesn't implement algorithms or manage rules.
// SOLID (D): Depends on RateLimitChain (abstraction), not specific algorithms.
//
// PATTERN: This follows the Intercepting Filter / Middleware pattern.
// In Spring Boot, this would be a HandlerInterceptor or Filter.
// ============================================================================
public class RateLimitMiddleware {

    private final RateLimitChain rateLimitChain;
    private final IdentifierExtractor identifierExtractor;
    private final boolean failOpen;  // If true, allow traffic when rate limiter fails

    public RateLimitMiddleware(RateLimitChain chain, IdentifierExtractor extractor, 
                               boolean failOpen) {
        this.rateLimitChain = chain;
        this.identifierExtractor = extractor;
        this.failOpen = failOpen;
    }

    /**
     * Process an incoming request through the rate limiter.
     * Returns the rate limit result with appropriate HTTP headers.
     *
     * In Spring Boot: implements HandlerInterceptor.preHandle()
     * In raw servlet: implements Filter.doFilter()
     */
    public HttpResponse intercept(HttpRequest request) {
        try {
            String endpoint = request.getPath();
            String identifier = identifierExtractor.extract(request);
            
            RateLimitResult result = rateLimitChain.evaluate(endpoint, identifier);
            
            if (result.allowed()) {
                // Add rate limit headers and forward to downstream service
                HttpResponse response = forwardRequest(request);
                response.addHeader("X-RateLimit-Remaining", 
                    String.valueOf(result.remainingRequests()));
                response.addHeader("X-RateLimit-Algorithm", result.algorithm());
                return response;
            }
            
            // Rate limited → 429 Too Many Requests
            HttpResponse response = new HttpResponse(429);
            response.addHeader("Retry-After", 
                String.valueOf(result.retryAfterMs() / 1000));
            response.addHeader("X-RateLimit-Remaining", "0");
            response.setBody("{\"error\": \"Rate limit exceeded\", " +
                "\"retry_after_ms\": " + result.retryAfterMs() + "}");
            return response;
            
        } catch (Exception e) {
            // FAIL-OPEN: If rate limiter crashes, allow traffic through
            // WHY? It's better to serve requests without rate limiting than
            // to block ALL traffic because the limiter has a bug.
            if (failOpen) {
                return forwardRequest(request);
            }
            throw e;
        }
    }

    private HttpResponse forwardRequest(HttpRequest request) {
        // Forward to downstream service (placeholder)
        return new HttpResponse(200);
    }
}

/**
 * Extracts the identifier to rate-limit by.
 * SOLID (I): Single method interface. Clean.
 * SOLID (O): New extraction strategy (e.g., by organization) = new implementation.
 */
public interface IdentifierExtractor {
    String extract(HttpRequest request);
}

public class UserIdExtractor implements IdentifierExtractor {
    @Override
    public String extract(HttpRequest request) {
        return request.getHeader("X-User-Id");
    }
}

public class IpAddressExtractor implements IdentifierExtractor {
    @Override
    public String extract(HttpRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return (forwarded != null) ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }
}

public class ApiKeyExtractor implements IdentifierExtractor {
    @Override
    public String extract(HttpRequest request) {
        return request.getHeader("X-API-Key");
    }
}
```

### Configuration Model

```java
// ============================================================================
// MODEL: RateLimitRule — Maps to the rate_limit_rules DB table.
// Represents a single rate limiting rule configuration.
// ============================================================================
public class RateLimitRule {
    private final String ruleId;
    private final String endpointPattern;
    private final Algorithm algorithm;
    private final int maxRequests;
    private final int windowSizeSeconds;
    private final int burstCapacity;
    private final int priority;

    public RateLimitRule(String ruleId, String endpointPattern, Algorithm algorithm,
                         int maxRequests, int windowSizeSeconds, int burstCapacity,
                         int priority) {
        this.ruleId = ruleId;
        this.endpointPattern = endpointPattern;
        this.algorithm = algorithm;
        this.maxRequests = maxRequests;
        this.windowSizeSeconds = windowSizeSeconds;
        this.burstCapacity = burstCapacity > 0 ? burstCapacity : maxRequests;
        this.priority = priority;
    }

    /**
     * Check if this rule applies to the given endpoint.
     * Supports exact match and wildcard patterns.
     */
    public boolean matches(String endpoint) {
        if (endpointPattern.endsWith("/*")) {
            String prefix = endpointPattern.substring(0, endpointPattern.length() - 2);
            return endpoint.startsWith(prefix);
        }
        return endpoint.equals(endpointPattern);
    }

    // Getters
    public String getRuleId() { return ruleId; }
    public Algorithm getAlgorithm() { return algorithm; }
    public int getMaxRequests() { return maxRequests; }
    public int getWindowSizeSeconds() { return windowSizeSeconds; }
    public int getBurstCapacity() { return burstCapacity; }
    public int getPriority() { return priority; }
}
```

### Putting It Together

```java
public class RateLimiterDemo {
    public static void main(String[] args) {
        // ── Define rules ─────────────────────────────────────────────────
        List<RateLimitRule> rules = List.of(
            // Global: 1000 requests/second using token bucket (allows bursts)
            new RateLimitRule("rule-1", "/api/*", Algorithm.TOKEN_BUCKET,
                1000, 1, 1500, 0),
            
            // Per-user search: 100/minute using sliding window (most accurate)
            new RateLimitRule("rule-2", "/api/search", Algorithm.SLIDING_WINDOW,
                100, 60, 0, 10),
            
            // Per-user upload: 10/hour using fixed window (simple, sufficient)
            new RateLimitRule("rule-3", "/api/upload", Algorithm.FIXED_WINDOW,
                10, 3600, 0, 10)
        );

        // ── Create chain with factory ────────────────────────────────────
        RateLimiterFactory factory = new RateLimiterFactory();
        RateLimitChain chain = new RateLimitChain(rules, factory);

        // ── Create middleware ────────────────────────────────────────────
        RateLimitMiddleware middleware = new RateLimitMiddleware(
            chain, new UserIdExtractor(), true /* failOpen */);

        // ── Simulate requests ────────────────────────────────────────────
        for (int i = 0; i < 120; i++) {
            RateLimitResult result = chain.evaluate("/api/search", "user-1");
            if (!result.allowed()) {
                System.out.printf("Request %d: DENIED (retry after %dms, algo: %s)%n",
                    i, result.retryAfterMs(), result.algorithm());
                break;
            }
            System.out.printf("Request %d: ALLOWED (remaining: %d)%n",
                i, result.remainingRequests());
        }

        // ── Decorator demo: Add logging + metrics ────────────────────────
        // PATTERN: Decorator — wraps existing algorithm with extra behavior
        RateLimitAlgorithm base = new TokenBucketLimiter(10, 1.0);
        RateLimitAlgorithm withLogging = new LoggingRateLimiter(base);
        RateLimitAlgorithm withMetrics = new MetricsRateLimiter(withLogging);

        for (int i = 0; i < 15; i++) {
            withMetrics.isAllowed("user-1");
        }

        MetricsRateLimiter metrics = (MetricsRateLimiter) withMetrics;
        System.out.printf("Allowed: %d, Denied: %d, Denial Rate: %.2f%%%n",
            metrics.getAllowedCount(), metrics.getDeniedCount(),
            metrics.getDenialRate() * 100);
    }
}
```

---

## Algorithm Comparison

```
┌──────────────────┬──────────────────┬──────────────────┬──────────────────┐
│                  │  Token Bucket    │ Sliding Window   │  Fixed Window    │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Memory per key   │ O(1) - 2 fields  │ O(n) - all       │ O(1) - 1 counter │
│                  │ (tokens, time)   │ timestamps       │                  │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Accuracy         │ Good             │ Best             │ Has boundary     │
│                  │ (smooths over)   │ (exact count)    │ problem          │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Burst handling   │ ✅ Allows bursts  │ ❌ No bursts      │ ❌ No bursts      │
│                  │ (up to capacity) │ (strict)         │ (strict window)  │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Best for         │ APIs with bursty │ Financial/payment│ Simple rate      │
│                  │ traffic patterns │ (accuracy matters│ limiting         │
│                  │                  │                  │                  │
├──────────────────┼──────────────────┼──────────────────┼──────────────────┤
│ Redis impl       │ Hash + Lua       │ Sorted Set       │ INCR + EXPIRE    │
│                  │ script           │ ZRANGEBYSCORE    │                  │
└──────────────────┴──────────────────┴──────────────────┴──────────────────┘
```

---

## Edge Cases & Tests

```java
// 1. Token bucket allows burst then throttles
@Test
void tokenBucket_burstThenThrottle() {
    RateLimitAlgorithm limiter = new TokenBucketLimiter(10, 1.0); // 10 tokens, 1/sec refill
    
    // First 10 requests succeed (burst)
    for (int i = 0; i < 10; i++) {
        assertTrue(limiter.isAllowed("user-1").allowed());
    }
    
    // 11th is rejected (bucket empty)
    assertFalse(limiter.isAllowed("user-1").allowed());
}

// 2. Token bucket refills over time
@Test
void tokenBucket_refillsAfterWait() throws InterruptedException {
    RateLimitAlgorithm limiter = new TokenBucketLimiter(5, 2.0); // 5 tokens, 2/sec refill
    
    // Exhaust all tokens
    for (int i = 0; i < 5; i++) {
        limiter.isAllowed("user-1");
    }
    assertFalse(limiter.isAllowed("user-1").allowed());
    
    // Wait 1 second → 2 tokens should refill
    Thread.sleep(1100);
    assertTrue(limiter.isAllowed("user-1").allowed());
    assertTrue(limiter.isAllowed("user-1").allowed());
    assertFalse(limiter.isAllowed("user-1").allowed()); // 3rd should fail
}

// 3. Sliding window is accurate across boundary
@Test
void slidingWindow_noBoundaryProblem() throws InterruptedException {
    RateLimitAlgorithm limiter = new SlidingWindowLogLimiter(10, 2000); // 10 per 2 sec
    
    // 8 requests at t=0
    for (int i = 0; i < 8; i++) {
        assertTrue(limiter.isAllowed("user-1").allowed());
    }
    
    Thread.sleep(1000); // t=1 second
    
    // 2 more (total 10 in window) → allowed
    assertTrue(limiter.isAllowed("user-1").allowed());
    assertTrue(limiter.isAllowed("user-1").allowed());
    
    // 11th in the window → denied (sliding window catches this)
    assertFalse(limiter.isAllowed("user-1").allowed());
}

// 4. Different users have independent limits
@Test
void separateUsers_independentLimits() {
    RateLimitAlgorithm limiter = new FixedWindowLimiter(5, 60000);
    
    for (int i = 0; i < 5; i++) {
        assertTrue(limiter.isAllowed("user-1").allowed());
    }
    assertFalse(limiter.isAllowed("user-1").allowed());
    
    // user-2 should still have full quota
    assertTrue(limiter.isAllowed("user-2").allowed());
}

// 5. Decorator preserves behavior while adding metrics
@Test
void decorator_metricsAreAccurate() {
    RateLimitAlgorithm base = new FixedWindowLimiter(3, 60000);
    MetricsRateLimiter metrics = new MetricsRateLimiter(base);
    
    metrics.isAllowed("user-1"); // allowed
    metrics.isAllowed("user-1"); // allowed
    metrics.isAllowed("user-1"); // allowed
    metrics.isAllowed("user-1"); // denied
    metrics.isAllowed("user-1"); // denied
    
    assertEquals(3, metrics.getAllowedCount());
    assertEquals(2, metrics.getDeniedCount());
    assertEquals(0.4, metrics.getDenialRate(), 0.01);
}

// 6. Chain evaluates multiple rules — most restrictive wins
@Test
void chain_mostRestrictiveRuleWins() {
    List<RateLimitRule> rules = List.of(
        new RateLimitRule("r1", "/api/*", Algorithm.FIXED_WINDOW, 100, 60, 0, 0),
        new RateLimitRule("r2", "/api/upload", Algorithm.FIXED_WINDOW, 5, 60, 0, 10)
    );
    RateLimitChain chain = new RateLimitChain(rules, new RateLimiterFactory());
    
    // /api/upload should be limited to 5 (not 100)
    for (int i = 0; i < 5; i++) {
        assertTrue(chain.evaluate("/api/upload", "user-1").allowed());
    }
    assertFalse(chain.evaluate("/api/upload", "user-1").allowed());
}

// 7. Retry-After header is correct
@Test
void retryAfter_returnsCorrectValue() {
    RateLimitAlgorithm limiter = new FixedWindowLimiter(1, 60000); // 1 per minute
    limiter.isAllowed("user-1"); // consume the single request
    
    RateLimitResult result = limiter.isAllowed("user-1");
    assertFalse(result.allowed());
    assertTrue(result.retryAfterMs() > 0);
    assertTrue(result.retryAfterMs() <= 60000);
}
```

---

## Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  DESIGN PATTERNS                                                │
│                                                                 │
│  Strategy   → RateLimitAlgorithm (Token, Sliding, Fixed)       │
│  Factory    → RateLimiterFactory (create from config)          │
│  Decorator  → LoggingRateLimiter, MetricsRateLimiter           │
│  Chain of Responsibility → RateLimitChain (multiple rules)     │
│                                                                 │
│  SOLID PRINCIPLES                                               │
│                                                                 │
│  S → Each algorithm class = one algorithm only                 │
│  O → New algorithm = new class, zero changes elsewhere         │
│  L → All algorithms interchangeable via interface              │
│  I → RateLimitAlgorithm has 1 method: isAllowed()             │
│  D → Middleware depends on interface, not implementations      │
│                                                                 │
│  KEY INTERVIEW TALKING POINTS                                   │
│                                                                 │
│  1. Token Bucket = best for bursty APIs (allows burst)         │
│  2. Sliding Window = most accurate (no boundary issues)        │
│  3. Fixed Window = simplest (but has boundary problem)         │
│  4. Decorator adds logging/metrics without modifying algos     │
│  5. Fail-open = allow traffic if limiter crashes               │
│  6. Redis for distributed: Lua scripts for atomicity           │
└─────────────────────────────────────────────────────────────────┘
```
