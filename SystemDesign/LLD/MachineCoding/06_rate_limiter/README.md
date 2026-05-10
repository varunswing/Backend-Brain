# Rate Limiting Algorithms - Deep Dive

This folder contains detailed explanations of the **5 core rate limiting algorithms** used in production systems. Each algorithm has its own file with theory, diagrams, use cases, and trade-offs.

---

## Files in This Folder

| # | Algorithm | File | One-Liner |
|---|-----------|------|-----------|
| 1 | **Token Bucket** | [01_token_bucket.md](./01_token_bucket.md) | Tokens refill at fixed rate; allows bursts up to bucket size |
| 2 | **Leaky Bucket** | [02_leaky_bucket.md](./02_leaky_bucket.md) | Requests leak out at constant rate; smooths traffic |
| 3 | **Fixed Window Counter** | [03_fixed_window_counter.md](./03_fixed_window_counter.md) | Counter per time window; simple but has boundary problem |
| 4 | **Sliding Window Log** | [04_sliding_window_log.md](./04_sliding_window_log.md) | Stores every request timestamp; most accurate but memory-heavy |
| 5 | **Sliding Window Counter** | [05_sliding_window_counter.md](./05_sliding_window_counter.md) | Weighted average of two fixed windows; best balance |

---

## The 30-Second Cheat Sheet

```
┌──────────────────────┬────────────┬──────────────┬───────────┬──────────────┐
│ Algorithm            │ Memory     │ Accuracy     │ Bursts?   │ Complexity   │
├──────────────────────┼────────────┼──────────────┼───────────┼──────────────┤
│ Token Bucket         │ O(1)       │ Good         │ YES       │ Medium       │
│ Leaky Bucket         │ O(1)       │ Good         │ NO        │ Medium       │
│ Fixed Window         │ O(1)       │ Poor (2x!)   │ Indirectly│ Simple       │
│ Sliding Window Log   │ O(N)       │ Perfect      │ NO        │ Complex      │
│ Sliding Window Count │ O(1)       │ Very Good    │ NO        │ Medium       │
└──────────────────────┴────────────┴──────────────┴───────────┴──────────────┘
```

> **Quick read**: `O(1)` = one number per user. `O(N)` = one entry per request per user.
> **Bursts** = can the algorithm allow a short surge above the steady rate?

---

## Visual Mental Model — All 5 in One Picture

```
TOKEN BUCKET                     LEAKY BUCKET
   ┌─────────┐                      ┌─────────┐
   │ ●●●●●●  │ ← refill              │  ●●●●   │ ← requests arrive
   │ ●●●●●●  │   (constant rate)     │  ●●●●   │
   │ ●●●●●●  │                       │  ●●●●   │
   └────┬────┘                       └────┬────┘
        │ consume on request              │ leak (constant rate)
        ▼                                 ▼
     allow/deny                       allow/deny
   "How many tokens left?"          "Is queue full?"


FIXED WINDOW                     SLIDING WINDOW LOG
  ┌─────────┬─────────┬──────       ┌─────────────────────┐
  │ Win 1   │ Win 2   │              │  T1 T2 T3 T4 T5 T6  │
  │  count  │  count  │              │  ←── timestamps ──→ │
  │   45    │   12    │              └─────────────────────┘
  └─────────┴─────────┴──────       count timestamps in
  Reset count each window           last N seconds


SLIDING WINDOW COUNTER (hybrid)
  ┌─────────────┬─────────────┐
  │ Prev count  │ Curr count  │
  │     80      │     30      │
  └─────────────┴─────────────┘
  weight = 80 × 0.3 + 30 = 54   (if 30% of prev window still in sliding range)
```

---

## Decision Guide — Which One Should I Use?

```
START HERE
    │
    ▼
Do I need to allow bursts?
    │
    ├── YES → Use TOKEN BUCKET
    │         (e.g., user uploads, API bursts, login attempts)
    │
    └── NO  → Continue
              │
              ▼
        Do I need perfect accuracy?
              │
              ├── YES (e.g., payments, billing) → SLIDING WINDOW LOG
              │
              └── NO → Continue
                      │
                      ▼
                Do I need strict, smooth output?
                      │
                      ├── YES (e.g., outbound API calls, traffic shaping)
                      │       → LEAKY BUCKET
                      │
                      └── NO → Continue
                              │
                              ▼
                        Memory-constrained at huge scale?
                              │
                              ├── YES + simplicity → FIXED WINDOW
                              │   (accept the boundary problem)
                              │
                              └── BALANCED → SLIDING WINDOW COUNTER
                                  (Cloudflare's pick)
```

---

## Real-World Usage (Who Uses What?)

| System | Algorithm Used | Why |
|--------|---------------|-----|
| **AWS API Gateway** | Token Bucket | API consumers naturally come in bursts |
| **Stripe API** | Token Bucket | Allows occasional spikes during checkout |
| **GitHub API** | Fixed Window (hourly) | Simple, well-documented, predictable reset time |
| **Twitter API** | Fixed Window (15-min) | Easy to communicate to developers |
| **Cloudflare** | Sliding Window Counter | Fast + accurate at planetary scale |
| **Nginx (req limiting)** | Leaky Bucket | Smooths incoming HTTP traffic |
| **Payment Gateways** | Sliding Window Log | Accuracy matters more than memory |
| **Discord** | Token Bucket | Allows brief bursts of messages |

---

## The Core Trade-Off Triangle

```
              ACCURACY
                  ▲
                  │
                  │
                  │
          Sliding Log
                  │
                  │
        Sliding Counter
                  │
   Token Bucket   │   Leaky Bucket
                  │
          Fixed Window
                  │
                  └────────────────────► SIMPLICITY
                  │
                  ▼
              MEMORY USAGE
```

**Pick any two:** You can have accuracy + simplicity (Fixed Window — but bad memory if not done right), accuracy + memory-efficiency (Sliding Counter — but more complex), or memory-efficiency + simplicity (Fixed Window — but inaccurate).

---

## Interview Talking Points

1. **The boundary problem** (Fixed Window can allow 2x the limit at window edges) — this is the #1 follow-up interviewers ask.
2. **Token Bucket vs Leaky Bucket** are often confused. Token Bucket *allows* bursts; Leaky Bucket *prevents* them.
3. **Distributed rate limiting** uses Redis with Lua scripts for atomicity — bring this up unprompted.
4. **Fail-open vs fail-closed** — what happens when the rate limiter itself goes down?
5. **Sliding Window Counter** is the production sweet spot for most APIs at scale.

---

## Reading Order Recommendation

If you're new to rate limiting, read in this order:

1. **Fixed Window** (`03`) — simplest, learn the basics
2. **Token Bucket** (`01`) — most commonly asked in interviews
3. **Leaky Bucket** (`02`) — compare and contrast with Token Bucket
4. **Sliding Window Log** (`04`) — understand what "perfect accuracy" looks like
5. **Sliding Window Counter** (`05`) — the production-grade synthesis
