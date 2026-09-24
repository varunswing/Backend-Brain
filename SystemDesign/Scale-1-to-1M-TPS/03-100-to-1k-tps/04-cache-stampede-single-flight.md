# Cache Stampede + Single-Flight

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Stampede: many requests miss the same key together (expiry) and all hit DB. Single-flight/coalesce: only one loads; others wait/share result.

---

## Why this band

At N pods, expiry alignment turns one key into a DB thundering herd.

---

## How (interview-ready)

1. Probabilistic early expire / soft TTL.
2. Per-key lock or single-flight in process + Redis lock.
3. Serve stale while refresh (stale-while-revalidate).
4. Jitter TTLs so keys don’t align.

---

## How vs related ideas

| | Blind miss | Single-flight |
|--|------------|---------------|
| DB hits on expiry | ×N | ~1 |

---

## Pitfalls

- Distributed lock without TTL (deadlock)
- Stampede on empty results
- No jitter on TTL

---

## 30-sec answer

> When a hot key expires, I coalesce loads with single-flight or a short lock so one miss doesn’t become N DB queries across pods.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
