# URL Shortener Service (TinyURL/Bit.ly) - System Design Interview

## Problem Statement
*"Design a URL shortening service like TinyURL or Bit.ly that converts long URLs into short links, handles massive traffic with global distribution, and provides analytics for link performance."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What's the core functionality needed?" → Shorten URLs, redirect to original URLs, custom aliases
- "Do we need user accounts?" → Yes, for link management, analytics, and custom domains
- "Should we provide analytics?" → Yes, click tracking, geographic data, referrer information
- "Do we need custom short codes?" → Yes, users can specify custom aliases (bit.ly/custom-name)
- "Should shortened URLs expire?" → Yes, configurable expiration (never, 30 days, 1 year)
- "Do we need rate limiting?" → Yes, prevent spam and abuse

**Non-Functional Requirements:**
- "What's our scale expectation?" → 100M URLs shortened daily, 10B clicks/day
- "Expected latency for redirects?" → <100ms globally (redirects are time-sensitive)
- "Read/Write ratio?" → 100:1 (much more clicks than URL creations)
- "Availability requirements?" → 99.99% uptime (links in emails/social media must work)
- "Global distribution?" → Yes, CDN-like performance worldwide

### Requirements Summary:
- **Scale**: 100M URLs/day, 10B clicks/day, 100:1 read/write ratio
- **Features**: URL shortening, custom aliases, analytics, expiration, user accounts
- **Performance**: <100ms redirect latency globally, 99.99% availability
- **Security**: Rate limiting, malicious URL detection, spam prevention

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Daily URL creations: 100M
Daily clicks: 10B (100:1 ratio)
Peak traffic multiplier: 3x during viral events
Peak URL creations: 100M × 3 ÷ 86,400 = ~3.5K RPS
Peak redirects: 10B × 3 ÷ 86,400 = ~350K RPS
Global distribution: 350K RPS ÷ 5 regions = 70K RPS per region
```

### Storage Estimation:
```
URL records: 100M/day × 500 bytes × 365 × 5 years = 91TB
Analytics data: 10B clicks/day × 200 bytes × 365 days = 730TB/year
User data: 10M users × 1KB = 10GB
Cache requirements: Hot URLs × 1KB = 1GB per cache node
Total storage: ~1PB over 5 years (with replication)
```

### Short Code Analysis:
```
Characters available: [a-zA-Z0-9] = 62 characters
6-character codes: 62^6 = 56B possible combinations
7-character codes: 62^7 = 3.5T possible combinations
Target: 7 characters for 100+ years of unique URLs
```

---

## Phase 3: High-Level Architecture (12 minutes)

### Step 1: Simple Architecture
```
[Client] → [Load Balancer] → [URL Service] → [Database] → [Cache]
```

### Step 2: Complete Architecture

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Web Client   │  │Mobile Apps  │  │API Partners │
│- Dashboard  │  │- Sharing    │  │- Integration│
│- Analytics  │  │- Quick Link │  │- Bulk APIs  │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│                  CDN / Edge                     │
│- Cache hot redirects  - Geographic distribution │
└─────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│              Global Load Balancer               │
│- DNS-based routing  - Health checks  - Failover│
└─────────────────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Region 1   │ │   Region 2   │ │   Region 3   │
│   (US-East)  │ │   (EU-West)  │ │  (AP-South)  │
└──────────────┘ └──────────────┘ └──────────────┘
        │
        ▼
┌─────────────────────────────────────────────────┐
│              Regional Load Balancer             │
│- Session affinity  - Circuit breakers          │
└─────────────────────────────────────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│URL         │ │Analytics   │ │User        │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Shorten   │ │- Track     │ │- Auth      │
│- Redirect  │ │- Report    │ │- Dashboard │
│- Validate  │ │- Stats     │ │- Settings  │
└────────────┘ └────────────┘ └────────────┘
    │                   │                   │
    └───────────────────┼───────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Code        │ │Cache       │ │Queue       │
│Generator   │ │Service     │ │Service     │
│            │ │            │ │            │
│- Base62    │ │- Redis     │ │- Analytics │
│- Custom    │ │- Bloom     │ │- Cleanup   │
│- Counter   │ │- Hot URLs  │ │- Jobs      │
└────────────┘ └────────────┘ └────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│                 Data Layer                      │
│                                                 │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────┐   │
│ │Cassandra│  │  Redis  │  │  Kafka  │  │ S3  │   │
│ │         │  │         │  │         │  │     │   │
│ │- URLs   │  │- Cache  │  │- Events │  │-Logs│   │
│ │- Clicks │  │- Counter│  │- Metrics│  │-Backup│ │
│ │- Users  │  │- Bloom  │  │- Stream │  │     │   │
│ └─────────┘  └─────────┘  └─────────┘  └─────┘   │
└─────────────────────────────────────────────────┘
```

### Key Components:
- **URL Service**: Core shortening and redirect logic
- **Code Generator**: Base62 encoding and collision handling
- **Analytics Service**: Click tracking and reporting
- **Cache Service**: Multi-layer caching for performance
- **Queue Service**: Asynchronous processing for analytics

---

## Phase 4: Database Design (8 minutes)

### Core Entities:
- URLs (shortened mappings), Users (accounts), Analytics (click data)

### Cassandra Schema (for massive scale):

```sql
-- URLs table (partitioned by short_code for even distribution)
CREATE TABLE urls (
    short_code TEXT PRIMARY KEY,      -- 7-character base62 code
    long_url TEXT NOT NULL,
    user_id UUID,                     -- NULL for anonymous
    
    -- Metadata
    title TEXT,                       -- Page title (optional)
    domain TEXT,                      -- extracted from long_url
    is_custom BOOLEAN DEFAULT false,  -- Custom alias vs generated
    
    -- Configuration
    expires_at TIMESTAMP,             -- NULL means never expires
    is_active BOOLEAN DEFAULT true,   -- Can be disabled
    password_hash TEXT,               -- Optional password protection
    
    -- Tracking
    click_count COUNTER DEFAULT 0,    -- Real-time click count
    created_at TIMESTAMP DEFAULT NOW(),
    last_accessed TIMESTAMP,
    
    -- Security
    is_malicious BOOLEAN DEFAULT false,
    blocked_reason TEXT
);

-- Users table
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    email TEXT UNIQUE NOT NULL,
    username TEXT UNIQUE,
    password_hash TEXT NOT NULL,
    
    -- Account info
    account_type TEXT DEFAULT 'free', -- free, premium, enterprise
    custom_domain TEXT,               -- pro.ly instead of bit.ly
    
    -- Limits and usage
    monthly_url_limit INTEGER DEFAULT 1000,
    urls_created_this_month COUNTER DEFAULT 0,
    
    -- Settings
    default_expiration TEXT DEFAULT 'never',
    analytics_enabled BOOLEAN DEFAULT true,
    
    created_at TIMESTAMP DEFAULT NOW(),
    last_login TIMESTAMP
);

-- User URLs index (for dashboard)
CREATE TABLE user_urls (
    user_id UUID,
    created_at TIMESTAMP,
    short_code TEXT,
    long_url TEXT,
    click_count COUNTER DEFAULT 0,
    
    PRIMARY KEY (user_id, created_at, short_code)
) WITH CLUSTERING ORDER BY (created_at DESC);

-- Analytics events (time-series data)
CREATE TABLE url_analytics (
    short_code TEXT,
    click_date DATE,                  -- Partition by date for time-series
    click_hour INTEGER,               -- 0-23 for hourly stats
    click_id UUID,
    
    -- Click details
    clicked_at TIMESTAMP,
    ip_address INET,
    user_agent TEXT,
    referer TEXT,
    
    -- Geographic data
    country TEXT,
    region TEXT,
    city TEXT,
    
    -- Computed fields
    device_type TEXT,                 -- mobile, desktop, tablet
    browser TEXT,                     -- chrome, firefox, safari
    os TEXT,                          -- windows, macos, android
    
    PRIMARY KEY ((short_code, click_date), click_hour, click_id)
) WITH CLUSTERING ORDER BY (click_hour DESC, click_id DESC);

-- Daily aggregated stats (for fast analytics)
CREATE TABLE daily_stats (
    short_code TEXT,
    stat_date DATE,
    
    -- Aggregated counts
    total_clicks COUNTER,
    unique_visitors COUNTER,          -- Approximated using HyperLogLog
    
    -- Top referrers (JSON)
    top_referrers TEXT,               -- JSON: {"google.com": 1500, "twitter.com": 800}
    top_countries TEXT,               -- JSON: {"US": 2000, "UK": 500}
    top_devices TEXT,                 -- JSON: {"mobile": 1800, "desktop": 700}
    
    PRIMARY KEY (short_code, stat_date)
) WITH CLUSTERING ORDER BY (stat_date DESC);
```

### Redis Cache Strategy:

```javascript
// Hot URLs cache (most accessed short codes)
"url:{short_code}": {
  "long_url": "https://example.com/very/long/path...",
  "expires_at": 1735689600,          // NULL if never expires
  "click_count": 1250000,
  "is_active": true,
  "ttl": 3600                        // 1 hour cache
}

// Bloom filter for existing short codes (prevents database lookups)
"bloom_filter:existing_codes": 
// Bloom filter with 1% false positive rate for 100B codes

// Rate limiting per IP
"rate_limit:ip:192.168.1.100": {
  "requests": 95,
  "window_start": 1704110400,
  "ttl": 3600                        // Reset hourly
}

// User rate limiting
"rate_limit:user:uuid": {
  "urls_today": 45,
  "clicks_today": 2500,
  "ttl": 86400                       // Reset daily
}

// Analytics buffer (before writing to Cassandra)
"analytics_buffer:{short_code}:{hour}": [
  {"click_id": "uuid", "ip": "1.1.1.1", "country": "US", "timestamp": 1704110450},
  // ... more click events
]

// Short code counter (for generating sequential codes)
"counter:short_code": 15234567890   // Incremental counter for base62 conversion

// Recent popular URLs (for trending analysis)
"trending_urls": [
  {"short_code": "abc123", "clicks_last_hour": 50000},
  {"short_code": "def456", "clicks_last_hour": 35000}
]
```

### Design Decisions:
- **Cassandra**: Handles massive write volume and horizontal scaling
- **Partition by short_code**: Even distribution across nodes
- **Time-series analytics**: Efficient querying by date ranges
- **Counter columns**: Real-time click count updates
- **Bloom filters**: Reduce database lookups for non-existent URLs

---

## Phase 5: Critical Flow - URL Shortening & Redirect (8 minutes)

### Most Critical Flow: Shorten URL

**1. URL Shortening Request**
```
POST /api/shorten
{
  "long_url": "https://example.com/very/long/path/to/article?param=value",
  "custom_alias": null,              // Optional custom short code
  "expires_at": null,                // Optional expiration
  "password": null                   // Optional password protection
}
```

**2. URL Validation & Processing**
```
URL Service processing:
1. Validate long URL format and reachability
2. Check for malicious URLs using security service
3. Extract domain and fetch page title (optional)
4. Check user rate limits (if authenticated)
5. Normalize URL (remove tracking parameters, normalize case)
```

**3. Short Code Generation**
```
Code Generator Service:
1. If custom alias requested:
   - Check availability using Bloom filter
   - If potentially available, check Cassandra
   - If taken, return error
2. If auto-generated:
   - Get next counter value from Redis
   - Convert to base62: counter → short_code
   - Ensure 7-character minimum length
3. Final collision check and retry if needed
```

**4. Database Storage & Caching**
```
Data persistence:
1. Insert URL record into Cassandra
2. Update user_urls table if user is authenticated
3. Cache URL mapping in Redis with TTL
4. Add short_code to Bloom filter
5. Return shortened URL to user
```

### Most Critical Flow: URL Redirect

**1. Redirect Request Processing**
```
GET /{short_code}
User clicks: https://tiny.ly/abc123
```

**2. Cache-First Lookup**
```
URL Service redirect:
1. Check Redis cache for short_code mapping
2. If cache hit:
   - Increment click counter asynchronously
   - Return 301/302 redirect immediately
3. If cache miss:
   - Query Cassandra for URL mapping
   - Cache result in Redis
   - Return redirect or 404
```

**3. Analytics Tracking (Async)**
```
Analytics Service (non-blocking):
1. Extract user information:
   - IP address, User-Agent, Referer
   - Geographic data from IP lookup
   - Device/browser detection
2. Buffer analytics event in Redis
3. Background job writes to Cassandra analytics tables
4. Update real-time counters
```

**4. Edge Cases Handling**
```
Special scenarios:
1. Expired URLs: Return custom expiration page
2. Password-protected: Redirect to password form
3. Malicious URLs: Block and show warning
4. High-traffic URLs: Serve from CDN edge cache
```

### Technical Challenges:

**Base62 Encoding:**
- "Convert incremental counter to base62 for short codes"
- "Handle collision detection and resolution"
- "Ensure uniform distribution across character space"

**Cache Strategy:**
- "Multi-layer caching: CDN → Redis → Database"
- "Cache invalidation for expired or deleted URLs"
- "Bloom filter optimization for non-existent URLs"

**Analytics at Scale:**
- "Async processing to not slow down redirects"
- "Batch writing to reduce database load"
- "Real-time vs batch analytics trade-offs"

**Global Distribution:**
- "Consistent hashing for cache distribution"
- "Regional database replication"
- "CDN integration for hot URLs"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Database writes**: 100M URL creations + 10B click analytics/day
2. **Cache memory**: Hot URLs and analytics data
3. **Code generation**: Collision handling at scale
4. **Global latency**: <100ms redirect requirement worldwide

### Scaling Solutions:

**Database Scaling:**
```
- Cassandra cluster: Horizontal scaling for writes
- Consistent hashing: Even data distribution
- Replication factor: 3x for high availability
- Batch analytics: Reduce write amplification
```

**Caching Optimization:**
```
- CDN integration: Cache popular redirects at edge
- Redis clustering: Distribute cache load
- Intelligent TTL: Longer cache for stable URLs
- Bloom filter: Reduce negative cache misses
```

**Code Generation:**
```
- Pre-generate code batches: Avoid real-time collisions
- Distributed counters: Multiple counter ranges per service
- Base62 optimization: Faster encoding algorithms
- Custom algorithm: Base62 + timestamp for uniqueness
```

**Global Performance:**
```
- Multi-region deployment: Reduce latency
- DNS geo-routing: Route to nearest region
- Edge caching: Cache hot URLs globally
- Regional databases: Local data for faster access
```

### Trade-offs:
- **Consistency vs Performance**: Eventually consistent analytics vs real-time accuracy
- **Cache vs Database**: Memory cost vs query performance
- **Custom URLs vs Scale**: Collision checking overhead vs user experience
- **Analytics Depth vs Speed**: Detailed tracking vs redirect latency

---

## Advanced Features:

**Security & Abuse Prevention:**
- Rate limiting per IP and user
- Malicious URL detection using ML
- CAPTCHA for suspicious traffic
- URL expiration and password protection

**Analytics & Intelligence:**
- Real-time click heatmaps
- Geographic analytics with maps
- A/B testing for different short codes
- Click fraud detection

**Enterprise Features:**
- Custom domains (customer.ly instead of tiny.ly)
- API access with authentication
- Bulk URL shortening
- Advanced analytics dashboard

---

## Success Metrics:
- **Redirect Latency**: <100ms globally (P95)
- **URL Creation Rate**: 100M URLs/day with <200ms response
- **Cache Hit Rate**: >95% for redirect requests
- **Availability**: 99.99% uptime across all regions
- **Analytics Accuracy**: <1% error in click counting

**🎯 This design demonstrates massive-scale read-heavy systems, global distribution, caching strategies, and building internet-scale infrastructure that serves billions of requests daily.**
