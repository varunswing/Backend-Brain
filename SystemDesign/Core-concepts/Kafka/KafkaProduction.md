Running **Kafka in production** requires more than just starting brokers. You must focus on **reliability, observability, performance, and disaster recovery**.

Here's a detailed guide:

---

## ✅ 1. **Cluster Setup**

| Component | Best Practice |
|----------|---------------|
| Brokers  | Start with **3+ brokers** (odd number) for high availability |
| Zookeeper | Use **3-5 nodes**, or switch to **KRaft** in Kafka 3.3+ |
| Partitions | Use enough partitions (more = better parallelism, but more metadata) |
| Replication | Set `replication.factor=3` for critical topics |
| Rack Awareness | Use `broker.rack` config for better fault zones |

---

## ✅ 2. **Topic Configuration**

| Setting | Value | Purpose |
|--------|-------|---------|
| `replication.factor` | 3 | High availability |
| `min.insync.replicas` | 2 | Required replicas to acknowledge for durability |
| `cleanup.policy` | `delete` or `compact` | Choose compaction for changelog topics |
| `retention.ms` | Set to days/hours | Manage disk usage for logs |

---

## ✅ 3. **Producer Configuration**

```properties
acks=all
retries=3
enable.idempotence=true
compression.type=snappy
batch.size=16384
linger.ms=5
```

- **acks=all** → strong durability
- **idempotence=true** → prevents duplicate writes
- **compression** → improves throughput

---

## ✅ 4. **Consumer Configuration**

```properties
enable.auto.commit=false
max.poll.records=500
max.poll.interval.ms=300000
session.timeout.ms=10000
```

- Manually commit offsets **after successful processing**
- Tune `max.poll.records` for processing speed vs memory

---

## ✅ 5. **Monitoring & Alerts**

Use **Grafana + Prometheus** or **Datadog/ELK** to monitor:

| Metric | What to Watch |
|--------|---------------|
| Under-replicated partitions | 🔥 Should be 0 |
| Consumer lag | Should not grow indefinitely |
| Disk usage per broker | Stay below 80% |
| ISR (In-Sync Replicas) | Match replication factor |
| Request latency | Any spike = potential problem |

---

## ✅ 6. **Security Setup**

| Feature | Use |
|--------|-----|
| TLS | Encrypt traffic (SSL between brokers, clients) |
| SASL | Auth users (PLAIN, SCRAM, Kerberos) |
| ACLs | Control access to topics/groups |

---

## ✅ 7. **Reliability & Backups**

- Enable **log compaction** for changelogs
- Use **MirrorMaker 2** for cross-cluster replication
- Setup **Daily log segment backups to S3/HDFS**

---

## ✅ 8. **Operational Tasks**

| Task | Tool |
|------|------|
| Broker rolling upgrade | `kafka-rolling-restart.sh` |
| Topic expansion | `kafka-reassign-partitions.sh` |
| Lag check | Burrow or Cruise Control |
| Disk cleanup | `kafka-log-dirs.sh` |

---

## ✅ 9. **Common Failure Scenarios**

| Problem | Fix |
|--------|-----|
| Broker crash | Kafka auto rebalances partitions |
| Partition out of ISR | Check replication, broker load |
| Lag spikes | Check slow consumers or GC issues |
| Full disk | Use `delete.retention.ms`, remove old logs |

---

## ✅ 10. **Kafka Tools You Should Know**

| Tool | Use |
|------|-----|
| **kcat** | Test produce/consume from terminal |
| **Cruise Control** | Broker rebalancing |
| **Kafka Manager** | UI to manage brokers, topics |
| **Burrow** | Consumer lag monitoring |

---