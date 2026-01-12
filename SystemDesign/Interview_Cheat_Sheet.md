# System Design Interview Cheat Sheet for Senior Developers

## 📋 Interview Flow (45-60 minutes)

| Phase | Time | Focus |
|-------|------|-------|
| **1. Clarify** | 5 min | Requirements, scale, constraints |
| **2. Estimate** | 5 min | QPS, storage, bandwidth |
| **3. Design** | 15 min | High-level architecture |
| **4. Deep Dive** | 15 min | 2-3 critical components |
| **5. Scale** | 10 min | Bottlenecks, trade-offs |

---

## 🔢 Back-of-Envelope Calculations

### Quick Numbers
| Metric | Value |
|--------|-------|
| Seconds/day | 86,400 (~100K) |
| Seconds/month | 2.5M |
| 1M requests/day | ~12 QPS |
| 1B requests/day | ~12K QPS |

### Data Sizes
| Type | Size |
|------|------|
| UUID | 16 bytes |
| Timestamp | 8 bytes |
| URL | ~100 bytes |
| Tweet | ~300 bytes |
| Image (thumbnail) | 50 KB |
| Image (HD) | 2 MB |

### Latency Numbers
| Operation | Time |
|-----------|------|
| L1 cache | 1 ns |
| L2 cache | 10 ns |
| RAM | 100 ns |
| SSD random | 100 μs |
| HDD seek | 10 ms |
| Same datacenter | 0.5 ms |
| Cross-region | 100 ms |

---

## 🏗️ HLD Building Blocks

### When to Use What

| Need | Solution |
|------|----------|
| **Fast reads** | Cache (Redis), CDN |
| **Fast writes** | Message Queue, NoSQL |
| **Real-time** | WebSocket, SSE |
| **Search** | Elasticsearch |
| **Location** | Geospatial DB (PostGIS) |
| **Consistency** | SQL, Distributed TX |
| **High availability** | Replication, Load balancer |
| **Decouple services** | Message Queue (Kafka) |

### Load Balancing Algorithms
| Algorithm | Use Case |
|-----------|----------|
| Round Robin | Equal servers |
| Weighted | Different capacities |
| Least Connections | Long requests |
| IP Hash | Session affinity |

### Caching Strategies
| Strategy | Description |
|----------|-------------|
| Cache-Aside | App manages cache |
| Write-Through | Sync write to both |
| Write-Back | Async write to DB |
| Read-Through | Cache loads on miss |

### Database Selection
| Type | Use Case | Example |
|------|----------|---------|
| **SQL** | ACID, complex queries | PostgreSQL |
| **Document** | Flexible schema | MongoDB |
| **Key-Value** | Caching, sessions | Redis |
| **Wide-Column** | Time-series, logs | Cassandra |
| **Graph** | Relationships | Neo4j |
| **Search** | Full-text search | Elasticsearch |

---

## 🧩 Common HLD Patterns

### URL Shortener
```
Client → API Gateway → URL Service → DB (NoSQL)
                            ↓
                         Cache
```
**Key**: Base62 encoding, Counter/Snowflake for IDs

### Chat System
```
Client ←→ WebSocket Gateway ←→ Message Service ←→ Cassandra
                                     ↓
                                   Kafka
```
**Key**: WebSocket, Message queue for reliability

### News Feed
```
User posts → Fan-out Service → Timeline Cache (Redis)
                   ↓
            For celebrities: Fan-out on read
```
**Key**: Hybrid fan-out (on-write for normal, on-read for celebrities)

### Rate Limiter
```
Request → Rate Limiter → Service
              ↓
         Redis (counters)
```
**Key**: Token bucket or Sliding window

---

## 📐 LLD Quick Reference

### SOLID Principles
| Principle | One-liner |
|-----------|-----------|
| **S**ingle Responsibility | One class, one reason to change |
| **O**pen-Closed | Open for extension, closed for modification |
| **L**iskov Substitution | Subtypes must be substitutable |
| **I**nterface Segregation | Small, focused interfaces |
| **D**ependency Inversion | Depend on abstractions |

### Design Patterns for Interviews
| Pattern | When to Use |
|---------|-------------|
| **Factory** | Object creation logic |
| **Strategy** | Interchangeable algorithms |
| **Observer** | Event notifications |
| **Singleton** | Single instance needed |
| **Builder** | Complex object construction |
| **Decorator** | Add behavior dynamically |
| **State** | State-dependent behavior |

### LLD Interview Template
```
1. Clarify requirements
2. Identify entities/classes
3. Define relationships
4. Design class diagram
5. Define key methods
6. Handle edge cases
7. Discuss concurrency
```

---

## ⚡ Concurrency Essentials

### Thread Safety Options
| Approach | Use Case |
|----------|----------|
| `synchronized` | Simple, single lock |
| `ReentrantLock` | tryLock, fairness |
| `ReadWriteLock` | Many reads, few writes |
| `AtomicInteger` | Lock-free counters |
| `ConcurrentHashMap` | Thread-safe map |

### Common Patterns
| Pattern | Use Case |
|---------|----------|
| Producer-Consumer | Task queues |
| Thread Pool | Manage threads |
| Future/Promise | Async results |
| Semaphore | Limit concurrency |

---

## 🔥 CAP Theorem Quick Reference

```
     C (Consistency)
        ╱╲
       ╱  ╲
      ╱ CP ╲    Choose 2:
     ╱──────╲   • CP: Consistency + Partition
    ╱ CA  AP ╲  • AP: Availability + Partition
   ╱──────────╲ • CA: Single node only
  A            P
```

| System | Type |
|--------|------|
| PostgreSQL | CA (single), CP (cluster) |
| MongoDB | CP |
| Cassandra | AP |
| DynamoDB | AP (configurable) |

---

## 🎯 Common Interview Questions

### HLD Questions
1. Design URL Shortener
2. Design Twitter/Instagram
3. Design WhatsApp/Messenger
4. Design Netflix/YouTube
5. Design Uber/Lyft
6. Design Rate Limiter
7. Design Notification System
8. Design Web Crawler

### LLD Questions
1. Parking Lot System
2. Elevator System
3. Library Management
4. Hotel Booking
5. Chess/Tic-Tac-Toe
6. ATM Machine
7. Vending Machine
8. File System

---

## 💡 Interview Tips

### Do's ✅
- Ask clarifying questions
- State assumptions
- Think out loud
- Draw diagrams
- Discuss trade-offs
- Start simple, then scale

### Don'ts ❌
- Don't jump into solution
- Don't stay silent
- Don't ignore non-functional requirements
- Don't over-engineer
- Don't be defensive about feedback

### Key Phrases
- "Let me clarify the requirements..."
- "I'll make an assumption that..."
- "The trade-off here is..."
- "For scalability, we could..."
- "An alternative approach would be..."

---

## 📚 Quick Formula Reference

### Capacity Estimation
```
Daily Users × Actions/User = Daily Requests
Daily Requests / 86400 = Average QPS
Peak QPS = Average QPS × 2-3

Data Size × Daily Records × Days × Replication = Storage
```

### Availability (9's)
| Availability | Downtime/Year |
|--------------|---------------|
| 99% (two 9s) | 3.65 days |
| 99.9% (three 9s) | 8.76 hours |
| 99.99% (four 9s) | 52.6 minutes |
| 99.999% (five 9s) | 5.26 minutes |

---

## 🗺️ Folder Structure Reference

```
SystemDesign/
├── Core-concepts/
│   ├── Databases/
│   ├── Distributed-Systems/
│   ├── Scalability-Architecture/
│   └── Security-Observability/
├── HLD/
│   ├── Building-Blocks/      ← Load balancing, CDN, etc.
│   ├── System-Examples/      ← Common HLD problems
│   └── Q&A.md               ← Detailed Q&A
└── LLD/
    ├── Core-Principles/      ← SOLID, Concurrency
    ├── MachineCoding/        ← Coding problems
    └── System-Examples/      ← LLD designs
```

---

*Good luck with your interview! 🚀*
