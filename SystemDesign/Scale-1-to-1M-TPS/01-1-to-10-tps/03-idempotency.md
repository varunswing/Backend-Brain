# Idempotency

**Band:** 1 → 10 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

An operation is idempotent if doing it N times has the same effect as once. Clients retry; networks duplicate; users double-click.

---

## Why this band

Retries exist even at 1 RPS. Payments/creates without idempotency = duplicate money/entities.

---

## How (interview-ready)

1. Client sends `Idempotency-Key` (UUID).
2. Server stores key → response/result with UNIQUE constraint.
3. Replay same key → return same result, no second side effect.
4. Scope key per user/merchant + endpoint; TTL for storage cleanup.

---

## How vs related ideas

| Pattern | Use |
|---------|-----|
| Idempotency-Key header | HTTP payments/creates |
| Natural unique | `order_id` already unique |
| Upsert | Some update paths |

---

## Pitfalls

- Key only in memory (lost on restart)
- Different body same key (define conflict policy)
- Forgetting downstream (email sent twice) — make side effects keyed too

---

## 30-sec answer

> Idempotency means retries don’t double-apply. I store a unique idempotency key and return the first result on replay — critical for pay/create even at low RPS.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
