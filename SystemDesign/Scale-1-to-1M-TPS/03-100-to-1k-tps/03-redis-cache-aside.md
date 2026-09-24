# Redis Cache-Aside

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Cache-aside: app reads cache first; on miss, load DB, then write cache. Cache is not SoT.

---

## Why this band

DB can’t cheaply serve every read at hundreds–1K RPS for hot entities. Redis absorbs repeats.

---

## How (interview-ready)

```text
get(key):
  v = redis.get(key)
  if v: return v
  v = db.load()
  redis.set(key, v, ttl)
  return v
```
1. Key design: `user:{id}`, versioned keys.
2. TTL + explicit delete on write.
3. Serialize compactly; watch big keys.

---

## How vs related ideas

| Pattern | Who writes cache |
|---------|------------------|
| Cache-aside | App on miss |
| Read-through | Cache layer |
| Write-through | App writes DB+cache |

---

## Pitfalls

- Cache as SoT
- No TTL / invalidation
- Caching errors/empty wrongly

---

## 30-sec answer

> Cache-aside means Redis first, DB on miss, then fill. I own TTL and delete-on-write — Redis speeds reads; the DB remains source of truth.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
