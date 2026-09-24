# Multi-Tier Cache (L1 / L2 / L3)

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Stack caches by distance: **L1** in-process (BigCache), **L2** Redis cluster (shared small/hot), **L3** Aerospike/large KV (fat catalog blobs). Request walks L1→L2/L3→DB.

---

## Why this band

Single Redis for multi-KB hotel/rate JSON at multi-k RPS gets expensive/hot. Tiering matches latency vs capacity.

---

## How (interview-ready)

```text
req → L1 hit? return
    → L3/Redis hit? promote L1, return
    → DB miss path → fill L3 (+ async) → return
```
1. L1 tiny TTL / size-bounded.
2. L3 holds fat immutable-ish blobs.
3. L2 for session, locks, sold-out flags.
4. Invalidate via TTL + events.

---

## How vs related ideas

| Tier | Latency | Scope |
|------|---------|-------|
| L1 | &lt;1ms | One pod |
| Redis | ~1–3ms | Fleet |
| Aerospike | ~2–5ms | Fleet, large data |
| DB | 10–100ms+ | SoT |

---

## Pitfalls

- Same data in all tiers with conflicting TTLs and no invalidation story
- Fat blobs in Redis when Aerospike/SSD KV fits better
- L1 without bound (memory death)

---

## 30-sec answer

> I use L1 in-process for repeats on a pod, Redis for small shared state, and Aerospike-class store for fat catalog — miss falls to DB. Tiering is how you hold multi-k RPS without melting MySQL.

## Related

- ./02-aerospike.md
- ./03-redis-cluster.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
