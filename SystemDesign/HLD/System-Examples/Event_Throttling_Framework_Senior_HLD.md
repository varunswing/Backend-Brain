# Event Throttling Framework System Design — Razorpay Senior SDE Interview Guide

> **Target Role:** Senior Software Development Engineer (Backend / Distributed Systems / Platform)  
> **Company Context:** Razorpay (Payment Events, Webhook Delivery Engine, Merchant API Quotas, Kafka Producer Throttling, High-Volume Refund Processing)  
> **Problem Statement:** *"Design an Event Throttling Framework that controls how many events a user or service can publish or process within a given time window."*

---

## Table of Contents
1. [Interview Strategy & 45-Minute Execution Playbook](#1-interview-strategy--45-minute-execution-playbook)
2. [Step 1: Clarifying Requirements & Scope Definition](#2-step-1-clarifying-requirements--scope-definition)
3. [Step 2: Capacity Estimation & Redis Sizing](#3-step-2-capacity-estimation--redis-sizing)
4. [Step 3: High-Level Architecture](#4-step-3-high-level-architecture)
5. [Step 4: Throttling Algorithms Deep-Dive](#5-step-4-throttling-algorithms-deep-dive)
6. [Step 5: Distributed Atomicity: Production Redis Lua Script](#6-step-5-distributed-atomicity-production-redis-lua-script)
7. [Step 6: Multi-Dimensional Scoping (User, Merchant, Service & Global)](#7-step-6-multi-dimensional-scoping-user-merchant-service--global)
8. [Step 7: Limit Breach Strategies: Reject, Queue, or Backpressure](#8-step-7-limit-breach-strategies-reject-queue-or-backpressure)
9. [Step 8: Fault Tolerance: Fail-Open, Fail-Closed & Local Quotas](#9-step-8-fault-tolerance-fail-open-fail-closed--local-quotas)
10. [Step 9: High-Scale Bottlenecks: Hot Keys, Clock Skew & Retry Storms](#10-step-9-high-scale-bottlenecks-hot-keys-clock-skew--retry-storms)
11. [Step 10: Observability, SRE Metrics & Production Dashboards](#11-step-10-observability-sre-metrics--production-dashboards)
12. [Step 11: Deep-Dive Answers to All Top 12 Follow-Up Questions](#12-step-11-deep-dive-answers-to-all-top-12-follow-up-questions)
13. [Step 12: Candidate Script & Verbal Pitch](#13-step-12-candidate-script--verbal-pitch)

---

## 1. Interview Strategy & 45-Minute Execution Playbook

In a Senior SDE System Design interview at Razorpay, the interviewer is **not** looking for a naive rate-limiter that simply drops HTTP packets. For high-throughput financial infrastructure, you must demonstrate:
1. **Producer vs. Consumer Throttling:** Understanding that throttling can happen at the producer (before publishing to Kafka) or at the consumer (preventing downstream bank gateway overload).
2. **Correctness under High Concurrency:** Multi-instance distributed safety with zero race conditions (atomic token mutation).
3. **Graceful Degradation:** What happens when Redis crashes during flash sales? Does payment processing halt or does the floodgate open?
4. **Multi-Policy Hierarchy:** A payment event might need to satisfy a User limit (e.g., 5 cards/min), a Merchant limit (e.g., 5,000 tx/sec for Swiggy), and a Service-wide Kafka cluster limit (e.g., 100,000 events/sec).

### 45-Minute Execution Timeline
```
┌───────────────────────────────────────────────────────────────────────────┐
│                       45-MINUTE INTERVIEW TIMELINE                        │
├──────────────────┬────────────────────────────────────────────────────────┤
│ 00:00 - 05:00    │ Clarify Requirements, Throttling Action & Scopes       │
│ 05:00 - 10:00    │ Capacity Estimation (Active Buckets, QPS, Redis RAM)   │
│ 10:00 - 18:00    │ High-Level Architecture & Component Separation         │
│ 18:00 - 28:00    │ Deep Dive: Token Bucket vs Sliding Window & Redis Lua  │
│ 28:00 - 36:00    │ Distributed Resiliency: Failover, Fallbacks & Hot Keys │
│ 36:00 - 42:00    │ Multi-Policy Evaluation & Actions (Queue/Backpressure)  │
│ 42:00 - 45:00    │ SRE Metrics, Trade-off Summary & Wrap-up               │
└──────────────────┴────────────────────────────────────────────────────────┘
```

---

## 2. Step 1: Clarifying Requirements & Scope Definition

### Candidate Opening Hook:
> *"Before designing the framework, I'd like to clarify our throttling scope, policy dimensions, target latency overhead, and the exact semantics required when limits are breached—specifically whether we drop, delay, or backpressure upstream producers."*

### Requirement Clarification Matrix

| Parameter | Question to Interviewer | Why It Matters | Standard Assumption (Razorpay Scale) |
|---|---|---|---|
| **Traffic Throughput** | What is peak event generation across the system? | Determines if Redis can be central or needs client-side token batching. | **500,000 Events/sec** aggregate peak across all services |
| **Throttling Scope** | Is the limit enforced per User, per Merchant, per Service, or Globally? | Determines the cache key structure and if multi-level evaluation is required. | **Hierarchical:** Per-Merchant (Tiered) + Per-Service + Global |
| **Breach Action** | When limit is exceeded, do we **Reject**, **Buffer/Delay**, or apply **Backpressure**? | Determines whether we need a bounded in-memory buffer, a Kafka retry topic, or 429 response. | **Configurable per event type:** Critical payments $\to$ Bounded Buffer; Webhooks $\to$ Delay/Queue; Telemetry $\to$ Drop |
| **Latency Budget** | How much latency overhead can the framework add? | Determines network hops to Redis vs local token pre-allocation. | **p95 < 2ms, p99 < 5ms** overhead on event emission |
| **Accuracy vs. Latency** | Is 100% strict limit enforcement required, or is soft bounding ($\pm 5\%$) acceptable? | Hard limits require atomic distributed state per event; soft limits allow local token leasing/batching. | **Strict for financial transactions; Soft for analytics/webhooks** |
| **Fault Tolerance** | If the throttling coordinator (Redis) fails, do we **Fail-Open** or **Fail-Closed**? | Balances financial availability against downstream system crash risk. | **Configurable:** Fail-open for checkout payments; Fail-closed for non-essential webhooks |
| **Dynamic Policies** | Can throttling limits change at runtime without redeploying services? | Requires a distributed configuration plane (etcd / Consul / Kafka config topic). | **Yes**, dynamic runtime updates with sub-minute propagation |

---

## 3. Step 2: Capacity Estimation & Redis Sizing

Let us quantify the hardware and storage footprint for Razorpay's scale.

### A. Assumptions
- **Aggregate System Throughput:** $500,000 \text{ events/sec}$
- **Active Entities (Keys):**
  - $50,000$ active merchants
  - $500$ internal microservice endpoints
  - $1,000,000$ active users during peak hours
- **Total Active Buckets at any second:** $\approx 1,000,000 \text{ keys}$
- **Throttling Algorithm:** Token Bucket (requires 2 fields: `tokens_left` and `last_updated_timestamp`)

### B. Redis Memory Calculation
For each Token Bucket in Redis:
- Hash Key: `throttle:merchant:merch_98765` ($\approx 32 \text{ bytes}$)
- Fields in Hash:
  - `tokens`: 8-byte floating point / integer
  - `last_updated`: 8-byte millisecond epoch timestamp
- Redis dict entry overhead + jemalloc fragmentation: $\approx 160 \text{ bytes}$ per key
- Total RAM for $1,000,000$ active keys:
  $$\text{Raw RAM} = 1,000,000 \times 160 \text{ bytes} \approx 160 \text{ MB}$$
- Even with 10M keys and Redis key TTL expiration:
  $$\text{10M Keys} \times 160 \text{ bytes} \approx 1.6 \text{ GB}$$
*Conclusion: Memory is **never** the bottleneck for Token Bucket rate limiting!*

### C. Network & Redis CPU Bottleneck (The Real Challenge)
- $500,000 \text{ QPS}$ hitting a single Redis instance will saturate the single-threaded CPU event loop (Redis typically handles 80K–120K ops/sec per core).
- To support $500,000 \text{ QPS}$:
  - We need a **Redis Cluster with at least 8 to 10 Primary Shards**.
  - Or, implement **Client-Side Token Leasing (Batching)** where the application checks out 50 tokens at a time, slashing Redis network calls by $50\times$ (down to $10,000 \text{ QPS}$).

---

## 4. Step 3: High-Level Architecture

```
                                +-----------------------------+
                                |  Application Services       |
                                |  (Payment, Refund, Webhook) |
                                +-----------------------------+
                                               |
                                               v
                          +-----------------------------------------+
                          |   Event Throttling Client SDK           |
                          |   - Local Token Buffer (Near-Lease)     |
                          |   - Policy Cache (Caffeine)             |
                          |   - Circuit Breaker (Resilience4j)      |
                          +-----------------------------------------+
                                               |
                     +-------------------------+-------------------------+
                     | Check / Deduct Tokens                             | Pull Rules
                     v                                                   v
        +--------------------------+                       +--------------------------+
        |   Redis Cluster          |                       |   Central Policy Engine  |
        |   (Atomic Lua Scripts)   |                       |   (Control Plane)        |
        |   - Shard by Entity Key  |                       |   - Dynamic Rule Store   |
        |   - High-Availability    |                       |   - Admin Dashboard / API|
        +--------------------------+                       +--------------------------+
                     |                                                   |
                     +-------------------------+                         v
                                               |             [Kafka Policy Config Topic]
                                               v                         |
                                       [Limit Evaluator]                 |
                                               |                         v
                        +----------------------+----------------------+  Local SDK Listeners
                        |                      |                      |
                    [ALLOWED]              [BREACHED]             [BREACHED]
                        |                  (Reject Path)          (Buffer Path)
                        v                      |                      v
             +--------------------+            v            +--------------------+
             | Kafka Event Broker |      HTTP 429 Error     | Bounded Disk/Queue |
             | (Production Queue) |     / Drop Telemetry    | (Delayed Retry)    |
             +--------------------+                         +--------------------+
                        |                                             |
                        v                                             v
             +--------------------+                         +--------------------+
             | Downstream Workers |                         | Delayed Publisher  |
             +--------------------+                         +--------------------+
```

### Component Roles & Separation of Concerns

1. **Throttling Client SDK (Smart Client):**
   - Embedded directly in Java/Go services.
   - Evaluates fast-path decisions.
   - Contains fallback circuit breakers when Redis is unavailable.
2. **Policy Control Plane:**
   - Decouples policy definition from enforcement.
   - Allows operations and merchant-success teams to change rate limits dynamically without redeploying microservices.
   - Pushes updates via Kafka or ZooKeeper/etcd watches.
3. **Distributed Rate Store (Redis Cluster):**
   - Holds shared global token buckets across 50+ app pods.
   - Evaluates atomic Lua scripts for token deduction.
4. **Action Dispatcher:**
   - Executes the configured breach policy: Reject (Drop / 429), Queue (Buffer in local ring buffer / dead-letter queue), or Backpressure (throttle producer thread).

---

## 5. Step 4: Throttling Algorithms Deep-Dive

Interviewers will drill you on why you chose one algorithm over another. Be prepared to compare all four with exact trade-offs.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                       ALGORITHM COMPARISON OVERVIEW                         │
├──────────────────┬─────────────────┬──────────────────┬─────────────────────┤
│ Algorithm        │ Memory Overhead │ Burst Tolerance  │ Implementation Cost │
├──────────────────┼─────────────────┼──────────────────┼─────────────────────┤
│ Fixed Window     │ Lowest (1 int)  │ Poor (2x burst)  │ Trivial             │
│ Sliding Log      │ High (O(N) ts)  │ Perfect Smooth   │ Expensive           │
│ Sliding Counter  │ Low (2 ints)    │ Good Smooth      │ Moderate            │
│ Token Bucket     │ Very Low (2 num)│ Configurable     │ Standard (Best)     │
│ Leaky Bucket     │ Low (Queue size)│ No Bursts Allowed│ Queue Management    │
└──────────────────┴─────────────────┴──────────────────┴─────────────────────┘
```

### 1. Fixed Window Counter
- Divides time into fixed windows (e.g., 12:00:00 to 12:00:59).
- **The Boundary Flaw:** If a client sends 100 requests at 12:00:59 and 100 requests at 12:01:00, **200 requests pass in a 2-second window**, violating the 100 req/min rule by $2\times$.

### 2. Sliding Window Log
- Logs every event's timestamp into a Redis Sorted Set (`ZSET`).
- When a new event arrives:
  1. Remove all timestamps older than `currentTime - windowSize` (`ZREMRANGEBYSCORE`).
  2. Count remaining elements (`ZCARD`).
  3. If count < limit, add current timestamp (`ZADD`) and allow.
- **Flaw:** Memory scales with $O(N)$ where $N$ is event volume. At 100,000 QPS, Redis RAM collapses under millions of stored timestamps.

### 3. Sliding Window Counter (Approximation)
- Blends the previous window's counter with the current window's counter based on elapsed percentage:
  $$\text{Count} = (\text{Count}_{\text{prev}} \times (1 - \text{progress})) + \text{Count}_{\text{curr}}$$
- Extremely memory efficient ($O(1)$) and prevents boundary spikes with $<5\%$ error.

### 4. Token Bucket (The Gold Standard)
- Capacity: $B$ (max burst size).
- Refill Rate: $R$ tokens per second.
- **Why it wins for Razorpay:** It permits legitimate bursts (e.g., checkout page loading 10 assets simultaneously) while strictly enforcing sustained throughput. Tokens refill lazily on-the-fly without needing background timer threads.

---

## 6. Step 5: Distributed Atomicity: Production Redis Lua Script

In a distributed environment with 20 application pods, calling `GET tokens`, computing in Java, and calling `SET tokens` introduces a **fatal race condition**. Two pods will read `tokens = 1`, both allow their events, and over-consume the quota.

### The Atomic Token Bucket Lua Script
This script performs lazy token refill, capacity clamping, token consumption, and TTL management **atomically in a single Redis execution**.

```lua
-- KEYS[1]: Rate limit key (e.g., 'throttle:merchant:merch_12345')
-- ARGV[1]: Bucket Capacity (max tokens allowed)
-- ARGV[2]: Refill Rate (tokens per millisecond)
-- ARGV[3]: Current Timestamp (epoch milliseconds from caller)
-- ARGV[4]: Requested Tokens (usually 1)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local now = tonumber(ARGV[3])
local requested = tonumber(ARGV[4])

-- 1. Fetch current bucket state from Redis Hash
local data = redis.call('HMGET', key, 'tokens', 'last_updated')
local current_tokens = tonumber(data[1])
local last_updated = tonumber(data[2])

if not current_tokens then
    -- First time seeing this bucket: initialize with full capacity
    current_tokens = capacity
    last_updated = now
else
    -- 2. Lazy refill: calculate tokens generated since last access
    local elapsed = now - last_updated
    if elapsed > 0 then
        local generated_tokens = elapsed * refill_rate
        current_tokens = math.min(capacity, current_tokens + generated_tokens)
        last_updated = now
    end
end

-- 3. Check availability
if current_tokens >= requested then
    -- Deduct tokens and persist state
    current_tokens = current_tokens - requested
    redis.call('HMSET', key, 'tokens', current_tokens, 'last_updated', last_updated)
    
    -- Auto-expire inactive buckets: TTL = time needed to fully refill to capacity
    local ttl_seconds = math.ceil(capacity / (refill_rate * 1000)) + 60
    redis.call('EXPIRE', key, ttl_seconds)
    
    -- Return: 1 = ALLOWED, remaining tokens, retry_after_ms = 0
    return {1, math.floor(current_tokens), 0}
else
    -- Bucket exhausted: save updated timestamp and return rejection
    redis.call('HMSET', key, 'tokens', current_tokens, 'last_updated', last_updated)
    
    -- Calculate exact wait time in milliseconds until enough tokens refill
    local missing_tokens = requested - current_tokens
    local retry_after_ms = math.ceil(missing_tokens / refill_rate)
    
    -- Return: 0 = REJECTED, remaining tokens, retry_after_ms
    return {0, math.floor(current_tokens), retry_after_ms}
end
```

### Java Client Integration (Spring Boot / Jedis / Lettuce)

```java
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Component
public class DistributedEventThrottler {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;

    public DistributedEventThrottler(StringRedisTemplate redisTemplate, DefaultRedisScript<List> tokenBucketScript) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
    }

    public ThrottleResult tryAcquire(String entityKey, double capacity, double tokensPerSecond, int requestedTokens) {
        List<String> keys = Collections.singletonList("throttle:" + entityKey);
        
        double refillRatePerMs = tokensPerSecond / 1000.0;
        long nowMs = Instant.now().toEpochMilli();

        try {
            List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                keys,
                String.valueOf(capacity),
                String.valueOf(refillRatePerMs),
                String.valueOf(nowMs),
                String.valueOf(requestedTokens)
            );

            if (result == null || result.isEmpty()) {
                // If Redis returns null, trigger fallback policy
                return fallback(entityKey);
            }

            boolean allowed = result.get(0) == 1L;
            long remainingTokens = result.get(1);
            long retryAfterMs = result.get(2);

            return new ThrottleResult(allowed, remainingTokens, retryAfterMs);
        } catch (Exception ex) {
            // Redis network timeout or failure: execute circuit breaker fallback
            return handleRedisFailure(entityKey, ex);
        }
    }

    private ThrottleResult fallback(String entityKey) {
        // Default fail-open or fail-closed based on config
        return new ThrottleResult(true, 0, 0);
    }

    private ThrottleResult handleRedisFailure(String entityKey, Exception ex) {
        // Log telemetry, increment fallback counter, fail-open for critical payments
        return new ThrottleResult(true, 0, 0);
    }
}
```

---

## 7. Step 6: Multi-Dimensional Scoping (User, Merchant, Service & Global)

A single event rarely has only one limit. In Razorpay:
- **User Scope:** Prevent a single fraudster script from emitting 500 payment attempts.
- **Merchant Scope:** Ensure Merchant A (tier: Standard) cannot exceed 500 webhooks/sec, while Merchant B (tier: Enterprise) is allowed 10,000 webhooks/sec.
- **Downstream Bank Gateway Scope:** HDFC bank integration cannot handle more than 2,000 TPS total across all merchants.

```
                              Incoming Event
                                    |
                                    v
                     +------------------------------+
                     |  Step 1: Check User Quota    |
                     |  throttle:user:{userId}      |
                     +------------------------------+
                                    |
                               [Allowed]
                                    |
                                    v
                     +------------------------------+
                     |  Step 2: Check Merchant Quota|
                     |  throttle:merch:{merchantId} |
                     +------------------------------+
                                    |
                               [Allowed]
                                    |
                                    v
                     +------------------------------+
                     |  Step 3: Check Gateway Quota |
                     |  throttle:gateway:{bankId}   |
                     +------------------------------+
                                    |
                               [Allowed]
                                    |
                                    v
                              Publish Event
```

### The Multi-Key Atomic Problem
If Step 1 deducts a user token, but Step 3 fails because the Bank Gateway is throttled, **the user has lost a token unfairly**.

**Two Production Solutions:**
1. **Multi-Key Lua Script (Hash Tags):**  
   If keys share the same Redis slot using Redis Cluster hash tags `{entity_group}`, an atomic Lua script evaluates all three buckets simultaneously and deducts tokens *only if all three pass*.
2. **Two-Phase Reservation (Token Lease):**  
   Check availability across all tiers without deduction. If all pass, issue a batch atomic deduction. If any tier rejects, zero tokens are consumed.

---

## 8. Step 7: Limit Breach Strategies: Reject, Queue, or Backpressure

When the 101st event arrives and the limit is 100, what happens? **A Senior Engineer must present all three patterns based on the event's business criticality:**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           LIMIT BREACH STRATEGIES                           │
├──────────────────┬─────────────────┬──────────────────┬─────────────────────┤
│ Strategy         │ Latency Impact  │ Data Loss Risk   │ Recommended Use-Case│
├──────────────────┼─────────────────┼──────────────────┼─────────────────────┤
│ 1. Hard Reject   │ Zero (immediate)│ 100% of excess   │ Non-critical APIs,  │
│    (Drop / 429)  │                 │                  │ Metrics, Scrapers   │
├──────────────────┼─────────────────┼──────────────────┼─────────────────────┤
│ 2. Bounded Queue │ High (delayed)  │ Zero (until full)│ Webhooks, Refunds,  │
│    (Buffer/Delay)│                 │                  │ Merchant Async Tasks│
├──────────────────┼─────────────────┼──────────────────┼─────────────────────┤
│ 3. Backpressure  │ Pauses caller   │ Zero             │ Bulk Event Importer,│
│    (Block/Yield) │                 │                  │ Kafka Ingestion     │
└──────────────────┴─────────────────┴──────────────────┴─────────────────────┘
```

### Strategy 1: Hard Reject with Standard HTTP 429
Return standard RFC 6585 response headers:
```http
HTTP/1.1 429 Too Many Requests
Retry-After: 3
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1697805603
Content-Type: application/json

{
    "error": "RATE_LIMIT_EXCEEDED",
    "message": "Quota exceeded for merchant_123. Retry in 3 seconds.",
    "retry_after_ms": 2840
}
```

### Strategy 2: Bounded Delay / Internal Retry Queue
For webhook delivery or payout events:
- Store throttled event in a **Local LMAX Disruptor RingBuffer** or push to a **Kafka Delayed/Retry Topic**.
- A worker thread sleeps for `retry_after_ms` and republishes the event.
- **Safety Rule:** Bounded queue depth (e.g., max 10,000 events). If the queue is 100% full, reject immediately to prevent JVM `OutOfMemoryError`.

### Strategy 3: Upstream Backpressure
For internal microservice producers:
- Use reactive streams (Project Reactor / RxJava) or bounded thread pools.
- When throttled, block the producer thread using `Thread.sleep(retryAfterMs)` or pause Kafka consumer partition consumption (`consumer.pause(topicPartition)`).

---

## 9. Step 8: Fault Tolerance: Fail-Open, Fail-Closed & Local Quotas

Interviewer: *"What happens if Redis becomes completely unreachable or network partitions?"*

```
                     +----------------------------+
                     |  Redis Connection Timeout  |
                     |  (> 10ms threshold)        |
                     +----------------------------+
                                   |
                                   v
                     +----------------------------+
                     |  Circuit Breaker Opens     |
                     |  (Resilience4j / Envoy)    |
                     +----------------------------+
                                   |
              +--------------------+--------------------+
              |                                         |
              v                                         v
   [Critical Payment Route]                  [Non-Critical Route]
              |                                         |
              v                                         v
    +--------------------+                    +--------------------+
    |   FAIL-OPEN MODE   |                    |  FAIL-CLOSED MODE  |
    | - Allow event      |                    | - Return 429 / Drop|
    | - Emit Alert       |                    | - Protect DB       |
    | - Local Safe Quota |                    +--------------------+
    +--------------------+
```

### 1. Fail-Open (Prioritize Availability)
- Used for Razorpay **Checkout Payments**.
- We never want to reject a customer paying a 5,000 INR bill because our Redis rate-limiter is slow.
- The event is permitted, an operational metric `throttle.redis.failure.fail_open` is incremented, and an alert is paged.

### 2. Fail-Closed (Prioritize Protection)
- Used for **Unauthenticated APIs, Sign-ups, or Public Webhook endpoints**.
- Reject incoming traffic to protect downstream MySQL and Kafka clusters from being brought down by malicious DDOS attacks.

### 3. Local In-Memory Fallback Quota (The Senior Approach)
Instead of pure fail-open, use an in-memory fallback:
- Each application pod holds a local Guava/Caffeine Token Bucket initialized to:
  $$\text{Local Pod Quota} = \frac{\text{Global Quota}}{\text{Number of Active Pods}} \times 0.5$$
- Enforcing a conservative local quota prevents catastrophic downstream overload while keeping the service alive!

---

## 10. Step 9: High-Scale Bottlenecks: Hot Keys, Clock Skew & Retry Storms

### A. The Hot-Key Bottleneck (Token Pre-Allocation / Leasing)
If merchant `ZOMATO` sends 100,000 events/sec:
- Calling Redis 100,000 times/sec for `throttle:merchant:ZOMATO` overwhelms the single Redis node holding that key.

#### Solution: Token Leasing (Batching)
```
Application Pod A                         Redis (Shared State)
      |                                           |
      | 1. Request batch of 100 tokens ---------->|
      |<-- 2. Lease granted (tokens: 100) --------|
      |                                           |
      | [Locally satisfies next 100 requests]     |
      |                                           |
      | 3. Batch exhausted, request next 100 ---->|
```
- The application pod requests a **lease of 100 tokens** from Redis in a single atomic call.
- The pod consumes those 100 tokens locally in memory with zero network latency.
- Reduces Redis QPS on the hot key by **$100\times$**!

### B. Clock Skew Handling
- Distributed system nodes have clock drift (NTP synchronization skew of 5–50ms).
- If Pod A sends a timestamp 20ms ahead of Pod B, token refill calculations in Lua become erratic.
- **Solution:** Never rely on client-reported timestamps in Lua. Call Redis's internal clock:
  ```lua
  local redis_time = redis.call('TIME')
  local now_ms = (tonumber(redis_time[1]) * 1000) + math.floor(tonumber(redis_time[2]) / 1000)
  ```
  This guarantees that all calculations use the **authoritative, monotonic Redis server clock**.

### C. Retry Storm Mitigation
When a batch of events is rejected, naive clients immediately retry in a tight loop, compounding the outage.
- **Solution:** Always enforce **Exponential Backoff with Full Jitter** in client SDKs:
  $$\text{Sleep} = \text{random}(0, \, \min(\text{MaxBackoff}, \, \text{Base} \times 2^{\text{attempt}}))$$

---

## 11. Step 10: Observability, SRE Metrics & Production Dashboards

A Senior Engineer designs for operability. You must specify the exact Prometheus/Datadog metrics required:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          KEY OBSERVABILITY METRICS                          │
├──────────────────────────┬──────────────┬───────────────────────────────────┤
│ Metric Name              │ Type         │ Description                       │
├──────────────────────────┼──────────────┼───────────────────────────────────┤
│ `events.throttled.total` │ Counter      │ Tagged by `merchant_id`, `reason` │
│ `events.allowed.total`   │ Counter      │ Rate of successfully passed events│
│ `redis.lua.latency.ms`   │ Histogram    │ p95 and p99 of Lua script run time│
│ `circuit_breaker.state`  │ Gauge        │ 0 = Closed, 1 = Open, 2 = Half-Open│
│ `local_buffer.depth`     │ Gauge        │ Current count of delayed events   │
│ `hot_key.detected`       │ Counter      │ Alert on keys exceeding 10K QPS   │
└──────────────────────────┴──────────────┴───────────────────────────────────┘
```

### Production Alerting Thresholds
1. **P99 Redis Latency > 10ms:** Indicates Redis CPU exhaustion or network saturation; trigger auto-sharding.
2. **Throttled Ratio > 20% for Enterprise Merchant:** Indicates misconfigured quota; alert merchant integration engineering.
3. **Circuit Breaker Open > 30 seconds:** PagerDuty alert: Rate-limiting tier degraded, fail-open active.

---

## 12. Step 11: Deep-Dive Answers to All Top 12 Follow-Up Questions

### Q1: What is the primary difference between Rate Limiting and Event Throttling?
**Answer:**  
Rate limiting typically focuses on restricting incoming API requests from clients (users/IPs) at the perimeter and usually terminates in an HTTP 429 response. Event Throttling controls the publishing, ingestion, or downstream dispatch rate of asynchronous messages within a distributed architecture (e.g., before publishing to Kafka or dispatching webhooks to bank gateways). While rate limiting primarily rejects, event throttling includes buffering, delayed execution, smoothing traffic bursts, and applying backpressure.

---

### Q2: Why does maintaining independent local in-memory counters on application pods fail?
**Answer:**  
In a distributed setup with $N$ application instances behind a load balancer, local counters cannot coordinate. If our target limit is 100 events/second globally, and each of the 5 instances maintains its own 100 events/second counter, total throughput can reach **500 events/second** (if traffic distributes evenly) or reject prematurely (if traffic lands on a single instance). Shared state (like Redis) or coordinated token allocation is required to enforce a unified global quota.

---

### Q3: Why is Redis Lua scripting better than standard multi-command Redis transactions (MULTI/EXEC)?
**Answer:**  
Standard `MULTI/EXEC` transactions in Redis are optimistic and do not allow **conditional branch evaluation** based on the results of earlier operations within the same transaction. For a Token Bucket, you must read the current tokens, calculate refill based on elapsed time, check if `tokens >= requested`, and *then* decide whether to deduct. With `MULTI/EXEC`, you would need `WATCH` (optimistic locking), which suffers severe failure rates under high concurrency. Lua scripts run natively inside Redis as a single atomic unit, executing all logic in one round-trip with zero lock contention.

---

### Q4: How do you prevent the boundary burst issue in Fixed Window algorithms?
**Answer:**  
Fixed Window resets counters at fixed wall-clock intervals (e.g., every minute on the minute). If a user fires 100 requests at 12:00:59 and another 100 requests at 12:01:00, 200 requests pass in a 2-second window.  
We eliminate this by using either:
1. **Token Bucket:** Refills continuously per millisecond, allowing only configured burst capacity.
2. **Sliding Window Counter:** Approximates request rate by calculating a weighted average:
   $$\text{Current Count} = \text{Current Window} + (\text{Previous Window} \times \text{Remaining Ratio})$$

---

### Q5: What is the memory footprint of Token Bucket vs Sliding Window Log in Redis?
**Answer:**  
- **Token Bucket:** Requires only two numeric values: `tokens` and `last_updated_timestamp`. Stored as a Redis Hash or packed binary, it takes $\approx 160 \text{ bytes}$ per key regardless of event volume.
- **Sliding Window Log:** Stores an individual timestamp for every single request in a Redis Sorted Set (`ZSET`). For 10,000 requests in a 1-minute window, it stores 10,000 set members ($\approx 10,000 \times 64 \text{ bytes} \approx 640 \text{ KB}$ per key). At scale with millions of users, Sliding Window Log causes catastrophic memory exhaustion.

---

### Q6: If an event needs to satisfy both a User Limit and a Merchant Limit, how do you handle partial failures atomically?
**Answer:**  
If a user has quota, but the merchant's global limit is exhausted, deducting the user's token creates an unfair quota loss.  
Two solutions:
1. **Composite Lua Script with Hash Tags:** If keys are co-located on the same Redis cluster node using Redis hash tags (e.g., `{merchant_123}:user:456` and `{merchant_123}:global`), a single Lua script checks both buckets and deducts tokens only if both have sufficient capacity.
2. **Two-Phase Commit / Compensation:** Client checks both buckets. If either fails, emit a compensation call to refund the consumed token.

---

### Q7: How do you handle a "Hot Key" where a single merchant sends 50,000 QPS to Redis?
**Answer:**  
A single key resides on one Redis shard, saturating its single-threaded event loop.  
**Mitigations:**
1. **Client-Side Token Leasing (Batching):** The application pod requests a lease of 50 or 100 tokens from Redis in one network trip, satisfying the next 100 local requests entirely in JVM memory.
2. **Key Partitioning / Sub-Bucketing:** Split the merchant key into $K$ sub-keys: `throttle:merch_123:1` through `throttle:merch_123:5`, each with $\frac{1}{5}$th of the capacity. Clients choose a random sub-key, distributing the 50,000 QPS across 5 distinct Redis shards.

---

### Q8: What happens during network partitions or Redis outages? Fail-Open or Fail-Closed?
**Answer:**  
The choice is business-driven:
- **Fail-Open:** For core checkout payment processing, allow the event. Missing a rate limit is acceptable; losing revenue or failing a customer payment is not.
- **Fail-Closed:** For unauthenticated public endpoints, webhook distribution to external partners, or expensive batch reports, drop the event or return HTTP 429 to protect internal microservices and downstream third-party gateways from cascading failure.
- **Hybrid Local Fallback:** When the circuit breaker trips, fall back to an in-memory local token bucket with a conservative fractional quota ($\frac{\text{Global Quota}}{\text{Pods}} \times 0.5$).

---

### Q9: How do you handle clock drift across different application servers?
**Answer:**  
If application servers pass their local system timestamps (`System.currentTimeMillis()`) to Redis, server clock skew (NTP drift) will cause timestamps to move backwards or jump forwards, corrupting the token refill rate.  
**Solution:** Do not trust client timestamps. Inside the Redis Lua script, call Redis's internal `redis.call('TIME')` command. This ensures that token calculations across all 100 application pods are computed against a single, authoritative, monotonic clock source.

---

### Q10: How do you update throttling limits dynamically at runtime without restarting services?
**Answer:**  
We build a **Control Plane** backed by a distributed key-value store (etcd, Consul) or a dedicated Kafka topic (`rate-limit-configs` with log compaction).  
1. When an admin updates a limit in the dashboard, the change is written to etcd.
2. Application SDKs maintain a local Caffeine cache of policy definitions and listen to etcd watch events or consume the Kafka config topic.
3. Upon receiving an update event, the SDK updates its in-memory policy cache in sub-second time without service interruption.

---

### Q11: What is the Thundering Herd / Retry Storm problem in rate limiting, and how do you prevent it?
**Answer:**  
When an event is throttled and clients receive an error or retry header, thousands of clients often retry simultaneously at the exact moment the quota window resets, creating a massive traffic spike that knocks the service down again.  
**Mitigation:**
1. Return precise, staggered `Retry-After` headers.
2. Require clients to implement **Exponential Backoff with Full Jitter**:
   $$\text{Backoff} = \text{random}(0, \, \min(\text{MaxWait}, \, \text{Base} \times 2^{\text{attempt}}))$$
3. Token Bucket natively dampens retry storms because tokens refill continuously rather than in an abrupt, synchronized reset at window boundaries.

---

### Q12: How would you throttle consumers processing events from a Kafka topic?
**Answer:**  
Throttling consumers is critical to avoid overwhelming downstream partner APIs (e.g., bank payout networks).  
1. Before processing an event pulled from a Kafka partition, the consumer calls `throttler.tryAcquire()`.
2. If throttled, the consumer does **not** commit the offset.
3. The consumer calls `consumer.pause(Set.of(topicPartition))` to halt polling that specific partition without triggering a consumer group rebalance.
4. A scheduled executor sleeps for the returned `retry_after_ms` and invokes `consumer.resume(Set.of(topicPartition))`.
5. This halts message ingestion cleanly while preserving strict partition ordering.

---

## 13. Step 12: Candidate Script & Verbal Pitch

Use this word-for-word pitch during your Razorpay Senior SDE interview to maintain control, sound senior, and pace your answers effectively:

### Opening Pitch (00:00 – 05:00)
> *"Hello! To design an Event Throttling Framework for Razorpay's ecosystem, I'd like to structure our discussion across five key pillars:*  
> *First, we will clarify requirements, identifying whether we are throttling producers before Kafka or consumers before downstream bank gateways, and defining our breach semantics—whether to drop, queue, or backpressure.*  
> *Second, we will size the cluster and establish our high-level architecture separating the control plane, smart SDK, and distributed state store.*  
> *Third, we will evaluate rate-limiting algorithms and implement a production-grade, atomic Token Bucket using a Redis Lua script.*  
> *Fourth, we will tackle high-scale failure modes: hot keys, clock skew, dynamic policy updates, and fail-open/fail-closed circuit breakers.*  
> *Finally, we'll review observability metrics and operational trade-offs.*  
>  
> *For our baseline, I will assume 500,000 aggregate events/sec, sub-2ms latency overhead, multi-tenant merchant and service scoping, and a requirement for zero race conditions across 50+ app pods. Does this scope align with what you'd like to explore?"*

### Architecture & Algorithm Pitch (10:00 – 20:00)
> *"For our core algorithm, I choose Token Bucket over Fixed Window and Sliding Log. Fixed Window suffers from 2x boundary bursts, while Sliding Log creates an unsustainable $O(N)$ memory footprint in Redis. Token Bucket provides constant $O(1)$ memory, allows configurable bursts for checkout spikes, and handles continuous refill lazily.*  
>  
> *To enforce this across multiple app instances without race conditions, our SDK executes an atomic Lua script directly in Redis Cluster. The script computes elapsed time, refills tokens, and deducts quota in a single execution step. For clock consistency, we rely strictly on the Redis server clock rather than client timestamps.*  
>  
> *If an enterprise merchant pushes 50,000 events/sec to a single key, calling Redis per event will saturate that single shard. We introduce Client-Side Token Leasing: each app pod pre-allocates a batch of 100 tokens, serving subsequent requests locally in JVM memory and cutting Redis QPS by 100x."*

### Failure & Wrap-Up Pitch (35:00 – 45:00)
> *"For resiliency, we wrap our Redis calls in a Resilience4j circuit breaker. If Redis latency spikes or nodes fail, we apply route-specific degradation: critical payment transactions fail-open with local conservative token caps, while unauthenticated webhooks fail-closed to protect our infrastructure.*  
>  
> *On the observability front, we export p99 Redis latency, throttled events per merchant, and circuit breaker status to Prometheus and Datadog. This guarantees that our throttling framework shields Razorpay's financial core without becoming a point of failure itself.*  
>  
> *I'm excited to dive deeper into our Lua script edge cases, Kafka consumer pause-resume semantics, or our dynamic policy propagation plane."*

---

## Summary Checklist for Razorpay Event Throttling Preparation
- [x] Clear requirements & mathematical capacity estimation (500K QPS, RAM sizing).
- [x] Four algorithms compared with explicit trade-offs (Fixed, Sliding Log, Sliding Counter, Token Bucket).
- [x] Complete, production-ready Redis Lua script with lazy refill and TTL auto-expiry.
- [x] Multi-dimensional scoping (User, Merchant, Service, Gateway) explained.
- [x] Three limit-breach strategies: Reject (429), Bounded Queue (Disruptor), and Upstream Backpressure.
- [x] Fail-open vs Fail-closed vs In-memory fallback quotas articulated.
- [x] Hot keys solved via Client-Side Token Leasing (Batching).
- [x] Clock skew resolved via Redis server clock (`TIME`).
- [x] All top 12 Senior SDE follow-up questions answered in exhaustive depth.
