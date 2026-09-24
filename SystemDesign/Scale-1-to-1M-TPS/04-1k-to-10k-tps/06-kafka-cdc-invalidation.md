# Kafka + CDC Cache Invalidation

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

CDC (e.g. Debezium) reads DB binlog and emits Kafka events. Consumers delete/refresh cache keys — search stays eventually consistent without sync write amplification on every API write.

---

## Why this band

At this RPS, app doing `update DB + delete 20 cache keys` inline is fragile. Async invalidation scales writers and keeps read path dumb/fast.

---

## How (interview-ready)

```text
ARI/content write → DB commit → binlog → Debezium → Kafka
  → Goku consumer → delete HTL_DET_/RTP_DET_ (or warm)
```
1. Idempotent consumers.
2. Lag SLO; alert when lag high (stale risk).
3. TTL as safety net if event lost.
4. Don’t block search on consumer.

---

## How vs related ideas

| | Sync invalidate in API | CDC + Kafka |
|--|------------------------|-------------|
| Write latency | Higher |
| Coupling | Tight | Loose |
| Miss storm risk | On bad delete loops | Manage consumers |

---

## Pitfalls

- At-least-once without idempotent delete
- One huge partition key hot
- Assuming instant consistency on search

---

## 30-sec answer

> Writes commit to MySQL; Debezium→Kafka tells search to drop Aerospike keys. That’s eventual consistency on purpose — confirm still re-validates inventory.

## Related

- ../05-10k-to-100k-tps/04-kafka-at-scale.md
- ./04-cache-aside-miss-vs-error.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
