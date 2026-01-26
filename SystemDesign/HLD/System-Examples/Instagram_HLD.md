# Instagram - High Level Design

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

1. **Photo/Video Upload**
   - Upload photos and short videos (Reels)
   - Apply filters and editing
   - Add captions, tags, and location

2. **Feed Generation**
   - Home feed with posts from followed users
   - Explore feed with recommended content
   - Stories (24-hour ephemeral content)
   - Reels feed (short-form video)

3. **Social Features**
   - Follow/unfollow users
   - Like, comment, save posts
   - Share posts via DMs
   - Tag users in posts

4. **Direct Messaging**
   - 1:1 and group messaging
   - Share posts, stories, reels
   - Photo/video messages

5. **Search & Discovery**
   - Search users, hashtags, locations
   - Explore page with trending content
   - Suggested users to follow

6. **Notifications**
   - Likes, comments, follows
   - Story mentions, tags

### Non-Functional Requirements

1. **Scale**: 2B monthly active users, 500M daily active users
2. **Availability**: 99.99% uptime
3. **Latency**: Feed load < 500ms, Image load < 100ms
4. **Storage**: Store billions of photos/videos reliably
5. **Durability**: User content must never be lost
6. **Consistency**: Eventual consistency acceptable for social features

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Monthly Active Users (MAU): 2 Billion
- Daily Active Users (DAU): 500 Million
- Average session time: 30 minutes

Content:
- Photos uploaded/day: 100 Million
- Videos uploaded/day: 50 Million
- Stories created/day: 500 Million
- Average photos viewed/user/day: 200

Writes:
- Photo uploads: 100M / 86400 = 1,160 uploads/sec
- Peak: 5,000 uploads/sec

Reads:
- Photo views: 500M * 200 = 100 Billion/day
- Photo views/sec: 100B / 86400 = 1.16 Million views/sec
```

### Storage Estimates

```
Photo Storage:
- Average photo size (multiple resolutions): 2 MB
- Original: 1 MB
- Large: 500 KB
- Medium: 200 KB
- Thumbnail: 50 KB

- Photos/day: 100 Million * 2 MB = 200 TB/day
- Photos/year: 200 TB * 365 = 73 PB/year
- 5 years: 365 PB

Video Storage:
- Average video size: 50 MB (after compression)
- Videos/day: 50 Million * 50 MB = 2.5 PB/day
- Videos/year: 912 PB/year

Total Storage (5 years): ~5 Exabytes

Metadata Storage:
- Per post: 1 KB (post info, user, location, etc.)
- 150M posts/day * 1 KB = 150 GB/day
- 5 years: ~275 TB
```

### Bandwidth Estimates

```
Incoming (Uploads):
- Photos: 1,160/sec * 1 MB = 1.16 GB/sec
- Videos: 580/sec * 50 MB = 29 GB/sec
- Total: ~30 GB/sec incoming

Outgoing (Downloads):
- Photo views: 1.16M/sec * 200 KB (average) = 232 GB/sec
- Video streams: 100K/sec * 5 MB/sec = 500 GB/sec
- Total: ~750 GB/sec outgoing
```

### Memory Estimates

```
Cache Requirements:
- Hot posts (last 24 hours): 150M * 1 KB = 150 GB
- User sessions: 500M * 500 bytes = 250 GB
- Feed cache: 100M active users * 50 KB = 5 TB
- Total cache: ~6 TB
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENTS                                           │
│                           (iOS, Android, Web)                                        │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                      CDN                                             │
│                            (CloudFront / Akamai)                                     │
│                    ┌──────────────────────────────────────┐                         │
│                    │  Images, Videos, Static Assets       │                         │
│                    └──────────────────────────────────────┘                         │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LOAD BALANCER                                           │
│                           (AWS ALB / Nginx)                                          │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    │                                         │
                    ▼                                         ▼
┌───────────────────────────────────────┐    ┌───────────────────────────────────────┐
│           API GATEWAY                  │    │        WEBSOCKET GATEWAY              │
│   (Auth, Rate Limit, Routing)         │    │     (Real-time: DMs, Notifications)   │
└───────────────────┬───────────────────┘    └───────────────────────────────────────┘
                    │
    ┌───────┬───────┼───────┬───────┬───────┬───────┐
    │       │       │       │       │       │       │
    ▼       ▼       ▼       ▼       ▼       ▼       ▼
┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐ ┌───────┐
│ User  │ │ Post  │ │ Feed  │ │Search │ │ Story │ │ DM    │
│Service│ │Service│ │Service│ │Service│ │Service│ │Service│
└───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘ └───┬───┘
    │         │         │         │         │         │
    └─────────┴─────────┴─────────┴─────────┴─────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE QUEUE (Kafka)                                   │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│   │Feed Fanout  │  │Notification │  │Media Process│  │ Analytics   │               │
│   │   Topic     │  │   Topic     │  │   Topic     │  │   Topic     │               │
│   └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘               │
└─────────────────────────────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────┴────────────────────┐
         ▼                                         ▼
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│     MEDIA PROCESSING SERVICE    │  │      FANOUT SERVICE              │
│  ┌───────────────────────────┐ │  │  ┌───────────────────────────┐  │
│  │ - Resize images          │ │  │  │ - Distribute posts        │  │
│  │ - Compress videos        │ │  │  │ - Update timelines        │  │
│  │ - Apply filters          │ │  │  │ - Generate feeds          │  │
│  │ - Generate thumbnails    │ │  │  └───────────────────────────┘  │
│  └───────────────────────────┘ │  └─────────────────────────────────┘
└─────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├───────────────┬───────────────┬───────────────┬───────────────┬─────────────────────┤
│   CACHE       │  METADATA DB  │   GRAPH DB    │   SEARCH      │   OBJECT STORAGE    │
│   (Redis)     │  (PostgreSQL/ │   (Neo4j)     │(Elasticsearch)│   (S3)              │
│               │   Cassandra)  │               │               │                     │
│ - Feed cache  │ - Posts       │ - Followers   │ - Users       │ - Photos            │
│ - Sessions    │ - Users       │ - Following   │ - Hashtags    │ - Videos            │
│ - Counters    │ - Comments    │ - Suggestions │ - Locations   │ - Stories           │
└───────────────┴───────────────┴───────────────┴───────────────┴─────────────────────┘
```

### Core Services

1. **User Service**: Registration, authentication, profiles
2. **Post Service**: Create, read, update, delete posts
3. **Feed Service**: Generate home and explore feeds
4. **Story Service**: Create and serve ephemeral stories
5. **Media Service**: Process and serve images/videos
6. **Search Service**: Search users, hashtags, locations
7. **DM Service**: Direct messaging functionality
8. **Notification Service**: Push and in-app notifications

---

## Request Flows

### Photo Upload Flow

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌────────┐  ┌─────────┐  ┌───────┐
│ Client │  │ LB  │  │ API GW  │  │   Post    │  │ Media  │  │   S3    │  │ Kafka │
│        │  │     │  │         │  │  Service  │  │ Service│  │         │  │       │
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └────┬───┘  └────┬────┘  └───┬───┘
    │          │          │             │             │           │           │
    │ Upload   │          │             │             │           │           │
    │ Photo    │          │             │             │           │           │
    │─────────>│          │             │             │           │           │
    │          │─────────>│             │             │           │           │
    │          │          │ Auth Check  │             │           │           │
    │          │          │────────────>│             │           │           │
    │          │          │             │             │           │           │
    │          │          │             │ Get Pre-signed URL      │           │
    │          │          │             │────────────>│           │           │
    │          │          │             │             │──────────>│           │
    │          │          │             │             │<──────────│           │
    │          │          │             │<────────────│           │           │
    │          │<─────────│<────────────│ Return URL  │           │           │
    │<─────────│          │             │             │           │           │
    │          │          │             │             │           │           │
    │ Direct upload to S3 │             │             │           │           │
    │─────────────────────────────────────────────────────────────>           │
    │          │          │             │             │           │           │
    │ Confirm  │          │             │             │           │           │
    │ Upload   │          │             │             │           │           │
    │─────────>│─────────>│────────────>│             │           │           │
    │          │          │             │ Store Post  │           │           │
    │          │          │             │─────────────────────────>│           │
    │          │          │             │             │           │           │
    │          │          │             │ Queue Media Processing  │           │
    │          │          │             │─────────────────────────────────────>│
    │          │          │             │             │           │           │
    │          │          │             │ Queue Feed Fanout       │           │
    │          │          │             │─────────────────────────────────────>│
    │          │          │             │             │           │           │
    │<─────────│<─────────│<────────────│ 201 Created │           │           │
```

### Feed Generation Flow

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌────────┐  ┌──────────┐
│ Client │  │ LB  │  │  Feed   │  │   Cache   │  │Post DB │  │ ML Ranker│
│        │  │     │  │ Service │  │  (Redis)  │  │        │  │          │
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └───┬────┘  └────┬─────┘
    │          │          │             │            │            │
    │ GET /feed│          │             │            │            │
    │─────────>│          │             │            │            │
    │          │─────────>│             │            │            │
    │          │          │             │            │            │
    │          │          │ Get Cached Feed          │            │
    │          │          │────────────>│            │            │
    │          │          │             │            │            │
    │          │          │ Cache HIT   │            │            │
    │          │          │<────────────│            │            │
    │          │          │             │            │            │
    │          │          │ (If MISS)   │            │            │
    │          │          │ Get following list       │            │
    │          │          │─────────────────────────>│            │
    │          │          │             │            │            │
    │          │          │<─────────────────────────│            │
    │          │          │             │            │            │
    │          │          │ Get recent posts         │            │
    │          │          │─────────────────────────>│            │
    │          │          │             │            │            │
    │          │          │<─────────────────────────│            │
    │          │          │             │            │            │
    │          │          │ Rank Posts  │            │            │
    │          │          │─────────────────────────────────────>│
    │          │          │             │            │            │
    │          │          │<─────────────────────────────────────│
    │          │          │             │            │            │
    │          │          │ Cache Feed  │            │            │
    │          │          │────────────>│            │            │
    │          │          │             │            │            │
    │<─────────│<─────────│ Return Feed │            │            │
```

### Story View Flow

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌────────┐  ┌──────┐
│ Client │  │ CDN │  │  Story  │  │   Cache   │  │Story DB│  │  S3  │
│        │  │     │  │ Service │  │  (Redis)  │  │        │  │      │
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └───┬────┘  └───┬──┘
    │          │          │             │            │           │
    │ GET      │          │             │            │           │
    │/stories  │          │             │            │           │
    │─────────>│          │             │            │           │
    │          │          │             │            │           │
    │          │ Check CDN Cache        │            │           │
    │          │─────────>│             │            │           │
    │          │          │             │            │           │
    │          │ (MISS)   │             │            │           │
    │          │─────────>│             │            │           │
    │          │          │ Get Story IDs            │           │
    │          │          │────────────>│            │           │
    │          │          │             │            │           │
    │          │          │<────────────│            │           │
    │          │          │             │            │           │
    │          │          │ Get Story Metadata       │           │
    │          │          │─────────────────────────>│           │
    │          │          │             │            │           │
    │          │          │<─────────────────────────│           │
    │          │          │             │            │           │
    │          │          │ Generate Signed URLs     │           │
    │          │          │────────────────────────────────────>│
    │          │          │             │            │           │
    │<─────────│<─────────│ Story URLs  │            │           │
    │          │          │             │            │           │
    │ Load     │          │             │            │           │
    │ Media    │          │             │            │           │
    │─────────>│ Fetch from S3/CDN      │            │           │
    │<─────────│          │             │            │           │
```

---

## Detailed Component Design

### 1. Media Processing Pipeline (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          MEDIA PROCESSING PIPELINE                                   │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Original Upload                                                                    │
│       │                                                                             │
│       ▼                                                                             │
│  ┌─────────────────┐                                                               │
│  │   S3 Bucket     │                                                               │
│  │   (Raw Media)   │                                                               │
│  └────────┬────────┘                                                               │
│           │                                                                         │
│           │ S3 Event Notification                                                   │
│           ▼                                                                         │
│  ┌─────────────────┐                                                               │
│  │   Kafka Topic   │                                                               │
│  │ (media-process) │                                                               │
│  └────────┬────────┘                                                               │
│           │                                                                         │
│           │ Multiple Consumer Groups                                                │
│           ├──────────────────┬──────────────────┬──────────────────┐               │
│           ▼                  ▼                  ▼                  ▼               │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐  │
│  │  Image Resizer  │ │  Video Trans-   │ │  Thumbnail      │ │  Content        │  │
│  │  (Lambda/ECS)   │ │  coder (MediaConvert)  │  Generator │ │  Scanner (ML)   │  │
│  └────────┬────────┘ └────────┬────────┘ └────────┬────────┘ └────────┬────────┘  │
│           │                  │                  │                  │               │
│           │  Multiple Resolutions              │                  │               │
│           │  ├── 1080x1080 (Full)              │                  │               │
│           │  ├── 640x640 (Medium)              │                  │               │
│           │  ├── 320x320 (Small)               │                  │               │
│           │  └── 150x150 (Thumbnail)           │                  │               │
│           │                  │                  │                  │               │
│           │  Video Outputs   │                  │                  │               │
│           │  ├── 1080p       │                  │                  │               │
│           │  ├── 720p        │                  │                  │               │
│           │  ├── 480p        │                  │                  │               │
│           │  └── HLS segments│                  │                  │               │
│           │                  │                  │                  │               │
│           └──────────────────┴──────────────────┴──────────────────┘               │
│                              │                                                      │
│                              ▼                                                      │
│                    ┌─────────────────┐                                             │
│                    │   S3 Bucket     │                                             │
│                    │  (Processed)    │                                             │
│                    └────────┬────────┘                                             │
│                             │                                                       │
│                             ▼                                                       │
│                    ┌─────────────────┐                                             │
│                    │      CDN        │                                             │
│                    │  Distribution   │                                             │
│                    └─────────────────┘                                             │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class MediaProcessingService:
    def __init__(self):
        self.s3 = boto3.client('s3')
        self.sqs = boto3.client('sqs')
        
    async def process_image(self, s3_key: str, post_id: str):
        # Download original
        original = self.s3.get_object(Bucket='raw-media', Key=s3_key)
        image = Image.open(original['Body'])
        
        # Generate multiple resolutions
        resolutions = [
            ('full', 1080),
            ('medium', 640),
            ('small', 320),
            ('thumbnail', 150)
        ]
        
        processed_urls = {}
        for name, size in resolutions:
            resized = self.resize_image(image, size)
            optimized = self.optimize_image(resized)
            
            output_key = f"processed/{post_id}/{name}.webp"
            self.s3.put_object(
                Bucket='processed-media',
                Key=output_key,
                Body=optimized,
                ContentType='image/webp',
                CacheControl='max-age=31536000'
            )
            processed_urls[name] = f"https://cdn.instagram.com/{output_key}"
        
        # Update post with processed URLs
        await self.update_post_urls(post_id, processed_urls)
        
        # Invalidate CDN cache if needed
        await self.invalidate_cdn_cache(post_id)
        
        return processed_urls
    
    def resize_image(self, image: Image, max_size: int) -> Image:
        # Maintain aspect ratio
        image.thumbnail((max_size, max_size), Image.LANCZOS)
        return image
    
    def optimize_image(self, image: Image) -> bytes:
        buffer = BytesIO()
        image.save(buffer, format='WEBP', quality=85, optimize=True)
        return buffer.getvalue()
```

### 2. Feed Generation System (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           FEED GENERATION SYSTEM                                     │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Feed Generation Strategy: Hybrid (Pre-computed + On-demand)                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                        FEED CANDIDATE SOURCES                                │   │
│  │                                                                              │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │   │
│  │  │  Following   │  │   Network    │  │   Explore    │  │    Ads       │    │   │
│  │  │   Posts      │  │  (Friends of │  │  (Discovery) │  │  (Sponsored) │    │   │
│  │  │              │  │   Friends)   │  │              │  │              │    │   │
│  │  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘    │   │
│  │         │                 │                 │                 │            │   │
│  │         └─────────────────┴─────────────────┴─────────────────┘            │   │
│  │                                   │                                        │   │
│  │                                   ▼                                        │   │
│  │                         ┌─────────────────┐                                │   │
│  │                         │   CANDIDATE     │                                │   │
│  │                         │     POOL        │                                │   │
│  │                         │  (~1000 posts)  │                                │   │
│  │                         └────────┬────────┘                                │   │
│  │                                  │                                         │   │
│  └──────────────────────────────────┼─────────────────────────────────────────┘   │
│                                     │                                             │
│                                     ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                           ML RANKING SYSTEM                                  │   │
│  │                                                                              │   │
│  │  Features:                                                                   │   │
│  │  ├── User engagement history                                                │   │
│  │  ├── Post recency                                                           │   │
│  │  ├── Creator relationship strength                                          │   │
│  │  ├── Content type preference                                                │   │
│  │  ├── Session context                                                        │   │
│  │  └── Time decay                                                             │   │
│  │                                                                              │   │
│  │  Model: Deep Neural Network (Two-Tower Architecture)                        │   │
│  │  ├── User Tower: User embedding                                             │   │
│  │  └── Post Tower: Post embedding                                             │   │
│  │                                                                              │   │
│  │  Output: Ranked list of ~500 posts                                          │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                     │                                             │
│                                     ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                          DIVERSITY & FRESHNESS                               │   │
│  │                                                                              │   │
│  │  Rules:                                                                      │   │
│  │  ├── No more than 2 consecutive posts from same creator                     │   │
│  │  ├── Mix of content types (photos, videos, reels)                           │   │
│  │  ├── Blend of recent and slightly older content                             │   │
│  │  ├── Include some exploratory content                                        │   │
│  │  └── Insert ads at appropriate intervals                                     │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                     │                                             │
│                                     ▼                                             │
│                            Final Feed (50 posts)                                   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class FeedService:
    def __init__(self):
        self.redis = Redis()
        self.post_db = PostDatabase()
        self.ml_ranker = MLRanker()
        self.graph_db = GraphDatabase()
    
    async def get_feed(self, user_id: str, cursor: str = None, limit: int = 50):
        # Try to get from cache first
        cache_key = f"feed:{user_id}"
        cached_feed = await self.redis.zrevrange(cache_key, 0, limit - 1)
        
        if cached_feed and len(cached_feed) >= limit:
            return self.paginate(cached_feed, cursor, limit)
        
        # Generate feed
        feed = await self.generate_feed(user_id, limit * 10)
        
        # Cache the feed
        await self.cache_feed(user_id, feed)
        
        return self.paginate(feed, cursor, limit)
    
    async def generate_feed(self, user_id: str, candidate_limit: int = 500):
        # Get candidates from multiple sources
        candidates = await asyncio.gather(
            self.get_following_posts(user_id, limit=300),
            self.get_network_posts(user_id, limit=100),
            self.get_explore_posts(user_id, limit=100)
        )
        
        all_candidates = self.merge_candidates(candidates)
        
        # Get user features for ranking
        user_features = await self.get_user_features(user_id)
        
        # Rank using ML model
        ranked_posts = await self.ml_ranker.rank(
            user_features=user_features,
            candidates=all_candidates
        )
        
        # Apply diversity rules
        diverse_feed = self.apply_diversity_rules(ranked_posts)
        
        return diverse_feed
    
    async def get_following_posts(self, user_id: str, limit: int):
        # Get users this person follows
        following = await self.graph_db.get_following(user_id)
        
        # Get recent posts from followed users
        posts = await self.post_db.get_posts_by_users(
            user_ids=following,
            since=datetime.now() - timedelta(days=7),
            limit=limit
        )
        
        return posts
    
    def apply_diversity_rules(self, posts: List[Post]) -> List[Post]:
        diverse_feed = []
        creator_count = {}
        content_type_count = {'photo': 0, 'video': 0, 'reel': 0}
        
        for post in posts:
            # Rule 1: Max 2 consecutive from same creator
            creator_count[post.creator_id] = creator_count.get(post.creator_id, 0) + 1
            if creator_count[post.creator_id] > 2:
                continue
            
            # Rule 2: Balance content types
            content_type_count[post.type] += 1
            
            diverse_feed.append(post)
            
            if len(diverse_feed) >= 500:
                break
        
        return diverse_feed
```

### 3. Story System (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              STORY SYSTEM                                            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Characteristics:                                                                   │
│  - 24-hour ephemeral content                                                        │
│  - Multiple stories per user (ring)                                                 │
│  - View tracking (who viewed)                                                       │
│  - Rich interactions (replies, reactions)                                           │
│                                                                                     │
│  Data Model:                                                                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐               │
│  │  stories table (Cassandra)                                      │               │
│  │  ├── user_id (partition key)                                   │               │
│  │  ├── story_id (clustering key)                                 │               │
│  │  ├── media_url                                                 │               │
│  │  ├── media_type (photo/video)                                  │               │
│  │  ├── created_at                                                │               │
│  │  ├── expires_at (created_at + 24h)                            │               │
│  │  └── metadata (stickers, mentions, etc.)                       │               │
│  └─────────────────────────────────────────────────────────────────┘               │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐               │
│  │  story_views table (Cassandra)                                  │               │
│  │  ├── story_id (partition key)                                  │               │
│  │  ├── viewer_id (clustering key)                                │               │
│  │  ├── viewed_at                                                 │               │
│  │  └── reaction (optional)                                       │               │
│  └─────────────────────────────────────────────────────────────────┘               │
│                                                                                     │
│  Story Tray Generation:                                                             │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐               │
│  │                                                                 │               │
│  │  1. Get list of users I follow                                 │               │
│  │  2. Check which have active stories (last 24h)                 │               │
│  │  3. Rank by:                                                   │               │
│  │     - Recency of stories                                       │               │
│  │     - Relationship strength                                     │               │
│  │     - Unseen stories first                                      │               │
│  │  4. Return ordered tray                                        │               │
│  │                                                                 │               │
│  └─────────────────────────────────────────────────────────────────┘               │
│                                                                                     │
│  TTL-based Cleanup:                                                                 │
│  - Stories automatically expire after 24 hours                                      │
│  - Cassandra TTL handles deletion                                                   │
│  - Media cleaned up via S3 lifecycle policy                                         │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class StoryService:
    def __init__(self):
        self.cassandra = CassandraClient()
        self.redis = Redis()
        self.s3 = S3Client()
        
    async def create_story(self, user_id: str, media: bytes, media_type: str):
        story_id = generate_uuid()
        now = datetime.utcnow()
        expires_at = now + timedelta(hours=24)
        
        # Upload media to S3
        media_key = f"stories/{user_id}/{story_id}.{media_type}"
        await self.s3.put_object(
            Bucket='story-media',
            Key=media_key,
            Body=media,
            Metadata={'expires': expires_at.isoformat()}
        )
        
        # Store story metadata with TTL
        await self.cassandra.execute("""
            INSERT INTO stories (user_id, story_id, media_url, media_type, created_at, expires_at)
            VALUES (%s, %s, %s, %s, %s, %s)
            USING TTL 86400
        """, (user_id, story_id, media_key, media_type, now, expires_at))
        
        # Invalidate story tray cache for followers
        await self.invalidate_follower_story_cache(user_id)
        
        return story_id
    
    async def get_story_tray(self, user_id: str):
        cache_key = f"story_tray:{user_id}"
        
        # Check cache
        cached = await self.redis.get(cache_key)
        if cached:
            return json.loads(cached)
        
        # Get following list
        following = await self.graph_db.get_following(user_id)
        
        # Get active stories for followed users
        stories_by_user = await self.cassandra.execute("""
            SELECT user_id, story_id, created_at 
            FROM stories 
            WHERE user_id IN %s 
            AND created_at > %s
        """, (following, datetime.utcnow() - timedelta(hours=24)))
        
        # Group by user
        tray = {}
        for row in stories_by_user:
            if row.user_id not in tray:
                tray[row.user_id] = {
                    'user_id': row.user_id,
                    'stories': [],
                    'seen': await self.has_seen_all(user_id, row.user_id)
                }
            tray[row.user_id]['stories'].append({
                'story_id': row.story_id,
                'created_at': row.created_at
            })
        
        # Rank users (unseen first, then by recency)
        ranked_tray = sorted(
            tray.values(),
            key=lambda x: (x['seen'], -max(s['created_at'].timestamp() for s in x['stories']))
        )
        
        # Cache for 1 minute
        await self.redis.setex(cache_key, 60, json.dumps(ranked_tray))
        
        return ranked_tray
    
    async def record_view(self, story_id: str, viewer_id: str):
        await self.cassandra.execute("""
            INSERT INTO story_views (story_id, viewer_id, viewed_at)
            VALUES (%s, %s, %s)
            USING TTL 86400
        """, (story_id, viewer_id, datetime.utcnow()))
```

---

## Trade-offs and Tech Choices

### 1. Storage Strategy

| Data Type | Storage | Reason |
|-----------|---------|--------|
| Photos/Videos | S3 | Infinite scale, cost-effective |
| Metadata | PostgreSQL/Cassandra | Structured, indexed queries |
| Feed Cache | Redis | Sub-millisecond reads |
| Social Graph | Neo4j | Efficient graph traversal |
| Search Index | Elasticsearch | Full-text search |

### 2. Image Format Choice

| Format | Pros | Cons | Use Case |
|--------|------|------|----------|
| JPEG | Universal support | Larger size | Legacy support |
| WebP | 25-35% smaller | Not universal | Default for modern clients |
| AVIF | 50% smaller than JPEG | Limited support | Future default |

**Decision**: Serve WebP with JPEG fallback, generate AVIF for newer clients

### 3. Video Encoding

```
┌─────────────────────────────────────────────────────────────────┐
│                    VIDEO ENCODING STRATEGY                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Codec: H.264/AVC (universal) + H.265/HEVC (modern devices)    │
│                                                                 │
│  Resolutions:                                                   │
│  ├── 1080p @ 4 Mbps (WiFi)                                     │
│  ├── 720p @ 2.5 Mbps (4G)                                      │
│  ├── 480p @ 1 Mbps (3G)                                        │
│  └── 360p @ 500 Kbps (Low bandwidth)                           │
│                                                                 │
│  Adaptive Streaming: HLS                                        │
│  - Client selects quality based on bandwidth                    │
│  - Seamless quality switching                                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4. Feed Algorithm Trade-offs

| Approach | Latency | Freshness | Personalization |
|----------|---------|-----------|-----------------|
| Chronological | Fast | Excellent | None |
| Pre-computed + Ranking | Medium | Good | Excellent |
| Real-time Ranking | Slow | Good | Excellent |

**Decision**: Hybrid - Pre-compute candidates, rank on-demand

---

## Failure Scenarios and Bottlenecks

### 1. CDN Failure

```
┌─────────────────────────────────────────────────────────────────┐
│                    CDN FAILOVER STRATEGY                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Multi-CDN Setup:                                               │
│  ├── Primary: CloudFront (AWS)                                  │
│  ├── Secondary: Akamai                                          │
│  └── Tertiary: Fastly                                           │
│                                                                 │
│  Health Checks:                                                  │
│  - Monitor latency and availability                             │
│  - Automatic failover if primary > 500ms or errors > 1%        │
│                                                                 │
│  DNS-based Switching:                                           │
│  - Route 53 health checks                                       │
│  - Weighted routing during degradation                          │
│  - Full failover for complete outage                            │
│                                                                 │
│  Fallback:                                                       │
│  - Direct S3 access with signed URLs                            │
│  - Increased latency but functional                             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Database Overload

```
Problem: Feed generation causing high DB load

Mitigation:
1. Pre-compute feeds for active users
   - Background job updates feeds every 5 minutes
   - Users get near-instant feed loads

2. Caching layers
   - User's following list: 1 hour TTL
   - Recent posts by user: 5 minute TTL
   - Full feed: 1 minute TTL

3. Read replicas
   - Feed queries → Read replicas
   - Write operations → Primary only

4. Rate limiting per user
   - Max 10 feed refreshes per minute
```

### 3. Media Processing Backlog

```
┌─────────────────────────────────────────────────────────────────┐
│                 PROCESSING QUEUE MANAGEMENT                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Problem: Viral event causes 10x upload spike                   │
│                                                                 │
│  Solutions:                                                      │
│                                                                 │
│  1. Auto-scaling workers                                         │
│     - Kubernetes HPA based on queue depth                       │
│     - Scale from 10 → 100 workers in minutes                   │
│                                                                 │
│  2. Priority queues                                              │
│     - High: Paid users, verified accounts                       │
│     - Normal: Regular users                                     │
│     - Low: Retry queue                                          │
│                                                                 │
│  3. Progressive rendering                                        │
│     - Show original image immediately                           │
│     - Replace with processed version when ready                 │
│                                                                 │
│  4. Graceful degradation                                         │
│     - Skip thumbnail generation if backlogged                   │
│     - Process only essential resolutions                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4. Hot Partition (Celebrity Posts)

```
Problem: Celebrity with 100M followers posts, causing hot partition

Solution: Write buffering + async fanout

1. Celebrity posts tweet
2. Store post (instant)
3. Return success to user (instant)
4. Queue fanout operation
5. Fanout service processes in batches
   - 1000 followers per batch
   - 100 batches in parallel
   - Complete fanout in ~15 minutes
6. Followers see post when opening app
   (acceptable delay for non-real-time feed)
```

---

## Future Improvements

### 1. AI-Powered Features

```
┌─────────────────────────────────────────────────────────────────┐
│                    AI ENHANCEMENTS                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Image Understanding:                                           │
│  - Auto-tagging (objects, scenes, people)                       │
│  - Alt-text generation for accessibility                        │
│  - Content categorization                                       │
│                                                                 │
│  Recommendation:                                                 │
│  - Similar content discovery                                    │
│  - User interest modeling                                       │
│  - Creator recommendations                                      │
│                                                                 │
│  Content Moderation:                                            │
│  - Nudity/violence detection                                    │
│  - Hate speech detection                                        │
│  - Misinformation flagging                                      │
│                                                                 │
│  Creative Tools:                                                │
│  - AI filters and effects                                       │
│  - Background removal                                           │
│  - Style transfer                                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Real-time Collaboration

- Live broadcasting
- Co-watching experiences
- Real-time photo editing
- Collaborative stories

### 3. AR/VR Integration

- AR filters and effects
- Virtual try-on
- 3D content support
- VR viewing experience

---

## Interviewer Questions & Answers

### Q1: How would you design the image upload and processing system?

**Answer:**

**Architecture:**
```
Client → API Gateway → Upload Service → S3 (raw) → Kafka → Processing Workers → S3 (processed) → CDN
```

**Flow:**
1. **Pre-signed URL**: Client requests upload URL, gets S3 pre-signed URL
2. **Direct Upload**: Client uploads directly to S3 (bypasses our servers)
3. **Processing Trigger**: S3 event triggers Kafka message
4. **Parallel Processing**: 
   - Resize to multiple resolutions (1080, 640, 320, 150)
   - Convert to WebP
   - Generate blur hash for placeholder
   - Extract EXIF data
   - Run content moderation ML
5. **Update Metadata**: Store processed URLs in database
6. **CDN Distribution**: Files available via CDN

**Why pre-signed URLs?**
- Reduces server bandwidth
- Direct client-to-S3 transfer
- Scales better

**Processing optimizations:**
- Use AWS Lambda for spike handling
- Keep ECS tasks for steady state
- Process thumbnails first (most requested)

---

### Q2: How do you generate the news feed?

**Answer:**

**Hybrid Approach:**

**1. Candidate Generation (Offline + Online):**
```python
candidates = (
    following_posts[:300] +      # Posts from followed users
    network_posts[:100] +        # Friends of friends
    explore_posts[:100] +        # Recommended content
    ads[:50]                     # Sponsored content
)
```

**2. ML Ranking:**
- Two-tower neural network
- User tower: user embedding (interests, history)
- Post tower: post embedding (content, engagement)
- Score = dot_product(user_embedding, post_embedding)

**3. Ranking Factors:**
- Relationship strength (how often you interact)
- Post recency (time decay)
- Engagement prediction (will you like/comment?)
- Content type match (you prefer videos → show more videos)
- Diversity (don't show 5 posts from same person)

**4. Caching Strategy:**
- Pre-compute feed for active users (last 7 days active)
- Cache feed in Redis sorted set
- TTL: 5 minutes
- On cache miss: generate on-demand (slower but acceptable)

**5. Pagination:**
- Cursor-based (not offset)
- Return cursor = last post timestamp
- Next page query: WHERE timestamp < cursor

---

### Q3: How would you handle a celebrity with 100 million followers posting?

**Answer:**

**Problem:** Fan-out on write would require 100M writes

**Solution: Hybrid fan-out**

**For celebrities (>100K followers):**
- NO fan-out on write
- Store post only in celebrity's own timeline
- Followers fetch celebrity posts on read

**For regular users:**
- Fan-out on write (push to all followers)
- Works well for <100K followers

**Timeline read for user following celebrities:**
```python
def get_feed(user_id):
    # Get pre-computed feed (from regular followed users)
    feed = cache.get(f"feed:{user_id}")
    
    # Get list of celebrities user follows
    celebrities = get_followed_celebrities(user_id)
    
    # Fetch recent posts from each celebrity
    for celeb_id in celebrities:
        celeb_posts = db.query("""
            SELECT * FROM posts 
            WHERE user_id = %s 
            AND created_at > NOW() - INTERVAL 7 DAY
            ORDER BY created_at DESC 
            LIMIT 10
        """, celeb_id)
        feed.extend(celeb_posts)
    
    # Merge and rank
    return rank_and_sort(feed)
```

**Trade-off:**
- Slightly slower feed generation for users following celebrities
- But avoids 100M writes on celebrity post

---

### Q4: How do you store and serve stories (ephemeral content)?

**Answer:**

**Storage Design:**
```sql
-- Cassandra with TTL
CREATE TABLE stories (
    user_id UUID,
    story_id TIMEUUID,
    media_url TEXT,
    created_at TIMESTAMP,
    PRIMARY KEY ((user_id), story_id)
) WITH default_time_to_live = 86400;  -- 24 hours
```

**Why Cassandra?**
- Native TTL support (automatic deletion)
- High write throughput for story creation
- Partition by user (all stories together)

**Story Tray Generation:**
```python
def get_story_tray(user_id):
    # Get users I follow
    following = graph_db.get_following(user_id)
    
    # Get users with active stories
    users_with_stories = cassandra.query("""
        SELECT DISTINCT user_id FROM stories
        WHERE user_id IN :following
        AND created_at > :cutoff
    """, following=following, cutoff=now() - 24h)
    
    # Get seen status for each
    seen_map = get_seen_status(user_id, users_with_stories)
    
    # Rank: unseen first, then by recency
    return sorted(users_with_stories, key=lambda u: (seen_map[u], -recency[u]))
```

**View Tracking:**
- Store views in separate table
- Also TTL = 24h (expires with story)
- Used for "who viewed your story" feature

---

### Q5: How do you handle search functionality?

**Answer:**

**Architecture:**
```
User types → API → Search Service → Elasticsearch → Return results
```

**Index Design:**
```json
{
  "users": {
    "username": "text",
    "display_name": "text",
    "bio": "text",
    "follower_count": "integer",
    "is_verified": "boolean"
  },
  "posts": {
    "caption": "text",
    "hashtags": "keyword",
    "location": "geo_point",
    "created_at": "date"
  },
  "hashtags": {
    "tag": "keyword",
    "post_count": "integer"
  }
}
```

**Search Types:**

1. **Top Results** (default):
   - Blend of users, hashtags, places
   - Ranked by relevance + popularity

2. **Accounts**:
   - Fuzzy match on username/display_name
   - Boost verified accounts
   - Boost accounts you follow

3. **Tags**:
   - Exact prefix match
   - Sort by post count

4. **Places**:
   - Geo-distance sort
   - Filter by current location

**Autocomplete:**
- Use Elasticsearch `completion` suggester
- Sub-50ms latency requirement
- Show results as user types

---

### Q6: How do you ensure high availability?

**Answer:**

**Multi-layer redundancy:**

**1. Application Layer:**
- Kubernetes with multiple replicas (min 3)
- Auto-scaling based on CPU/memory
- Rolling deployments (zero downtime)
- Multiple availability zones

**2. Database Layer:**
```
PostgreSQL:
- Primary + 2 read replicas
- Cross-region standby
- Automatic failover (Patroni/RDS)

Cassandra:
- 3+ nodes per datacenter
- RF = 3 (survive 2 node failures)
- Multi-datacenter deployment
```

**3. Cache Layer:**
```
Redis Cluster:
- 6 shards with replicas
- Automatic failover
- Cross-region replication for sessions
```

**4. CDN Layer:**
- Multi-CDN strategy
- Automatic failover
- Origin shielding

**5. Storage Layer:**
```
S3:
- 99.999999999% durability
- Cross-region replication
- Versioning enabled
```

**SLA Target:** 99.99% (52 minutes downtime/year)

---

### Q7: How would you implement direct messaging?

**Answer:**

**Data Model:**
```sql
-- Conversations
CREATE TABLE conversations (
    conversation_id UUID PRIMARY KEY,
    participants UUID[],
    created_at TIMESTAMP,
    last_message_at TIMESTAMP
);

-- Messages (Cassandra for scalability)
CREATE TABLE messages (
    conversation_id UUID,
    message_id TIMEUUID,
    sender_id UUID,
    content TEXT,            -- Encrypted
    media_url TEXT,
    created_at TIMESTAMP,
    PRIMARY KEY ((conversation_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```

**Real-time Delivery:**
```
┌────────────────────────────────────────────────────────────────┐
│                                                                │
│  User A sends message:                                         │
│  1. API receives message                                       │
│  2. Store in Cassandra                                         │
│  3. Publish to Kafka (messages topic)                          │
│  4. WebSocket service consumes                                 │
│  5. Find User B's connection                                   │
│  6. Push message via WebSocket                                 │
│                                                                │
│  If User B offline:                                            │
│  - Store in unread queue                                       │
│  - Send push notification                                      │
│  - Deliver when they connect                                   │
│                                                                │
└────────────────────────────────────────────────────────────────┘
```

**End-to-End Encryption:**
- Signal Protocol
- Keys exchanged on first message
- Server cannot read messages
- Per-device keys for multi-device

---

### Q8: How do you handle content moderation at scale?

**Answer:**

**Pipeline:**
```
┌─────────────────────────────────────────────────────────────────┐
│                   CONTENT MODERATION PIPELINE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Upload → Pre-check → ML Classification → Human Review (if needed)
│                                                                 │
│  Pre-check (instant):                                           │
│  - Hash matching (known bad content)                            │
│  - Account age/reputation check                                 │
│  - Rate limiting                                                │
│                                                                 │
│  ML Classification:                                             │
│  - Nudity detection (NSFW classifier)                          │
│  - Violence detection                                           │
│  - Hate symbols recognition                                     │
│  - Text toxicity (caption/comments)                            │
│                                                                 │
│  Classification Results:                                        │
│  - Safe (>95% confidence) → Publish immediately                │
│  - Suspicious (70-95%) → Publish + queue for review            │
│  - Likely violating (<70%) → Hold for human review             │
│  - Definitely violating → Block + notify user                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
async def moderate_content(post):
    # Pre-check
    if is_known_bad_hash(post.media_hash):
        return ContentDecision.BLOCK
    
    # ML classification
    ml_results = await asyncio.gather(
        nsfw_classifier.predict(post.media),
        violence_classifier.predict(post.media),
        text_toxicity.predict(post.caption)
    )
    
    nsfw_score, violence_score, toxicity_score = ml_results
    
    if max(nsfw_score, violence_score, toxicity_score) > 0.95:
        return ContentDecision.BLOCK
    elif max(scores) > 0.70:
        queue_for_review(post)
        return ContentDecision.PUBLISH_PENDING_REVIEW
    else:
        return ContentDecision.PUBLISH
```

**Scale:**
- 100M posts/day
- ML processing: <500ms per post
- Human review: ~1M posts/day requiring review
- 5000+ human moderators globally

---

### Q9: How would you design the explore/discover page?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                    EXPLORE PAGE SYSTEM                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Candidate Sources:                                             │
│  1. Trending posts (high engagement in last few hours)          │
│  2. Similar to liked content (content-based filtering)          │
│  3. Popular in your network (collaborative filtering)           │
│  4. Diverse content (serendipity)                              │
│                                                                 │
│  Personalization Pipeline:                                      │
│                                                                 │
│  ┌─────────────┐     ┌─────────────┐     ┌─────────────┐       │
│  │  Interest   │────>│  Candidate  │────>│   Ranking   │       │
│  │  Modeling   │     │ Generation  │     │   (ML)      │       │
│  └─────────────┘     └─────────────┘     └─────────────┘       │
│                                                                 │
│  Interest Modeling:                                             │
│  - Topics user engages with                                     │
│  - Content types preferred                                      │
│  - Time-of-day patterns                                         │
│  - Explicit preferences (topics followed)                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Ranking Model:**
```python
features = {
    'user_interest_score': dot_product(user_embedding, post_embedding),
    'post_quality_score': engagement_rate * author_trust,
    'freshness_score': time_decay(post.created_at),
    'diversity_score': distance_from_recent_shown,
    'virality_score': engagement_velocity
}

final_score = model.predict(features)
```

**Caching:**
- Pre-compute explore feed for all users (expensive)
- Cache top 1000 trending posts globally
- Personalize in real-time using cached components

---

### Q10: How do you handle the like count for viral posts?

**Answer:**

**Problem:** Viral post gets 1M likes/minute → DB bottleneck

**Solution: Counter aggregation**

```
┌─────────────────────────────────────────────────────────────────┐
│                  LIKE COUNTER ARCHITECTURE                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Like Action Flow:                                              │
│                                                                 │
│  1. User clicks like                                            │
│  2. Write to likes table (for relationship)                     │
│  3. Increment Redis counter (atomic INCR)                       │
│  4. Return immediately                                          │
│                                                                 │
│  Background Sync:                                               │
│  - Every 30 seconds: flush Redis counters to DB                 │
│  - Batch update: UPDATE posts SET likes = likes + X WHERE id = Y │
│                                                                 │
│  Display:                                                        │
│  - Read from Redis (fast)                                       │
│  - Fallback to DB on cache miss                                 │
│  - Show approximate count for very large numbers               │
│    (e.g., "1.2M likes" instead of "1,234,567 likes")           │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
async def like_post(user_id, post_id):
    # Record the like relationship
    await db.execute("""
        INSERT INTO likes (user_id, post_id, created_at)
        VALUES (%s, %s, %s)
        ON CONFLICT DO NOTHING
    """, user_id, post_id, datetime.now())
    
    # Increment counter in Redis
    await redis.incr(f"likes:{post_id}")
    
    # Queue for notifications
    await kafka.send("engagement", {
        "type": "like",
        "post_id": post_id,
        "liker_id": user_id
    })

# Background job (runs every 30 seconds)
async def sync_like_counts():
    keys = await redis.keys("likes:*")
    for key in keys:
        post_id = key.split(":")[1]
        count = await redis.getset(key, 0)  # Get and reset
        if count > 0:
            await db.execute("""
                UPDATE posts SET like_count = like_count + %s
                WHERE post_id = %s
            """, count, post_id)
```

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                 INSTAGRAM - FINAL ARCHITECTURE                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 2B MAU, 500M DAU, 100M photos/day                      │
│                                                                 │
│  Core Components:                                               │
│  ├── CDN (CloudFront/Akamai) - 750 GB/s outbound               │
│  ├── Load Balancers - L4/L7                                    │
│  ├── API Gateway - Auth, rate limiting                         │
│  ├── Microservices - User, Post, Feed, Story, DM, Search       │
│  ├── Media Processing - Lambda + ECS workers                    │
│  └── ML Systems - Feed ranking, content moderation              │
│                                                                 │
│  Data Stores:                                                   │
│  ├── PostgreSQL - User data, relationships                     │
│  ├── Cassandra - Posts, stories, messages (write-heavy)        │
│  ├── Elasticsearch - Search index                              │
│  ├── Redis Cluster - 6TB cache                                 │
│  ├── Neo4j - Social graph                                      │
│  └── S3 - 5+ EB media storage                                  │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── Pre-signed URLs for direct S3 upload                      │
│  ├── Hybrid fan-out (push for regular, pull for celebrities)   │
│  ├── ML-based feed ranking                                     │
│  ├── TTL-based story expiration                                │
│  └── Redis counters for high-velocity metrics                  │
│                                                                 │
│  SLA: 99.99% availability, <500ms feed load                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
