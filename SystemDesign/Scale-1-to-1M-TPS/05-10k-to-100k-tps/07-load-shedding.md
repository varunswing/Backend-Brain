# Load Shedding

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

When overloaded, intentionally reject/deprioritize low-value work so critical paths (checkout, login) survive.

---

## Why this band

At this RPS, retry storms + spikes exceed capacity. Serving everything slowly fails everything — shed to save the core.

---

## How (interview-ready)

1. Priority classes: critical / optional / best-effort.
2. Shed optional first (recommendations, non-critical personalization).
3. Return 503 with Retry-After; don’t queue infinitely.
4. Adaptive: shed from latency/CPU/queue depth signals.

---

## How vs related ideas

| | Queue forever | Shed |
|--|---------------|------|
| Latency | → ∞ | Bounded |
| Core path | Dies |
| Survives | |

---

## Pitfalls

- Shedding checkout before banners
- No client backoff guidance
- Shedding without metrics

---

## 30-sec answer

> Load shedding drops optional traffic under overload so checkout survives. I define priority classes and 503 early — infinite queues just make everyone fail.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
