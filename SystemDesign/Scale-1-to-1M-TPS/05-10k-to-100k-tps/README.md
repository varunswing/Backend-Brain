# Band 5: 10K → 100K TPS/RPS

← [1K → 10K](../04-1k-to-10k-tps/) | [Index](../README.md) | Next: [100K → 1M](../06-100k-to-1m-tps/)

**Traffic feel:** Super-app / major social / large ad or feed / global flash sale. One primary DB is **gone** as a universal read/write store. **Sharding, CQRS, CDN, and heavy async** dominate.

---

## 1. Snapshot

```text
                    ┌─ CDN / edge cache (static + some API)
Clients ─► Gateway ─┼─ API fleet (region)
                    │     ├─ partitioned cache clusters
                    │     ├─ sharded DB / NewSQL / specialized stores
                    │     └─ Kafka (many partitions) → consumers / stream jobs
                    └─ Async pipelines (search index, ML features, notifications)
```

| Concern | Approach |
|---------|----------|
| Data volume + QPS | **Shard** by `user_id` / `hotel_id` / `tenant_id` |
| Read fan-out | CQRS: write DB ≠ read models (ES, columnar, KV) |
| Hot keys | Split keys, local cache, request coalesce, sometimes **dedicated** store |
| Writes | Batch, async, conflict-free where possible (CRDT/ops) |
| Deploy | Canaries, progressive delivery; blast radius per shard |

---

## 2. What happens

| Symptom | Cause |
|---------|-------|
| One shard red-hot, others idle | Bad partition key / skew |
| Cross-shard joins impossible / slow | Normalized relational habits at wrong tier |
| Consumer lag hours | Under-partitioned Kafka / slow consumer / hot partition |
| “Strong consistency everywhere” latency | Coordination (2PC, cross-shard tx) tax |
| Cache cluster rebalance storms | Large nodes; need careful capacity |

---

## 3. Why

| Limit | Why you hit it |
|-------|----------------|
| **Single-node QPS** | Even NVMe KV / MySQL primary has a ceiling |
| **Working set** | Data > one machine RAM/disk sweet spot |
| **Lock/coordination** | Global locks don’t scale |
| **Network fan-in** | One popular entity → thundering herd |
| **Operational blast** | One cluster fail = too much % of traffic |

```text
Shard_QPS ≈ Total_QPS / N_shards   (ideal uniform)
If 5% keys get 80% traffic → those shards need special handling
```

---

## 4. What you change

### Data plane

| Pattern | Use when |
|---------|----------|
| **Horizontal sharding** | Clear partition key; avoid cross-shard tx |
| **CQRS + event sourcing (selective)** | Many read shapes, audit, replay |
| **Search/index cluster** | Discovery ≠ SoT |
| **Cell / pod architecture** | Isolate tenants or regions into cells |
| **CDN + edge** | Cacheable GETs, media, some personalized via edge workers |
| **Stream processing** | Aggregate, fraud, feed fan-out offline/nearline |

### Control plane / safety

| Pattern | Why |
|---------|-----|
| Per-shard / per-tenant limits | Noisy neighbor |
| Adaptive load shedding | Protect core money path |
| Backpressure | Queues with max lag SLO |
| Chaos + game days | Failures certain at this scale |

### Consistency menu (say explicitly)

| Need | Tool |
|------|------|
| Money / inventory confirm | Strong on **narrow** write path |
| Feed / search / ranking | Eventual + repair |
| Counters | Approx or CRDT / Redis |

---

## 5. Capacity math

```text
Target 50,000 RPS reads, hit 99% on edge+KV:
  Origin RPS ≈ 500
Target 50,000 with 90% hit:
  Origin ≈ 5,000 → still need sharded origin

Writes 2,000 TPS into Kafka:
  Partitions ≥ consumer_parallelism; key by entity for ordering
```

**Ordering:** Same partition key → ordered. Global order → bottleneck — avoid.

---

## 6. Interview script

### 30 sec

> “At tens of thousands of RPS I assume **no single database**. I shard by a stable key, push reads to **CQRS/cache/CDN**, keep **strong consistency only on the narrow write path**, and use **Kafka-scale** pipelines for fan-out. I design for **skew** — hot keys and hot partitions — not just average QPS.”

### 3–5 min

1. Pick partition key; show skew failure.  
2. Draw CQRS: write API → log/DB → consumers → read models.  
3. Where strong vs eventual.  
4. Load shed + cell isolation.  
5. Metrics: per-shard QPS, lag, hot key.  
6. Next: multi-region active-active / edge at 100K–1M.

---

## 7. Transition → 100K–1M

- Multi-continent latency budget
- Active-active or region pin
- Hardware/special NICs, custom UDP/gRPC stacks sometimes
- Org: platform teams, capacity org, formal error budgets

---

## Topics to learn (this band)

Each file: **What** · **Why this band** · **How** · compare · pitfalls · 30-sec answer.

- [Sharding](./01-sharding.md)
- [CQRS](./02-cqrs.md)
- [CDN + edge](./03-cdn-and-edge.md)
- [Kafka partitions](./04-kafka-at-scale.md)
- [Hot keys + skew](./05-hot-keys-and-skew.md)
- [Cell architecture](./06-cell-based-architecture.md)
- [Load shedding](./07-load-shedding.md)
- [Consistency models](./08-consistency-models.md)
- [Event sourcing (selective)](./09-event-sourcing-selective.md)

## Checklist

- [ ] Partition key chosen + skew plan  
- [ ] No accidental cross-shard 2PC on hot path  
- [ ] Read models explicit  
- [ ] Kafka partition count vs consumers  
- [ ] Load shedding on non-critical  
- [ ] Per-cell / per-shard dashboards
