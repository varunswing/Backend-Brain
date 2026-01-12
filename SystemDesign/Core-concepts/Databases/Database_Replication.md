# Database Replication

## What is Replication?

Replication is the process of copying and maintaining database objects in multiple databases that make up a distributed system.

```
┌──────────────┐         ┌──────────────┐
│   Primary    │ ──────→ │   Replica    │
│  (Master)    │  sync   │   (Slave)    │
└──────────────┘         └──────────────┘
```

---

## Why Replicate?

| Goal | How Replication Helps |
|------|----------------------|
| **High Availability** | Failover to replica if primary fails |
| **Read Scalability** | Distribute reads across replicas |
| **Geographic Distribution** | Place data closer to users |
| **Disaster Recovery** | Backup in different location |
| **Analytics Isolation** | Run heavy queries on replica |

---

## Replication Topologies

### 1. Primary-Replica (Master-Slave)

```
                 ┌─────────┐
                 │ Primary │ ← All writes
                 └────┬────┘
          ┌──────────┼──────────┐
          ▼          ▼          ▼
     ┌─────────┐┌─────────┐┌─────────┐
     │Replica 1││Replica 2││Replica 3│ ← Reads distributed
     └─────────┘└─────────┘└─────────┘
```

**Characteristics**:
- One primary accepts writes
- Multiple replicas serve reads
- Replicas are read-only

**Use Cases**:
- Read-heavy workloads
- Reporting/analytics separation
- Simple high availability

### 2. Multi-Primary (Master-Master)

```
     ┌─────────┐           ┌─────────┐
     │Primary 1│ ←───────→ │Primary 2│
     │  (R/W)  │   sync    │  (R/W)  │
     └─────────┘           └─────────┘
```

**Characteristics**:
- Multiple nodes accept writes
- Bi-directional synchronization
- Conflict resolution required

**Use Cases**:
- Geographic distribution
- Write scalability
- No single point of failure for writes

**Challenges**:
- Write conflicts
- Complexity
- Eventually consistent

### 3. Chain Replication

```
┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐
│ Primary │───→│ Relay 1 │───→│ Relay 2 │───→│  Tail   │
│ (Head)  │    │         │    │         │    │(Reads)  │
└─────────┘    └─────────┘    └─────────┘    └─────────┘
  Writes                                        Reads
```

**Characteristics**:
- Writes at head
- Reads at tail
- Strong consistency

---

## Synchronization Methods

### 1. Synchronous Replication

```
Client      Primary       Replica
  │            │             │
  │──Write────→│             │
  │            │──Replicate─→│
  │            │←───ACK──────│
  │←───ACK─────│             │
```

**Pros**:
- Strong consistency
- No data loss on failover
- Read-your-writes guaranteed

**Cons**:
- Higher latency (wait for replica)
- Throughput limited by slowest replica
- Availability reduced (replica down = writes blocked)

### 2. Asynchronous Replication

```
Client      Primary       Replica
  │            │             │
  │──Write────→│             │
  │←───ACK─────│             │
  │            │──Replicate─→│ (later)
  │            │             │
```

**Pros**:
- Lower latency
- Higher throughput
- Replica failure doesn't block writes

**Cons**:
- Eventual consistency
- Potential data loss on failover
- Replication lag

### 3. Semi-Synchronous Replication

```
Wait for at least ONE replica to acknowledge.
If no replica responds within timeout, proceed asynchronously.
```

**Pros**:
- Balance of durability and performance
- At least one replica has data
- Graceful degradation

---

## Replication Lag

The delay between write on primary and visibility on replica.

```
Timeline:
Primary:  ──[Write]───────────────────────────────────
Replica:  ────────────────[Replicated]────────────────
                     │←─── Lag ───→│
```

### Causes
- Network latency
- Replica overloaded
- Large transactions
- Long-running queries on replica

### Problems
1. **Stale reads**: User sees old data
2. **Read-your-writes violation**: Write, then read returns old value
3. **Non-monotonic reads**: Read new value, then old value

### Solutions

**1. Read-Your-Writes Consistency**
```python
def get_user(user_id, last_write_timestamp):
    # Check if replica is caught up
    if replica.replication_position >= last_write_timestamp:
        return replica.query(user_id)
    else:
        return primary.query(user_id)  # Fallback to primary
```

**2. Monotonic Reads**
```python
# Always route user to same replica (sticky sessions)
replica = get_replica_for_user(user_id)
return replica.query(user_id)
```

**3. Consistent Prefix Reads**
```python
# Ensure causally related writes are read in order
# Use logical timestamps or version vectors
```

---

## Failover Strategies

### Automatic Failover

```
┌──────────────┐    Heartbeat     ┌─────────────┐
│   Primary    │ ←─────────────── │  Watchdog   │
└──────┬───────┘                  └──────┬──────┘
       │                                 │
       │ (Primary fails)                 │ Detects failure
       ▼                                 ▼
┌──────────────┐                  Promotes Replica
│   Replica    │ ────────────────→ to Primary
└──────────────┘
```

**Steps**:
1. Detect primary failure (heartbeat timeout)
2. Elect new primary (consensus)
3. Redirect traffic to new primary
4. Rebuild replica from new primary

### Manual Failover
- Used for planned maintenance
- Controlled switch with zero data loss
- Drain connections before switch

---

## Replication in Different Databases

### MySQL
```sql
-- Primary configuration
[mysqld]
server-id = 1
log_bin = mysql-bin
binlog_format = ROW

-- Replica configuration
[mysqld]
server-id = 2
relay_log = relay-bin
read_only = ON

-- Set up replication
CHANGE MASTER TO
    MASTER_HOST='primary.example.com',
    MASTER_USER='repl_user',
    MASTER_PASSWORD='password',
    MASTER_LOG_FILE='mysql-bin.000001',
    MASTER_LOG_POS=0;
START SLAVE;
```

### PostgreSQL
```sql
-- Primary: postgresql.conf
wal_level = replica
max_wal_senders = 10
synchronous_standby_names = 'replica1'

-- Replica: Create standby.signal file
-- Replica: postgresql.conf
primary_conninfo = 'host=primary port=5432 user=repl'
```

### MongoDB
```javascript
// Initialize replica set
rs.initiate({
  _id: "myReplicaSet",
  members: [
    { _id: 0, host: "primary:27017" },
    { _id: 1, host: "secondary1:27017" },
    { _id: 2, host: "secondary2:27017" }
  ]
})

// Read preference
db.collection.find().readPref("secondary")
```

---

## Conflict Resolution (Multi-Primary)

### Types of Conflicts
```
Primary 1: UPDATE users SET name = 'Alice' WHERE id = 1;
Primary 2: UPDATE users SET name = 'Bob' WHERE id = 1;

Result: Conflict! Which value wins?
```

### Resolution Strategies

**1. Last Write Wins (LWW)**
```
Use timestamp - most recent write wins
Pros: Simple, deterministic
Cons: May lose valid updates
```

**2. Merge**
```
Combine changes if possible
Pros: Preserves both updates
Cons: Not always possible
```

**3. Application-Level Resolution**
```
Let application decide based on business logic
Pros: Correct for domain
Cons: Complexity in application
```

**4. Conflict-Free Replicated Data Types (CRDTs)**
```
Use data structures that automatically merge
Pros: Always converges
Cons: Limited data types
```

---

## Best Practices

### Design
- Choose consistency level based on requirements
- Plan for replication lag
- Design for eventual consistency where possible
- Implement proper failover testing

### Operations
- Monitor replication lag
- Test failover regularly
- Use connection pooling
- Implement circuit breakers for failover

### Application
```python
# Read from replica for non-critical reads
def get_product_catalog():
    return replica.query("SELECT * FROM products")

# Read from primary for consistency-critical reads
def get_user_balance(user_id):
    return primary.query("SELECT balance FROM accounts WHERE id = ?", user_id)

# Write to primary
def transfer_money(from_id, to_id, amount):
    primary.execute("UPDATE accounts SET balance = balance - ? WHERE id = ?", amount, from_id)
    primary.execute("UPDATE accounts SET balance = balance + ? WHERE id = ?", amount, to_id)
```

---

## Interview Questions

**Q: What is database replication and why is it used?**
> Replication copies data across multiple database instances for: high availability (failover), read scalability (distribute reads), geographic distribution (low latency), and disaster recovery (backups).

**Q: Explain synchronous vs asynchronous replication.**
> Synchronous: wait for replica ACK before confirming write. Strong consistency, higher latency. Asynchronous: confirm write immediately, replicate later. Lower latency, eventual consistency, potential data loss.

**Q: How do you handle replication lag?**
> Strategies: read-your-writes (track write position, read from primary if replica behind), monotonic reads (sticky sessions), monitor lag, adjust consistency requirements per query.

**Q: What happens during failover?**
> Detect primary failure (heartbeat), elect new primary (may lose uncommitted async writes), redirect traffic, rebuild replica. Automatic failover needs consensus to prevent split-brain.

---

## Quick Reference

| Aspect | Synchronous | Asynchronous |
|--------|-------------|--------------|
| Consistency | Strong | Eventual |
| Latency | Higher | Lower |
| Data Loss Risk | None | Possible |
| Availability | Lower | Higher |
| Use Case | Financial | Social media |

| Topology | Writes | Reads | Complexity |
|----------|--------|-------|------------|
| Primary-Replica | Single | Scaled | Low |
| Multi-Primary | Scaled | Scaled | High |
| Chain | Single | Single (tail) | Medium |
