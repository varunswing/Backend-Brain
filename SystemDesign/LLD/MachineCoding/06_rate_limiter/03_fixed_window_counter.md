# Fixed Window Counter Algorithm

## Cheat Sheet

| Property | Value |
|----------|-------|
| **Memory per user** | O(1) — a single counter per window |
| **Accuracy** | **Poor at boundaries** (can allow 2× the limit briefly) |
| **Allows bursts?** | Indirectly — yes, exactly at window boundaries (the problem!) |
| **Complexity** | Simplest of all algorithms |
| **Best for** | Public APIs with hourly/daily quotas, simple use cases |
| **Used by** | GitHub API, Twitter API, most "quota per hour" systems |

---

## The Core Idea

Divide time into **fixed-size windows** (e.g., 1 minute, 1 hour, 1 day). Maintain one counter per window per user. Each request **increments** the counter. If the counter exceeds the limit, **reject**. When the next window starts, **reset to zero**.

> **The key insight:** Beautifully simple — just one number per user per window. But that simplicity has a dark side called the **boundary problem**.

---

## Visual Model

```
Time:    0s        60s       120s      180s      240s
         │         │         │         │         │
         ├─────────┼─────────┼─────────┼─────────┤
         │ Win 1   │ Win 2   │ Win 3   │ Win 4   │
         │ count=45│ count=12│ count=98│ count=3 │
         │ limit=100│ limit=100│ limit=100│ limit=100│
         └─────────┴─────────┴─────────┴─────────┘
         
         At t=60s, the counter resets to 0 (new window).
         The window is "fixed" because boundaries are at fixed clock times.
```

---

## How It Works — Step by Step

1. **Compute the current window:** `window_id = floor(now / window_size)`.
2. **Lookup the counter** for `(user_id, window_id)`.
3. **Increment** the counter.
4. **Check:**
   - If `counter <= limit` → **ALLOW**.
   - Else → **DENY** (with `Retry-After = window_end − now`).
5. **Cleanup:** Old window counters can be deleted (or set to expire).

---

## Pseudocode (For Understanding)

```
function isAllowed(user):
    windowId = floor(now() / windowSize)
    key = user + ":" + windowId

    count = increment(key)        // atomic increment
    setExpiry(key, windowSize)    // auto-cleanup

    if count <= limit:
        return ALLOWED(remaining = limit - count)
    else:
        windowEnd = (windowId + 1) * windowSize
        return DENIED(retryAfter = windowEnd - now())
```

> **Why so simple?** Because Redis's `INCR` is atomic and returns the new value in one operation. The whole algorithm is essentially `INCR + check`.

---

## Worked Example

**Config:** limit = 100 requests per 60-second window.

| Time | Request | Window | Counter | Decision |
|------|---------|--------|---------|----------|
| 0:00 | 1 | win-1 | 1 | ALLOW |
| 0:30 | 50 more | win-1 | 51 | ALLOW |
| 0:59 | 49 more | win-1 | 100 | ALLOW (exactly at limit) |
| 0:59.9 | 1 more | win-1 | 101 | **DENY** |
| 1:00 | 1 | win-2 | 1 | ALLOW (new window!) |
| 1:00.1 | 99 more | win-2 | 100 | ALLOW |

Everything looks fine — until you notice the issue in the next section.

---

## The Boundary Problem (The Big Flaw)

This is the **single most important** thing to know about Fixed Window. Interviewers will always ask about it.

### The Scenario

**Config:** limit = 100 req/minute.

Suppose a malicious (or just impatient) user sends:
- **100 requests at 0:59** (end of window 1) → all allowed.
- **100 requests at 1:00** (start of window 2) → all allowed.

**Result:** **200 requests in 1 second**, while the stated limit is "100 per minute". The user effectively got **double** the limit by exploiting the boundary.

### Visualized

```
Window 1                    │  Window 2
  count goes 1→100 by 0:59  │  count resets to 0 at 1:00
                            │
─────────────────────────────────────────────────────►
                       ░░░░│░░░░
                       ░░░░│░░░░    ← 200 reqs in this 1-second span!
                       ░░░░│░░░░       (limit was 100 per MINUTE)
                       ░░░░│░░░░
                            ▲
                            │
                        boundary
                        (window reset)
```

The algorithm doesn't see "the last 60 seconds" — it sees "this clock minute". A 1-second span that straddles the boundary is treated as two separate windows.

---

## Pros

| Pro | Explanation |
|-----|-------------|
| **Dead simple** | One `INCR` + one check. Easiest algorithm to implement and debug. |
| **Tiny memory** | One integer per user per window. Trivial. |
| **Easy to communicate** | "100 requests per hour, resets at the top of each hour" — users understand this instantly. |
| **Atomic in Redis** | `INCR` is a single command, no races. |
| **Easy to monitor** | Just plot counter values; spikes are obvious. |
| **Auto-cleanup** | Old windows expire naturally via TTL. |

---

## Cons

| Con | Explanation |
|-----|-------------|
| **Boundary problem** | Can allow up to 2× the limit in a 1-second span around the boundary. |
| **No burst smoothing** | All 100 requests can hit at 0:59.999 — no rate of arrival control. |
| **Unfair "lucky" timing** | A user who happens to start their session at the top of a window gets more headroom than one who starts at 0:59. |
| **Coarse granularity** | A 1-hour window means a single misclick at 1:00:01 means waiting until 2:00:00. |

---

## When to Use Fixed Window

**Use Fixed Window when:**
- You're providing a **public API** with clear quota semantics ("100 requests per hour").
- The boundary problem doesn't matter much (users sending 2× over 1 second isn't a disaster).
- Simplicity and operational ease are top priorities.
- You're at extreme scale where memory matters and minor inaccuracy is acceptable.

**Avoid Fixed Window when:**
- The boundary problem could cause downstream damage (e.g., 2× spike crashes a service).
- You need fair, smooth rate limiting (use Sliding Window Counter).
- You want exact accuracy (use Sliding Window Log).

---

## Real-World Examples

### 1. GitHub API
- **5,000 requests per hour** for authenticated users.
- The reset time is in a response header: `X-RateLimit-Reset: 1644321600`.
- **Why GitHub uses this:** simplicity, clear communication to developers, and the boundary problem is benign (no one is going to DDoS GitHub through this).

### 2. Twitter API v1.1 (old)
- **15 requests per 15-minute window** for many endpoints.
- Same reasoning — easy to communicate, simple to implement.

### 3. Daily Quotas
- "10 GB transfer per day" → fixed window of 86,400 seconds.
- Daily quotas are *inherently* fixed-window; the boundary problem at midnight UTC matters less because users don't typically time their requests around it.

### 4. Free Tier Limits
- "100 free API calls per month" — billing systems typically use fixed monthly windows.
- The boundary problem is irrelevant at this granularity.

---

## Edge Cases & Gotchas

### 1. The Boundary Problem (Already Covered)
The biggest gotcha. If your API uses Fixed Window and someone needs strict enforcement, they'll be unhappy. Mitigations:
- **Use smaller windows** (1 minute instead of 1 hour) → boundary problem still exists but causes less damage.
- **Combine with another limiter** (e.g., a global Token Bucket below the per-user Fixed Window).
- **Switch to Sliding Window Counter** if accuracy matters.

### 2. Clock Synchronization
The window boundary is computed from `now()`. If nodes have skewed clocks, they may disagree on which window we're in.
**Fix:** Use Redis's server time, or align windows to NTP-synchronized clocks.

### 3. Aligned vs Unaligned Windows
- **Aligned**: windows start at clock boundaries (e.g., exactly :00 seconds). Easy to reason about, but every user resets at the same time → spike risk.
- **Unaligned**: each user's window starts when their first request arrives. Spreads load but is harder to debug.

Most systems use aligned (it's simpler), and accept the synchronized reset spike.

### 4. Resetting Counters
With Redis TTL, old window counters expire automatically. But if you set TTL = window_size, a counter for window 5 might still exist when window 6 starts (because the TTL hasn't fired yet). Use `INCR + EXPIRE` carefully — `EXPIRE` should only be set on the first increment, or you'll keep extending the TTL.

### 5. Race Condition on First Request
```
Request A: INCR (count=1), EXPIRE 60s
Request B: INCR (count=2), EXPIRE 60s  ← resets TTL!
```
This can leak counters. Fix: use a Lua script that only sets `EXPIRE` when the counter was just created (returned value = 1).

---

## Redis Implementation (Conceptual)

```
KEY: fw:user-123:/api/search:982651200    (982651200 = window id)
VALUE: 87                                  (current count)
TTL: 60 seconds                            (auto-cleanup)
```

The window ID is in the key, so each new window gets a fresh key automatically. Old keys expire. **No cleanup job needed.**

The atomic operation is:
1. `INCR key` → returns new count.
2. If count == 1, `EXPIRE key 60` (only on first increment).
3. Compare count to limit.

For step 2, use a Lua script to ensure atomicity.

---

## Comparison to Sliding Window Counter (Coming Next File)

Fixed Window is the **starting point** for understanding rate limiting. Sliding Window Counter improves on it by smoothing the boundary problem with a weighted blend of two consecutive windows.

```
Fixed Window:           [###45####][###12####][##98#####]
                        ↑          ↑          ↑
                        reset      reset      reset (sharp boundaries → spikes)

Sliding Window Counter: smooth blend of "previous window weight + current window count"
```

If you're considering Fixed Window for serious production use, **strongly consider Sliding Window Counter instead** — same O(1) memory but no boundary problem.

---

## Interview Talking Points

1. **"What's wrong with Fixed Window?"** → **The boundary problem.** Be ready to draw the diagram showing 100+100 = 200 requests in 1 second.

2. **"How do you mitigate the boundary problem?"** → (a) Use smaller windows. (b) Switch to Sliding Window Counter. (c) Add a secondary limiter (e.g., per-second cap on top of per-minute).

3. **"How do you implement this in Redis?"** → `INCR key` + `EXPIRE` on first increment. Use a Lua script for atomicity of the `INCR + EXPIRE` pair.

4. **"Why is GitHub OK with this algorithm?"** → Their boundary problem just means a user can briefly double their hourly quota every hour — not enough to cause issues. GitHub prioritizes API simplicity over precision.

5. **"What if I want millisecond accuracy?"** → Fixed Window is the wrong tool. Use Sliding Window Log (perfect) or Sliding Window Counter (very good + cheap).

6. **"When did you last use Fixed Window in production?"** → Anywhere with quota-style limits: API call counts, daily transfer caps, free tier monthly limits. The clock-bound reset is a *feature* there, not a bug.
