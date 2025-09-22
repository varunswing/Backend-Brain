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

**Concept**:
```
Hash Ring:
    0° ────────────── 90°
    │                 │
    │     Shard 1     │
270° ────────────── 180°
    │     Shard 2     │
    │                 │
   225° ──── 135° ────
```

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

### Common Shard Key Patterns

#### 1. Entity ID
```sql
-- Users table sharded by user_id
SELECT * FROM users WHERE user_id = ?;
```

#### 2. Tenant ID (Multi-tenancy)
```sql
-- Multi-tenant application sharded by tenant_id
SELECT * FROM orders WHERE tenant_id = ? AND order_date > ?;
```

#### 3. Geographic Location
```sql
-- Location-based sharding
SELECT * FROM stores WHERE region = 'US-WEST';
```

#### 4. Time-based
```sql
-- Time-series data sharded by date
SELECT * FROM logs WHERE date = '2023-01-01';
```

## Implementation Approaches

### 1. Application-Level Sharding

**Definition**: The application handles shard routing and query distribution.

**Architecture**:
```
Application Layer
├── Shard Router
├── Connection Pool Manager
└── Query Distributor
    ├── Shard 1 (Database)
    ├── Shard 2 (Database)
    ├── Shard 3 (Database)
    └── Shard 4 (Database)
```

**Implementation Example**:
```python
class ShardedDatabase:
    def __init__(self, shard_configs):
        self.shards = {}
        for shard_id, config in shard_configs.items():
            self.shards[shard_id] = DatabaseConnection(config)
    
    def get_shard(self, user_id):
        shard_id = hash(user_id) % len(self.shards)
        return self.shards[shard_id]
    
    def get_user(self, user_id):
        shard = self.get_shard(user_id)
        return shard.execute("SELECT * FROM users WHERE id = ?", [user_id])
    
    def get_users_by_country(self, country):
        # Cross-shard query
        results = []
        for shard in self.shards.values():
            result = shard.execute("SELECT * FROM users WHERE country = ?", [country])
            results.extend(result)
        return results
```

**Advantages**:
- Full control over sharding logic
- Can optimize for specific use cases
- No additional infrastructure
- Easy to customize

**Disadvantages**:
- Complex application code
- Difficult to maintain
- Cross-shard operations are complex
- No built-in failover

### 2. Middleware/Proxy-Based Sharding

**Definition**: A middleware layer handles sharding transparently to the application.

**Popular Solutions**:
- **Vitess** (MySQL)
- **Citus** (PostgreSQL)
- **ProxySQL** (MySQL)
- **PgBouncer** (PostgreSQL)

**Architecture**:
```
Application
    ↓
Sharding Proxy/Middleware
    ├── Shard 1
    ├── Shard 2
    ├── Shard 3
    └── Shard 4
```

**Advantages**:
- Transparent to application
- Centralized sharding logic
- Built-in failover and load balancing
- Professional tooling and support

**Disadvantages**:
- Additional infrastructure complexity
- Potential single point of failure
- Learning curve for new tools
- May have performance overhead

### 3. Database-Native Sharding

**Definition**: The database system provides built-in sharding capabilities.

**Examples**:
- **MongoDB** (Automatic sharding)
- **Cassandra** (Consistent hashing)
- **CockroachDB** (Automatic range splitting)
- **Azure Cosmos DB** (Partition keys)

**MongoDB Example**:
```javascript
// Enable sharding for database
sh.enableSharding("myapp")

// Shard collection by user_id
sh.shardCollection("myapp.users", { "user_id": 1 })

// MongoDB automatically distributes data
```

**Advantages**:
- Built-in optimization
- Automatic rebalancing
- Professional support
- Integrated monitoring

**Disadvantages**:
- Vendor lock-in
- Less control over sharding logic
- May not fit all use cases
- Learning curve

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

#### Denormalization
```sql
-- Store frequently queried data in each shard
CREATE TABLE user_summary (
    user_id INT,
    status VARCHAR(50),
    country VARCHAR(50),
    created_at TIMESTAMP
);
```

#### Separate Reporting Database
```
Operational Shards → ETL Process → Reporting Database
                                      ↓
                                 Analytics Queries
```

### 2. Cross-Shard Transactions

**Challenge**: Maintaining ACID properties across multiple shards.

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

**Drawbacks**:
- Blocking protocol
- Single point of failure (coordinator)
- Poor performance
- Complex error handling

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

#### Eventual Consistency
```python
# Accept temporary inconsistency
def transfer_money(from_account, to_account, amount):
    # Step 1: Debit source account
    debit_event = create_debit_event(from_account, amount)
    publish_event(debit_event)
    
    # Step 2: Credit destination account (async)
    credit_event = create_credit_event(to_account, amount)
    publish_event(credit_event)
    
    # System will eventually be consistent
```

## Resharding and Rebalancing

### When to Reshard

**Indicators**:
- Uneven data distribution
- Performance degradation
- Storage capacity issues
- Hot spots in specific shards

**Metrics to Monitor**:
- Data size per shard
- Query load per shard
- Response time per shard
- Storage utilization

### Resharding Strategies

#### 1. Offline Resharding
```
1. Take system offline
2. Export data from all shards
3. Reconfigure sharding scheme
4. Import data to new shards
5. Bring system back online
```

**Pros**: Simple, consistent
**Cons**: Downtime required

#### 2. Online Resharding
```
1. Create new shard configuration
2. Start dual-writing to old and new shards
3. Migrate data in background
4. Switch reads to new shards
5. Remove old shards
```

**Pros**: No downtime
**Cons**: Complex, temporary inconsistency

#### 3. Gradual Migration
```
1. Identify data to migrate
2. Copy data to new shards
3. Verify data consistency
4. Update routing for migrated data
5. Remove data from old shards
```

### Rebalancing Techniques

#### Hot Spot Mitigation
```python
# Detect hot shards
def detect_hot_shards():
    shard_metrics = get_shard_metrics()
    avg_load = sum(shard_metrics.values()) / len(shard_metrics)
    
    hot_shards = []
    for shard, load in shard_metrics.items():
        if load > avg_load * 1.5:  # 50% above average
            hot_shards.append(shard)
    
    return hot_shards

# Split hot shards
def split_hot_shard(hot_shard):
    # Create new shard
    new_shard = create_new_shard()
    
    # Move half the data
    migrate_data(hot_shard, new_shard, percentage=50)
    
    # Update routing
    update_shard_routing(hot_shard, new_shard)
```

## Challenges and Solutions

### 1. Complexity Management

**Challenge**: Sharding adds significant complexity to the system.

**Solutions**:
- Start with simpler scaling approaches first
- Use proven sharding frameworks
- Invest in monitoring and tooling
- Document sharding logic thoroughly

### 2. Cross-Shard Joins

**Challenge**: Joins across shards are expensive or impossible.

**Solutions**:
- Denormalize data to avoid joins
- Use application-level joins
- Implement caching for frequently joined data
- Consider NoSQL alternatives

### 3. Shard Key Changes

**Challenge**: Changing shard keys requires data migration.

**Solutions**:
- Choose immutable shard keys
- Plan for shard key evolution
- Implement versioned sharding schemes
- Use composite shard keys for flexibility

### 4. Operational Complexity

**Challenge**: Managing multiple database instances.

**Solutions**:
- Automate deployment and configuration
- Implement centralized monitoring
- Use infrastructure as code
- Plan for disaster recovery

## Monitoring and Observability

### Key Metrics

#### Per-Shard Metrics
- Query response time
- Query throughput (QPS)
- Data size and growth rate
- Connection count
- Error rates

#### Cross-Shard Metrics
- Cross-shard query percentage
- Data distribution balance
- Rebalancing frequency
- Migration success rates

#### Application Metrics
- Shard routing accuracy
- Connection pool utilization
- Cache hit rates
- Transaction success rates

### Monitoring Tools

```python
class ShardMonitor:
    def __init__(self, shards):
        self.shards = shards
        self.metrics = {}
    
    def collect_metrics(self):
        for shard_id, shard in self.shards.items():
            self.metrics[shard_id] = {
                'query_count': shard.get_query_count(),
                'avg_response_time': shard.get_avg_response_time(),
                'error_rate': shard.get_error_rate(),
                'data_size': shard.get_data_size(),
                'connection_count': shard.get_connection_count()
            }
    
    def detect_imbalance(self):
        data_sizes = [m['data_size'] for m in self.metrics.values()]
        avg_size = sum(data_sizes) / len(data_sizes)
        
        imbalanced_shards = []
        for shard_id, metrics in self.metrics.items():
            if metrics['data_size'] > avg_size * 1.3:  # 30% above average
                imbalanced_shards.append(shard_id)
        
        return imbalanced_shards
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

**Decision Framework**:
1. Exhaust simpler scaling options first
2. Ensure you have operational expertise
3. Design for your specific access patterns
4. Plan for long-term maintenance and evolution

Remember: Sharding should be a last resort after other scaling techniques have been exhausted. The complexity it introduces must be justified by the scalability benefits it provides.
