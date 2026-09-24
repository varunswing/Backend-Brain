# Timeouts, Retries, Jitter

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Timeouts bound waits; retries overcome transient errors; jitter randomizes backoff so retries don’t synchronize into a storm.

---

## Why this band

At hundreds RPS, synchronized retries after a blip can **DDoS your own dependency**.

---

## How (interview-ready)

1. Per-dependency timeout < user SLA.
2. Retry budget (e.g. max 2) on idempotent calls.
3. Exponential backoff + full jitter.
4. Circuit breaker when error rate high.

---

## How vs related ideas

| | No jitter | With jitter |
|--|----------|-------------|
| After outage | Lockstep retry spike | Smoothed |

---

## Pitfalls

- Infinite retries
- Retrying 400s
- Timeout > caller timeout (cascading waits)

---

## 30-sec answer

> I set tight timeouts, limited retries, and jittered backoff. Without jitter, recoveries create retry stampedes at this scale.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
