Here’s a complete breakdown of **Kafka Operations** every backend/devops engineer should know:

---

## 🔷 **Kafka Operations Guide**

---

### 🔹 1. **Topic Management**

#### ✅ Create a topic:
```bash
kafka-topics.sh --create --topic orders --bootstrap-server localhost:9092 --partitions 3 --replication-factor 2
```

#### ✅ List topics:
```bash
kafka-topics.sh --list --bootstrap-server localhost:9092
```

#### ✅ Describe a topic:
```bash
kafka-topics.sh --describe --topic orders --bootstrap-server localhost:9092
```

#### ✅ Delete a topic:
```bash
kafka-topics.sh --delete --topic orders --bootstrap-server localhost:9092
```

---

### 🔹 2. **Producing and Consuming Data**

#### ✅ Start a producer (CLI):
```bash
kafka-console-producer.sh --topic orders --bootstrap-server localhost:9092
```

#### ✅ Start a consumer (CLI):
```bash
kafka-console-consumer.sh --topic orders --bootstrap-server localhost:9092 --from-beginning
```

---

### 🔹 3. **Consumer Group Management**

#### ✅ List consumer groups:
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list
```

#### ✅ Describe a group:
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --describe --group my-consumer-group
```

#### ✅ Reset offsets:
```bash
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group my-consumer-group \
  --topic orders \
  --reset-offsets --to-earliest --execute
```

---

### 🔹 4. **Broker Operations**

#### ✅ View broker IDs:
Check Zookeeper:
```bash
zookeeper-shell.sh localhost:2181 ls /brokers/ids
```

---

### 🔹 5. **Monitoring**

Use **JMX** or tools like:
- **Kafka Manager**
- **Prometheus + Grafana**
- **Burrow** (for lag monitoring)
- **Cruise Control** (for auto-balancing)

---

### 🔹 6. **Partitions & Replication**

#### ✅ Reassign partitions:
1. Generate reassignment JSON:
```bash
kafka-reassign-partitions.sh --generate ...
```
2. Execute reassignment:
```bash
kafka-reassign-partitions.sh --execute ...
```

---

### 🔹 7. **Configuration Changes**

#### ✅ Add partitions:
```bash
kafka-topics.sh --alter --topic orders --partitions 6 --bootstrap-server localhost:9092
```

#### ✅ Change retention policy:
```bash
kafka-configs.sh --alter --bootstrap-server localhost:9092 \
  --entity-type topics --entity-name orders \
  --add-config retention.ms=604800000
```

---

### 🔹 8. **Log Cleanup & Retention**

Configs:
- `retention.ms` – How long to retain messages
- `retention.bytes` – Retain up to size
- `segment.ms` – How frequently to roll logs

---
