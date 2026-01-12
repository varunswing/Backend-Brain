# Database Selection Guide: Complete Reference

## 📚 Table of Contents
1. [Quick Decision Cheat Sheet](#quick-decision-cheat-sheet)
2. [Database Categories Overview](#database-categories-overview)
3. [Detailed Database Profiles](#detailed-database-profiles)
4. [Selection Criteria](#selection-criteria)
5. [Design Principles](#design-principles)
6. [Database Patterns](#database-patterns)
7. [Multi-Database Architectures](#multi-database-architectures)
8. [Decision Framework for Interviews](#decision-framework-for-interviews)
9. [Common Architecture Patterns](#common-architecture-patterns)
10. [Common Mistakes to Avoid](#common-mistakes-to-avoid)

---

## Quick Decision Cheat Sheet

### When to Use Each Database Type

| Need | Database Type | Top Choices |
|------|--------------|-------------|
| **Transactions & ACID** | RDBMS | PostgreSQL, MySQL |
| **Flexible Documents** | Document DB | MongoDB, CouchDB |
| **Fast Caching** | Key-Value | Redis, Memcached |
| **High Write Volume** | Wide-Column | Cassandra, ScyllaDB |
| **Complex Relationships** | Graph DB | Neo4j, Neptune |
| **Full-Text Search** | Search Engine | Elasticsearch, OpenSearch |
| **Time Series Data** | Time-Series | InfluxDB, TimescaleDB |

### One-Liner Decision Guide

```
┌─────────────────────────────────────────────────────────────┐
│                    QUICK DECISION TREE                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Need ACID + Complex Joins? ────────► RDBMS (PostgreSQL)    │
│                                                              │
│  Flexible Schema + Rapid Dev? ──────► MongoDB               │
│                                                              │
│  Sub-millisecond Latency? ──────────► Redis                 │
│                                                              │
│  Massive Write Throughput? ─────────► Cassandra             │
│                                                              │
│  Social Graphs/Fraud Detection? ────► Neo4j                 │
│                                                              │
│  Full-Text Search? ─────────────────► Elasticsearch         │
│                                                              │
│  IoT/Metrics/Time-based? ───────────► InfluxDB/TimescaleDB  │
│                                                              │
│  Object Storage/Media? ─────────────► S3/MinIO              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Database Categories Overview

### 1. Relational Databases (RDBMS)

| Database | Strengths | Best For |
|----------|-----------|----------|
| **PostgreSQL** | Advanced features, extensions, JSON support | Complex queries, data integrity, general purpose |
| **MySQL** | Speed, replication, wide support | Web apps, read-heavy workloads |
| **SQL Server** | .NET integration, enterprise features | Windows environments, BI |
| **Oracle** | Enterprise, RAC, partitioning | Large enterprises, legacy systems |
| **SQLite** | Embedded, zero-config | Mobile apps, local storage |

**When to Use RDBMS**:
- ✅ Need ACID transactions
- ✅ Complex relationships between entities
- ✅ Complex queries with JOINs
- ✅ Data integrity is critical
- ✅ Well-defined, stable schema

---

### 2. Document Databases

| Database | Strengths | Best For |
|----------|-----------|----------|
| **MongoDB** | Flexible schema, rich queries | Content management, catalogs, user data |
| **CouchDB** | Offline-first, sync | Mobile apps, offline capability |
| **Amazon DocumentDB** | MongoDB compatible, managed | AWS ecosystem, MongoDB migration |

**When to Use Document DB**:
- ✅ Schema varies or evolves frequently
- ✅ Nested/hierarchical data
- ✅ Rapid development cycles
- ✅ Denormalized data models
- ✅ Content management systems

**MongoDB Example**:
```javascript
// Product catalog with varying attributes
{
  "_id": "prod_123",
  "name": "Laptop",
  "category": "Electronics",
  "price": 999.99,
  "specs": {
    "cpu": "Intel i7",
    "ram": "16GB",
    "storage": "512GB SSD"
  },
  "reviews": [
    { "user": "john", "rating": 5, "comment": "Great!" }
  ],
  "tags": ["electronics", "computers", "portable"]
}
```

---

### 3. Key-Value Stores

| Database | Strengths | Best For |
|----------|-----------|----------|
| **Redis** | In-memory, data structures, pub/sub | Caching, sessions, real-time data |
| **Memcached** | Simple, distributed caching | Pure caching layer |
| **DynamoDB** | Managed, auto-scaling | Serverless, AWS ecosystem |
| **etcd** | Distributed, consistent | Configuration, service discovery |

**When to Use Key-Value**:
- ✅ Simple lookups by key
- ✅ Caching hot data
- ✅ Session management
- ✅ Real-time counters/leaderboards
- ✅ Rate limiting

**Redis Example**:
```redis
# Session storage
SET session:user123 '{"userId":"123","name":"John","role":"admin"}' EX 3600

# Rate limiting
INCR rate:user123:minute
EXPIRE rate:user123:minute 60

# Leaderboard
ZADD leaderboard 1000 "player1"
ZADD leaderboard 2500 "player2"
ZREVRANGE leaderboard 0 9 WITHSCORES
```

---

### 4. Wide-Column Stores

| Database | Strengths | Best For |
|----------|-----------|----------|
| **Cassandra** | Linear scalability, no SPOF | Time-series, IoT, logging |
| **ScyllaDB** | Cassandra compatible, faster | High-performance time-series |
| **HBase** | Hadoop integration | Big data analytics |
| **Google Bigtable** | Managed, scalable | GCP ecosystem, analytics |

**When to Use Wide-Column**:
- ✅ Time-series data (IoT, metrics, logs)
- ✅ High write throughput
- ✅ Need horizontal scalability
- ✅ Write-heavy workloads
- ✅ Analytics on large datasets

**Cassandra Example**:
```sql
-- IoT sensor data
CREATE TABLE sensor_readings (
    sensor_id UUID,
    timestamp TIMESTAMP,
    temperature FLOAT,
    humidity FLOAT,
    PRIMARY KEY (sensor_id, timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC);

-- Query recent readings
SELECT * FROM sensor_readings 
WHERE sensor_id = ? 
AND timestamp > '2024-01-01' 
LIMIT 100;
```

---

### 5. Graph Databases

| Database | Strengths | Best For |
|----------|-----------|----------|
| **Neo4j** | Powerful queries, Cypher language | Social networks, recommendations |
| **Amazon Neptune** | Managed, AWS integration | Cloud-native graph applications |
| **JanusGraph** | Distributed, scalable | Large-scale graphs |
| **TigerGraph** | Real-time analytics | Fraud detection, ML |

**When to Use Graph DB**:
- ✅ Many-to-many relationships
- ✅ Traversal queries (friends of friends)
- ✅ Fraud detection patterns
- ✅ Recommendation engines
- ✅ Social networks
- ✅ Knowledge graphs

**Neo4j Example**:
```cypher
// Create social connections
CREATE (alice:Person {name: 'Alice'})
CREATE (bob:Person {name: 'Bob'})
CREATE (alice)-[:FRIENDS_WITH {since: 2020}]->(bob)

// Find friends of friends who like a product
MATCH (user:Person {name: 'Alice'})-[:FRIENDS_WITH*1..2]-(friend)
      -[:PURCHASED]->(product:Product)
WHERE NOT (user)-[:PURCHASED]->(product)
RETURN DISTINCT product.name, COUNT(friend) AS recommendations
ORDER BY recommendations DESC
```

---

### 6. Search Engines

| Database | Strengths | Best For |
|----------|-----------|----------|
| **Elasticsearch** | Full-text search, analytics | Search, logging, APM |
| **OpenSearch** | AWS fork of Elasticsearch | AWS ecosystem search |
| **Solr** | Mature, enterprise features | Enterprise search |
| **Algolia** | Hosted, fast | E-commerce search |

**When to Use Search Engine**:
- ✅ Full-text search with relevance
- ✅ Log analytics (ELK stack)
- ✅ Faceted navigation
- ✅ Auto-complete/suggestions
- ✅ Analytics on text data

---

### 7. Time-Series Databases

| Database | Strengths | Best For |
|----------|-----------|----------|
| **InfluxDB** | Purpose-built, InfluxQL | Metrics, IoT, monitoring |
| **TimescaleDB** | PostgreSQL extension | SQL + time-series |
| **Prometheus** | Pull-based, alerting | Kubernetes monitoring |
| **QuestDB** | Ultra-fast ingestion | High-frequency data |

**When to Use Time-Series**:
- ✅ Metrics and monitoring
- ✅ IoT sensor data
- ✅ Financial tick data
- ✅ Event tracking
- ✅ Time-based analytics

---

## Detailed Database Profiles

### When to Use SQL (Detailed)

**Ideal Scenarios**:
1. **Financial Systems**: Banking, payments, accounting
2. **E-Commerce Orders**: Transactions, inventory
3. **User Management**: Authentication, profiles
4. **Reporting**: Complex analytics, joins
5. **ERP Systems**: Enterprise resource planning

**Technical Reasons**:
```
✅ ACID transactions required
✅ Complex multi-table joins
✅ Data integrity constraints
✅ Mature tooling and expertise
✅ Standard query language
✅ Referential integrity (foreign keys)
```

**SQL Example - E-Commerce**:
```sql
-- Atomic order creation
BEGIN TRANSACTION;

INSERT INTO orders (customer_id, total) VALUES (123, 99.99);
SET @order_id = LAST_INSERT_ID();

INSERT INTO order_items (order_id, product_id, qty) VALUES 
  (@order_id, 1, 2),
  (@order_id, 2, 1);

UPDATE inventory SET stock = stock - 2 WHERE product_id = 1;
UPDATE inventory SET stock = stock - 1 WHERE product_id = 2;

COMMIT;
```

---

### When to Use Document DB (Detailed)

**Ideal Scenarios**:
1. **Content Management**: Articles, posts, pages
2. **Product Catalogs**: Varying attributes
3. **User Profiles**: Flexible user data
4. **Real-Time Analytics**: Event data
5. **Mobile App Backends**: Sync-friendly data

**Technical Reasons**:
```
✅ Schema flexibility needed
✅ Hierarchical/nested data
✅ Rapid prototyping
✅ Read-heavy workloads
✅ Denormalized access patterns
```

---

### When to Use Key-Value (Detailed)

**Ideal Scenarios**:
1. **Session Storage**: User sessions, tokens
2. **Caching Layer**: Database query cache
3. **Rate Limiting**: API throttling
4. **Real-Time Features**: Leaderboards, counters
5. **Message Queues**: Simple pub/sub (Redis)

**Technical Reasons**:
```
✅ Simple key-based access
✅ Sub-millisecond latency required
✅ High throughput (100K+ ops/sec)
✅ TTL-based expiration
✅ Atomic operations
```

---

### When to Use Column-Family (Detailed)

**Ideal Scenarios**:
1. **IoT Data**: Sensor readings
2. **Time-Series Metrics**: System monitoring
3. **Event Logging**: Application logs
4. **Analytics Workloads**: Large-scale aggregations
5. **Message Storage**: Chat history

**Technical Reasons**:
```
✅ Write-heavy workloads (10K+ writes/sec)
✅ Time-based data partitioning
✅ Linear horizontal scaling
✅ High availability requirements
✅ No single point of failure
```

---

### When to Use Graph DB (Detailed)

**Ideal Scenarios**:
1. **Social Networks**: Followers, friends, connections
2. **Recommendation Engines**: "People who bought X also bought Y"
3. **Fraud Detection**: Unusual transaction patterns
4. **Knowledge Graphs**: Entity relationships
5. **Network Topology**: Infrastructure mapping

**Technical Reasons**:
```
✅ Complex relationship queries
✅ Variable-depth traversals
✅ Pattern matching
✅ Real-time recommendations
✅ Connected data analysis
```

---

## Selection Criteria

### Data Model Considerations

| Question | If YES, Consider |
|----------|------------------|
| Is data highly structured with clear relationships? | RDBMS |
| Does schema change frequently? | Document DB |
| Is access pattern simple key lookup? | Key-Value |
| Is data time-based with high write volume? | Wide-Column / Time-Series |
| Are relationships the primary concern? | Graph DB |
| Need full-text search? | Search Engine |

### Scalability Requirements

| Requirement | Recommendation |
|-------------|----------------|
| **Vertical scaling sufficient** | RDBMS (PostgreSQL, MySQL) |
| **Horizontal read scaling** | RDBMS with read replicas |
| **Horizontal write scaling** | NoSQL (Cassandra, DynamoDB) |
| **Global distribution** | DynamoDB, CockroachDB, Spanner |

### Consistency Requirements

| Requirement | Options |
|-------------|---------|
| **Strong ACID** | PostgreSQL, MySQL, SQL Server |
| **Tunable consistency** | Cassandra, DynamoDB, MongoDB |
| **Eventual consistency OK** | Cassandra, CouchDB, Riak |

### Performance Metrics

| Metric | Best Options |
|--------|--------------|
| **Lowest latency** | Redis, Memcached |
| **Highest write throughput** | Cassandra, ScyllaDB |
| **Complex query performance** | PostgreSQL (indexed), Elasticsearch |
| **Graph traversal speed** | Neo4j, TigerGraph |

---

## Design Principles

### 1. Normalization vs Denormalization

**Normalization (RDBMS)**:
- Reduce data redundancy
- Maintain data integrity
- More complex queries (JOINs)

**Denormalization (NoSQL)**:
- Optimize for read patterns
- Accept data duplication
- Faster reads, more storage

### 2. Indexing Strategies

```
Primary Index   → Unique identifier (always create)
Secondary Index → Frequently queried fields
Composite Index → Multi-column queries
Partial Index   → Conditional indexing (PostgreSQL)
Full-Text Index → Search capabilities
```

### 3. Partitioning Strategies

| Strategy | Use Case |
|----------|----------|
| **Range Partitioning** | Time-series, sequential data |
| **Hash Partitioning** | Even distribution |
| **List Partitioning** | Categorical data |
| **Composite** | Multi-dimensional partitioning |

---

## Database Patterns

### 1. Database-per-Service

```
┌─────────────┐   ┌─────────────┐   ┌─────────────┐
│ User Service│   │Order Service│   │ Product Svc │
└──────┬──────┘   └──────┬──────┘   └──────┬──────┘
       │                 │                 │
       ▼                 ▼                 ▼
  ┌────────┐       ┌────────┐       ┌────────┐
  │PostgreSQL│      │ MongoDB│       │ Redis  │
  └────────┘       └────────┘       └────────┘
```

**Pros**: Independence, technology fit, scaling
**Cons**: Data consistency, complex queries across services

### 2. CQRS (Command Query Responsibility Segregation)

```
        ┌─────────────────────────────────────┐
        │            Application              │
        └───────────────┬─────────────────────┘
                       / \
              Commands/   \Queries
                    /     \
           ┌───────▼─┐   ┌─▼───────┐
           │ Write DB│   │ Read DB │
           │(PostgreSQL)│ │ (Redis) │
           └─────────┘   └─────────┘
                  \      /
                   \    / Event Sync
                    \  /
                ┌────▼────┐
                │ Event   │
                │ Store   │
                └─────────┘
```

### 3. Event Sourcing

Store all changes as events:
```
Events Table:
┌──────────┬────────────┬─────────────────────────┐
│ Event ID │ Timestamp  │ Event Data              │
├──────────┼────────────┼─────────────────────────┤
│ 1        │ 2024-01-01 │ OrderCreated {id: 123}  │
│ 2        │ 2024-01-01 │ ItemAdded {item: "A"}   │
│ 3        │ 2024-01-01 │ ItemAdded {item: "B"}   │
│ 4        │ 2024-01-02 │ OrderPaid {amount: 100} │
└──────────┴────────────┴─────────────────────────┘
```

---

## Multi-Database Architectures

### Polyglot Persistence

Use the right database for each use case:

```
┌─────────────────────────────────────────────────────────────┐
│                      Application Layer                       │
├─────────┬─────────┬─────────┬─────────┬─────────┬──────────┤
│ Orders  │ Cache   │ Products│ Social  │ Search  │ Analytics│
│  SQL    │ Redis   │ MongoDB │ Neo4j   │Elastic  │ClickHouse│
└─────────┴─────────┴─────────┴─────────┴─────────┴──────────┘
```

### Data Lake Architecture

```
┌────────────────────────────────────────────────────┐
│                  Data Sources                       │
│  RDBMS  │  NoSQL  │  APIs  │  Files  │  Streams   │
└────────────────────┬───────────────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────────────┐
│              Data Lake (S3/HDFS)                   │
│   Raw Zone  │  Processed Zone  │  Curated Zone    │
└────────────────────┬───────────────────────────────┘
                     │
                     ▼
┌────────────────────────────────────────────────────┐
│              Analytics Layer                        │
│   Spark  │  Presto  │  Redshift  │  Snowflake     │
└────────────────────────────────────────────────────┘
```

---

## Decision Framework for Interviews

### Step-by-Step Approach

#### Step 1: Identify Access Patterns
```
Questions to ask:
- Read-heavy or write-heavy?
- Point queries or range queries?
- Real-time or batch processing?
- Simple lookups or complex joins?
```

#### Step 2: Assess Scale Requirements
```
Questions to ask:
- Expected data volume?
- QPS (queries per second)?
- Latency requirements?
- Growth projections?
```

#### Step 3: Evaluate Consistency Needs
```
Questions to ask:
- Is ACID required?
- Can we accept eventual consistency?
- What's the cost of inconsistency?
```

#### Step 4: Consider Operational Factors
```
Questions to ask:
- Team expertise?
- Cloud provider constraints?
- Managed vs self-hosted?
- Cost considerations?
```

### Interview Response Template

```
"For this system, I would choose [DATABASE] because:

1. ACCESS PATTERN: Our primary access pattern is [describe], 
   which [DATABASE] handles well because [reason].

2. SCALE: We need to handle [X QPS / Y TB], and [DATABASE] 
   can scale [horizontally/vertically] to meet this.

3. CONSISTENCY: We [need/don't need] strong consistency 
   because [reason], and [DATABASE] provides [consistency model].

4. ADDITIONAL CONSIDERATIONS: [mention caching, search, etc.]

I would also consider adding [SECONDARY DATABASE] for [use case] 
to leverage polyglot persistence."
```

---

## Common Architecture Patterns

### 1. Cache-Aside Pattern

```
┌─────────────────────────────────────────────────┐
│                   Application                    │
│                                                  │
│   1. Check cache ──────────► Redis              │
│   2. If miss, query ───────► PostgreSQL         │
│   3. Store in cache ───────► Redis              │
│   4. Return data                                │
└─────────────────────────────────────────────────┘
```

### 2. Read Replica Pattern

```
┌─────────────────────────────────────────────────┐
│               Load Balancer                      │
└────────────────────┬────────────────────────────┘
                     │
         ┌───────────┴───────────┐
         │                       │
    ┌────▼────┐            ┌────▼────┐
    │  Writes │            │  Reads  │
    └────┬────┘            └────┬────┘
         │                      │
    ┌────▼────┐     ┌──────────▼──────────┐
    │ Primary │────►│ Replica1 │ Replica2 │
    └─────────┘     └─────────────────────┘
```

### 3. Sharding Pattern

```
┌─────────────────────────────────────────────────┐
│              Shard Router/Proxy                  │
└────────────────────┬────────────────────────────┘
                     │
    ┌────────────────┼────────────────┐
    │                │                │
┌───▼───┐       ┌───▼───┐       ┌───▼───┐
│Shard 1│       │Shard 2│       │Shard 3│
│ A-H   │       │ I-P   │       │ Q-Z   │
└───────┘       └───────┘       └───────┘
```

---

## Common Mistakes to Avoid

### 1. Over-Engineering
❌ Using multiple databases when one would suffice
✅ Start simple, add complexity as needed

### 2. Under-Considering Scale
❌ Choosing a database that can't scale for your needs
✅ Plan for 10x growth from day one

### 3. Ignoring Access Patterns
❌ Choosing based on features, not actual usage
✅ Design schema around query patterns

### 4. Neglecting Operations
❌ Choosing unfamiliar technology without expertise
✅ Consider team skills and operational overhead

### 5. Wrong Consistency Model
❌ Using eventual consistency for financial transactions
✅ Match consistency to business requirements

### 6. Missing Caching Layer
❌ Hitting the database for every request
✅ Add caching for hot data (Redis/Memcached)

### 7. No Backup Strategy
❌ Assuming the database handles everything
✅ Implement backup, disaster recovery, monitoring

---

## Summary Decision Matrix

| Scenario | Primary DB | Secondary DB | Cache |
|----------|-----------|--------------|-------|
| **E-Commerce** | PostgreSQL | Elasticsearch | Redis |
| **Social Media** | PostgreSQL | Neo4j, Elasticsearch | Redis |
| **IoT Platform** | Cassandra | PostgreSQL | Redis |
| **Content Platform** | MongoDB | Elasticsearch | Redis |
| **Financial System** | PostgreSQL | - | Redis |
| **Gaming** | Redis/DynamoDB | PostgreSQL | Redis |
| **Analytics** | ClickHouse | PostgreSQL | - |
| **Real-time Chat** | Cassandra | PostgreSQL | Redis |

---

**Remember**: There's no one-size-fits-all database. The best choice depends on your specific requirements, constraints, and trade-offs you're willing to make.
