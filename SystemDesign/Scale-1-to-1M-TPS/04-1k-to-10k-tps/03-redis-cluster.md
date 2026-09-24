# Redis Cluster — What & How

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Redis Cluster shards keys across nodes via hash slots (0–16383). Scales RAM/QPS beyond one instance; still best for **smaller** values and rich data structures.

---

## Why this band

Sessions, rate-limit counters, locks, sold-out flags need shared sub-ms ops across many pods — cluster removes single-node ceiling.

---

## How (interview-ready)

1. Key → CRC16 → slot → node.
2. Multi-key ops need same slot (hash tags `{userId}:...`).
3. Use for: sessions, rate limits, pub/sub light, small caches.
4. Avoid huge values / huge `KEYS`; use SCAN.
5. Persistence optional (AOF/RDB) — often treat as ephemeral+recomputable.

---

## How vs related ideas

| | Redis Cluster | Aerospike |
|--|---------------|-----------|
| Data structures | Rich (lists, zset) | Record/bins |
| Very large values | Awkward | Better |
| Typical MMT | Session/aux | Hotel blobs |

---

## Pitfalls

- Cross-slot transactions without tags
- Using as durable money ledger
- Hot slot from bad key design
- Unbounded big keys

---

## 30-sec answer

> Redis Cluster shards by hash slot for shared session, limits, and small keys. For fat hotel JSON at search QPS I prefer Aerospike; Redis stays the aux/control-plane cache.

## Related

- ./02-aerospike.md
- ../03-100-to-1k-tps/03-redis-cache-aside.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
