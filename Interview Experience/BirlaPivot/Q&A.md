# BirlaPivot Interview — Questions & Answers

Detailed answers for system design / backend discussion round (inventory, payments, DB replication, scaling, messaging).

---

## Q1. When you block inventory and payment fails, how do you unblock inventory? How is this managed end-to-end?

**Short answer:** Yes — if payment fails (or times out), blocked inventory **must** be released. This is handled via **explicit rollback**, **TTL-based auto-expiry**, and **reconciliation jobs**.

### Typical flow (Booking / Order service as orchestrator)

```
1. User selects item
2. Booking Service → Inventory Service: BLOCK / HOLD (soft reserve)
3. Booking Service → Payment Service: CHARGE
4a. Payment SUCCESS → Inventory Service: CONFIRM (convert hold → sold)
4b. Payment FAILURE → Inventory Service: RELEASE (unblock)
```

### How inventory block is implemented

| Layer | What happens | Why |
|-------|--------------|-----|
| **DB** | `available_qty -= 1`, `blocked_qty += 1` (or row status = `HELD`) | Strong consistency at source of truth |
| **Redis** | `SET hold:{sku}:{bookingId} 1 NX EX 600` (10 min TTL) | Fast lock + auto-expiry if app crashes |
| **Booking table** | `status = PENDING_PAYMENT`, `expires_at = now() + 10min` | Business state for reconciliation |

### Payment failure paths

1. **Synchronous failure** (card declined, insufficient balance)
   - Payment API returns `FAILED` immediately
   - Booking service calls `inventory.release(bookingId)` in same request thread
   - Booking status → `PAYMENT_FAILED`

2. **Timeout** (payment gateway slow / no response)
   - Booking stays `PENDING_PAYMENT` until TTL expires
   - Background job OR Redis TTL releases inventory
   - If payment webhook arrives later → handled by reconciliation (see Q3)

3. **App crash** after block but before payment
   - Redis TTL expires → inventory auto-released
   - Cron scans `PENDING_PAYMENT` where `expires_at < now()` → release + mark `EXPIRED`

### Important design rules

- **Hold is not a sale.** Until payment succeeds, inventory is only *reserved*, not consumed.
- **Release must be idempotent.** Calling `release()` twice should be safe (no double-add to available).
- **Use Saga / compensating transaction** for multi-service flows:
  - Forward: `BlockInventory → ChargePayment → ConfirmInventory`
  - Compensate on failure: `ReleaseInventory` (+ refund if money was captured)

```java
// Simplified orchestration (Booking Service)
public BookingResult checkout(CheckoutRequest req) {
    String bookingId = UUID.randomUUID().toString();

    try {
        inventoryService.block(req.getSkuId(), req.getQty(), bookingId, Duration.ofMinutes(10));
        PaymentResult payment = paymentService.charge(req, bookingId); // idempotency key = bookingId

        if (payment.isSuccess()) {
            inventoryService.confirm(bookingId);
            bookingRepo.save(bookingId, CONFIRMED);
            return BookingResult.success(bookingId);
        } else {
            inventoryService.release(bookingId);  // ← UNBLOCK on payment failure
            bookingRepo.save(bookingId, PAYMENT_FAILED);
            return BookingResult.failed("Payment failed");
        }
    } catch (Exception e) {
        inventoryService.release(bookingId);      // ← always compensate
        throw e;
    }
}
```

---

## Q2. Flow: Inventory → Payment → Inventory confirm. Who manages TTL?

**Your understanding is correct.**

```
Client
  → Booking Service
      → Inventory Service: block(hold TTL = 10 min)
      → Payment Service: charge(idempotencyKey = bookingId)
      → (on success) Inventory Service: confirm(bookingId)
      → (on failure) Inventory Service: release(bookingId)
```

### Who manages TTL?

Usually **multiple layers** work together:

| Component | Role |
|-----------|------|
| **Inventory Service** | Sets hold expiry in DB (`held_until`) and/or Redis (`EX 600`) |
| **Booking Service** | Stores `booking_expires_at`; drives user-facing countdown |
| **Scheduler / Cron job** | `ReleaseExpiredHoldsJob` runs every 1–5 min — safety net if Redis/HTTP call missed |
| **Payment Service** | Payment link/session may have its own expiry (e.g. Razorpay 15 min) |

TTL is **not** only in one place. Redis TTL is for **fast auto-release**; DB `expires_at` is for **audit + reconciliation**; cron is for **guaranteed cleanup**.

### State machine (Booking)

```
CREATED → INVENTORY_HELD → PAYMENT_PENDING → CONFIRMED
                │                  │
                └──── EXPIRED ←────┘ (TTL / cron)
                │
                └── PAYMENT_FAILED → inventory released
```

---

## Q3. Payment succeeds via webhook **after** hold TTL expired — inventory already released. What now?

This is a **classic distributed systems race**. Payment gateway says SUCCESS; your system already released stock.

### What goes wrong

```
T0: Block inventory (10 min TTL)
T1: User pays slowly / UPI pending
T10: TTL expires → inventory released → another user can book same unit
T12: Payment webhook: SUCCESS
```

You cannot "just confirm" — stock may already be sold to someone else.

### How to handle it

**1. Re-check inventory on webhook (optimistic confirm)**

```
onPaymentSuccessWebhook(bookingId):
  booking = getBooking(bookingId)

  if booking.status == CONFIRMED:
      return  // idempotent — already done

  if booking.status == EXPIRED:
      if inventoryService.tryConfirm(bookingId):   // stock still available?
          booking.confirm()
      else:
          booking.markPaymentReceivedButOversold()
          refundService.initiateRefund(booking.paymentId, "INVENTORY_UNAVAILABLE")
      return

  inventoryService.confirm(bookingId)
  booking.confirm()
```

**2. Extend TTL when payment is initiated**

When user lands on payment page / payment `INITIATED` event received:
- Extend hold by another 5–10 minutes
- Prevents most races for slow UPI

**3. Payment-before-hold (alternative design)**

For scarce inventory (flash sales):
- Take payment authorization first (hold money, don't capture)
- Then block inventory
- Then capture payment
- Trade-off: worse UX if inventory gone after pay

**4. Oversell buffer + waitlist**

If confirm fails after payment: auto-refund + notify user + optional waitlist.

**5. Reconciliation job**

Nightly job matches: `payments SUCCESS` ↔ `bookings NOT CONFIRMED` → refund or manual ops queue.

### Key principle

> **Payment success ≠ booking success.** Booking is confirmed only when **payment success AND inventory confirm** both succeed. Otherwise → **refund**.

---

## Q4. How is the refund flow triggered?

Refunds are triggered by **explicit business events**, not only by user action.

### Refund trigger scenarios

| Trigger | Example |
|---------|---------|
| Payment failed after capture | Rare — capture only on success; if partial capture bug → refund |
| Booking failed after payment | Inventory confirm failed (Q3) |
| User cancellation | Within cancellation policy window |
| Order rejected by seller | B2B: seller cannot fulfill |
| Duplicate payment | Same idempotency key processed twice — refund duplicate |
| Reconciliation mismatch | Payment SUCCESS but booking EXPIRED/CANCELLED |
| Ops / support manual | Chargeback, dispute |

### Refund flow (async, reliable)

```
1. RefundService.createRefundRequest(paymentId, amount, reason)
   → DB: refunds table, status = INITIATED

2. Publish event: RefundInitiated → Kafka

3. Refund worker calls Payment Gateway API (Razorpay/Stripe refund)
   → status = PROCESSING

4. Webhook from gateway: refund.processed
   → status = SUCCESS
   → notify user, update booking/payment ledger

5. On failure: retry with exponential backoff (3–5 attempts)
   → still failing → DLQ + alert ops
```

### Idempotency

- Each refund has `refund_id` + `idempotency_key` (e.g. `refund:{bookingId}:{reason}`)
- Gateway + internal DB check prevents double refund

### Saga compensation

In a Saga, **refund is the compensating action** for `ChargePayment` when a later step fails:

```
BlockInventory → ChargePayment → ConfirmInventory
                      ↓ fail confirm
                 RefundPayment (compensate)
                 ReleaseInventory (if not already released)
```

---

## Q5. What database do you use? Which service owns inventory state?

### Typical split (microservices)

| Service | Owns DB | Core tables |
|---------|---------|-------------|
| **Inventory Service** | MySQL / PostgreSQL (or MongoDB for flexible SKU attrs) | `inventory`, `inventory_holds`, `sku`, `warehouse` |
| **Booking / Order Service** | PostgreSQL | `bookings`, `order_items`, `booking_status_history` |
| **Payment Service** | PostgreSQL | `payments`, `refunds`, `payment_attempts` |
| **User Service** | PostgreSQL | `users`, `addresses` |

**Inventory table is owned by Inventory Service** — not the booking service. Booking service only stores `bookingId`, `skuId`, `qty`, `status` — not the live stock count.

### Example: `inventory` table (Inventory Service)

```sql
CREATE TABLE inventory (
    sku_id          VARCHAR(64) PRIMARY KEY,
    warehouse_id    VARCHAR(64) NOT NULL,
    total_qty       INT NOT NULL,
    available_qty   INT NOT NULL,      -- can be sold right now
    blocked_qty     INT NOT NULL DEFAULT 0,  -- held for pending orders
    version         INT NOT NULL DEFAULT 0,  -- optimistic locking
    updated_at      TIMESTAMP NOT NULL
);

CREATE TABLE inventory_holds (
    hold_id         UUID PRIMARY KEY,
    sku_id          VARCHAR(64) NOT NULL,
    booking_id      UUID NOT NULL UNIQUE,
    qty             INT NOT NULL,
    status          VARCHAR(20) NOT NULL,  -- ACTIVE, CONFIRMED, RELEASED, EXPIRED
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL
);
```

### Why separate `inventory_holds`?

- Audit trail: who held what, when, why released
- Reconciliation: match holds to bookings
- Don't lose history when `blocked_qty` goes back to 0

### Redis (not source of truth)

- Cache for `available_qty` (read-heavy product pages)
- Distributed locks / fast holds
- **Always reconcile with DB** — DB wins on conflict

---

## Q6. MySQL on third-party cloud — is it on multiple nodes? How much TPS can it handle?

### Deployment model (AWS RDS / Aurora / Cloud SQL)

Even when you "use MySQL from a third party," it is **not one invisible box**:

```
                    ┌─────────────┐
   App ──writes──►  │   PRIMARY   │  (1 writer node)
                    │   (Master)  │
                    └──────┬──────┘
                           │ async/sync replication
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         ┌─────────┐ ┌─────────┐ ┌─────────┐
         │ Replica │ │ Replica │ │ Replica │  (read scaling)
         └─────────┘ └─────────┘ └─────────┘
```

- **Primary**: handles all writes (and optionally reads)
- **Replicas**: read-only copies, scale read traffic
- Managed services also handle failover, backups, patching

### Rough TPS capacity (rule of thumb — varies hugely)

| Setup | Write TPS | Read TPS |
|-------|-----------|----------|
| Single small RDS instance (2 vCPU, SSD) | ~500–2K | ~2K–5K (same node) |
| Medium instance + query tuning + indexes | ~2K–5K | ~10K–20K |
| Primary + 5 read replicas | ~2K–8K writes to primary | ~50K–100K+ reads (distributed) |
| Sharded cluster (multiple primaries) | Scales writes horizontally | Scales reads per shard |

**Factors that change numbers:** query complexity, row size, indexes, network latency, connection pool size, `SELECT *` vs indexed lookups, hot rows (inventory for one SKU).

> Exact TPS is measured with load tests (JMeter, k6, Gatling) — never assume from instance size alone.

---

## Q7. What is Read TPS vs Write TPS? (e.g. at a company like MakeMyTrip)

### Definitions

| Term | Meaning |
|------|---------|
| **Write TPS** | Transactions that **modify** data per second (`INSERT`, `UPDATE`, `DELETE`) |
| **Read TPS** | Transactions that **only read** data per second (`SELECT`) |

### Typical ratio in travel / e-commerce

```
Read TPS : Write TPS ≈ 10:1 to 100:1
```

**Why reads dominate:**
- Search hotels / flights / products
- Browse listings, filters, autocomplete
- Check availability (read)
- View booking history, profile

**Writes are bursty:**
- Create booking (1 write)
- Block inventory (1–2 writes)
- Confirm payment (few writes)
- Peak: flash sale, holiday sale

### Example (illustrative, not official MMT numbers)

| Operation | Type | Relative volume |
|-----------|------|-----------------|
| Hotel search | Read | Very high |
| View hotel details | Read | High |
| Check room availability | Read | High |
| Create booking | Write | Medium |
| Payment confirmation | Write | Medium |
| Cancel / refund | Write | Low |

At scale, you might see **tens of thousands of read TPS** vs **hundreds to low thousands of write TPS** on booking/inventory paths — but **inventory hot rows** (one popular hotel) can bottleneck writes on a single row.

### How teams report it

- **Grafana / Datadog**: `db.read.qps`, `db.write.qps` per service
- **Aurora/RDS metrics**: `ReadThroughput`, `WriteThroughput`, `DatabaseConnections`
- Separate dashboards for search (read-heavy) vs booking (write-heavy)

---

## Q8. Backend and DB both have limits — single node vs distributed?

### Single node mental model

```
1000 requests/sec → ONE app server → ONE database
```

- All reads and writes hit the same machine
- Limit = min(app capacity, DB capacity, network)
- Single point of failure

### Distributed model

```
                    Load Balancer
                         │
        ┌────────────────┼────────────────┐
        ▼                ▼                ▼
    App Pod 1        App Pod 2        App Pod N     ← stateless, scale horizontally
        │                │                │
        └────────────────┼────────────────┘
                         │
              ┌──────────┴──────────┐
              ▼                     ▼
         DB Primary            Redis Cluster
              │
         ┌────┴────┐
    Replica  Replica ...                         ← read scale-out
```

| Layer | Scale strategy |
|-------|----------------|
| **App servers** | Horizontal — add pods behind LB (stateless) |
| **DB writes** | Vertical scale first → then sharding |
| **DB reads** | Read replicas |
| **Cache** | Redis cluster — offload hot reads |
| **Queue** | Kafka partitions — async decouple |

**Key insight:** App tier scales easily (stateless). **Database is usually the bottleneck** — especially write TPS and hot rows.

---

## Q9. Master + 5 replicas — replication lag. User reads stale inventory. How is this managed?

### The problem (simplified with money example)

```
Master:  user_1 balance = 100
Action:  deduct 10 on master → balance = 90
Replica: still replicating... balance = 100 (stale for ~10ms–500ms+)

If payment check reads replica → thinks user still has 100 → WRONG
```

Same for inventory:
```
Master: available_qty = 0 (blocked)
Replica: available_qty = 1 (stale) → another user sees "available" → double booking risk
```

### Solutions

**1. Read-your-writes / sticky to primary (critical paths)**

For **booking, payment, inventory block** → always read from **primary**, not replica.

```java
@Transactional
public void blockInventory(String skuId) {
    // Always use primary connection for this code path
    inventoryRepo.decrementAvailable(skuId);  // routed to master
}
```

**2. Replication lag monitoring**

- Alert if replica lag > 1 second
- Route traffic away from lagging replica

**3. Cache coherence**

After write to master → **update Redis** with new availability → reads can use Redis (with short TTL) instead of replica.

```
Write path: Master DB → invalidate/update Redis cache
Read path (browse): Redis or replica OK (eventual consistency acceptable)
Read path (checkout): Primary DB or Redis (must be fresh)
```

**4. Synchronous replication (trade-off)**

`semi-sync` / Aurora shared storage — smaller lag window, higher write latency.

**5. Version / timestamp check**

```sql
SELECT available_qty, version FROM inventory WHERE sku_id = ?;
-- On confirm: UPDATE ... WHERE version = ? (optimistic lock)
```

**6. Product decision: eventual consistency for browse only**

- Search / listing page: replica OK if 1–2 sec stale
- **Checkout / pay / block: strong consistency on primary**

| Use case | Read from |
|----------|-----------|
| Product listing | Replica / cache |
| Availability on PDP | Cache (updated on write) |
| Block inventory | **Primary only** |
| Payment | **Primary only** |

---

## Q10. Load grows from 100 TPS → 50,000 TPS. How do you scale?

### Step-by-step scaling path

```
Phase 1: 100 TPS
  → Single app + single DB + basic indexes

Phase 2: 1,000 TPS
  → Add Redis cache
  → Connection pooling (HikariCP)
  → Read replicas for browse APIs
  → Scale app pods (K8s HPA)

Phase 3: 10,000 TPS
  → Kafka for async (notifications, analytics)
  → CDN for static assets
  → Separate search (Elasticsearch)
  → Rate limiting / API gateway
  → DB query optimization, remove N+1

Phase 4: 50,000 TPS
  → Database sharding (inventory by sku/warehouse, orders by user_id)
  → Cache hot inventory in Redis
  → Queue-based order processing (async checkout tail)
  → Multi-region if global
  → Dedicated inventory service cluster
```

### Inventory-specific at high TPS

- **Hot SKU problem**: 10,000 people buy same item → one row contention
  - **Shard inventory by SKU** across DB partitions
  - **Pre-allocate stock** into Redis counters (`DECR` is atomic)
  - **Queue checkout requests** per SKU (serialize safely)

### Auto-scaling signals

- CPU > 70% → add pods
- DB connections exhausted → pool tuning + read replicas
- Kafka consumer lag → add consumers
- p99 latency > SLA → scale + cache

---

## Q11. Sharding: 1 lakh user records — how do you divide across shards?

### Naive approach: `user_id % N`

```
shard_id = hash(user_id) % N

N = 4 shards:
  user_id 1001 → shard 1
  user_id 1002 → shard 2
  ...
```

Each shard is a separate DB (or separate schema) with **only its subset** of users.

### What goes on each shard

```
Shard 0: users where hash(user_id) % 4 == 0
         + their orders, bookings (co-locate by user_id)
```

**Co-location rule:** Data accessed together should live on the same shard (user + their orders).

### Shard key selection

| Shard key | Good for | Bad for |
|-----------|----------|---------|
| `user_id` | My bookings, my profile | Admin: all orders today |
| `order_id` | Order lookup | User history across shards |
| `sku_id` | Inventory per product | User-centric queries |
| `warehouse_id` | B2B inventory | Global inventory view |

---

## Q12. `user_id % N` — when N changes, must you reshuffle all data? Better approach?

### Problem with modulo sharding

```
N = 4 → N = 5

user_id 1001: 1001 % 4 = 1  →  shard 1
user_id 1001: 1001 % 5 = 1  →  shard 1  (lucky, same)

user_id 1002: 1002 % 4 = 2  →  shard 2
user_id 1002: 1002 % 5 = 2  →  shard 2

user_id 1003: 1003 % 4 = 3  →  shard 3
user_id 1003: 1003 % 5 = 3  →  shard 3

user_id 1004: 1004 % 4 = 0  →  shard 0
user_id 1004: 1004 % 5 = 4  →  shard 4  ← MOVED! (~80% of keys move when N changes)
```

When N changes, **most keys remap** → massive data migration.

### Better approaches

**1. Consistent hashing (hash ring)**

```
hash(user_id) → position on ring → nearest shard node clockwise
```

- Add node → only **K/N** keys move (K = total keys, N = nodes)
- Used in: Cassandra, DynamoDB, Redis Cluster, many caches

**2. Fixed virtual buckets (e.g. 1024 buckets → map to shards)**

```
bucket = hash(user_id) % 1024
bucket 0-255   → shard A
bucket 256-511 → shard B
...

To add shard C: move some buckets from A/B to C — not full reshuffle
```

**3. Directory-based sharding (lookup table)**

```
shard_map: user_id → shard_id  (stored in ZooKeeper / etcd)
```

- Full control; move one user at a time
- Lookup overhead on every request

**4. Range-based sharding**

```
user_id A–M → shard 1
user_id N–Z → shard 2
```

- Simple; risk of hot spots (all new users in one range)

### Interview answer

> "Modulo N is simple but resharding is painful. In production we prefer **consistent hashing** or **virtual buckets** so adding a shard only moves a fraction of data. Migration is done gradually with dual-read/dual-write during transition."

---

## Q13. Service A writes to DB — how does Service B know? Communication mechanisms?

| Mechanism | Type | When to use |
|-----------|------|-------------|
| **REST / gRPC API** | Sync | Need immediate response, simple request-response |
| **Message Queue (Kafka, SQS, RabbitMQ)** | Async | Fire-and-forget, burst handling, decoupling |
| **Event bus / Pub-Sub** | Async | Multiple subscribers for same event |
| **Database CDC (Debezium)** | Async | B reacts to DB changes without A knowing |
| **Webhooks** | Async | External systems notify you |
| **Shared DB (anti-pattern)** | Sync | Avoid in microservices — tight coupling |
| **gRPC streaming** | Semi-async | Real-time stream of events |

### Pick guide

```
Need instant answer?           → Sync API
Can tolerate delay?            → Queue / events
Many subscribers?              → Pub-Sub / Kafka topic
Don't want to change Service A? → CDC from DB
```

---

## Q14. Queue flow: DB write → publish event → consumer. Is this correct?

**Yes — that's the standard transactional outbox pattern variant:**

```
1. Client request → Service A
2. Service A: BEGIN TRANSACTION
       INSERT into business_table
       INSERT into outbox_events (same TX)
   COMMIT
3. Outbox poller / relay publishes to Kafka
4. Service B consumes event → does its work
```

### Simpler (but risky) flow

```
1. Write to DB
2. Publish to Kafka        ← if crash here, event lost!
3. Return response
```

The risky version is what the interviewer probes in Q17.

---

## Q15. Async flow — what can go wrong?

```
Happy path:
  Request → DB write → Queue publish → Consumer processes → Done

Failure paths:
  F1: DB write fails           → return error to client (OK)
  F2: DB OK, queue fail        → DB updated, no event (BAD — B never knows)
  F3: Queue OK, consumer fail  → retry / DLQ (manageable)
  F4: Consumer OK, DB fail on B → partial state (need idempotency)
  F5: Duplicate messages       → consumer must be idempotent
```

### Reliability patterns

| Pattern | Solves |
|---------|--------|
| **Transactional Outbox** | F2 — DB + event atomic |
| **Idempotent consumer** | F4, F5 |
| **DLQ (Dead Letter Queue)** | Poison messages after N retries |
| **At-least-once delivery** | Message redelivery on failure |
| **Saga** | Multi-step distributed transaction |

---

## Q16. Pod crashes after DB write but before queue publish — event never sent. How do you fix this?

**This is the exact problem.** You **cannot** guarantee both without a pattern.

### Solution 1: Transactional Outbox (recommended)

```sql
-- Same database transaction
BEGIN;
  INSERT INTO orders (id, status) VALUES ('o1', 'CREATED');
  INSERT INTO outbox (id, aggregate_id, event_type, payload, status)
    VALUES ('e1', 'o1', 'OrderCreated', '{"orderId":"o1"}', 'PENDING');
COMMIT;

-- Separate relay process (always running)
SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY created_at LIMIT 100;
-- publish each to Kafka
-- UPDATE outbox SET status = 'PUBLISHED'
```

If pod crashes after COMMIT → outbox row exists → relay will publish later.

### Solution 2: Change Data Capture (CDC)

```
PostgreSQL WAL → Debezium → Kafka
```

Service B gets changes even if Service A never called Kafka. A doesn't need to publish at all.

### Solution 3: Polling / reconciliation cron

```
Every 5 min: find orders where status=CREATED and no downstream event
→ republish manually
```

Safety net, not primary design.

### What NOT to do

```
write DB();
try {
  kafka.publish();  // if crash here → lost event
} catch ...
```

---

## Q17. Have you worked with queues? Why do we need them? What problems do they solve?

**Yes — Kafka** (and SQS in AWS contexts).

### Problems queues solve

| Problem | How queue helps |
|---------|-----------------|
| **Decoupling** | Producer doesn't need to know consumers |
| **Burst traffic** | Absorb spikes; consumer processes at its pace |
| **Slow consumer** | Buffer messages instead of timeout / failure |
| **Multiple subscribers** | One event → inventory, analytics, email |
| **Reliability** | Persist messages; retry on failure |
| **Availability** | If B is down, messages wait in queue |

### Without queue

```
Service A ──HTTP──► Service B
```

- B down → A fails or must implement retry itself
- B slow → A slow (cascading latency)
- Add Service C → change A's code to call C too

### With queue

```
Service A → Kafka topic → Service B
                      → Service C
                      → Service D
```

A publishes once;任意 number of consumers.

---

## Q18. "Async is also programming — why not just call Service B's API directly?"

### Sync API is fine when:

- You **need the response** to return to user (e.g. "deduct inventory and tell me yes/no now")
- Consumer is **fast and always available**
- **Low coupling** acceptable
- Volume is **low and predictable**

### Queue is better when:

- User **doesn't need to wait** (send email, update analytics, sync to warehouse)
- Consumer is **slower** than producer
- You want **resilience** (consumer temporarily down)
- **Peak spikes** (10x traffic for 5 minutes)
- **Multiple independent actions** from one event

### Cost trade-off

```
Queue = extra infra (Kafka cluster, ops, monitoring)
```

You pay that cost for **resilience + scalability + decoupling**. Not every call needs a queue.

**Rule of thumb:**

| Path | Pattern |
|------|---------|
| Checkout / payment / block inventory | **Sync** (user waiting) |
| Send notification / index search / analytics | **Async** (queue) |

---

## Q19. Producer 2 msg/sec, consumer 1 msg/sec — is a queue a good idea?

**Yes — this is one of the best use cases for a queue.**

```
Without queue:
  Producer pushes 2/sec → Consumer handles 1/sec
  → 1 msg/sec backs up on HTTP threads
  → timeouts, memory pressure, cascading failures

With queue:
  Producer publishes 2/sec → Kafka buffers
  Consumer processes 1/sec
  → lag grows temporarily but system stays stable
  → scale consumers to 2+ to catch up
```

### What to watch

| Metric | Action |
|--------|--------|
| **Consumer lag** increasing forever | Add more consumer instances / partitions |
| **Lag temporary** (nightly spike) | Queue is doing its job |
| **Max retention** exceeded | Messages dropped — increase retention or scale |

### When queue is NOT enough

If sustained produce rate > consume rate forever and you never scale consumers → lag grows until disk full. Queue **buys time**, doesn't replace capacity.

**Fix:** scale consumers horizontally (Kafka: more partitions + more consumers in group).

---

## Quick revision cheat sheet

```
Inventory block fail path     → release() + TTL + cron
Payment after TTL             → try confirm → else refund
Refund trigger                → confirm failed / cancel / reconciliation
DB ownership                  → Inventory Svc owns stock; Booking owns booking state
Read replica stale            → critical path reads from primary or Redis
Scale 50K TPS                 → cache + replicas + shard + queue
Shard N change                → consistent hashing > modulo N
DB then crash before queue    → transactional outbox or CDC
Why queue                     → decouple, buffer, retry, multi-subscriber
2 prod / 1 cons               → queue is correct; scale consumers
```

---

## Related topics to revise

- Saga pattern & compensating transactions
- Idempotency keys (payment + booking)
- Redis `SET NX EX` for distributed locks
- Kafka: partitions, consumer groups, offset commit, DLQ
- CAP: inventory/booking = CP, search/browse = AP
