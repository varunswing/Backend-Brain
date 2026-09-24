# N+1 Query Problem

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

N+1 = 1 query to load a list, then N more queries (one per row) for related data. ORM lazy loading often causes it.

---

## Why this band

50 RPS × (1+20) queries = 1,050 QPS accidental load — feels like a ‘scale’ incident.

---

## How (interview-ready)

1. Detect: log SQL count per request; APM spans.
2. Fix: `JOIN`, eager fetch, batch `WHERE id IN (...)`.
3. GraphQL/dataloaders: batch per tick.
4. Budget: max queries per endpoint.

---

## How vs related ideas

| | N+1 | Batched |
|--|-----|---------|
| Queries | 1+N | ~2 |
| Latency | Climbs with page size | Stable |

---

## Pitfalls

- ‘ORM is slow’ without counting queries
- Fixing with giant unscoped joins
- Caching over broken query shape first

---

## 30-sec answer

> N+1 means one query per child row. I count SQL per request; if it’s O(N), I batch or join. At 10–100 RPS this alone can saturate the DB.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
