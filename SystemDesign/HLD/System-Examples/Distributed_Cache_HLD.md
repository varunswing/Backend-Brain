# Distributed Cache (Redis) - High Level Design

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Caching Fundamentals](#caching-fundamentals)
3. [High-Level Design](#high-level-design)
4. [Detailed Component Design](#detailed-component-design)
5. [Cache Patterns](#cache-patterns)
6. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
7. [Failure Scenarios](#failure-scenarios)
8. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **Basic Operations**
   - GET: Retrieve value by key
   - SET: Store key-value pair
   - DELETE: Remove key
   - TTL: Automatic expiration

2. **Advanced Operations**
   - Atomic increments/decrements
   - Conditional writes (CAS)
   - Bulk operations (MGET, MSET)
   - Data structures (lists, sets, hashes)

3. **Features**
   - Key expiration (TTL)
   - Eviction policies
   - Pub/Sub messaging
   - Transactions

### Non-Functional Requirements

1. **Latency**: < 1ms for reads/writes
2. **Throughput**: 100K+ ops/second per node
3. **Availability**: 99.99% uptime
4. **Scalability**: Linear scaling with nodes
5. **Durability**: Configurable (ephemeral vs persistent)
6. **Consistency**: Tunable (strong vs eventual)

---

## Caching Fundamentals

### Why Cache?

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              WHY CACHING?                                            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Without cache:                                                                     │
│  Client → App Server → Database                                                     │
│         └── Response time: 50-100ms                                                │
│                                                                                     │
│  With cache:                                                                        │
│  Client → App Server → Cache (HIT) → Response                                      │
│         └── Response time: 1-5ms                                                   │
│                                                                                     │
│  Client → App Server → Cache (MISS) → Database → Cache → Response                 │
│         └── Response time: 50-100ms (first time)                                   │
│                                                                                     │
│  Performance improvement: 10-50x for cache hits                                    │
│  Database load reduction: 80-95% (typical cache hit rate)                         │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Cache Hit Ratio

```
Cache Hit Ratio = Cache Hits / (Cache Hits + Cache Misses)

Target: > 80% for application cache
Target: > 95% for CDN cache

Factors affecting hit ratio:
- Cache size (larger = more hits)
- TTL duration (longer = more hits, but staler data)
- Access patterns (hot data = better)
- Eviction policy (LRU usually best)
```

### Eviction Policies

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           EVICTION POLICIES                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  1. LRU (Least Recently Used) - Most common                                        │
│     Evicts: Item not accessed for longest time                                     │
│     Use: General purpose caching                                                   │
│                                                                                     │
│  2. LFU (Least Frequently Used)                                                    │
│     Evicts: Item with lowest access count                                          │
│     Use: When popularity is stable                                                 │
│                                                                                     │
│  3. FIFO (First In First Out)                                                      │
│     Evicts: Oldest item by insertion time                                          │
│     Use: When access patterns are uniform                                          │
│                                                                                     │
│  4. Random                                                                          │
│     Evicts: Random item                                                            │
│     Use: When all items equally likely                                             │
│                                                                                     │
│  5. TTL-based                                                                       │
│     Evicts: Expired items first                                                    │
│     Use: Time-sensitive data                                                       │
│                                                                                     │
│  Redis: Uses approximated LRU (samples 5 random keys, evicts least recent)        │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                        DISTRIBUTED CACHE ARCHITECTURE                                │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         APPLICATION SERVERS                                  │   │
│  │                                                                              │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐                      │   │
│  │  │   Server 1   │  │   Server 2   │  │   Server N   │                      │   │
│  │  │              │  │              │  │              │                      │   │
│  │  │ ┌──────────┐ │  │ ┌──────────┐ │  │ ┌──────────┐ │                      │   │
│  │  │ │ L1 Cache │ │  │ │ L1 Cache │ │  │ │ L1 Cache │ │  ← Local (in-memory)│   │
│  │  │ └──────────┘ │  │ └──────────┘ │  │ └──────────┘ │                      │   │
│  │  │              │  │              │  │              │                      │   │
│  │  │ Redis Client │  │ Redis Client │  │ Redis Client │                      │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘                      │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         REDIS CLUSTER (L2 Cache)                             │   │
│  │                                                                              │   │
│  │  ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │  │                      HASH SLOTS (16384)                             │   │   │
│  │  │                                                                      │   │   │
│  │  │  Slot 0-5460          Slot 5461-10922       Slot 10923-16383       │   │   │
│  │  │  ┌────────────┐       ┌────────────┐        ┌────────────┐         │   │   │
│  │  │  │  Shard 1   │       │  Shard 2   │        │  Shard 3   │         │   │   │
│  │  │  │            │       │            │        │            │         │   │   │
│  │  │  │  Primary   │       │  Primary   │        │  Primary   │         │   │   │
│  │  │  │     │      │       │     │      │        │     │      │         │   │   │
│  │  │  │  Replica   │       │  Replica   │        │  Replica   │         │   │   │
│  │  │  │  Replica   │       │  Replica   │        │  Replica   │         │   │   │
│  │  │  └────────────┘       └────────────┘        └────────────┘         │   │   │
│  │  │                                                                      │   │   │
│  │  └─────────────────────────────────────────────────────────────────────┘   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                            DATABASE                                          │   │
│  │                      (Source of truth)                                       │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Redis Cluster Hash Slots

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          HASH SLOT DISTRIBUTION                                      │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Key routing:                                                                       │
│  slot = CRC16(key) mod 16384                                                       │
│                                                                                     │
│  Example:                                                                           │
│  key = "user:123"                                                                  │
│  slot = CRC16("user:123") mod 16384 = 5821                                        │
│  → Routes to Shard 2 (owns slots 5461-10922)                                      │
│                                                                                     │
│  Hash tags (force keys to same slot):                                              │
│  key = "user:{123}:profile"     slot = CRC16("123")                              │
│  key = "user:{123}:settings"    slot = CRC16("123")                              │
│  → Same slot, same shard                                                          │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Component Design

### 1. Cache Client

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            CACHE CLIENT DESIGN                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  class CacheClient:                                                                 │
│      def __init__(self, redis_cluster, local_cache_size=1000):                     │
│          self.redis = redis_cluster                                                │
│          self.local_cache = LRUCache(local_cache_size)  # L1 cache                │
│                                                                                     │
│      async def get(self, key):                                                      │
│          # L1: Check local cache first                                             │
│          value = self.local_cache.get(key)                                         │
│          if value is not None:                                                      │
│              return value                                                           │
│                                                                                     │
│          # L2: Check Redis                                                          │
│          value = await self.redis.get(key)                                         │
│          if value is not None:                                                      │
│              # Populate L1                                                          │
│              self.local_cache.set(key, value)                                      │
│              return value                                                           │
│                                                                                     │
│          return None  # Cache miss                                                  │
│                                                                                     │
│      async def set(self, key, value, ttl=3600):                                    │
│          # Update L2 (Redis)                                                        │
│          await self.redis.setex(key, ttl, value)                                  │
│                                                                                     │
│          # Update L1                                                                │
│          self.local_cache.set(key, value)                                          │
│                                                                                     │
│      async def delete(self, key):                                                   │
│          # Delete from L2                                                           │
│          await self.redis.delete(key)                                              │
│                                                                                     │
│          # Delete from L1                                                           │
│          self.local_cache.delete(key)                                              │
│                                                                                     │
│          # Broadcast invalidation to other servers (via Pub/Sub)                   │
│          await self.redis.publish("invalidations", key)                           │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Consistent Hashing (for custom implementation)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          CONSISTENT HASHING                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Hash Ring:                                                                         │
│                                                                                     │
│                            0°                                                       │
│                            │                                                        │
│                     Node C ●───────● Node A                                        │
│                           /         \                                              │
│                          /           \                                             │
│                   270° ─●─────────────●─ 90°                                       │
│                          \           /                                             │
│                           \         /                                              │
│                            ●───────●                                               │
│                       Node B       Node D                                          │
│                            │                                                        │
│                           180°                                                      │
│                                                                                     │
│  Key "user:123" hashes to position X → Find next node clockwise                   │
│                                                                                     │
│  Virtual nodes (for better distribution):                                          │
│  - Each physical node = 100-200 virtual nodes                                      │
│  - More uniform distribution                                                       │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class ConsistentHash:                                                       │   │
│  │      def __init__(self, nodes, virtual_nodes=150):                          │   │
│  │          self.ring = SortedDict()                                           │   │
│  │          self.virtual_nodes = virtual_nodes                                 │   │
│  │                                                                              │   │
│  │          for node in nodes:                                                  │   │
│  │              self.add_node(node)                                            │   │
│  │                                                                              │   │
│  │      def add_node(self, node):                                               │   │
│  │          for i in range(self.virtual_nodes):                                │   │
│  │              hash_val = md5(f"{node}:{i}") % (2**32)                        │   │
│  │              self.ring[hash_val] = node                                     │   │
│  │                                                                              │   │
│  │      def get_node(self, key):                                                │   │
│  │          hash_val = md5(key) % (2**32)                                      │   │
│  │          # Find first node >= hash_val (clockwise)                         │   │
│  │          idx = self.ring.bisect_right(hash_val)                            │   │
│  │          if idx == len(self.ring):                                          │   │
│  │              idx = 0  # Wrap around                                         │   │
│  │          return self.ring.peekitem(idx)[1]                                  │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Replication and Failover

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                      REPLICATION & FAILOVER                                          │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Redis Sentinel (for Redis non-cluster):                                           │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  ┌──────────────┐                                                           │   │
│  │  │  Sentinel 1  │◄──┐                                                       │   │
│  │  └──────────────┘   │                                                       │   │
│  │         │           │ Quorum voting                                         │   │
│  │  ┌──────────────┐   │                                                       │   │
│  │  │  Sentinel 2  │◄──┤                                                       │   │
│  │  └──────────────┘   │                                                       │   │
│  │         │           │                                                       │   │
│  │  ┌──────────────┐   │                                                       │   │
│  │  │  Sentinel 3  │◄──┘                                                       │   │
│  │  └──────────────┘                                                           │   │
│  │         │                                                                    │   │
│  │         ▼                                                                    │   │
│  │  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐                  │   │
│  │  │   Primary    │───►│   Replica 1  │    │   Replica 2  │                  │   │
│  │  │   (Write)    │    │   (Read)     │    │   (Read)     │                  │   │
│  │  └──────────────┘    └──────────────┘    └──────────────┘                  │   │
│  │                                                                              │   │
│  │  Failover process:                                                          │   │
│  │  1. Sentinels detect primary failure (SDOWN)                               │   │
│  │  2. Sentinels agree primary is down (ODOWN) - quorum                       │   │
│  │  3. One sentinel is elected leader                                          │   │
│  │  4. Leader promotes replica to primary                                      │   │
│  │  5. Other replicas reconfigure to new primary                              │   │
│  │  6. Clients notified of new primary                                         │   │
│  │                                                                              │   │
│  │  Failover time: ~10-30 seconds                                              │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Redis Cluster (built-in failover):                                                │
│  - Each shard has primary + replicas                                               │
│  - Nodes gossip to detect failures                                                 │
│  - Automatic promotion within shard                                                │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Cache Patterns

### 1. Cache-Aside (Lazy Loading)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          CACHE-ASIDE PATTERN                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Read path:                                                                         │
│                                                                                     │
│  ┌────────┐     ┌───────┐     ┌──────┐                                            │
│  │ Client │────►│ Cache │     │  DB  │                                            │
│  └────────┘     └───────┘     └──────┘                                            │
│       │              │                                                              │
│       │  1. Get(key) │                                                              │
│       │─────────────►│                                                              │
│       │              │                                                              │
│       │  2a. HIT: Return value                                                      │
│       │◄─────────────│                                                              │
│       │              │                                                              │
│       │  2b. MISS    │                                                              │
│       │◄─────────────│                                                              │
│       │              │                                                              │
│       │  3. Query DB │                                                              │
│       │─────────────────────────────────►│                                         │
│       │                                  │                                         │
│       │  4. Return value                 │                                         │
│       │◄─────────────────────────────────│                                         │
│       │              │                   │                                         │
│       │  5. Set(key, value, TTL)        │                                         │
│       │─────────────►│                   │                                         │
│       │              │                   │                                         │
│                                                                                     │
│  Code:                                                                              │
│  async def get_user(user_id):                                                       │
│      # Try cache                                                                    │
│      cached = await cache.get(f"user:{user_id}")                                  │
│      if cached:                                                                     │
│          return json.loads(cached)                                                 │
│                                                                                     │
│      # Cache miss - load from DB                                                   │
│      user = await db.query("SELECT * FROM users WHERE id = ?", user_id)           │
│                                                                                     │
│      # Populate cache                                                               │
│      await cache.setex(f"user:{user_id}", 3600, json.dumps(user))                │
│                                                                                     │
│      return user                                                                    │
│                                                                                     │
│  Pros: Only cache what's needed, handles cache failures gracefully                 │
│  Cons: Cache miss = slow, potential for stale data                                 │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Write-Through

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         WRITE-THROUGH PATTERN                                        │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Write path:                                                                        │
│                                                                                     │
│  ┌────────┐     ┌───────┐     ┌──────┐                                            │
│  │ Client │     │ Cache │     │  DB  │                                            │
│  └────────┘     └───────┘     └──────┘                                            │
│       │              │             │                                               │
│       │  1. Write    │             │                                               │
│       │─────────────►│             │                                               │
│       │              │  2. Write   │                                               │
│       │              │────────────►│                                               │
│       │              │             │                                               │
│       │              │  3. ACK     │                                               │
│       │              │◄────────────│                                               │
│       │  4. ACK      │             │                                               │
│       │◄─────────────│             │                                               │
│       │              │             │                                               │
│                                                                                     │
│  Code:                                                                              │
│  async def update_user(user_id, data):                                             │
│      # Write to DB first                                                           │
│      await db.update("users", user_id, data)                                      │
│                                                                                     │
│      # Then update cache                                                           │
│      await cache.setex(f"user:{user_id}", 3600, json.dumps(data))                │
│                                                                                     │
│  Pros: Cache always consistent with DB                                             │
│  Cons: Higher write latency (both DB + cache)                                     │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Write-Behind (Write-Back)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         WRITE-BEHIND PATTERN                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Write path:                                                                        │
│                                                                                     │
│  ┌────────┐     ┌───────┐     ┌───────────┐     ┌──────┐                          │
│  │ Client │     │ Cache │     │   Queue   │     │  DB  │                          │
│  └────────┘     └───────┘     └───────────┘     └──────┘                          │
│       │              │              │                │                             │
│       │  1. Write    │              │                │                             │
│       │─────────────►│              │                │                             │
│       │              │              │                │                             │
│       │  2. ACK      │ 3. Queue     │                │                             │
│       │◄─────────────│─────────────►│                │                             │
│       │              │              │                │                             │
│       │              │              │ 4. Async write │                             │
│       │              │              │───────────────►│                             │
│       │              │              │                │                             │
│                                                                                     │
│  Code:                                                                              │
│  async def update_user(user_id, data):                                             │
│      # Write to cache immediately                                                  │
│      await cache.setex(f"user:{user_id}", 3600, json.dumps(data))                │
│                                                                                     │
│      # Queue for async DB write                                                    │
│      await queue.push("db_writes", {                                              │
│          "table": "users",                                                         │
│          "id": user_id,                                                            │
│          "data": data                                                              │
│      })                                                                             │
│                                                                                     │
│  Pros: Fast writes, reduced DB load                                                │
│  Cons: Risk of data loss if cache fails before DB write                           │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 4. Read-Through

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         READ-THROUGH PATTERN                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Similar to cache-aside, but cache handles DB loading:                             │
│                                                                                     │
│  ┌────────┐     ┌───────────────────┐     ┌──────┐                                │
│  │ Client │     │ Cache (with loader)│     │  DB  │                                │
│  └────────┘     └───────────────────┘     └──────┘                                │
│       │                   │                    │                                   │
│       │  1. Get(key)      │                    │                                   │
│       │──────────────────►│                    │                                   │
│       │                   │                    │                                   │
│       │                   │  2. Load from DB   │                                   │
│       │                   │   (on cache miss)  │                                   │
│       │                   │───────────────────►│                                   │
│       │                   │                    │                                   │
│       │                   │  3. Store in cache │                                   │
│       │                   │◄───────────────────│                                   │
│       │                   │                    │                                   │
│       │  4. Return value  │                    │                                   │
│       │◄──────────────────│                    │                                   │
│       │                   │                    │                                   │
│                                                                                     │
│  Code (using Caffeine/Guava):                                                      │
│  cache = CacheBuilder.newBuilder()                                                 │
│      .maximumSize(10000)                                                           │
│      .expireAfterWrite(1, TimeUnit.HOURS)                                         │
│      .build(new CacheLoader<String, User>() {                                     │
│          public User load(String key) {                                           │
│              return db.getUser(key);                                              │
│          }                                                                         │
│      });                                                                            │
│                                                                                     │
│  Pros: Simpler application code                                                    │
│  Cons: Less flexibility, cache library must support                               │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### Redis vs Memcached

| Feature | Redis | Memcached |
|---------|-------|-----------|
| Data structures | Rich (lists, sets, hashes) | Key-value only |
| Persistence | Yes (RDB, AOF) | No |
| Replication | Yes | No |
| Clustering | Yes (native) | Client-side |
| Memory efficiency | Lower | Higher |
| Multi-threading | Single-threaded* | Multi-threaded |
| Use case | General purpose | Simple caching |

*Redis 6+ has I/O threading

### Consistency vs Availability

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                    CONSISTENCY vs AVAILABILITY                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Strong consistency (Synchronous replication):                                      │
│  - Write confirmed only after all replicas ACK                                     │
│  - Higher latency                                                                  │
│  - Risk of unavailability if replica down                                         │
│                                                                                     │
│  Eventual consistency (Async replication):                                          │
│  - Write confirmed after primary ACK                                               │
│  - Lower latency                                                                   │
│  - Risk of reading stale data                                                      │
│                                                                                     │
│  Redis default: Async replication (eventual consistency)                           │
│  - WAIT command for synchronous when needed                                        │
│                                                                                     │
│  Example:                                                                           │
│  # Async (default)                                                                 │
│  await redis.set("key", "value")                                                  │
│                                                                                     │
│  # Sync (wait for 1 replica)                                                      │
│  await redis.set("key", "value")                                                  │
│  await redis.wait(1, 1000)  # Wait for 1 replica, 1000ms timeout                 │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Failure Scenarios

### Cache Stampede (Thundering Herd)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          CACHE STAMPEDE                                              │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Problem:                                                                           │
│  1. Popular key expires                                                            │
│  2. 1000 requests hit simultaneously                                               │
│  3. All 1000 go to database (cache miss)                                          │
│  4. Database overwhelmed                                                           │
│                                                                                     │
│  Solution 1: Locking (single flight)                                               │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  async def get_with_lock(key):                                               │   │
│  │      value = await cache.get(key)                                           │   │
│  │      if value:                                                               │   │
│  │          return value                                                        │   │
│  │                                                                              │   │
│  │      # Try to acquire lock                                                   │   │
│  │      lock = await cache.set(f"lock:{key}", "1", nx=True, ex=10)            │   │
│  │                                                                              │   │
│  │      if lock:                                                                │   │
│  │          # We have the lock - fetch from DB                                 │   │
│  │          value = await db.get(key)                                          │   │
│  │          await cache.setex(key, 3600, value)                               │   │
│  │          await cache.delete(f"lock:{key}")                                 │   │
│  │          return value                                                        │   │
│  │      else:                                                                   │   │
│  │          # Someone else is fetching - wait and retry                        │   │
│  │          await asyncio.sleep(0.1)                                           │   │
│  │          return await get_with_lock(key)                                    │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Solution 2: Early refresh (before expiry)                                         │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  TTL = 3600 (1 hour)                                                        │   │
│  │  REFRESH_THRESHOLD = 300 (5 minutes)                                        │   │
│  │                                                                              │   │
│  │  async def get_with_early_refresh(key):                                      │   │
│  │      value, ttl = await cache.get_with_ttl(key)                            │   │
│  │                                                                              │   │
│  │      if value:                                                               │   │
│  │          # Check if approaching expiry                                      │   │
│  │          if ttl < REFRESH_THRESHOLD:                                        │   │
│  │              # Refresh in background                                        │   │
│  │              asyncio.create_task(refresh_cache(key))                       │   │
│  │          return value                                                        │   │
│  │                                                                              │   │
│  │      return await refresh_cache(key)                                         │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Solution 3: Stale-while-revalidate                                                │
│  - Serve stale data while refreshing in background                                 │
│  - Never let cache fully expire                                                    │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Hot Key Problem

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            HOT KEY PROBLEM                                           │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Problem:                                                                           │
│  - Single key gets extreme traffic (celebrity tweet, viral product)                │
│  - One Redis node overwhelmed                                                      │
│                                                                                     │
│  Solution 1: Local caching                                                          │
│  - Cache hot keys in application memory                                            │
│  - Short TTL (seconds)                                                             │
│                                                                                     │
│  Solution 2: Key replication                                                        │
│  - Duplicate hot key across multiple keys                                          │
│  - Random suffix to distribute reads                                               │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  # Write to multiple keys                                                    │   │
│  │  async def set_hot_key(key, value, replicas=5):                             │   │
│  │      for i in range(replicas):                                              │   │
│  │          await cache.set(f"{key}:{i}", value)                              │   │
│  │                                                                              │   │
│  │  # Read from random replica                                                 │   │
│  │  async def get_hot_key(key, replicas=5):                                    │   │
│  │      replica = random.randint(0, replicas - 1)                             │   │
│  │      return await cache.get(f"{key}:{replica}")                            │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Solution 3: Read replicas                                                          │
│  - Redis with read replicas                                                        │
│  - Route reads to replicas, writes to primary                                     │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Cache Invalidation

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         CACHE INVALIDATION                                           │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  "There are only two hard things in Computer Science:                              │
│   cache invalidation and naming things." - Phil Karlton                            │
│                                                                                     │
│  Strategies:                                                                        │
│                                                                                     │
│  1. TTL-based (time-to-live)                                                       │
│     Pros: Simple, automatic                                                        │
│     Cons: Stale data until TTL expires                                            │
│                                                                                     │
│  2. Event-based (on update)                                                        │
│     ┌─────────────────────────────────────────────────────────────────────────┐   │
│     │                                                                          │   │
│     │  async def update_user(user_id, data):                                   │   │
│     │      await db.update(user_id, data)                                     │   │
│     │      await cache.delete(f"user:{user_id}")                             │   │
│     │      # Or update                                                        │   │
│     │      await cache.set(f"user:{user_id}", data)                          │   │
│     │                                                                          │   │
│     └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  3. CDC (Change Data Capture)                                                      │
│     - Debezium captures DB changes                                                 │
│     - Publishes to Kafka                                                           │
│     - Consumer invalidates cache                                                   │
│                                                                                     │
│     DB → Debezium → Kafka → Cache Invalidator → Redis                             │
│                                                                                     │
│  4. Pub/Sub broadcast (for multi-server L1 cache)                                  │
│     ┌─────────────────────────────────────────────────────────────────────────┐   │
│     │                                                                          │   │
│     │  # On update                                                             │   │
│     │  await redis.publish("invalidations", f"user:{user_id}")               │   │
│     │                                                                          │   │
│     │  # All servers subscribe                                                 │   │
│     │  async def listen_invalidations():                                       │   │
│     │      async for message in pubsub.subscribe("invalidations"):           │   │
│     │          local_cache.delete(message.data)                               │   │
│     │                                                                          │   │
│     └─────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Interviewer Questions & Answers

### Q1: How do you design a cache for a high-read, low-write system?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│  High Read / Low Write System                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Pattern: Cache-Aside with aggressive TTL                       │
│                                                                 │
│  class HighReadCache:                                            │
│      async def get(self, key):                                   │
│          # L1: Local cache (1000 most accessed)                 │
│          if key in self.local_cache:                            │
│              return self.local_cache[key]                       │
│                                                                 │
│          # L2: Redis                                            │
│          value = await self.redis.get(key)                     │
│          if value:                                              │
│              self.local_cache[key] = value                     │
│              return value                                       │
│                                                                 │
│          # L3: Database                                         │
│          value = await self.db.get(key)                        │
│          await self.redis.setex(key, 86400, value)  # 24hr TTL│
│          self.local_cache[key] = value                         │
│          return value                                           │
│                                                                 │
│  On write (rare):                                                │
│  - Update DB                                                    │
│  - Delete from Redis (not update - safer)                       │
│  - Publish invalidation for local caches                        │
│                                                                 │
│  Optimizations:                                                  │
│  - Pre-warm cache on startup                                    │
│  - Read replicas for Redis                                      │
│  - Long TTLs (data changes rarely)                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

### Q2: How do you handle cache consistency with database?

**Answer:**

**Problem:** Cache and DB can get out of sync

**Solutions by consistency requirement:**

1. **Strong consistency (banking, inventory):**
```python
async def update_with_strong_consistency(key, value):
    async with distributed_lock(key):
        await db.update(key, value)
        await cache.set(key, value)
```

2. **Eventual consistency (most use cases):**
```python
async def update_with_eventual_consistency(key, value):
    await db.update(key, value)
    await cache.delete(key)  # Delete, not update (safer)
    # Next read will populate cache
```

3. **Write-through with DB transaction:**
```python
async def transactional_update(key, value):
    async with db.transaction():
        await db.update(key, value)
        await cache.set(key, value)
        # Both succeed or both fail
```

**Why delete instead of update:**
```
Race condition with update:
1. Thread A: DB update (value = 1)
2. Thread B: DB update (value = 2)
3. Thread B: Cache update (value = 2)
4. Thread A: Cache update (value = 1)  ← Stale!

With delete:
1. Thread A: DB update (value = 1)
2. Thread B: DB update (value = 2)
3. Thread B: Cache delete
4. Thread A: Cache delete
5. Next read: Gets fresh value from DB
```

---

### Q3: How do you handle a cache failure?

**Answer:**

**Graceful degradation:**

```python
class ResilientCache:
    def __init__(self, redis, fallback_ttl=60):
        self.redis = redis
        self.circuit_breaker = CircuitBreaker()
        self.local_fallback = TTLCache(maxsize=1000, ttl=fallback_ttl)
    
    async def get(self, key):
        # Check circuit breaker
        if self.circuit_breaker.is_open():
            return self.local_fallback.get(key)
        
        try:
            value = await asyncio.wait_for(
                self.redis.get(key),
                timeout=0.1  # 100ms timeout
            )
            self.circuit_breaker.record_success()
            return value
        except (RedisError, asyncio.TimeoutError) as e:
            self.circuit_breaker.record_failure()
            logger.warning(f"Cache failure: {e}")
            return self.local_fallback.get(key)
    
    async def set(self, key, value, ttl=3600):
        # Always update local fallback
        self.local_fallback[key] = value
        
        if self.circuit_breaker.is_open():
            return  # Skip Redis
        
        try:
            await asyncio.wait_for(
                self.redis.setex(key, ttl, value),
                timeout=0.1
            )
            self.circuit_breaker.record_success()
        except (RedisError, asyncio.TimeoutError):
            self.circuit_breaker.record_failure()
```

**Circuit breaker states:**
- Closed: Normal operation
- Open: All requests bypass Redis (after N failures)
- Half-open: Test if Redis recovered

---

### Q4: How do you choose between Redis and Memcached?

**Answer:**

**Choose Redis when:**
- Need data structures (lists, sets, sorted sets, hashes)
- Need persistence (survive restarts)
- Need replication for HA
- Need Pub/Sub
- Need transactions (MULTI/EXEC)
- Need Lua scripting

**Choose Memcached when:**
- Simple key-value caching only
- Maximum memory efficiency (20% less than Redis)
- Multi-threaded performance needed
- No persistence required
- Simpler operational model

**Real-world choice:** Most teams choose Redis because:
1. More versatile (can do what Memcached does + more)
2. Better ecosystem (Redis Cluster, Sentinel)
3. Active development and community

---

### Q5: How do you monitor cache performance?

**Answer:**

**Key metrics:**

```python
class CacheMetrics:
    def record_operation(self, operation, key, hit, latency):
        # Hit rate
        metrics.increment(
            "cache.requests",
            tags={"operation": operation, "hit": hit}
        )
        
        # Latency
        metrics.timing("cache.latency", latency, tags={"operation": operation})
        
        # Memory usage (periodic)
        info = await redis.info("memory")
        metrics.gauge("cache.memory_used", info["used_memory"])
        
        # Evictions (indicates cache is too small)
        metrics.gauge("cache.evicted_keys", info["evicted_keys"])
```

**Dashboard alerts:**
```yaml
alerts:
  - name: low_cache_hit_rate
    condition: cache.hit_rate < 0.7
    severity: warning
    
  - name: high_cache_latency
    condition: p99(cache.latency) > 10ms
    severity: critical
    
  - name: high_eviction_rate
    condition: rate(cache.evicted_keys) > 100/min
    severity: warning  # May need more memory
    
  - name: cache_memory_high
    condition: cache.memory_used > 0.9 * cache.max_memory
    severity: warning
```

---

### Q6: How do you handle caching for a multi-tenant system?

**Answer:**

**Key isolation strategies:**

```python
# Strategy 1: Key prefix
def get_tenant_key(tenant_id, key):
    return f"tenant:{tenant_id}:{key}"

# Strategy 2: Separate databases (Redis 0-15)
def get_tenant_db(tenant_id):
    return tenant_id % 16  # Map to Redis DB

# Strategy 3: Separate clusters (for large tenants)
tenant_clusters = {
    "enterprise_1": RedisCluster("cluster1.redis.com"),
    "enterprise_2": RedisCluster("cluster2.redis.com"),
    "default": RedisCluster("default.redis.com")
}
```

**Rate limiting per tenant:**
```python
async def get_with_rate_limit(tenant_id, key):
    # Check tenant quota
    usage = await redis.incr(f"quota:{tenant_id}:{current_minute()}")
    if usage > tenant_quota[tenant_id]:
        raise RateLimitExceeded()
    
    return await cache.get(get_tenant_key(tenant_id, key))
```

---

### Q7: How do you implement cache warming?

**Answer:**

**Strategies:**

```python
# 1. Startup warming (for predictable hot data)
async def warm_cache_on_startup():
    # Top 1000 products
    popular_products = await db.query(
        "SELECT * FROM products ORDER BY views DESC LIMIT 1000"
    )
    for product in popular_products:
        await cache.set(f"product:{product.id}", product)
    
    # User sessions (from recent activity)
    active_users = await db.query(
        "SELECT * FROM users WHERE last_active > NOW() - INTERVAL '1 hour'"
    )
    for user in active_users:
        await cache.set(f"user:{user.id}", user)

# 2. Background warming (continuous)
async def background_warmer():
    while True:
        # Find keys about to expire
        expiring_keys = await get_keys_expiring_soon()
        for key in expiring_keys:
            await refresh_cache(key)
        await asyncio.sleep(60)

# 3. Predictive warming (ML-based)
async def predictive_warm():
    # Predict what will be accessed soon
    predictions = await ml_model.predict_access(
        time=next_hour(),
        features=current_context()
    )
    for key in predictions[:1000]:
        await refresh_cache(key)
```

---

### Q8: How do you handle cache for data that changes frequently?

**Answer:**

**Short TTL + Background refresh:**

```python
class FrequentlyChangingCache:
    def __init__(self, short_ttl=10, refresh_interval=5):
        self.short_ttl = short_ttl
        self.refresh_interval = refresh_interval
        
    async def get(self, key):
        value = await cache.get(key)
        if value:
            return value
        
        # Cache miss - fetch and cache with short TTL
        value = await db.get(key)
        await cache.setex(key, self.short_ttl, value)
        return value
    
    async def background_refresher(self, keys_to_track):
        """Proactively refresh before expiry"""
        while True:
            for key in keys_to_track:
                try:
                    value = await db.get(key)
                    await cache.setex(key, self.short_ttl, value)
                except Exception as e:
                    logger.error(f"Refresh failed for {key}: {e}")
            
            await asyncio.sleep(self.refresh_interval)
```

**Real-time updates with Pub/Sub:**

```python
# Publisher (on data change)
async def on_data_change(key, new_value):
    await db.update(key, new_value)
    await redis.publish("data_changes", json.dumps({
        "key": key,
        "value": new_value
    }))

# Subscriber (all app servers)
async def subscribe_to_changes():
    async for message in pubsub.subscribe("data_changes"):
        data = json.loads(message.data)
        local_cache[data["key"]] = data["value"]
```

---

### Q9: Design a distributed cache for sessions.

**Answer:**

**Session cache design:**

```python
class SessionCache:
    def __init__(self, redis_cluster):
        self.redis = redis_cluster
        self.session_ttl = 3600 * 24  # 24 hours
    
    async def create_session(self, user_id, data):
        session_id = str(uuid.uuid4())
        session_key = f"session:{session_id}"
        
        session = {
            "user_id": user_id,
            "created_at": time.time(),
            "data": data
        }
        
        # Store session
        await self.redis.setex(
            session_key,
            self.session_ttl,
            json.dumps(session)
        )
        
        # Index by user (for "logout all devices")
        await self.redis.sadd(f"user_sessions:{user_id}", session_id)
        
        return session_id
    
    async def get_session(self, session_id):
        session_key = f"session:{session_id}"
        data = await self.redis.get(session_key)
        
        if not data:
            return None
        
        # Extend TTL on access (sliding expiration)
        await self.redis.expire(session_key, self.session_ttl)
        
        return json.loads(data)
    
    async def invalidate_session(self, session_id):
        session = await self.get_session(session_id)
        if session:
            await self.redis.delete(f"session:{session_id}")
            await self.redis.srem(
                f"user_sessions:{session['user_id']}",
                session_id
            )
    
    async def invalidate_all_user_sessions(self, user_id):
        session_ids = await self.redis.smembers(f"user_sessions:{user_id}")
        for session_id in session_ids:
            await self.redis.delete(f"session:{session_id}")
        await self.redis.delete(f"user_sessions:{user_id}")
```

**Considerations:**
- Sticky sessions vs shared sessions
- Session size (keep small, <1KB)
- Security (encrypt sensitive data)

---

### Q10: How do you scale a cache system?

**Answer:**

**Horizontal scaling strategies:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    SCALING STRATEGIES                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Read replicas (scale reads)                                 │
│     Primary (writes) → Replica 1 (reads)                       │
│                      → Replica 2 (reads)                       │
│                      → Replica N (reads)                       │
│                                                                 │
│  2. Sharding (scale capacity)                                   │
│     Keys a-f → Shard 1                                         │
│     Keys g-l → Shard 2                                         │
│     Keys m-z → Shard 3                                         │
│                                                                 │
│  3. Redis Cluster (automatic sharding + replication)            │
│     - 16384 hash slots                                         │
│     - Automatic rebalancing                                    │
│     - Built-in failover                                        │
│                                                                 │
│  4. Multi-level caching (scale hot data)                        │
│     L1: Application memory (microseconds)                      │
│     L2: Redis cluster (milliseconds)                           │
│     L3: Database (tens of milliseconds)                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Capacity planning:**
```python
# Estimate memory needs
def estimate_memory(
    num_keys,
    avg_key_size,
    avg_value_size,
    overhead_factor=2  # Redis overhead
):
    raw_size = num_keys * (avg_key_size + avg_value_size)
    with_overhead = raw_size * overhead_factor
    return with_overhead

# Example
memory_needed = estimate_memory(
    num_keys=100_000_000,
    avg_key_size=50,
    avg_value_size=500,
    overhead_factor=2
)
# = 100M * 550 * 2 = 110 GB

# With 3 shards: 37 GB per shard
# With replication: 74 GB total per shard (primary + replica)
```

---

### Bonus: Quick Reference

```
┌─────────────────────────────────────────────────────────────────┐
│             DISTRIBUTED CACHE - QUICK REFERENCE                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Patterns:                                                      │
│  ├── Cache-Aside: App manages cache + DB                       │
│  ├── Write-Through: Sync write to both                         │
│  ├── Write-Behind: Async write to DB                           │
│  └── Read-Through: Cache loads from DB                         │
│                                                                 │
│  Eviction:                                                      │
│  ├── LRU (most common)                                         │
│  ├── LFU (stable popularity)                                   │
│  └── TTL (time-based expiry)                                   │
│                                                                 │
│  Problems & Solutions:                                          │
│  ├── Stampede → Locking / Early refresh                       │
│  ├── Hot key → Replication / Local cache                      │
│  ├── Inconsistency → Delete on write / CDC                    │
│  └── Failure → Circuit breaker / Fallback                     │
│                                                                 │
│  Redis Cluster:                                                 │
│  ├── 16384 hash slots                                          │
│  ├── slot = CRC16(key) % 16384                                 │
│  └── Hash tags for co-location: {user}:profile                │
│                                                                 │
│  Targets:                                                       │
│  ├── Hit rate: > 80%                                           │
│  ├── Latency: < 1ms                                            │
│  └── Availability: 99.99%                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
