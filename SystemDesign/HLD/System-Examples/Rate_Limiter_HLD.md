# Rate Limiter - High Level Design

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Rate Limiting Algorithms](#rate-limiting-algorithms)
3. [High-Level Design](#high-level-design)
4. [Detailed Component Design](#detailed-component-design)
5. [Distributed Rate Limiting](#distributed-rate-limiting)
6. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
7. [Failure Scenarios](#failure-scenarios)
8. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **Rate Limiting**
   - Limit requests per user/IP/API key
   - Support different limits per tier (free, premium)
   - Multiple rate limit rules (per minute, per hour, per day)

2. **Flexibility**
   - Different limits for different endpoints
   - Dynamic rule updates without restart
   - Whitelist/blacklist support

3. **Feedback**
   - Return remaining quota in headers
   - Clear error messages when rate limited
   - Retry-After header for throttled requests

### Non-Functional Requirements

1. **Latency**: < 1ms added latency
2. **Accuracy**: Minimal false positives
3. **Scale**: Handle millions of requests/second
4. **Availability**: Degrade gracefully
5. **Distributed**: Work across multiple servers

---

## Rate Limiting Algorithms

### 1. Token Bucket

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              TOKEN BUCKET                                            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Concept: Bucket holds tokens, requests consume tokens, tokens refill at fixed rate │
│                                                                                     │
│  Parameters:                                                                        │
│  - Bucket capacity (max tokens)                                                     │
│  - Refill rate (tokens per second)                                                  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Example: 10 tokens capacity, 1 token/second refill                         │   │
│  │                                                                              │   │
│  │  Time 0:  [●●●●●●●●●●] 10 tokens                                           │   │
│  │  Request → [●●●●●●●●●○]  9 tokens (allowed)                                │   │
│  │  Request → [●●●●●●●●○○]  8 tokens (allowed)                                │   │
│  │  ...                                                                         │   │
│  │  Request → [○○○○○○○○○○]  0 tokens                                          │   │
│  │  Request → REJECTED (no tokens)                                             │   │
│  │                                                                              │   │
│  │  After 5 seconds: [●●●●●○○○○○] 5 tokens (refilled)                        │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Pros: Allows burst, smooth rate limiting, memory efficient                         │
│  Cons: Needs atomic operations for distributed systems                              │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class TokenBucket:                                                          │   │
│  │      def __init__(self, capacity, refill_rate):                             │   │
│  │          self.capacity = capacity                                           │   │
│  │          self.refill_rate = refill_rate  # tokens per second               │   │
│  │          self.tokens = capacity                                             │   │
│  │          self.last_refill = time.time()                                     │   │
│  │                                                                              │   │
│  │      def allow_request(self, tokens_needed=1):                              │   │
│  │          self._refill()                                                      │   │
│  │                                                                              │   │
│  │          if self.tokens >= tokens_needed:                                   │   │
│  │              self.tokens -= tokens_needed                                   │   │
│  │              return True                                                     │   │
│  │          return False                                                        │   │
│  │                                                                              │   │
│  │      def _refill(self):                                                      │   │
│  │          now = time.time()                                                   │   │
│  │          elapsed = now - self.last_refill                                   │   │
│  │          new_tokens = elapsed * self.refill_rate                            │   │
│  │          self.tokens = min(self.capacity, self.tokens + new_tokens)         │   │
│  │          self.last_refill = now                                             │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Leaky Bucket

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LEAKY BUCKET                                            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Concept: Requests fill bucket, bucket leaks at fixed rate. Overflow rejected.     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │                  Requests                                                    │   │
│  │                     ↓                                                        │   │
│  │              ┌─────────────┐                                                │   │
│  │              │ ░░░░░░░░░░░ │  ← Queue (bucket)                             │   │
│  │              │ ░░░░░░░░░░░ │                                                │   │
│  │              │ ░░░░░░░░░░░ │                                                │   │
│  │              └──────┬──────┘                                                │   │
│  │                     │ Fixed rate (leak)                                     │   │
│  │                     ▼                                                        │   │
│  │              [Processed Requests]                                           │   │
│  │                                                                              │   │
│  │  If bucket full → Request rejected                                         │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Pros: Smooths out bursts, constant output rate                                    │
│  Cons: May cause latency for queued requests, doesn't allow any burst             │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class LeakyBucket:                                                          │   │
│  │      def __init__(self, capacity, leak_rate):                               │   │
│  │          self.capacity = capacity                                           │   │
│  │          self.leak_rate = leak_rate  # requests per second                  │   │
│  │          self.water = 0                                                      │   │
│  │          self.last_leak = time.time()                                       │   │
│  │                                                                              │   │
│  │      def allow_request(self):                                                │   │
│  │          self._leak()                                                        │   │
│  │                                                                              │   │
│  │          if self.water < self.capacity:                                     │   │
│  │              self.water += 1                                                │   │
│  │              return True                                                     │   │
│  │          return False                                                        │   │
│  │                                                                              │   │
│  │      def _leak(self):                                                        │   │
│  │          now = time.time()                                                   │   │
│  │          leaked = (now - self.last_leak) * self.leak_rate                   │   │
│  │          self.water = max(0, self.water - leaked)                           │   │
│  │          self.last_leak = now                                               │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Fixed Window Counter

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          FIXED WINDOW COUNTER                                        │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Concept: Count requests in fixed time windows, reset at window boundary           │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Limit: 100 requests per minute                                             │   │
│  │                                                                              │   │
│  │  Window 1 (12:00-12:01)    Window 2 (12:01-12:02)                          │   │
│  │  ┌─────────────────────┐   ┌─────────────────────┐                         │   │
│  │  │ Requests: 95        │   │ Requests: 0 (reset) │                         │   │
│  │  │ Remaining: 5        │   │ Remaining: 100      │                         │   │
│  │  └─────────────────────┘   └─────────────────────┘                         │   │
│  │                                                                              │   │
│  │  Problem: Boundary burst                                                     │   │
│  │  - 100 requests at 12:00:59                                                 │   │
│  │  - 100 requests at 12:01:01                                                 │   │
│  │  → 200 requests in 2 seconds! (violates intended limit)                    │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Pros: Simple, memory efficient                                                     │
│  Cons: Burst at window boundaries                                                  │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class FixedWindowCounter:                                                   │   │
│  │      def __init__(self, limit, window_seconds):                             │   │
│  │          self.limit = limit                                                  │   │
│  │          self.window_seconds = window_seconds                               │   │
│  │          self.counter = 0                                                    │   │
│  │          self.window_start = self._get_window_start()                       │   │
│  │                                                                              │   │
│  │      def allow_request(self):                                                │   │
│  │          current_window = self._get_window_start()                          │   │
│  │                                                                              │   │
│  │          if current_window != self.window_start:                            │   │
│  │              self.counter = 0                                               │   │
│  │              self.window_start = current_window                             │   │
│  │                                                                              │   │
│  │          if self.counter < self.limit:                                      │   │
│  │              self.counter += 1                                              │   │
│  │              return True                                                     │   │
│  │          return False                                                        │   │
│  │                                                                              │   │
│  │      def _get_window_start(self):                                            │   │
│  │          return int(time.time() / self.window_seconds)                      │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 4. Sliding Window Log

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          SLIDING WINDOW LOG                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Concept: Store timestamp of each request, count requests in rolling window        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Limit: 5 requests per minute                                               │   │
│  │  Current time: 12:01:30                                                     │   │
│  │                                                                              │   │
│  │  Request log: [12:00:45, 12:00:50, 12:01:00, 12:01:15, 12:01:25]           │   │
│  │                  ↑ expired   ↑ expired                                      │   │
│  │                                                                              │   │
│  │  Window: [12:00:30 ────────────────────────────────── 12:01:30]            │   │
│  │                                                                              │   │
│  │  Valid requests in window: 3 [12:01:00, 12:01:15, 12:01:25]                │   │
│  │  Remaining: 2                                                               │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Pros: Very accurate, no boundary burst issue                                      │
│  Cons: High memory usage (stores all timestamps)                                   │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class SlidingWindowLog:                                                     │   │
│  │      def __init__(self, limit, window_seconds):                             │   │
│  │          self.limit = limit                                                  │   │
│  │          self.window_seconds = window_seconds                               │   │
│  │          self.requests = []  # List of timestamps                           │   │
│  │                                                                              │   │
│  │      def allow_request(self):                                                │   │
│  │          now = time.time()                                                   │   │
│  │          window_start = now - self.window_seconds                           │   │
│  │                                                                              │   │
│  │          # Remove expired entries                                           │   │
│  │          self.requests = [t for t in self.requests if t > window_start]    │   │
│  │                                                                              │   │
│  │          if len(self.requests) < self.limit:                                │   │
│  │              self.requests.append(now)                                      │   │
│  │              return True                                                     │   │
│  │          return False                                                        │   │
│  │                                                                              │   │
│  │  # Redis implementation (efficient):                                        │   │
│  │  # ZADD user:123 <timestamp> <timestamp>                                   │   │
│  │  # ZREMRANGEBYSCORE user:123 0 <window_start>                              │   │
│  │  # ZCARD user:123                                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 5. Sliding Window Counter (Hybrid)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                      SLIDING WINDOW COUNTER                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Concept: Combine fixed window with weighted previous window                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Limit: 100 requests per minute                                             │   │
│  │  Current time: 12:01:15                                                     │   │
│  │                                                                              │   │
│  │  Previous window (12:00-12:01): 84 requests                                 │   │
│  │  Current window (12:01-12:02): 36 requests                                  │   │
│  │                                                                              │   │
│  │  Position in current window: 15/60 = 25%                                    │   │
│  │  Previous window weight: 1 - 0.25 = 75%                                     │   │
│  │                                                                              │   │
│  │  Estimated count: 84 * 0.75 + 36 = 63 + 36 = 99                            │   │
│  │                                                                              │   │
│  │  Can accept 1 more request                                                  │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Pros: Accurate, memory efficient (just 2 counters per user)                       │
│  Cons: Approximate (but very close)                                                │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class SlidingWindowCounter:                                                 │   │
│  │      def __init__(self, limit, window_seconds):                             │   │
│  │          self.limit = limit                                                  │   │
│  │          self.window_seconds = window_seconds                               │   │
│  │          self.prev_count = 0                                                 │   │
│  │          self.curr_count = 0                                                 │   │
│  │          self.curr_window = self._get_window()                              │   │
│  │                                                                              │   │
│  │      def allow_request(self):                                                │   │
│  │          now = time.time()                                                   │   │
│  │          current_window = self._get_window()                                │   │
│  │                                                                              │   │
│  │          # Rotate windows if needed                                          │   │
│  │          if current_window != self.curr_window:                             │   │
│  │              self.prev_count = self.curr_count                              │   │
│  │              self.curr_count = 0                                            │   │
│  │              self.curr_window = current_window                              │   │
│  │                                                                              │   │
│  │          # Calculate weighted count                                          │   │
│  │          window_position = (now % self.window_seconds) / self.window_seconds│   │
│  │          prev_weight = 1 - window_position                                  │   │
│  │          weighted_count = self.prev_count * prev_weight + self.curr_count   │   │
│  │                                                                              │   │
│  │          if weighted_count < self.limit:                                    │   │
│  │              self.curr_count += 1                                           │   │
│  │              return True                                                     │   │
│  │          return False                                                        │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Algorithm Comparison

| Algorithm | Memory | Accuracy | Burst Handling | Complexity |
|-----------|--------|----------|----------------|------------|
| Token Bucket | O(1) | Good | Allows burst | Low |
| Leaky Bucket | O(1) | Good | No burst | Low |
| Fixed Window | O(1) | Poor | Boundary issue | Very Low |
| Sliding Log | O(n) | Excellent | Perfect | Medium |
| Sliding Counter | O(1) | Very Good | Good | Low |

**Recommendation:** Token Bucket for APIs (allows burst), Sliding Window Counter for general use

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              CLIENT REQUEST                                          │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LOAD BALANCER                                           │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              API GATEWAY                                             │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                        RATE LIMITER MIDDLEWARE                               │   │
│  │                                                                              │   │
│  │  1. Extract identifier (user_id, IP, API key)                               │   │
│  │  2. Get rules for identifier                                                │   │
│  │  3. Check all applicable limits                                             │   │
│  │  4. If within limits → Allow                                                │   │
│  │  5. If exceeded → Return 429                                                │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
              ┌──────────────────────────┼──────────────────────────┐
              │                          │                          │
              ▼                          ▼                          ▼
┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────────────┐
│     RULES ENGINE     │   │     REDIS CLUSTER    │   │     BACKEND SERVICES         │
│                      │   │                      │   │                              │
│  - Rule definitions  │   │  - Counter storage   │   │  (Only reached if           │
│  - Tier mapping      │   │  - Distributed locks │   │   rate limit passes)         │
│  - Dynamic updates   │   │  - Atomic operations │   │                              │
└──────────────────────┘   └──────────────────────┘   └──────────────────────────────┘
```

### Where to Place Rate Limiter

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                     RATE LIMITER PLACEMENT OPTIONS                                   │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Option 1: Client-side                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Pros: Reduces server load                                                  │   │
│  │  Cons: Can be bypassed, unreliable                                         │   │
│  │  Use: Friendly rate limiting for mobile apps                               │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Option 2: API Gateway / Load Balancer (RECOMMENDED)                                │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Pros: Single entry point, protects all services                           │   │
│  │  Cons: Single point of failure, may be bottleneck                         │   │
│  │  Use: Most production systems                                               │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Option 3: Application middleware                                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Pros: Fine-grained control, service-specific limits                       │   │
│  │  Cons: Duplicated logic, harder to maintain                               │   │
│  │  Use: Service-specific limits, multi-service architecture                  │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Option 4: Sidecar proxy (Envoy/Istio)                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  Pros: Transparent, language-agnostic                                      │   │
│  │  Cons: Additional infrastructure complexity                                │   │
│  │  Use: Kubernetes environments                                               │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Component Design

### Rate Limiter Service

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         RATE LIMITER SERVICE                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  class RateLimiter:                                                                 │
│      def __init__(self, redis_client, rules_engine):                               │
│          self.redis = redis_client                                                  │
│          self.rules_engine = rules_engine                                           │
│                                                                                     │
│      async def is_allowed(self, request):                                           │
│          # Extract identifier                                                       │
│          identifier = self.get_identifier(request)                                  │
│                                                                                     │
│          # Get applicable rules                                                     │
│          rules = await self.rules_engine.get_rules(identifier, request.endpoint)   │
│                                                                                     │
│          # Check each rule                                                          │
│          results = []                                                               │
│          for rule in rules:                                                         │
│              allowed, remaining, reset_at = await self.check_limit(                │
│                  identifier,                                                        │
│                  rule                                                               │
│              )                                                                      │
│              results.append(RateLimitResult(                                       │
│                  rule=rule,                                                         │
│                  allowed=allowed,                                                   │
│                  remaining=remaining,                                               │
│                  reset_at=reset_at                                                 │
│              ))                                                                     │
│                                                                                     │
│          # Return most restrictive                                                  │
│          return self.aggregate_results(results)                                     │
│                                                                                     │
│      def get_identifier(self, request):                                             │
│          # Priority: API key > User ID > IP                                        │
│          if request.api_key:                                                        │
│              return f"apikey:{request.api_key}"                                    │
│          if request.user_id:                                                        │
│              return f"user:{request.user_id}"                                      │
│          return f"ip:{request.ip}"                                                  │
│                                                                                     │
│      async def check_limit(self, identifier, rule):                                 │
│          key = f"ratelimit:{identifier}:{rule.name}"                               │
│                                                                                     │
│          if rule.algorithm == "token_bucket":                                       │
│              return await self.token_bucket_check(key, rule)                       │
│          elif rule.algorithm == "sliding_window":                                   │
│              return await self.sliding_window_check(key, rule)                     │
│          # ... other algorithms                                                     │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Redis Implementation (Token Bucket)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    REDIS TOKEN BUCKET IMPLEMENTATION                                 │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  -- Lua script for atomic token bucket                                              │
│                                                                                     │
│  local key = KEYS[1]                                                                │
│  local capacity = tonumber(ARGV[1])                                                 │
│  local refill_rate = tonumber(ARGV[2])                                              │
│  local now = tonumber(ARGV[3])                                                      │
│  local requested = tonumber(ARGV[4])                                                │
│                                                                                     │
│  local bucket = redis.call('HMGET', key, 'tokens', 'last_update')                  │
│  local tokens = tonumber(bucket[1]) or capacity                                     │
│  local last_update = tonumber(bucket[2]) or now                                     │
│                                                                                     │
│  -- Refill tokens                                                                   │
│  local elapsed = now - last_update                                                  │
│  local new_tokens = math.min(capacity, tokens + elapsed * refill_rate)             │
│                                                                                     │
│  local allowed = 0                                                                  │
│  local remaining = new_tokens                                                       │
│                                                                                     │
│  if new_tokens >= requested then                                                    │
│      remaining = new_tokens - requested                                             │
│      allowed = 1                                                                    │
│  end                                                                                │
│                                                                                     │
│  -- Update bucket                                                                   │
│  redis.call('HMSET', key, 'tokens', remaining, 'last_update', now)                 │
│  redis.call('EXPIRE', key, math.ceil(capacity / refill_rate) * 2)                  │
│                                                                                     │
│  return {allowed, remaining}                                                        │
│                                                                                     │
│  -------------------------------------------------------------------------------   │
│                                                                                     │
│  Python implementation:                                                             │
│                                                                                     │
│  class TokenBucketRedis:                                                            │
│      def __init__(self, redis, capacity, refill_rate):                             │
│          self.redis = redis                                                         │
│          self.capacity = capacity                                                   │
│          self.refill_rate = refill_rate                                            │
│          self.script = self.redis.register_script(LUA_SCRIPT)                      │
│                                                                                     │
│      async def allow_request(self, key, tokens_needed=1):                          │
│          result = await self.script(                                                │
│              keys=[key],                                                            │
│              args=[                                                                 │
│                  self.capacity,                                                     │
│                  self.refill_rate,                                                  │
│                  time.time(),                                                       │
│                  tokens_needed                                                      │
│              ]                                                                      │
│          )                                                                          │
│          allowed, remaining = result                                                │
│          return bool(allowed), int(remaining)                                       │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### HTTP Response Headers

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                      RATE LIMIT RESPONSE HEADERS                                     │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Successful request (within limits):                                                │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  HTTP/1.1 200 OK                                                            │   │
│  │  X-RateLimit-Limit: 100                                                     │   │
│  │  X-RateLimit-Remaining: 95                                                  │   │
│  │  X-RateLimit-Reset: 1705312800                                              │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Rate limited request:                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  HTTP/1.1 429 Too Many Requests                                             │   │
│  │  X-RateLimit-Limit: 100                                                     │   │
│  │  X-RateLimit-Remaining: 0                                                   │   │
│  │  X-RateLimit-Reset: 1705312800                                              │   │
│  │  Retry-After: 30                                                            │   │
│  │                                                                              │   │
│  │  {                                                                           │   │
│  │    "error": {                                                               │   │
│  │      "code": "rate_limit_exceeded",                                         │   │
│  │      "message": "Rate limit exceeded. Please retry after 30 seconds.",     │   │
│  │      "retry_after": 30                                                      │   │
│  │    }                                                                         │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Distributed Rate Limiting

### Challenge

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    DISTRIBUTED RATE LIMITING CHALLENGE                               │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Problem: Multiple API servers, each needs to enforce same limit                   │
│                                                                                     │
│                         User (limit: 100/min)                                       │
│                                   │                                                 │
│                                   ▼                                                 │
│                          Load Balancer                                              │
│                       ┌─────────┼─────────┐                                        │
│                       ▼         ▼         ▼                                        │
│                   Server 1  Server 2  Server 3                                     │
│                    (33 req)  (33 req)  (34 req)                                    │
│                                                                                     │
│  Without coordination: Each server allows 100 → 300 total (3x limit!)             │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Solution 1: Centralized (Redis)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                       CENTRALIZED RATE LIMITING                                      │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  All servers share a single Redis for state                                        │
│                                                                                     │
│       Server 1 ──────┐                                                              │
│       Server 2 ──────┼──────► Redis Cluster ◄──────┬────── Server 4               │
│       Server 3 ──────┘                             └────── Server 5               │
│                                                                                     │
│  Pros:                                                                              │
│  - Globally accurate                                                                │
│  - Simple implementation                                                            │
│                                                                                     │
│  Cons:                                                                              │
│  - Redis latency added to every request                                            │
│  - Redis becomes single point of failure                                           │
│                                                                                     │
│  Mitigation:                                                                        │
│  - Redis Cluster for HA                                                            │
│  - Local cache for recently checked keys                                           │
│  - Fail-open if Redis unavailable                                                  │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Solution 2: Sticky Sessions

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         STICKY SESSIONS                                              │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Route same user to same server → Local rate limiting works                        │
│                                                                                     │
│       User A ────────► Server 1 (100/min for A)                                    │
│       User B ────────► Server 2 (100/min for B)                                    │
│       User C ────────► Server 3 (100/min for C)                                    │
│                                                                                     │
│  Implementation:                                                                    │
│  - Hash user ID or IP to server                                                    │
│  - Consistent hashing for better distribution                                      │
│                                                                                     │
│  Pros:                                                                              │
│  - No coordination needed                                                           │
│  - Very fast (local memory)                                                        │
│                                                                                     │
│  Cons:                                                                              │
│  - Server failure → lose state (burst)                                            │
│  - Uneven distribution possible                                                    │
│  - Doesn't work with IP-based limits (proxies)                                    │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Solution 3: Synchronization (Gossip)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                      GOSSIP-BASED SYNCHRONIZATION                                    │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Servers periodically share their counters                                         │
│                                                                                     │
│       Server 1                                                                      │
│         │      ╲                                                                    │
│         │        ╲  gossip                                                         │
│         │          ╲                                                               │
│       gossip         Server 2                                                       │
│         │          ╱                                                               │
│         │        ╱  gossip                                                         │
│         │      ╱                                                                    │
│       Server 3                                                                      │
│                                                                                     │
│  Each server:                                                                       │
│  1. Counts locally                                                                 │
│  2. Periodically broadcasts counts                                                 │
│  3. Aggregates received counts                                                     │
│  4. Enforces limit based on aggregate                                              │
│                                                                                     │
│  Pros:                                                                              │
│  - Tolerates network issues                                                        │
│  - Eventually consistent                                                           │
│                                                                                     │
│  Cons:                                                                              │
│  - Small window of inconsistency                                                   │
│  - More complex                                                                     │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### Algorithm Selection

| Use Case | Recommended Algorithm |
|----------|----------------------|
| API rate limiting | Token Bucket |
| DDoS protection | Leaky Bucket |
| Simple per-minute limit | Sliding Window Counter |
| Precise billing | Sliding Window Log |

### Storage Selection

| Storage | Latency | Scale | Use Case |
|---------|---------|-------|----------|
| Local memory | <0.1ms | Single server | Low-volume, sticky sessions |
| Redis | 1-2ms | High | Most production systems |
| Redis Cluster | 2-5ms | Very high | Global, high-scale |

---

## Failure Scenarios

### Redis Failure

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                      HANDLING REDIS FAILURE                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Options:                                                                           │
│                                                                                     │
│  1. Fail-open (allow all requests)                                                 │
│     - Risk: No rate limiting during outage                                         │
│     - Use: Customer-facing APIs (availability > protection)                        │
│                                                                                     │
│  2. Fail-closed (reject all requests)                                              │
│     - Risk: Service unavailable                                                    │
│     - Use: Security-critical endpoints                                             │
│                                                                                     │
│  3. Fallback to local rate limiting                                                │
│     - Distribute limit across servers: 100/min ÷ 5 servers = 20/min each          │
│     - Conservative but functional                                                   │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  async def is_allowed(self, key):                                                   │
│      try:                                                                           │
│          return await self.redis_check(key)                                        │
│      except RedisError:                                                             │
│          if self.fail_mode == "open":                                              │
│              self.metrics.increment("rate_limit.redis_failure_open")              │
│              return True                                                            │
│          elif self.fail_mode == "closed":                                          │
│              return False                                                           │
│          else:                                                                      │
│              return self.local_fallback_check(key)                                 │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Interviewer Questions & Answers

### Q1: Which rate limiting algorithm would you choose and why?

**Answer:**

**For API rate limiting: Token Bucket**

```
Reasons:
1. Allows burst traffic (good UX)
   - User can make 10 quick requests, then wait
   - Better than steady 1 req/sec requirement

2. Memory efficient (O(1) per user)
   - Just store: tokens, last_update

3. Smooth rate over time
   - Tokens refill continuously
   - No boundary burst issues

4. Easy to implement with Redis
   - Single Lua script for atomicity
```

**For DDoS protection: Leaky Bucket**

```
Reasons:
1. Strict output rate
   - Smooths burst into steady stream
   - Protects backend from overload

2. Queue behavior
   - Doesn't reject immediately
   - Adds slight latency instead
```

---

### Q2: How do you handle distributed rate limiting?

**Answer:**

**Primary approach: Centralized Redis**

```python
# All servers use same Redis instance
async def check_rate_limit(user_id, limit, window):
    key = f"ratelimit:{user_id}:{window}"
    
    # Lua script for atomicity
    result = await redis.eval(
        SLIDING_WINDOW_SCRIPT,
        keys=[key],
        args=[limit, window, time.time()]
    )
    
    return result.allowed, result.remaining
```

**Handling Redis unavailability:**
```python
# Fallback to local with reduced limit
async def check_with_fallback(user_id, limit, window):
    try:
        return await redis_check(user_id, limit, window)
    except RedisError:
        # Distribute limit across servers
        local_limit = limit // num_servers
        return local_check(user_id, local_limit, window)
```

**Why not local-only:**
- User can hit different servers
- 5 servers × 100 limit = 500 allowed (5x intended)

---

### Q3: How do you handle rate limiting for different tiers?

**Answer:**

**Configuration:**
```python
RATE_LIMITS = {
    "free": {
        "requests_per_minute": 60,
        "requests_per_day": 1000,
    },
    "basic": {
        "requests_per_minute": 300,
        "requests_per_day": 10000,
    },
    "premium": {
        "requests_per_minute": 1000,
        "requests_per_day": 100000,
    },
    "enterprise": {
        "requests_per_minute": 10000,
        "requests_per_day": None,  # Unlimited
    }
}
```

**Implementation:**
```python
async def get_rate_limit(user):
    tier = await get_user_tier(user.id)
    limits = RATE_LIMITS[tier]
    
    # Check all applicable limits
    for window, limit in limits.items():
        if limit is None:
            continue  # Unlimited
        
        allowed = await check_limit(user.id, window, limit)
        if not allowed:
            return RateLimitExceeded(window=window, limit=limit)
    
    return Allowed()
```

---

### Q4: How do you set appropriate rate limits?

**Answer:**

**Methodology:**

1. **Analyze traffic patterns:**
```python
# Query existing traffic data
SELECT 
    user_id,
    COUNT(*) as requests,
    percentile_cont(0.99) as p99_requests
FROM requests
WHERE timestamp > NOW() - INTERVAL '1 day'
GROUP BY user_id
```

2. **Set limits based on percentiles:**
```
- p50 users: 50 req/min → Set limit: 100 (2x headroom)
- p99 users: 200 req/min → Enterprise tier: 500
- Abusers: 10,000 req/min → Block or throttle
```

3. **Consider endpoint cost:**
```python
ENDPOINT_WEIGHTS = {
    "/api/search": 5,      # Expensive (DB query)
    "/api/user": 1,        # Cheap (cached)
    "/api/upload": 10,     # Very expensive
}

# Consume more "tokens" for expensive endpoints
tokens_needed = ENDPOINT_WEIGHTS.get(endpoint, 1)
```

4. **Monitor and adjust:**
- Track 429 rate by tier
- Adjust if legitimate users hitting limits
- Alert on sudden changes

---

### Q5: How do you prevent race conditions?

**Answer:**

**Problem:**
```
Time 0: Server A reads count = 99
Time 1: Server B reads count = 99
Time 2: Server A writes count = 100
Time 3: Server B writes count = 100
Result: 2 requests allowed, but count shows 100 (should be 101)
```

**Solution: Atomic operations with Lua**

```lua
-- Everything runs atomically on Redis
local key = KEYS[1]
local limit = tonumber(ARGV[1])

local current = tonumber(redis.call('GET', key) or '0')

if current < limit then
    redis.call('INCR', key)
    return {1, limit - current - 1}  -- allowed, remaining
else
    return {0, 0}  -- denied
end
```

**Alternative: Redis MULTI/EXEC**
```python
async def increment_with_check(key, limit):
    async with redis.pipeline(transaction=True) as pipe:
        await pipe.watch(key)
        current = int(await pipe.get(key) or 0)
        
        if current >= limit:
            await pipe.unwatch()
            return False, 0
        
        pipe.multi()
        await pipe.incr(key)
        await pipe.execute()
        return True, limit - current - 1
```

---

### Q6: How do you handle rate limiting at the edge (CDN)?

**Answer:**

**Edge rate limiting:**
```
┌─────────────────────────────────────────────────────────────────┐
│                   EDGE RATE LIMITING                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  CDN Edge PoP (100+ locations)                                  │
│       │                                                         │
│       ├── Local rate limiting (per-PoP)                        │
│       │   - Protects against DDoS                              │
│       │   - Fast (no origin round-trip)                        │
│       │                                                         │
│       └── Synchronized rate limiting                           │
│           - Eventual consistency across PoPs                   │
│           - For user-level limits                              │
│                                                                 │
│  Implementation (Cloudflare Workers):                          │
│                                                                 │
│  async function handleRequest(request) {                       │
│      const ip = request.headers.get('CF-Connecting-IP')       │
│                                                                 │
│      // Local check (fast)                                     │
│      const localAllowed = await localRateLimit(ip)            │
│      if (!localAllowed) return new Response('429', {status:429})
│                                                                 │
│      // User-level check (if authenticated)                    │
│      if (request.userId) {                                     │
│          const userAllowed = await userRateLimit(userId)      │
│          if (!userAllowed) return new Response('429', ...)    │
│      }                                                         │
│                                                                 │
│      return fetch(request)  // Forward to origin              │
│  }                                                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

### Q7: How do you implement rate limiting for GraphQL?

**Answer:**

**Challenge:** GraphQL has single endpoint, varying query complexity

**Solution: Query complexity analysis**

```python
# Calculate complexity before execution
def calculate_complexity(query):
    complexity = 0
    
    for field in query.fields:
        base_cost = FIELD_COSTS.get(field.name, 1)
        
        # Multiply by pagination
        if field.has_connection:
            limit = field.arguments.get('first', 10)
            base_cost *= limit
        
        # Add nested field costs
        for nested in field.selections:
            base_cost += calculate_complexity(nested)
        
        complexity += base_cost
    
    return complexity

# Rate limit based on complexity
async def execute_query(query, user):
    complexity = calculate_complexity(query)
    
    # Check complexity budget (e.g., 1000 points/minute)
    allowed = await rate_limiter.consume(
        user_id=user.id,
        tokens=complexity,
        limit=1000
    )
    
    if not allowed:
        raise GraphQLError("Complexity budget exceeded")
    
    return execute(query)
```

---

### Q8: How do you test rate limiting?

**Answer:**

**Unit tests:**
```python
async def test_token_bucket_allows_burst():
    bucket = TokenBucket(capacity=10, refill_rate=1)
    
    # Should allow burst of 10
    for _ in range(10):
        assert await bucket.allow_request()
    
    # 11th should fail
    assert not await bucket.allow_request()

async def test_token_bucket_refills():
    bucket = TokenBucket(capacity=10, refill_rate=1)
    
    # Use all tokens
    for _ in range(10):
        await bucket.allow_request()
    
    # Wait 5 seconds
    await asyncio.sleep(5)
    
    # Should have 5 tokens now
    for _ in range(5):
        assert await bucket.allow_request()
    assert not await bucket.allow_request()
```

**Load tests:**
```python
async def load_test_rate_limiter():
    # Simulate 1000 users, 100 req/min limit each
    users = [f"user_{i}" for i in range(1000)]
    
    async def simulate_user(user_id):
        allowed = 0
        denied = 0
        
        for _ in range(200):  # Try 200 requests
            if await rate_limiter.is_allowed(user_id):
                allowed += 1
            else:
                denied += 1
            await asyncio.sleep(0.3)  # ~200 req/min attempt
        
        return allowed, denied
    
    results = await asyncio.gather(*[simulate_user(u) for u in users])
    
    # Each user should have ~100 allowed
    for allowed, denied in results:
        assert 95 <= allowed <= 105
```

---

### Q9: What metrics should you track for rate limiting?

**Answer:**

**Key metrics:**
```python
class RateLimiterMetrics:
    def record_request(self, result):
        # Count allowed vs denied
        if result.allowed:
            metrics.increment("rate_limit.allowed", tags={
                "tier": result.tier,
                "endpoint": result.endpoint
            })
        else:
            metrics.increment("rate_limit.denied", tags={
                "tier": result.tier,
                "endpoint": result.endpoint,
                "rule": result.rule_name
            })
        
        # Track remaining quota
        metrics.gauge("rate_limit.remaining", result.remaining, tags={
            "user": result.user_id
        })
        
        # Track latency
        metrics.timing("rate_limit.latency", result.check_duration)
```

**Dashboard alerts:**
```yaml
alerts:
  - name: high_rate_limit_denials
    condition: rate(rate_limit.denied) > 1000/min
    severity: warning
    
  - name: rate_limiter_latency
    condition: p99(rate_limit.latency) > 10ms
    severity: critical
    
  - name: redis_failures
    condition: rate(rate_limit.redis_failure) > 0
    severity: critical
```

---

### Q10: Design rate limiting for a multi-region setup.

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                    MULTI-REGION RATE LIMITING                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  US-East                           EU-West                      │
│  ┌─────────────────┐              ┌─────────────────┐          │
│  │ API Servers     │              │ API Servers     │          │
│  │       ↓         │              │       ↓         │          │
│  │ Redis (primary) │◄────sync────►│ Redis (replica) │          │
│  └─────────────────┘              └─────────────────┘          │
│                                                                 │
│  Options:                                                       │
│                                                                 │
│  1. Global limit (synced)                                      │
│     - All regions share same counter                           │
│     - Higher latency (cross-region sync)                       │
│     - Accurate                                                  │
│                                                                 │
│  2. Per-region limit (independent)                             │
│     - Each region has own limit                                │
│     - Low latency                                               │
│     - User can get 2x limit (if hitting both regions)         │
│                                                                 │
│  3. Hybrid                                                      │
│     - Local limit per region                                   │
│     - Global limit synced eventually                           │
│     - Balance of accuracy and latency                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class MultiRegionRateLimiter:
    def __init__(self, local_redis, global_redis, region):
        self.local_redis = local_redis
        self.global_redis = global_redis
        self.region = region
        
    async def is_allowed(self, user_id, global_limit, local_limit):
        # Quick local check first
        local_key = f"ratelimit:{self.region}:{user_id}"
        local_allowed = await self.check_limit(
            self.local_redis,
            local_key,
            local_limit
        )
        
        if not local_allowed:
            return False
        
        # Async global check
        global_key = f"ratelimit:global:{user_id}"
        global_allowed = await self.check_limit(
            self.global_redis,
            global_key,
            global_limit
        )
        
        return global_allowed
```

---

### Bonus: Quick Reference

```
┌─────────────────────────────────────────────────────────────────┐
│                RATE LIMITER - QUICK REFERENCE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Algorithms:                                                    │
│  ├── Token Bucket - Best for APIs (allows burst)               │
│  ├── Leaky Bucket - Best for DDoS (smooth output)              │
│  ├── Fixed Window - Simple but boundary burst issue            │
│  ├── Sliding Log - Accurate but memory heavy                   │
│  └── Sliding Counter - Best balance (accurate + efficient)     │
│                                                                 │
│  Implementation:                                                │
│  ├── Redis + Lua scripts for atomicity                         │
│  ├── Local fallback for Redis failures                         │
│  └── Multi-level: IP → User → API Key                         │
│                                                                 │
│  Response Headers:                                              │
│  ├── X-RateLimit-Limit: 100                                    │
│  ├── X-RateLimit-Remaining: 95                                 │
│  ├── X-RateLimit-Reset: 1705312800                             │
│  └── Retry-After: 30 (on 429)                                  │
│                                                                 │
│  Best Practices:                                                │
│  ├── Fail-open for availability                                │
│  ├── Different limits per tier                                 │
│  ├── Endpoint-specific limits for expensive operations         │
│  └── Monitor denial rates and adjust                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
