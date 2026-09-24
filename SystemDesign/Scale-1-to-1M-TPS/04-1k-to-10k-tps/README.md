# Band 4: 1K → 10K TPS/RPS

← [100 → 1K](../03-100-to-1k-tps/) | [Index](../README.md) | Next: [10K → 100K](../05-10k-to-100k-tps/)

**Traffic feel:** Large consumer app / hotel search / major fintech read path. **Cache + replicas + async** are mandatory. Write path carefully isolated.

> **MMT anchor:** Supply search day-avg ~**2k RPS** lives here — Aerospike cache-aside, Goku pods, MySQL slaves on miss, Kafka invalidation. Booking writes ≪ search reads.

---

## 1. Snapshot

```text
Clients → Gateway/LB → App fleets (pools: user / bot / async)
                           ├─ L1 in-process cache
                           ├─ L2/L3 distributed cache (Redis / Aerospike)
                           ├─ MySQL primary (writes only)
                           ├─ MySQL replicas / ProxySQL (miss path)
                           └─ Kafka → consumers (invalidate / warm / index)
```

| Layer | Job |
|-------|-----|
| App | Many pods; pool isolation (bots ≠ users) |
| L1 | Sub-ms repeat keys per pod |
| L3 (Aerospike/Redis Cluster) | Fat catalog / session at multi-k QPS |
| DB reads | Replicas only for search-shaped traffic |
| DB writes | Master / domain services (booking, ARI) |
| Async | CDC/Kafka for cache bust — not sync on every read |

---

## 2. What happens

| Symptom | Meaning |
|---------|---------|
| App CPU OK, p99 awful | Miss path / dependency tail / GC / lock |
| Aerospike/Redis timeout → DB melt | **Write-back on error** or miss storm |
| Replica lag → weird reads | Eventual consistency biting UX |
| Master WAL / row locks hot | Too many writes on request path |
| One “pool” (bot scrapers) starves users | No traffic isolation |

---

## 3. Why

```text
DB_QPS ≈ RPS × (1 − hit) × reads_per_req

5,000 RPS × 10% miss × 3 = 1,500 QPS  → need replicas + indexes
5,000 RPS × 40% miss × 3 = 6,000 QPS  → crisis; fix hit rate first
```

| Physics | Detail |
|---------|--------|
| **Cache is the scale plane** | Disk DB cannot serve multi-k fat-blob reads alone |
| **Miss ≠ error** | Timeout + async repopulate = **amplification** (classic Goku failure mode) |
| **Write:read asymmetry** | Search 2k RPS vs book ~1 RPS → different architectures |
| **Invalidation** | TTL alone → stale; sync invalidate on write → write amplification; **Kafka/CDC** balances |
| **Tail latency** | p99 dependencies dominate; timeouts + bulkheads required |

---

## 4. What you change

| Lever | What |
|-------|------|
| **Multi-tier cache** | L1 + distributed KV; Snappy/compress fat JSON |
| **Cache-aside policy** | Miss → DB → async fill; **never** fill on infra error |
| **Read replicas + pool split** | `citysearch` vs `booking` pools |
| **Event-driven invalidation** | Debezium/binlog → Kafka → delete/warm keys |
| **HPA + pod pools** | Isolate bot/async |
| **Bulkheads** | Timeouts, circuit breakers, rate limits |
| **CQRS-lite** | Read models / search index ≠ write model |
| **Idempotent consumers** | At-least-once Kafka safe |

### Explicitly avoid

- Routing **search reads to master**
- Infinite vertical scale on one MySQL
- Storing booking source-of-truth only in cache

---

## 5. Growth worksheet (sale day)

1. Peak RPS × miss% = miss QPS → load-test **that**  
2. HPA max = `ceil(peak / RPS_per_pod) × headroom`  
3. Replica count absorbs miss QPS  
4. Kafka lag SLO under ARI/write burst  
5. Rollback: rate limit, cache override, feature flags  

---

## 6. Interview script

### 30 sec

> “Between 1K and 10K RPS, especially read-heavy, I treat **distributed cache as the serving tier** and MySQL replicas as the miss path. Writes and invalidation go async via **Kafka/CDC**. I isolate traffic pools, enforce timeouts, and never let cache **errors** trigger write storms. Scaling pods without hit-rate is a trap.”

### 3–5 min

1. Draw Goku-like path: L1 → Aerospike → slave → async warm.  
2. Hit-rate math on whiteboard.  
3. Miss vs timeout policy.  
4. Search vs book TPS ratio.  
5. Sale-day checklist.  
6. Next band: single-cluster limits / hot keys / multi-tenant fan-out.

---

## 7. Transition → 10K–100K

- Sustained **10K+** or global spikes
- Single cache/DB cluster vertical limit
- Hot partition / celebrity key
- Need geographic locality or shard by tenant/hotel_id

---

## Topics to learn (this band)

Each file: **What** · **Why this band** · **How** · compare · pitfalls · 30-sec answer.

- [Multi-tier cache L1/L2/L3](./01-multi-tier-cache.md)
- [Aerospike what & how](./02-aerospike.md)
- [Redis Cluster](./03-redis-cluster.md)
- [Miss vs error](./04-cache-aside-miss-vs-error.md)
- [Replicas + ProxySQL](./05-mysql-replicas-proxysql.md)
- [Kafka + CDC invalidate](./06-kafka-cdc-invalidation.md)
- [Bulkheads + breakers](./07-bulkheads-circuit-breakers.md)
- [CQRS-lite](./08-cqrs-lite.md)
- [Traffic pool isolation](./09-traffic-pool-isolation.md)

## Checklist

- [ ] Hit-rate dashboard (catalog, not just ops success)  
- [ ] Miss/error policy coded  
- [ ] Write path off search  
- [ ] Replica + pool budgets  
- [ ] Kafka consumer lag alerts  
- [ ] Load-test miss path, not only happy cache
