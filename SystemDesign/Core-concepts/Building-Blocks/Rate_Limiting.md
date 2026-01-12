# Rate Limiting & Throttling: Complete Guide

## Overview
Rate limiting controls the number of requests a user/client can make to an API within a given time period. It protects services from abuse, ensures fair usage, and maintains system stability.

## Why Rate Limiting?

| Threat | Solution |
|--------|----------|
| DDoS attacks | Limit requests per IP |
| Brute force attempts | Limit login attempts |
| API abuse | Limit per API key |
| Resource exhaustion | Protect backend services |
| Cost control | Limit expensive operations |

## Rate Limiting Algorithms

### 1. Fixed Window Counter

```
Window: 1 minute (00:00 - 00:59)
Limit: 100 requests

00:00 - Request 1 ✓ (counter: 1)
00:30 - Request 50 ✓ (counter: 50)
00:59 - Request 100 ✓ (counter: 100)
00:59 - Request 101 ✗ (limit reached)
01:00 - Counter resets → Request 1 ✓
```

**Implementation**:
```python
def fixed_window(user_id, limit=100, window=60):
    key = f"rate:{user_id}:{current_minute()}"
    count = redis.incr(key)
    if count == 1:
        redis.expire(key, window)
    return count <= limit
```

**Pros**: Simple, low memory
**Cons**: Burst at window boundaries

### 2. Sliding Window Log

```
Stores timestamp of each request
Window slides with time

Requests: [10:00:05, 10:00:30, 10:00:45, 10:01:10]
At 10:01:20, window = [10:00:20 - 10:01:20]
Valid requests: [10:00:30, 10:00:45, 10:01:10] = 3
```

**Implementation**:
```python
def sliding_window_log(user_id, limit=100, window=60):
    key = f"rate:{user_id}"
    now = time.time()
    
    # Remove old entries
    redis.zremrangebyscore(key, 0, now - window)
    
    # Count current entries
    count = redis.zcard(key)
    
    if count < limit:
        redis.zadd(key, {str(now): now})
        redis.expire(key, window)
        return True
    return False
```

**Pros**: Accurate, no boundary issues
**Cons**: High memory for high-volume users

### 3. Sliding Window Counter

```
Combines fixed window efficiency with sliding window accuracy

Previous window count: 80 (40% weight)
Current window count: 30 (60% weight)
Weighted count: 80 * 0.4 + 30 * 0.6 = 50
```

**Implementation**:
```python
def sliding_window_counter(user_id, limit=100, window=60):
    now = time.time()
    current_window = int(now / window)
    prev_window = current_window - 1
    
    prev_count = redis.get(f"rate:{user_id}:{prev_window}") or 0
    curr_count = redis.get(f"rate:{user_id}:{current_window}") or 0
    
    # Calculate position in current window (0-1)
    window_progress = (now % window) / window
    
    # Weighted count
    count = prev_count * (1 - window_progress) + curr_count
    
    if count < limit:
        redis.incr(f"rate:{user_id}:{current_window}")
        return True
    return False
```

**Pros**: Good balance of accuracy and memory
**Cons**: Approximate (but very close)

### 4. Token Bucket

```
Bucket capacity: 100 tokens
Refill rate: 10 tokens/second

Time 0: Bucket has 100 tokens
User makes 50 requests → 50 tokens remain
After 5 seconds → 100 tokens (refilled)
Burst of 100 requests → 0 tokens
Next request → rejected until refill
```

**Implementation**:
```python
def token_bucket(user_id, capacity=100, refill_rate=10):
    key = f"bucket:{user_id}"
    now = time.time()
    
    # Get current state
    bucket = redis.hgetall(key)
    tokens = float(bucket.get('tokens', capacity))
    last_update = float(bucket.get('last_update', now))
    
    # Refill tokens
    elapsed = now - last_update
    tokens = min(capacity, tokens + elapsed * refill_rate)
    
    if tokens >= 1:
        tokens -= 1
        redis.hset(key, mapping={'tokens': tokens, 'last_update': now})
        return True
    return False
```

**Pros**: Allows controlled bursts, smooth rate
**Cons**: More complex state management

### 5. Leaky Bucket

```
Bucket processes requests at fixed rate
Overflow is rejected

Incoming: Variable rate
Outgoing: Fixed rate (10 req/sec)

Queue: [req1, req2, req3, req4, ...]
Processing: 1 request every 100ms
If queue full → reject new requests
```

**Implementation**:
```python
def leaky_bucket(user_id, capacity=100, leak_rate=10):
    key = f"leaky:{user_id}"
    now = time.time()
    
    bucket = redis.hgetall(key)
    water = float(bucket.get('water', 0))
    last_leak = float(bucket.get('last_leak', now))
    
    # Leak water
    elapsed = now - last_leak
    water = max(0, water - elapsed * leak_rate)
    
    if water < capacity:
        water += 1
        redis.hset(key, mapping={'water': water, 'last_leak': now})
        return True
    return False
```

**Pros**: Smooth output rate, prevents bursts
**Cons**: May delay legitimate bursts

## Algorithm Comparison

| Algorithm | Memory | Accuracy | Burst Handling | Complexity |
|-----------|--------|----------|----------------|------------|
| Fixed Window | Low | Low | Poor | Simple |
| Sliding Log | High | High | Good | Medium |
| Sliding Counter | Medium | High | Good | Medium |
| Token Bucket | Low | High | Excellent | Medium |
| Leaky Bucket | Low | High | Controlled | Medium |

## Rate Limiting Strategies

### By User/API Key
```yaml
rate_limits:
  free_tier:
    requests_per_minute: 60
    requests_per_day: 1000
  
  pro_tier:
    requests_per_minute: 600
    requests_per_day: 50000
```

### By IP Address
```yaml
rate_limits:
  per_ip:
    requests_per_second: 10
    requests_per_minute: 100
```

### By Endpoint
```yaml
rate_limits:
  endpoints:
    /api/search:
      limit: 10/minute  # Expensive
    /api/users:
      limit: 100/minute  # Normal
    /api/health:
      limit: unlimited   # Health checks
```

### By HTTP Method
```yaml
rate_limits:
  methods:
    GET: 100/minute
    POST: 20/minute
    DELETE: 10/minute
```

## Response Headers

```http
HTTP/1.1 200 OK
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 45
X-RateLimit-Reset: 1640000000
Retry-After: 30
```

### When Limit Exceeded
```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 30

{
  "error": "rate_limit_exceeded",
  "message": "Too many requests",
  "retry_after": 30
}
```

## Distributed Rate Limiting

### Centralized (Redis)
```
┌─────────┐     ┌─────────┐     ┌─────────┐
│ Server 1│     │ Server 2│     │ Server 3│
└────┬────┘     └────┬────┘     └────┬────┘
     │               │               │
     └───────────────┼───────────────┘
                     ↓
              ┌─────────────┐
              │    Redis    │
              │ (Counters)  │
              └─────────────┘
```

### Local + Sync
```
Each server: Local counter
Periodic sync to central store
Faster but less accurate
```

### Sticky Sessions
```
Route same user to same server
Local counters are accurate
Less distributed complexity
```

## Implementation Examples

### Spring Boot
```java
@Configuration
public class RateLimitConfig {
    
    @Bean
    public RateLimiter rateLimiter() {
        return RateLimiter.of("api", RateLimiterConfig.custom()
            .limitRefreshPeriod(Duration.ofMinutes(1))
            .limitForPeriod(100)
            .timeoutDuration(Duration.ofMillis(500))
            .build());
    }
}

@RestController
public class ApiController {
    
    @RateLimiter(name = "api")
    @GetMapping("/api/data")
    public Response getData() {
        return service.getData();
    }
}
```

### Node.js with Redis
```javascript
const rateLimit = require('express-rate-limit');
const RedisStore = require('rate-limit-redis');

const limiter = rateLimit({
    store: new RedisStore({
        client: redisClient,
        prefix: 'rl:'
    }),
    windowMs: 60 * 1000,  // 1 minute
    max: 100,
    message: {
        error: 'Too many requests',
        retryAfter: 60
    },
    standardHeaders: true,
    legacyHeaders: false
});

app.use('/api/', limiter);
```

### NGINX
```nginx
# Define rate limit zone
limit_req_zone $binary_remote_addr zone=api_limit:10m rate=10r/s;

server {
    location /api/ {
        # Apply rate limit with burst
        limit_req zone=api_limit burst=20 nodelay;
        
        # Custom error response
        limit_req_status 429;
        error_page 429 /rate_limit.json;
        
        proxy_pass http://backend;
    }
}
```

## Best Practices

### Design
1. Choose algorithm based on use case
2. Set reasonable limits (not too restrictive)
3. Use multiple layers (IP, user, endpoint)
4. Implement graceful degradation

### User Experience
1. Return clear error messages
2. Include Retry-After header
3. Provide rate limit info in headers
4. Offer higher limits for paid tiers

### Security
1. Rate limit authentication endpoints heavily
2. Don't expose internal counters
3. Use sliding window for security-sensitive endpoints
4. Combine with other security measures

## Interview Questions

**Q: Which algorithm would you use for an API?**
> Token bucket for most APIs - allows controlled bursts while maintaining average rate. Sliding window counter for simpler needs.

**Q: How to handle distributed rate limiting?**
> Centralized Redis for accuracy, local counters with sync for performance. Trade-off between accuracy and latency.

**Q: Rate limiting vs throttling?**
> Rate limiting rejects requests over limit. Throttling queues or delays requests. Throttling provides smoother experience.

## Quick Reference

| Scenario | Algorithm | Reason |
|----------|-----------|--------|
| Simple API | Fixed Window | Easy to implement |
| High accuracy | Sliding Log | Precise tracking |
| Allow bursts | Token Bucket | Controlled bursting |
| Smooth output | Leaky Bucket | Constant rate |
| Balance | Sliding Counter | Good trade-off |
