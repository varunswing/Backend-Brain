# Sliding Window Log Algorithm

## Cheat Sheet

| Property | Value |
|----------|-------|
| **Memory per user** | **O(N)** — one entry per request (within the window) |
| **Accuracy** | **Perfect** — exact count of requests in the last N seconds |
| **Allows bursts?** | No — strict enforcement at every moment |
| **Complexity** | Higher (sorted set + cleanup logic) |
| **Best for** | Financial systems, payment APIs, security-sensitive endpoints |
| **Used by** | Payment processors, fraud detection systems, sensitive APIs |

---

## The Core Idea

For each user, keep a **log of timestamps**, one entry per request. To check if a new request is allowed:
1. **Remove timestamps older than `window_size`** (they're outside the sliding window).
2. **Count what's left.** If count < limit, allow and append the new timestamp.
3. Otherwise, deny.

> **The key insight:** Unlike Fixed Window which uses *clock-aligned* windows, Sliding Window Log uses a window that **moves with the request** — always "the last N seconds from now". This eliminates the boundary problem entirely.

---

## Visual Model

```
Current time: t = 60s. Window = last 60 seconds (so: t ∈ [0, 60]).

Request log (timestamps, oldest → newest):
   ┌────────────────────────────────────────────────────────┐
   │ 5s  12s  18s  25s  30s  42s  50s  55s  58s            │
   └────────────────────────────────────────────────────────┘
   
Count = 9 entries within [0, 60] → if limit is 10, ALLOW.


Time advances to t = 65s. Window = [5, 65].

   ┌────────────────────────────────────────────────────────┐
   │ 5s  12s  18s  25s  30s  42s  50s  55s  58s            │
   └─┬──────────────────────────────────────────────────────┘
     │
     └─── 5s is now AT the boundary; older entries (none here) would be removed.
     
After cleanup (entries strictly < 5s removed): still 9 entries in [5, 65].

Time t = 80s. Window = [20, 80].

   ┌────────────────────────────────────────────────────────┐
   │ 25s  30s  42s  50s  55s  58s                          │
   └────────────────────────────────────────────────────────┘
   
   (5, 12, 18 dropped because they're < 20)
   Count = 6 → 4 slots remaining if limit is 10.
```

The window **slides** in real-time as the clock advances. It always represents *the last N seconds*, not "this clock minute".

---

## How It Works — Step by Step

1. **For request at time `t` from user U:**
   1. Load the log for user U (sorted set of timestamps).
   2. **Cleanup:** Remove all entries where `timestamp < t - window_size`.
   3. **Count** remaining entries.
   4. **Decide:**
      - If count < limit → **ALLOW**, append `t` to log.
      - Else → **DENY** (with `Retry-After = (oldest_entry + window_size) - t`).

---

## Pseudocode (For Understanding)

```
function isAllowed(user):
    log = getLog(user)               // sorted set of timestamps
    now = currentTime()
    windowStart = now - windowSize

    // Step 1: Remove timestamps outside the window
    log.removeAllBefore(windowStart)

    // Step 2: Count and decide
    if log.size() < limit:
        log.append(now)
        return ALLOWED(remaining = limit - log.size())
    else:
        oldestInWindow = log.first()
        retryAfter = (oldestInWindow + windowSize) - now
        return DENIED(retryAfter)
```

---

## Worked Example

**Config:** limit = 5 requests per 10-second window.

| Time | Action | Log After Action | Count | Decision |
|------|--------|------------------|-------|----------|
| 0s | Request 1 | [0] | 1 | ALLOW |
| 1s | Request 2 | [0, 1] | 2 | ALLOW |
| 2s | Request 3 | [0, 1, 2] | 3 | ALLOW |
| 3s | Request 4 | [0, 1, 2, 3] | 4 | ALLOW |
| 4s | Request 5 | [0, 1, 2, 3, 4] | 5 | ALLOW |
| 5s | Request 6 | [0, 1, 2, 3, 4] (denied, log unchanged) | 5 | **DENY** (retry in 5s — when 0 expires) |
| 10s | Request 7 | [1, 2, 3, 4, 10] (0 removed at t=10 since 10-10=0) | 5 | ALLOW |
| 11s | Request 8 | [2, 3, 4, 10, 11] | 5 | ALLOW |

**Crucial observation:** At t=11s, requests at t=0, t=1 are no longer in the window. The "10-second window" slides smoothly — no sharp resets.

---

## Why It's the Most Accurate

Consider the **boundary problem** from Fixed Window:
- Fixed Window: 100 reqs at 0:59 + 100 reqs at 1:00 → both allowed (different clock minutes).
- Sliding Window Log: At 1:00, looks back to 0:00 → sees 100 reqs at 0:59 → counts toward the limit. **Correctly rejects** the burst.

```
Fixed Window's view:               Sliding Window Log's view:

[Win 1]│[Win 2]                    │←── last 60 seconds ──→│
█████  │ █████                     │  █████  █████         │
       ▲                                                   ▲
   reset!                                              "now"
   (2× the limit goes through)     (the actual count in any 60-second
                                    window NEVER exceeds the limit)
```

Sliding Window Log gives you the **mathematically correct** answer to "how many requests has this user made in the last N seconds?"

---

## Pros

| Pro | Explanation |
|-----|-------------|
| **Perfect accuracy** | At any instant, you can answer exactly "how many requests in the last N seconds". |
| **No boundary problem** | Window moves continuously — no sharp resets. |
| **Audit-friendly** | The log itself is an audit trail of every request timestamp. |
| **Flexible queries** | Beyond rate limiting, you can ask "how many in last 5s? last minute? last hour?" with the same log. |
| **Fair across users** | No one gets "lucky" timing — every user's window is anchored to their own activity. |

---

## Cons

| Con | Explanation |
|-----|-------------|
| **O(N) memory per user** | If limit = 1000/minute, you store up to 1000 timestamps per user. With 10M users, that's 10B entries. |
| **Expensive cleanup** | Removing old entries from the log can be O(K) where K = entries to remove. |
| **Slower than Fixed Window** | More operations per request (cleanup + count + insert vs single INCR). |
| **Storage cost in Redis** | Redis Sorted Sets are great but pricier than counters. |
| **Cleanup needed even on no-allow** | If you only clean on allow, denied requests can leave stale data. |

---

## When to Use Sliding Window Log

**Use Sliding Window Log when:**
- **Accuracy is critical** — payment processing, fraud detection, financial APIs.
- **Per-user request volume is low** (say, ≤ 1000 in the window) so O(N) memory is acceptable.
- You want an **audit trail** for free (you'd build one anyway).
- The boundary problem of Fixed Window would cause real damage (e.g., abuse, double-charging).

**Avoid Sliding Window Log when:**
- Per-user request volume is very high (millions of requests in window → out-of-memory).
- Operational latency matters more than perfect accuracy → use Sliding Window Counter.
- The use case tolerates some imprecision (use Fixed Window for simplicity).

---

## Real-World Examples

### 1. Payment Processors (Stripe, PayPal)
- "Max 3 charges per card per minute" — needs to be **exact** to prevent fraud.
- Sliding Window Log ensures no boundary-exploiting double-charges.

### 2. Login Attempt Tracking (Security)
- "5 failed logins per minute → temporary lockout" needs accuracy. Allowing 10 attempts in 1 second around a boundary defeats the purpose.
- Each failed timestamp is logged; the system queries "how many failures in the last 60s?"

### 3. SMS / Email Verification Code Resends
- "Don't allow resending the verification code more than 3 times per 5 minutes" — accuracy prevents abuse.

### 4. Financial Trading APIs
- Exchange APIs that enforce strict order-rate limits (e.g., "10 orders/sec maximum") use this approach because boundary spikes could trigger market events.

### 5. Fraud Detection Heuristics
- "Trigger fraud alert if more than 5 transactions in last 60 seconds" — needs precise count, not a Fixed Window approximation.

---

## Edge Cases & Gotchas

### 1. Memory Explosion
A user sending **1000 requests/minute** at a 1-minute window = 1000 timestamps in the log. With 10M users, that's **10 billion entries**.
**Mitigation:** Cap the log size (you don't need more than `limit` entries — beyond that, you already know you're over). If `count > limit`, you can also short-circuit without storing the new timestamp.

### 2. Cleanup on Denials
If you only clean up the log on allowed requests, denied requests leave stale entries forever.
**Fix:** Clean up at the **start of every request**, before deciding allow/deny.

### 3. Time Granularity
Timestamps in milliseconds, microseconds, or nanoseconds?
- **Seconds**: too coarse, two requests in the same second collide.
- **Milliseconds**: good for most APIs.
- **Microseconds**: needed for high-throughput trading systems where multiple requests can occur in the same millisecond.

Use unique values as scores in Redis Sorted Sets to avoid collisions.

### 4. Clock Skew (Distributed)
If two nodes have skewed clocks and write to the same log, timestamps can appear out of order. Use Redis's server time (single source of truth) when possible.

### 5. Append-Only Optimization
After cleanup, if `count >= limit`, you might *not* want to append the denied timestamp (it doesn't matter — it's denied). But some systems append anyway to track *attempted* rate (e.g., for abuse detection).

---

## Redis Implementation (Conceptual)

Store as a **Redis Sorted Set** where:
- **Member** = a unique request ID (or just the timestamp if unique).
- **Score** = the timestamp.

```
KEY: swl:user-123:/api/charge
VALUE (sorted set):
  score=1683456001  member="req-abc"
  score=1683456002  member="req-def"
  score=1683456005  member="req-ghi"
TTL: window_size  (e.g., 60 seconds)
```

The check is a 3-command sequence (wrapped in a Lua script for atomicity):
1. `ZREMRANGEBYSCORE key 0 (now - windowSize)` → cleanup.
2. `ZCARD key` → count.
3. If count < limit: `ZADD key now uniqueId`.

The TTL ensures inactive users' logs are auto-cleaned up.

---

## Memory Optimization: Bounded Log

If the log size can't exceed `limit`, the algorithm becomes O(limit) memory per user, not O(requests sent). Use this trick:
- When the log is at capacity and a new request comes in, **don't bother appending**. Just deny.
- The log only stores *allowed* requests, capped at `limit`.

This gives you O(limit) memory per user instead of O(all requests ever made).

---

## Comparison to Sliding Window Counter

Sliding Window Counter (next file) approximates Sliding Window Log using only O(1) memory. The trade-off:
- **Sliding Window Log:** exact count, but O(N) memory.
- **Sliding Window Counter:** approximate count (±5% typical), but O(1) memory.

For most use cases, **Sliding Window Counter is preferred** because the small approximation error is rarely a problem, and memory savings are huge at scale.

Sliding Window Log shines when **precision matters more than memory** — payments, security, audit-required systems.

---

## Interview Talking Points

1. **"How is this different from Fixed Window?"** → Window moves continuously with each request; doesn't reset at clock boundaries. **No boundary problem.**

2. **"What's the memory footprint?"** → O(N) per user where N = limit (with the bounded-log optimization). Worst case = `users × limit` entries.

3. **"How do you handle 10 million users at this scale?"** → If memory is tight, switch to Sliding Window Counter. Sliding Window Log is best when per-user volume is modest (under 1000/window).

4. **"How do you implement it in Redis?"** → Sorted Set with timestamp as score. Use `ZREMRANGEBYSCORE` to clean up, `ZCARD` to count, `ZADD` to append. Wrap in Lua for atomicity.

5. **"What if I just want approximate counts?"** → Use Sliding Window Counter for O(1) memory at the cost of small accuracy error.

6. **"Can you give an example where this is the only correct choice?"** → Payment fraud limits. "No more than 3 charges in 60 seconds" must be exact — a 4th charge at second 61 should be allowed, but a 4th at second 59 should not. Sliding Window Log does this precisely; Fixed Window can't.

7. **"Why is the log itself useful beyond rate limiting?"** → It's an audit trail. You can answer "how many requests in any window" retroactively. Great for analytics and abuse investigation.
