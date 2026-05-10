# Sliding Window Counter Algorithm

## Cheat Sheet

| Property | Value |
|----------|-------|
| **Memory per user** | O(1) — just two counters (previous + current window) |
| **Accuracy** | **Very good** (typically within 1% error of true count) |
| **Allows bursts?** | No — smooth enforcement (no boundary problem) |
| **Complexity** | Medium |
| **Best for** | High-scale public APIs needing accurate, smooth rate limiting |
| **Used by** | **Cloudflare**, large CDN/edge platforms, modern API gateways |

---

## The Core Idea

A **hybrid** of Fixed Window and Sliding Window Log that captures the best of both:
- **Memory efficiency** of Fixed Window (just counters).
- **Smoothness** of Sliding Window Log (no boundary problem).

The trick: maintain counters for the **previous window** and the **current window**, then compute a **weighted average** based on how far into the current window we are.

> **The key insight:** You don't need every single timestamp to approximate "how many requests in the last N seconds". A weighted blend of the previous and current fixed-window counters gives you ~99% accuracy with O(1) memory.

---

## Visual Model

```
Time:    0:00s            1:00s            2:00s
         │                │                │
         │  Window 1      │   Window 2     │
         │  count = 80    │   count = 30   │
         │                │                │
         └────────────────┴────────────────┘
                          ↑
                       NOW = 1:18s (18 seconds into Window 2)
                       
       Fraction of Window 2 elapsed: 18 / 60 = 30%
       Fraction of Window 1 still in sliding window: 70%

       Estimated count in last 60 seconds:
         = (prev_count × 70%) + curr_count
         = (80 × 0.70) + 30
         = 56 + 30
         = 86 requests in the last 60 seconds

       If limit = 100 → ALLOW. If limit = 80 → DENY.
```

---

## How It Works — Step by Step

1. **Compute window IDs:**
   - `curr_window = floor(now / window_size)`
   - `prev_window = curr_window - 1`

2. **Lookup counters** for both windows (often `prev_count` may be 0 if no prior activity).

3. **Compute fraction of current window elapsed:**
   `fraction_curr = (now mod window_size) / window_size`
   This is "how much of the current window we've already used", in [0, 1].

4. **Compute weight of previous window:** `weight_prev = 1 - fraction_curr`
   This says: "the previous window's count contributes to the sliding view, scaled down as we move further from it."

5. **Weighted estimate:**
   `estimated_count = (prev_count × weight_prev) + curr_count`

6. **Decide:**
   - If `estimated_count < limit` → **ALLOW** (increment `curr_count`).
   - Else → **DENY**.

---

## Pseudocode (For Understanding)

```
function isAllowed(user):
    now = currentTime()
    currWindow = floor(now / windowSize)
    prevWindow = currWindow - 1

    prevCount = getCount(user, prevWindow) or 0
    currCount = getCount(user, currWindow) or 0

    elapsedInCurr = now mod windowSize
    fractionCurr = elapsedInCurr / windowSize
    weightPrev = 1 - fractionCurr

    estimated = (prevCount × weightPrev) + currCount

    if estimated < limit:
        increment(user, currWindow)         // atomic incr in Redis
        return ALLOWED(remaining = limit - estimated - 1)
    else:
        return DENIED
```

---

## Worked Example

**Config:** limit = 100 req/minute. Window 1 already had 80 requests; we're now in Window 2.

### Snapshot 1: t = 1:00 (start of Window 2)

- `fraction_curr = 0` (just entered Window 2)
- `weight_prev = 1.0` (all of Window 1's recent traffic counts)
- `estimated = (80 × 1.0) + 0 = 80`
- ALLOW. `curr_count` becomes 1.

### Snapshot 2: t = 1:18s (18s into Window 2, after 30 more requests)

- `fraction_curr = 18/60 = 0.30`
- `weight_prev = 0.70`
- `prev_count = 80, curr_count = 30`
- `estimated = (80 × 0.70) + 30 = 56 + 30 = 86`
- ALLOW (86 < 100). `curr_count` becomes 31.

### Snapshot 3: t = 1:30s (50% into Window 2, after 50 more requests)

- `fraction_curr = 0.50`
- `weight_prev = 0.50`
- `prev_count = 80, curr_count = 50`
- `estimated = (80 × 0.50) + 50 = 40 + 50 = 90`
- ALLOW.

### Snapshot 4: t = 1:55s, after 10 more requests this window

- `fraction_curr = 55/60 ≈ 0.92`
- `weight_prev = 0.08`
- `prev_count = 80, curr_count = 60`
- `estimated = (80 × 0.08) + 60 = 6.4 + 60 = 66.4`
- ALLOW comfortably (66.4 < 100).

### Snapshot 5: t = 2:00s (start of Window 3)

- `prev_count` is now Window 2's count = 60.
- `curr_count = 0`.
- `estimated = 60 × 1.0 + 0 = 60`. Still under limit. Smooth transition.

**Notice:** unlike Fixed Window, the limit doesn't suddenly reset — the previous window's count smoothly fades out as we move further from it.

---

## Why It Solves the Boundary Problem

Recall the Fixed Window boundary problem: 100 requests at 0:59 + 100 requests at 1:00 = 200 in 1 second.

With Sliding Window Counter:
- At 1:00 (start of Window 2), prev_count = 100, weight_prev = 1.0.
- `estimated = 100 × 1.0 + 0 = 100` → at the limit.
- The 101st request at 1:00 → `estimated = 100 + 1 = 101` → **DENY**.

The previous window's 100 requests **still count**, fading out only as time progresses. The boundary spike is **prevented**.

```
Sliding Window Counter's view:

Window 1 (count = 100)     Window 2 (count = ?)
████████████████████│
            weight 1.0 → ─→ 0.5 → ─→ 0.0
                          ↑
                       at 1:30s, weight = 0.5,
                       so Win1 contributes 50 to the estimate
```

The previous window's contribution **smoothly decays from 1.0 to 0.0** over the next window's duration.

---

## Accuracy: Why It's Approximate

The algorithm **assumes uniform distribution** of requests within the previous window. In reality, requests may cluster.

**Worst case error:** if all 100 requests in Window 1 happened at 0:59 (the very end), then at 1:00:01, the algorithm assumes ~99% of them are still "recent" (correct), so it counts ~99 of them. Accurate.

But if all 100 happened at 0:00 (start of Window 1) and we're at 1:30 (mid Window 2), the algorithm assumes ~50% are still in the sliding window. **In reality, all are 60-90 seconds old (outside the sliding window).** The algorithm *overcounts* in this case — but **fail-safe direction**: it might deny a legitimate request, but won't allow excess traffic.

**Empirical accuracy:** Cloudflare's published measurements show ~0.003% of requests get incorrectly rejected vs Sliding Window Log. In practice, the approximation is excellent.

---

## Pros

| Pro | Explanation |
|-----|-------------|
| **O(1) memory** | Just two counters per user. Massive savings vs Sliding Window Log. |
| **No boundary problem** | Smooth transition between windows. |
| **Very accurate** | Typically within 1% of the exact count. |
| **Fast** | Just a few arithmetic operations. |
| **Atomic via Redis Lua** | All ops can be batched in a single script. |
| **Scales to billions of users** | Cloudflare uses it at planet scale. |

---

## Cons

| Con | Explanation |
|-----|-------------|
| **Approximation** | Not exact. If you need *exact* counts (payments), use Sliding Window Log. |
| **Assumes uniform prev-window distribution** | Worst-case error appears if previous window's traffic was clustered. |
| **Slightly more complex** | Two windows + weighted math is more code than Fixed Window. |
| **Doesn't track individual timestamps** | No audit trail of when requests happened (unlike Sliding Window Log). |

---

## When to Use Sliding Window Counter

**Use Sliding Window Counter when:**
- You need **accurate** rate limiting at **massive scale** (CDN, large API gateway).
- The boundary problem of Fixed Window would cause real issues.
- Per-user request rates are too high for Sliding Window Log's memory.
- You want a **production-grade default** that gets 95% of the benefits of Sliding Window Log at 1% of the memory cost.

**Avoid Sliding Window Counter when:**
- You need **exact** counts (payments, fraud, financial systems) → use Sliding Window Log.
- Simplicity is the top priority → use Fixed Window (accept boundary issues).
- You want bursts allowed → use Token Bucket.

**This is often the "best default" for rate limiting in production APIs.**

---

## Real-World Examples

### 1. Cloudflare
Cloudflare's edge network (one of the largest in the world) uses sliding window counter for its rate limiting feature. They published a [blog post](https://blog.cloudflare.com/counting-things-a-lot-of-different-things/) explaining the choice. At billions of requests/second, **memory efficiency matters**, and the approximation error is statistically negligible.

### 2. Modern API Gateways (Kong, Tyk, etc.)
Many open-source and commercial API gateways default to Sliding Window Counter. It's the sweet spot for general-purpose API protection.

### 3. CDN Edge Rate Limiting
- Distinguishing legitimate users from bots requires accurate rate limiting.
- Fixed Window's boundary problem could let bots burst through; Sliding Window Counter fixes that without per-request memory cost.

### 4. WAF (Web Application Firewall) Rules
- "Block IPs that exceed 1000 requests in 60 seconds" — needs accuracy across rolling windows.

### 5. SaaS Per-Account API Limits
- "200 requests/minute per organization" — at scale, Sliding Window Counter handles millions of organizations with predictable memory.

---

## Edge Cases & Gotchas

### 1. Prev Window Doesn't Exist
On the very first request from a user (no prior window), `prev_count = 0`. The math still works:
`estimated = 0 × weight + curr_count = curr_count`. Equivalent to Fixed Window for the first window — that's fine.

### 2. Long Idle, Then Burst
User is idle for 10 minutes (windows pass). On their next request:
- `prev_window` was 10 windows ago → its counter has expired (TTL).
- `prev_count = 0`, `curr_count = 0` → fresh start.

This is correct behavior; idle users get a clean slate.

### 3. Clock Skew
Like Fixed Window, the algorithm depends on `now mod window_size`. Skewed clocks cause inconsistent windowing. Use a single source of time (Redis) when possible.

### 4. Window Size Choice
- Smaller windows (e.g., 1 second) → less approximation error but more frequent transitions.
- Larger windows (e.g., 1 hour) → more memory per counter (because rate counts get higher), but more efficient.
- Production sweet spot: usually 1 minute or 10 seconds.

### 5. The "Cluster at Boundary" Worst Case
If 100 requests all happened at 0:59 (end of Window 1) and now we're at 1:01 (just into Window 2), the algorithm thinks ~98 of them are still "recent" — which is actually correct. The worst case is when requests happen at 0:00 (start of Window 1) and we're at 1:30 — algorithm overestimates, but err-on-the-side-of-rejection is safe.

---

## Redis Implementation (Conceptual)

Store two counters per user (one per window):

```
KEY: swc:user-123:/api/search:982651200    (current window)
VALUE: 30
TTL: 2 × window_size  (so prev window is still around)

KEY: swc:user-123:/api/search:982651199    (previous window)
VALUE: 80
TTL: window_size  (expires after this window completes)
```

Each request runs (in a Lua script):
1. `INCR curr_window_key` → get new curr_count.
2. `GET prev_window_key` → get prev_count (default 0).
3. Compute weighted estimate.
4. Decide allow/deny.
5. If denied, optionally DECR curr_window_key to roll back (depends on policy).

---

## Comparison with All Other Algorithms

```
                  ┌──────────────────────────────────────────────────────┐
                  │           Memory vs Accuracy Trade-Off               │
                  │                                                      │
ACCURACY  ▲       │                                                      │
          │       │  Sliding Window Log (PERFECT, O(N) memory)           │
          │       │  ●                                                   │
          │       │                                                      │
          │       │  ● Sliding Window Counter (VERY GOOD, O(1) memory)   │
          │       │       ← THE PRODUCTION SWEET SPOT                    │
          │       │                                                      │
          │       │  ● Token Bucket (GOOD, allows bursts)                │
          │       │  ● Leaky Bucket (GOOD, smooth output)                │
          │       │                                                      │
          │       │  ● Fixed Window (POOR — boundary problem)            │
          │       │                                                      │
          └───────┼─────────────────────────────────────────────►        │
                  │                                            MEMORY-   │
                  │                                            EFFICIENT │
                  └──────────────────────────────────────────────────────┘
```

---

## Interview Talking Points

1. **"Why prefer this over Fixed Window?"** → Solves the boundary problem with the same O(1) memory. Strictly better unless you need ultra-simple semantics.

2. **"Why prefer this over Sliding Window Log?"** → O(1) memory instead of O(N). At scale (millions of users), this matters enormously. Accuracy loss is typically < 1%.

3. **"How accurate is it really?"** → Worst-case overestimate when previous window's traffic was front-loaded. Cloudflare measured ~0.003% false-positive rate at production scale.

4. **"What's the math behind the weighting?"** → Assume uniform distribution in the previous window. Then the fraction still in the sliding window is `(1 - elapsed_in_current / window_size)`. Multiply prev_count by this weight.

5. **"What if I need exact accuracy?"** → Switch to Sliding Window Log. The trade-off is memory.

6. **"This sounds complex — when is Fixed Window OK?"** → When you accept the boundary problem and want simplicity (e.g., "100 reqs/hour, resets at the top of each hour"). For most public APIs, this is fine.

7. **"Can you build this with just Redis primitives?"** → Yes — two `INCR` + `GET` + arithmetic. Bundle in a Lua script for atomicity. Total: ~10 lines of Lua.

8. **"This is what Cloudflare uses?"** → Yes. They published a great blog post on it. At their scale (billions of requests/second), this is the only algorithm that gives both accuracy and acceptable memory.
