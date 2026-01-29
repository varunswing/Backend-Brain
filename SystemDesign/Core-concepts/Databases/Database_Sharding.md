# Database Sharding

## Overview
Database sharding is a method of horizontal partitioning that splits large databases across multiple servers or database instances. This document covers sharding strategies, implementation patterns, challenges, and best practices for scaling databases horizontally.

## What is Sharding?

### Definition
Sharding is a database architecture pattern that involves breaking up large databases into smaller, more manageable pieces called shards. Each shard is a separate database that contains a subset of the total data.

### Key Concepts
- **Shard**: Individual database instance containing a subset of data
- **Shard Key**: The field used to determine which shard contains specific data
- **Shard Function**: Algorithm that maps shard keys to specific shards
- **Routing Layer**: Component that directs queries to appropriate shards

### Sharding vs Other Scaling Techniques

| Technique | Description | Pros | Cons |
|-----------|-------------|------|------|
| **Vertical Scaling** | Add more power to existing server | Simple, no code changes | Hardware limits, single point of failure |
| **Read Replicas** | Create read-only copies | Scales read operations | Doesn't help with writes, eventual consistency |
| **Partitioning** | Split tables within same database | Better performance | Limited by single server capacity |
| **Sharding** | Split data across multiple databases | Scales reads and writes | Complex implementation |

---

## Sharding vs Partitioning: Complete Comparison

### Key Differences

| Aspect | Partitioning | Sharding |
|--------|-------------|----------|
| **Scope** | Within single database instance | Across multiple database instances |
| **Server** | Single server | Multiple servers |
| **Management** | Database handles internally | Application/middleware handles |
| **Scalability** | Limited by server capacity | Theoretically unlimited |
| **Complexity** | Lower | Higher |
| **Data Location** | Same physical machine | Different physical machines |

### The 4 Combinations Matrix

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    SHARDING vs PARTITIONING MATRIX                                   │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│                          │  NOT Partitioned          │  Partitioned                │
│  ────────────────────────┼───────────────────────────┼─────────────────────────────│
│                          │                           │                             │
│  NOT Sharded             │  Single DB, Single Table  │  Single DB, Split Tables    │
│  (Single Server)         │  ● Basic setup            │  ● Vertical partitioning    │
│                          │  ● No scaling benefits    │  ● Horizontal partitioning  │
│                          │  ● Simple operations      │  ● Better query performance │
│                          │                           │  ● Still limited by 1 server│
│  ────────────────────────┼───────────────────────────┼─────────────────────────────│
│                          │                           │                             │
│  Sharded                 │  Multiple DBs, No split   │  Multiple DBs + Split       │
│  (Multiple Servers)      │  ● Read replicas pattern  │  ● Maximum scalability      │
│                          │  ● Scales reads only      │  ● Scales reads AND writes  │
│                          │  ● Write bottleneck       │  ● Best performance         │
│                          │                           │  ● Most complex             │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Detailed Comparison Table

| Configuration | Sharded? | Partitioned? | Read Scaling | Write Scaling | Complexity | Use Case |
|---------------|----------|--------------|--------------|---------------|------------|----------|
| **Basic Single DB** | ❌ No | ❌ No | ❌ Poor | ❌ Poor | ✅ Low | Small apps, prototypes, <100K records |
| **Partitioned Single DB** | ❌ No | ✅ Yes | ⚠️ Moderate | ⚠️ Moderate | ⚠️ Medium | Medium traffic, <10M records, single server sufficient |
| **Sharded (No Partition)** | ✅ Yes | ❌ No | ✅ Good | ⚠️ Limited | ⚠️ Medium | Read-heavy workloads, caching layer pattern |
| **Sharded + Partitioned** | ✅ Yes | ✅ Yes | ✅ Excellent | ✅ Excellent | ❌ High | High traffic, >10M records, large scale systems |

### Detailed Breakdown of Each Configuration

#### 1. Not Sharded + Not Partitioned (Basic)
```
┌───────────────────────────────────┐
│         SINGLE SERVER             │
│  ┌─────────────────────────────┐  │
│  │      SINGLE DATABASE        │  │
│  │  ┌───────────────────────┐  │  │
│  │  │    users table        │  │  │
│  │  │    (all 10M rows)     │  │  │
│  │  └───────────────────────┘  │  │
│  └─────────────────────────────┘  │
└───────────────────────────────────┘

Characteristics:
├── Reads: Limited by single server I/O
├── Writes: Limited by single server I/O
├── Queries: Full table scans on large tables
├── Joins: Easy (all data local)
├── Transactions: Simple ACID
└── Failure: Single point of failure
```

**When to Use:**
- Small applications
- Development/testing environments
- Data size < 100K records
- Traffic < 100 requests/second

---

#### 2. Not Sharded + Partitioned (Vertical/Horizontal Partitioning)
```
┌───────────────────────────────────┐
│         SINGLE SERVER             │
│  ┌─────────────────────────────┐  │
│  │      SINGLE DATABASE        │  │
│  │                             │  │
│  │  ┌─────────┐ ┌─────────┐   │  │
│  │  │users_p1 │ │users_p2 │   │  │  ← Horizontal
│  │  │(A-M)    │ │(N-Z)    │   │  │    Partitioning
│  │  └─────────┘ └─────────┘   │  │
│  │                             │  │
│  │  ┌─────────┐ ┌─────────┐   │  │
│  │  │users_p3 │ │users_p4 │   │  │
│  │  │(2023)   │ │(2024)   │   │  │
│  │  └─────────┘ └─────────┘   │  │
│  └─────────────────────────────┘  │
└───────────────────────────────────┘

Characteristics:
├── Reads: Improved (partition pruning)
├── Writes: Improved (parallel writes to partitions)
├── Queries: Efficient if partition key in WHERE
├── Joins: Still easy (same server)
├── Transactions: Standard ACID
└── Failure: Still single point of failure
```

**Partitioning Types:**
| Type | Description | Example |
|------|-------------|---------|
| **Range** | By value ranges | `date >= '2024-01-01'` |
| **List** | By specific values | `country IN ('US', 'UK')` |
| **Hash** | By hash of column | `hash(user_id) % 4` |
| **Composite** | Combination | Range + Hash |

**When to Use:**
- Single server has sufficient capacity
- Query patterns align with partition key
- Data size 100K - 10M records
- Need query optimization without complexity

---

#### 3. Sharded + Not Partitioned (Distributed without internal partition)
```
┌────────────────┐  ┌────────────────┐  ┌────────────────┐
│   SERVER 1     │  │   SERVER 2     │  │   SERVER 3     │
│  ┌──────────┐  │  │  ┌──────────┐  │  │  ┌──────────┐  │
│  │ Shard 1  │  │  │  │ Shard 2  │  │  │  │ Shard 3  │  │
│  │          │  │  │  │          │  │  │  │          │  │
│  │ users    │  │  │  │ users    │  │  │  │ users    │  │
│  │ (A-H)    │  │  │  │ (I-P)    │  │  │  │ (Q-Z)    │  │
│  │          │  │  │  │          │  │  │  │          │  │
│  └──────────┘  │  └──────────┘  │  │  └──────────┘  │
└────────────────┘  └────────────────┘  └────────────────┘

         ▲                 ▲                 ▲
         │                 │                 │
         └─────────────────┼─────────────────┘
                           │
                  ┌────────────────┐
                  │ Shard Router   │
                  │ (Application)  │
                  └────────────────┘

Characteristics:
├── Reads: Scales horizontally (add more shards)
├── Writes: Distributed BUT each shard still single table
├── Queries: Cross-shard queries expensive
├── Joins: Complex (cross-shard joins)
├── Transactions: Distributed transactions needed
└── Failure: Better isolation per shard
```

**This is similar to Read Replicas when:**
- All shards have same data (full replication)
- Application routes reads to any shard
- Writes go to primary only

**When to Use:**
- Read-heavy workloads (90%+ reads)
- Data fits reasonably per shard
- Simple sharding without partition complexity

---

#### 4. Sharded + Partitioned (Maximum Scalability)
```
┌─────────────────────────┐  ┌─────────────────────────┐  ┌─────────────────────────┐
│       SERVER 1          │  │       SERVER 2          │  │       SERVER 3          │
│  ┌───────────────────┐  │  │  ┌───────────────────┐  │  │  ┌───────────────────┐  │
│  │     Shard 1       │  │  │  │     Shard 2       │  │  │  │     Shard 3       │  │
│  │  ┌─────┐ ┌─────┐  │  │  │  │  ┌─────┐ ┌─────┐  │  │  │  │  ┌─────┐ ┌─────┐  │  │
│  │  │ P1  │ │ P2  │  │  │  │  │  │ P1  │ │ P2  │  │  │  │  │  │ P1  │ │ P2  │  │  │
│  │  │2023 │ │2024 │  │  │  │  │  │2023 │ │2024 │  │  │  │  │  │2023 │ │2024 │  │  │
│  │  └─────┘ └─────┘  │  │  │  │  └─────┘ └─────┘  │  │  │  │  └─────┘ └─────┘  │  │
│  │  ┌─────┐ ┌─────┐  │  │  │  │  ┌─────┐ ┌─────┐  │  │  │  │  ┌─────┐ ┌─────┐  │  │
│  │  │ P3  │ │ P4  │  │  │  │  │  │ P3  │ │ P4  │  │  │  │  │  │ P3  │ │ P4  │  │  │
│  │  │arch │ │hot  │  │  │  │  │  │arch │ │hot  │  │  │  │  │  │arch │ │hot  │  │  │
│  │  └─────┘ └─────┘  │  │  │  │  └─────┘ └─────┘  │  │  │  │  └─────┘ └─────┘  │  │
│  └───────────────────┘  │  └───────────────────┘  │  │  └───────────────────┘  │
└─────────────────────────┘  └─────────────────────────┘  └─────────────────────────┘

Characteristics:
├── Reads: Maximum scalability (shards × partitions)
├── Writes: Maximum scalability (parallel across all)
├── Queries: Optimal with proper shard key + partition key
├── Joins: Most complex (cross-shard + cross-partition)
├── Transactions: Most complex (distributed + partitioned)
└── Failure: Best isolation (shard + partition level)
```

**When to Use:**
- Very large datasets (>100M records)
- High traffic (>10K requests/second)
- Need both horizontal and vertical scaling
- Large-scale systems (social media, e-commerce)

---

### Decision Matrix

```
                                    DATA SIZE
                    Small (<1M)          Large (>10M)
                 ┌─────────────────┬─────────────────┐
    Low          │                 │                 │
    (<100 QPS)   │  No Sharding    │  Partitioning   │
                 │  No Partitioning│  Only           │
    TRAFFIC      │                 │                 │
                 ├─────────────────┼─────────────────┤
    High         │                 │                 │
    (>1K QPS)    │  Sharding Only  │  Sharding +     │
                 │  (Read Replicas)│  Partitioning   │
                 │                 │                 │
                 └─────────────────┴─────────────────┘
```

### Performance Comparison

| Metric | Basic | Partitioned | Sharded | Sharded + Partitioned |
|--------|-------|-------------|---------|----------------------|
| **Read Throughput** | 1x | 2-5x | 3-10x | 10-100x |
| **Write Throughput** | 1x | 1.5-3x | 3-10x | 10-100x |
| **Query Latency** | Baseline | 30-50% better | Variable | Best (with proper keys) |
| **Storage Capacity** | Limited | Limited | High | Highest |
| **Operational Complexity** | Low | Medium | High | Very High |
| **Development Complexity** | Low | Low-Medium | High | Very High |

### Real-World Examples

| System | Configuration | Reason |
|--------|---------------|--------|
| **Small Blog** | Basic | Low traffic, simple queries |
| **Enterprise ERP** | Partitioned | Large tables, time-based queries |
| **Social Network** | Sharded | User-based access patterns |
| **Global E-commerce** | Sharded + Partitioned | Massive scale, geographic distribution |
| **Analytics Platform** | Partitioned | Time-series data, range queries |
| **Gaming Platform** | Sharded + Partitioned | Real-time + historical data |

---

## Sharding Strategies

### 1. Range-Based Sharding (Horizontal Partitioning)

**Definition**: Distribute data based on ranges of the shard key values.

**Example**:
```
User ID Ranges:
├── Shard 1: User IDs 1-1,000,000
├── Shard 2: User IDs 1,000,001-2,000,000
├── Shard 3: User IDs 2,000,001-3,000,000
└── Shard 4: User IDs 3,000,001-4,000,000
```

**Implementation**:
```python
def get_shard_by_range(user_id):
    if user_id <= 1000000:
        return "shard_1"
    elif user_id <= 2000000:
        return "shard_2"
    elif user_id <= 3000000:
        return "shard_3"
    else:
        return "shard_4"
```

**Advantages**:
- Simple to implement and understand
- Range queries are efficient
- Easy to add new shards for new ranges
- Good for time-series data

**Disadvantages**:
- Uneven data distribution (hotspots)
- Difficult to rebalance existing data
- Sequential access patterns can overload single shard
- Requires knowledge of data distribution

**Best Use Cases**:
- Time-series data (logs, metrics)
- Sequential data (order IDs, timestamps)
- When range queries are common

### 2. Hash-Based Sharding

**Definition**: Use a hash function on the shard key to determine which shard contains the data.

**Example**:
```
Hash Function: hash(user_id) % number_of_shards
├── hash(12345) % 4 = 1 → Shard 1
├── hash(67890) % 4 = 2 → Shard 2
├── hash(11111) % 4 = 3 → Shard 3
└── hash(22222) % 4 = 0 → Shard 4
```

**Implementation**:
```python
import hashlib

def get_shard_by_hash(user_id, num_shards=4):
    hash_value = int(hashlib.md5(str(user_id).encode()).hexdigest(), 16)
    return f"shard_{hash_value % num_shards + 1}"
```

**Advantages**:
- Even data distribution
- No hotspots for random access patterns
- Simple routing logic
- Good for point lookups

**Disadvantages**:
- Range queries require checking all shards
- Difficult to add/remove shards (resharding)
- No locality for related data
- Hash function changes affect all data

**Best Use Cases**:
- User data with random access patterns
- Key-value lookups
- When even distribution is critical

### 3. Directory-Based Sharding

**Definition**: Use a lookup service to maintain a mapping between shard keys and shards.

**Example**:
```
Directory Service:
├── User 12345 → Shard 2
├── User 67890 → Shard 1
├── User 11111 → Shard 3
└── User 22222 → Shard 4

Shard Mapping Table:
| User ID | Shard |
|---------|-------|
| 12345   | 2     |
| 67890   | 1     |
| 11111   | 3     |
| 22222   | 4     |
```

**Implementation**:
```python
class DirectoryService:
    def __init__(self):
        self.directory = {}  # Could be Redis, database, etc.
    
    def get_shard(self, user_id):
        return self.directory.get(user_id, "default_shard")
    
    def assign_shard(self, user_id, shard):
        self.directory[user_id] = shard
```

**Advantages**:
- Flexible shard assignment
- Easy to rebalance data
- Can optimize for access patterns
- Supports complex sharding logic

**Disadvantages**:
- Additional lookup overhead
- Directory service becomes bottleneck
- More complex architecture
- Directory service needs high availability

**Best Use Cases**:
- Complex sharding requirements
- Frequent rebalancing needs
- When flexibility is more important than performance

### 4. Consistent Hashing

**Definition**: Use consistent hashing to minimize data movement when shards are added or removed.

**Implementation**:
```python
import hashlib
import bisect

class ConsistentHash:
    def __init__(self, nodes=None, replicas=3):
        self.replicas = replicas
        self.ring = {}
        self.sorted_keys = []
        
        if nodes:
            for node in nodes:
                self.add_node(node)
    
    def add_node(self, node):
        for i in range(self.replicas):
            key = self.hash(f"{node}:{i}")
            self.ring[key] = node
            self.sorted_keys.append(key)
        self.sorted_keys.sort()
    
    def remove_node(self, node):
        for i in range(self.replicas):
            key = self.hash(f"{node}:{i}")
            del self.ring[key]
            self.sorted_keys.remove(key)
    
    def get_node(self, key):
        if not self.ring:
            return None
        
        hash_key = self.hash(key)
        idx = bisect.bisect_right(self.sorted_keys, hash_key)
        if idx == len(self.sorted_keys):
            idx = 0
        return self.ring[self.sorted_keys[idx]]
    
    def hash(self, key):
        return int(hashlib.md5(key.encode()).hexdigest(), 16)
```

**Advantages**:
- Minimal data movement when adding/removing shards
- Good load distribution
- Handles node failures gracefully
- Scalable architecture

**Disadvantages**:
- More complex implementation
- Potential for uneven distribution
- Requires virtual nodes for better balance
- Range queries still challenging

**Best Use Cases**:
- Dynamic scaling requirements
- Distributed caching systems
- Microservices architectures
- Cloud-native applications

## Shard Key Selection

### Criteria for Good Shard Keys

#### 1. High Cardinality
**Definition**: The shard key should have many distinct values.

**Good Example**: User ID, Email Address
**Bad Example**: Country (limited values), Boolean flags

#### 2. Even Distribution
**Definition**: Values should be distributed evenly across the range.

**Good Example**: Hash of user ID
**Bad Example**: Sequential IDs with gaps

#### 3. Query Pattern Alignment
**Definition**: Most queries should be able to target specific shards.

**Example**:
```sql
-- Good: Single shard query
SELECT * FROM users WHERE user_id = 12345;

-- Bad: Multi-shard query
SELECT * FROM users WHERE created_date > '2023-01-01';
```

#### 4. Immutability
**Definition**: Shard key values should not change frequently.

**Good Example**: User ID, Order ID
**Bad Example**: User status, current location

## Cross-Shard Operations

### 1. Cross-Shard Queries

**Challenge**: Queries that need data from multiple shards.

**Strategies**:

#### Scatter-Gather Pattern
```python
def get_users_by_status(status):
    results = []
    for shard in all_shards:
        shard_results = shard.query("SELECT * FROM users WHERE status = ?", [status])
        results.extend(shard_results)
    
    # Merge and sort results
    return sorted(results, key=lambda x: x['created_at'])
```

### 2. Cross-Shard Transactions

**Solutions**:

#### Two-Phase Commit (2PC)
```
Coordinator
├── Phase 1: Prepare
│   ├── Shard 1: PREPARE → OK
│   ├── Shard 2: PREPARE → OK
│   └── Shard 3: PREPARE → OK
└── Phase 2: Commit
    ├── Shard 1: COMMIT → OK
    ├── Shard 2: COMMIT → OK
    └── Shard 3: COMMIT → OK
```

#### Saga Pattern
```
Transfer Money Saga:
1. Debit Account A → Success
2. Credit Account B → Success
3. Update Transaction Log → Success

If any step fails:
- Execute compensating actions
- Rollback previous steps
```

## Best Practices

### 1. Design Principles
- **Start Simple**: Begin with read replicas before sharding
- **Shard by Entity**: Keep related data together
- **Avoid Cross-Shard Operations**: Design to minimize cross-shard queries
- **Plan for Growth**: Design sharding scheme for future scale

### 2. Implementation Guidelines
- **Choose Immutable Shard Keys**: Avoid keys that change frequently
- **Monitor Continuously**: Track shard balance and performance
- **Automate Operations**: Use tools for deployment and management
- **Test Thoroughly**: Include failure scenarios in testing

### 3. Operational Excellence
- **Document Everything**: Shard mapping, procedures, runbooks
- **Plan for Disasters**: Backup and recovery procedures
- **Monitor Proactively**: Set up alerts for imbalances
- **Train Teams**: Ensure team understands sharding implications

## When to Avoid Sharding

### Alternative Solutions
1. **Vertical Scaling**: Upgrade hardware first
2. **Read Replicas**: Scale read operations
3. **Caching**: Reduce database load
4. **Query Optimization**: Improve existing queries
5. **Archiving**: Move old data to separate storage

### Warning Signs
- Small dataset that fits on single server
- Mostly read-only workload
- Complex cross-table relationships
- Limited operational expertise
- Tight consistency requirements

## Conclusion

Database sharding is a powerful technique for scaling databases horizontally, but it comes with significant complexity. Key considerations include:

**Benefits**:
- Horizontal scalability for both reads and writes
- Improved performance through parallel processing
- Better fault isolation
- Cost-effective scaling

**Challenges**:
- Increased application complexity
- Cross-shard operations difficulties
- Operational overhead
- Data rebalancing complexity

**Success Factors**:
- Careful shard key selection
- Proper monitoring and tooling
- Team expertise and training
- Gradual implementation approach

**Remember**: Sharding should be a last resort after other scaling techniques have been exhausted. The complexity it introduces must be justified by the scalability benefits it provides.
