# CAP Theorem: Complete Guide

## 📚 Table of Contents
1. [Introduction](#introduction)
2. [The Three Components](#the-three-components)
3. [Why Only Two?](#why-only-two)
4. [The Three Combinations](#the-three-combinations)
5. [Real-World Examples](#real-world-examples)
6. [PACELC Theorem](#pacelc-theorem)
7. [Practical Implications](#practical-implications)
8. [Interview Quick Reference](#interview-quick-reference)

---

## Introduction

The **CAP Theorem** (also known as Brewer's Theorem, proposed by Eric Brewer in 2000) states that it is **impossible for a distributed data store to simultaneously provide more than two out of the following three guarantees**:

1. **C**onsistency
2. **A**vailability
3. **P**artition Tolerance

This theorem is fundamental to understanding distributed systems design and the trade-offs involved.

---

## The Three Components

### 🔵 Consistency (C)
**Every read receives the most recent write or an error.**

- All nodes see the same data at the same time
- After a write completes, all subsequent reads return that value
- Linearizability: operations appear to be instantaneous

```
Client A writes: x = 5
Client B reads x → must get 5 (not stale data)
```

**Example**: In a banking system, your balance must be consistent across all nodes immediately after a transaction.

---

### 🟢 Availability (A)
**Every request receives a (non-error) response, without guarantee that it contains the most recent write.**

- System is always responsive
- Every request gets a response (success or failure)
- No downtime, even during failures

```
Client sends request → Always gets a response
(even if data might be slightly stale)
```

**Example**: A social media feed that always loads, even if some posts are a few seconds behind.

---

### 🟡 Partition Tolerance (P)
**The system continues to operate despite network partitions (communication breakdowns between nodes).**

- System works even when network failures occur
- Nodes can't communicate but system keeps functioning
- Essential for distributed systems over unreliable networks

```
Node A ←✕→ Node B (network partition)
System must still handle requests to both nodes
```

**Example**: Data centers in different regions continue operating even if the connection between them fails.

---

## Why Only Two?

In a distributed system, **network partitions are inevitable**. When a partition occurs, you must choose between:

### The Fundamental Trade-off

```
During a network partition:
┌─────────────────────────────────────────────────┐
│                                                 │
│   Node A                        Node B          │
│   ┌─────┐        ✕ PARTITION ✕    ┌─────┐      │
│   │Data │    ←─────────────────→  │Data │      │
│   └─────┘                         └─────┘      │
│                                                 │
│   Client writes to Node A...                    │
│   Client reads from Node B...                   │
│                                                 │
│   CHOICE:                                       │
│   • Return possibly stale data (Availability)  │
│   • Wait/Error until partition heals (Consistency)│
│                                                 │
└─────────────────────────────────────────────────┘
```

**Since partitions are unavoidable in distributed systems, you effectively choose between Consistency and Availability.**

---

## The Three Combinations

### 1. CP Systems (Consistency + Partition Tolerance)

**Sacrifices**: Availability during partitions

**Behavior**: When a partition occurs, the system may refuse requests to maintain consistency.

**Characteristics**:
- Guarantees all nodes have the same data
- May return errors or timeout during network issues
- Waits for partition to heal before responding

**Use Cases**:
- Banking systems
- Inventory management
- Financial transactions
- Any system where data accuracy is critical

**Example Systems**:
| System | Description |
|--------|-------------|
| **HBase** | Consistent reads/writes, unavailable during partition |
| **MongoDB** (with majority writes) | Strong consistency mode |
| **Redis Cluster** | Consistency over availability |
| **Zookeeper** | Coordination service requiring consistency |
| **Google Spanner** | Globally consistent with TrueTime |

---

### 2. AP Systems (Availability + Partition Tolerance)

**Sacrifices**: Consistency (allows eventual consistency)

**Behavior**: System remains available during partitions but may return stale data.

**Characteristics**:
- Always responds to requests
- May return outdated data during partitions
- Eventually becomes consistent when partition heals
- Uses conflict resolution strategies

**Use Cases**:
- Social media feeds
- Shopping carts
- DNS systems
- Content delivery
- Systems where slight staleness is acceptable

**Example Systems**:
| System | Description |
|--------|-------------|
| **Cassandra** | Always available, eventually consistent |
| **DynamoDB** | High availability, configurable consistency |
| **CouchDB** | Availability-first with conflict resolution |
| **Riak** | Distributed with eventual consistency |
| **DNS** | Always resolves, may have stale records |

---

### 3. CA Systems (Consistency + Availability)

**Sacrifices**: Partition Tolerance

**Reality**: In a truly distributed system over a network, **partitions will happen**. Therefore, CA systems are essentially **single-node systems** or systems in a single network segment.

**Characteristics**:
- All nodes see same data
- Always available
- Only works when no partition occurs
- Essentially a single-server or tightly coupled system

**Example Systems**:
| System | Description |
|--------|-------------|
| **Traditional RDBMS** | Single-node PostgreSQL, MySQL |
| **Single-server systems** | Non-distributed deployments |

> ⚠️ **Note**: True CA systems are rare in distributed environments because network partitions are inevitable.

---

## Real-World Examples

### DynamoDB (Configurable)
```
┌────────────────────────────────────────┐
│              DynamoDB                   │
├────────────────────────────────────────┤
│ • Default: Eventually consistent (AP)  │
│ • Optional: Strongly consistent (CP)   │
│ • Choose per-request basis             │
└────────────────────────────────────────┘
```

### Google Spanner (CP with High Availability)
```
┌────────────────────────────────────────┐
│            Google Spanner              │
├────────────────────────────────────────┤
│ • Global strong consistency            │
│ • Uses TrueTime (GPS + atomic clocks)  │
│ • High availability through redundancy │
│ • Technically CP but ~99.999% available│
└────────────────────────────────────────┘
```

### Cassandra (AP)
```
┌────────────────────────────────────────┐
│             Cassandra                  │
├────────────────────────────────────────┤
│ • Always available                     │
│ • Tunable consistency levels           │
│ • Eventual consistency by default      │
│ • Great for write-heavy workloads      │
└────────────────────────────────────────┘
```

---

## PACELC Theorem

The **PACELC Theorem** extends CAP by addressing what happens when the system is running normally (no partition):

```
PACELC: if Partition → (Availability or Consistency)
        else          → (Latency or Consistency)
```

### The Extension

| Condition | Trade-off |
|-----------|-----------|
| **During Partition (P)** | Choose between **A** (Availability) or **C** (Consistency) |
| **Else (E)** - Normal operation | Choose between **L** (Latency) or **C** (Consistency) |

### PACELC Categories

| System | During Partition | Normal Operation | Classification |
|--------|------------------|------------------|----------------|
| **DynamoDB** | A (Available) | L (Low Latency) | PA/EL |
| **Cassandra** | A (Available) | L (Low Latency) | PA/EL |
| **MongoDB** | C (Consistent) | C (Consistent) | PC/EC |
| **PNUTS** | A (Available) | C (Consistent) | PA/EC |
| **VoltDB** | C (Consistent) | L (Low Latency) | PC/EL |

### Why PACELC Matters

CAP only considers failure scenarios. PACELC also considers the normal case:
- **PA/EL systems**: Prioritize performance (availability + low latency)
- **PC/EC systems**: Prioritize correctness (consistency always)
- **PA/EC systems**: Available during failures, consistent normally
- **PC/EL systems**: Consistent during failures, fast normally

---

## Practical Implications

### Choosing Your Trade-offs

| Scenario | Recommended | Reasoning |
|----------|-------------|-----------|
| **Financial Transactions** | CP | Cannot afford inconsistent data |
| **Social Media Feed** | AP | Slightly stale posts are acceptable |
| **Shopping Cart** | AP | Cart should always work, merge later |
| **Inventory Count** | CP | Overselling is costly |
| **User Sessions** | AP | Session loss is bad, staleness is okay |
| **Medical Records** | CP | Data accuracy is critical |
| **Gaming Leaderboard** | AP | Eventual consistency is fine |

### Design Strategies

#### For CP Systems:
```
1. Use consensus protocols (Raft, Paxos)
2. Implement quorum-based reads/writes
3. Accept higher latency for consistency
4. Plan for unavailability during partitions
```

#### For AP Systems:
```
1. Implement conflict resolution (LWW, vector clocks)
2. Design for eventual consistency
3. Use idempotent operations
4. Handle merge conflicts gracefully
```

### Consistency Levels (Configurable Systems)

Many modern databases allow tuning consistency:

```
┌─────────────────────────────────────────────────────┐
│                Consistency Spectrum                  │
├─────────────────────────────────────────────────────┤
│  WEAK          EVENTUAL         STRONG              │
│   ◄──────────────────────────────────────────────►  │
│                                                      │
│  • ONE           • QUORUM          • ALL            │
│  • Fast          • Balanced        • Slow           │
│  • May be stale  • Usually fresh   • Always fresh   │
│  • High avail    • Good avail      • Lower avail    │
└─────────────────────────────────────────────────────┘
```

---

## Interview Quick Reference

### CAP at a Glance

```
        C (Consistency)
           /\
          /  \
         /    \
        / CP   \
       /________\
      / CA   AP  \
     /____________\
    A              P
(Availability) (Partition Tolerance)

Pick 2 out of 3:
• CP: Consistent but may be unavailable
• AP: Available but may be inconsistent  
• CA: Both, but no partition tolerance (single node)
```

### Quick Answers

**Q: What is the CAP theorem?**
> A distributed system can only guarantee 2 of 3: Consistency, Availability, and Partition Tolerance. Since partitions are inevitable, you choose between C and A.

**Q: Why can't we have all three?**
> During a network partition, nodes can't communicate. You must either wait for consistency (sacrificing availability) or respond with potentially stale data (sacrificing consistency).

**Q: Is CA possible?**
> Only in single-node systems. In distributed systems, partitions will occur, so CA is not practically achievable.

**Q: What's the difference between CP and AP?**
> CP systems refuse to respond during partitions to ensure consistency. AP systems always respond but may return stale data.

**Q: What is PACELC?**
> An extension of CAP that also considers the latency vs. consistency trade-off during normal (non-partition) operation.

### Classification Cheat Sheet

| Database | Type | Notes |
|----------|------|-------|
| PostgreSQL (single) | CA | Not distributed |
| MongoDB | CP | Consistency first |
| Cassandra | AP | Availability first |
| DynamoDB | AP/CP | Configurable |
| Redis Cluster | CP | Consistency focused |
| CouchDB | AP | Availability first |
| HBase | CP | Consistency focused |
| Riak | AP | Availability first |
| Spanner | CP | With high availability |
| Zookeeper | CP | Coordination service |

---

## Summary

1. **CAP Theorem**: In distributed systems, you can only have 2 of 3: Consistency, Availability, Partition Tolerance

2. **Partitions are inevitable**: In practice, you choose between Consistency and Availability

3. **CP Systems**: Choose consistency, may be unavailable during partitions (banking, inventory)

4. **AP Systems**: Choose availability, accept eventual consistency (social media, caching)

5. **PACELC**: Extends CAP to consider latency vs. consistency during normal operation

6. **Modern systems**: Often provide configurable consistency levels (DynamoDB, Cassandra)

**Key Takeaway**: Understand your application's requirements and choose the right trade-offs. There's no universally correct answer—it depends on your specific use case.
