# Twitter/X - High Level Design

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

1. **Tweet Management**
   - Users can post tweets (280 characters max)
   - Tweets can contain text, images, videos, links
   - Users can delete their own tweets
   - Support for retweets and quote tweets

2. **Timeline**
   - Home timeline: Tweets from followed users
   - User timeline: Tweets from a specific user
   - Trending topics timeline

3. **Social Features**
   - Follow/unfollow users
   - Like/unlike tweets
   - Reply to tweets (threaded conversations)
   - Mention users with @username

4. **Search**
   - Search tweets by keywords/hashtags
   - Search users by name/username
   - Trending hashtags

5. **Notifications**
   - New followers
   - Likes, retweets, replies
   - Mentions

### Non-Functional Requirements

1. **Scale**: 500M+ daily active users, 500M tweets/day
2. **Availability**: 99.99% uptime (highly available)
3. **Latency**: Timeline load < 200ms, Tweet post < 500ms
4. **Consistency**: Eventual consistency acceptable for timeline
5. **Partition Tolerance**: System must handle network partitions
6. **Read Heavy**: 100:1 read to write ratio

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Total users: 1 Billion
- Daily Active Users (DAU): 500 Million
- Monthly Active Users (MAU): 800 Million

Tweets:
- Tweets per day: 500 Million
- Tweets per second: 500M / 86400 ≈ 5,800 tweets/sec
- Peak: 10x average = 58,000 tweets/sec

Timeline Reads:
- Average user checks timeline 10 times/day
- Timeline reads/day: 500M * 10 = 5 Billion
- Timeline reads/second: 5B / 86400 ≈ 58,000 reads/sec

Follows:
- Average follows per user: 200
- Average followers per user: 200
- Total follow relationships: 500M * 200 = 100 Billion
```

### Storage Estimates

```
Tweet Storage:
- Tweet metadata: 500 bytes
  - Tweet ID: 8 bytes
  - User ID: 8 bytes
  - Text: 280 bytes (max)
  - Timestamps: 16 bytes
  - Counts (likes, retweets): 24 bytes
  - Other metadata: ~164 bytes

- Tweets per day: 500 Million
- Storage per day: 500M * 500 bytes = 250 GB/day
- Storage per year: 250 GB * 365 = 91.25 TB/year
- 5 years with replication (3x): ~1.4 PB

Media Storage:
- 20% of tweets have images: 100M images/day
- Average image size: 500 KB
- Image storage/day: 100M * 500KB = 50 TB/day
- Image storage/year: 18.25 PB/year

User Data:
- 1 Billion users * 1 KB = 1 TB
- Follow relationships: 100B * 16 bytes = 1.6 TB
```

### Bandwidth Estimates

```
Incoming (Writes):
- Tweets: 5,800/sec * 500 bytes = 2.9 MB/sec
- Images: 1,160/sec * 500 KB = 580 MB/sec
- Total incoming: ~600 MB/sec

Outgoing (Reads):
- Timeline: 58,000/sec * 50 tweets * 500 bytes = 1.45 GB/sec
- Images served: 2.9 GB/sec (assuming 5 images per timeline load)
- Total outgoing: ~4.5 GB/sec
```

### Memory (Cache) Estimates

```
Cache hot tweets and timelines:
- Active users: 500M
- Timeline cache per user: 200 tweets * 500 bytes = 100 KB
- If we cache 20% of active users: 100M * 100 KB = 10 TB

Hot tweets cache:
- Last 24 hours tweets: 500M * 500 bytes = 250 GB
- With metadata and indexes: ~500 GB
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENTS                                           │
│                        (Web, iOS, Android, API Partners)                            │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CDN (Akamai/CloudFront)                            │
│                              (Static assets, cached content)                         │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                               LOAD BALANCER (L7)                                     │
│                            (AWS ALB / Nginx / HAProxy)                               │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    │                                         │
                    ▼                                         ▼
┌───────────────────────────────────────┐    ┌───────────────────────────────────────┐
│           API GATEWAY                  │    │        WEBSOCKET GATEWAY              │
│   (Auth, Rate Limit, Routing)         │    │     (Real-time notifications)         │
└───────────────────┬───────────────────┘    └───────────────────────────────────────┘
                    │
    ┌───────────────┼───────────────┬───────────────┬───────────────┐
    │               │               │               │               │
    ▼               ▼               ▼               ▼               ▼
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│  Tweet  │   │Timeline │   │  User   │   │ Search  │   │  Media  │
│ Service │   │ Service │   │ Service │   │ Service │   │ Service │
└────┬────┘   └────┬────┘   └────┬────┘   └────┬────┘   └────┬────┘
     │             │             │             │             │
     │             │             │             │             │
┌────┴─────────────┴─────────────┴─────────────┴─────────────┴────┐
│                         MESSAGE QUEUE                            │
│                    (Kafka / Apache Pulsar)                       │
└────┬─────────────────────────────────────────────────────┬──────┘
     │                                                      │
     ▼                                                      ▼
┌─────────────────────────────────┐    ┌─────────────────────────────────┐
│        FANOUT SERVICE           │    │      NOTIFICATION SERVICE       │
│   (Timeline pre-computation)    │    │    (Push notifications)         │
└─────────────────────────────────┘    └─────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├─────────────────┬─────────────────┬─────────────────┬───────────────────────────────┤
│    CACHE        │   TWEET DB      │   GRAPH DB      │     SEARCH                    │
│   (Redis)       │  (Cassandra)    │    (Neo4j)      │  (Elasticsearch)              │
│                 │                 │                 │                               │
│ - Timelines     │ - Tweets        │ - Followers     │ - Tweet index                 │
│ - User sessions │ - User data     │ - Following     │ - User index                  │
│ - Counters      │                 │ - Blocks        │ - Hashtag index               │
└─────────────────┴─────────────────┴─────────────────┴───────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                               OBJECT STORAGE                                         │
│                              (S3 / GCS / Azure Blob)                                │
│                         (Images, Videos, Attachments)                                │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Services

1. **Tweet Service**: Create, read, delete tweets
2. **Timeline Service**: Generate and serve timelines
3. **User Service**: User profiles, authentication
4. **Search Service**: Full-text search for tweets/users
5. **Media Service**: Upload/serve images and videos
6. **Fanout Service**: Distribute tweets to follower timelines
7. **Notification Service**: Push/pull notifications

---

## Request Flows

### Tweet Posting Flow

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌────────┐  ┌───────────┐  ┌────────┐
│ Client │  │ LB  │  │ API GW  │  │  Tweet    │  │ Kafka  │  │  Fanout   │  │Timeline│
│        │  │     │  │         │  │  Service  │  │        │  │  Service  │  │ Cache  │
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └───┬────┘  └─────┬─────┘  └───┬────┘
    │          │          │             │            │             │            │
    │ POST     │          │             │            │             │            │
    │ /tweet   │          │             │            │             │            │
    │─────────>│          │             │            │             │            │
    │          │─────────>│             │            │             │            │
    │          │          │ Validate    │            │             │            │
    │          │          │────────────>│            │             │            │
    │          │          │             │            │             │            │
    │          │          │             │ Store Tweet│            │            │
    │          │          │             │──────────>(DB)          │            │
    │          │          │             │            │             │            │
    │          │          │             │ Publish    │             │            │
    │          │          │             │────────────>             │            │
    │          │          │             │            │ Consume     │            │
    │          │          │             │            │────────────>│            │
    │          │          │             │            │             │            │
    │          │          │             │            │             │ For each   │
    │          │          │             │            │             │ follower   │
    │          │          │             │            │             │───────────>│
    │          │          │             │            │             │            │
    │<─────────│<─────────│<────────────│            │             │            │
    │ 201 Created         │             │            │             │            │
```

### Timeline Generation Flow

```
┌────────┐  ┌─────┐  ┌───────────┐  ┌─────────┐  ┌────────┐  ┌───────────┐
│ Client │  │ LB  │  │ Timeline  │  │  Cache  │  │Tweet DB│  │ User      │
│        │  │     │  │ Service   │  │ (Redis) │  │        │  │ Service   │
└───┬────┘  └──┬──┘  └─────┬─────┘  └────┬────┘  └───┬────┘  └─────┬─────┘
    │          │           │             │           │             │
    │ GET      │           │             │           │             │
    │/timeline │           │             │           │             │
    │─────────>│           │             │           │             │
    │          │──────────>│             │           │             │
    │          │           │             │           │             │
    │          │           │ Check Cache │           │             │
    │          │           │────────────>│           │             │
    │          │           │             │           │             │
    │          │           │ Cache HIT   │           │             │
    │          │           │<────────────│           │             │
    │          │           │             │           │             │
    │          │           │ (If MISS - for celebrities)          │
    │          │           │─────────────────────────────────────>│
    │          │           │             │           │  Get following
    │          │           │             │           │             │
    │          │           │             │           │<────────────│
    │          │           │ Fetch tweets│           │             │
    │          │           │──────────────────────>│             │
    │          │           │             │           │             │
    │          │           │<──────────────────────│             │
    │          │           │             │           │             │
    │          │           │ Merge, Sort, Return   │             │
    │<─────────│<──────────│             │           │             │
    │ Timeline │           │             │           │             │
```

### Search Flow

```
┌────────┐  ┌─────┐  ┌───────────┐  ┌───────────────┐  ┌──────────┐
│ Client │  │ LB  │  │  Search   │  │ Elasticsearch │  │ Tweet DB │
│        │  │     │  │  Service  │  │               │  │          │
└───┬────┘  └──┬──┘  └─────┬─────┘  └───────┬───────┘  └────┬─────┘
    │          │           │                │               │
    │ GET      │           │                │               │
    │/search?q=│           │                │               │
    │─────────>│           │                │               │
    │          │──────────>│                │               │
    │          │           │                │               │
    │          │           │ Search Query   │               │
    │          │           │───────────────>│               │
    │          │           │                │               │
    │          │           │ Tweet IDs      │               │
    │          │           │<───────────────│               │
    │          │           │                │               │
    │          │           │ Fetch Tweet Details            │
    │          │           │───────────────────────────────>│
    │          │           │                │               │
    │          │           │<───────────────────────────────│
    │          │           │                │               │
    │<─────────│<──────────│                │               │
    │ Results  │           │                │               │
```

---

## Detailed Component Design

### 1. Timeline Generation (Deep Dive)

The timeline is the core feature. There are two main approaches:

#### Approach 1: Fan-out on Write (Push Model)

```
┌─────────────────────────────────────────────────────────────────┐
│                    FAN-OUT ON WRITE                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  When user posts tweet:                                         │
│  1. Store tweet in Tweet DB                                     │
│  2. Get list of all followers                                   │
│  3. For each follower: Add tweet to their timeline cache       │
│                                                                 │
│  Example: User A (1000 followers) posts tweet                   │
│                                                                 │
│  ┌─────────┐     ┌─────────────┐                               │
│  │ User A  │────>│ Tweet Store │                               │
│  │ tweets  │     └─────────────┘                               │
│  └─────────┘            │                                       │
│                         ▼                                       │
│                  ┌─────────────┐                                │
│                  │   Fanout    │                                │
│                  │   Service   │                                │
│                  └──────┬──────┘                                │
│                         │                                       │
│       ┌─────────────────┼─────────────────┐                    │
│       ▼                 ▼                 ▼                    │
│  ┌─────────┐       ┌─────────┐       ┌─────────┐              │
│  │Follower1│       │Follower2│  ...  │Follower │              │
│  │Timeline │       │Timeline │       │  1000   │              │
│  └─────────┘       └─────────┘       └─────────┘              │
│                                                                 │
│  Pros: Fast timeline reads (pre-computed)                       │
│  Cons: Slow writes for users with many followers                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Approach 2: Fan-out on Read (Pull Model)

```
┌─────────────────────────────────────────────────────────────────┐
│                    FAN-OUT ON READ                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  When user requests timeline:                                   │
│  1. Get list of users they follow                               │
│  2. Fetch recent tweets from each followed user                 │
│  3. Merge and sort tweets                                       │
│  4. Return top N tweets                                         │
│                                                                 │
│  ┌─────────┐                                                    │
│  │ User B  │                                                    │
│  │requests │                                                    │
│  │timeline │                                                    │
│  └────┬────┘                                                    │
│       │                                                         │
│       ▼                                                         │
│  ┌─────────────┐                                                │
│  │  Timeline   │                                                │
│  │  Service    │                                                │
│  └──────┬──────┘                                                │
│         │                                                       │
│         │ Fetch tweets from followed users                      │
│         │                                                       │
│  ┌──────┴──────┬──────────────┬──────────────┐                 │
│  ▼             ▼              ▼              ▼                 │
│ User X      User Y        User Z         User W                │
│ tweets      tweets        tweets         tweets                │
│                                                                 │
│  Pros: Fast writes, no celebrity problem                        │
│  Cons: Slow reads (compute on demand)                           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Twitter's Hybrid Approach (Recommended)

```
┌─────────────────────────────────────────────────────────────────┐
│                     HYBRID APPROACH                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Classification:                                                 │
│  - Regular users (<10K followers): Fan-out on Write             │
│  - Celebrities (>10K followers): Fan-out on Read                │
│                                                                 │
│  Timeline Generation:                                            │
│                                                                 │
│  ┌─────────────┐                                                │
│  │ User's      │                                                │
│  │ Timeline    │                                                │
│  │ Request     │                                                │
│  └──────┬──────┘                                                │
│         │                                                       │
│         ▼                                                       │
│  ┌──────────────────────────────────────────────────────┐      │
│  │                                                      │      │
│  │  ┌─────────────────┐    ┌─────────────────┐         │      │
│  │  │ Pre-computed    │    │ Celebrity tweets│         │      │
│  │  │ timeline (cache)│  + │ (fetch on read) │         │      │
│  │  │                 │    │                 │         │      │
│  │  │ From regular    │    │ From followed   │         │      │
│  │  │ users           │    │ celebrities     │         │      │
│  │  └─────────────────┘    └─────────────────┘         │      │
│  │                                                      │      │
│  └──────────────────────────────────────────────────────┘      │
│                         │                                       │
│                         ▼                                       │
│                   Merge & Sort                                  │
│                   Return Top N                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class TimelineService:
    CELEBRITY_THRESHOLD = 10000
    
    async def get_timeline(self, user_id, page=0, size=50):
        # Get pre-computed timeline from cache
        cached_timeline = await redis.zrevrange(
            f"timeline:{user_id}",
            page * size,
            (page + 1) * size - 1
        )
        
        # Get list of celebrities user follows
        celebrities = await self.get_followed_celebrities(user_id)
        
        # Fetch celebrity tweets
        celebrity_tweets = []
        for celeb_id in celebrities:
            tweets = await self.tweet_service.get_recent_tweets(celeb_id, limit=10)
            celebrity_tweets.extend(tweets)
        
        # Merge cached timeline with celebrity tweets
        all_tweets = cached_timeline + celebrity_tweets
        
        # Sort by timestamp and return top N
        all_tweets.sort(key=lambda t: t.created_at, reverse=True)
        return all_tweets[:size]
    
    async def fanout_tweet(self, tweet, author_followers_count):
        if author_followers_count < self.CELEBRITY_THRESHOLD:
            # Fan-out on write for regular users
            followers = await self.get_followers(tweet.author_id)
            
            # Use Kafka for async fanout
            for batch in chunks(followers, 1000):
                await kafka.send("fanout", {
                    "tweet_id": tweet.id,
                    "follower_ids": batch
                })
        else:
            # Celebrity - no fanout, will be fetched on read
            pass
```

### 2. Tweet Storage (Deep Dive)

#### Database Schema

```sql
-- Tweet Table (Cassandra)
CREATE TABLE tweets (
    tweet_id        BIGINT,
    user_id         BIGINT,
    content         TEXT,
    media_urls      LIST<TEXT>,
    created_at      TIMESTAMP,
    reply_to_id     BIGINT,        -- For threaded replies
    retweet_of_id   BIGINT,        -- For retweets
    quote_tweet_id  BIGINT,        -- For quote tweets
    like_count      COUNTER,
    retweet_count   COUNTER,
    reply_count     COUNTER,
    
    PRIMARY KEY ((user_id), created_at, tweet_id)
) WITH CLUSTERING ORDER BY (created_at DESC, tweet_id DESC);

-- Timeline Table (Cassandra)
CREATE TABLE timelines (
    user_id         BIGINT,
    tweet_id        BIGINT,
    author_id       BIGINT,
    created_at      TIMESTAMP,
    
    PRIMARY KEY ((user_id), created_at, tweet_id)
) WITH CLUSTERING ORDER BY (created_at DESC, tweet_id DESC);

-- User Table (PostgreSQL)
CREATE TABLE users (
    user_id         BIGSERIAL PRIMARY KEY,
    username        VARCHAR(15) UNIQUE NOT NULL,
    display_name    VARCHAR(50),
    email           VARCHAR(255) UNIQUE NOT NULL,
    password_hash   VARCHAR(255),
    bio             VARCHAR(160),
    profile_image   TEXT,
    follower_count  BIGINT DEFAULT 0,
    following_count BIGINT DEFAULT 0,
    tweet_count     BIGINT DEFAULT 0,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_verified     BOOLEAN DEFAULT FALSE,
    is_celebrity    BOOLEAN DEFAULT FALSE
);
```

#### Sharding Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    SHARDING STRATEGY                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Tweet Sharding: By user_id                                     │
│  - All tweets from a user on same shard                         │
│  - Enables efficient user timeline queries                      │
│  - Hot users may cause hotspots                                 │
│                                                                 │
│  Shard Key: user_id % num_shards                                │
│                                                                 │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────────┐           │
│  │ Shard 0 │  │ Shard 1 │  │ Shard 2 │  │ Shard 3 │           │
│  │         │  │         │  │         │  │         │           │
│  │Users    │  │Users    │  │Users    │  │Users    │           │
│  │0,4,8... │  │1,5,9... │  │2,6,10.. │  │3,7,11.. │           │
│  └─────────┘  └─────────┘  └─────────┘  └─────────┘           │
│                                                                 │
│  Timeline Sharding: By viewer_user_id                           │
│  - Each user's timeline on one shard                            │
│  - Fast reads for specific user                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3. Search System (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────┐
│                    SEARCH ARCHITECTURE                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                  ELASTICSEARCH CLUSTER                   │   │
│  │                                                          │   │
│  │  Indices:                                                │   │
│  │  ├── tweets (sharded by time: tweets_2024_01, etc.)     │   │
│  │  ├── users (single index, sharded by user_id hash)      │   │
│  │  └── hashtags (single index)                            │   │
│  │                                                          │   │
│  │  Tweet Index Mapping:                                    │   │
│  │  {                                                       │   │
│  │    "tweet_id": "keyword",                               │   │
│  │    "user_id": "keyword",                                │   │
│  │    "content": "text",         // Full-text search       │   │
│  │    "hashtags": "keyword",     // For exact match        │   │
│  │    "mentions": "keyword",                               │   │
│  │    "created_at": "date",                                │   │
│  │    "engagement_score": "float" // For ranking           │   │
│  │  }                                                       │   │
│  │                                                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Search Pipeline:                                               │
│                                                                 │
│  Query → Parse → Tokenize → Search Index → Rank → Filter       │
│                                                                 │
│  Ranking Factors:                                               │
│  - Text relevance (TF-IDF / BM25)                              │
│  - Recency (time decay)                                        │
│  - Engagement (likes, retweets)                                │
│  - User authority (verified, followers)                        │
│  - Personalization (user's interests)                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class SearchService:
    def __init__(self, es_client):
        self.es = es_client
    
    async def search_tweets(self, query, user_id=None, page=0, size=20):
        # Build Elasticsearch query
        es_query = {
            "bool": {
                "must": [
                    {
                        "multi_match": {
                            "query": query,
                            "fields": ["content^2", "hashtags", "user.username"],
                            "type": "best_fields"
                        }
                    }
                ],
                "should": [
                    # Boost recent tweets
                    {
                        "range": {
                            "created_at": {
                                "gte": "now-1d",
                                "boost": 2.0
                            }
                        }
                    },
                    # Boost high engagement
                    {
                        "range": {
                            "engagement_score": {
                                "gte": 100,
                                "boost": 1.5
                            }
                        }
                    }
                ]
            }
        }
        
        # Add personalization if user is logged in
        if user_id:
            user_interests = await self.get_user_interests(user_id)
            es_query["bool"]["should"].append({
                "terms": {
                    "hashtags": user_interests,
                    "boost": 1.2
                }
            })
        
        result = await self.es.search(
            index="tweets_*",
            query=es_query,
            from_=page * size,
            size=size,
            sort=[
                {"_score": "desc"},
                {"created_at": "desc"}
            ]
        )
        
        return result["hits"]["hits"]
```

---

## Trade-offs and Tech Choices

### 1. Consistency vs Availability

| Scenario | Choice | Rationale |
|----------|--------|-----------|
| Timeline | Eventual Consistency | Users can tolerate slight delays |
| Tweet Counts | Eventual Consistency | Approximate counts acceptable |
| Follow/Unfollow | Strong Consistency | Must be accurate |
| User Authentication | Strong Consistency | Security critical |

### 2. Database Selection

| Data Type | Database | Reason |
|-----------|----------|--------|
| Tweets | Cassandra | High write throughput, time-series |
| Timelines | Redis | Fast reads, sorted sets |
| Users | PostgreSQL | ACID for user data |
| Follows | Neo4j | Graph relationships |
| Search | Elasticsearch | Full-text search |

### 3. Caching Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                    CACHING LAYERS                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Layer 1: CDN (Static assets, profile images)                   │
│  TTL: Hours to days                                             │
│                                                                 │
│  Layer 2: Application Cache (Local memory)                      │
│  TTL: Minutes                                                   │
│  Content: Hot tweets, trending topics                           │
│                                                                 │
│  Layer 3: Distributed Cache (Redis)                             │
│  TTL: Minutes to hours                                          │
│  Content: Timelines, user sessions, counters                    │
│                                                                 │
│  Cache Invalidation Strategy:                                   │
│  - Timelines: Write-through (update on new tweet)              │
│  - User data: Write-behind with pub/sub invalidation           │
│  - Counters: Eventual consistency (batch updates)              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4. Push vs Pull for Notifications

| Type | When | Method |
|------|------|--------|
| High Priority | Mentions, DMs | WebSocket Push |
| Medium Priority | Likes, Retweets | Push + Pull fallback |
| Low Priority | New followers | Pull on app open |

---

## Failure Scenarios and Bottlenecks

### 1. Celebrity Problem

```
Problem: Celebrity with 50M followers posts tweet
Traditional fan-out: 50M writes = hours to complete

Solution: Hybrid fan-out
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  Celebrity posts tweet:                                         │
│  1. Store tweet in Tweet DB                                     │
│  2. Mark user as celebrity (cached flag)                        │
│  3. NO fan-out to follower timelines                           │
│                                                                 │
│  Follower requests timeline:                                    │
│  1. Get pre-computed timeline (from regular users)              │
│  2. Identify followed celebrities                               │
│  3. Fetch recent celebrity tweets (small number)               │
│  4. Merge and return                                            │
│                                                                 │
│  Celebrity threshold: 10,000 followers                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Hot Partition

```
Problem: Trending topic causes all searches to hit same shard

Solution: 
┌─────────────────────────────────────────────────────────────────┐
│  1. Replicate hot shards                                        │
│  2. Add caching layer for trending queries                      │
│  3. Rate limit searches per user                                │
│  4. Pre-compute trending results                                │
│                                                                 │
│  Implementation:                                                 │
│  - Monitor shard load in real-time                              │
│  - Auto-replicate shards above threshold                        │
│  - Cache trending search results (TTL: 30 seconds)             │
└─────────────────────────────────────────────────────────────────┘
```

### 3. Database Failover

```
┌─────────────────────────────────────────────────────────────────┐
│                CASSANDRA FAILURE HANDLING                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Node Failure:                                                   │
│  - Cassandra automatically routes to replica                    │
│  - Hinted handoff stores writes for failed node                 │
│  - Anti-entropy repair syncs on recovery                        │
│                                                                 │
│  Datacenter Failure:                                            │
│  - Multi-DC deployment (3 regions)                              │
│  - LOCAL_QUORUM for reads/writes                                │
│  - Automatic failover to other DC                               │
│                                                                 │
│  Recovery Time: ~0 seconds (automatic)                          │
│  Data Loss: 0 (RF=3 across DCs)                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4. Cache Failure

```
┌─────────────────────────────────────────────────────────────────┐
│                  REDIS FAILURE HANDLING                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Single Node Failure:                                           │
│  - Redis Cluster auto-promotes replica                          │
│  - ~1-2 seconds failover time                                   │
│                                                                 │
│  Entire Cache Failure:                                          │
│  - Graceful degradation to database                             │
│  - Rate limit DB queries                                        │
│  - Return stale data if available                               │
│  - Show "loading" for timeline                                  │
│                                                                 │
│  Cache Stampede Prevention:                                      │
│  - Request coalescing for same key                              │
│  - Staggered TTLs (jitter)                                      │
│  - Early refresh for expiring keys                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Future Improvements

### 1. Machine Learning Integration

```
┌─────────────────────────────────────────────────────────────────┐
│                    ML FEATURES                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Personalized Timeline Ranking:                                 │
│  - User engagement prediction model                             │
│  - Content similarity based on embeddings                       │
│  - Real-time feature pipeline (Kafka → Flink → Model)          │
│                                                                 │
│  Content Moderation:                                            │
│  - Toxicity detection (BERT-based)                             │
│  - Spam classification                                          │
│  - Bot detection                                                │
│                                                                 │
│  Recommendations:                                               │
│  - Who to follow (collaborative filtering)                      │
│  - Topics of interest (content-based)                           │
│  - Similar tweets (embedding similarity)                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Real-time Features

- Live tweet updates on timeline (WebSocket)
- Real-time like/retweet counts
- Live follower counts
- Typing indicators for DMs

### 3. Global Expansion

- Multi-region deployment
- Content localization
- Regional compliance (GDPR, etc.)
- Edge caching for media

---

## Interviewer Questions & Answers

### Q1: How would you design the timeline generation system?

**Answer:**
I would use a hybrid approach combining fan-out on write and fan-out on read:

**For regular users (< 10K followers):**
- Fan-out on write: When user posts, push tweet ID to all follower timelines
- Store timeline in Redis sorted set (score = timestamp)
- Limit timeline cache to last 800 tweets per user
- Timeline reads are O(1) - just fetch from Redis

**For celebrities (> 10K followers):**
- No fan-out on write
- On timeline request, fetch celebrity tweets separately
- Merge with pre-computed timeline from regular users

**Data structures:**
```
Redis:
timeline:{user_id} → Sorted Set of (tweet_id, timestamp)
celebrity_followers:{user_id} → Set of follower_ids
```

**Optimization:**
- Async fan-out via Kafka
- Batch updates (aggregate 100ms of follows then fan-out)
- Skip inactive users (no login in 7 days)

---

### Q2: How do you handle the celebrity problem?

**Answer:**
The celebrity problem occurs when a user with millions of followers posts a tweet, requiring millions of writes to update timelines.

**Solution: Hybrid Model**

1. **Classification:**
   - Track follower count for each user
   - Flag users with >10K followers as celebrities
   - Re-evaluate weekly (users can become/stop being celebrities)

2. **Tweet Posting:**
   ```python
   def post_tweet(user_id, content):
       tweet = store_tweet(user_id, content)
       
       if user.is_celebrity:
           # No fan-out, just store the tweet
           # Followers will fetch it on read
           pass
       else:
           # Fan-out to all followers
           followers = get_followers(user_id)
           for batch in chunk(followers, 1000):
               kafka.send("fanout_topic", {
                   "tweet_id": tweet.id,
                   "follower_ids": batch
               })
   ```

3. **Timeline Reading:**
   ```python
   def get_timeline(user_id):
       # Get pre-computed timeline (from regular users)
       regular_timeline = redis.zrevrange(f"timeline:{user_id}", 0, 800)
       
       # Get celebrities the user follows
       celebrities = get_followed_celebrities(user_id)
       
       # Fetch recent tweets from each celebrity
       celebrity_tweets = []
       for celeb_id in celebrities:
           tweets = get_recent_tweets(celeb_id, limit=10)
           celebrity_tweets.extend(tweets)
       
       # Merge and sort
       all_tweets = merge_sort(regular_timeline, celebrity_tweets)
       return all_tweets[:50]
   ```

**Trade-offs:**
- Slightly slower timeline reads (need to fetch celebrity tweets)
- But much faster tweet posting for celebrities
- Acceptable latency increase: ~20-50ms

---

### Q3: What database would you use for storing tweets?

**Answer:**
I'd use **Apache Cassandra** for tweet storage:

**Reasons:**
1. **Write-optimized:** Twitter has 5,800 writes/sec
2. **Horizontal scaling:** Add nodes linearly
3. **Time-series friendly:** Tweets naturally ordered by time
4. **No single point of failure:** Multi-datacenter replication

**Schema Design:**
```cql
CREATE TABLE tweets_by_user (
    user_id BIGINT,
    tweet_id BIGINT,
    created_at TIMESTAMP,
    content TEXT,
    media_urls LIST<TEXT>,
    
    PRIMARY KEY ((user_id), created_at, tweet_id)
) WITH CLUSTERING ORDER BY (created_at DESC);

-- For fetching individual tweets
CREATE TABLE tweets_by_id (
    tweet_id BIGINT PRIMARY KEY,
    user_id BIGINT,
    content TEXT,
    created_at TIMESTAMP,
    like_count COUNTER,
    retweet_count COUNTER
);
```

**Partitioning:**
- Partition by `user_id` - all tweets from a user together
- Cluster by `created_at DESC` - most recent first
- Each partition limited to ~1MB (last N tweets)

**Alternatives considered:**
- **PostgreSQL:** Good for user data but scaling writes is challenging
- **MongoDB:** Good flexibility but Cassandra better for time-series
- **DynamoDB:** Viable but vendor lock-in

---

### Q4: How would you implement the search functionality?

**Answer:**
I'd use **Elasticsearch** for full-text search:

**Architecture:**
```
Tweet Posted → Kafka → Search Indexer → Elasticsearch
                              ↓
                        Index Document
```

**Index Design:**
```json
{
  "settings": {
    "number_of_shards": 10,
    "number_of_replicas": 2
  },
  "mappings": {
    "properties": {
      "tweet_id": { "type": "keyword" },
      "content": { 
        "type": "text",
        "analyzer": "twitter_analyzer"
      },
      "hashtags": { "type": "keyword" },
      "mentions": { "type": "keyword" },
      "created_at": { "type": "date" },
      "user_id": { "type": "keyword" },
      "engagement_score": { "type": "float" }
    }
  }
}
```

**Custom Analyzer:**
```json
"twitter_analyzer": {
  "type": "custom",
  "tokenizer": "standard",
  "filter": ["lowercase", "hashtag_filter", "mention_filter"]
}
```

**Search Query:**
```python
def search_tweets(query, filters=None):
    es_query = {
        "bool": {
            "must": [
                {"match": {"content": query}}
            ],
            "should": [
                {"range": {"created_at": {"gte": "now-24h", "boost": 2}}},
                {"range": {"engagement_score": {"gte": 100, "boost": 1.5}}}
            ]
        }
    }
    
    results = es.search(
        index="tweets-*",
        query=es_query,
        sort=["_score", {"created_at": "desc"}]
    )
    return results
```

**Scaling:**
- Time-based indices: `tweets-2024-01`, `tweets-2024-02`
- Archive old indices (compress, reduce replicas)
- Separate clusters for real-time vs historical search

---

### Q5: How do you ensure high availability?

**Answer:**
Multi-layer redundancy:

**1. Application Layer:**
- Stateless servers behind load balancer
- Auto-scaling: 100-1000 instances
- Health checks every 5 seconds
- Graceful degradation

**2. Database Layer:**
```
Cassandra Cluster:
- 3 datacenters (US-East, EU-West, AP-South)
- 9 nodes per datacenter
- Replication factor: 3 (local), 1 (remote)
- Consistency: LOCAL_QUORUM
```

**3. Cache Layer:**
```
Redis Cluster:
- 6 masters + 6 replicas
- Automatic failover
- Cross-region replication for session data
```

**4. Service Isolation:**
- Circuit breakers between services
- Bulkheads to prevent cascade failures
- Timeouts on all external calls

**5. Geographic Redundancy:**
```
           ┌─────────────────┐
           │   Global DNS    │
           │   (Route 53)    │
           └────────┬────────┘
                    │
     ┌──────────────┼──────────────┐
     ▼              ▼              ▼
┌─────────┐   ┌─────────┐   ┌─────────┐
│ US-East │   │ EU-West │   │AP-South │
│  Stack  │   │  Stack  │   │  Stack  │
└─────────┘   └─────────┘   └─────────┘
```

**SLA Target:** 99.99% (52 minutes downtime/year)

---

### Q6: How would you implement trending topics?

**Answer:**

**Algorithm:** Count-Min Sketch + Time Decay

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                    TRENDING SYSTEM                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Tweet Posted → Extract Hashtags → Update Counters              │
│                                                                 │
│  Data Structure: Count-Min Sketch per time window               │
│                                                                 │
│  Windows:                                                        │
│  - Last 1 hour (high resolution)                                │
│  - Last 4 hours                                                 │
│  - Last 24 hours                                                │
│                                                                 │
│  Trending Score = Recent Count / Historical Baseline            │
│                                                                 │
│  Example:                                                        │
│  #earthquake: 50,000 tweets/hour (baseline: 100)               │
│  Score = 50,000 / 100 = 500 (HIGH TREND)                       │
│                                                                 │
│  #goodmorning: 100,000 tweets/hour (baseline: 90,000)          │
│  Score = 100,000 / 90,000 = 1.1 (NOT TRENDING)                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class TrendingService:
    def __init__(self):
        self.sketch = CountMinSketch(width=10000, depth=5)
        self.baseline = {}  # Historical averages
    
    def record_hashtag(self, hashtag, timestamp):
        # Update count-min sketch
        time_bucket = timestamp // 3600  # Hour bucket
        key = f"{hashtag}:{time_bucket}"
        self.sketch.add(key)
        
        # Update Redis sorted set for this hour
        redis.zincrby(f"trending:{time_bucket}", 1, hashtag)
    
    def get_trending(self, location=None, limit=10):
        current_bucket = time.time() // 3600
        
        # Get counts for current hour
        current_counts = redis.zrevrange(
            f"trending:{current_bucket}", 0, 100, withscores=True
        )
        
        # Calculate trending scores
        trending = []
        for hashtag, count in current_counts:
            baseline = self.baseline.get(hashtag, 1)
            score = count / baseline
            if score > 2:  # Must be 2x normal volume
                trending.append((hashtag, score, count))
        
        # Sort by score and return top N
        trending.sort(key=lambda x: x[1], reverse=True)
        return trending[:limit]
```

**Localization:**
- Separate trends per country/city
- Use user's location to filter
- Global trends as fallback

---

### Q7: How do you handle real-time notifications?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                  NOTIFICATION SYSTEM                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Event Sources:                                                  │
│  - Tweet Service (mentions, replies)                            │
│  - Social Service (follows, likes, retweets)                    │
│  - DM Service (direct messages)                                 │
│                                                                 │
│  Pipeline:                                                       │
│  Event → Kafka → Notification Service → Delivery                │
│                                                                 │
│  Delivery Methods:                                              │
│  ├── WebSocket (real-time, user online)                        │
│  ├── Push Notification (mobile, user offline)                  │
│  └── Email Digest (batched, daily/weekly)                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**WebSocket Implementation:**
```python
class NotificationGateway:
    def __init__(self):
        self.connections = {}  # user_id → WebSocket
    
    async def connect(self, user_id, websocket):
        self.connections[user_id] = websocket
        # Also store in Redis for distributed setup
        redis.sadd(f"ws_server:{self.server_id}", user_id)
    
    async def send_notification(self, user_id, notification):
        if user_id in self.connections:
            # User connected to this server
            await self.connections[user_id].send(notification)
        else:
            # Find which server has this user
            for server_id in redis.keys("ws_server:*"):
                if redis.sismember(server_id, user_id):
                    # Forward to that server via Redis pub/sub
                    redis.publish(f"notifications:{server_id}", {
                        "user_id": user_id,
                        "notification": notification
                    })
                    return
            
            # User not online, send push notification
            await self.send_push_notification(user_id, notification)
```

**Priority Handling:**
- **High:** Direct messages, mentions → Immediate push
- **Medium:** Likes, retweets → Batch every 1 minute
- **Low:** New followers → Batch every 5 minutes

---

### Q8: How would you implement retweets and quote tweets?

**Answer:**

**Data Model:**
```sql
-- Tweet table
CREATE TABLE tweets (
    tweet_id BIGINT PRIMARY KEY,
    user_id BIGINT,
    content TEXT,
    tweet_type ENUM('original', 'retweet', 'quote'),
    original_tweet_id BIGINT,  -- For retweets/quotes
    created_at TIMESTAMP
);

-- Retweet relationship (for fast "who retweeted" queries)
CREATE TABLE retweets (
    tweet_id BIGINT,
    retweeted_by_user_id BIGINT,
    retweeted_at TIMESTAMP,
    PRIMARY KEY ((tweet_id), retweeted_at, retweeted_by_user_id)
);
```

**Implementation:**
```python
def create_retweet(user_id, original_tweet_id):
    original = get_tweet(original_tweet_id)
    
    # Check if already retweeted
    if has_retweeted(user_id, original_tweet_id):
        raise AlreadyRetweetedError()
    
    # Create retweet
    retweet = Tweet(
        user_id=user_id,
        tweet_type="retweet",
        original_tweet_id=original_tweet_id,
        content=None  # Retweets have no new content
    )
    save_tweet(retweet)
    
    # Update retweet count (async)
    kafka.send("engagement", {
        "type": "retweet",
        "tweet_id": original_tweet_id,
        "user_id": user_id
    })
    
    # Fan-out to retweeter's followers
    fanout_tweet(retweet, user_id)
    
    return retweet

def create_quote_tweet(user_id, original_tweet_id, comment):
    # Quote tweet is a new tweet with reference
    quote = Tweet(
        user_id=user_id,
        tweet_type="quote",
        original_tweet_id=original_tweet_id,
        content=comment  # User's comment
    )
    save_tweet(quote)
    
    # Same fan-out as regular tweet
    fanout_tweet(quote, user_id)
    
    return quote
```

**Display Logic:**
```python
def render_tweet(tweet):
    if tweet.tweet_type == "retweet":
        # Show: "User retweeted"
        # Then show original tweet
        original = get_tweet(tweet.original_tweet_id)
        return {
            "header": f"{tweet.user.name} Retweeted",
            "tweet": render_tweet(original)
        }
    elif tweet.tweet_type == "quote":
        # Show user's comment with embedded original
        original = get_tweet(tweet.original_tweet_id)
        return {
            "content": tweet.content,
            "quoted_tweet": render_tweet(original)
        }
    else:
        return {"content": tweet.content}
```

---

### Q9: How do you handle rate limiting?

**Answer:**

**Strategy: Multi-level rate limiting**

```
┌─────────────────────────────────────────────────────────────────┐
│                    RATE LIMITING                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Level 1: Global (API Gateway)                                  │
│  - 10,000 requests/second total                                 │
│  - Protects against DDoS                                        │
│                                                                 │
│  Level 2: Per User                                              │
│  - Tweets: 300/3 hours                                          │
│  - Likes: 1000/day                                              │
│  - Follows: 400/day                                             │
│  - API calls: 450/15 minutes                                    │
│                                                                 │
│  Level 3: Per Endpoint                                          │
│  - Search: 180/15 minutes                                       │
│  - Timeline: 900/15 minutes                                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation (Token Bucket):**
```python
class RateLimiter:
    def __init__(self, redis_client):
        self.redis = redis_client
    
    def is_allowed(self, user_id, action, limit, window):
        key = f"rate:{user_id}:{action}"
        current_time = time.time()
        window_start = current_time - window
        
        # Use Redis sorted set for sliding window
        pipe = self.redis.pipeline()
        
        # Remove old entries
        pipe.zremrangebyscore(key, 0, window_start)
        
        # Count requests in window
        pipe.zcard(key)
        
        # Add current request
        pipe.zadd(key, {str(current_time): current_time})
        
        # Set expiry
        pipe.expire(key, window)
        
        results = pipe.execute()
        request_count = results[1]
        
        if request_count >= limit:
            return False, limit - request_count
        
        return True, limit - request_count - 1
    
    def check_tweet_limit(self, user_id):
        return self.is_allowed(user_id, "tweet", limit=300, window=3*3600)
```

**Response Headers:**
```
X-Rate-Limit-Limit: 300
X-Rate-Limit-Remaining: 150
X-Rate-Limit-Reset: 1234567890
```

---

### Q10: How would you design the follower/following system?

**Answer:**

**Data Model:**
```
┌─────────────────────────────────────────────────────────────────┐
│                    SOCIAL GRAPH                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Storage Options:                                               │
│                                                                 │
│  Option 1: Relational (for smaller scale)                       │
│  CREATE TABLE follows (                                         │
│      follower_id BIGINT,                                       │
│      following_id BIGINT,                                      │
│      created_at TIMESTAMP,                                      │
│      PRIMARY KEY (follower_id, following_id)                   │
│  );                                                             │
│                                                                 │
│  Option 2: Graph Database (Neo4j) - Recommended                 │
│  (User)-[:FOLLOWS]->(User)                                     │
│                                                                 │
│  Option 3: Adjacency List in NoSQL                              │
│  Cassandra with separate tables for followers/following        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**I'd recommend Neo4j for the social graph:**

```cypher
// Create follow relationship
MATCH (follower:User {id: $follower_id})
MATCH (following:User {id: $following_id})
CREATE (follower)-[:FOLLOWS {since: datetime()}]->(following)

// Get followers
MATCH (user:User {id: $user_id})<-[:FOLLOWS]-(follower)
RETURN follower
ORDER BY follower.follower_count DESC

// Get mutual follows
MATCH (user:User {id: $user_id})-[:FOLLOWS]->(friend)-[:FOLLOWS]->(user)
RETURN friend

// Recommend users to follow (friends of friends)
MATCH (user:User {id: $user_id})-[:FOLLOWS]->(friend)-[:FOLLOWS]->(suggested)
WHERE NOT (user)-[:FOLLOWS]->(suggested)
  AND suggested <> user
RETURN suggested, count(friend) AS mutual_friends
ORDER BY mutual_friends DESC
LIMIT 10
```

**Caching Strategy:**
```python
def get_followers(user_id, page=0, size=20):
    cache_key = f"followers:{user_id}:{page}"
    
    # Check cache
    cached = redis.get(cache_key)
    if cached:
        return json.loads(cached)
    
    # Query graph database
    followers = neo4j.run("""
        MATCH (u:User {id: $user_id})<-[:FOLLOWS]-(f)
        RETURN f
        SKIP $skip LIMIT $limit
    """, user_id=user_id, skip=page*size, limit=size)
    
    # Cache for 5 minutes
    redis.setex(cache_key, 300, json.dumps(followers))
    
    return followers

def follow_user(follower_id, following_id):
    # Create relationship
    neo4j.run("""
        MATCH (a:User {id: $follower_id})
        MATCH (b:User {id: $following_id})
        CREATE (a)-[:FOLLOWS {since: datetime()}]->(b)
    """, follower_id=follower_id, following_id=following_id)
    
    # Update counters (async)
    kafka.send("social_events", {
        "type": "follow",
        "follower_id": follower_id,
        "following_id": following_id
    })
    
    # Invalidate caches
    redis.delete(f"followers:{following_id}:*")
    redis.delete(f"following:{follower_id}:*")
```

---

### Q11: How do you handle content moderation at scale?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                CONTENT MODERATION PIPELINE                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Tweet Posted → Pre-publish Checks → ML Classifier              │
│                         │                                       │
│                         ▼                                       │
│               ┌─────────────────────┐                           │
│               │   Classification    │                           │
│               │   ├── Safe ────────────────────→ Publish        │
│               │   ├── Flagged ──────→ Queue for Review         │
│               │   └── Violating ───→ Block + Notify            │
│               └─────────────────────┘                           │
│                                                                 │
│  ML Models:                                                     │
│  - Toxicity detection (BERT-based)                             │
│  - Spam classification                                          │
│  - Adult content detection (images)                            │
│  - Misinformation signals                                       │
│                                                                 │
│  Post-publish:                                                   │
│  - User reports → Queue for review                              │
│  - Viral content auto-review                                    │
│  - Periodic batch scanning                                      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class ContentModerationService:
    def __init__(self):
        self.toxicity_model = load_model("toxicity")
        self.spam_model = load_model("spam")
    
    async def check_tweet(self, tweet):
        # Run all checks in parallel
        results = await asyncio.gather(
            self.check_toxicity(tweet.content),
            self.check_spam(tweet),
            self.check_forbidden_links(tweet.content)
        )
        
        toxicity_score, spam_score, has_forbidden_links = results
        
        if toxicity_score > 0.9 or has_forbidden_links:
            return ModResult.BLOCK
        elif toxicity_score > 0.7 or spam_score > 0.8:
            return ModResult.REVIEW
        else:
            return ModResult.SAFE
    
    async def check_toxicity(self, text):
        # BERT-based toxicity model
        embedding = self.tokenizer(text)
        score = self.toxicity_model.predict(embedding)
        return score
```

---

### Q12: How would you implement direct messages (DMs)?

**Answer:**

**Data Model:**
```sql
-- Conversations
CREATE TABLE conversations (
    conversation_id BIGINT PRIMARY KEY,
    participant_ids BIGINT[],  -- Sorted for consistency
    created_at TIMESTAMP,
    last_message_at TIMESTAMP,
    is_group BOOLEAN DEFAULT FALSE
);

-- Messages
CREATE TABLE messages (
    message_id BIGINT,
    conversation_id BIGINT,
    sender_id BIGINT,
    content TEXT,           -- Encrypted
    media_urls TEXT[],
    created_at TIMESTAMP,
    read_by BIGINT[],
    
    PRIMARY KEY ((conversation_id), created_at, message_id)
) WITH CLUSTERING ORDER BY (created_at DESC);
```

**Encryption (E2E):**
```
┌─────────────────────────────────────────────────────────────────┐
│            END-TO-END ENCRYPTION                                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Key Exchange: X25519 (Curve25519)                              │
│  Encryption: AES-256-GCM                                        │
│  Key Derivation: HKDF-SHA256                                    │
│                                                                 │
│  Flow:                                                           │
│  1. Each user has public/private key pair                       │
│  2. Sender encrypts message with shared secret                  │
│  3. Server stores encrypted message                             │
│  4. Recipient decrypts with their private key                   │
│                                                                 │
│  Server never sees plaintext                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Real-time Delivery:**
```python
class DMService:
    async def send_message(self, conversation_id, sender_id, content):
        # Encrypt content
        conversation = get_conversation(conversation_id)
        encrypted = encrypt_for_recipients(content, conversation.participants)
        
        # Store message
        message = Message(
            conversation_id=conversation_id,
            sender_id=sender_id,
            content=encrypted,
            created_at=datetime.now()
        )
        save_message(message)
        
        # Real-time delivery
        for participant_id in conversation.participant_ids:
            if participant_id != sender_id:
                await websocket_gateway.send(participant_id, {
                    "type": "new_dm",
                    "message": message.to_dict()
                })
        
        return message
```

---

### Bonus: Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                 TWITTER - FINAL ARCHITECTURE                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 500M DAU, 500M tweets/day                               │
│                                                                 │
│  Core Components:                                               │
│  ├── CDN (Akamai) - Static content, media                      │
│  ├── Load Balancers (L4/L7) - Traffic distribution             │
│  ├── API Gateway - Auth, rate limiting                         │
│  ├── Tweet Service - CRUD operations                           │
│  ├── Timeline Service - Hybrid fan-out                         │
│  ├── Search Service - Elasticsearch                            │
│  ├── Notification Service - WebSocket + Push                   │
│  └── Fanout Service - Kafka-based distribution                 │
│                                                                 │
│  Data Stores:                                                   │
│  ├── Cassandra - Tweets, timelines                             │
│  ├── PostgreSQL - User data                                    │
│  ├── Neo4j - Social graph                                      │
│  ├── Elasticsearch - Search index                              │
│  ├── Redis Cluster - Cache, sessions                           │
│  └── S3 - Media storage                                        │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── Hybrid timeline (push for regular, pull for celebrities)  │
│  ├── Eventual consistency for social features                  │
│  ├── Async processing via Kafka                                │
│  └── Multi-region deployment                                   │
│                                                                 │
│  SLA: 99.99% availability, <200ms timeline latency             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
