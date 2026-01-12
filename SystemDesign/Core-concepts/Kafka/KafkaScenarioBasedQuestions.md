Here are **senior-level Kafka interview questions** with **detailed answers**, covering **lag, low-level design (LLD) integration, Kafka internals, performance tuning, and real-world issues**:

---

## 🔸 1. **What is Consumer Lag? How do you handle it in production?**

### ✅ **Answer:**
Consumer lag = **Difference between the latest offset (head) in a Kafka partition and the last committed offset** by a consumer.

**Lag = Log End Offset - Committed Offset**

### 📌 Causes:
- Slow processing logic
- Too few consumer instances
- High message volume spikes
- Blocking I/O (e.g., DB writes)

### 🛠 How to Handle:
- Use **horizontal scaling**: add more consumers
- Improve **processing time** or batch writes to DB
- Increase **`max.poll.records`** to fetch more per poll
- Tune **`max.poll.interval.ms`** and thread pool size
- Monitor with tools like **Burrow**, **Prometheus**, or **Cruise Control**

---

## 🔸 2. **How is Kafka used in Low-Level Design (LLD)?**

### ✅ **Answer:**
Kafka is used as a **decoupling layer** between producers and consumers in LLD.

### 📌 Use Cases:
- **Order Processing System**  
  - `orders` → process → `payments` → `shipment`
- **Event-Driven Microservices**  
  - Publish domain events (e.g., "user.created", "refund.failed")
- **Audit Logs / CDC**  
  - Persist user/system actions asynchronously

### 📌 Advantages in LLD:
| Benefit | Impact |
|--------|--------|
| Async communication | Non-blocking workflows |
| Replayable events | Recovery and audits |
| Decoupling | Services evolve independently |
| Scaling | Separate consumers per need (batch, analytics) |

---

## 🔸 3. **What happens during Kafka partition rebalance?**

### ✅ **Answer:**
When a consumer joins/leaves, Kafka triggers **rebalancing** using **Group Coordinator**.

### 📌 Steps:
1. Stop fetching during rebalance
2. Reassign partitions to consumers
3. Resume fetching with new assignments

### 🔥 Problems:
- Processing paused during rebalance
- Offset commits may fail
- Frequent rebalances = throughput drop

### 🛠 Solutions:
- Use **static membership** (`group.instance.id`)
- Use **Cooperative Sticky Assignor** to avoid full reassignment
- Commit offsets only **after** successful processing

---

## 🔸 4. **How would you design a Dead Letter Queue (DLQ) in Kafka?**

### ✅ **Answer:**
When a consumer **fails to process a message**, you should not lose it. Instead, redirect it to a **DLQ** topic.

### 🛠 How:
```java
try {
    process(message);
    commitOffset();
} catch (Exception e) {
    produceToDLQ(originalMessage, errorDetails);
}
```

### 📌 DLQ Best Practices:
- Use a separate `dlq.<original-topic>` topic
- Include error reason + timestamp in message
- Monitor DLQ volume with alerts
- Setup replay tooling for DLQ

---

## 🔸 5. **How does Kafka achieve high throughput and durability?**

### ✅ **Answer:**
Kafka’s performance is based on:

| Feature | Description |
|--------|-------------|
| Sequential disk writes | Log files are append-only (fast) |
| OS page cache | Avoids per-message fsync |
| Batching & compression | Reduces network and disk I/O |
| Zero-copy | Uses `sendfile()` syscall to push bytes directly to socket |
| Replication | Replicas store messages on different brokers |

---

## 🔸 6. **What is Idempotence in Kafka? How does it prevent duplicates?**

### ✅ **Answer:**
If a producer retries due to timeout, the same message can be written twice.  
**Idempotent Producer** ensures duplicates are **not written** even if retried.

### 📌 How:
- Kafka assigns a **producer ID (PID)**
- Adds a **sequence number** to every message
- Broker deduplicates messages per partition

### ✅ Enable it:
```properties
enable.idempotence=true
acks=all
```

---

## 🔸 7. **What is ISR (In-Sync Replica) and how does it affect durability?**

### ✅ **Answer:**
ISR = Set of replicas that are **fully caught up** with the leader.

Kafka only **acks writes** when data is replicated to all ISR members (based on `min.insync.replicas`).

### 📌 Risk:
- If ISR falls below `min.insync.replicas`, Kafka **rejects writes** (to prevent data loss)

### 🛠 Tip:
- Set `min.insync.replicas=2` with `replication.factor=3` for safety

---

## 🔸 8. **How to achieve Exactly-Once delivery in Kafka?**

### ✅ **Answer:**
Requires:
1. **Idempotent producer** → prevent duplicate writes
2. **Transactional producer** → atomicity of data + offset commit
3. **`read_committed` consumers**

### 📌 Use Case:
ETL pipeline: Kafka → Processor → Kafka  
You can use `sendOffsetsToTransaction` to atomically update offsets and send messages.

---

## 🔸 9. **How to handle message ordering across partitions?**

### ✅ **Answer:**
- Kafka guarantees **ordering per partition**, not globally
- Use a consistent **partitioning key** (e.g., userId)

```java
producer.send(new ProducerRecord("topic", userId, message));
```

- To ensure strict ordering, use **one partition** or **keyed writes**

---

## 🔸 10. **What would you monitor in a production Kafka cluster?**

### ✅ **Key Metrics:**

| Area | Metric |
|------|--------|
| Consumer | Lag per partition |
| Broker | Disk usage, CPU, heap, under-replicated partitions |
| Producer | Send errors, retries |
| Topics | Message rate, ISR count |
| Zookeeper | Session count, leader election |

---
Here are **new, scenario-based Kafka interview questions** with **detailed answers**, designed for **senior-level roles**:

---

## 🔸 11: Kafka Message Duplication in Downstream System**

**Question:**  
Your service consumes Kafka messages and writes to a database. The team reports duplicate rows. How would you debug and solve this?

### ✅ **Answer:**

### 🔍 Root Causes:
1. **Consumer commits offset before DB write**
2. **Retries without idempotency**
3. **No unique constraint in DB**

### 🛠 Fix Strategy:
- Use **at-least-once** safely by:
  - Committing offsets **after** successful DB write
  - Making DB writes **idempotent** (e.g., using a message UUID or primary key constraint)
- Use a **transactional producer** + **`sendOffsetsToTransaction`** if you're producing to another Kafka topic as well.

---

## 🔸 12: High Consumer Lag in One Partition Only**

**Question:**  
You have a Kafka topic with 5 partitions. One partition shows consistently high lag. What might be wrong?

### ✅ **Answer:**

### 🔍 Likely Issues:
- **Hot partition**: Uneven key distribution (e.g., some user IDs more active)
- **Stuck message**: Processing blocked on a bad message
- **Single-threaded consumer**: One thread handles one partition

### 🛠 Fix:
- Redistribute key space or use a **custom partitioner**
- Introduce **parallel processing** within consumer (e.g., thread pool)
- Use **DLQ** to isolate bad messages and continue processing others

---

## 🔸 13: Kafka Topic’s Disk Usage Growing Rapidly**

**Question:**  
Your Kafka topic is consuming too much disk space. What steps will you take?

### ✅ **Answer:**

### 🧪 Diagnosis:
- Check topic retention: `retention.ms`, `retention.bytes`
- Are consumers lagging or stopped?
- Is cleanup policy `compact` or `delete`?

### 🛠 Actions:
- Reduce `retention.ms` to keep logs for fewer days
- Use `compact` only if key-based change logs make sense
- Add a **monitoring alert** for lag and disk usage
- Ensure consumers are alive and consuming data

---

## 🔸 14: Kafka Broker Crashes Unexpectedly**

**Question:**  
One of your Kafka brokers keeps crashing under load. How do you investigate?

### ✅ **Answer:**

### 🔍 Steps:
- Check `server.log` and `controller.log`
- Monitor JVM: heap, GC pressure, threads
- Disk I/O saturation or disk full?
- Look for oversized messages causing memory pressure

### 🛠 Fix:
- Tune `message.max.bytes` and `fetch.message.max.bytes`
- Increase heap or tune GC (e.g., G1GC)
- Use **segmented log** directory per disk
- Consider upgrading Kafka if bug-related

---

## 🔸 15: Kafka Consumers Stop Receiving Data After Deployment**

**Question:**  
After a new deployment, consumers stop consuming messages. Brokers are healthy. What’s your debugging strategy?

### ✅ **Answer:**

### ✅ Checklist:
- Are **consumer group IDs** accidentally changed? → New group = starts from latest offset
- `auto.offset.reset` set to `latest` instead of `earliest`?
- `enable.auto.commit=true` but not actually processing?

### 🛠 Fix:
- Ensure **stable group.id** across deployments
- Set `auto.offset.reset=earliest` for first run
- Use **manual offset commits** after successful processing

---

## 🔸 16: Kafka Consumers Rebalancing Too Frequently**

**Question:**  
You notice high latency due to frequent consumer group rebalancing. What could be the cause?

### ✅ **Answer:**

### 🔍 Root Causes:
- Consumers crashing or timing out
- Scaling pods up/down rapidly
- No **static membership**

### 🛠 Fix:
- Use `group.instance.id` for **static group membership**
- Tune `session.timeout.ms` and `heartbeat.interval.ms`
- Avoid scaling Kafka consumers during peak hours

---

## 🔸 17: Message Ordering Violated in an Event-Driven Workflow**

**Question:**  
You expect messages for a user to be processed in order but you’re seeing out-of-order processing. How would you handle this?

### ✅ **Answer:**

### 🔍 Likely Cause:
- Messages are spread across multiple partitions due to bad or missing key
- Multiple consumers processing out-of-order

### 🛠 Fix:
- Use **userId as key** → ensures messages land in the same partition
- Process one partition per consumer thread
- For strict order: **single partition + single-threaded consumer** (at cost of throughput)

---
