# Message Queue System - System Design Interview

## Problem Statement
*"Design a distributed message queue like Apache Kafka or Amazon SQS that handles millions of messages per second with high availability and durability."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What queue patterns do we need?" → Producer-Consumer, Pub-Sub, Point-to-Point
- "Do we need message ordering?" → Yes, FIFO within partitions
- "Should messages persist?" → Yes, configurable retention (7 days default)
- "Do we need dead letter queues?" → Yes, for failed message handling
- "What delivery guarantees?" → At-least-once, exactly-once options

**Non-Functional Requirements:**
- "What's our throughput requirement?" → 1M+ messages/second peak
- "Expected latency?" → <10ms for message delivery
- "Availability requirement?" → 99.99% uptime
- "Message retention?" → 7 days default, configurable
- "Global distribution needed?" → Yes, multi-region

### Requirements Summary:
- **Throughput**: 1M+ messages/second, 5M+ peak bursts
- **Latency**: <10ms delivery, <1ms in-memory queues
- **Patterns**: Producer-Consumer, Pub-Sub, Request-Reply
- **Durability**: Persistent with configurable retention
- **Availability**: 99.99% uptime with failover

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Peak message rate: 1M messages/second
Average message size: 1KB
Peak throughput: 1M × 1KB = 1GB/second
Daily volume: 86.4B messages/day
```

### Storage Estimation:
```
Daily storage: 86.4B × 1KB = 86.4TB/day
7-day retention: 86.4TB × 7 = 604TB
3x replication: 604TB × 3 = 1.8PB
With indexes: ~2PB total storage
```

### Memory Requirements:
```
Active partitions: 1M partitions × 1KB = 1GB metadata
Message buffers: 60GB for 1-minute buffer
Connection state: 1GB for 100K producers
Total per broker: ~80GB memory
```

---

## Phase 3: High-Level Architecture (12 minutes)

### Architecture Evolution:
```
Step 1: [Producers] → [Single Broker] → [Consumers]
Step 2: Distributed Architecture
```

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Producer Apps│  │Admin Console│  │Consumer Apps│
│- SDKs       │  │- Monitoring │  │- Stream Proc│
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│          Load Balancer                  │
│- Routing  - Health Checks  - SSL       │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│        Message Broker Cluster           │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │Broker-1 │  │Broker-2 │  │Broker-3 │   │
│ │(Leader) │  │(Replica)│  │(Replica)│   │
│ │         │  │         │  │         │   │
│ │- Topics │  │- Sync   │  │- Backup │   │
│ │- Parts  │  │- Follow │  │- Recover│   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│         Coordination Services           │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │ZooKeeper│  │Schema   │  │Dead     │   │
│ │- Leader │  │Registry │  │Letter   │   │
│ │- Meta   │  │- Format │  │Queue    │   │
│ │- Config │  │- Version│  │- Retry  │   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            Storage Layer                │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │Kafka    │  │File     │  │Archive  │   │
│ │Logs     │  │System   │  │Storage  │   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
```

### Key Components:
- **Broker Cluster**: Message routing, persistence, replication
- **ZooKeeper**: Coordination, leader election, metadata
- **Producer/Consumer APIs**: Client libraries
- **Schema Registry**: Message format management

---

## Phase 4: Database Design (8 minutes)

### Core Data Structures:
- Topics, Partitions, Messages, Consumer Groups, Offsets

### Message Storage Schema:
```json
// Topic Configuration
{
  "topic_name": "user_events",
  "partition_count": 12,
  "replication_factor": 3,
  "retention_ms": 604800000,
  "cleanup_policy": "delete"
}

// Message Format
{
  "offset": 1000,
  "timestamp": 1704110400000,
  "key": "user_123",
  "value": {"event": "purchase", "amount": 99.99},
  "headers": {"source": "web_app"}
}
```

### ZooKeeper Schema:
```
/brokers/ids/0 → {"host": "broker-1", "port": 9092}
/topics/user_events/partitions/0/state → {"leader": 0, "isr": [0,1,2]}
/consumers/analytics_group/offsets/user_events/0 → 1000
```

### In-Memory Structures:
```java
class Partition {
    private Log log;                    // Message storage
    private long highWatermark;         // Committed offset
    private ReplicaManager replicas;
}
```

### Design Decisions:
- **Partition-based scaling**: Horizontal partitioning for parallelism
- **Immutable logs**: Append-only for performance
- **Offset tracking**: Consumer position for replay
- **Leader-follower replication**: High availability

---

## Phase 5: Critical Flow - Message Publishing (8 minutes)

### Step-by-Step Flow:

**1. Message Production**
```
Producer sends:
POST /topics/user_events
{
  "key": "user_123",
  "value": {"event": "purchase"},
  "partition": null
}
```

**2. Routing & Leader Selection**
```
Routing logic:
1. If partition specified → use it
2. If key provided → hash(key) % partition_count  
3. No key → round-robin
4. Find partition leader from ZooKeeper
5. Route to leader broker
```

**3. Leader Processing**
```
Leader broker:
1. Validate message format
2. Assign next offset
3. Append to log segment
4. Replicate to followers in ISR
5. Update high water mark after quorum ack
```

**4. Acknowledgment**
```
Based on acks setting:
- acks=0: No wait (fire and forget)
- acks=1: Leader ack only
- acks=all: Wait for all ISR replicas
```

### Technical Challenges:
**Ordering**: "FIFO within partition using message keys"
**Durability**: "acks=all for zero data loss"
**Performance**: "Batching and async replication"
**Failover**: "ZooKeeper-based leader election"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Single partition throughput** - disk I/O limits
2. **ZooKeeper coordination** - metadata bottleneck
3. **Network bandwidth** - replication overhead
4. **Consumer lag** - slow processing

### Scaling Solutions:
**Horizontal Scaling:**
- Add more brokers for more partitions
- Increase partition count for parallelism
- Scale consumer groups

**Performance Optimization:**
- Message batching for throughput
- Compression (snappy, gzip)
- Zero-copy transfers
- SSD storage for hot data

**Advanced Features:**
- Schema registry for compatibility
- Exactly-once semantics
- Stream processing integration

### Trade-offs:
- **Throughput vs Latency**: Batching adds latency
- **Durability vs Speed**: More replicas = safer but slower
- **Ordering vs Parallelism**: Single partition limits scale

---

## Success Metrics:
- **Throughput**: >1M messages/second sustained
- **Latency**: <10ms P99 delivery
- **Availability**: 99.99% uptime
- **Durability**: Zero message loss with acks=all
- **Consumer Lag**: <1 second during normal ops

**🎯 Demonstrates distributed systems, consensus protocols, and high-throughput messaging infrastructure.**