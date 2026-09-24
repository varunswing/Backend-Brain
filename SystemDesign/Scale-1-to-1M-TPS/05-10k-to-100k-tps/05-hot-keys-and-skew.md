# Hot Keys & Partition Skew

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A hot key/partition receives disproportionate traffic (celebrity hotel, flash SKU). Average QPS looks fine; one shard/node dies.

---

## Why this band

Hash sharding assumes uniform access. Real popularity is Zipf — must design explicitly.

---

## How (interview-ready)

1. Detect: per-key/per-shard QPS metrics.
2. Mitigate: local cache, single-flight, split key (`key#1..N`), dedicated store, rate limit.
3. For Kafka: avoid single key for all events of a huge tenant if possible (or special pipeline).
4. Load-test with realistic skew, not uniform random.

---

## How vs related ideas

| | Uniform | Zipf |
|--|---------|------|
| Shard load | Even | One hot |
| Fix | Add shards | Special-case hot |

---

## Pitfalls

- ‘More shards’ as only fix for celebrity key
- Uniform load tests only
- Caching hot key without stampede control

---

## 30-sec answer

> Hot keys break average-based capacity plans. I detect per-shard QPS, then coalesce, locally cache, split keys, or isolate — not just add shards.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
