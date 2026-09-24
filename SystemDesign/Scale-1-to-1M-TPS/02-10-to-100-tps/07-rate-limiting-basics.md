# Rate Limiting — Basics

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Rate limiting caps how many requests a client/IP/API key can make per window — protects the single DB from abuse or bugs.

---

## Why this band

One runaway script at 100 RPS can equal your whole capacity. Cheap protection.

---

## How (interview-ready)

1. Choose key: IP, user_id, api_key.
2. Algorithm: fixed window, sliding window, or token bucket.
3. Enforce at app middleware or API gateway.
4. Return 429 + Retry-After.

---

## How vs related ideas

| Algorithm | Pros |
|-----------|------|
| Token bucket | Smooth bursts |
| Fixed window | Simple |
| Sliding log | Accurate, more memory |

---

## Pitfalls

- Global limit only (one user blocks all)
- Limit too low for batch jobs
- No bypass for health checks

---

## 30-sec answer

> Rate limits stop one client from taking the whole DB. Token bucket or fixed window at middleware is enough at 10–100 RPS.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
