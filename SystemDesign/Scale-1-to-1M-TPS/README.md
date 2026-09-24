# Scale Ladder — 1 TPS/RPS → 1M TPS/RPS

Interview-ready path: **what breaks**, **why**, **what you add** as load grows.

> **RPS vs TPS:** RPS = requests/sec (HTTP/gRPC). TPS = transactions/sec (often DB commits / money moves). In interviews people mix them — clarify: *“I’ll use RPS for API ingress; write TPS is usually much lower.”* Numbers below are **ingress RPS** unless marked write.

---

## Topic curriculum (per band)

Open the band folder — each topic has its own doc (**what / how / why / 30-sec**).

| Band | Learn these |
|------|-------------|
| [1 → 10](./01-1-to-10-tps/) | Constraints, indexes, idempotency, timeouts, request IDs, backups |
| [10 → 100](./02-10-to-100-tps/) | EXPLAIN, N+1, pooling, locks, pagination, Redis sessions, rate limit, jobs |
| [100 → 1K](./03-100-to-1k-tps/) | LB/stateless, HPA, cache-aside, stampede, replicas, queues, jitter, conn budget |
| [1K → 10K](./04-1k-to-10k-tps/) | Multi-tier cache, **Aerospike**, **Redis Cluster**, miss vs error, CDC/Kafka, CQRS-lite |
| [10K → 100K](./05-10k-to-100k-tps/) | **Sharding**, **CQRS**, CDN, Kafka scale, hot keys, cells, shedding, consistency |
| [100K → 1M](./06-100k-to-1m-tps/) | Multi-region, edge, anycast, fan-out, specialized stores, CRDT, SLOs, decompose 1M |

**~50 topic files** total. Start at your interview band; skim adjacent bands for “what’s next.”

---

## How to use

| Goal | Do this |
|------|---------|
| Quick map | Read table below |
| Deep band | Open that folder’s `README.md` |
| Interview | Say band you’re in → bottleneck → next lever |
| Whiteboard | Draw “before” → “after” for the jump |

---

## Ladder (folders)

| Band | Folder | Typical shape | Dominant bottleneck |
|------|--------|---------------|---------------------|
| **1 → 10** | [01-1-to-10-tps](./01-1-to-10-tps/) | 1 box, 1 DB | Correctness, not scale |
| **10 → 100** | [02-10-to-100-tps](./02-10-to-100-tps/) | App + DB + indexes | Slow queries, locks |
| **100 → 1K** | [03-100-to-1k-tps](./03-100-to-1k-tps/) | LB + N app + cache | DB CPU / connections |
| **1K → 10K** | [04-1k-to-10k-tps](./04-1k-to-10k-tps/) | Cache-aside + replicas + async | Cache miss, master writes |
| **10K → 100K** | [05-10k-to-100k-tps](./05-10k-to-100k-tps/) | Shard / CQRS / CDN / queues | Hot partitions, fan-out |
| **100K → 1M** | [06-100k-to-1m-tps](./06-100k-to-1m-tps/) | Multi-region, edge, specialized stores | Coordination, consistency tax |

```text
1 ──► 10 ──► 100 ──► 1K ──► 10K ──► 100K ──► 1M
 │      │       │      │       │        │       │
 │      │       │      │       │        │       └─ edge + multi-DC + specialized
 │      │       │      │       │        └─ shard / CQRS / heavy async
 │      │       │      │       └─ replicas + cache + queues
 │      │       │      └─ horizontal app + Redis/Aero
 │      │       └─ indexes + pooling + basic cache
 │      └─ still mostly monolith + one DB
 └─ laptop / single VM OK
```

---

## Universal capacity math (use every band)

```text
Capacity_needed ≈ Peak_RPS × Avg_work_per_req
DB_QPS_reads    ≈ RPS × (1 − cache_hit_rate) × reads_per_req
DB_QPS_writes   ≈ Write_RPS × writes_per_req   (often ≪ read RPS)
Connections     ≈ concurrent_inflight ≈ RPS × latency_sec
```

**Example:** 5,000 RPS, p50 = 50 ms → ~250 in-flight. At 500 ms p99 spike → ~2,500 in-flight (pools melt).

**Rule:** Scale **hit rate and latency** before raw hardware. Miss storm ≠ “need bigger DB” first.

---

## Interview 30-sec meta-pitch

> “I don’t jump to Kafka and shards at 50 RPS. Scale is a **ladder**: first correctness and indexes, then app replicas + cache, then read replicas and async writes, then sharding and CQRS, then multi-region and edge. At each step I name the **bottleneck** (CPU, connections, locks, hot key, cross-DC RTT) and the **cheapest lever** that removes it.”

---

## Related

- Core: `../Core-concepts/Scalability-Architecture/`
- Building blocks: cache, rate limit, queues under `../Core-concepts/`
- MMT real numbers: `Resume/MMT/System Design/18-mmt-scale-tps.md` (~2k search RPS band)
