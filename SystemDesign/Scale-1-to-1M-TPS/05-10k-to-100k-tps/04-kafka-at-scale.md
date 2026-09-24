# Kafka at Scale — Partitions & Consumers

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Kafka throughput scales with **partitions**. Ordering is per partition key. Consumer parallelism ≤ partitions per group.

---

## Why this band

Event-driven fan-out (invalidate, notify, index) must match produce rate or lag becomes product staleness.

---

## How (interview-ready)

1. Partition count ≥ max expected consumer parallelism.
2. Key by entity for ordering (`hotel_id`).
3. Idempotent consumers; compact topics where appropriate.
4. Lag SLO + alert; scale consumers or fix hot keys.
5. Separate clusters/topics by criticality.

---

## How vs related ideas

| | Low partitions | Enough partitions |
|--|----------------|-------------------|
| Parallelism | Capped | Scales |
| Hot key | One partition melts | Still a risk — key design |

---

## Pitfalls

- Global ordering requirement
- Huge messages
- Lag ignored until UX breaks
- One consumer group for all workloads

---

## 30-sec answer

> Kafka scales by partitions; ordering is per key. I size partitions for consumer parallelism, key by entity, and treat consumer lag as a first-class SLO.

## Related

- ../04-1k-to-10k-tps/06-kafka-cdc-invalidation.md
- ./05-hot-keys-and-skew.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
