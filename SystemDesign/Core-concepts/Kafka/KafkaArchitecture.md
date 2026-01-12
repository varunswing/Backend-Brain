## 🔷 **Kafka Architecture Overview**

Kafka is a **distributed, fault-tolerant, horizontally scalable** event streaming platform made up of the following components:

---

### 🔹 1. **Cluster**
- A Kafka cluster is a **group of Kafka brokers**.
- Each broker has a unique `broker.id`.
- Topics and partitions are spread across brokers for **load balancing**.

---

### 🔹 2. **Broker**
- A Kafka broker is a single Kafka server that:
  - Stores topic **partitions**
  - Handles **read/write requests** from producers/consumers
  - Coordinates **replication and leadership**

#### 🧠 Example:
- Broker 1: Partition 0 (leader), Partition 1 (follower)
- Broker 2: Partition 1 (leader), Partition 0 (follower)

---

### 🔹 3. **Topic and Partition**
- **Topic** = category of messages
- **Partition** = split of a topic for parallelism
- Messages within a partition are **strictly ordered**

#### Example:
- Topic: `orders`
- Partitions: 3
  - Partition 0 → Broker 1
  - Partition 1 → Broker 2
  - Partition 2 → Broker 3

---

### 🔹 4. **Replication & Fault Tolerance**

Kafka ensures durability via **replication**:

#### 🔸 Key Concepts:
- Each partition has:
  - **One leader** (handles reads/writes)
  - **One or more followers** (replicate the leader's data)
- Replicas live on **different brokers**
- Followers sync from leader using **replication protocol**

#### 🔸 ISR (In-Sync Replica) Set:
- Set of replicas that are **fully caught up** with the leader.
- Only replicas in ISR are eligible to become leader if current leader fails.

---

### 🔹 5. **Leader Election**
- Only the **leader replica** handles client requests (produce, consume).
- If leader broker goes down:
  - Kafka elects a new leader from ISR.
  - This ensures **high availability**.

#### Example:
- Partition 1 has:
  - Leader → Broker 1
  - Followers → Broker 2, Broker 3
- If Broker 1 fails → Broker 2 becomes leader.

---

### 🔹 6. **ZooKeeper (or KRaft Mode)**
#### 🟡 With ZooKeeper:
- Manages metadata (brokers, topic configs)
- Elects controller node (manages leader election)
- Required in Kafka versions < 2.8

#### 🟢 KRaft (Kafka Raft):
- Kafka handles everything natively (no Zookeeper)
- Simpler deployments, faster recovery

---

### 🔹 7. **Controller**
- A special broker in the cluster.
- Manages:
  - Partition leadership
  - Broker health
  - Reassignment during failures

---

### 🔹 8. **Log Storage**
- Messages are written to **disk** in the partition's commit log.
- Kafka uses:
  - **Segment files** (e.g., 1GB chunks)
  - **Indexing** for fast reads
  - **Retention policy**: time-based or size-based

---

### 🔹 9. **High Throughput Techniques**
- Sequential disk writes (avoid random I/O)
- Batching and compression
- Page cache (OS memory)
- Zero-copy using `sendfile()`

---

### 🔹 10. **Kafka Guarantees**
| Guarantee            | How Kafka achieves it                                      |
|----------------------|------------------------------------------------------------|
| **Durability**       | Replication to multiple brokers                            |
| **Scalability**      | Partitioning and horizontal scaling                        |
| **Fault Tolerance**  | Leader election, ISR, replication                          |
| **High Throughput**  | Batching, compression, sequential disk I/O                |
| **Ordering**         | Guaranteed within a partition                             |

---
