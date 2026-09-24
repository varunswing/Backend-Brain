# Gateway Rate Limiting

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Enforce limits at the API gateway/edge so bad clients never reach every pod’s local limiter inconsistently.

---

## Why this band

Per-pod limits don’t form a correct global limit; gateway + Redis counters do.

---

## How (interview-ready)

1. Gateway policy per route/key.
2. Shared counter store (Redis) for distributed limits.
3. Separate limits: anonymous vs auth vs partner.
4. Shed bots before origin.

---

## How vs related ideas

| | In-app only | Gateway |
|--|-------------|---------|
| Global accuracy | Weak | Stronger |
| Protection | After work started | Earlier |

---

## Pitfalls

- One global bucket for all users
- No partner-specific quotas
- Counting health checks

---

## 30-sec answer

> At 100–1K I put rate limits at the gateway with a shared Redis counter so the limit is global, not accidentally N times per pod.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
