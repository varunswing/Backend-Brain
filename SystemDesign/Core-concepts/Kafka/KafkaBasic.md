## 🔷 **Kafka Basic Concepts**

### 🔹 **1. Producer**
- Sends records (key + value) to a **specific topic**.
- Example: A payment service sends a transaction event to topic `transactions`.
  
#### ✅ Important Configs:
- `acks` (acknowledgment):
  - `0`: Don't wait for any acknowledgment (fast, but risky).
  - `1`: Wait for leader only (default).
  - `all`: Wait for **all replicas** to acknowledge (safest).
- `retries`: How many times to retry on failure.
- `linger.ms`: Time to wait before batching messages.
- `batch.size`: Max size of a batch.

#### 💡 Example:
```java
producer.send(new ProducerRecord<>("orders", "order123", "placed"));
```

---

### 🔹 **2. Topic**
- A **category** where producers send messages and consumers read from.
- Think of it like a table in a DB, but append-only.
  
#### Example:
- Topic: `user-signups`
- Partitions: 3 (allows parallel reads/writes)

---

### 🔹 **3. Partition**
- **Each topic is split into partitions**.
- Enables **parallelism** (multiple consumers can read in parallel).
- Each partition has a **leader** (handles writes/reads) and **replicas** (backup copies).

#### Example:
- Topic `payments` with 3 partitions
  - Partition 0 → Consumer A
  - Partition 1 → Consumer B
  - Partition 2 → Consumer C

#### 📌 Key Rule:
- Same key → same partition → **ordering is preserved** per key

---

### 🔹 **4. Offset**
- Unique, ever-increasing number per partition.
- Helps **track which messages have been read**.

#### Example:
- Partition 0:
  - offset 0: msg1
  - offset 1: msg2
  - offset 2: msg3

- Consumers track last **committed offset**. If crash happens, they resume from that offset.

---

### 🔹 **5. Consumer**
- Reads messages from partitions.
- Must call `poll()` continuously; otherwise Kafka will **rebalance** and give the partition to another consumer.

#### Types of Offset Commit:
- **Auto commit**:
  - Kafka commits offset at interval (e.g., every 5 sec).
  - Risk: If processing fails after offset commit, message is **lost**.
- **Manual commit**:
  - Commit only after message is successfully processed.
  - Safer, used in **exactly-once** or **at-least-once** setups.

#### Example:
```java
consumer.commitSync(); // safe manual offset commit
```

---

### 🔹 **6. Consumer Group**
- A group of consumers sharing the load of one or more topics.
- Kafka ensures:
  - Each partition is read by **only one consumer** in a group.
  - Messages are **load-balanced**.

#### Example:
- Topic `logins` has 3 partitions.
- Consumer Group A has 2 consumers:
  - Consumer1 → Partition 0 and 1
  - Consumer2 → Partition 2

#### Rebalancing:
- If a consumer crashes or joins, partitions are reassigned.

---

### 🔹 **7. Broker**
- A Kafka server that:
  - Stores topic partitions.
  - Handles client requests (produce, consume).
- Each broker has a **broker ID**.
- Kafka scales by adding more brokers (i.e., nodes).

#### Example:
- Broker 1: Partition 0 (leader), Partition 1 (follower)
- Broker 2: Partition 1 (leader), Partition 0 (follower)

---

### 🔹 **8. Zookeeper (or KRaft)**
- Used in older Kafka for:
  - Tracking brokers
  - Electing **partition leaders**
  - Managing metadata
- **KRaft Mode (Kafka Raft)**:
  - Kafka itself handles coordination (no Zookeeper).
  - Simpler architecture (from Kafka 2.8+)

---

### 🔹 Bonus: Delivery Semantics

#### 🔸 At-most-once:
- Offset is committed **before** processing.
- Risk of **data loss**.

#### 🔸 At-least-once:
- Offset is committed **after** processing.
- Risk of **duplicate processing** (need idempotency).

#### 🔸 Exactly-once:
- Kafka 0.11+ supports it via **idempotent producer** + **transactional API**.
- Guarantees no duplicates **and** no data loss.

---
