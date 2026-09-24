# Band 3: 100 → 1K TPS/RPS

← [10 → 100](../02-10-to-100-tps/) | [Index](../README.md) | Next: [1K → 10K](../04-1k-to-10k-tps/)

**Traffic feel:** Solid mid-size product / busy marketplace vertical. Horizontal **app** scale becomes normal; **cache** becomes a first-class tier.

---

## 1. Snapshot

```text
Clients → LB → App pods × N (stateless)
                    ├─► Redis / Memcached (hot keys)
                    └─► MySQL/Postgres primary
                           └─ optional 1 read replica (reports / heavy reads)
```

| Layer | Typical |
|-------|---------|
| App | HPA / multiple pods; **stateless** (session in Redis or JWT) |
| Cache | Redis for sessions, rate limits, hot entities |
| DB | Primary + maybe replica; pool per service |
| Async | Queue for email, webhooks, search index updates |
| Edge | CDN for static only |

**Math:** 1,000 RPS × 50 ms = **50 concurrent**. Apps scale out easily; **DB and cache miss path** don’t.

---

## 2. What happens

| Symptom | Cause |
|---------|-------|
| Adding app pods doesn’t help latency | Bottleneck moved to DB / shared Redis |
| Pool exhaustion under load test | RPS × latency × queries; or lock waits |
| Cache stampede on key expiry | Many pods miss together → DB spike |
| Replica lag complaints | Reads of “read-your-writes” on wrong node |
| Deploy / config storms | Thundering herd to dependencies |

---

## 3. Why

| Bottleneck | Mechanism |
|------------|-----------|
| **DB CPU** | Every miss / uncached read hits primary |
| **Connections** | `N_pods × pool_size` can exceed `max_connections` |
| **Single primary writes** | All writes serialize on one node |
| **Hot keys** | One Redis key / one row → contention |
| **Sync fan-out** | One API call → 10 downstream HTTP = 10× load |

```text
Total_DB_connections ≈ num_pods × pool_max
Example: 30 pods × 50 = 1,500 → primary max_connections=500 → meltdown
```

**Fix direction:** smaller pools + PgBouncer/ProxySQL, or fewer chatty queries — not blind “more pods.”

---

## 4. What you change

| Add | Why |
|-----|-----|
| **Stateless app + LB + HPA** | Scale compute independently |
| **Redis cache-aside** for hot reads | Cut DB QPS |
| **Single-flight / lock on miss** | Stop stampede |
| **Read replica** for analytics / heavy GET | Protect primary |
| **Queue (SQS/Rabbit/Kafka lite)** for side effects | Shrink request critical path |
| **Timeouts + retries with jitter** | Contain failure |
| **Rate limiting** at gateway | Protect origin |
| **Connection pool budget** | Explicit math vs `max_connections` |

### Patterns to name in interview

- Cache-aside  
- Stateless services  
- Read/write splitting (careful with consistency)  
- Async for non-critical path  

### Still careful

- Don’t put **all** reads on replica if user must see own write immediately → primary or session stickiness / version check.  
- Don’t invent 20 microservices — split only on **failure domain / team / scale need**.

---

## 5. Capacity math

```text
DB_QPS ≈ RPS × (1 − hit_rate) × reads_per_req

1,000 RPS × 10% miss × 2 reads = 200 QPS  → primary OK if indexed
1,000 RPS × 50% miss × 5 reads = 2,500 QPS → primary in trouble
```

| Lever | Effect |
|-------|--------|
| Hit 50% → 90% | 5× less DB read QPS |
| Cut reads_per_req 5 → 2 | 2.5× less |
| Add pods only | Helps CPU of app, **not** DB if miss-bound |

---

## 6. Interview script

### 30 sec

> “At hundreds to ~1K RPS I scale **stateless app replicas behind a load balancer**, put **hot reads in Redis**, guard miss stampedes, and budget **DB connections** carefully. I move emails and webhooks **off the request path** onto a queue. If reports hurt the primary, I add a **read replica** — not a shard yet.”

### 2–3 min

1. Draw LB → N apps → Redis → primary (+ replica).  
2. Show connection math: pods × pool.  
3. Show hit-rate formula.  
4. Stampede story + single-flight.  
5. Consistency caveat on replicas.  
6. Exit: miss path or write path saturates → next band.

---

## 7. Transition → 1K–10K

- Sustained **1K+ RPS**
- Cache hit critical; multi-prefix / fat blobs (think Aerospike/catalog)
- Write amplification or ARI-like updates need **event-driven invalidation**
- Primary write CPU / WAL / lock time saturates

---

## Topics to learn (this band)

Each file: **What** · **Why this band** · **How** · compare · pitfalls · 30-sec answer.

- [LB + stateless](./01-load-balancer-and-stateless.md)
- [HPA / horizontal scale](./02-horizontal-pod-autoscaling.md)
- [Redis cache-aside](./03-redis-cache-aside.md)
- [Stampede + single-flight](./04-cache-stampede-single-flight.md)
- [Read replicas](./05-read-replicas.md)
- [Message queues](./06-message-queues.md)
- [Timeouts + jitter](./07-timeouts-retries-jitter.md)
- [Connection budget](./08-connection-budget.md)
- [Gateway rate limits](./09-rate-limiting-gateway.md)

## Checklist

- [ ] Apps stateless  
- [ ] Pool budget documented  
- [ ] Cache + TTL + stampede control  
- [ ] Timeouts on all RPC  
- [ ] Queue for side effects  
- [ ] Dashboards: hit rate, pool wait, DB CPU, RPS, p99
