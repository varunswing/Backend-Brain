# Band 1: 1 → 10 TPS/RPS

← [Ladder index](../README.md) | Next: [10 → 100](../02-10-to-100-tps/)

**Traffic feel:** Side project, internal tool, early MVP. One request every few seconds → a few per second.

---

## 1. Snapshot — what the system looks like

```text
Client → App (1 process) → MySQL/Postgres (1 instance)
                └─ maybe local files / SQLite
```

| Layer | Typical choice | Why enough |
|-------|----------------|------------|
| App | 1 VM / laptop / single container | CPU idle most of the time |
| DB | Single primary | Writes tiny; no replica lag worry |
| Cache | None (or process memory) | Working set fits RAM of one box |
| Queue | None | Sync request/response fine |
| Deploy | Manual / simple CI | Change rate low |

**Back-of-envelope:** 10 RPS × 200 ms = **2 concurrent** requests. A single core laughs at this.

---

## 2. What happens (symptoms)

| Works fine | Starts hurting (even at low RPS) |
|------------|----------------------------------|
| Throughput | Wrong indexes → 2s queries still “slow product” |
| One box | Disk full, no backups, secrets in `.env` |
| Happy path | No idempotency → double-click double charge |
| Deploy | Downtime deploy OK until customers notice |

**Key insight:** At 1–10 RPS you almost never have a *scale* problem. You have **correctness, operability, and data model** problems that will explode later.

---

## 3. Why (physics)

- **CPU / network:** Unused. Bottleneck is human (bugs, unclear requirements).
- **DB:** Even bad queries often “feel OK” because QPS is tiny — **masks future pain**.
- **Connections:** Default pools (10–50) are overkill.
- **Failure domain:** One box = 100% blast radius — acceptable only while traffic is tiny.

---

## 4. What you do (and what you skip)

### Do now

| Action | Why |
|--------|-----|
| Clear schema + primary keys + unique constraints | Prevents duplicate money / bookings |
| Basic indexes on hot filters (`user_id`, `created_at`) | Cheap insurance |
| Structured logs + request id | Debug without guessing |
| Backups + restore drill once | 10 RPS still loses money if disk dies |
| Timeouts on outbound HTTP | One hung dependency ≠ hung process |
| Idempotency key on payments / creates | Scale later won’t invent this for you |

### Skip / don’t overbuild

| Temptation | Why wait |
|------------|----------|
| Kafka, CQRS, microservices | Ops cost > benefit |
| Redis cluster | One DB fine |
| Multi-AZ everything | Cost; learn product first |
| Sharding | Zero evidence of need |

---

## 5. Capacity math

```text
Peak concurrent ≈ RPS × latency_s
10 × 0.2 = 2
```

DB: even 50 ms query × 10 RPS = 0.5 QPS equivalent load — **indexes still matter for p99 spikes** (admin report, bad loop).

---

## 6. Interview script

### 30 sec

> “At 1–10 RPS I’d keep a **simple monolith and one DB**. Scale isn’t the enemy — **data integrity and debuggability** are. I’d add indexes, idempotency for writes, backups, and timeouts. I would **not** introduce Kafka or shards yet.”

### 2–3 min blocks

1. **Situation:** MVP / low traffic.  
2. **Architecture:** 1 app + 1 DB. Draw it.  
3. **Risks:** Silent bad schema, no backup, double submit.  
4. **Levers:** Constraints, indexes, logs, backups.  
5. **Explicit non-goals:** Distributed everything.  
6. **Exit criterion:** Sustained >10–20 RPS + rising p99 → next band.

---

## 7. Transition triggers → 10–100

Move when you see:

- Sustained **>10 RPS** or bursty **>50**
- DB CPU or slow query log lighting up
- Need zero-downtime deploys
- Second app instance desired for deploys / mild HA

---

## Topics to learn (this band)

Each file: **What** · **Why this band** · **How** · compare · pitfalls · 30-sec answer.

- [PK / Unique / FK](./01-data-integrity-constraints.md)
- [Indexes basics](./02-indexes-basics.md)
- [Idempotency](./03-idempotency.md)
- [Timeouts](./04-timeouts-and-retries.md)
- [Logs + request ID](./05-logging-request-ids.md)
- [Backups + restore](./06-backups-and-restore.md)

## Checklist

- [ ] PK / unique / FK where money or identity matters  
- [ ] Indexes on real query patterns (EXPLAIN once)  
- [ ] Request ID in logs  
- [ ] Backup + restore tested  
- [ ] Outbound timeouts  
- [ ] Idempotent create/pay path  
- [ ] **No** premature Kafka/shard story in interview unless asked “future”
