# Token Bucket Algorithm

## Cheat Sheet

| Property | Value |
|----------|-------|
| **Memory per user** | O(1) — two numbers: token count + last refill time |
| **Accuracy** | Good (smooths over time) |
| **Allows bursts?** | **YES** — up to bucket capacity |
| **Complexity** | Medium |
| **Best for** | APIs that need flexibility, login attempts, user-facing actions |
| **Used by** | AWS API Gateway, Stripe, Discord, most cloud APIs |

---

## The Core Idea

Imagine a **bucket** that holds tokens. Each incoming request must "pay" by taking **one token** out of the bucket. If the bucket is empty, the request is rejected. The bucket refills at a **constant rate** (e.g., 10 tokens per second), but cannot exceed its capacity.

> **The key insight:** Tokens accumulate when traffic is low, so when a burst arrives, the bucket is already full and can absorb it.

---

## Visual Model

```
                  refill rate: 5 tokens/sec
                          │
                          ▼
                  ┌───────────────┐
                  │  ●●●●●●●●●●   │  ← bucket capacity = 10
                  │  ●●●●●●●●●●   │
                  └───────┬───────┘
                          │
                          │  each request consumes 1 token
                          ▼
                  ┌───────────────┐
                  │   Request     │
                  │   incoming    │
                  └───────────────┘
                          │
                  ┌───────┴───────┐
                  │               │
            tokens >= 1     tokens < 1
                  │               │
                  ▼               ▼
              ALLOWED          DENIED
            (consume 1)       (429 error)
```

---

## How It Works — Step by Step

1. **Initialize:** Bucket starts with `capacity` tokens (often full).
2. **On each request:**
   1. **Refill:** Compute how many tokens should have been added since the last access:
      `tokens_to_add = (time_now − last_refill) × refill_rate`
   2. Add those tokens but **cap at `capacity`** (the bucket can't overflow).
   3. **Check:** If `tokens >= 1`, decrement by 1 and **allow** the request.
   4. **Else:** **Reject** with a `Retry-After` calculated from how long until 1 token refills.
3. **Update** `last_refill = time_now`.

> **Important:** Refill is **lazy** — we don't run a background timer. We compute the refill when a request arrives. With 10 million users, that's 10M timers we *don't* need.

---

## Pseudocode (For Understanding, Not Production)

```
function isAllowed(user):
    bucket = getBucket(user)              // load or create
    elapsed = now() - bucket.lastRefill
    tokensToAdd = elapsed * refillRate
    bucket.tokens = min(capacity, bucket.tokens + tokensToAdd)
    bucket.lastRefill = now()

    if bucket.tokens >= 1:
        bucket.tokens -= 1
        return ALLOWED
    else:
        retryAfter = (1 - bucket.tokens) / refillRate
        return DENIED(retryAfter)
```

---

## Worked Example

**Config:** capacity = 10 tokens, refill rate = 2 tokens/sec.

| Time | Event | Tokens Before | Refill Added | Tokens After | Decision |
|------|-------|--------------|-------------|-------------|----------|
| 0.0s | Start | — | — | 10.0 | — |
| 0.0s | Request | 10.0 | 0 | 9.0 | ALLOW |
| 0.1s | Request | 9.0 | +0.2 | 8.2 | ALLOW |
| 0.2s | Request × 8 | 8.2 | +0.2 | 0.4 | ALLOW (last drains to 0.4) |
| 0.2s | Request | 0.4 | 0 | 0.4 | **DENY** (need 1 token) |
| 1.0s | Request | 0.4 | +1.6 | 1.0 | ALLOW → 0.0 |

**Note the burst:** At t=0, we served 10 requests *instantly*. Then the rate limiter throttles us to ~2 req/sec — the steady refill rate.

---

## Why Bursts Are Allowed (And Why That's a Feature)

Real user traffic is **bursty**:
- A user clicks "Search" 5 times in 2 seconds because they're frustrated.
- A mobile app reconnects after losing network and fires off 20 queued requests.
- A user clicks a button rapidly while waiting for a response.

Token Bucket says: *"That's fine, as long as you've been quiet recently."* The accumulated tokens cushion the burst.

Contrast with a strict "100 req/min" Fixed Window: that user gets *one* request every 0.6 seconds, with no flexibility.

---

## Pros

| Pro | Explanation |
|-----|-------------|
| **Allows bursts** | Bucket fills during quiet periods, drains during spikes — matches real traffic patterns. |
| **Memory efficient** | Just 2 numbers per user (tokens, timestamp). |
| **Smooth over time** | The refill rate sets the long-term average; bursts don't violate it. |
| **No background jobs** | Lazy refill computes everything on access. |
| **Easy to tune** | Two knobs: capacity (burst size) and refill rate (steady rate). |

---

## Cons

| Con | Explanation |
|-----|-------------|
| **Burst can hammer downstream** | If 1000 users burst at once, downstream services see a spike. |
| **Tuning is non-obvious** | Picking capacity vs refill rate requires understanding traffic patterns. |
| **Not truly "fair" mid-burst** | A user can grab 100 tokens in 1 second and starve their own next-second budget. |
| **Distributed = trickier** | In a multi-node setup, you need atomic Redis ops (Lua script) to avoid race conditions. |

---

## When to Use Token Bucket

**Use Token Bucket when:**
- Users naturally produce bursty traffic (web/mobile clients).
- You want to *feel* permissive while still enforcing a long-term limit.
- The downstream service can absorb short spikes.
- You're protecting against sustained abuse, not micro-bursts.

**Avoid Token Bucket when:**
- The downstream service is fragile and *cannot* handle bursts (use Leaky Bucket instead).
- You need strict, predictable output rate (e.g., a job scheduler hitting an external API).

---

## Real-World Examples

### 1. AWS API Gateway
- Each API method has a **steady-state rate** (refill) and **burst limit** (capacity).
- Example default: 10,000 RPS steady, 5,000 burst.
- This is **textbook Token Bucket**.

### 2. Stripe API
- Stripe documents their limits as "100 read ops/sec **in live mode**" — but allows brief spikes during a busy checkout.
- During holiday sales, the bucket capacity matters more than the refill rate.

### 3. Login Attempts
- A user typo'd their password 3 times in 10 seconds — allow that (capacity = 5 burst).
- But after 5 failures, throttle to 1 attempt every 30 seconds (refill rate = 1/30 per sec).
- This punishes attackers without locking out legitimate users.

### 4. Discord Message Sending
- Bots/users can send a few messages quickly, but sustained spam triggers the limit.
- Bucket capacity = 5 messages, refill = 1 message per 2 seconds.

---

## Edge Cases & Gotchas

### 1. Clock Drift in Distributed Systems
If you store `last_refill` as a wall-clock timestamp and your nodes have skewed clocks, refill calculations break.
**Fix:** Use a monotonic clock per node OR use the Redis server's time (`TIME` command) as the source of truth.

### 2. Fractional Tokens
The refill formula can produce decimals (e.g., 0.3 tokens). You can either:
- Track tokens as a **float** (allow 0.3 + 0.7 = consume 1 next request).
- Floor to integer (slightly less precise but simpler).

Production systems typically use floats.

### 3. The "Always Empty" User
A user who hits the limit every second forever has `tokens` permanently at 0. The retry-after must be calculated correctly:
`retry_after = (1 − tokens) / refill_rate`

### 4. Distributed Race Condition
Two nodes simultaneously do `read → compute → write`. Both think `tokens = 1` and both allow → 2 requests served.
**Fix:** Use Redis Lua script for atomic read-modify-write, OR use Redis `WATCH/MULTI/EXEC` optimistic locking.

---

## Redis Implementation (Conceptual)

Store as a **Redis Hash**:
```
KEY: tb:user-123:/api/search
HASH FIELDS:
  tokens      → 7.5
  last_refill → 1683456789.123
TTL: 1 hour  (auto-cleanup of inactive users)
```

**Why Lua script?** A single Lua script runs **atomically** inside Redis — no race conditions between read and write. This is the standard production pattern.

**Why TTL?** If a user goes silent for an hour, why keep their bucket in memory? Let Redis auto-expire it. Next request rebuilds it (full).

---

## Interview Talking Points

1. **"Why not just use a leaky bucket?"** → Token Bucket *allows* bursts; Leaky Bucket *prevents* them. Choose based on whether bursts help or hurt.

2. **"How do you make it distributed?"** → Redis + Lua script for atomicity. Each node calls the same script; Redis serializes execution.

3. **"What if Redis goes down?"** → **Fail-open** (allow traffic — partial outage > full outage) or **fail-closed** (reject — safer for sensitive endpoints). Have a per-endpoint policy.

4. **"How do you size the bucket?"** → Capacity = expected burst size (P99 of burst from a single user). Refill rate = sustainable long-term rate. Measure your traffic first.

5. **"How does it compare to Sliding Window?"** → Token Bucket uses less memory (O(1) vs O(N) for log) and allows bursts. Sliding Window is more accurate but stricter.
