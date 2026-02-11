# MakeMyTrip System Design - 45-Minute Interview Guide

## Interview Timeline (45 minutes)

| Time | Phase | Focus |
|------|-------|-------|
| 0-5 min | Requirements Gathering | Clarify functional & non-functional requirements |
| 5-10 min | Capacity Estimation | Back-of-envelope calculations |
| 10-20 min | High-Level Design | Components, architecture diagram |
| 20-30 min | Deep Dive | Search implementation + 1 more component |
| 30-40 min | Trade-offs & Failure Scenarios | Discuss choices and edge cases |
| 40-45 min | Future Improvements | Scaling and enhancements |

---

## Quick Reference Card

### Key Numbers (Memorize These)

```
Daily Active Users (DAU):      10 Million
Searches per second (peak):    2,900 QPS
Bookings per second (peak):    145 BPS
Search latency target:         < 200ms
System availability:           99.99%
Cache hit rate target:         > 80%
Total hotels:                  500,000
Flights per day:               50,000
```

### Critical Components (The Big 5)

1. **Search Service** → Elasticsearch + Redis Caching
2. **Booking Service** → Distributed locks + ACID transactions
3. **Payment Service** → Idempotency + Multiple gateways
4. **Inventory Service** → Real-time availability
5. **Price Engine** → Dynamic pricing with ML

---

## Phase 1: Requirements (5 minutes)

### Functional Requirements - Quick List

**Search:**
- Flight/Hotel/Train search with filters
- Auto-suggestions
- Price sorting
- Real-time availability

**Booking:**
- Multi-step booking flow
- Payment processing
- Seat/room selection
- Modifications & cancellations

**User:**
- Registration & login
- Profile management
- Booking history
- Wishlist

### Non-Functional Requirements - Key Points

```
Performance:  < 200ms search, < 1s booking
Scale:        10M DAU, 2900 QPS
Availability: 99.99% uptime
Consistency:  Strong for bookings, eventual for search
Security:     PCI-DSS, encryption, fraud detection
```

---

## Phase 2: Capacity Estimation (5 minutes)

### Quick Calculation Template

```
Search Traffic:
- 10M users × 5 searches/day = 50M searches/day
- 50M / 86400 = ~580 QPS average
- Peak: 580 × 5 = 2,900 QPS

Booking Traffic:
- 5% conversion = 2.5M bookings/day
- 2.5M / 86400 = ~29 BPS average
- Peak: 29 × 5 = 145 BPS

Storage:
- User data: 100M × 2KB = 200 GB
- Inventory: 500K hotels × 50KB = 25 GB
- Bookings (10 years): 2.5M × 10KB × 365 × 10 = 912 TB
- Images: 500K × 20 × 500KB = 5 PB (use CDN)
- Search index: ~150 GB (with replication)

Bandwidth:
- Incoming: 2,900 × 2KB = 5.8 MB/s
- Outgoing: 2,900 × 50KB = 145 MB/s
```

---

## Phase 3: High-Level Design (10 minutes)

### Architecture Sketch (Draw This)

```
┌─────────────────────────────────────────────────────┐
│                    CLIENTS                           │
│         Web App | Mobile App | Partners              │
└─────────────────────┬───────────────────────────────┘
                      │
┌─────────────────────▼───────────────────────────────┐
│                API GATEWAY                           │
│         (Auth, Rate Limit, Routing)                  │
└─────────────────────┬───────────────────────────────┘
                      │
        ┌─────────────┼─────────────┐
        │             │             │
┌───────▼──────┐ ┌───▼────┐ ┌──────▼───────┐
│Auth Service  │ │Search  │ │Booking       │
│              │ │Service │ │Service       │
└──────────────┘ └───┬────┘ └──────┬───────┘
                     │             │
        ┌────────────┼─────────────┼──────────┐
        │            │             │          │
┌───────▼───┐ ┌─────▼──────┐ ┌────▼─────┐ ┌─▼────────┐
│Elasticsearch│ │Redis Cache│ │Payment   │ │Inventory │
└───────────┘ └───────────┘ │Service   │ │Service   │
                             └──────────┘ └──────────┘
                                    │          │
                        ┌───────────┴──────────┴──────┐
                        │                              │
                ┌───────▼───────┐          ┌───────────▼──┐
                │PostgreSQL     │          │MongoDB       │
                │(Bookings)     │          │(Inventory)   │
                └───────────────┘          └──────────────┘
```

### Key Talking Points

1. **API Gateway**: Entry point for auth, rate limiting, routing
2. **Microservices**: Search, Booking, Payment, Inventory, Notification
3. **Databases**: 
   - PostgreSQL (ACID for bookings)
   - MongoDB (flexible inventory)
   - Elasticsearch (fast search)
4. **Cache**: Redis for search results, session data
5. **Message Queue**: Kafka for async processing
6. **CDN**: CloudFront for images & static content

---

## Phase 4: Deep Dive - Fast Search (10 minutes)

### Why Search is Fast - 5 Key Optimizations

#### 1. Multi-Level Caching
```
L1: In-Memory (1GB)     → 10ms   → Popular queries
L2: Redis (100GB)       → 50ms   → Recent searches  
L3: Elasticsearch       → 150ms  → All data
```

#### 2. Elasticsearch Optimizations
```
- Sharding: 20 shards (25K hotels each)
- Inverted index for text search
- Geo-spatial index for location
- Filter context (no scoring) for filters
- Limited _source fields
- track_total_hits: false
```

#### 3. Query Strategy
```json
{
  "query": {
    "bool": {
      "must": [{"match": {"city": "Mumbai"}}],
      "filter": [
        {"range": {"price": {"gte": 1000, "lte": 5000}}},
        {"term": {"available": true}}
      ]
    }
  },
  "size": 20,
  "_source": ["id", "name", "price", "rating"]
}
```

#### 4. Async Data Enrichment
```
1. Return basic results immediately (50ms)
2. Enrich in background:
   - Real-time pricing
   - Current availability
   - Personalized offers
3. Progressive rendering on frontend
```

#### 5. Data Sync (CDC Pipeline)
```
PostgreSQL → Debezium (CDC) → Kafka → Elasticsearch
- Parse Write-Ahead-Log (WAL)
- Batch indexing (1000 docs)
- Refresh interval: 5 seconds
```

### Search Flow - 1 Minute Explanation

> "User searches for Mumbai hotels. Request hits API Gateway, which validates the token. Search Service generates a cache key and checks Redis. On cache miss, we query Elasticsearch which uses inverted indexes to find matching hotels. We parallelly enrich results with real-time pricing and availability from the Inventory Service. Results are cached for 5 minutes and returned in under 200ms."

---

## Phase 4: Deep Dive - Booking Service (Optional 2nd Component)

### Preventing Double Booking - 4 Layers

#### 1. Distributed Lock (Redis)
```python
lock_key = f"booking:hotel:{hotel_id}:room:{room_id}"
lock = redis.set(lock_key, uuid, nx=True, ex=30)
# TTL prevents deadlock
```

#### 2. Database Transaction (ACID)
```sql
BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE;
SELECT * FROM rooms WHERE id = ? FOR UPDATE;  -- Row lock
UPDATE rooms SET available_count = available_count - 1;
INSERT INTO bookings (...);
COMMIT;
```

#### 3. Optimistic Locking
```sql
UPDATE bookings 
SET status = 'CONFIRMED', version = version + 1
WHERE booking_id = ? AND version = ?;
-- Fails if version changed
```

#### 4. Idempotency Key
```python
idempotency_key = request.headers['Idempotency-Key']
existing = redis.get(f"booking:{idempotency_key}")
if existing:
    return existing  # Prevent duplicate booking
```

### Booking Flow - 1 Minute Explanation

> "User selects a room and submits booking. We acquire a distributed lock on that room with 30-second TTL. Inside a database transaction, we check availability and decrement count atomically. We then call the Payment Service with an idempotency key. On payment success, we confirm the booking and publish an event to Kafka for notifications. On failure, we rollback the transaction and release the lock. The idempotency key prevents duplicate charges if the user clicks twice."

---

## Phase 5: Trade-offs & Tech Choices (10 minutes)

### Key Trade-off Discussions

#### 1. Consistency vs Availability (CAP Theorem)

| Component | Choice | Reasoning |
|-----------|--------|-----------|
| Bookings | **Consistency** (CP) | Cannot have double booking |
| Search | **Availability** (AP) | OK if results slightly stale |
| Inventory | **Consistency** (CP) | Accurate availability critical |

#### 2. SQL vs NoSQL

```
PostgreSQL (Bookings):
✓ ACID transactions
✓ Strong consistency
✓ Rich queries
✗ Harder to scale horizontally

MongoDB (Inventory):
✓ Flexible schema
✓ Fast reads
✓ Easy sharding
✗ No multi-document ACID
```

#### 3. Synchronous vs Asynchronous

```
Synchronous (REST/gRPC):
Use: Search, Booking, Payment
✓ Immediate response
✗ Tight coupling

Asynchronous (Kafka):
Use: Notifications, Analytics, Logs
✓ Loose coupling
✓ Better fault tolerance
✗ Eventual consistency
```

#### 4. Sharding Strategy

```
User-based sharding for Bookings:
✓ User's bookings co-located
✓ Fast "my bookings" query
✗ Cross-shard queries expensive

Solution: Global secondary index for hotel-based queries
```

---

## Phase 6: Failure Scenarios (5 minutes)

### Critical Scenarios - Quick Answers

#### 1. Search Overwhelmed (10x traffic spike)
```
Symptoms: High latency, ES CPU at 100%
Solution:
- Enable aggressive rate limiting
- Increase cache TTL
- Auto-scale ES cluster
- Circuit breaker to prevent cascading failure
```

#### 2. Payment Gateway Down
```
Symptoms: All payments failing
Solution:
- Multiple gateway integration (Razorpay, Stripe, Paytm)
- Automatic failover
- Queue payments for retry
- Saga pattern for rollback
```

#### 3. Database Hotspot (Popular hotel)
```
Symptoms: Lock contention, slow queries
Solution:
- Read replicas for read traffic
- Request coalescing (multiple requests → 1 DB call)
- Better sharding (hash-based)
- Pessimistic locking with timeout
```

#### 4. Cache Stampede (Cache expiry during peak)
```
Symptoms: Sudden DB spike, DB overload
Solution:
- Probabilistic early expiration
- Request coalescing
- Staggered TTL (add jitter)
- Cache warming
```

#### 5. Distributed Lock Deadlock
```
Symptoms: Inventory locked permanently
Solution:
- Lock TTL (30 seconds auto-expire)
- Lock monitoring
- Manual release API
- Health checks
```

---

## Phase 7: Future Improvements (5 minutes)

### High-Impact Improvements

#### 1. Advanced Personalization
```
- ML-based recommendations (collaborative filtering)
- Real-time personalization using user behavior
- A/B testing framework
- Dynamic homepage per user
```

#### 2. Predictive Analytics
```
- Price prediction: "Price likely to increase 20% tomorrow"
- Best time to book recommendations
- Demand forecasting for proactive scaling
- Fraud detection using anomaly detection
```

#### 3. GraphQL API
```
- Client-specific data fetching
- Reduced over-fetching
- Better mobile performance
- Field-level caching
```

#### 4. Multi-Region Deployment
```
- Active-active across regions
- Data replication (CRDT for conflicts)
- Geo-routing for low latency
- Disaster recovery
```

#### 5. Edge Computing
```
- Cloudflare Workers for edge compute
- Personalization at edge
- Reduced origin load
- Lower latency globally
```

---

## Common Interview Questions & Answers

### Q1: How do you ensure no double booking?

**Answer:** Four-layer protection:
1. Distributed lock (Redis) - prevents concurrent attempts
2. Database transaction with row-level lock (FOR UPDATE)
3. Optimistic locking with version field
4. Idempotency key - prevents duplicate requests

### Q2: How do you handle 10x traffic spike?

**Answer:** 
1. Auto-scaling (Kubernetes HPA based on CPU/memory)
2. Aggressive caching (increase TTL temporarily)
3. Rate limiting per user
4. Circuit breakers to prevent cascading failures
5. Graceful degradation (disable non-critical features)

### Q3: How do you keep search index in sync?

**Answer:** 
1. Change Data Capture (CDC) using Debezium
2. Parse PostgreSQL Write-Ahead-Log (WAL)
3. Publish changes to Kafka
4. Consumer batches and indexes to Elasticsearch
5. Eventual consistency acceptable for search

### Q4: How do you scale the database?

**Answer:**
1. Read replicas (5 replicas for read-heavy traffic)
2. Horizontal sharding (50 shards based on user_id)
3. Caching layer (Redis) to reduce DB load
4. Connection pooling
5. Query optimization (indexes, explain analyze)

### Q5: What if payment succeeds but booking update fails?

**Answer:** 
1. Use Saga pattern for distributed transactions
2. Payment success → publish event to Kafka
3. Booking Service consumes event and updates DB
4. If update fails → compensating transaction (refund)
5. Idempotency ensures no duplicate charges
6. Dead letter queue for failed messages

### Q6: How do you prevent fraudulent bookings?

**Answer:**
1. ML-based fraud detection (anomaly detection)
2. Device fingerprinting
3. Behavioral biometrics (typing patterns)
4. Risk scoring (low/medium/high)
5. 3D Secure for card payments
6. Manual review for high-risk bookings

### Q7: How do you handle peak load (holiday season)?

**Answer:**
1. Pre-warm cache with popular searches
2. Increase infrastructure capacity proactively
3. Load testing before peak season
4. Feature flags to disable non-critical features
5. Request throttling per user
6. CDN for static content

---

## Key Metrics to Monitor

### Golden Signals

```
Latency:   P50, P95, P99 latency per endpoint
Traffic:   Requests per second
Errors:    Error rate (5xx, 4xx)
Saturation: CPU, Memory, Disk, Network utilization
```

### Business Metrics

```
Searches per minute
Bookings per minute
Conversion rate (bookings / searches)
Revenue per minute
Average booking value
Cache hit rate
Database query time
```

### SLOs (Service Level Objectives)

```
Search latency:        P99 < 200ms
Booking latency:       P99 < 1 second
Availability:          99.99% uptime
Error rate:            < 0.1%
Payment success rate:  > 99.5%
```

---

## Drawing Tips for Whiteboard

### 1. Start with Simple Boxes
```
[User] → [API Gateway] → [Services] → [Databases]
```

### 2. Add Details Progressively
- First: Components
- Second: Connections
- Third: Data stores
- Fourth: Caching/queues

### 3. Use Arrows to Show Flow
- Solid arrow: Synchronous call
- Dashed arrow: Asynchronous message
- Bidirectional: Request-response

### 4. Label Everything
- Service names
- Data flows
- Protocol (REST/gRPC/Kafka)
- Data stores

### 5. Add Numbers for Sequence
```
1→ 2→ 3→  (Shows request flow)
```

---

## Interview Do's and Don'ts

### ✅ DO:

- **Ask clarifying questions** before jumping into design
- **State assumptions** explicitly (e.g., "Assuming 10M DAU...")
- **Think out loud** - interviewer wants to see your thought process
- **Start simple**, then add complexity
- **Draw diagrams** to visualize architecture
- **Discuss trade-offs** for every decision
- **Mention monitoring** and observability
- **Consider failure scenarios**
- **Scale numbers reasonably** (not 1 billion users)

### ❌ DON'T:

- Jump into coding immediately
- Over-engineer the initial solution
- Ignore the interviewer's hints
- Get stuck on one component for too long
- Forget about non-functional requirements
- Design without considering scale
- Ignore failure scenarios
- Use technologies without justifying why
- Claim expertise in tools you don't know

---

## Quick Reference - Technologies Stack

### Frontend
- React / React Native
- TypeScript
- Next.js (SSR)

### API Gateway
- Kong / AWS API Gateway
- Rate limiting: Token bucket algorithm

### Backend Services
- Go / Java / Node.js
- gRPC for inter-service communication
- REST for external APIs

### Databases
- PostgreSQL (Bookings, Users)
- MongoDB (Inventory, Reviews)
- Redis (Cache, Sessions, Locks)
- Elasticsearch (Search)
- ClickHouse (Analytics)

### Message Queue
- Apache Kafka
- Consumer groups for parallel processing

### Storage
- AWS S3 (Images)
- CloudFront CDN

### Infrastructure
- Kubernetes (EKS)
- Docker containers
- Terraform (IaC)

### Monitoring
- Prometheus + Grafana
- ELK Stack (Logs)
- Jaeger (Distributed Tracing)
- Sentry (Error tracking)

### CI/CD
- GitHub Actions / Jenkins
- ArgoCD (GitOps)

---

## One-Liner Explanations (For Quick Recap)

1. **Search**: "Multi-level caching + Elasticsearch with geo-spatial index + async enrichment = sub-200ms"

2. **Booking**: "Distributed lock + database transaction + idempotency = no double booking"

3. **Pricing**: "Base price × demand × inventory × seasonality × advance booking = dynamic price"

4. **Scaling**: "Microservices + horizontal sharding + read replicas + caching = handle 10M DAU"

5. **Consistency**: "Strong for bookings (CP), eventual for search (AP)"

6. **Fault Tolerance**: "Circuit breakers + retry logic + multiple gateways + graceful degradation"

---

## Time Management Tips

```
0-5 min:   Requirements (breadth, not depth)
           "What scale? What features? What SLAs?"

5-10 min:  Capacity (back-of-envelope, round numbers)
           "~3000 QPS, ~1TB data, ~150 MB/s bandwidth"

10-20 min: High-level (components + diagram)
           "API Gateway → Services → Data stores"

20-30 min: Deep dive (2 components max)
           "Search: Elasticsearch + caching"
           "Booking: Distributed transactions"

30-40 min: Trade-offs + Failures
           "Why PostgreSQL? What if DB is down?"

40-45 min: Future improvements
           "ML recommendations, multi-region, GraphQL"
```

---

## Final Checklist Before Interview

- [ ] Understand the problem thoroughly
- [ ] Memorize key numbers (DAU, QPS, etc.)
- [ ] Practice drawing architecture diagram
- [ ] Know trade-offs for each tech choice
- [ ] Prepare 3-4 failure scenarios
- [ ] Have 3-5 future improvements ready
- [ ] Practice explaining in simple terms
- [ ] Be ready to deep dive into 2-3 components

---

Good luck with your interview! 🚀

Remember: **It's not about getting everything perfect, it's about demonstrating your thought process and problem-solving ability.**
