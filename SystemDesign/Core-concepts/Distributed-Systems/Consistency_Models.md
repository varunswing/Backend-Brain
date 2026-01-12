# Consistency Models in Distributed Systems

## Overview

Consistency models define rules for how data reads and writes behave across distributed nodes.

```
Strong Consistency                        Eventual Consistency
        │                                         │
        ▼                                         ▼
  All nodes see                           Nodes may show
  same data at                            different data
  same time                               temporarily
        │                                         │
        ▼                                         ▼
   Higher latency                          Lower latency
   Lower availability                      Higher availability
```

---

## Consistency Spectrum

```
Strongest ◄─────────────────────────────────────────────► Weakest

Linearizable → Sequential → Causal → Read-Your-Writes → Eventual
     │              │           │            │              │
     ▼              ▼           ▼            ▼              ▼
  Single         Global      Related     Your writes    Eventually
  timeline       order       events      visible to     converge
                             ordered     you
```

---

## Strong Consistency

### Linearizability

Every operation appears to execute atomically at a single point in time.

```
Timeline:
Client A: ────[Write X=1]─────────────────────────
Client B: ────────────────────[Read X]────────────
                                  │
                                  └── Always returns 1

All operations appear to happen in a single, global order.
```

**Characteristics:**
- Latest write always visible
- Real-time ordering
- Expensive (consensus required)

**Use Cases:**
- Leader election
- Distributed locks
- Financial transactions

### Sequential Consistency

All operations appear in some sequential order consistent with program order.

```
Client A: Write X=1, Write Y=2
Client B: Read Y, Read X

Valid orderings:
✓ Write X=1 → Write Y=2 → Read Y=2 → Read X=1
✓ Write X=1 → Read X=1 → Write Y=2 → Read Y=2
✗ Read Y=2 → Read X=0 (Y written after X, so X must be 1)
```

---

## Weaker Consistency Models

### Causal Consistency

Causally related operations are seen in the same order by all nodes.

```
Client A: Posts "Hello" (M1)
Client B: Sees M1, Replies "Hi" (M2)

All clients must see: M1 before M2 (causally related)
Independent messages can appear in any order.

Example violation:
✗ Client C sees: M2 "Hi" then M1 "Hello" (wrong order!)
```

**Implementation:**
```python
# Vector clocks track causality
class VectorClock:
    def __init__(self, node_id, num_nodes):
        self.node_id = node_id
        self.clock = [0] * num_nodes
    
    def increment(self):
        self.clock[self.node_id] += 1
        return self.clock.copy()
    
    def update(self, received_clock):
        for i in range(len(self.clock)):
            self.clock[i] = max(self.clock[i], received_clock[i])
        self.increment()
    
    def happens_before(self, other_clock):
        """Check if this event causally precedes other"""
        return all(a <= b for a, b in zip(self.clock, other_clock))
```

### Read-Your-Writes Consistency

A client always sees their own writes.

```
Client A: Write X=5
Client A: Read X → Must return 5 (or later value)

Other clients may see stale data, but you always see your writes.
```

**Implementation:**
```python
class ReadYourWritesClient:
    def __init__(self):
        self.last_write_timestamp = {}
    
    def write(self, key, value):
        timestamp = self.db.write(key, value)
        self.last_write_timestamp[key] = timestamp
    
    def read(self, key):
        min_timestamp = self.last_write_timestamp.get(key, 0)
        
        # Ensure we read from a replica that has our write
        return self.db.read(key, min_timestamp=min_timestamp)
```

### Monotonic Reads

Once you see a value, you never see an older value.

```
Client A:
  Read X → 5
  Read X → Must be ≥ 5 (not 3)

Prevents "going back in time"
```

**Implementation:**
```python
class MonotonicReadsClient:
    def __init__(self):
        self.last_read_timestamp = {}
    
    def read(self, key):
        min_timestamp = self.last_read_timestamp.get(key, 0)
        
        value, timestamp = self.db.read(key, min_timestamp=min_timestamp)
        self.last_read_timestamp[key] = timestamp
        
        return value
```

### Session Consistency

Combines read-your-writes and monotonic reads within a session.

```python
class SessionClient:
    def __init__(self, session_id):
        self.session_id = session_id
        self.session_timestamp = 0
    
    def write(self, key, value):
        timestamp = self.db.write(key, value)
        self.session_timestamp = max(self.session_timestamp, timestamp)
    
    def read(self, key):
        value, timestamp = self.db.read(key, min_timestamp=self.session_timestamp)
        self.session_timestamp = max(self.session_timestamp, timestamp)
        return value
```

---

## Eventual Consistency

All replicas converge to the same value eventually, given no new writes.

```
Time T0: Write X=1 to Node A
Time T1: Node A has X=1, Node B has X=0
Time T2: Node A has X=1, Node B has X=0 (replication in progress)
Time T3: Node A has X=1, Node B has X=1 (converged!)

No guarantee on how long convergence takes.
```

### Conflict Resolution

When concurrent writes occur:

```python
# Last-Write-Wins (LWW)
class LWWRegister:
    def __init__(self):
        self.value = None
        self.timestamp = 0
    
    def write(self, value, timestamp):
        if timestamp > self.timestamp:
            self.value = value
            self.timestamp = timestamp
    
    def merge(self, other):
        if other.timestamp > self.timestamp:
            self.value = other.value
            self.timestamp = other.timestamp

# Multi-Value (return all concurrent writes)
class MVRegister:
    def __init__(self):
        self.values = {}  # version -> value
    
    def write(self, value, version):
        # Remove versions that this write supersedes
        self.values = {v: val for v, val in self.values.items() 
                       if not version.supersedes(v)}
        self.values[version] = value
    
    def read(self):
        return list(self.values.values())  # Return all concurrent values
```

---

## CRDTs (Conflict-Free Replicated Data Types)

Data structures that automatically merge without conflicts.

### G-Counter (Grow-only Counter)

```python
class GCounter:
    def __init__(self, node_id, num_nodes):
        self.node_id = node_id
        self.counts = [0] * num_nodes
    
    def increment(self):
        self.counts[self.node_id] += 1
    
    def value(self):
        return sum(self.counts)
    
    def merge(self, other):
        for i in range(len(self.counts)):
            self.counts[i] = max(self.counts[i], other.counts[i])

# Usage
node_a = GCounter(0, 3)
node_b = GCounter(1, 3)

node_a.increment()  # [1, 0, 0]
node_b.increment()  # [0, 1, 0]
node_b.increment()  # [0, 2, 0]

node_a.merge(node_b)  # [1, 2, 0] → value = 3
```

### LWW-Register

```python
class LWWRegister:
    def __init__(self):
        self.value = None
        self.timestamp = 0
    
    def set(self, value):
        self.value = value
        self.timestamp = time.time()
    
    def merge(self, other):
        if other.timestamp > self.timestamp:
            self.value = other.value
            self.timestamp = other.timestamp
```

### OR-Set (Observed-Remove Set)

```python
class ORSet:
    def __init__(self):
        self.elements = {}  # element -> set of (node_id, counter)
        self.tombstones = {}  # element -> set of (node_id, counter)
    
    def add(self, element, node_id, counter):
        tag = (node_id, counter)
        self.elements.setdefault(element, set()).add(tag)
    
    def remove(self, element):
        if element in self.elements:
            # Tombstone all current tags
            self.tombstones[element] = self.elements[element].copy()
            del self.elements[element]
    
    def contains(self, element):
        if element not in self.elements:
            return False
        # Element exists if it has tags not in tombstones
        active_tags = self.elements[element] - self.tombstones.get(element, set())
        return len(active_tags) > 0
```

---

## Choosing Consistency Level

### Trade-offs

| Model | Latency | Availability | Complexity |
|-------|---------|--------------|------------|
| Linearizable | High | Low | High |
| Sequential | Medium | Medium | Medium |
| Causal | Medium | High | Medium |
| Eventual | Low | High | Low |

### Decision Guide

```
Need absolute correctness (banking)?
  → Linearizable

Need operations in order?
  → Sequential

Need related events ordered (social)?
  → Causal

Need users to see their writes?
  → Read-Your-Writes + Monotonic

Can tolerate temporary inconsistency?
  → Eventual
```

---

## Database Consistency Settings

### Cassandra

```python
# Write consistency
session.execute(query, consistency_level=ConsistencyLevel.QUORUM)

# Levels:
# ONE - Fast, weak consistency
# QUORUM - (N/2 + 1) nodes, balanced
# ALL - All nodes, strongest
# LOCAL_QUORUM - Quorum in local datacenter
```

### MongoDB

```python
# Read preference
collection.find({}, read_preference=ReadPreference.PRIMARY)

# Options:
# PRIMARY - Always read from primary (strong)
# PRIMARY_PREFERRED - Primary if available
# SECONDARY - Read from secondary (eventual)
# NEAREST - Lowest latency node
```

### DynamoDB

```python
# Strong consistency
response = table.get_item(
    Key={'id': '123'},
    ConsistentRead=True  # Strong consistency
)

# Eventual consistency (default)
response = table.get_item(
    Key={'id': '123'},
    ConsistentRead=False  # Eventual (faster, cheaper)
)
```

---

## Interview Questions

**Q: What is eventual consistency?**
> All replicas converge to same value eventually, given no new writes. Temporary inconsistency allowed. Used when availability > consistency. Examples: DNS, social media feeds.

**Q: Linearizability vs Sequential consistency?**
> Linearizable: operations appear in real-time order. Sequential: operations appear in some valid order. Linearizable is stricter - if write completes before read starts, read must see write.

**Q: How do you implement read-your-writes consistency?**
> Track write timestamps per key. On read, ensure replica has that timestamp or higher. Route to same replica, or pass version/timestamp in request.

**Q: What are CRDTs?**
> Data structures that merge automatically without conflicts. Examples: G-Counter, LWW-Register, OR-Set. Enable eventual consistency without coordination.

---

## Quick Reference

| Model | Guarantee | Use Case |
|-------|-----------|----------|
| **Linearizable** | Real-time order | Locks, elections |
| **Sequential** | Some valid order | Transactions |
| **Causal** | Related events ordered | Social feeds |
| **Read-Your-Writes** | See own writes | User sessions |
| **Monotonic** | No going back | Progress tracking |
| **Eventual** | Eventually same | Caching, DNS |
