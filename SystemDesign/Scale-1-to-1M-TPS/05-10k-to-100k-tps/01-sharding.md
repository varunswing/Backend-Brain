# Sharding — What & How

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Sharding splits data across many databases/nodes by a **partition key** so each node owns a subset of keys. Aim: QPS and storage scale ~linear with shard count (when skew is controlled).

---

## Why this band

One primary (even with replicas) hits CPU/IOPS/size walls. You need horizontal data plane.

---

## How (interview-ready)

1. Pick key: `user_id`, `tenant_id`, `hotel_id` — high cardinality, even access.
2. Strategy: hash mod N, range, directory/lookup service.
3. App or proxy routes to shard.
4. Avoid cross-shard transactions on hot path; use sagas if needed.
5. Plan reshard (consistent hashing / double-write) before you must.

---

## How vs related ideas

```text
shard = hash(hotel_id) % N
```
| Strategy | Pros | Cons |
|----------|------|------|
| Hash | Even if key good | Range queries hard |
| Range | Range scans | Hot ranges |
| Geo/tenant | Isolation | Skew by big tenant |

---

## Pitfalls

- Sharding on low-cardinality (`country`)
- Cross-shard joins in request path
- No hot-key plan
- Reshard with downtime only

---

## 30-sec answer

> Sharding splits data by a key like hotel_id so each DB owns a slice. I choose a high-cardinality key, avoid cross-shard tx on the hot path, and design for skew — average QPS lies.

## Related

- ./05-hot-keys-and-skew.md
- ./02-cqrs.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
