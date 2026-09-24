# Aerospike — What & How

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Aerospike is a distributed KV/record store optimized for high throughput and predictable low latency. Hybrid memory: primary index in RAM, records often on NVMe/SSD. Namespace → Set → Record → Bins.

---

## Why this band

At search/catalog QPS, you need **millions of fat-ish records** with few-ms gets. Pure RAM Redis for all catalog = costly; MySQL every time = too slow.

---

## How (interview-ready)

1. **Cache-aside** from app: batch get by hotel/room/rateplan keys (`HTL_DET_`, `RM_DET_`, `RTP_DET_`).
2. On miss: DB → compress (Snappy) → put with TTL.
3. Invalidate via Kafka/CDC delete or overwrite.
4. Watch packet size limits (oversized → nil/empty bin poison — real MMT class bug).
5. Cluster: add nodes; data partitioned automatically.

---

## How vs related ideas

| | Redis | Aerospike | MySQL |
|--|-------|-----------|-------|
| Fat catalog QPS | Costly RAM | Strong fit | Too slow |
| Complex queries | No | No | Yes |
| SoT booking | No | No | Yes |

---

## Pitfalls

- Treating Aerospike as booking SoT
- Storing empty bins as valid ‘0 rateplans’ hit
- Repopulating cache on **GET timeout** (write storm)
- One global size limit for all sets

---

## 30-sec answer

> Aerospike is our high-QPS catalog cache: index in RAM, data on SSD, get-by-key. I use cache-aside from Goku — MySQL remains truth; Aerospike absorbs search reads.

## Related

- ./04-cache-aside-miss-vs-error.md
- ../05-10k-to-100k-tps/05-hot-keys-and-skew.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
