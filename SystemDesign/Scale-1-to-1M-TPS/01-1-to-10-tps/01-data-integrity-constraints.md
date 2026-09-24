# Data Integrity — PK / Unique / FK

**Band:** 1 → 10 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Database constraints are rules the DB enforces: primary keys (identity), unique (no duplicate business keys), foreign keys (referential links), check/not-null (domain rules).

---

## Why this band

At tiny RPS, bugs still create **double charges / duplicate bookings**. App-only checks race under concurrency; constraints are the last line of defense.

---

## How (interview-ready)

1. Identify entities that must be unique (`payment_id`, `idempotency_key`, `email`).
2. Add `UNIQUE` / PK in migrations before traffic grows.
3. Prefer DB reject (duplicate key error) over silent double insert.
4. Keep FKs where orphans hurt money/compliance; drop FKs only with a clear integrity story later.

---

## How vs related ideas

| Idea | Role |
|------|------|
| App validation | UX / early reject |
| DB constraint | Concurrent-safe truth |
| Idempotency key | Often stored with UNIQUE |

---

## Pitfalls

- Relying only on `SELECT then INSERT` (TOCTOU race)
- Missing unique on natural business keys
- Soft-delete without unique on `(key) WHERE deleted_at IS NULL` (partial unique)

---

## 30-sec answer

> At 1–10 RPS I still put **unique constraints** on money and identity keys. Scale won’t invent integrity — the DB enforces it under race.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
