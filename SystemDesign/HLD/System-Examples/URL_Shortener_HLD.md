# URL Shortener (TinyURL) - High Level Design

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Capacity Estimation](#capacity-estimation)
3. [High-Level Design](#high-level-design)
4. [Request Flows](#request-flows)
5. [Detailed Component Design](#detailed-component-design)
6. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
7. [Failure Scenarios and Bottlenecks](#failure-scenarios-and-bottlenecks)
8. [Future Improvements](#future-improvements)
9. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **URL Shortening**: Given a long URL, generate a shorter and unique alias
2. **URL Redirection**: When users access the short URL, redirect them to the original URL
3. **Custom Aliases**: Users can optionally choose a custom short link for their URL
4. **Link Expiration**: Support optional expiration time for shortened URLs
5. **Analytics**: Track click statistics (optional) - number of redirects, geographic location, referrer
6. **User Accounts**: Allow users to create accounts to manage their URLs (optional)

### Non-Functional Requirements

1. **High Availability**: System should be highly available (99.99% uptime)
2. **Low Latency**: URL redirection should happen in real-time (<100ms)
3. **Scalability**: Handle 100M+ URL shortenings per day
4. **Durability**: Links should not be lost; data should be persisted reliably
5. **Security**: Prevent malicious URLs, implement rate limiting
6. **Read Heavy**: System will be read-heavy (100:1 read to write ratio)

---

## Capacity Estimation

### Traffic Estimates

```
Assumptions:
- 100 Million new URLs shortened per day
- Read:Write ratio = 100:1

Write Operations:
- New URLs/day: 100 Million
- New URLs/second: 100M / (24 * 3600) ≈ 1,160 URLs/sec

Read Operations:
- Redirections/day: 100M * 100 = 10 Billion
- Redirections/second: 10B / (24 * 3600) ≈ 115,740 redirects/sec
```

### Storage Estimates

```
Assumptions:
- Each URL mapping: ~500 bytes
  - Short URL: 7 bytes
  - Long URL: 200 bytes
  - Created date: 8 bytes
  - Expiration date: 8 bytes
  - User ID: 8 bytes
  - Other metadata: ~270 bytes

- Retention period: 5 years
- Total URLs in 5 years: 100M * 365 * 5 = 182.5 Billion URLs

Storage Required:
- 182.5B * 500 bytes = 91.25 TB
- With replication (3x): ~275 TB
```

### Bandwidth Estimates

```
Incoming (Write):
- 1,160 URLs/sec * 500 bytes = 580 KB/sec

Outgoing (Read):
- 115,740 redirects/sec * 500 bytes = 57.87 MB/sec
```

### Memory Estimates (Caching)

```
Cache hot URLs (20% of traffic generates 80% of requests - Pareto)
- URLs to cache: 10B * 0.2 = 2 Billion URLs
- Cache storage: 2B * 500 bytes = 1 TB

Daily cache refresh:
- 10B * 0.2 * 500 bytes = 1 TB per day for hot URLs
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                        │
│                    (Web Browsers, Mobile Apps, API Clients)                      │
└─────────────────────────────────────┬───────────────────────────────────────────┘
                                      │
                                      ▼
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              LOAD BALANCER                                       │
│                         (AWS ALB / Nginx / HAProxy)                             │
└─────────────────────────────────────┬───────────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    │                                   │
                    ▼                                   ▼
┌───────────────────────────────────┐   ┌───────────────────────────────────┐
│         API GATEWAY               │   │           CDN (Optional)          │
│    (Rate Limiting, Auth, SSL)     │   │      (Static Content Caching)     │
└───────────────────┬───────────────┘   └───────────────────────────────────┘
                    │
        ┌───────────┴───────────┬───────────────────────┐
        │                       │                       │
        ▼                       ▼                       ▼
┌───────────────┐      ┌───────────────┐      ┌───────────────┐
│ URL Shortening│      │  Redirection  │      │   Analytics   │
│    Service    │      │    Service    │      │    Service    │
└───────┬───────┘      └───────┬───────┘      └───────┬───────┘
        │                      │                      │
        │              ┌───────┴───────┐              │
        │              │               │              │
        │              ▼               │              │
        │      ┌───────────────┐       │              │
        │      │  CACHE LAYER  │       │              │
        │      │    (Redis)    │       │              │
        │      │               │       │              │
        │      │ ┌───────────┐ │       │              │
        │      │ │ Hot URLs  │ │       │              │
        │      │ └───────────┘ │       │              │
        │      └───────┬───────┘       │              │
        │              │               │              │
        ▼              ▼               │              ▼
┌─────────────────────────────────────────────────────────────────┐
│                        DATABASE LAYER                            │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │
│  │   Primary DB    │  │   Replica DB    │  │  Analytics DB   │  │
│  │  (Cassandra/    │  │  (Read Replica) │  │ (ClickHouse/    │  │
│  │   DynamoDB)     │  │                 │  │    Druid)       │  │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                     KEY GENERATION SERVICE                       │
│  ┌─────────────────┐  ┌─────────────────┐                       │
│  │  Key Generator  │  │   Key Database  │                       │
│  │  (Standalone)   │  │  (Pre-generated)│                       │
│  └─────────────────┘  └─────────────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **Load Balancer**: Distributes traffic across application servers
2. **API Gateway**: Handles authentication, rate limiting, SSL termination
3. **URL Shortening Service**: Creates short URLs from long URLs
4. **Redirection Service**: Handles URL lookups and redirects
5. **Key Generation Service**: Generates unique short keys
6. **Cache Layer**: Stores frequently accessed URLs
7. **Database**: Persistent storage for URL mappings
8. **Analytics Service**: Tracks and stores click statistics

---

## Request Flows

### URL Shortening Flow

```
┌────────┐     ┌──────────┐     ┌───────────┐     ┌─────────────┐     ┌────────┐
│ Client │     │    LB    │     │ API GW    │     │ URL Service │     │   DB   │
└───┬────┘     └────┬─────┘     └─────┬─────┘     └──────┬──────┘     └───┬────┘
    │               │                 │                  │                │
    │ POST /shorten │                 │                  │                │
    │──────────────>│                 │                  │                │
    │               │ Forward Request │                  │                │
    │               │────────────────>│                  │                │
    │               │                 │ Validate & Auth  │                │
    │               │                 │─────────────────>│                │
    │               │                 │                  │                │
    │               │                 │                  │ Get Unique Key │
    │               │                 │                  │───────────────>│
    │               │                 │                  │                │
    │               │                 │                  │<───────────────│
    │               │                 │                  │                │
    │               │                 │                  │ Store Mapping  │
    │               │                 │                  │───────────────>│
    │               │                 │                  │                │
    │               │                 │                  │<───────────────│
    │               │                 │                  │                │
    │               │                 │<─────────────────│                │
    │               │<────────────────│                  │                │
    │<──────────────│ Return Short URL│                  │                │
    │               │                 │                  │                │
```

### URL Redirection Flow

```
┌────────┐     ┌──────────┐     ┌───────────┐     ┌─────────────┐     ┌───────┐     ┌────────┐
│ Client │     │    LB    │     │ Redirect  │     │    Cache    │     │  DB   │     │Analytics│
└───┬────┘     └────┬─────┘     │  Service  │     │   (Redis)   │     │       │     │ Service │
    │               │           └─────┬─────┘     └──────┬──────┘     └───┬───┘     └───┬─────┘
    │ GET /abc123   │                 │                  │                │              │
    │──────────────>│                 │                  │                │              │
    │               │────────────────>│                  │                │              │
    │               │                 │ Check Cache      │                │              │
    │               │                 │─────────────────>│                │              │
    │               │                 │                  │                │              │
    │               │                 │ Cache HIT        │                │              │
    │               │                 │<─────────────────│                │              │
    │               │                 │                  │                │              │
    │               │                 │ (If Cache MISS)  │                │              │
    │               │                 │─────────────────────────────────>│              │
    │               │                 │                  │                │              │
    │               │                 │<─────────────────────────────────│              │
    │               │                 │                  │                │              │
    │               │                 │ Update Cache     │                │              │
    │               │                 │─────────────────>│                │              │
    │               │                 │                  │                │              │
    │               │                 │ Log Click (Async)│                │              │
    │               │                 │─────────────────────────────────────────────────>│
    │               │<────────────────│                  │                │              │
    │<──────────────│ 301/302 Redirect│                  │                │              │
    │               │                 │                  │                │              │
```

---

## Detailed Component Design

### 1. Key Generation Service (Deep Dive)

The key generation is critical for creating unique, short URLs. Several approaches exist:

#### Approach 1: Base62 Encoding with Counter

```
Characters: a-z, A-Z, 0-9 (62 characters)
Key Length: 7 characters

Possible combinations: 62^7 = 3.5 trillion unique keys

Example:
Counter: 1000000
Base62: "4c92"
Padded: "0004c92"
```

**Pros**: Simple, sequential, guaranteed unique
**Cons**: Predictable URLs, single point of failure

#### Approach 2: MD5/SHA256 Hash + Base62

```
Input: Long URL + Timestamp + User ID
Hash: MD5(input) = "d41d8cd98f00b204e9800998ecf8427e"
Take first 43 bits → Convert to Base62 → 7 character key

Example:
Long URL: "https://example.com/very/long/path"
MD5: "a1b2c3d4e5f6..."
Base62 (first 7): "Xk9P2mQ"
```

**Pros**: Distributed, unpredictable
**Cons**: Collision handling required

#### Approach 3: Pre-generated Key Database (KGS)

```
┌─────────────────────────────────────────────────────────┐
│                 Key Generation Service                   │
├─────────────────────────────────────────────────────────┤
│  ┌─────────────────┐    ┌─────────────────┐            │
│  │   Used Keys     │    │  Unused Keys    │            │
│  │    Database     │    │   Database      │            │
│  │                 │    │                 │            │
│  │ abc1234 ✓       │    │ xyz7890         │            │
│  │ def5678 ✓       │    │ mno3456         │            │
│  │ ...             │    │ ...             │            │
│  └─────────────────┘    └─────────────────┘            │
│                                                         │
│  Worker Process:                                        │
│  1. Load batch of keys into memory                      │
│  2. Mark as "in-use" in DB                             │
│  3. Serve keys from memory                              │
│  4. When batch depleted, load new batch                 │
└─────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class KeyGenerationService:
    def __init__(self):
        self.key_batch = []
        self.batch_size = 10000
        self.lock = threading.Lock()
    
    def get_key(self):
        with self.lock:
            if not self.key_batch:
                self._load_new_batch()
            return self.key_batch.pop()
    
    def _load_new_batch(self):
        # Atomic operation: SELECT and UPDATE in transaction
        self.key_batch = db.execute("""
            UPDATE keys 
            SET used = true 
            WHERE key_id IN (
                SELECT key_id FROM keys 
                WHERE used = false 
                LIMIT %s
                FOR UPDATE SKIP LOCKED
            )
            RETURNING short_key
        """, self.batch_size)
```

### 2. Database Design (Deep Dive)

#### URL Mapping Table Schema

```sql
CREATE TABLE url_mappings (
    short_key       VARCHAR(7) PRIMARY KEY,
    long_url        TEXT NOT NULL,
    user_id         BIGINT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMP,
    click_count     BIGINT DEFAULT 0,
    is_active       BOOLEAN DEFAULT true,
    
    INDEX idx_user_id (user_id),
    INDEX idx_expires_at (expires_at)
);

-- For analytics
CREATE TABLE click_events (
    event_id        UUID PRIMARY KEY,
    short_key       VARCHAR(7),
    clicked_at      TIMESTAMP,
    ip_address      INET,
    user_agent      TEXT,
    referrer        TEXT,
    country         VARCHAR(2),
    
    INDEX idx_short_key (short_key),
    INDEX idx_clicked_at (clicked_at)
);
```

#### Database Choice: Cassandra

**Why Cassandra?**
- High write throughput
- Horizontal scalability
- No single point of failure
- Tunable consistency

```
Cassandra Data Model:

CREATE KEYSPACE url_shortener WITH replication = {
    'class': 'NetworkTopologyStrategy',
    'datacenter1': 3,
    'datacenter2': 3
};

CREATE TABLE url_mappings (
    short_key text PRIMARY KEY,
    long_url text,
    user_id bigint,
    created_at timestamp,
    expires_at timestamp,
    click_count counter
) WITH compaction = {'class': 'LeveledCompactionStrategy'};

-- Partition key: short_key (ensures even distribution)
-- Replication factor: 3 (high availability)
```

### 3. Caching Strategy (Deep Dive)

```
┌─────────────────────────────────────────────────────────┐
│                    CACHING ARCHITECTURE                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │              Application Server                  │   │
│  │  ┌─────────────────────────────────────────┐    │   │
│  │  │         Local Cache (LRU)               │    │   │
│  │  │         Size: 1GB, TTL: 5 min           │    │   │
│  │  └─────────────────────────────────────────┘    │   │
│  └──────────────────────┬──────────────────────────┘   │
│                         │                               │
│                         ▼                               │
│  ┌─────────────────────────────────────────────────┐   │
│  │           Redis Cluster (Distributed)            │   │
│  │                                                  │   │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐        │   │
│  │  │ Shard 1  │ │ Shard 2  │ │ Shard 3  │        │   │
│  │  │ (Master) │ │ (Master) │ │ (Master) │        │   │
│  │  │    +     │ │    +     │ │    +     │        │   │
│  │  │ Replica  │ │ Replica  │ │ Replica  │        │   │
│  │  └──────────┘ └──────────┘ └──────────┘        │   │
│  │                                                  │   │
│  │  Total Size: 1TB, TTL: 24 hours                 │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘

Cache Strategy: Cache-Aside (Lazy Loading)

Read Path:
1. Check local cache → HIT: return
2. Check Redis → HIT: update local cache, return
3. Query DB → Update Redis → Update local cache → return

Write Path:
1. Write to DB
2. Invalidate Redis cache
3. Invalidate local cache (via pub/sub)
```

**Cache Eviction Policy:**

```python
class URLCache:
    def __init__(self, redis_client, local_cache_size=10000):
        self.redis = redis_client
        self.local_cache = LRUCache(local_cache_size)
        self.TTL = 86400  # 24 hours
    
    def get_url(self, short_key):
        # Level 1: Local cache
        if short_key in self.local_cache:
            return self.local_cache[short_key]
        
        # Level 2: Redis
        long_url = self.redis.get(f"url:{short_key}")
        if long_url:
            self.local_cache[short_key] = long_url
            return long_url
        
        # Level 3: Database
        long_url = self.db.get(short_key)
        if long_url:
            self.redis.setex(f"url:{short_key}", self.TTL, long_url)
            self.local_cache[short_key] = long_url
        
        return long_url
    
    def invalidate(self, short_key):
        self.redis.delete(f"url:{short_key}")
        self.local_cache.pop(short_key, None)
        # Broadcast invalidation to other servers
        self.redis.publish("cache_invalidation", short_key)
```

---

## Trade-offs and Tech Choices

### 1. Short Key Length

| Length | Combinations | Trade-off |
|--------|--------------|-----------|
| 6 chars | 56.8B | May run out in ~1.5 years at 100M/day |
| 7 chars | 3.5T | Good for 95+ years |
| 8 chars | 218T | Overkill, longer URLs |

**Decision**: 7 characters - balances uniqueness with URL length

### 2. URL Encoding: Base62 vs Base64

| Encoding | Characters | Pros | Cons |
|----------|------------|------|------|
| Base62 | a-z, A-Z, 0-9 | URL-safe, no encoding needed | Slightly longer |
| Base64 | +Base62, +, / | More compact | Requires URL encoding |

**Decision**: Base62 - cleaner URLs, no special character handling

### 3. Database Choice

| Database | Pros | Cons |
|----------|------|------|
| MySQL | ACID, familiar | Scaling challenges |
| Cassandra | High throughput, scales horizontally | Eventual consistency |
| DynamoDB | Managed, auto-scaling | Vendor lock-in, cost |
| Redis + Persistence | Extremely fast | Memory cost |

**Decision**: Cassandra for primary storage, Redis for caching

### 4. Redirect Type: 301 vs 302

| Type | Meaning | Pros | Cons |
|------|---------|------|------|
| 301 | Permanent | Browser caches, reduces server load | Can't track analytics |
| 302 | Temporary | Every request hits server, better analytics | More server load |

**Decision**: 302 for analytics tracking, 301 for expired URLs

---

## Failure Scenarios and Bottlenecks

### 1. Database Failures

```
Scenario: Primary database node fails

Mitigation:
┌─────────────────────────────────────────────────────────┐
│                  DATABASE FAILOVER                       │
│                                                         │
│  Normal Operation:                                       │
│  Client → Primary (Writes) → Replica (Reads)            │
│                                                         │
│  Failover:                                              │
│  1. Health check detects primary failure                │
│  2. Promote replica to primary (automatic)              │
│  3. Update DNS/connection string                        │
│  4. New replica spins up from snapshot                  │
│                                                         │
│  Recovery Time: ~30 seconds with Cassandra              │
│  Data Loss: Minimal (sync replication)                  │
└─────────────────────────────────────────────────────────┘
```

### 2. Cache Stampede

```
Scenario: Popular URL expires from cache, thousands of requests hit DB

Mitigation Strategies:

1. Locking (Prevent concurrent DB hits):
   def get_url_with_lock(short_key):
       url = cache.get(short_key)
       if url:
           return url
       
       lock = acquire_lock(f"lock:{short_key}", timeout=5)
       if lock:
           try:
               url = db.get(short_key)
               cache.set(short_key, url, TTL=86400)
           finally:
               release_lock(lock)
       else:
           # Wait and retry
           time.sleep(0.1)
           return get_url_with_lock(short_key)

2. Early Expiration (Probabilistic refresh):
   TTL = 24 hours
   Early refresh window = last 1 hour
   If in early window: 10% chance to refresh proactively

3. Never Expire + Background Refresh:
   - Set very long TTL
   - Background job refreshes before expiration
```

### 3. Key Generation Service Failure

```
Scenario: KGS becomes unavailable

Mitigation:
┌─────────────────────────────────────────────────────────┐
│               KGS HIGH AVAILABILITY                      │
│                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │
│  │    KGS 1    │    │    KGS 2    │    │    KGS 3    │ │
│  │ (Primary)   │    │  (Standby)  │    │  (Standby)  │ │
│  │             │    │             │    │             │ │
│  │ Keys: 1-1M  │    │ Keys: 1M-2M │    │ Keys: 2M-3M │ │
│  └─────────────┘    └─────────────┘    └─────────────┘ │
│                                                         │
│  Each instance has pre-allocated key ranges             │
│  No coordination needed between instances               │
│  If one fails, others continue serving different keys   │
└─────────────────────────────────────────────────────────┘

Fallback: Use hash-based generation if KGS unavailable
```

### 4. Hot Key Problem

```
Scenario: Viral URL gets millions of requests/second

Mitigation:
1. Local caching on each server
2. Cache replication across multiple Redis shards
3. Rate limiting per URL
4. CDN caching for redirect responses

Implementation:
def handle_redirect(short_key):
    # Check if hot key
    if is_hot_key(short_key):
        return CDN_REDIRECT  # Redirect to CDN edge
    
    # Normal flow
    return cache.get(short_key)

def is_hot_key(short_key):
    # Track request rate in sliding window
    rate = increment_counter(f"rate:{short_key}", window=60)
    return rate > 10000  # >10K requests/minute
```

---

## Future Improvements

### 1. Geographic Distribution

```
┌─────────────────────────────────────────────────────────┐
│              GLOBAL ARCHITECTURE                         │
│                                                         │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │
│  │   US-East   │    │   EU-West   │    │  AP-South   │ │
│  │   Region    │    │   Region    │    │   Region    │ │
│  │             │    │             │    │             │ │
│  │ Full Stack  │◄──►│ Full Stack  │◄──►│ Full Stack  │ │
│  │  + Local DB │    │  + Local DB │    │  + Local DB │ │
│  └─────────────┘    └─────────────┘    └─────────────┘ │
│                                                         │
│  Global Load Balancer (GeoDNS / Anycast)               │
│  Routes users to nearest region                         │
│  Cross-region replication for consistency              │
└─────────────────────────────────────────────────────────┘
```

### 2. Advanced Analytics

- Real-time click tracking with Apache Kafka
- Time-series analysis with InfluxDB/TimescaleDB
- Geographic heatmaps
- Referrer analysis
- A/B testing for landing pages

### 3. Security Enhancements

- Malware/phishing URL detection (Google Safe Browsing API)
- CAPTCHA for suspicious activity
- URL preview before redirect
- Rate limiting per IP/user
- OAuth integration for premium features

### 4. API Rate Limiting Tiers

```
┌─────────────────────────────────────────────────────────┐
│                   RATE LIMITING TIERS                    │
├─────────────────────────────────────────────────────────┤
│  Free Tier:                                             │
│    - 100 URLs/day                                       │
│    - 10,000 redirects/day                               │
│    - Basic analytics                                    │
│                                                         │
│  Pro Tier ($9.99/month):                                │
│    - 10,000 URLs/day                                    │
│    - Unlimited redirects                                │
│    - Advanced analytics                                 │
│    - Custom domains                                     │
│                                                         │
│  Enterprise:                                            │
│    - Unlimited everything                               │
│    - SLA guarantees                                     │
│    - Dedicated support                                  │
└─────────────────────────────────────────────────────────┘
```

---

## Interviewer Questions & Answers

### Q1: How would you generate unique short URLs?

**Answer:**
There are three main approaches:

1. **Counter-based with Base62 encoding**: Use a distributed counter (like Snowflake ID) and encode to Base62. Simple but predictable.

2. **Hash-based**: MD5/SHA256 hash the URL + timestamp, take first 43 bits, convert to Base62. Handle collisions by appending sequence number.

3. **Pre-generated Key Database (Recommended)**: 
   - Pre-generate all possible keys (3.5 trillion for 7 chars)
   - Store in two tables: used and unused
   - Application servers fetch batches of unused keys
   - Mark as used atomically
   - Benefits: No collision handling, guaranteed unique, fast

For production, I'd use approach 3 with multiple KGS instances, each with pre-allocated key ranges to avoid coordination overhead.

---

### Q2: How do you handle hash collisions?

**Answer:**
If using hash-based approach:

```python
def generate_short_url(long_url, user_id):
    for attempt in range(MAX_ATTEMPTS):
        # Include attempt number in hash input
        hash_input = f"{long_url}{user_id}{timestamp}{attempt}"
        hash_value = md5(hash_input)
        short_key = base62_encode(hash_value[:7])
        
        # Check if exists
        if not db.exists(short_key):
            return short_key
    
    # Fallback to UUID-based approach
    return base62_encode(uuid4())[:7]
```

With 7-character Base62 keys (3.5T combinations) and 182.5B URLs over 5 years, collision probability is ~5%. Handle by:
1. Retry with different input
2. Use database unique constraint as final check
3. Fallback to counter-based approach

---

### Q3: How would you handle a URL that becomes extremely popular (viral)?

**Answer:**
Multi-layer mitigation:

1. **Local Cache**: Each application server caches hot URLs in memory (LRU cache)
   - Eliminates Redis network call for 90%+ of requests
   - 1GB local cache per server

2. **Redis Replication**: Hot keys replicated across all Redis shards
   - Use Redis `OBJECT FREQ` to identify hot keys
   - Replicate to all shards for parallel serving

3. **CDN Caching**: For extremely viral URLs
   - Return redirect response with `Cache-Control: public, max-age=3600`
   - CDN caches the 302 redirect
   - Reduces load to nearly zero

4. **Rate Limiting**: Protect the system
   - Limit to 100K requests/second per URL
   - Return cached response while limiting

5. **Async Analytics**: Don't block redirect for analytics
   - Fire-and-forget to Kafka
   - Process asynchronously

---

### Q4: What database would you use and why?

**Answer:**
**Primary Choice: Apache Cassandra**

Reasons:
1. **Write-optimized**: 100M writes/day needs high write throughput
2. **Horizontal scaling**: Add nodes linearly as data grows
3. **No single point of failure**: Multi-datacenter replication
4. **Tunable consistency**: Use LOCAL_QUORUM for balance

Schema design:
```cql
CREATE TABLE url_mappings (
    short_key text PRIMARY KEY,  -- Partition key for even distribution
    long_url text,
    created_at timestamp,
    expires_at timestamp
) WITH compaction = {'class': 'LeveledCompactionStrategy'}
  AND gc_grace_seconds = 864000;
```

**Alternative: DynamoDB** (if on AWS)
- Fully managed, auto-scaling
- Global tables for multi-region
- Higher cost but zero operational overhead

**Redis for caching** (not primary storage):
- Sub-millisecond latency
- 80% of requests served from cache

---

### Q5: How do you ensure high availability?

**Answer:**
Multiple layers of redundancy:

1. **Load Balancer**: Multiple LBs with health checks
   - AWS ALB with cross-zone load balancing
   - Automatic failover

2. **Application Servers**: Stateless, horizontally scaled
   - Minimum 3 instances per region
   - Auto-scaling based on CPU/request rate
   - Health checks every 10 seconds

3. **Database**: Multi-node Cassandra cluster
   - Replication factor 3 (survive 2 node failures)
   - Multi-datacenter deployment
   - Automatic failover and repair

4. **Cache**: Redis Cluster with replicas
   - Each shard has 1 replica
   - Automatic failover to replica
   - Data persisted to disk (RDB snapshots)

5. **Key Generation Service**: Multiple instances
   - Pre-allocated key ranges
   - No coordination needed
   - Fallback to hash-based if all KGS fail

6. **Geographic redundancy**:
   - Deploy in 3 regions (US, EU, Asia)
   - GeoDNS routes to nearest healthy region
   - Cross-region data replication

SLA Target: 99.99% (52 minutes downtime/year)

---

### Q6: How would you design the analytics system?

**Answer:**

**Architecture:**
```
Click Event → Kafka → Flink/Spark → Analytics DB → Dashboard
                           ↓
                    Real-time Counters (Redis)
```

**Implementation:**
1. **Event Capture**: Non-blocking, fire-and-forget
   ```python
   async def log_click(short_key, metadata):
       event = {
           "short_key": short_key,
           "timestamp": time.time(),
           "ip": metadata.ip,
           "user_agent": metadata.user_agent,
           "referrer": metadata.referrer
       }
       await kafka_producer.send("click_events", event)
   ```

2. **Real-time Processing**: Apache Flink
   - Count clicks per URL per minute
   - Detect trending URLs
   - Geographic distribution

3. **Storage**: 
   - Real-time: Redis counters (last 24 hours)
   - Historical: ClickHouse (columnar, fast aggregations)

4. **Analytics Features**:
   - Total clicks
   - Clicks over time (hourly/daily/weekly)
   - Geographic breakdown
   - Top referrers
   - Device/browser breakdown

---

### Q7: How do you prevent abuse (spam/malicious URLs)?

**Answer:**

**Multi-layer protection:**

1. **Rate Limiting**:
   - Per IP: 100 URLs/hour
   - Per user: 1000 URLs/day
   - Global: Circuit breaker at 1M URLs/hour

2. **URL Validation**:
   ```python
   def validate_url(url):
       # Check format
       if not is_valid_url(url):
           raise InvalidURLError()
       
       # Check against blacklist
       if is_blacklisted_domain(url):
           raise BlockedURLError()
       
       # Check Google Safe Browsing API
       if is_malicious(url):
           raise MaliciousURLError()
       
       return True
   ```

3. **Google Safe Browsing Integration**:
   - Check URLs against malware/phishing databases
   - Cache results for 30 minutes
   - Block or warn users

4. **CAPTCHA**: After suspicious behavior
   - Multiple URLs in short time
   - Similar URLs (URL farming)
   - Anonymous users

5. **User Reporting**: Allow users to report malicious links

6. **Periodic Scanning**: Background job re-checks old URLs

---

### Q8: How would you handle URL expiration?

**Answer:**

**Design:**
```
┌─────────────────────────────────────────────────────────┐
│                 URL EXPIRATION HANDLING                  │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  On Redirect:                                           │
│  1. Check expires_at in cache/DB                        │
│  2. If expired:                                         │
│     - Return 410 Gone (not 404)                        │
│     - Log expiration event                              │
│     - Optionally show "expired" page                    │
│                                                         │
│  Background Cleanup Job (Daily):                        │
│  1. SELECT * FROM urls WHERE expires_at < NOW()         │
│  2. Move to archive table (for analytics)              │
│  3. Delete from main table                              │
│  4. Invalidate cache                                    │
│  5. Recycle short keys (optional)                       │
│                                                         │
│  Key Recycling:                                         │
│  - Wait 30 days after expiration                        │
│  - Move key back to unused pool                         │
│  - Prevents old bookmarks hitting new URLs              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
def handle_redirect(short_key):
    url_data = get_url(short_key)
    
    if not url_data:
        return Response(status=404, body="URL not found")
    
    if url_data.expires_at and url_data.expires_at < datetime.now():
        return Response(status=410, body="URL has expired")
    
    log_click_async(short_key)
    return Response(status=302, headers={"Location": url_data.long_url})
```

---

### Q9: How would you implement custom aliases?

**Answer:**

**Implementation:**
```python
def create_short_url(long_url, custom_alias=None):
    if custom_alias:
        # Validate custom alias
        if not is_valid_alias(custom_alias):
            raise InvalidAliasError("Alias must be 4-20 alphanumeric chars")
        
        # Check availability
        if db.exists(custom_alias):
            raise AliasExistsError("Alias already taken")
        
        short_key = custom_alias
    else:
        # Generate random key
        short_key = key_generation_service.get_key()
    
    # Store mapping
    db.insert({
        "short_key": short_key,
        "long_url": long_url,
        "is_custom": custom_alias is not None
    })
    
    return short_key

def is_valid_alias(alias):
    # Length: 4-20 characters
    if not 4 <= len(alias) <= 20:
        return False
    
    # Alphanumeric only
    if not alias.isalnum():
        return False
    
    # Not reserved words
    reserved = ["admin", "api", "help", "login", "signup"]
    if alias.lower() in reserved:
        return False
    
    return True
```

**Considerations:**
- Custom aliases occupy the same namespace as generated keys
- Reserve short aliases (<4 chars) for system/premium use
- Premium feature: charge for custom aliases

---

### Q10: How do you scale the system to handle 100M URLs per day?

**Answer:**

**Horizontal Scaling Strategy:**

1. **Application Layer**:
   - Stateless servers behind load balancer
   - Auto-scale: 10-100 instances based on load
   - Each server handles ~1000 requests/second

2. **Database Layer**:
   - Cassandra cluster: Start with 9 nodes (3 per DC)
   - Scale by adding nodes (linear scalability)
   - Replication factor 3 for durability

3. **Cache Layer**:
   - Redis Cluster: 6 shards × 2 (master + replica)
   - Total cache: 1TB
   - Scale by adding shards

4. **Key Generation**:
   - 3 KGS instances with pre-allocated ranges
   - Each can serve 100K keys/second

**Capacity Planning:**

```
Current: 100M URLs/day = 1,160/sec writes
Target:  1B URLs/day = 11,600/sec writes (10x)

To scale 10x:
- App servers: 10 → 100 instances
- Cassandra: 9 → 27 nodes
- Redis: 6 → 18 shards
- KGS: 3 → 9 instances
```

**Performance Optimizations:**
- Batch writes to database
- Connection pooling
- Async processing for non-critical paths
- CDN for static content

---

### Q11: What's the difference between using 301 and 302 redirects?

**Answer:**

| Aspect | 301 (Permanent) | 302 (Temporary) |
|--------|-----------------|-----------------|
| Meaning | URL permanently moved | URL temporarily moved |
| Browser Caching | Caches redirect | Doesn't cache |
| SEO | Passes link juice | Doesn't pass link juice |
| Analytics | Loses tracking after first visit | Every visit tracked |
| Server Load | Lower (browser caches) | Higher (every request hits server) |

**Use Cases:**
- **301**: Permanent domain change, SEO purposes
- **302**: Short URLs with analytics (recommended for URL shorteners)

**Recommendation for URL Shortener:**
Use 302 by default for analytics tracking. Offer 301 as a premium feature for users who want SEO benefits and don't need analytics.

```python
def redirect(short_key, use_permanent=False):
    url_data = get_url(short_key)
    status_code = 301 if use_permanent else 302
    return Response(status=status_code, headers={"Location": url_data.long_url})
```

---

### Q12: How would you implement read replicas and handle replication lag?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────┐
│                 REPLICATION TOPOLOGY                     │
│                                                         │
│  ┌─────────────┐                                        │
│  │   Primary   │◄── All Writes                          │
│  │   (Write)   │                                        │
│  └──────┬──────┘                                        │
│         │                                               │
│    Sync/Async Replication                               │
│         │                                               │
│    ┌────┴────┬────────┬────────┐                       │
│    ▼         ▼        ▼        ▼                       │
│ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐               │
│ │Replica│ │Replica│ │Replica│ │Replica│               │
│ │  1    │ │  2    │ │  3    │ │  4    │               │
│ │(Read) │ │(Read) │ │(Read) │ │(Read) │               │
│ └───────┘ └───────┘ └───────┘ └───────┘               │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Handling Replication Lag:**

1. **Read-Your-Writes Consistency**:
   ```python
   def get_url(short_key, user_id):
       # If user just created this URL, read from primary
       if was_recently_created(short_key, user_id):
           return primary_db.get(short_key)
       
       # Otherwise, read from replica
       return replica_db.get(short_key)
   ```

2. **Session Consistency**:
   - Track last write timestamp per user
   - Route reads to primary if within lag window

3. **Cassandra Approach** (Recommended):
   - Use `LOCAL_QUORUM` for both reads and writes
   - Guarantees reading your own writes
   - Slightly higher latency but strong consistency

4. **Lag Monitoring**:
   - Monitor replication lag metrics
   - Alert if lag > 1 second
   - Circuit breaker to route to primary if lag too high

---

### Q13: How do you monitor the health of the system?

**Answer:**

**Monitoring Stack:**
```
┌─────────────────────────────────────────────────────────┐
│                   MONITORING ARCHITECTURE                │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Metrics Collection:                                    │
│  - Prometheus (time-series metrics)                     │
│  - StatsD (application metrics)                         │
│                                                         │
│  Visualization:                                         │
│  - Grafana dashboards                                   │
│                                                         │
│  Alerting:                                              │
│  - PagerDuty integration                                │
│  - Slack notifications                                  │
│                                                         │
│  Logging:                                               │
│  - ELK Stack (Elasticsearch, Logstash, Kibana)         │
│  - Structured JSON logs                                 │
│                                                         │
│  Tracing:                                               │
│  - Jaeger for distributed tracing                       │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Key Metrics:**

1. **Application Metrics**:
   - Request rate (URLs/sec, redirects/sec)
   - Latency (p50, p95, p99)
   - Error rate (4xx, 5xx)
   - Cache hit ratio

2. **Infrastructure Metrics**:
   - CPU, memory, disk usage
   - Network throughput
   - Database connections

3. **Business Metrics**:
   - URLs created per day
   - Active URLs
   - Top URLs by clicks
   - User sign-ups

**Alerting Rules:**
```yaml
- alert: HighErrorRate
  expr: rate(http_errors_total[5m]) > 0.01
  for: 5m
  severity: critical

- alert: HighLatency
  expr: histogram_quantile(0.99, http_request_duration) > 0.5
  for: 5m
  severity: warning

- alert: CacheMissHigh
  expr: rate(cache_misses[5m]) / rate(cache_requests[5m]) > 0.3
  for: 10m
  severity: warning
```

---

### Q14: How would you design a URL preview feature?

**Answer:**

**Implementation:**
```
┌─────────────────────────────────────────────────────────┐
│                  URL PREVIEW SYSTEM                      │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Request: GET /preview/abc123                           │
│                                                         │
│  Response:                                              │
│  {                                                      │
│    "short_url": "https://tiny.url/abc123",             │
│    "long_url": "https://example.com/...",              │
│    "title": "Example Page Title",                       │
│    "description": "Page description...",               │
│    "thumbnail": "https://cdn.../thumb.jpg",            │
│    "safe_browsing_status": "safe",                     │
│    "created_at": "2024-01-15T10:30:00Z"               │
│  }                                                      │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Metadata Extraction Pipeline:**
```python
async def extract_metadata(long_url):
    # Fetch page content
    response = await http_client.get(long_url, timeout=5)
    soup = BeautifulSoup(response.content, 'html.parser')
    
    metadata = {
        "title": extract_title(soup),
        "description": extract_description(soup),
        "thumbnail": extract_og_image(soup),
        "favicon": extract_favicon(soup)
    }
    
    # Cache metadata
    cache.set(f"meta:{long_url}", metadata, ttl=86400)
    
    return metadata

def extract_title(soup):
    # Try Open Graph first
    og_title = soup.find("meta", property="og:title")
    if og_title:
        return og_title["content"]
    
    # Fall back to <title> tag
    title_tag = soup.find("title")
    return title_tag.string if title_tag else None
```

**When to Extract:**
1. **On URL creation**: Extract asynchronously, store with URL
2. **On first preview request**: Extract and cache
3. **Periodic refresh**: Update metadata weekly for active URLs

---

### Q15: How would you implement URL shortening for private/authenticated users?

**Answer:**

**Authentication & Authorization:**
```
┌─────────────────────────────────────────────────────────┐
│               PRIVATE URL ARCHITECTURE                   │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  Database Schema:                                       │
│  urls:                                                  │
│    - short_key (PK)                                    │
│    - long_url                                          │
│    - user_id (FK)                                      │
│    - visibility: ENUM(public, private, unlisted)       │
│    - password_hash (nullable)                          │
│    - allowed_users: JSON array                         │
│                                                         │
│  Access Control:                                        │
│  - Public: Anyone can access                            │
│  - Unlisted: Only with link, not indexed               │
│  - Private: Requires authentication                     │
│  - Password Protected: Requires password               │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
def handle_redirect(short_key, user=None, password=None):
    url_data = get_url(short_key)
    
    if url_data.visibility == "public":
        return redirect(url_data.long_url)
    
    if url_data.visibility == "private":
        if not user or user.id not in url_data.allowed_users:
            return Response(status=403, body="Access denied")
        return redirect(url_data.long_url)
    
    if url_data.password_hash:
        if not password or not verify_password(password, url_data.password_hash):
            return Response(status=401, body="Password required")
        return redirect(url_data.long_url)
    
    return redirect(url_data.long_url)
```

**Features:**
- User dashboard to manage URLs
- Share URLs with specific users
- Password protection option
- Expiration with login requirement
- Access logs for private URLs

---

### Bonus: Quick Architecture Recap

```
┌─────────────────────────────────────────────────────────────────┐
│                    URL SHORTENER - FINAL ARCHITECTURE            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 100M URLs/day, 10B redirects/day                       │
│                                                                 │
│  Components:                                                    │
│  ├── Load Balancer (AWS ALB, Nginx)                            │
│  ├── API Gateway (Rate limiting, Auth)                         │
│  ├── Application Servers (Stateless, 50+ instances)            │
│  ├── Key Generation Service (3 instances, pre-allocated keys)  │
│  ├── Cache Layer (Redis Cluster, 1TB)                          │
│  ├── Database (Cassandra, 9+ nodes, RF=3)                      │
│  ├── Analytics (Kafka → Flink → ClickHouse)                    │
│  └── Monitoring (Prometheus, Grafana, ELK)                     │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── 7-character Base62 keys (3.5T combinations)               │
│  ├── Pre-generated keys (no collisions)                        │
│  ├── 302 redirects (for analytics)                             │
│  ├── Multi-layer caching (local + Redis)                       │
│  └── Async analytics (fire-and-forget)                         │
│                                                                 │
│  SLA: 99.99% availability, <100ms latency                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
