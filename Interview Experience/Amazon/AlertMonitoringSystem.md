Great. Let’s design a **Low-Level Design (LLD) for an Alert Monitoring System** supporting:

* `SIMPLE_COUNT`: alert if count of events exceeds a threshold
* `BUCKETED_WINDOW`: fixed-size time windows (e.g., 5-minute buckets)
* `MOVING_WINDOW`: sliding window with real-time evaluation

---

## ✅ 1. Functional Requirements

### Core Requirements:

* Ingest real-time event data (metric, timestamp, value)
* Evaluate alerts on:

  * `SIMPLE_COUNT` – e.g., errors > 100 in last 5 mins
  * `BUCKETED_WINDOW` – e.g., CPU usage > 70% in a fixed 5-min window
  * `MOVING_WINDOW` – e.g., latency > 200ms in any 10-second sliding window
* Send alerts to configured channels (Slack, Email, Webhook)
* Allow alert definition with configurable thresholds, windows, channels
* Allow listing and querying alerts and their history

### ✅ 2. Non-Functional Requirements

* **Real-time ingestion & evaluation** (low latency)
* **High throughput** (100K events/sec)
* **Delivery guarantees**: at-least-once processing
* **Fault tolerance**: alerts should not be missed
* **Scalability**: horizontally scalable ingestion and evaluation
* **Durability**: alerts and rules must persist across restarts

---

## ✅ 3. DB Schema

### 💽 Relational DB (e.g., **PostgreSQL**) for alert metadata:

```sql
CREATE TABLE AlertRule (
  id UUID PRIMARY KEY,
  name TEXT,
  type TEXT CHECK (type IN ('SIMPLE_COUNT', 'BUCKETED_WINDOW', 'MOVING_WINDOW')),
  metric_name TEXT,
  threshold DOUBLE,
  window_duration_secs INT,
  bucket_interval_secs INT,
  created_at TIMESTAMP
);

CREATE TABLE AlertNotification (
  id UUID PRIMARY KEY,
  alert_id UUID REFERENCES AlertRule(id),
  channel TEXT, -- email/slack/webhook
  endpoint TEXT
);

CREATE TABLE AlertTriggerHistory (
  id UUID PRIMARY KEY,
  alert_id UUID REFERENCES AlertRule(id),
  triggered_at TIMESTAMP,
  value DOUBLE,
  details JSONB
);
```

### 💽 Time Series DB (e.g., **ClickHouse**, **InfluxDB**, or **TimescaleDB**) for events:

```sql
-- Events ingested into Timescale hypertable
CREATE TABLE MetricEvent (
  time TIMESTAMP NOT NULL,
  metric_name TEXT,
  value DOUBLE,
  labels JSONB
);
```

---

## ✅ 4. UML Class Diagram

```
+------------------+
|     AlertRule    |
+------------------+
| id               |
| name             |
| type             |
| metric_name      |
| threshold        |
| window_duration  |
| bucket_interval  |
+------------------+
        |
        | 1
        |
        | *    
+---------------------+
| AlertNotification   |
+---------------------+
| id                  |
| alert_id            |
| channel             |
| endpoint            |
+---------------------+

+------------------+
| MetricEvent      |
+------------------+
| time             |
| metric_name      |
| value            |
| labels           |
+------------------+

+------------------------+
| AlertTriggerHistory    |
+------------------------+
| alert_id               |
| triggered_at           |
| value                  |
| details                |
+------------------------+
```

---

## ✅ 5. API Endpoints

### 🔷 Rule Management

```http
POST /api/alerts
{
  "name": "High Error Count",
  "type": "SIMPLE_COUNT",
  "metricName": "error_count",
  "threshold": 100,
  "windowDurationSecs": 300,
  "bucketIntervalSecs": null
}
```

```http
GET /api/alerts
GET /api/alerts/{id}
DELETE /api/alerts/{id}
```

---

### 🔷 Event Ingestion

```http
POST /api/events
[
  {
    "metricName": "error_count",
    "timestamp": "2025-06-30T15:00:00Z",
    "value": 1,
    "labels": {
      "service": "payment"
    }
  }
]
```

---

### 🔷 Alert History

```http
GET /api/alerts/{id}/history
```

---

## ✅ 6. Service Calls & Implementation (with code)

### Ingestion Service

* Accepts event data via REST or Kafka topic
* Persists to time-series DB (ClickHouse / Influx / TimescaleDB)
* Optionally pushes to **Kafka** for real-time processing

```java
public class EventIngestionService {
    public void ingest(MetricEvent event) {
        timeSeriesDB.insert(event);
        kafkaProducer.send("events", event);
    }
}
```

---

### Alert Evaluation Engine

* Subscribes to Kafka
* Matches incoming events to alert rules
* Evaluates and triggers alerts using:

  * Count for `SIMPLE_COUNT`
  * Window aggregates for `BUCKETED_WINDOW`
  * Sliding counters for `MOVING_WINDOW`

```java
public class AlertEvaluator {
    public void evaluate(Event event) {
        List<AlertRule> rules = ruleRepo.findByMetric(event.metricName);
        for (AlertRule rule : rules) {
            switch (rule.type) {
                case SIMPLE_COUNT:
                    evaluateSimpleCount(rule, event);
                    break;
                case BUCKETED_WINDOW:
                    evaluateBucketed(rule, event);
                    break;
                case MOVING_WINDOW:
                    evaluateMoving(rule, event);
                    break;
            }
        }
    }
}
```

---

### Notification Service (Observer Pattern)

```java
public interface NotificationChannel {
    void send(String message, AlertRule rule);
}

public class SlackNotifier implements NotificationChannel {
    public void send(String message, AlertRule rule) {
        // send POST to Slack Webhook
    }
}
```

---

## ✅ 7. Design Patterns Involved

| Pattern        | Usage                                                |
| -------------- | ---------------------------------------------------- |
| **Observer**   | Alert → Notification dispatch to multiple channels   |
| **Strategy**   | Different alert types use different evaluation logic |
| **Repository** | DB interaction layers for `AlertRule`, `MetricEvent` |
| **Factory**    | Channel factory (Slack, Email, Webhook)              |

---

## ✅ 8. Handling Concurrency, Lag, Faults

### Concurrency

* Events may arrive out of order; we use timestamps to assign them to windows.
* Evaluation logic must be **thread-safe** using concurrent maps or DB atomic ops.

### Lag

* `MOVING_WINDOW` can suffer if events arrive late.

  * Solution: use watermarking + buffer last N minutes in memory.

### Delivery Guarantees

* Kafka ensures **at-least-once delivery** to evaluation engine.
* DB writes are idempotent.
* Use UUID-based deduplication to avoid duplicate alerts.

### Fault Tolerance

* Kafka-based ingestion ensures replay on failure.
* Alert state cached in Redis for quick access with fallback to DB.

---

## ✅ 9. Storage & Architecture Choices

| Component        | Tech Used                         | Reason                        |
| ---------------- | --------------------------------- | ----------------------------- |
| **Event Store**  | TimescaleDB / ClickHouse / Influx | High write throughput         |
| **Rules/Alerts** | PostgreSQL                        | Relational & transaction-safe |
| **Queue**        | Kafka                             | High-throughput, replayable   |
| **Cache**        | Redis                             | Fast access to counters       |

---

## ✅ 10. Real-time vs Batch

| Type             | Use Cases                                                      |
| ---------------- | -------------------------------------------------------------- |
| **Real-time**    | Moving window alerts, ingestion                                |
| **Batch (cron)** | Re-evaluate missed windows (fallback), periodic recalculations |

---

Let's break down the **Database Design** for the Alert Monitoring System in **detail**.

---

## 🗃️ High-Level View

We need **two types of databases**:

| Purpose                 | DB Type             | Examples                            |
| ----------------------- | ------------------- | ----------------------------------- |
| Alert config, metadata  | **Relational DB**   | PostgreSQL / MySQL                  |
| Time-series metric data | **Time-series DB**  | TimescaleDB / InfluxDB / ClickHouse |
| Transient counter cache | **In-memory Store** | Redis                               |

---

## ✅ 1. Alert Rule Metadata — (PostgreSQL)

Stores **alert configuration** (threshold, type, windows, etc.).

### `AlertRule` Table

```sql
CREATE TABLE AlertRule (
  id UUID PRIMARY KEY,
  name TEXT NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('SIMPLE_COUNT', 'BUCKETED_WINDOW', 'MOVING_WINDOW')),
  metric_name TEXT NOT NULL,
  threshold DOUBLE PRECISION NOT NULL,
  window_duration_secs INT,        -- for BUCKETED_WINDOW or MOVING_WINDOW
  bucket_interval_secs INT,        -- only for BUCKETED_WINDOW
  is_active BOOLEAN DEFAULT TRUE,
  created_at TIMESTAMP DEFAULT now()
);
```

✅ **Why relational DB?**

* Alert rules are rarely updated.
* Need ACID for configuration correctness.
* JOINs needed with notification configs.

---

### `AlertNotification` Table

Stores **how to notify** when an alert triggers (e.g., Slack, Email).

```sql
CREATE TABLE AlertNotification (
  id UUID PRIMARY KEY,
  alert_id UUID REFERENCES AlertRule(id),
  channel TEXT CHECK (channel IN ('EMAIL', 'SLACK', 'WEBHOOK')),
  endpoint TEXT NOT NULL,    -- Email address / webhook URL
  created_at TIMESTAMP DEFAULT now()
);
```

---

### `AlertTriggerHistory` Table

Stores **alert trigger logs** — used for audit, analysis, retry on failure.

```sql
CREATE TABLE AlertTriggerHistory (
  id UUID PRIMARY KEY,
  alert_id UUID REFERENCES AlertRule(id),
  triggered_at TIMESTAMP,
  event_time TIMESTAMP,         -- Time of actual metric event
  current_value DOUBLE PRECISION,
  window_start TIMESTAMP,       -- For BUCKETED/MOVING window
  window_end TIMESTAMP,
  message TEXT,
  status TEXT CHECK (status IN ('SUCCESS', 'FAILED', 'RETRY_PENDING'))
);
```

✅ **Indexing Strategy**:

```sql
CREATE INDEX idx_alert_rule_metric ON AlertRule(metric_name);
CREATE INDEX idx_alert_trigger_alert ON AlertTriggerHistory(alert_id);
```

---

## ✅ 2. Time-Series Metric Data — (TimescaleDB / InfluxDB / ClickHouse)

Used to **store real-time metrics** with timestamps.

### `MetricEvent` Table (Hypertable in TimescaleDB)

```sql
CREATE TABLE MetricEvent (
  time TIMESTAMP NOT NULL,
  metric_name TEXT NOT NULL,
  value DOUBLE PRECISION NOT NULL,
  labels JSONB
);
```

> Example Row:

```json
{
  "time": "2025-06-30T12:00:00Z",
  "metric_name": "latency_ms",
  "value": 250,
  "labels": {
    "service": "payment",
    "host": "10.0.0.1"
  }
}
```

✅ Use **hypertables** (TimescaleDB) or **partitioned tables** (PostgreSQL) for:

* Better insert throughput
* Easy querying over windows
* Retention policies (auto-delete after X days)

✅ **Indexing**:

```sql
CREATE INDEX idx_metric_event ON MetricEvent(metric_name, time DESC);
```

---

## ✅ 3. Redis (Optional) — Fast Count / Bucket Cache

**Why?**
To avoid scanning DB repeatedly for:

* Count of events in window
* Maintaining sliding counters
* De-duplication of alert triggers

### Sample Redis keys:

```
alert:counter:{alertId}:{bucketStart} = <count>
alert:lastTriggered:{alertId} = <timestamp>
metric:sliding:window:{alertId} = [timestamp1, timestamp2, ...]
```

Set TTL (Time To Live) = window + buffer

---

## ✅ 4. Partitioning & Sharding Strategy

### Time-Series DB

* Partition by **metric\_name** + **time window** (e.g., daily or hourly).
* Shard across nodes by **metric\_name** hash.

### PostgreSQL (Alert Metadata)

* Can scale vertically or use read replicas.
* Alerts can be partitioned per `tenant_id` or `organization_id` (if multi-tenant).

---

## ✅ 5. Retention Policy

| Table                       | Retention Policy              |
| --------------------------- | ----------------------------- |
| `MetricEvent`               | TTL 15-30 days (configurable) |
| `AlertTriggerHistory`       | TTL 90 days or archived       |
| `AlertRule`, `Notification` | Permanent                     |

---

## ✅ 6. Summary Table

| Table Name            | Purpose                            | Storage Type         |
| --------------------- | ---------------------------------- | -------------------- |
| `AlertRule`           | Stores alert configs               | PostgreSQL           |
| `AlertNotification`   | Stores channel + endpoint configs  | PostgreSQL           |
| `AlertTriggerHistory` | Audit history of alerts triggered  | PostgreSQL           |
| `MetricEvent`         | Time-series metrics storage        | TimescaleDB / Influx |
| `Redis (Optional)`    | Counter/bucket cache for fast eval | Redis                |

---

Let’s break down the **types of databases** you would use in an **Alert Monitoring System**, and **why** you choose each based on the characteristics of the data and operations involved.

---

## 🔧 Problem Summary:

You need to store:

1. **Metric Events** (e.g., error counts, CPU usage)
2. **Alert Configuration**
3. **Alert Trigger History**
4. **Short-term counters for fast alerting (optional)**

These use-cases require different DB types optimized for different access patterns.

---

## 🏗️ Database Types Overview

| Data Type              | Best DB Type               | Example Technologies              |
| ---------------------- | -------------------------- | --------------------------------- |
| Real-time Metrics      | **Time-Series DB**         | TimescaleDB, InfluxDB, ClickHouse |
| Alert Config & Rules   | **Relational DB (RDBMS)**  | PostgreSQL, MySQL                 |
| Alert Trigger History  | **Relational DB**          | PostgreSQL                        |
| Sliding counters/cache | **In-memory Key-Value DB** | Redis                             |

---

## ✅ 1. Time-Series Database

### 🔹 What is it?

A time-series database is optimized for storing **timestamped events**, typically at high write rates.

### 🔹 Why use it for metrics?

* Every event (e.g., CPU usage, error count) has:

  * `timestamp`, `metric_name`, `value`, and possibly `tags/labels`
* You need fast:

  * **Inserts** (up to 100k+ per sec)
  * **Range queries** over time (e.g., last 5 mins)

### 🔹 Key Features:

* Automatic partitioning based on **time**
* **Compression** of time-series data
* **Downsampling** (e.g., store 1 min average after 1 day)
* **Continuous aggregates** (precomputed metrics)

### 🔹 Good options:

| Tool            | Notes                                                               |
| --------------- | ------------------------------------------------------------------- |
| **TimescaleDB** | PostgreSQL extension, great SQL support, easy to use                |
| **InfluxDB**    | Pure TSDB, better for IoT/metrics, has Flux query language          |
| **ClickHouse**  | Excellent for aggregations over large volumes, widely used at scale |

---

## ✅ 2. Relational Database (RDBMS)

### 🔹 Why use it for configuration data?

Alert rules, thresholds, notification settings are:

* **Structured**
* Have relationships (foreign keys)
* Require **ACID** transactions

### 🔹 Use it to store:

* Alert definitions (thresholds, windows)
* Alert notification endpoints
* Trigger history of alerts

### 🔹 Best choices:

| DB         | Reason                                                 |
| ---------- | ------------------------------------------------------ |
| PostgreSQL | Strong support for JSONB, complex queries, reliability |
| MySQL      | Fast, widely supported, simpler use cases              |

### 🔹 RDBMS strengths:

* Strong consistency
* Easy indexing and querying
* Joins and constraints
* Mature tooling and backup

---

## ✅ 3. In-Memory Cache / KV Store (Redis)

### 🔹 Why use Redis?

* You want **low latency** (microseconds)
* You need **fast access counters** like:

  * Number of events in a window
  * Last triggered time for an alert
* You may want to store **sliding windows** in-memory

### 🔹 Sample Redis use-cases:

```bash
SET alert:counter:alertId:bucket:202506301230 100  # count of events in this bucket
GET alert:lastTriggered:alertId                   # last alert timestamp
```

### 🔹 Benefits:

* Fastest read/write
* Supports TTL (auto expiry)
* Pub/Sub and Streams for advanced patterns

---

## 🧠 Summary Table of When to Use What

| Use Case                      | Recommended DB         | Reason                                        |
| ----------------------------- | ---------------------- | --------------------------------------------- |
| Store metrics per second      | TimescaleDB / InfluxDB | Optimized for writes, time-based aggregations |
| Define alerts and thresholds  | PostgreSQL             | Relational integrity, queryability            |
| Store trigger history (audit) | PostgreSQL             | Needs durable, structured history             |
| Store in-memory counters      | Redis                  | Extremely fast, supports TTL                  |

---

## 🚫 What *not* to use

| DB Type       | Why not use?                                                           |
| ------------- | ---------------------------------------------------------------------- |
| MongoDB       | Good for unstructured data, but not ideal for time-based range queries |
| Cassandra     | Good for heavy writes, but complex for joins or alert logic            |
| Elasticsearch | Great for logs/search, but expensive for high cardinality metrics      |

---

## 🧩 When to Combine Them (Polyglot Persistence)

In production, you’d often **combine multiple DBs**:

* **TimescaleDB** → to store metrics efficiently
* **PostgreSQL** → to manage rules, users, history
* **Redis** → to maintain counters, sliding windows, quick deduplication

This is called **polyglot persistence** — using the **right tool for the job**.

---