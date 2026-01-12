# System Design Interview Template

A structured approach to solving system design interview questions.

## Overview

System design interviews test your ability to design large-scale distributed systems. This template provides a step-by-step framework.

## Interview Structure (45-60 minutes)

| Phase | Time | Focus |
|-------|------|-------|
| **1. Requirements** | 5-7 min | Clarify scope |
| **2. Estimation** | 3-5 min | Scale calculations |
| **3. High-Level Design** | 10-15 min | Architecture |
| **4. Database Design** | 5-10 min | Data model |
| **5. Deep Dive** | 10-15 min | Critical flows |
| **6. Scaling** | 5-10 min | Bottlenecks |

---

## Phase 1: Clarify Requirements (5-7 min)

### Functional Requirements
*What should the system do?*

Questions to ask:
- What are the core features?
- Who are the users?
- What are the main use cases?

### Non-Functional Requirements
*How should the system perform?*

| Aspect | Questions |
|--------|-----------|
| **Scale** | How many users? DAU/MAU? |
| **Performance** | Latency requirements? |
| **Availability** | What's acceptable downtime? |
| **Consistency** | Strong or eventual? |
| **Security** | Authentication needs? |

### Constraints
- Budget constraints?
- Technology preferences?
- Geographic requirements?

---

## Phase 2: Back-of-Envelope Calculations (3-5 min)

### Traffic Estimates
```
Users: 10M DAU
Requests per user: 10/day
Total requests: 100M/day

QPS = 100M / 86,400 ≈ 1,200 QPS
Peak QPS = 2-3x average ≈ 3,600 QPS
```

### Storage Estimates
```
Data per item: 1 KB
Items per day: 1M
Daily storage: 1 GB
Yearly storage: 365 GB

With 3x replication: ~1 TB/year
```

### Bandwidth Estimates
```
Request size: 1 KB
Requests per second: 1,200
Bandwidth: 1.2 MB/s = ~10 Mbps
```

### Useful Numbers
| Unit | Value |
|------|-------|
| Seconds/day | 86,400 |
| Seconds/month | ~2.5M |
| 1 million/day QPS | ~12 |
| 1 billion/day QPS | ~12,000 |

---

## Phase 3: High-Level Architecture (10-15 min)

### Core Components
```
┌─────────────┐    ┌─────────────┐    ┌─────────────┐
│   Clients   │───→│ Load Balancer│───→│ API Servers │
└─────────────┘    └─────────────┘    └─────────────┘
                                             │
                   ┌─────────────────────────┼─────────────────────────┐
                   │                         │                         │
             ┌─────▼─────┐           ┌──────▼──────┐           ┌──────▼──────┐
             │   Cache   │           │  Database   │           │  Message Q  │
             └───────────┘           └─────────────┘           └─────────────┘
```

### Component Checklist
- [ ] Load Balancer
- [ ] API Gateway
- [ ] Application Servers
- [ ] Cache Layer (Redis)
- [ ] Database (SQL/NoSQL)
- [ ] Message Queue
- [ ] CDN (if applicable)
- [ ] Search (if applicable)

---

## Phase 4: Database Design (5-10 min)

### Choose Database Type
| Use Case | Database Type |
|----------|--------------|
| Transactions, ACID | PostgreSQL, MySQL |
| Flexible schema | MongoDB |
| Caching | Redis |
| Time-series | Cassandra, InfluxDB |
| Search | Elasticsearch |
| Relationships | Neo4j |

### Data Model Example
```sql
-- Users table
CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    username VARCHAR(255) UNIQUE,
    email VARCHAR(255) UNIQUE,
    created_at TIMESTAMP
);

-- Posts table
CREATE TABLE posts (
    id BIGINT PRIMARY KEY,
    user_id BIGINT REFERENCES users(id),
    content TEXT,
    created_at TIMESTAMP
);

-- Indexes
CREATE INDEX idx_posts_user_id ON posts(user_id);
CREATE INDEX idx_posts_created_at ON posts(created_at);
```

---

## Phase 5: Deep Dive - Critical Flow (10-15 min)

### Pick 1-2 Core Flows
Example: "User creates a post"

```
1. Client sends POST /api/posts
2. API Gateway validates token
3. Load Balancer routes to API server
4. API server validates request
5. Check rate limits (Redis)
6. Write to database
7. Publish event to message queue
8. Update cache
9. Return response to client
10. Async: Update feeds, send notifications
```

### Handle Edge Cases
- What if database write fails?
- What if cache is unavailable?
- How to handle duplicate requests?

---

## Phase 6: Scaling & Bottlenecks (5-10 min)

### Identify Bottlenecks
1. **Database** - Most common bottleneck
2. **Network** - External API calls
3. **Application** - CPU-intensive operations
4. **Cache** - Hot keys

### Scaling Strategies

#### Database Scaling
```
Read-heavy: Add read replicas
Write-heavy: Database sharding
Both: Caching layer
```

#### Application Scaling
```
Horizontal: Add more servers
Vertical: Bigger servers (temporary)
Async: Background job processing
```

### Common Solutions
| Problem | Solution |
|---------|----------|
| Slow reads | Caching, read replicas |
| Slow writes | Async processing, sharding |
| Hot data | Cache with TTL |
| Large files | CDN, object storage |
| Search | Elasticsearch |
| Analytics | Separate data warehouse |

---

## What to Demonstrate

### Technical Skills
- [ ] System design fundamentals
- [ ] Trade-off analysis
- [ ] Scalability understanding
- [ ] Data modeling

### Communication Skills
- [ ] Clear structure
- [ ] Thinking out loud
- [ ] Asking clarifying questions
- [ ] Acknowledging trade-offs

---

## Common Follow-up Topics

### If Asked About:

**Availability**
- Multi-region deployment
- Failover strategies
- Health checks

**Consistency**
- CAP theorem trade-offs
- Eventual vs strong consistency
- Conflict resolution

**Performance**
- Caching strategies
- CDN usage
- Database optimization

**Security**
- Authentication/Authorization
- Encryption
- Rate limiting

---

## Quick Reference: Common Patterns

### Caching
```
Cache-Aside: App checks cache → DB → update cache
Write-Through: Write to cache → cache writes to DB
Write-Behind: Write to cache → async write to DB
```

### Load Balancing
```
Round Robin: Simple rotation
Least Connections: Route to least busy
IP Hash: Same client to same server
```

### Database Patterns
```
Replication: Read scalability, HA
Sharding: Write scalability
CQRS: Separate read/write models
```

---

## Template Response Structure

```
"Let me start by clarifying requirements..."
→ Ask 3-5 questions

"Based on these requirements, let me estimate scale..."
→ Quick calculations

"Here's my high-level architecture..."
→ Draw diagram, explain components

"For the database, I'd use..."
→ Schema design, justify choice

"Let me walk through the [core feature] flow..."
→ Step-by-step explanation

"For scaling, the main bottlenecks are..."
→ Identify issues, propose solutions

"Some trade-offs to consider..."
→ Acknowledge alternatives
```

---

## Final Tips

1. **Start broad, then deep** - Don't jump into details
2. **Draw as you explain** - Visual aids help
3. **Think out loud** - Show your reasoning
4. **Acknowledge trade-offs** - Nothing is perfect
5. **Be honest** - Say "I don't know" if needed
6. **Practice timing** - Know when to move on
