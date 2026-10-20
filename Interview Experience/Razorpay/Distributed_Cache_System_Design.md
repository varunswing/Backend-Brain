# Razorpay Senior SDE Interview Experience — System Design: Distributed Cache

## Round Overview
- **Role:** Senior Software Development Engineer (Backend / Distributed Systems)
- **Topic:** Distributed In-Memory Cache (High Throughput, Low Latency, Replication, Eviction, Failure Recovery)
- **Primary Guide Location:** [Razorpay_Distributed_Cache_Senior_SDE_Interview_Guide.md](file:///Users/mmt12368/Documents/GitHub/Interview/SystemDesign/HLD/System-Examples/Razorpay_Distributed_Cache_Senior_SDE_Interview_Guide.md)

---

## Key Highlights & Framework

### 1. Requirements & Workload Assumptions
- **Workload:** 1,000,000 Reads/sec, 100,000 Writes/sec (10:1 Read-Heavy).
- **Latency:** p95 < 2ms, p99 < 5ms over local datacenter network.
- **Active Data Size:** 500 GB raw working set -> 750 GB RAM with overhead -> 1.5 TB with 1 Primary + 1 Replica.
- **Cluster Dimensioning:** 16 Primary Shards (64 GB nodes) + 16 Replica Nodes = 32 Total Nodes.

### 2. Core Architecture
- **Routing:** Smart Client SDK with Consistent Hash Ring (256 Virtual Nodes / physical node).
- **Storage Tier:** In-Memory key-value store with $O(1)$ LRU eviction (Doubly-Linked List + Hash Map).
- **Coordination Tier:** Consensus-based cluster manager (etcd / ZooKeeper / Redis Sentinel) for heartbeat monitoring, quorum-based failover, and topology broadcasts.
- **Replication:** Asynchronous replication for high write throughput; semi-synchronous quorum for idempotency/ledger keys.

### 3. Critical Edge Cases to Discuss
1. **Cache Stampede (Thundering Herd):** Solved via Single-Flight Request Coalescing (`CompletableFuture` / Mutex), TTL Jitter ($\pm 15\%$), and XFetch probabilistic early refresh.
2. **Hot Key Saturation:** Solved via L1 in-process Caffeine cache (3–5s TTL), key salting (`key#1..k`), and dedicated read replicas.
3. **Database Consistency:** Database is source-of-truth; cache invalidation on write (not in-place update); fallbacks handled via CDC (Debezium) / Outbox table.
4. **Cluster Failure Fallback:** Circuit breakers (Resilience4j), strict DB connection pool limits, load shedding, and pre-warmed cache restoration.

---

> For the complete 45-minute interview script, full Java implementations (Consistent Hashing, Cache-Aside Manager, Thread-Safe LRU), and exhaustive answers to all 12 follow-up questions, refer to:  
> 👉 **[Razorpay Distributed Cache Senior SDE Guide](file:///Users/mmt12368/Documents/GitHub/Interview/SystemDesign/HLD/System-Examples/Razorpay_Distributed_Cache_Senior_SDE_Interview_Guide.md)**
