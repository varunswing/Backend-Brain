# Band 2: 10 → 100 TPS/RPS

← [1 → 10](../01-1-to-10-tps/) | [Index](../README.md) | Next: [100 → 1K](../03-100-to-1k-tps/)

**Traffic feel:** Small production product. Busy minutes, quiet nights. Still one primary DB, but queries and locks start mattering.

---

## 1. Snapshot

```text
Clients → App (1–2 instances) → Primary DB
                └─ optional Redis for sessions
```

| Layer | Typical | Notes |
|-------|---------|-------|
| App | 1–2 nodes behind simple LB or just one | Vertical scale first often OK |
| DB | One primary, maybe nightly dumps | Connection pool sized (~20–100) |
| Cache | Session / rate-limit Redis optional | Not yet full catalog cache |
| Jobs | Cron on app box or small worker | Reports, emails |
| Observability | Metrics + slow query log | Essential now |

**Math:** 100 RPS × 100 ms = **10 concurrent**. Fine for app; **DB query shape** decides fate.

---

## 2. What happens

| Symptom | Meaning |
|---------|---------|
| p95 climbs while RPS flat | Queries / locks, not “need 50 servers” |
| Occasional timeouts under small bursts | Pool wait + slow SQL |
| Admin page kills API | Unbounded `SELECT *` / missing pagination |
| “It worked on staging” | Staging has 100 rows; prod has 10M |

**Still not:** Needing global sharding. **Is:** Needing **query discipline**.

---

## 3. Why

| Resource | What bites |
|----------|------------|
| **Disk I/O** | Full table scans, missing index → random reads |
| **Row locks** | Long transactions; `SELECT … FOR UPDATE` held during HTTP |
| **Connection pool** | Each slow query holds a connection; pool = 50 → queueing |
| **Single thread of truth** | One primary serializes writes; 100 write TPS rare here but 100 read QPS of bad SQL hurts |
| **N+1 ORM** | 100 RPS × 20 queries = 2,000 QPS accidental |

```text
Apparent_RPS = 50
Actual_DB_QPS = 50 × (1 + N+1 extras)  → can look like 500–2000 QPS
```

---

## 4. What you change

### Add

| Change | Why |
|--------|-----|
| **Indexes** from EXPLAIN / slow log | Highest ROI |
| **Pagination** everywhere | Kill unbounded reads |
| **Connection pooling** (PgBouncer / Hikari / etc.) | Cap and reuse |
| **Short transactions** | Lock time ↓ |
| **2nd app instance + LB** | Deploy without downtime; tiny HA |
| **Basic cache** (session, config, hot single keys) | Cut repeat reads |
| **Background jobs** for email/PDF | Keep request path thin |
| **Rate limit** abusive clients | Protect 1 DB |

### Still usually skip

- Read replicas (unless reporting load hurts primary)
- Kafka (unless clear async domain)
- Microservices split “for scale”

---

## 5. Capacity math examples

| Scenario | Calc | Action |
|----------|------|--------|
| 80 RPS, 5 queries/req, no cache | 400 QPS | Index + cut queries |
| 80 RPS, 1 query, 20 ms | Easy | App fine |
| Report job scans 20M rows | Spikes I/O | Replica or off-hours + covering index |

**Pool rule of thumb:**  
`pool_size ≈ (cores × 2) to (cores × 4)` for OLTP — not “set 500 because traffic.”

---

## 6. Interview script

### 30 sec

> “From 10 to 100 RPS the failure mode is usually **bad queries and lock time**, not lack of servers. I’d add indexes, pagination, connection pooling, short transactions, maybe a second app instance and a small Redis for sessions. I’d measure DB QPS vs RPS to catch N+1.”

### 2–3 min

1. Draw 1–2 apps + primary DB.  
2. Show N+1: RPS × queries.  
3. Fix order: **measure → index → pool → cache hot keys → split read-heavy reports**.  
4. Mention deploy HA via 2 apps.  
5. Trigger to next band: primary CPU high even after indexes / cache.

---

## 7. Transition → 100–1K

- Sustained **100+ RPS**
- Primary CPU / IOPS high after query hygiene
- Need cache for **many** keys (catalog, feed)
- Read vs write asymmetry clear (reads dominate)

---

## Topics to learn (this band)

Each file: **What** · **Why this band** · **How** · compare · pitfalls · 30-sec answer.

- [EXPLAIN + slow log](./01-explain-and-slow-queries.md)
- [N+1 queries](./02-n-plus-1-problem.md)
- [Connection pooling](./03-connection-pooling.md)
- [Transactions + locks](./04-transactions-and-locks.md)
- [Pagination](./05-pagination.md)
- [Redis sessions](./06-redis-sessions.md)
- [Rate limiting](./07-rate-limiting-basics.md)
- [Background jobs](./08-background-jobs.md)

## Checklist

- [ ] Slow query log reviewed weekly  
- [ ] No unbounded list APIs  
- [ ] Pool sized consciously  
- [ ] Transactions don’t call external HTTP while holding locks  
- [ ] 2 app instances if uptime matters  
- [ ] Basic dashboards: RPS, p95, DB CPU, pool wait
