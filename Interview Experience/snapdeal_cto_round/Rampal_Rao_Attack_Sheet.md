# Rampal Rao Final Round — Attack Sheet

**Interviewer:** Rampal Rao — Senior Director of Engineering, Snapdeal | AceVector Group (Snapdeal since ~2016; hands-on Java → Director path).  
**Style expect:** Technical + practical drill-down. Not “what is polymorphism.” More: “explain your system → why → what breaks at 10x → what did **you** own.”

**Language honesty:** MMT day-to-day = mostly **Go + gRPC**. Paytm = **Java/Spring + Kafka + MySQL**. For Java concurrency / JPA / Hikari — lead with Paytm; for search/pricing mesh — lead with MMT. Never fake Pinpoint depth — map to Grafana/Datadog/traces and say you’ll ramp.

**Pronoun rule he will enforce:**
- ❌ “We implemented…”
- ✅ “**I** owned / designed / investigated / changed / proposed…”

**Related:** [`CTO_Round_Prep.md`](./CTO_Round_Prep.md) (tenure / behavioral). This sheet = **technical drill**.

---

## 0. Opening (if he asks “tell me about yourself” technical)

> “I’ve built and owned high-blast-radius backends on two stacks: **Paytm** — Java/Spring Boot, Kafka, MySQL, UPI/refund money paths, P0 incident ownership; **MakeMyTrip INGO Hotels** — Go microservices for search/pricing/booking/extranet, including GST money-path program lead, HCP tax correctness, and a greenfield addon platform (Ancillary + DO). Happy to start from MMT architecture or from a Kafka/concurrency incident — whichever you prefer.”

---

# TIER 1 — MUST

---

## 1. MMT / INGO Hotels architecture (highest priority)

### Q1.1 Explain your current architecture.

**Whiteboard (speak while drawing):**

```text
                    Guest / App / Meta
                            │
                            ▼
                 ┌─────────────────────┐
                 │  Search / Review    │  (high QPS read path)
                 │  INGO-Goku          │  Aerospike cache + MySQL
                 └──────────┬──────────┘
                            │ gRPC
              ┌─────────────┼─────────────┐
              ▼             ▼             ▼
        PriceEngine    Content/Flags   Offers/Promo
        (tax, HCP,     (hotel caps)    (CUG, deals)
         CUG apply)
              │
              ▼
        Booking / Inventory / ReservationEngine
              │
              ▼
     ┌────────┴────────┐
     │ Extranet hosts  │──GraphQL──► Enigma (BFF)
     └────────┬────────┘              │
              │              Dynamic Orchestrator (DO)
              │                       │ parallel fan-out
              │         ┌─────────────┴─────────────┐
              │         ▼                           ▼
              │      Enigma metadata            Ancillary
              │      (rooms, flags)             (addon SoR)
              └──────────────────────────────────────────
```

**Say:**
> “INGO Hotels is a **microservice mesh**. **Goku** is the search/price aggregation edge for guests. It fans out to **PriceEngine** for money math (tax, HCP, CUG), uses **Aerospike** as cache-aside over MySQL-backed hotel/tax/offer data, and talks **gRPC/protobuf** internally.  
>  
> Host/extranet flows go **Extranet → Enigma (GraphQL BFF) → Dynamic Orchestrator**, which composes backends. I owned pieces across this mesh: pricing correctness (HCP), GST forward path design on Goku plus Inventory booking-update for refunds, and greenfield **Ancillary** + DO PaidEcLc for TrueFlexi.  
>  
> Async: Kafka for cache invalidation / addon topics and promo/inventory-style event paths on other initiatives.”

### Q1.2 Why this architecture (not a monolith)?

> “Team + release velocity + blast radius. Search must scale independently of extranet config writes. Pricing experiments shouldn’t block reservation releases — that’s why I pushed **Ancillary as SoR** instead of stuffing EC/LC into RE. Cost is operational complexity; we pay it where bounded contexts are real.”

### Q1.3 How does traffic flow for a search?

> “Request hits Goku → hydrate hotel set → GST/tax rules (cache then DB) → offers → PriceEngine apply → assemble room rates → response. Hot path is cache-heavy; miss/error must not poison cache (we hardened Aerospike GET-failure behavior on search optimization work).”

### Q1.4 Scale numbers (only claim what you can defend)

| Signal | What you can say |
|--------|------------------|
| Extra-guest mix | ~**15%** bookings, ~**50K/day** on HCP-relevant paths |
| Enigma class traffic | Org cites **50M+/day** class on gateway (dual-server story context) — say “org-scale gateway traffic” if not your personal metric |
| Pricing SLA | HCP add **&lt;2ms**; pricing band **~200–250ms** narrative |
| TrueFlexi quality | **143** gRPC ITs; Grafana on ancillary + DO |

If unsure on QPS:  
> “I don’t want to invent peak QPS — I can speak to **money-path volume** I owned (~50K/day extra-guest) and latency budgets on pricing.”

### Q1.5 What if traffic increases 10x?

> “Read path: more Goku/PE replicas; raise Aerospike capacity; protect DB with cache hit-ratio SLOs; shed non-critical enrichment.  
> Write path: partition Kafka by hotel/order key; bound consumer concurrency to DB pools; backpressure.  
> Failures: timeouts + circuit breakers so one slow PE doesn’t melt Goku threads.  
> I’d load-test the money path first — wrong tax at 10x is worse than slow search.”

### Q1.6 Where is Kafka?

> “Examples I touched: **Ancillary** post-commit publish to `AddonGokuCacheTopic` for cache refresh; promo/inventory event pipelines on Last Minutes–class work; Paytm refunds used Kafka as the durability backbone. Pattern: SoT in DB, Kafka for fan-out/async, not dual-write without care.”

### Q1.7 Where is the database?

> “Service-owned MySQL (Ancillary 5 tables master/slave; Inventory bookings; PE/Goku datastores). No shared ‘god DB.’ Read replicas for heavy read; writes on master with transactions for upserts.”

### Q1.8 How do you cache?

> “Cache-aside (Aerospike): read cache → miss → DB → async fill. **Critical:** on cache **GET error**, still read DB but **do not** async-write bad state (search guardrail I owned). TTL + explicit invalidation via Kafka where config changes.”

### Q1.9 What if one service goes down?

> “Depends which. PE down → degrade search (no price / error budget). Ancillary down → PaidEcLc GET fails for enrolled hotels; metadata-only degrade only if product allows. Enigma down → extranet broken; guest search may still live via Goku. We use timeouts, not infinite waits; DO runs Enigma+Ancillary **in parallel** to cut tail latency.”

### Q1.10 How do you monitor?

> “Grafana on OpenTSDB metrics: API count/p95, downstream call/error/panic, runtime gauges. Correlation IDs across gRPC. Jenkins IT + Sonar before promote. Paytm side: Datadog on pool/lag/error. Snapdeal Pinpoint/Loki — same debugging habit, different UI.”

### Q1.11 Biggest production problem you faced (MMT)?

**Pick Hour CP or DO EqualFold or HCP boundary:**

> “Hour CP: UI showed hour, cancel engine empty — **display↔enforcement** mismatch. I traced Proto→RE→DO→Enigma EasyJSON; optional field died mid-chain. Fixed every hop; bar was refund liability.  
> Or DO GET null: case-sensitive category vs lowercase DB — EqualFold.  
> Or HCP: isolation tax negative at GST slabs — dual-method + release gate.”

### Q1.12 Biggest production problem (Paytm — he may love this more)?

> “P0: ~3% refund failures, ~₹2cr stuck. Leadership blamed network. **I** correlated Kafka retries + HikariCP 60-conn saturation + row locks. Hold time 200ms→5–8s at peak. Tuned retries, pool, circuit breakers; built alerts. Classic distributed failure cascade.”

---

## 2. Java + concurrency

### Q2.1 HashMap vs ConcurrentHashMap?

> “HashMap: not thread-safe; concurrent put/get can infinite-loop/corrupt (legacy) or race. ConcurrentHashMap: concurrent readers; updates lock finer-grained (segments historically / bins+CAS modern). **No ConcurrentModificationException** on iterators the same way — weakly consistent iteration. Use CHM for shared caches/maps; don’t use HashMap as static shared mutable state.”

### Q2.2 How does ConcurrentHashMap work (enough depth)?

> “Java 8+: array of bins; treeify long bins; updates use CAS on bin heads; reads volatile-ish visibility without full map lock. `size()` is estimate under concurrency. `computeIfAbsent` is atomic for that key — useful for idempotent caches.”

### Q2.3 synchronized vs volatile vs Atomic*?

| Tool | Use |
|------|-----|
| `synchronized` / Lock | Critical section, mutual exclusion |
| `volatile` | Visibility of single write; **not** atomic check-then-act |
| `AtomicInteger` / LongAdder | Lock-free counters |
| CHM | Concurrent map structure |

> “volatile ≠ thread-safe counter++. For check-then-act use locks/atomics/CHM compute.”

### Q2.4 ExecutorService / ThreadPoolExecutor — how size?

> “**CPU-bound:** ~`cores` or `cores+1`.  
> **IO-bound:** larger — `cores * (1 + wait/compute)` as starting heuristic, then measure.  
> **corePoolSize / maxPoolSize / queue:**  
> - Small queue + larger max → more threads under burst  
> - Large queue + fixed threads → latency grows, protects DB  
> For refunds I cared more about **not exceeding DB pool** than maximizing threads.”

### Q2.5 Thread pool exhausted — what happens?

> “Depends on `RejectedExecutionHandler`: Abort (exception), CallerRuns (caller thread executes — backpressure), Discard. Symptoms: API latency↑, queue depth↑, or rejections. Fix: bound ingress, shed load, scale consumers carefully, **never** unbounded queue + createOnDemand into DB death spiral.”

### Q2.6 10,000 requests, pool size 200 — what happens?

> “Only 200 run; rest queue or reject. If each task holds a DB connection, you also hit Hikari limits (e.g. 60) — thread pool &gt; DB pool is a common footgun. Align: servlet/tomcat threads, app executor, Hikari maxActive, downstream timeouts.”

### Q2.7 CompletableFuture?

> “Compose async IO (supplyAsync + thenCombine). On Paytm we thought in async stages for downstream calls; must specify executor (not `ForkJoinPool.commonPool()` for blocking JDBC). Timeouts + exceptionally for degrade.”

### Q2.8 Deadlock / race — example you owned?

> “Refund P0: race between retry consumers and row locks — multiple consumers processing same refund identity under failure → longer locks → pool exhaust → more retries. Fix: identity-keyed locking/idempotency, separate retry groups, shorter lock scope, pool headroom.”

### Q2.9 Thread safety checklist you’d apply at Snapdeal?

> “No shared mutable statics; immutable messages; DB transaction boundaries clear; idempotent consumers; bound every pool; timeouts on all RPC.”

---

## 3. Kafka (drill-heavy)

### Fundamentals (crisp)

| Term | One line |
|------|----------|
| Partition | Ordered log shard; parallelism unit |
| Consumer group | Competing consumers; each partition → one consumer in group |
| Offset | Position in partition |
| Replication | Leader + followers; durability |
| ISR | In-sync replicas eligible for leadership |
| Rebalance | Group membership change → partition revokes/assigns |
| Lag | `log-end − committed offset` |

### Q3.1 Why Kafka (not only REST)?

> “Durable buffer, fan-out, peak smoothing, replay. Refunds/inventory events shouldn’t be lost if consumer down. REST couples availability; Kafka decouples producers from slow consumers.”

### Q3.2 Why not ActiveMQ/Rabbit only?

> “Kafka stronger as **high-throughput commit log** + replay + partition scale. Rabbit/ActiveMQ fine for classic work queues/routing. Snapdeal may use both — I’d pick by replay + retention + throughput needs.”

### Q3.3 Producer settings you used?

> “Paytm refunds: `acks=all`, `enable.idempotence=true`, retries bounded; key = business id for ordering per entity.”

### Q3.4 Consumer slow — what happens?

> “Lag grows; disk retention pressure; producer still accepts until quotas. Users see stale status. Alert on lag SLO; scale consumers **up to partition count**; optimize handler; isolate retry topic.”

### Q3.5 Crash after process, before offset commit?

> “At-least-once → **reprocess**. Must be **idempotent** (unique refund id / upsert). That’s why commit **after** success.”

### Q3.6 Crash after commit, before side effect finishes?

> “At-most-once risk if you commit early — we avoided that. Prefer: process → side effect → commit; or transactional outbox.”

### Q3.7 Prevent duplicates?

> “Idempotent producer + idempotent consumer (DB unique constraint / processed-event table). Exactly-once is ecosystem-hard; money systems usually **at-least-once + idempotent business logic**.”

### Q3.8 Ordering?

> “Order **within partition**. Key by `orderId`/`refundId`. Don’t expect global order across partitions.”

### Q3.9 How many partitions?

> “Start from target consumer parallelism and throughput; too many = overhead/rebalances; too few = can’t scale. Change later is painful — err slightly high for hot topics, measure.”

### Q3.10 Scale consumers?

> “Add instances until `consumers ≈ partitions` for that group. More consumers than partitions idle.”

### Q3.11 Rebalance pain?

> “Stop-the-world processing gaps; duplicate risk at revoke. Use cooperative sticky assignor when available; keep processing short; don’t do heavy init in rebalance callback.”

### Q3.12 Poison message?

> “Max retry → dead-letter topic + alert; don’t block partition forever. Log payload size/hash; fix forward.”

### Q3.13 Monitor Kafka?

> “Consumer lag by group/partition; produce/consume rates; ISR shrink; request latency; DLQ depth. Tie lag to **business SLO** (refund delay), not only broker CPU.”

### Resume line → drill chain (Paytm Kafka)

| Your line | Likely Q | Answer cue |
|-----------|----------|------------|
| Kafka migration / refunds | Why Kafka? | Durability + decouple |
| | Consumer crash? | Idempotent + commit after success |
| | Duplicate? | Unique business key |
| | Lag? | Alert + scale ≤ partitions + fix handler |
| | Partition count? | Parallelism vs overhead |
| P0 retries | Retry storm? | Separate retry group; backoff; circuit break |

---

## 4. Database + performance

### Q4.1 Query takes 5 seconds — debug steps

> “1) Reproduce with same params. 2) `EXPLAIN`/`EXPLAIN ANALYZE`. 3) Type: full scan? bad join order? filesort? temp table? 4) Indexes: missing / wrong order / low cardinality. 5) Data volume & skew. 6) Locks/waits. 7) App: N+1, holding txn open, pool wait mistaken for query time. 8) Fix: index/rewrite/batch; verify p95 not only one run.”

### Q4.2 Composite vs covering index?

> “Composite: `(status, created_at)` matches `WHERE status=? ORDER BY created_at`. Leftmost prefix matters. Covering: index includes all selected columns → index-only scan.”

### Q4.3 Isolation / deadlocks?

> “Default often REPEATABLE READ (MySQL InnoDB). Deadlock: two txns lock rows opposite order — detect/retry. Keep transactions **short**; lock in consistent order; don’t call HTTP inside txn (refund P0 lesson).”

### Q4.4 Connection pool?

> “HikariCP size ≈ concurrent queries you can sustain, not thread count. Monitor active/pending/timeouts. P0: 60 max saturated when hold time exploded.”

### Q4.5 Read replicas / sharding?

> “Replicas for read scale; lag-aware. Shard when single primary can’t hold write/storage — by shop/seller/order id — last resort after vertical + cache.”

### Q4.6 Your MySQL story

> “Paytm: GTID multi-bank migration; archival with txn integrity; refund hot-path indexes. MMT Ancillary: master write / slave read for state-diff build.”

---

## 5. E-commerce system design (Snapdeal-shaped)

For each: requirements → APIs → data → consistency → scale sale → failure → metrics. Speak **I would**.

### Q5.1 Order Management (OMS)

> “Order state machine: CREATED→PENDING_PAYMENT→PAID→CONFIRMED→FULFILLING→SHIPPED→DELIVERED / CANCELLED. Idempotent create with client token. Payment webhook verifies signature + idempotent transition. Outbox publishes `OrderPaid`. Inventory reservation with TTL on create; confirm on pay; release on timeout/fail.”

### Q5.2 Inventory

> “Soft reserve vs hard decrement. SKU warehouse-level stock. Optimistic version or conditional update `stock = stock - n WHERE stock >= n`. Flash sale: pre-split inventory shards / queue admits to protect DB.”

### Q5.3 Fulfillment / warehouse / courier

> “After CONFIRMED: allocate warehouse (serviceability + SLA + cost), create shipment, assign courier, track scans via events. Seller vs Snapdeal fulfilled branches. Returns = reverse logistics state machine.”

### Q5.4 Search

> “Index pipeline (Kafka/CDC) → search cluster. Query: filter → retrieve → rank. Cache popular queries. Degrade: filters-only if ranker down.”

### Q5.5 Cart

> “User/cart id; line items; price revalidation at checkout (don’t trust cart price). Merge guest→user. TTL for guest carts.”

### Q5.6 Payment workflow

> “Initiate → redirect/SDK → webhook → reconcile job for missing webhooks. Never trust client ‘success’. Idempotent capture/refund keys.”

### Q5.7 High-traffic sale design?

> “Admit queue / rate limit; cache product pages; pre-warm; inventory sharding; async non-critical paths; circuit-break non-essential personalization; scale read replicas; protect checkout DB; feature-flag kill switches; load test + game day.”

### Trade-off table (he loves why / why not)

| Choice | Why | Why not always |
|--------|-----|----------------|
| Kafka | Buffer, replay | Ops + lag complexity |
| Sync REST | Simple read path | Couples failure |
| MySQL | Strong txn money | Write ceiling |
| Mongo | Flexible catalog attrs | Weak multi-doc txn historically |
| Redis/Aerospike | Hot read | Stampede / stampede on error writes |
| Microservices | Scale teams | Distributed failure modes |
| Monolith modular | Simpler txn | Org scale limits |

---

## 6. Production debugging scenarios

### S1. Latency 200ms → 3s

> “Check dashboards: which dependency p99? DB? Kafka? Downstream? Recent deploy? Thread pool / DB pool wait vs CPU. Trace one request (Pinpoint/Zipkin-class). Isolate canary. Rollback if deploy-correlated.”

### S2. CPU 100%

> “Hot thread dump / flame graph; infinite loop; regex; tight spin; runaway GC? If GC → memory pressure. Scale horizontally only after knowing if work is useful.”

### S3. Memory climbing

> “Heap dump; leak (cache unbounded, listener leak); metaspace; move to off-heap carefully. Check CHM caches without eviction.”

### S4. Kafka lag climbing

> “Consumer error loop? Slow DB? Rebalance storm? Partition skew hot key? Scale consumers; fix handler; pause producers if dangerous.”

### S5. DB CPU 95%

> “Top queries; missing index; lock wait; connect storm from app; runaway report. Kill bad query; add index carefully; shed read traffic to replica.”

### S6. One microservice 5xx

> “Dependency? deploy? saturation? Circuit break callers; rollback; scale; feature flag off.”

### S7. Metrics green, users say slow

> “Client-side / CDN / specific geo / specific API not on dashboard / cache stampede on rare keys / bad percentile (avg hides). Check **user-centric** timings and error rate by endpoint + cohort.”

### Tie to your incident

> “Refund P0 looked ‘random’ until pool hold-time and retry correlation — green host CPU doesn’t mean healthy money path.”

---

## 7. JPA / Hibernate

### Concepts (practical)

| Topic | Say |
|-------|-----|
| Lazy vs Eager | Lazy default associations; eager can explode joins |
| N+1 | 1 query entity + N for each association |
| Persistence context | L1 cache per EntityManager/Session |
| Dirty checking | Flush syncs dirty entities |
| Optimistic lock | `@Version` — retry on conflict |
| Pessimistic | `LockModeType.PESSIMISTIC_WRITE` — short txns |
| Propagation | `REQUIRED` default; `REQUIRES_NEW` for independent audit |

### Q. API makes hundreds of DB calls — investigate?

> “Enable SQL logging/APM (Pinpoint). Look for N+1. Fix: join fetch / EntityGraph / DTO query / batch size. Also check lazy in loops after session closed (LazyInitializationException) — sometimes people open-session-in-view hiding mess.”

### Honesty

> “I used Spring + JDBC/JPA patterns at Paytm; I’m sharper on transaction/pool/SQL than on every Hibernate annotation trivia — I’ll ramp on Snapdeal’s exact stack fast.”

---

## 8. System design trade-offs (senior bar)

Practice aloud for **each** of your stories:

| Story | Why X | Why not Y |
|-------|-------|-----------|
| Ancillary service | SoR + dedup | Stuff into RE |
| DO façade | Simplify extranet | Direct Ancillary JSON |
| Goku GST override | Hit date | Wait forever for data migration |
| Sibling CUG AD | Clean attribution | Mega JSON row |
| Kafka refunds | Durability | Sync-only chain |
| Dual-method HCP | Correct money | Ship isolation-only |

---

# TIER 2 — Strong to have

### Redis / Aerospike
> “Hot keys, TTL, stampede protection (singleflight), never write on GET error.”

### Kubernetes
> “Deploy, probes, HPA on CPU/RPS/lag, pod disruption for consumers, resource limits to avoid noisy neighbor.”

### AWS
> “Familiar with cloud-hosted Kafka/MySQL/K8s patterns from Paytm/MMT infra; confirm Snapdeal’s exact services in ramp.”

### CI/CD
> “Jenkins IT image, Sonar gate, prodpp→prod; collaborate with DevOps.”

### RabbitMQ vs Kafka
> “Rabbit: flexible routing, classic queue. Kafka: log, replay, high throughput partitions.”

### Java GC (enough)
> “p99 latency spikes → GC logs; allocate less; size heap; G1 typical; avoid huge mid-request allocations.”

---

# TIER 3 — Light

- DSA: only if he pivots — refresh arrays/maps/heap medium  
- Behavioral tenure: see `CTO_Round_Prep.md` §1b  
- Don’t waste tonight on polymorphism definitions  

---

## Resume line → Rampal drill chains

### Line: Kafka / UPI refunds (Paytm)

1. Why Kafka?  
2. Consumer crashes mid-process?  
3. Idempotency how?  
4. Lag monitoring?  
5. Partition key?  
6. Retry storm incident?

### Line: P0 3% refund loss

1. What did **you** do first hour?  
2. Why not network?  
3. Pool sizing math?  
4. How prevent recurrence?  
5. How talk to leadership?

### Line: HCP / GST

1. Trade-off speed vs quality?  
2. What did you personally code vs lead?  
3. Isolation vs Direct?  
4. Why temporary override?

### Line: TrueFlexi / Ancillary

1. Why new service?  
2. State-diff upsert?  
3. How test 143 ITs?  
4. Prod bug EqualFold?

### Line: CUG Additional Discount

1. Why sibling not one row?  
2. Your ownership vs Teja?  
3. Legacy app block why?

### Line: Grace/Hour CP

1. Display vs enforcement?  
2. Deploy order?  
3. EasyJSON lesson?

---

## Tonight study order (3–4 hours)

| Block | Time | Focus |
|-------|------|-------|
| 1 | 45m | Draw MMT architecture + 10x + failure modes aloud |
| 2 | 45m | Kafka Q&A aloud + refund P0 story |
| 3 | 40m | Java pools/CHM/volatile + Hikari alignment |
| 4 | 40m | OMS + flash sale design + trade-off table |
| 5 | 30m | Debug scenarios S1–S7 cold |
| 6 | 20m | Tenure pushback (CTO_Round_Prep §1b) |

---

## Closing lines if time-boxed

> “Happy to go deeper on MMT search/pricing mesh, or on the Paytm Kafka/pool incident — both show how I debug and own distributed money systems.”

> “I use **I** carefully: led GST Goku/Inventory tracks; coded HCP; owned Ancillary/DO delivery; owned refund P0 RCA.”

---

*Attack sheet — Rampal Rao final round — Snapdeal JD overlap — practice aloud, don’t memorize essays*
