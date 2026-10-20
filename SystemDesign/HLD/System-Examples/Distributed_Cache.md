# Distributed Cache System Design — Razorpay Senior SDE Interview Guide

> **Target Role:** Senior Software Development Engineer (Backend / Distributed Systems)  
> **Company Context:** Razorpay (Payments, Merchant Dashboard, High-Throughput Checkout, Ledger & Tokenization)  
> **Problem Statement:** *"Design a Distributed Cache that supports high throughput, low latency, replication, eviction, and failure recovery."*

---

## Table of Contents
1. [Interview Strategy & 45-Minute Execution Playbook](#1-interview-strategy--45-minute-execution-playbook)
2. [Step 1: Clarifying Requirements & SLA Definition](#2-step-1-clarifying-requirements--sla-definition)
3. [Step 2: Capacity Estimation & Hardware Sizing](#3-step-2-capacity-estimation--hardware-sizing)
4. [Step 3: High-Level Architecture](#4-step-3-high-level-architecture)
5. [Step 4: Data Partitioning & Consistent Hashing](#5-step-4-data-partitioning--consistent-hashing)
6. [Step 5: Cache Read & Write Flows (with Java Code)](#6-step-5-cache-read--write-flows-with-java-code)
7. [Step 6: Replication, High Availability & Failover Protocol](#7-step-6-replication-high-availability--failover-protocol)
8. [Step 7: Eviction Policies & Memory Management Internals](#8-step-7-eviction-policies--memory-management-internals)
9. [Step 8: Scaling to Millions of QPS: Hot Keys & Stampede Mitigation](#9-step-8-scaling-to-millions-of-qps-hot-keys--stampede-mitigation)
10. [Step 9: Observability, SRE Metrics & Production Triage](#10-step-9-observability-sre-metrics--production-triage)
11. [Step 10: Deep-Dive Answers to All 12 Follow-Up Questions](#11-step-10-deep-dive-answers-to-all-12-follow-up-questions)
12. [Step 11: Candidate Script & Verbal Pitch](#12-step-11-candidate-script--verbal-pitch)

---

## 1. Interview Strategy & 45-Minute Execution Playbook

In a Senior SDE round at Razorpay, interviewers evaluate:
1. **Breadth vs. Depth Balance:** Can you establish a solid end-to-end architecture before diving into low-level protocols?
2. **Production Reality:** Do you understand that networks fail, nodes reboot, and distributed cache is **not** a database replacement?
3. **Trade-off Rigor:** Can you articulate *why* consistent hashing beats modulo hashing, or *why* asynchronous replication causes stale reads?
4. **Razorpay Domain Sensitivities:** Financial transactions demand strict idempotency, low p99 latency during flash sales (e.g., Big Billion Days/merchant sales), and resilient fallback paths when dependencies fail.

### 45-Minute Timeline
```
┌───────────────────────────────────────────────────────────────────────────┐
│                       45-MINUTE INTERVIEW TIMELINE                        │
├──────────────────┬────────────────────────────────────────────────────────┤
│ 00:00 - 05:00    │ Clarify Requirements, Assumptions & Scope               │
│ 05:00 - 10:00    │ Capacity Estimation (QPS, Memory, Bandwidth, Nodes)    │
│ 10:00 - 20:00    │ High-Level Architecture & Core APIs                    │
│ 20:00 - 30:00    │ Deep Dive: Sharding, Consistent Hashing & Replication  │
│ 30:00 - 40:00    │ Resiliency: Eviction, Failover, Hot Keys & Stampede    │
│ 40:00 - 45:00    │ Observability, Trade-off Summary & Follow-ups          │
└──────────────────┴────────────────────────────────────────────────────────┘
```

---

## 2. Step 1: Clarifying Requirements & SLA Definition

When the interviewer asks:  
> *"Design a Distributed Cache that supports high throughput, low latency, replication, eviction, and failure recovery."*

Do **not** jump directly into Redis or Memcached commands. Open with structured requirement gathering:

### Candidate Opening Hook:
> *"Before jumping into architectural diagrams, I'd like to clarify our workload characteristics, SLA targets, and consistency guarantees. Specifically: read/write distribution, dataset size, durability expectations, and fault tolerance thresholds."*

### Clarification Matrix

| Parameter | Question to Interviewer | Why It Matters | Standard Assumption (if self-assumed) |
|---|---|---|---|
| **Throughput (QPS)** | What is peak read and write operations per second? | Dictates connection pooling, sharding count, and network I/O model. | **1,000,000 Reads/sec**, **100,000 Writes/sec** (10:1 ratio) |
| **Latency SLA** | What is the target latency at p95 and p99? | Network hops, serialisation formats, and single-thread vs multi-thread event loops. | **p95 < 2ms**, **p99 < 5ms** over local DC network |
| **Dataset Size** | How much active data needs to be cached? | In-memory RAM sizing, node cluster dimensions, and cost. | **500 GB active working set** (e.g., 500M keys @ 1 KB avg size) |
| **Durability & Loss** | Is data loss acceptable during node reboot? | Decides whether in-memory only or disk snapshotting (AOF/RDB) is needed. | **Cache is ephemeral/semi-durable**; DB is source-of-truth. |
| **Consistency** | Strong consistency or eventual consistency? | Determines replication model: Synchronous (2PC/Quorum) vs Asynchronous. | **Eventual consistency** with short bounded staleness (<100ms) |
| **Eviction Policy** | What happens when memory is exhausted? | Eviction data structures (LRU, LFU, FIFO) vs throwing errors. | **LRU with configurable TTL** per key |
| **Availability SLA** | What is our uptime target during partition/outage? | High availability clustering, failover automation, multi-AZ deployment. | **99.99% availability** (max 52 min downtime/year) |

---

## 3. Step 2: Capacity Estimation & Hardware Sizing

Always walk the interviewer through the math. This demonstrates Senior engineering rigor.

### A. Assumptions
- **Read Throughput:** $1,000,000 \text{ QPS}$
- **Write Throughput:** $100,000 \text{ QPS}$
- **Total Keys:** $500,000,000$ (500 Million keys)
- **Average Key Size:** $64 \text{ bytes}$
- **Average Value Size:** $960 \text{ bytes}$
- **Average Entry Size:** $64 + 960 = 1,024 \text{ bytes} = 1 \text{ KB}$

### B. Memory Calculation
$$\text{Raw Data Size} = 500,000,000 \times 1 \text{ KB} = 500 \text{ GB}$$

In production in-memory systems (like Redis/Memcached), memory bookkeeping, hash table pointers, jemalloc fragmentation, and dict metadata add ~50% overhead:
$$\text{Memory with Overhead} = 500 \text{ GB} \times 1.5 = 750 \text{ GB}$$

With a replication factor of $2$ (1 Primary + 1 Replica per shard):
$$\text{Total Cluster RAM} = 750 \text{ GB} \times 2 = 1,500 \text{ GB} = 1.5 \text{ TB}$$

### C. Shard Dimensioning
- Target safe RAM per cache node: **64 GB RAM** (e.g., AWS `r6g.2xlarge` or GCP `n2-highmem-8`).
- Usable memory per node for cache data (leaving 25% for OS, replication buffers, and copy-on-write during background tasks):
  $$\text{Usable RAM per node} = 64 \text{ GB} \times 0.75 = 48 \text{ GB}$$
- Total Primary Nodes required:
  $$\text{Primary Nodes} = \frac{750 \text{ GB}}{48 \text{ GB}} \approx 16 \text{ Primary Shards}$$
- With 1 Replica per Primary:
  $$\text{Total Cache Nodes} = 16 \times 2 = 32 \text{ Nodes}$$

### D. Network Bandwidth Calculation
- **Read Bandwidth:** $1,000,000 \text{ QPS} \times 1 \text{ KB} = 1 \text{ GB/sec} = 8 \text{ Gbps}$
- **Write Bandwidth:** $100,000 \text{ QPS} \times 1 \text{ KB} = 100 \text{ MB/sec} = 800 \text{ Mbps}$
- **Replication Bandwidth:** $100 \text{ MB/sec} \times 1 \text{ replica} = 800 \text{ Mbps}$
- **Total Cluster Network Traffic:** $\approx 9.6 \text{ Gbps}$
- Divided across 16 primary nodes:
  $$\text{Per-node Bandwidth} = \frac{9.6 \text{ Gbps}}{16} \approx 600 \text{ Mbps}$$
*Well within 10 Gbps / 25 Gbps network interface cards (NICs).*

---

## 4. Step 3: High-Level Architecture

```
                                  +-----------------------+
                                  |  Application Services |
                                  | (Checkout, Auth, etc) |
                                  +-----------------------+
                                              |
                     +------------------------+------------------------+
                     |                                                 |
                     v                                                 v
        +-------------------------+                       +-------------------------+
        |   Smart Cache Client    |                       |    Envoy / Sidecar      |
        |   (Fat Client SDK)      |  --- (Alternative) -- |    Transparent Proxy    |
        +-------------------------+                       +-------------------------+
                     |                                                 |
         [Consistent Hash Ring]                             [Consistent Hash Ring]
                     |                                                 |
        +------------+------------------------+------------------------+
        |                                     |
        v                                     v
+------------------+                  +------------------+
|  Shard 0: Node A |                  |  Shard 1: Node B |
|  [PRIMARY]       |                  |  [PRIMARY]       |
|  - In-Memory Map |                  |  - In-Memory Map |
|  - LRU LinkedList|                  |  - LRU LinkedList|
+------------------+                  +------------------+
        |  Async Replication                  |  Async Replication
        v                                     v
+------------------+                  +------------------+
|  Shard 0: Node A'|                  |  Shard 1: Node B'|
|  [REPLICA]       |                  |  [REPLICA]       |
+------------------+                  +------------------+
        ^                                     ^
        |                                     |
        +------------------+------------------+
                           |
                 +-------------------+
                 | Cluster Manager / |
                 | Consensus Service |
                 | (etcd / ZooKeeper/|
                 | Redis Sentinel)   |
                 +-------------------+
```

### Core Components Breakdown

1. **Application Service:** The caller requiring rapid sub-millisecond key-value retrieval (e.g., Merchant configuration, User session, API Idempotency keys).
2. **Routing Tier:**
   - **Option A (Smart Client - Recommended):** Embedded Java/Go library containing the Consistent Hash Ring, connection pool, failure detector, and topology listener. **Zero additional network hops.**
   - **Option B (Proxy Layer - Envoy / Twemproxy):** Centralized load-balancing proxy. Decouples client applications from cache topology changes at the expense of an extra network hop (+0.5ms latency).
3. **Primary Cache Shard:** Holds partition ownership for a subset of the hash ring. Executes in-memory `GET`, `SET`, `DEL`, handles eviction, and streams replication logs.
4. **Replica Cache Shard:** Replicates the primary's mutation log. Ready for failover promotion. Can optionally serve read-only queries if read consistency allows eventual consistency.
5. **Cluster Coordination Service (etcd / ZooKeeper / Raft Engine):**
   - Tracks node heartbeats.
   - Maintains authoritative cluster state (which node owns which hash slot).
   - Coordinates automated leader election when a primary fails.

---

## 5. Step 4: Data Partitioning & Consistent Hashing

### Why Simple Modulo Hashing Fails

In simple modulo hashing:
$$\text{Node Index} = \text{hash}(\text{key}) \pmod N$$

If we start with $N = 3$ nodes and scale to $N = 4$ nodes:
- Almost **75% of existing keys remap** to different nodes.
- When $N$ changes to $N+1$, the fraction of keys that stay on the same node is only $\frac{1}{N+1}$.
- Result: **Instant Cache Wipeout**, mass cache misses, and database stampede.

### Consistent Hashing & Virtual Nodes

Consistent Hashing maps both **nodes** and **keys** to a 32-bit (or 64-bit) integer space represented as a circular ring ($0 \text{ to } 2^{32}-1$).

```
                        0 / 2^32
                           |
                     Node A_v1 (1000)
                   /                 \
        Key 3 (3800)                  Key 1 (1500)
         /                                     \
    Node C_v1 (3200)                      Node B_v1 (2200)
        \                                     /
         \                                   /
       Node B_v2 (2900) - - - - - - - Key 2 (2500)
```

1. **Routing Rule:** A key is hashed to a position on the ring. The client walks **clockwise** until it hits the first node position. That node owns the key.
2. **Node Addition/Removal:** When a node is added or removed, only $\frac{K}{N}$ keys are remapped on average ($K$ = total keys, $N$ = number of nodes). The remaining keys continue hitting their existing nodes.
3. **Virtual Nodes (VNodes):**
   - Physical nodes have heterogeneous capacities or random clustering on the ring, leading to severe key distribution skew.
   - Each physical node is hashed as multiple virtual instances (e.g., `NodeA#1`, `NodeA#2`, ..., `NodeA#256`).
   - **Standard Production Rule:** **128 to 256 virtual nodes per physical machine** guarantees less than 5% variance in key distribution across nodes.

### Java Implementation: Consistent Hash Ring

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public class ConsistentHashRouter<T> {
    private final int numberOfReplicas; // Virtual nodes per physical node
    private final SortedMap<Long, T> circle = new TreeMap<>();

    public ConsistentHashRouter(int numberOfReplicas, Collection<T> nodes) {
        this.numberOfReplicas = numberOfReplicas;
        for (T node : nodes) {
            addNode(node);
        }
    }

    public synchronized void addNode(T node) {
        for (int i = 0; i < numberOfReplicas; i++) {
            long hash = hash(node.toString() + "#VN_" + i);
            circle.put(hash, node);
        }
    }

    public synchronized void removeNode(T node) {
        for (int i = 0; i < numberOfReplicas; i++) {
            long hash = hash(node.toString() + "#VN_" + i);
            circle.remove(hash);
        }
    }

    public T route(String key) {
        if (circle.isEmpty()) {
            return null;
        }
        long hash = hash(key);
        if (!circle.containsKey(hash)) {
            // Find the next node clockwise
            SortedMap<Long, T> tailMap = circle.tailMap(hash);
            hash = tailMap.isEmpty() ? circle.firstKey() : tailMap.firstKey();
        }
        return circle.get(hash);
    }

    // 64-bit MurmurHash3 or FNV-1a is preferred in production over MD5 for lower CPU overhead
    private long hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(key.getBytes(StandardCharsets.UTF_8));
            return ((long) (digest[3] & 0xFF) << 24)
                 | ((long) (digest[2] & 0xFF) << 16)
                 | ((long) (digest[1] & 0xFF) << 8)
                 | ((long) (digest[0] & 0xFF));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }
}
```

---

## 6. Step 5: Cache Read & Write Flows (with Java Code)

### A. Read Flow: Cache-Aside (Lazy Loading)

```
[Client Request]
       |
       v
Check Cache Key
       |
   +---+---+
   |       |
[Hit]    [Miss]
   |       |
   |       v
   |    Acquire Single-Flight Lock / Request Coalescing
   |       |
   |       v
   |    Read from Primary Database
   |       |
   |       v
   |    Populate Cache with TTL + Jitter
   |       |
   +-------+
       |
       v
Return Response
```

### B. Write Flow: Invalidate After Database Commit

```
[Write Request]
       |
       v
Update / Commit Database
       |
       +--- Success? ---> Invalidate Cache Key (DELETE)
       |                        |
       |                   Failed? ---> Push to Retry Queue / Debezium CDC
       |
       +--- Failure? ---> Abort transaction, do not touch cache
```

### C. Production-Grade Java Implementation

```java
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class CacheAsideManager<K, V> {
    private final CacheClient<K, V> cacheClient;
    private final DatabaseRepository<K, V> databaseRepository;
    
    // Concurrent map for single-flight request coalescing to prevent Cache Stampede
    private final ConcurrentMap<K, CompletableFuture<V>> inFlightLoads = new ConcurrentHashMap<>();

    public CacheAsideManager(CacheClient<K, V> cacheClient, DatabaseRepository<K, V> databaseRepository) {
        this.cacheClient = cacheClient;
        this.databaseRepository = databaseRepository;
    }

    public V get(K key) {
        // 1. Check L2 Distributed Cache
        V cachedValue = cacheClient.get(key);
        if (cachedValue != null) {
            return cachedValue;
        }

        // 2. Cache Miss: Single-flight execution (Mutually coalesced DB read)
        CompletableFuture<V> future = inFlightLoads.computeIfAbsent(key, k -> {
            CompletableFuture<V> loadFuture = new CompletableFuture<>();
            CompletableFuture.runAsync(() -> {
                try {
                    V dbValue = databaseRepository.findById(k);
                    if (dbValue != null) {
                        // Apply TTL with randomized jitter (e.g. 10m +/- 10%) to prevent synchronized expiry
                        long jitterSeconds = ThreadLocalRandom.current().nextLong(540, 660);
                        cacheClient.set(k, dbValue, Duration.ofSeconds(jitterSeconds));
                    }
                    loadFuture.complete(dbValue);
                } catch (Exception ex) {
                    loadFuture.completeExceptionally(ex);
                } finally {
                    inFlightLoads.remove(k);
                }
            });
            return loadFuture;
        });

        try {
            return future.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            // Fallback: direct DB read or circuit-break
            return databaseRepository.findById(key);
        }
    }

    public void update(K key, V updatedValue) {
        // Step 1: Write to Database (Source of Truth)
        databaseRepository.save(key, updatedValue);

        // Step 2: Invalidate Cache (Evict, do NOT update in-place to avoid race conditions)
        try {
            cacheClient.delete(key);
        } catch (Exception ex) {
            // If delete fails, publish event to an Outbox / Reliable Kafka Topic
            // so CDC / asynchronous worker retries cache invalidation
            cacheClient.enqueueInvalidationRetry(key);
        }
    }
}
```

---

## 7. Step 6: Replication, High Availability & Failover Protocol

### Synchronous vs. Asynchronous Replication

```
Synchronous Replication:
Client ──[Write]──> Primary ──[Replicate]──> Replica
Client <──[ACK]───── Primary <────[ACK]────── Replica
(Trade-off: Strong consistency, higher write latency, replica failure blocks writes)

Asynchronous Replication:
Client ──[Write]──> Primary ──[Async Log]──> Replica
Client <──[ACK]───── Primary
(Trade-off: Sub-millisecond write ACK, risk of stale reads or write loss on crash)
```

**Razorpay Recommendation:**
Use **Asynchronous Replication with Semi-Synchronous Option for Critical Paths**.
- For regular read caches (e.g., product catalog, merchant profile): Asynchronous replication.
- For financial deduplication/idempotency keys: Write to Primary and require ACK from at least $\lfloor N/2 \rfloor + 1$ quorum nodes before responding.

### Automated Failure Recovery Protocol

When Primary Node A crashes:

```
[Primary Node A Fails]
          |
          v
[1. Failure Detection]
- Sentinels / Coordination nodes detect missed heartbeats (>3 missed pings over 3000ms)
- Quorum agreement reached: Node A marked as Subjectively Down (sdown) -> Objectively Down (odown)
          |
          v
[2. Leader Election]
- Sentinels elect a leader among themselves via Raft/Paxos
          |
          v
[3. Select Best Replica]
- Candidate replica with smallest replication offset (farthest along in replication stream) selected
          |
          v
[4. Failover & Promotion]
- Replica Node A' promoted to Primary via CLUSTER FAILOVER / SLAVEOF NO ONE
          |
          v
[5. Topology Broadcast]
- Cluster state version (Epoch) incremented
- Smart Clients receive push notification or gossip update with new routing table
          |
          v
[6. Old Primary Reboots]
- Old Node A rejoins as a REPLICA of the new Primary Node A'
```

---

## 8. Step 7: Eviction Policies & Memory Management Internals

### Expiration (TTL) vs. Eviction

- **TTL Expiration:** Logical expiration based on time. Does not immediately free memory.
  - *Passive/Lazy Expiry:* Key is deleted when a read request touches it and detects `currentTime > expiryTime`.
  - *Active Expiry:* Periodic background thread samples 20 keys every 100ms; if more than 25% are expired, it repeats immediately to reclaim RAM.
- **Eviction:** Triggered when memory hits `maxmemory` threshold. System must aggressively dump valid data according to an algorithm.

### Eviction Policies Comparison

| Policy | Data Structure | Time Complexity | Best Suited For | Trade-offs |
|---|---|---|---|---|
| **LRU (Least Recently Used)** | HashMap + Doubly Linked List | $O(1)$ get, $O(1)$ put | General workloads with temporal locality (recency matters) | Susceptible to polluter scans (one-off batch dump clears active cache) |
| **LFU (Least Frequently Used)** | Frequency Map + Doubly Linked Lists per count | $O(1)$ with frequency bucket lists | Long-term stable hot items (e.g., top 100 merchant configs) | Old hot keys accumulate counts and never get evicted (frequency starvation) |
| **FIFO (First In First Out)** | Queue / LinkedList | $O(1)$ | Sequential streams | Ignores access frequency and recency |
| **W-TinyLFU (Caffeine)** | Count-Min Sketch + Window LRU | $O(1)$ amortized | Modern high-performance caching (State of the art) | Complex internal structure |

### Java Implementation: Thread-Safe LRU Cache

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class ThreadSafeLRUCache<K, V> {
    private static class Node<K, V> {
        K key;
        V value;
        Node<K, V> prev;
        Node<K, V> next;

        Node(K key, V value) {
            this.key = key;
            this.value = value;
        }
    }

    private final int capacity;
    private final ConcurrentHashMap<K, Node<K, V>> map;
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private final ReentrantLock lock = new ReentrantLock();

    public ThreadSafeLRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new ConcurrentHashMap<>(capacity);
        
        // Sentinel head and tail nodes to eliminate null checks
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) {
            return null;
        }

        // Move accessed node to head of LRU list
        lock.lock();
        try {
            moveToHead(node);
        } finally {
            lock.unlock();
        }
        return node.value;
    }

    public void put(K key, V value) {
        lock.lock();
        try {
            Node<K, V> existing = map.get(key);
            if (existing != null) {
                existing.value = value;
                moveToHead(existing);
                return;
            }

            if (map.size() >= capacity) {
                // Evict tail (least recently used)
                Node<K, V> lru = tail.prev;
                removeNode(lru);
                map.remove(lru.key);
            }

            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addFirst(newNode);
        } finally {
            lock.unlock();
        }
    }

    private void moveToHead(Node<K, V> node) {
        removeNode(node);
        addFirst(node);
    }

    private void addFirst(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
    }

    private void removeNode(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }
}
```

---

## 9. Step 8: Scaling to Millions of QPS: Hot Keys & Stampede Mitigation

### A. The Hot-Key Problem

Imagine an Indian cricket match or flash sale: 1,000,000 customers hit the checkout page for merchant `SWIGGY` simultaneously.
- Even with 32 shards and consistent hashing, `key = "merchant:SWIGGY"` hashes to **one single shard**.
- That shard's CPU and 1 Gbps network card hit 100% saturation, while the remaining 31 shards sit idle.

#### Hot-Key Solutions:
1. **L1 In-Process Cache (Near Cache):**  
   Store super-hot keys in the JVM process memory (using Caffeine Cache) with a small TTL (e.g., 5 seconds). Absorbs 99% of requests before hitting the distributed cache network.
2. **Key Salting / Replication:**  
   Append random suffix `salt = random(1, K)` to write keys:  
   `merchant:SWIGGY#1`, `merchant:SWIGGY#2`, ..., `merchant:SWIGGY#10`.  
   Reads pick a random suffix between 1 and 10. Distributes traffic across 10 distinct physical shards.
3. **Read Replicas:**  
   Configure read-heavy hot keys to be served from read-replicas rather than the primary shard.

### B. The Cache Stampede (Thundering Herd)

Occurs when a popular key expires, and 50,000 concurrent threads simultaneously experience a cache miss and execute the same heavy SQL query.

#### Multi-Tier Defense:
1. **Request Coalescing (Single-Flight):** Only 1 thread goes to DB; other 49,999 wait on a shared future (see Section 5 code).
2. **TTL Jitter:** Never set static expiration (e.g., exactly 10 minutes). Add $\pm 15\%$ random noise:
   $$\text{TTL} = \text{BaseTTL} + \text{UniformRandom}(-\delta, +\delta)$$
3. **Probabilistic Early Recomputation (XFetch Algorithm):**  
   Recompute the key in the background *before* it expires based on access frequency and computation cost:
   $$\text{recompute if: } -\beta \times \delta \times \ln(\text{random}()) > \text{timeToExpiry}$$

---

## 10. Step 9: Observability, SRE Metrics & Production Triage

| Metric | Target SLA | Alert Threshold | Remediation Action |
|---|---|---|---|
| **Cache Hit Ratio** | $> 85\%$ | $< 70\%$ for 5 min | Check for stampede, unwanted key eviction, or rogue batch queries. |
| **p99 Read Latency** | $< 5\text{ms}$ | $> 15\text{ms}$ | Check for network saturation, GC pauses, or slow keys (large payloads). |
| **Eviction Rate** | $< 100 \text{ keys/sec}$ | Sudden $10\times$ spike | Cache is under-provisioned for active working set; scale out shards. |
| **Replication Lag** | $< 50\text{ms}$ | $> 500\text{ms}$ | Primary writing faster than replica network ingestion; investigate network saturation. |
| **Memory Fragmentation** | $1.0 < \text{Ratio} < 1.3$ | $> 1.5$ | jemalloc memory fragmentation; trigger background defragmentation. |
| **Hot Shard CPU** | $< 60\%$ avg | $> 85\%$ on one node | Hot-key alert; enable L1 cache or apply key salting. |

---

## 11. Step 10: Deep-Dive Answers to All 12 Follow-Up Questions

### Q1: What is Consistent Hashing, and why do we use Virtual Nodes?
**Answer:**  
Standard modulo hashing ($\text{hash}(\text{key}) \pmod N$) maps keys to specific node indices. When node count $N$ changes, almost all keys remap ($\approx \frac{N-1}{N}$ keys move), resulting in devastating cache misses.  

Consistent Hashing maps keys and nodes onto a circular $2^{32}-1$ integer ring. A key is assigned to the first node encountered clockwise. When a node is added or removed, only $\frac{1}{N}$ fraction of keys need remapping.  

**Virtual Nodes** solve data skew: With physical nodes alone, hash distribution across the ring is uneven, creating "hot spots". By hashing each physical node as 128–256 virtual nodes (e.g., `NodeA#1`, `NodeA#2`), we achieve an evenly distributed ring with less than 5% standard deviation in key distribution.

---

### Q2: What is the fundamental difference between Sharding and Replication?
**Answer:**  
- **Sharding (Partitioning)** divides the dataset horizontally across multiple nodes. Each shard holds a disjoint subset of data (e.g., Node 1 holds keys A–F, Node 2 holds keys G–M). It solves the **scalability and memory limit problem** (scaling writes and storage capacity).
- **Replication** creates redundant copies of the *same* data on multiple machines. It solves the **high availability, durability, and read throughput problem** (surviving hardware failure and scaling read operations).

*Mnemonic:* Sharding divides the pie; Replication copies the pie.

---

### Q3: What is a Cache Stampede, and how do you prevent it?
**Answer:**  
A cache stampede (thundering herd) occurs when a high-traffic key expires or is deleted. Thousands of concurrent requests simultaneously miss the cache and overwhelm the underlying database with redundant queries.  

**Prevention Strategies:**
1. **Single-Flight / Request Coalescing (Mutex Locking):** The first thread to miss acquires a distributed lock or local mutex to query the DB and update the cache. Other threads wait or poll.
2. **TTL Jitter:** Add randomized offsets ($\pm 10\text{--}20\%$) to key expiration times so keys in batch writes do not expire simultaneously.
3. **Probabilistic Early Refresh (XFetch):** Background workers recompute and refresh the cached entry *before* its actual TTL expires based on request probability.

---

### Q4: What is the difference between Cache-Aside and Write-Through?
**Answer:**  
- **Cache-Aside (Lazy Loading):** The application orchestrates both cache and database. On read: read cache; on miss: read DB and write back to cache. On write: write DB and invalidate cache.  
  *Pros:* Lazy loading avoids caching unused data; cache node crash does not break DB writes.  
  *Cons:* First read is always a miss; potential consistency gaps.
- **Write-Through:** The application interacts **only** with the cache layer. The cache is responsible for synchronously writing to the database before acknowledging success to the caller.  
  *Pros:* Data in cache is never stale; cleaner application code.  
  *Cons:* Higher write latency (waits for both cache and DB); writes cache items that might never be read again.

---

### Q5: What happens if the database update succeeds, but cache invalidation fails? How do you handle it?
**Answer:**  
This creates a **stale cache anomaly** where the cache holds outdated data until its TTL expires.  

**Production Solutions:**
1. **Short TTL Safety Net:** Every cache entry has an enforced bounded TTL (e.g., 5–15 minutes) as a fallback limit.
2. **Transactional Outbox + CDC (Debezium):** Instead of application deleting cache directly, DB write publishes a row to an `outbox_table` or WAL. A Change Data Capture (CDC) worker reads the DB transaction log and issues cache invalidations. Invalidation is guaranteed once DB commits.
3. **Reliable Retry Queue:** Catch the cache deletion exception in the application and enqueue the key into an asynchronous Kafka/RabbitMQ retry topic with exponential backoff.

---

### Q6: How does replica promotion work when a Primary Node crashes?
**Answer:**  
1. **Failure Detection:** Sentinels or cluster peers send periodic `PING` heartbeats. If a primary fails to respond within `down-after-milliseconds`, it is marked as `sdown` (subjectively down). When a majority of sentinels agree, it becomes `odown` (objectively down).
2. **Leader Election:** Sentinels run Raft-based consensus to elect an orchestrator.
3. **Replica Selection:** The orchestrator selects the replica with:
   - Lowest replication offset (least data loss).
   - Highest priority configuration.
4. **Promotion:** The orchestrator sends `SLAVEOF NO ONE` to the chosen replica, promoting it to primary.
5. **Reconfiguration:** Sentinels instruct other replicas to replicate from the new primary and broadcast the updated routing topology to smart clients via gossip/pub-sub.

---

### Q7: What are the trade-offs between Strong Consistency and Eventual Consistency in distributed caching?
**Answer:**  
By CAP theorem, in the presence of network partitions ($P$):
- **Strong Consistency ($CP$):** Every write must synchronously acknowledge on all replicas (or a strict quorum $W + R > N$).  
  *Trade-offs:* High write latency, lower availability (if a replica is slow or disconnected, writes block or fail). Not ideal for a cache designed for sub-millisecond speeds.
- **Eventual Consistency ($AP$):** Writes acknowledge immediately on primary and propagate asynchronously to replicas.  
  *Trade-offs:* Blazing fast sub-millisecond writes, high availability. Replicas may serve stale data for a few milliseconds, and un-replicated writes are lost if primary dies before replication. For a cache (where DB is source-of-truth), $AP$ with eventual consistency is almost always preferred.

---

### Q8: What is the algorithmic and structural difference between LRU and LFU?
**Answer:**  
- **LRU (Least Recently Used):**
  - *Data Structure:* Hash Table + Doubly Linked List.
  - *Eviction Logic:* Removes the item whose last access timestamp is oldest (the tail node).
  - *Flaw:* Vulnerable to a sudden batch scan (e.g., a one-time dump reads 100,000 keys, evicting all hot items even though they are accessed daily).
- **LFU (Least Frequently Used):**
  - *Data Structure:* Hash Table + Frequency Buckets (each bucket containing a Doubly Linked List of items with that frequency).
  - *Eviction Logic:* Removes the item with the lowest total access counter.
  - *Flaw:* High memory overhead for frequency counters; **frequency starvation** (an item accessed 10,000 times during a morning flash sale stays cached forever even after the sale ends unless counter decay is implemented).

---

### Q9: If a single key receives 1,000,000 requests per second, how do you handle it?
**Answer:**  
Sharding alone does not help because a single key always hashes to the same shard.  
**Multi-Layer Mitigation:**
1. **L1 In-Memory Near Cache:** Cache the key in application process memory (Caffeine/Guava) with a 3–5 second TTL. 100 app instances will absorb 990,000+ requests internally, reducing cache cluster queries to a few hundred per second.
2. **Key Replication with Random Salt:** Replicate the key across multiple shards with suffixes: `key#1`, `key#2`, ..., `key#20`. Writers update all 20 keys; readers pick a random suffix from $1 \text{ to } 20$. This fans the 1M QPS across 20 distinct physical nodes.
3. **Read Replicas:** Route read requests for the hot key to multiple dedicated read-only replicas of that shard.

---

### Q10: If the entire cache cluster crashes, how do you prevent the database from going down?
**Answer:**  
Without protection, 100% of read traffic immediately strikes the database, causing a complete system outage.  
**Defensive Layers:**
1. **Circuit Breakers (Resilience4j / Envoy):** If cache error rate exceeds 50%, the circuit breaker trips. Rather than forwarding misses to the database, requests are throttled or fail fast.
2. **Rate Limiting & Load Shedding:** Enforce strict concurrency limits on database connection pools. Reject lower-priority requests with HTTP 429 / 503.
3. **Stale Cache / Degraded Mode:** Serve stale data from local disk snapshots or return default degraded responses (e.g., empty recommendations or cached static configs).
4. **Controlled Pre-Warming:** When bringing the cache cluster back up, isolate it from public traffic and run a warm-up script that populates the top 20% hottest keys from database replicas before opening traffic.

---

### Q11: How do you estimate cache capacity and determine the number of nodes required?
**Answer:**  
Step-by-step formula:
1. $\text{Raw Storage} = \text{Total Keys} \times (\text{Key Size} + \text{Value Size})$.
2. $\text{RAM with Overhead} = \text{Raw Storage} \times 1.5$ (accounting for pointer overhead and hash table metadata).
3. $\text{Cluster RAM} = \text{RAM with Overhead} \times \text{Replication Factor}$.
4. $\text{Usable RAM per Node} = \text{Node Physical RAM} \times 0.75$ (reserve 25% for OS, replication backlog, and Redis fork copy-on-write).
5. $\text{Number of Primary Nodes} = \lceil \frac{\text{RAM with Overhead}}{\text{Usable RAM per Node}} \rceil$.
6. Check network bottleneck: Verify that $\frac{\text{Total Read Bandwidth}}{\text{Number of Nodes}} < \text{Node Network Limit}$.

---

### Q12: Why might p99 latency degrade significantly even when the cache hit ratio is 99%?
**Answer:**  
A high hit ratio does not guarantee fast responses. p99 latency reflects the worst 1% of transactions, caused by:
1. **Network Queueing & NIC Saturation:** Large value sizes on a hot shard saturate the network card, creating socket buffer queuing.
2. **Head-of-Line Blocking in Single-Threaded Engines:** In Redis, an expensive command (`KEYS *`, `HGETALL` on a 50,000-field hash, or big key eviction) blocks the single event loop, delaying thousands of lightweight `GET` queries queued behind it.
3. **JVM Garbage Collection Pauses:** In Java applications or Java-based cache proxies, Stop-The-World (STW) GC pauses freeze threads for 50–200ms.
4. **TCP Connection Churn:** Applications creating and tearing down TCP connections per request instead of reusing a persistent connection pool encounter TCP handshake and slow-start latency.
5. **Hot Shards:** 99% of requests hit the cache, but 50% hit **the exact same node**, causing CPU starvation on that specific machine.

---

## 12. Step 11: Candidate Script & Verbal Pitch

Use this structured script to present confidently in your Razorpay Senior SDE interview:

### Opening Pitch (Minutes 0–5)
> *"Hello! To design a distributed in-memory cache supporting high throughput, low latency, replication, eviction, and failure recovery, I'd like to structure our session into five distinct stages:*  
> *1. Requirements clarification and workload sizing,*  
> *2. Core architecture and data partitioning strategy,*  
> *3. Read/write workflows and data consistency,*  
> *4. Fault tolerance, automated failover, and eviction,*  
> *5. Hot-key mitigation and operational observability.*  
>  
> *For our baseline, I will assume a read-heavy workload of 1M reads/sec, 100K writes/sec, a 500 GB active dataset, p99 latency under 5 milliseconds, and high availability with 99.99% uptime. Does that align with what you'd like to focus on?"*

### Architecture Pitch (Minutes 5–15)
> *"At a high level, our architecture separates the cluster into three tiers:*  
> *First, a Smart Client embedded in the application services that maintains an in-memory Consistent Hash Ring with 256 virtual nodes per physical machine. This gives us $O(1)$ routing with zero extra network hops.*  
> *Second, a partitioned storage cluster divided into 16 primary shards, each paired with an asynchronous replica for failover redundancy.*  
> *Third, a consensus coordination tier—such as ZooKeeper, etcd, or Redis Sentinel—that monitors heartbeats, detects node crashes, and orchestrates leader election during partitions.*  
>  
> *For reads and writes, we adopt the Cache-Aside pattern with single-flight request coalescing to eliminate cache stampedes. On database writes, we invalidate the cache key rather than updating it in-place to avoid race conditions."*

### Resiliency & Failover Pitch (Minutes 15–30)
> *"When a primary node crashes, the coordination cluster detects missed heartbeats over a 3-second quorum window. It elects the replica with the latest replication offset, issues a promotion command, and broadcasts the new cluster epoch. Smart clients receive the topology update without dropping healthy requests to other shards.*  
>  
> *For memory management, each node enforces an LRU eviction policy using a doubly linked list and hash map. To handle hot keys like top merchants during flash sales, we introduce a two-tier defense: an in-process L1 Caffeine cache with a 5-second TTL, alongside key salting across multiple shards."*

### Closing Summary (Minutes 40–45)
> *"To ensure this runs smoothly in production at Razorpay, we monitor four golden signals: Cache Hit Ratio (target >85%), p99 latency (target <5ms), eviction rate, and replication lag. If the cache fails completely, circuit breakers prevent database collapse, allowing the payment gateway to fail gracefully.*  
> *I'm happy to dive deeper into any specific component, whether it's our lock-free LRU implementation, the Raft consensus protocol, or memory fragmentation tuning."*

---

## Summary Checklist for Razorpay Interview Preparation
- [x] Clear requirements & mathematical capacity estimation.
- [x] Consistent Hashing with Virtual Nodes explained and coded.
- [x] Cache-Aside with Single-Flight / Stampede protection implemented in Java.
- [x] Asynchronous vs. Synchronous replication trade-offs articulated.
- [x] Step-by-step automated failover lifecycle documented.
- [x] LRU Cache internal data structure implemented with $O(1)$ operations.
- [x] Hot key & Thundering herd mitigations covered.
- [x] All 12 high-frequency Senior SDE follow-up questions answered in detail.
