# Rate Limiter System - System Design Interview

## Problem Statement
*"Design a distributed rate limiter service that can handle millions of requests per second and prevent API abuse while ensuring low latency overhead."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What rate limiting algorithms should we support?" → Token bucket, sliding window, fixed window counters
- "What rate limiting granularity?" → Per user, per IP, per API key, per endpoint
- "Should we support different limits for different users?" → Yes, premium vs regular users
- "Do we need real-time configuration updates?" → Yes, update limits without restart
- "What happens when limit is exceeded?" → Return 429 status with retry-after header
- "Should we support burst traffic?" → Yes, token bucket allows bursts within limits

**Non-Functional Requirements:**
- "What's our request volume?" → 1M+ requests/second across all services
- "Latency overhead tolerance?" → <1ms additional latency per request
- "Accuracy requirements?" → 99%+ accuracy in rate limiting decisions
- "Availability needs?" → 99.99% uptime, fail-open if rate limiter down
- "Global vs regional?" → Global rate limiting across multiple data centers

### Requirements Summary:
- **Scale**: 1M+ RPS, distributed across regions
- **Algorithms**: Token bucket, sliding window, fixed window
- **Granularity**: User, IP, API key, endpoint-based limiting
- **Latency**: <1ms overhead per request
- **Accuracy**: 99%+ rate limiting precision
- **Flexibility**: Dynamic configuration updates

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Peak requests: 1M requests/second
Rate limit checks: 1:1 ratio = 1M checks/second
Average response time: <1ms for rate limit check
Memory per active key: ~100 bytes (counters, timestamps)
```

### Memory Requirements:
```
Active users: 10M concurrent users
Active API keys: 1M API keys
Memory per key: 100 bytes (token count, last refill time)
Total memory: (10M + 1M) × 100 bytes = 1.1GB
With overhead and metadata: ~2GB per node
```

### Storage Requirements:
```
Rate limit configurations: 100MB (rules, policies)
Historical data (analytics): 1TB/month
Logs and metrics: 500GB/month
Total: Minimal storage, mostly in-memory
```

### Infrastructure:
```
Nodes needed: 1M RPS ÷ 100K RPS/node = 10+ nodes
Memory per node: 2GB for active counters
Network: Low bandwidth (just counter updates)
```

---

## Phase 3: High-Level Architecture (12 minutes)

### Architecture Evolution:
```
Step 1: [Client] → [Single Rate Limiter] → [API Services]
Step 2: Distributed Architecture
```

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Client Apps  │  │Mobile Apps  │  │3rd Party    │
│- Web        │  │- iOS/Android│  │- Partners   │
│- API calls  │  │- SDK        │  │- Webhooks   │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│          Load Balancer                  │
│- Request routing  - Health checks       │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│        Rate Limiter Gateway             │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │  Node-1 │  │  Node-2 │  │  Node-3 │   │
│ │         │  │         │  │         │   │
│ │- Token  │  │- Token  │  │- Token  │   │
│ │  Bucket │  │  Bucket │  │  Bucket │   │
│ │- Sliding│  │- Sliding│  │- Sliding│   │
│ │  Window │  │  Window │  │  Window │   │
│ │- Config │  │- Config │  │- Config │   │
│ │  Cache  │  │  Cache  │  │  Cache  │   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
┌─────────────┐ ┌─────────────┐ ┌─────────────┐
│Configuration│ │Analytics    │ │Downstream   │
│Service      │ │Service      │ │Services     │
│             │ │             │ │             │
│- Rules Mgmt │ │- Metrics    │ │- Business   │
│- Policies   │ │- Monitoring │ │  Logic APIs │
│- Updates    │ │- Alerting   │ │- Databases  │
└─────────────┘ └─────────────┘ └─────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            Data Layer                   │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │  Redis  │  │ MongoDB │  │  Kafka  │   │
│ │         │  │         │  │         │   │
│ │- Counter│  │- Config │  │- Events │   │
│ │- Tokens │  │- Rules  │  │- Logs   │   │
│ │- Windows│  │- Users  │  │- Metrics│   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
```

### Key Components:
- **Rate Limiter Gateway**: Core rate limiting logic and algorithms
- **Configuration Service**: Manage rate limiting rules and policies
- **Analytics Service**: Monitoring, metrics, and alerting
- **Redis Cluster**: Distributed counters and token storage
- **MongoDB**: Configuration storage and user policies

---

## Phase 4: Database Design (8 minutes)

### Core Data Structures:
- Rate limit configurations (rules, policies)
- User/API key metadata (limits, current usage)
- Token buckets and sliding windows (active counters)
- Analytics data (usage patterns, violations)

### Redis Data Structures:

```javascript
// Token Bucket Algorithm
"token_bucket:{user_id}:{endpoint}": {
  "tokens": 95,              // Current token count
  "capacity": 100,           // Bucket capacity
  "refill_rate": 10,         // Tokens per second
  "last_refill": 1704110400, // Last refill timestamp
  "ttl": 3600                // 1 hour expiration
}

// Sliding Window Counter
"sliding_window:{api_key}:{endpoint}": {
  "windows": {
    "1704110340": 25,        // Requests in this 60s window
    "1704110400": 30,        // Current window
    "1704110460": 0          // Future window
  },
  "window_size": 60,         // 60 seconds
  "limit": 100,              // Requests per window
  "ttl": 300                 // 5 minutes
}

// Fixed Window Counter  
"fixed_window:{ip_address}:{endpoint}": {
  "count": 45,               // Current count
  "window_start": 1704110400, // Window start time
  "window_size": 3600,       // 1 hour window
  "limit": 1000,             // Requests per hour
  "ttl": 3600
}

// User rate limit overrides
"user_limits:{user_id}": {
  "tier": "premium",         // User tier
  "custom_limits": {
    "/api/search": 1000,     // Custom limit for endpoint
    "/api/upload": 10
  },
  "global_multiplier": 2.0,  // 2x normal limits
  "ttl": 86400               // 24 hours
}
```

### MongoDB Configuration Schema:

```json
// Rate limiting rules
{
  "_id": "api_endpoint_rule_1",
  "endpoint": "/api/users",
  "method": "GET",
  "default_limits": {
    "requests_per_second": 10,
    "requests_per_minute": 100,
    "requests_per_hour": 1000
  },
  "user_tier_limits": {
    "free": {"rps": 5, "rpm": 50, "rph": 500},
    "premium": {"rps": 20, "rpm": 200, "rph": 2000}
  },
  "algorithm": "token_bucket",
  "burst_capacity": 20,
  "enabled": true,
  "created_at": "2024-01-01T00:00:00Z"
}

// User profiles with rate limit info
{
  "_id": "user_123",
  "email": "user@example.com",
  "tier": "premium",
  "api_key": "ak_premium_user_123",
  "custom_limits": {
    "/api/data": 5000
  },
  "whitelist_ips": ["192.168.1.100"],
  "rate_limit_exempt": false,
  "created_at": "2024-01-01T00:00:00Z"
}
```

### In-Memory Data Structures:

```java
// Rate limiter algorithms
class TokenBucket {
    private int tokens;
    private int capacity;
    private double refillRate;
    private long lastRefillTime;
    
    public boolean tryConsume(int requestedTokens) {
        refill();
        if (tokens >= requestedTokens) {
            tokens -= requestedTokens;
            return true;
        }
        return false;
    }
}

class SlidingWindowCounter {
    private Map<Long, Integer> windows;
    private int windowSizeSeconds;
    private int limit;
    
    public boolean isAllowed() {
        long currentWindow = getCurrentWindowKey();
        pruneOldWindows();
        
        int totalRequests = windows.values().stream()
            .mapToInt(Integer::intValue).sum();
            
        if (totalRequests < limit) {
            windows.put(currentWindow, 
                windows.getOrDefault(currentWindow, 0) + 1);
            return true;
        }
        return false;
    }
}
```

### Design Decisions:
- **Redis for counters**: Low latency, atomic operations
- **MongoDB for config**: Flexible schema for complex rules
- **TTL for cleanup**: Automatic expiration of unused counters
- **Algorithm flexibility**: Support multiple rate limiting strategies

---

## Phase 5: Critical Flow - Rate Limit Check (8 minutes)

### Most Critical Flow: Incoming Request Rate Limiting

**1. Request Interception**
```
Client request → Load Balancer → Rate Limiter Gateway

Headers examined:
- Authorization: Bearer token / API key
- X-Forwarded-For: Client IP address
- User-Agent: Client identification
- X-Request-ID: Request tracking
```

**2. Rate Limit Key Generation**
```
Rate limiter determines checking strategy:

1. Extract user_id from JWT token or API key
2. Get client IP from headers
3. Identify endpoint from request path
4. Generate composite key: "user:{user_id}:endpoint:{endpoint}"
5. Check for any custom limits or overrides
```

**3. Algorithm Execution**
```
For Token Bucket algorithm:

1. Fetch current bucket state from Redis
2. Calculate tokens to add based on time elapsed
3. Refill bucket up to capacity limit
4. Check if enough tokens available for request
5. If yes: consume tokens and allow request
6. If no: reject with 429 status code
```

**4. Response Handling**
```
If request allowed:
1. Update Redis counters atomically
2. Add rate limit headers to response
3. Forward request to downstream service
4. Log successful request

If request blocked:
1. Return 429 Too Many Requests
2. Include Retry-After header
3. Log rate limit violation
4. Optionally trigger alert for abuse detection
```

**5. Analytics & Monitoring**
```
Async processing:
1. Publish rate limit event to Kafka
2. Update metrics (success rate, violation count)
3. Check for abuse patterns
4. Update user behavior analytics
```

### Technical Challenges:

**Distributed Consistency:**
- "Use Redis Lua scripts for atomic counter updates"
- "Handle race conditions in distributed environment"

**Performance Optimization:**
- "Local caching of rate limit rules to avoid DB lookups"
- "Connection pooling for Redis to minimize overhead"

**Accuracy vs Performance:**
- "Trade-off between perfect accuracy and low latency"
- "Use approximate algorithms for very high throughput"

**Configuration Management:**
- "Hot reload of rate limit rules without restart"
- "A/B testing different rate limiting strategies"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Redis throughput**: Limited by single Redis instance
2. **Network latency**: Round-trip time to Redis cluster
3. **Hot keys**: Popular users/APIs creating hotspots
4. **Configuration updates**: Propagating rule changes

### Scaling Solutions:

**Horizontal Scaling:**
```
- Redis sharding: Distribute counters across multiple Redis nodes
- Rate limiter nodes: Scale gateway instances independently
- Geographic distribution: Regional rate limiter clusters
- Consistent hashing: Distribute load evenly across nodes
```

**Performance Optimization:**
```
- Local caching: Cache frequently accessed limits locally
- Batch operations: Combine multiple Redis operations
- Lua scripts: Atomic operations to reduce network calls
- Connection pooling: Reuse Redis connections efficiently
```

**Advanced Techniques:**
```
- Approximate algorithms: HyperLogLog for approximate counting
- Hierarchical limiting: Global → Regional → Local limits
- Predictive scaling: Scale based on traffic patterns
- Circuit breaker: Fail-open when rate limiter unavailable
```

### Trade-offs:
- **Accuracy vs Latency**: Perfect counting vs sub-millisecond response
- **Memory vs Network**: Local state vs distributed consistency  
- **Strictness vs Availability**: Strict limits vs fail-open behavior

---

## Advanced Features:

**Dynamic Rate Limiting:**
- ML-based adaptive limits based on user behavior
- Spike detection and automatic limit adjustment
- Geographic rate limiting based on request origin

**Security Integration:**
- DDoS protection with automatic IP blocking
- Bot detection and different limits for automated traffic
- Integration with fraud detection systems

---

## Success Metrics:
- **Latency Overhead**: <1ms P99 additional latency
- **Throughput**: >1M rate limit checks/second
- **Accuracy**: >99% correct rate limiting decisions
- **Availability**: 99.99% rate limiter uptime
- **False Positive Rate**: <0.1% legitimate requests blocked

**🎯 This design demonstrates high-performance system architecture, distributed algorithms, and building critical infrastructure components that protect services from abuse.**




