# Batch vs Stream Processing

## Overview

Two fundamental approaches to processing data at scale.

```
Batch Processing:
  Collect data → Process all at once → Output results
  (Minutes to hours)

Stream Processing:
  Data arrives → Process immediately → Output in real-time
  (Milliseconds to seconds)
```

---

## Batch Processing

### What is Batch Processing?

Processing data in large chunks at scheduled intervals.

```
┌──────────┐    ┌──────────┐    ┌──────────┐
│   Data   │───→│  Batch   │───→│  Output  │
│  (24hrs) │    │  Job     │    │ (Report) │
└──────────┘    └──────────┘    └──────────┘
      │              │               │
   Collect      Process all      Generate
    data        at midnight       results
```

### Characteristics
- High throughput
- High latency (results not immediate)
- Efficient for large datasets
- Fault tolerant (restart from checkpoint)

### Use Cases
| Use Case | Example |
|----------|---------|
| ETL Jobs | Daily warehouse loads |
| Reports | Monthly sales reports |
| ML Training | Train models on historical data |
| Data Archival | Compress and archive logs |
| Analytics | Calculate user metrics |

### Technologies

| Tool | Description |
|------|-------------|
| **Apache Spark** | Distributed batch processing |
| **Hadoop MapReduce** | Original big data batch |
| **AWS Glue** | Serverless ETL |
| **Apache Hive** | SQL on Hadoop |
| **Airflow** | Workflow orchestration |

### Batch Processing Example (Spark)

```python
from pyspark.sql import SparkSession

spark = SparkSession.builder.appName("DailySales").getOrCreate()

# Read all data from yesterday
df = spark.read.parquet("s3://data/sales/date=2024-01-15/")

# Process
daily_totals = df.groupBy("store_id") \
    .agg({"amount": "sum", "transactions": "count"})

# Write results
daily_totals.write.parquet("s3://reports/daily_sales/")
```

---

## Stream Processing

### What is Stream Processing?

Processing data continuously as it arrives.

```
Events → ┌──────────┐ → Real-time
 flow    │  Stream  │    output
───────→ │ Processor│ ───────→
         └──────────┘
```

### Characteristics
- Low latency (sub-second)
- Continuous processing
- Complex event handling
- State management challenges

### Use Cases
| Use Case | Example |
|----------|---------|
| Real-time Analytics | Live dashboards |
| Fraud Detection | Instant transaction alerts |
| IoT Processing | Sensor data analysis |
| Log Processing | Error monitoring |
| Recommendations | Real-time personalization |

### Technologies

| Tool | Description |
|------|-------------|
| **Apache Kafka Streams** | Kafka-native streaming |
| **Apache Flink** | True streaming with state |
| **Apache Spark Streaming** | Micro-batch streaming |
| **AWS Kinesis** | Managed streaming |
| **Apache Storm** | Legacy streaming |

### Stream Processing Example (Kafka Streams)

```java
StreamsBuilder builder = new StreamsBuilder();

// Read from Kafka topic
KStream<String, Transaction> transactions = 
    builder.stream("transactions");

// Process: Detect high-value transactions
KStream<String, Alert> alerts = transactions
    .filter((key, txn) -> txn.getAmount() > 10000)
    .mapValues(txn -> new Alert("High value: " + txn.getId()));

// Write to output topic
alerts.to("fraud-alerts");
```

---

## Comparison

| Aspect | Batch | Stream |
|--------|-------|--------|
| **Latency** | Minutes to hours | Milliseconds to seconds |
| **Throughput** | Very high | High |
| **Data Size** | Bounded (finite) | Unbounded (infinite) |
| **Processing** | All at once | Continuous |
| **Complexity** | Simpler | More complex |
| **State** | Easy (complete data) | Hard (partial data) |
| **Fault Tolerance** | Restart job | Checkpointing |
| **Cost** | Often cheaper | More resources |

---

## Stream Processing Concepts

### Windows

Group streaming data by time or count.

```
Tumbling Window (non-overlapping):
  |──5min──|──5min──|──5min──|
  [event1] [event3] [event5]
  [event2] [event4] [event6]

Sliding Window (overlapping):
  |──5min──|
       |──5min──|
            |──5min──|

Session Window (gap-based):
  [event1][event2]───gap───[event3][event4]───gap───
  |───session 1───|        |───session 2───|
```

```python
# Flink windowing example
events.keyBy("userId")
    .window(TumblingEventTimeWindows.of(Time.minutes(5)))
    .aggregate(CountAggregate())
```

### Watermarks

Handle late-arriving data.

```
Event Time:     1:00  1:01  1:02  1:03  1:04  1:05
                 ↓     ↓     ↓     ↓     ↓     ↓
Processing:     1:02  1:03  1:01  1:04  1:05  1:06
                             ↑
                         Late event!

Watermark: "All events before time X have arrived"
- Events after watermark are "late"
- Can allow lateness up to threshold
```

### State Management

```python
# Stateful stream processing
class FraudDetector:
    def __init__(self):
        self.user_state = {}  # Per-user state
    
    def process(self, event):
        user_id = event.user_id
        
        # Get or initialize state
        state = self.user_state.get(user_id, {"count": 0, "total": 0})
        
        # Update state
        state["count"] += 1
        state["total"] += event.amount
        
        # Check fraud condition
        if state["count"] > 10 and state["total"] > 50000:
            emit_alert(user_id)
        
        # Save state
        self.user_state[user_id] = state
```

### Exactly-Once Processing

```
At-most-once:  May lose data (fastest)
At-least-once: May duplicate data (most common)
Exactly-once:  No loss, no duplicates (hardest)

Exactly-once requires:
1. Idempotent processing
2. Transactional state updates
3. Source replay capability
```

---

## Hybrid Architectures

### Lambda Architecture

Combine batch and stream for best of both.

```
                         ┌─────────────────┐
                    ┌───→│   Batch Layer   │───┐
                    │    │  (Spark/Hadoop) │   │
                    │    └─────────────────┘   │
┌──────────┐        │                          ▼
│   Data   │────────┤                    ┌──────────┐
│  Source  │        │                    │  Serving │
└──────────┘        │    ┌─────────────┐ │  Layer   │
                    └───→│ Speed Layer │─→│          │
                         │(Kafka/Flink)│  └──────────┘
                         └─────────────┘
                         
Batch: Accurate but slow (recompute all)
Speed: Fast but approximate (real-time)
Serving: Merges both views
```

### Kappa Architecture

Stream-only architecture.

```
┌──────────┐    ┌─────────────┐    ┌──────────┐
│   Data   │───→│   Stream    │───→│  Serving │
│  Source  │    │  Processing │    │  Layer   │
└──────────┘    │ (Kafka/Flink│    └──────────┘
                └─────────────┘

Reprocess by replaying from beginning of stream.
Simpler than Lambda, but requires replayable source.
```

---

## When to Use What

### Use Batch When:
- Data freshness in hours/days is acceptable
- Processing complete dataset is needed
- Complex transformations required
- Cost optimization is priority
- Historical analysis

### Use Stream When:
- Real-time response needed
- Continuous monitoring required
- Event-driven architecture
- Low latency is critical
- Alerting/notifications

### Use Hybrid When:
- Need both real-time and historical views
- Accuracy vs speed tradeoff needed
- Complex event correlation

---

## Best Practices

### Batch Processing
1. **Partition data** for parallel processing
2. **Checkpoint** long-running jobs
3. **Monitor** job progress and failures
4. **Optimize** shuffle operations
5. **Schedule** during off-peak hours

### Stream Processing
1. **Design for failures** (checkpointing, replay)
2. **Handle late data** (watermarks, allowed lateness)
3. **Manage state size** (TTL, compaction)
4. **Monitor lag** (consumer behind producer)
5. **Test with realistic data** (volume, variety)

---

## Interview Questions

**Q: When would you choose batch over stream processing?**
> Batch: when latency isn't critical (hours OK), processing complete dataset, complex ML training, cost optimization, historical analysis. Stream: real-time requirements, monitoring, alerts, event-driven systems.

**Q: Explain windowing in stream processing.**
> Windows group unbounded streams into finite chunks. Types: Tumbling (fixed, non-overlapping), Sliding (fixed, overlapping), Session (gap-based). Needed to aggregate streaming data meaningfully.

**Q: How do you handle late-arriving data in stream processing?**
> Watermarks indicate when all data up to a time has arrived. Allow lateness window for late events. Either update previous results or drop late data. Design depends on accuracy vs latency tradeoff.

**Q: What is the Lambda architecture?**
> Combines batch layer (accurate, slow) and speed layer (fast, approximate). Batch recomputes complete views periodically, speed handles real-time. Serving layer merges both. Complexity: maintaining two codebases.

---

## Quick Reference

| Aspect | Batch | Stream |
|--------|-------|--------|
| **Tool** | Spark, Hadoop | Kafka, Flink |
| **Latency** | Hours | Seconds |
| **Data** | Bounded | Unbounded |
| **State** | Simple | Complex |
| **Best For** | ETL, Reports | Real-time |
