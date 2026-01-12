# System Design Resources & Quick Reference Q&A

## 📚 Table of Contents
1. [Learning Resources](#learning-resources)
2. [Practice Platforms](#practice-platforms)
3. [Quick Reference Q&A](#quick-reference-qa)
4. [System Design Topics Roadmap](#system-design-topics-roadmap)

---

## Learning Resources

### Essential Links
- [Awesome System Design Resources](https://github.com/ashishps1/awesome-system-design-resources) - Comprehensive GitHub collection
- [Educative IO Cheatsheets](https://www.educative.io/cheatsheets) - Quick reference guides
- [Most Asked System Design Questions](https://www.linkedin.com/posts/jainshrayansh_softwareengineer-java-springboot-activity-7269204973043757056-qU35)

### Practice Platforms
- [Machine Coding Practice - workat.tech](https://workat.tech/machine-coding) - LLD coding practice
- [System Design Primer](https://github.com/donnemartin/system-design-primer) - Comprehensive guide

---

## Quick Reference Q&A

### SOLID Principles

**Q: How do you write SOLID code?**

The SOLID principles are fundamental guidelines for writing maintainable, scalable code:

| Principle | Description | Example |
|-----------|-------------|---------|
| **S** - Single Responsibility | A class should have only one reason to change | Separate `UserValidator` from `UserRepository` |
| **O** - Open/Closed | Open for extension, closed for modification | Use interfaces/abstract classes |
| **L** - Liskov Substitution | Subtypes must be substitutable for base types | Child classes honor parent contracts |
| **I** - Interface Segregation | Many specific interfaces > one general interface | Split `IWorker` into `IWorkable`, `IEatable` |
| **D** - Dependency Inversion | Depend on abstractions, not concretions | Inject `IRepository` not `MySQLRepository` |

**Resources**:
- [SOLID Interview Questions](https://www.qfles.com/interview-question/solid-principles-interview-questions)
- [GFG SOLID Examples](https://www.geeksforgeeks.org/solid-principle-in-programming-understand-with-real-life-examples/)

---

### Database Indexing

**Q: What is database indexing and what are its types?**

Indexing is a data structure technique to efficiently retrieve records based on indexed attributes.

**Types of Indexes**:

| Type | Description | Use Case |
|------|-------------|----------|
| **Primary Index** | On ordered data file, usually on primary key | Default lookups |
| **Secondary Index** | On candidate key or non-key field | Alternative access paths |
| **Clustering Index** | On ordered non-key field | Range queries |
| **Dense Index** | Entry for every record | Fast lookups, more space |
| **Sparse Index** | Entry for blocks of records | Less space, slower lookups |

**Resources**:
- [Indexing in DBMS - Scaler](https://www.scaler.com/topics/dbms/indexing-in-dbms/)
- [GFG Indexing](https://www.geeksforgeeks.org/indexing-in-databases-set-1/)

---

### Cache Policies

**Q: What are the different cache write policies?**

| Policy | Description | Pros | Cons |
|--------|-------------|------|------|
| **Write-Through** | Write to cache AND database simultaneously | Data consistency | Higher write latency |
| **Write-Back** | Write to cache, async write to DB | Lower write latency | Risk of data loss |
| **Write-Around** | Write directly to DB, bypass cache | Prevents cache pollution | Cache misses on recent writes |

**Cache Eviction Policies**:
- **LRU** (Least Recently Used) - Most common
- **LFU** (Least Frequently Used) - For hot data patterns
- **FIFO** (First In First Out) - Simple, predictable
- **TTL** (Time To Live) - Time-based expiration

**Resource**: [Cache Types & Policies](https://www.bytesizedpieces.com/posts/cache-types)

---

### SQL vs NoSQL Quick Reference

**Q: When to use SQL vs NoSQL?**

**SQL (Relational Databases)**:
- ✅ Structured data with fixed schema
- ✅ ACID compliance required
- ✅ Complex queries and JOINs
- ✅ Vertical scaling
- 📌 Use cases: Transactions, complex queries, financial systems

**NoSQL (Non-Relational Databases)**:
- ✅ Flexible/dynamic schema
- ✅ Horizontal scaling
- ✅ High performance for specific patterns
- ✅ Big data and real-time analytics
- 📌 Use cases: Big data, real-time apps, content management

**Key Differences**:

| Aspect | SQL | NoSQL |
|--------|-----|-------|
| Schema | Fixed | Dynamic |
| Scalability | Vertical | Horizontal |
| Transactions | ACID | BASE (usually) |
| Query Language | Standard SQL | Varies |
| Data Model | Tables | Documents/Key-Value/Graph |

---

### HTTP Methods & Status Codes

**Q: What are the main HTTP methods and status codes?**

**HTTP Status Code Categories**:
| Range | Category | Examples |
|-------|----------|----------|
| 1xx | Informational | 100 Continue |
| 2xx | Success | 200 OK, 201 Created |
| 3xx | Redirection | 301 Moved, 302 Found |
| 4xx | Client Error | 400 Bad Request, 404 Not Found |
| 5xx | Server Error | 500 Internal Error, 503 Unavailable |

**Main HTTP Methods**:

| Method | Purpose | Idempotent | Request Body |
|--------|---------|------------|--------------|
| **GET** | Retrieve resource | ✅ Yes | ❌ None |
| **POST** | Create resource | ❌ No | ✅ Contains data |
| **PUT** | Update/Replace | ✅ Yes | ✅ Contains data |
| **PATCH** | Partial update | ❌ No | ✅ Contains data |
| **DELETE** | Remove resource | ✅ Yes | ❌ Usually none |

**Key Differences**:
- GET retrieves, POST creates, PUT replaces, PATCH updates partially
- PUT is idempotent (same result if called multiple times)
- POST is NOT idempotent (may create duplicates)

---

## System Design Topics Roadmap

### Core Concepts to Master

#### 1. Scalability
- [ ] Horizontal vs Vertical Scaling
- [ ] Load Balancing
- [ ] Caching strategies
- [ ] Database sharding

#### 2. High Availability
- [ ] Redundancy
- [ ] Failover strategies
- [ ] Disaster recovery
- [ ] Health checks

#### 3. Microservices
- [ ] Service discovery
- [ ] API Gateway
- [ ] Inter-service communication
- [ ] Circuit breakers

#### 4. Databases
- [ ] SQL vs NoSQL
- [ ] ACID vs BASE
- [ ] CAP theorem
- [ ] Indexing & Partitioning

#### 5. Distributed Systems
- [ ] Consensus algorithms (Raft, Paxos)
- [ ] Distributed transactions
- [ ] Event-driven architecture
- [ ] Message queues

#### 6. Performance
- [ ] Caching (Redis, CDN)
- [ ] Connection pooling
- [ ] Async processing
- [ ] Rate limiting

#### 7. Security
- [ ] Authentication (OAuth, JWT)
- [ ] Authorization (RBAC, ABAC)
- [ ] Encryption (TLS, at-rest)
- [ ] API security

#### 8. Containerization & Orchestration
- [ ] Docker fundamentals
- [ ] Kubernetes basics
- [ ] Service mesh
- [ ] CI/CD pipelines

#### 9. Event-Driven Architecture
- [ ] Message brokers (Kafka, RabbitMQ)
- [ ] Event sourcing
- [ ] CQRS pattern
- [ ] Saga pattern

#### 10. Cloud Computing
- [ ] AWS/GCP/Azure services
- [ ] Serverless architecture
- [ ] Cloud-native patterns
- [ ] Cost optimization

---

## External Video Resources

### Quick Learning Videos
- Spring Boot in 1 Minute (see RoadMaps folder)
- System Design Interviews on YouTube

### Recommended YouTube Channels
- System Design Interview
- Gaurav Sen
- Tech Dummies Narendra L
- ByteByteGo

---

## Interview Preparation Checklist

### Before the Interview
- [ ] Review common system design problems
- [ ] Practice whiteboard design
- [ ] Understand trade-offs for each decision
- [ ] Prepare questions to clarify requirements

### During the Interview
- [ ] Clarify requirements first
- [ ] Think out loud
- [ ] Start with high-level design
- [ ] Discuss trade-offs
- [ ] Be ready to dive deep

### Key Things to Demonstrate
- [ ] Requirement gathering skills
- [ ] Scalability awareness
- [ ] Trade-off analysis
- [ ] System components knowledge
- [ ] Real-world experience (if any)

---

**Tip**: System design interviews are about demonstrating your thought process, not about reaching a perfect solution. Focus on communication, trade-offs, and iterative improvement.
