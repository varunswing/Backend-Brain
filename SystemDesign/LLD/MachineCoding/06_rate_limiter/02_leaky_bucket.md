# Leaky Bucket Algorithm

## Cheat Sheet

| Property | Value |
|----------|-------|
| **Memory per user** | O(1) — queue size + last leak time |
| **Accuracy** | Good (strict constant output rate) |
| **Allows bursts?** | **NO** — outflow is always constant |
| **Complexity** | Medium |
| **Best for** | Traffic shaping, smoothing outbound calls, protecting fragile downstream |
| **Used by** | Nginx (`limit_req`), telecom network shapers, outbound API workers |

---

## The Core Idea

Imagine a **bucket with a hole at the bottom**. Water (requests) pours in at unpredictable rates, but leaks out at a **fixed, constant rate**. If the bucket overflows, the extra water is rejected (dropped).

> **The key insight:** Output is always smooth, regardless of how chaotic the input is. The bucket *absorbs* variability and *outputs* uniformity.

---

## Visual Model

```
        requests arriving (irregular)
           │     │  │       │ │
           ▼     ▼  ▼       ▼ ▼
        ┌──────────────────────┐
        │ ●●●●●●●●●●●●●●●●●●●● │ ← if bucket is full, new requests are DROPPED
        │ ●●●●●●●●●●●●●●●●●●●● │   (this is the queue)
        │ ●●●●●●●●●●●●●●●●●●●● │
        └──────────┬───────────┘
                   │
                   │ leak rate: 5 req/sec
                   │ (CONSTANT regardless of input)
                   ▼
              ●   ●   ●   ●   ●
              ───────────────────►  smooth output to backend
              uniform spacing
```

---

## How It Works — Two Common Variants

There are **two interpretations** of "Leaky Bucket". Know both — interviewers sometimes mean different things.

### Variant A: Leaky Bucket as a Queue (Meter)

Used by Nginx. The bucket is a **FIFO queue**:
1. Requests arrive and are placed in the queue.
2. A constant-rate processor pulls from the queue and forwards each request.
3. If the queue is **full**, new requests are **rejected**.

```
Input (chaotic) → [FIFO Queue, max size N] → Processor (fixed rate) → Backend
```

### Variant B: Leaky Bucket as a Counter

Mathematically equivalent but conceptually different:
1. A counter represents "water level". It **leaks down** at a constant rate over time.
2. Each request **adds 1** to the counter.
3. If counter would exceed the bucket capacity, **reject**.

```
function isAllowed(user):
    bucket = getBucket(user)
    elapsed = now() - bucket.lastLeak
    bucket.level = max(0, bucket.level - elapsed * leakRate)
    bucket.lastLeak = now()

    if bucket.level + 1 <= capacity:
        bucket.level += 1
        return ALLOWED
    else:
        return DENIED
```

> **Note:** Variant B looks *almost identical* to Token Bucket. The difference: in Token Bucket the bucket *fills* with tokens (representing **permits**); in Leaky Bucket the bucket *fills* with water (representing **pending requests**). Functionally similar — but the **intent** is the opposite.

---

## Worked Example (Variant A — Queue)

**Config:** queue size = 5, leak rate = 1 request/sec.

| Time | Event | Queue State | Action |
|------|-------|-------------|--------|
| 0.0s | 3 reqs arrive | [R1, R2, R3] | All queued |
| 0.0s | Leak (1/sec) | [R2, R3] | R1 sent to backend |
| 0.5s | 4 reqs arrive | [R2, R3, R4, R5, R6] | R6 rejected (queue full would be 6 > 5; R4, R5 queued, R6 dropped) |
| 1.0s | Leak | [R3, R4, R5] | R2 sent |
| 2.0s | Leak | [R4, R5] | R3 sent |
| ... | ... | ... | Backend sees 1 req/sec, smooth |

**Notice:** even though 7 requests arrived in 0.5 seconds, the backend received exactly 1 per second. **Bursts are smoothed.**

---

## Token Bucket vs Leaky Bucket — The Critical Comparison

This comes up in *every* rate limiter interview. Memorize it.

| Aspect | Token Bucket | Leaky Bucket |
|--------|-------------|--------------|
| **Bucket contains** | Tokens (permits to spend) | Water (pending requests) |
| **Action on request** | Consume a token | Add water |
| **Burst handling** | **Allows bursts** (drain accumulated tokens) | **Prevents bursts** (output is always constant) |
| **Output pattern** | Bursty (mirrors input) | Smooth (constant rate) |
| **When bucket is empty** | Tokens = 0 → reject | Water = 0 → bucket can accept more |
| **When bucket is full** | Tokens = capacity → no harm | Water = capacity → reject new |
| **Best mental model** | "Savings account" | "Funnel" |

### The Picture That Makes It Click

```
TOKEN BUCKET                       LEAKY BUCKET
                                   
Input  ▓▓▓▓▓ ▓ ▓ ▓▓▓▓▓ → →         Input ▓▓▓▓▓ ▓ ▓ ▓▓▓▓▓
                                   
Output ▓▓▓▓▓ ▓ ▓ ▓▓▓▓▓ → →         Output ▓ ▓ ▓ ▓ ▓ ▓ ▓ ▓
       (output mirrors             (output is uniform,
        the input bursts,           regardless of how
        as long as tokens exist)    chaotic the input is)
```

---

## Pros

| Pro | Explanation |
|-----|-------------|
| **Smooth output** | Backend sees uniform traffic; load balancing is trivial. |
| **Predictable downstream load** | You can capacity-plan exactly because output rate is constant. |
| **Memory efficient** | Just a counter or a bounded queue. |
| **Strict guarantee** | Output rate **cannot** exceed the leak rate, ever. |
| **Implicit fairness** | FIFO queue serves requests in arrival order. |

---

## Cons

| Con | Explanation |
|-----|-------------|
| **Forbids bursts** | Legitimate burst traffic is rejected even if quiet before. |
| **Adds latency (Variant A)** | Queued requests wait their turn; users see delay. |
| **Unused capacity is lost** | If no traffic for an hour, you can't "save up" — output rate is fixed at `leakRate`. |
| **Bad for human-facing APIs** | Users hate "even though I haven't used the API in 10 minutes, why am I throttled?" |
| **Queue overflow = silent drops** | Rejected requests just vanish (need explicit handling). |

---

## When to Use Leaky Bucket

**Use Leaky Bucket when:**
- You're protecting a **fragile downstream service** that can't tolerate spikes (legacy systems, payment processors, third-party APIs with strict limits).
- You need **traffic shaping** — smoothing erratic traffic to a uniform output.
- The system is **machine-to-machine** (not human-facing), so latency is acceptable.
- You're an **outbound worker** calling someone else's API with hard rate limits (e.g., "200 calls/min to Twitter").

**Avoid Leaky Bucket when:**
- Users are human and expect immediate response (queuing = bad UX).
- Bursty traffic is normal and benign (use Token Bucket).
- The downstream service can handle bursts (no need for shaping).

---

## Real-World Examples

### 1. Nginx `limit_req`
Nginx's HTTP rate-limiting module is a **classic leaky bucket queue**:
```
limit_req_zone $binary_remote_addr zone=myzone:10m rate=10r/s;
limit_req zone=myzone burst=20;
```
- `rate=10r/s` → leak rate (constant 10 req/sec to backend).
- `burst=20` → queue size (up to 20 requests can wait).
- Without `nodelay`, requests are **delayed** to enforce the leak rate.

### 2. Telecom Network Shapers
- ISPs use leaky bucket to enforce bandwidth caps.
- Smooths traffic to a uniform output to prevent network congestion.

### 3. Outbound API Workers
- Your service calls Stripe (rate limit: 100/sec). You enqueue payment events; a worker processes them at exactly 100/sec.
- This is leaky bucket protecting *the third party*, not your service.

### 4. Email Sending Pipelines
- SendGrid/SES recommend sending at a constant rate to maintain sender reputation.
- A leaky bucket smooths a queued backlog into a uniform send stream.

### 5. Video Streaming Bitrate
- The "playout buffer" is a leaky bucket: variable network arrival → constant playback rate.

---

## Edge Cases & Gotchas

### 1. Queue Overflow Strategy
What happens when the queue is full?
- **Drop tail** (default): reject the *new* request.
- **Drop head**: reject the *oldest* queued request to make room.
- **Push back**: signal the caller to slow down (TCP-style).

Each has different latency/fairness trade-offs.

### 2. Latency Spike at Queue Full
Even before rejection, a user near the queue tail sees high latency. Monitor **queue depth percentiles** (P50, P99), not just rejection rate.

### 3. Variant A vs B Confusion
If asked "is leaky bucket the same as token bucket?", the answer depends on which variant. **Variant B (counter)** has nearly identical math to token bucket, but **Variant A (queue)** is genuinely different (it queues; token bucket rejects).

### 4. Long Quiet Periods Don't Help
Unlike token bucket, a user who's been silent for an hour gets the same throughput as one constantly hitting the limit. The bucket can't "save up" capacity.

### 5. Distributed Implementation
For a global leak rate, you need a coordinated counter (Redis). For per-region leak rates, each region's Nginx does it locally. Pick based on whether the constraint is global (third-party API) or local (one server's capacity).

---

## Mental Model: When Should I Smooth vs Allow Bursts?

```
Is the bottleneck UPSTREAM (us → them)?
    │
    ├── YES (e.g., we call Stripe at 100/sec max) → Leaky Bucket
    │
    └── NO. Is bursty traffic normal user behavior?
              │
              ├── YES (users click rapidly, mobile retries) → Token Bucket
              │
              └── NO. Do we want strict, predictable system load?
                        │
                        └── YES → Leaky Bucket
```

---

## Interview Talking Points

1. **"How is this different from Token Bucket?"** → Token Bucket allows bursts (mirrors input). Leaky Bucket smooths to constant output. **Different intents, similar implementations.**

2. **"What if my queue is full?"** → Drop tail by default. Or drop head for "newest wins" semantics. Or apply backpressure to the caller (TCP-style).

3. **"Why use this over Token Bucket?"** → When you're protecting something **fragile downstream** that genuinely cannot handle bursts. Token Bucket's burst feature is a *liability* in that case.

4. **"How do I do distributed leaky bucket?"** → Either a single coordinated counter (Redis + Lua) for global constraints, or per-region buckets for local constraints. Same trade-offs as Token Bucket distribution.

5. **"What's the latency impact?"** → In Variant A (queue), latency is the time spent waiting. If the queue is full or near full, latency can spike to seconds. Monitor `queue_depth` carefully.

6. **"Can I combine Token Bucket and Leaky Bucket?"** → Yes. Token Bucket on ingress (allow bursts from users) + Leaky Bucket on egress (smooth to third-party APIs). This is a common production pattern.
