# Social Media Platform (Facebook/Twitter) - System Design Interview

## Problem Statement
*"Design a social media platform like Facebook or Twitter that supports billions of users, real-time feeds, messaging, media sharing, and handles viral content with global distribution."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What core features do we need?" → User profiles, posts, news feed, following/friends, likes/comments
- "Should we support real-time messaging?" → Yes, direct messages and group chats
- "Do we need media support?" → Yes, photos, videos, stories, live streaming
- "What about notifications?" → Yes, real-time push notifications for interactions
- "Should we have content discovery?" → Yes, trending topics, hashtags, search
- "Do we need content moderation?" → Yes, spam detection, inappropriate content filtering

**Non-Functional Requirements:**
- "What's our user scale?" → 3B+ users globally, 1.5B daily active users
- "Expected post volume?" → 500M posts/day, 10B interactions/day (likes, comments, shares)
- "Real-time requirements?" → <100ms for feed loading, <1s for new post delivery
- "Storage needs?" → Massive media storage, 10+ years of content history
- "Global availability?" → 99.99% uptime, multi-region deployment
- "Consistency requirements?" → Eventually consistent feeds, strong consistency for payments

### Requirements Summary:
- **Scale**: 3B users, 1.5B DAU, 500M posts/day, 10B interactions/day
- **Features**: Profiles, feeds, messaging, media sharing, notifications, search
- **Performance**: <100ms feed load, real-time notifications, viral content handling
- **Global**: Multi-region with localized content and compliance
- **Media**: Photos, videos, stories, live streaming with CDN distribution

---

## Phase 2: Capacity Estimation (5 minutes)

### User & Traffic Estimation:
```
Total users: 3B registered users
Daily active users: 1.5B (50% of total)
Concurrent users during peak: 200M
Average session duration: 30 minutes
Posts per user per day: 1 post (heavy users: 10, light users: 0.1)
Feed refreshes per user per day: 20
```

### Content Volume:
```
Daily posts: 500M posts
Daily media uploads: 2B photos, 100M videos
Daily interactions: 10B (likes, comments, shares)
Daily messages: 50B direct messages
Storage per post: 2KB text + metadata
Storage per photo: 1MB average
Storage per video: 50MB average
```

### Storage Estimation:
```
User profiles: 3B × 2KB = 6TB
Posts (text): 500M/day × 2KB × 365 × 5 = 1.8TB/year
Photos: 2B/day × 1MB × 365 × 5 = 3.6PB/year
Videos: 100M/day × 50MB × 365 × 5 = 9.1PB/year
Social graph: 3B users × 500 connections × 100B = 150TB
Total: ~13PB/year (with replication ~40PB/year)
```

### Bandwidth Requirements:
```
Peak read requests: 200M users × 20 refreshes / 86,400s = 46K RPS
Peak media bandwidth: 200M users × 10MB/session / 1800s = 1TB/s
Video streaming: 50M concurrent streams × 2Mbps = 100Tbps
Global CDN bandwidth: 500Tbps total capacity needed
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Mobile Apps  │  │Web Client   │  │Desktop Apps │
│- iOS/Android│  │- React PWA  │  │- Electron   │
│- Native UI  │  │- Real-time  │  │- Native     │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│                Global CDN                       │
│- Static assets  - Media content  - Edge caching│
└─────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│            Global Load Balancer                 │
│- Geographic routing  - DDoS protection         │
└─────────────────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│   Region 1   │ │   Region 2   │ │   Region 3   │
│   (US-West)  │ │   (EU-West)  │ │  (AP-South)  │
└──────────────┘ └──────────────┘ └──────────────┘
        │
        ▼
┌─────────────────────────────────────────────────┐
│              Regional API Gateway               │
│- Authentication  - Rate limiting  - Routing    │
└─────────────────────────────────────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│User        │ │Feed        │ │Post        │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Profile   │ │- Timeline  │ │- Create    │
│- Auth      │ │- Algorithm │ │- Media     │
│- Friends   │ │- Ranking   │ │- Moderate  │
└────────────┘ └────────────┘ └────────────┘
    │                   │                   │
    └───────────────────┼───────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Social      │ │Messaging   │ │Notification│
│Graph       │ │Service     │ │Service     │
│            │ │            │ │            │
│- Follow    │ │- DM/Chat   │ │- Push      │
│- Friends   │ │- Groups    │ │- Real-time │
│- Recommend │ │- Calls     │ │- Email     │
└────────────┘ └────────────┘ └────────────┘
    │                   │                   │
    └───────────────────┼───────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Media       │ │Search      │ │Analytics   │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Upload    │ │- Posts     │ │- Metrics   │
│- Process   │ │- Users     │ │- ML Insights│
│- Stream    │ │- Trending  │ │- Ads       │
└────────────┘ └────────────┘ └────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│          Machine Learning Platform              │
│                                                 │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────┐   │
│ │Feed     │  │Content  │  │Friend   │  │Ad   │   │
│ │Ranking  │  │Moderation│ │Suggest  │  │Target│  │
│ │         │  │         │  │         │  │     │   │
│ │- Neural │  │- Image  │  │- Graph  │  │-CTR │   │
│ │  Net    │  │- Text   │  │- ML     │  │-Rev │   │
│ │- Collab │  │- Video  │  │- Social │  │-Opt │   │
│ └─────────┘  └─────────┘  └─────────┘  └─────┘   │
└─────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│                 Data Layer                      │
│                                                 │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐  ┌─────┐   │
│ │MySQL    │  │Cassandra│  │  Redis  │  │Kafka│   │
│ │         │  │         │  │         │  │     │   │
│ │- Users  │  │- Posts  │  │- Cache  │  │-Events│ │
│ │- Graph  │  │- Messages│ │- Sessions│ │-Logs│   │
│ │- Config │  │- Analytics│ │- Feed   │  │-ML  │   │
│ └─────────┘  └─────────┘  └─────────┘  └─────┘   │
└─────────────────────────────────────────────────┘
```

### Key Components:
- **Feed Service**: Personalized timeline generation and ranking
- **Social Graph Service**: Friend connections and recommendations  
- **Post Service**: Content creation, media processing, moderation
- **Messaging Service**: Real-time chat and group communications
- **ML Platform**: Feed ranking, content moderation, ad targeting

---

## Phase 4: Database Design (8 minutes)

### Database Strategy:
- **MySQL**: Users, social graph, configuration (ACID compliance needed)
- **Cassandra**: Posts, messages, analytics (massive write volume)
- **Redis**: Caching, sessions, real-time features
- **S3/Blob Storage**: Media files with CDN distribution

### MySQL Schema (Core Social Data):

```sql
-- Users table
CREATE TABLE users (
    user_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    phone VARCHAR(20) UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    
    -- Profile information
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    bio TEXT,
    profile_picture_url VARCHAR(500),
    cover_photo_url VARCHAR(500),
    
    -- Account status
    is_verified BOOLEAN DEFAULT false,
    account_status VARCHAR(20) DEFAULT 'active', -- active, suspended, deleted
    privacy_level VARCHAR(20) DEFAULT 'public',  -- public, friends, private
    
    -- Metrics
    followers_count INT DEFAULT 0,
    following_count INT DEFAULT 0,
    posts_count INT DEFAULT 0,
    
    -- Timestamps
    created_at TIMESTAMP DEFAULT NOW(),
    last_active_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_users_username (username),
    INDEX idx_users_email (email),
    INDEX idx_users_last_active (last_active_at)
);

-- Social connections (friendships/follows)
CREATE TABLE user_connections (
    connection_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,              -- User who follows
    following_id BIGINT NOT NULL,             -- User being followed
    
    connection_type VARCHAR(20) DEFAULT 'follow', -- follow, friend, block
    connection_status VARCHAR(20) DEFAULT 'active', -- active, pending, blocked
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    UNIQUE KEY unique_connection (follower_id, following_id),
    INDEX idx_follower (follower_id, connection_type, connection_status),
    INDEX idx_following (following_id, connection_type, connection_status)
);

-- User interests and preferences (for ML)
CREATE TABLE user_interests (
    user_id BIGINT,
    interest_category VARCHAR(50),            -- technology, sports, music, etc.
    interest_weight DECIMAL(3,2) DEFAULT 1.0, -- 0.0 to 1.0 preference strength
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    PRIMARY KEY (user_id, interest_category),
    INDEX idx_category_weight (interest_category, interest_weight DESC)
);
```

### Cassandra Schema (High-Volume Data):

```sql
-- Posts table (partitioned by user_id for user timeline queries)
CREATE TABLE posts (
    user_id BIGINT,                          -- Partition key
    post_id UUID,                            -- Clustering key
    
    -- Post content
    content TEXT,
    media_urls LIST<TEXT>,                   -- Photo/video URLs
    post_type VARCHAR(20) DEFAULT 'text',    -- text, photo, video, story
    
    -- Engagement metrics
    likes_count COUNTER DEFAULT 0,
    comments_count COUNTER DEFAULT 0,
    shares_count COUNTER DEFAULT 0,
    views_count COUNTER DEFAULT 0,
    
    -- Metadata
    hashtags SET<TEXT>,
    mentions SET<BIGINT>,                    -- User IDs mentioned
    location TEXT,
    
    -- Configuration
    visibility VARCHAR(20) DEFAULT 'public', -- public, friends, private
    comments_enabled BOOLEAN DEFAULT true,
    
    -- Timestamps
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    PRIMARY KEY (user_id, created_at, post_id)
) WITH CLUSTERING ORDER BY (created_at DESC);

-- Global posts feed (for discovery and trending)
CREATE TABLE global_posts (
    post_date DATE,                          -- Partition key (YYYY-MM-DD)
    post_id UUID,                            -- Clustering key
    user_id BIGINT,
    
    content TEXT,
    media_urls LIST<TEXT>,
    hashtags SET<TEXT>,
    
    -- Engagement for ranking
    engagement_score DOUBLE,                 -- Calculated engagement metric
    created_at TIMESTAMP,
    
    PRIMARY KEY (post_date, engagement_score, post_id)
) WITH CLUSTERING ORDER BY (engagement_score DESC, post_id DESC);

-- User feed cache (precomputed personalized feeds)
CREATE TABLE user_feeds (
    user_id BIGINT,                          -- Partition key
    feed_rank BIGINT,                        -- Clustering key (for ordering)
    post_id UUID,
    
    -- Post preview for fast loading
    author_id BIGINT,
    author_username TEXT,
    author_profile_pic TEXT,
    content_preview TEXT,                    -- First 200 chars
    media_thumbnail TEXT,
    
    -- Ranking factors
    relevance_score DOUBLE,                  -- ML-computed relevance
    interaction_probability DOUBLE,          -- Predicted engagement
    
    -- Metadata
    post_created_at TIMESTAMP,
    added_to_feed_at TIMESTAMP,
    
    PRIMARY KEY (user_id, feed_rank)
) WITH CLUSTERING ORDER BY (feed_rank ASC);

-- Direct messages
CREATE TABLE messages (
    conversation_id UUID,                    -- Partition key
    message_id UUID,                         -- Clustering key
    
    sender_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    
    -- Message content
    message_text TEXT,
    media_urls LIST<TEXT>,
    message_type VARCHAR(20) DEFAULT 'text', -- text, photo, video, emoji
    
    -- Message status
    is_read BOOLEAN DEFAULT false,
    is_deleted BOOLEAN DEFAULT false,
    read_at TIMESTAMP,
    
    created_at TIMESTAMP,
    
    PRIMARY KEY (conversation_id, created_at, message_id)
) WITH CLUSTERING ORDER BY (created_at DESC);

-- Real-time analytics (time-series data)
CREATE TABLE post_analytics (
    post_id UUID,
    analytics_date DATE,                     -- Partition key
    hour_bucket INT,                         -- 0-23 for hourly aggregation
    
    -- Engagement metrics per hour
    hourly_likes COUNTER,
    hourly_comments COUNTER,
    hourly_shares COUNTER,
    hourly_views COUNTER,
    
    -- Geographic breakdown
    country_stats MAP<TEXT, INT>,            -- Country -> engagement count
    
    PRIMARY KEY (post_id, analytics_date, hour_bucket)
) WITH CLUSTERING ORDER BY (analytics_date DESC, hour_bucket DESC);
```

### Redis Cache Strategy:

```javascript
// User session and authentication
"session:{session_id}": {
  "user_id": 123456,
  "username": "john_doe",
  "last_activity": 1704110400,
  "permissions": ["read", "write", "admin"],
  "ttl": 86400                             // 24 hours
}

// Hot user feed cache (pre-computed timeline)
"feed:{user_id}": [
  {
    "post_id": "uuid-1",
    "author_id": 789,
    "content_preview": "Just visited the most amazing...",
    "media_thumbnail": "https://cdn.social.com/thumb/123.jpg",
    "created_at": 1704110400,
    "relevance_score": 0.95
  },
  // ... top 50 posts in feed
]

// Post engagement cache (for real-time updates)
"post_stats:{post_id}": {
  "likes_count": 1250,
  "comments_count": 89,
  "shares_count": 34,
  "views_count": 12500,
  "ttl": 300                               // 5 minutes
}

// User online status and presence
"presence:{user_id}": {
  "status": "online",                      // online, away, offline
  "last_seen": 1704110400,
  "active_device": "mobile",
  "ttl": 900                               // 15 minutes
}

// Real-time notifications queue
"notifications:{user_id}": [
  {
    "type": "like",
    "actor_id": 456,
    "actor_name": "Jane Smith",
    "post_id": "uuid-1",
    "created_at": 1704110400
  },
  // ... recent notifications
]

// Trending hashtags and topics
"trending:global": [
  {"hashtag": "#WorldCup", "mentions_24h": 2500000},
  {"hashtag": "#TechNews", "mentions_24h": 890000},
  {"hashtag": "#MondayMotivation", "mentions_24h": 650000}
]

// Friend suggestions cache
"friend_suggestions:{user_id}": [
  {
    "suggested_user_id": 789,
    "mutual_friends": 5,
    "suggestion_score": 0.87,
    "reason": "mutual_connections"
  },
  // ... top 20 suggestions
]

// Rate limiting per user
"rate_limit:posts:{user_id}": {
  "posts_last_hour": 8,
  "posts_last_day": 45,
  "ttl": 3600
}

// Popular content cache (for discovery)
"popular:posts:24h": [
  {"post_id": "uuid-1", "engagement_score": 95000},
  {"post_id": "uuid-2", "engagement_score": 87000}
]
```

### Design Decisions:
- **Multi-database approach**: Leverage strengths of each database type
- **Denormalized feeds**: Pre-compute user timelines for fast loading
- **Time-series partitioning**: Efficient analytics and message storage
- **Counter columns**: Real-time engagement metrics without read-modify-write
- **Cache-heavy architecture**: Redis for all real-time features

---

## Phase 5: Critical Flow - News Feed Generation (8 minutes)

### Most Critical Flow: Personalized Feed Generation

**1. Feed Request**
```
User opens app → GET /api/feed?limit=20&offset=0
Headers: Authorization: Bearer {jwt_token}
```

**2. Cache-First Feed Serving**
```
Feed Service processing:
1. Extract user_id from JWT token
2. Check Redis cache for pre-computed feed
3. If cache hit (90% case):
   - Return cached feed posts
   - Async: trigger feed refresh if cache is >30 min old
4. If cache miss (10% case):
   - Generate feed using ML ranking service
   - Cache result and return
```

**3. Real-Time Feed Generation (Cache Miss)**
```
ML-Powered Feed Assembly:
1. Fetch user's social connections (following list)
2. Get recent posts from followed users (last 7 days)
3. Fetch user's interest profile and engagement history
4. Run ML ranking algorithm:
   - Content relevance score (user interests vs post topics)
   - Social signals (likes, comments from mutual friends)
   - Recency factor (newer posts get higher priority)
   - Diversity factor (mix of content types and authors)
5. Score and rank ~1000 candidate posts
6. Return top 50 posts with metadata
```

**4. Feed Personalization Algorithm**
```
ML Ranking Service:
For each candidate post, calculate score:

relevance_score = 0.4 * content_similarity + 
                 0.3 * social_proof + 
                 0.2 * recency_factor + 
                 0.1 * diversity_bonus

Where:
- content_similarity: User interest alignment (0-1)
- social_proof: Engagement from user's network (0-1) 
- recency_factor: Time decay function (0-1)
- diversity_bonus: Content type and author variety (0-0.2)

Final ranking: Sort by relevance_score DESC
```

**5. Feed Delivery & Client Rendering**
```
Client receives feed response:
1. Parse JSON feed data
2. Render post cards with lazy loading
3. Preload media thumbnails for visible posts
4. Set up infinite scroll for pagination
5. Cache media assets locally
6. Track view events for analytics
```

### Critical Flow: Post Creation & Distribution

**1. Post Creation**
```
User creates post → POST /api/posts
{
  "content": "Beautiful sunset at the beach! #sunset #photography",
  "media_files": ["image1.jpg", "image2.jpg"],
  "visibility": "public",
  "location": "Malibu Beach, CA"
}
```

**2. Content Processing Pipeline**
```
Post Service processing:
1. Content validation and sanitization
2. Media upload to CDN (if present):
   - Image processing: Multiple resolutions, compression
   - Video processing: Encoding, thumbnail generation
3. Extract hashtags and mentions
4. Content moderation check (AI + human review queue)
5. Store post in Cassandra (user timeline)
6. Add to global posts index for discovery
```

**3. Fan-out Strategy (Push vs Pull)**
```
Feed Distribution Service:
1. Check user's follower count
2. If followers < 10K (celebrity threshold):
   - Push model: Add post to each follower's feed cache
   - Async fanout via message queue
3. If followers > 10K:
   - Pull model: Store post in author's timeline
   - Followers fetch during feed generation
4. Hybrid: Push to active followers, pull for inactive
```

**4. Real-Time Notifications**
```
Notification Service:
1. Identify users to notify (followers, mentioned users)
2. Generate notification events
3. Send push notifications via mobile push services
4. Update notification counts in Redis
5. Store notification history in database
```

### Technical Challenges:

**Feed Generation Performance:**
- "Sub-100ms feed loading with ML ranking"
- "Handling viral posts with millions of interactions"
- "Personalization without privacy compromise"

**Data Consistency:**
- "Eventually consistent feeds vs real-time accuracy"
- "Handling post deletions across distributed caches"
- "Race conditions in engagement counting"

**Scale & Hot Spotting:**
- "Celebrity users with 100M+ followers"
- "Viral content causing traffic spikes"
- "Geographic load distribution"

**Real-Time Features:**
- "Live engagement updates (likes, comments)"
- "Instant messaging with delivery guarantees"
- "Presence indicators and typing status"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Feed generation**: ML ranking for millions of users simultaneously
2. **Celebrity fanout**: Users with 100M+ followers posting content
3. **Media processing**: Video encoding and global CDN distribution
4. **Database writes**: 10B+ daily interactions and analytics
5. **Real-time features**: WebSocket connections for 200M concurrent users

### Scaling Solutions:

**Feed & Ranking Optimization:**
```
- Pre-compute feeds for active users during low-traffic hours
- Approximate algorithms: Use sampling for large follower lists
- ML model optimization: Quantized models, GPU acceleration
- Regional feed generation: Distribute ML workload geographically
```

**Celebrity User Handling:**
```
- Hybrid fanout: Push for small audiences, pull for celebrities
- Follower sampling: Notify sample of followers, not all
- Dedicated infrastructure: Separate queues for high-follower users
- Rate limiting: Prevent spam from verified accounts
```

**Database & Storage Scaling:**
```
- Database sharding: Partition by user_id hash
- Read replicas: Separate analytical and operational workloads
- Time-based partitioning: Archive old posts to cold storage
- Cassandra tuning: Optimized for write-heavy workloads
```

**Media & CDN Optimization:**
```
- Multi-CDN strategy: Geographic distribution, failover
- Adaptive streaming: Video quality based on connection speed
- Image optimization: WebP format, progressive loading
- Edge computing: Process media closer to users
```

**Real-Time Infrastructure:**
```
- WebSocket clustering: Distribute connections across servers
- Message queues: Kafka for reliable event processing
- Push notification batching: Reduce mobile battery impact
- Connection pooling: Efficient resource utilization
```

### Trade-offs:
- **Personalization vs Performance**: Complex ML vs simple chronological feeds
- **Consistency vs Availability**: Real-time updates vs system stability
- **Features vs Simplicity**: Rich functionality vs maintainable codebase
- **Privacy vs Engagement**: User data protection vs recommendation accuracy

---

## Advanced Features:

**AI & Machine Learning:**
- Advanced content moderation (image, video, text analysis)
- Deepfake detection for video content
- Personalized ad targeting with privacy preservation
- Automated content translation for global audience

**Content & Safety:**
- Real-time content filtering and fact-checking
- Community guidelines enforcement
- Harassment detection and prevention
- Age-appropriate content filtering

**Business Intelligence:**
- Creator monetization tools and analytics
- A/B testing platform for feature rollouts  
- Business account features and advertising API
- Advanced analytics dashboard for content creators

**Emerging Features:**
- AR/VR content support and viewing
- Live audio/video streaming with interactive features
- NFT and cryptocurrency integration
- Metaverse and virtual world connectivity

---

## Success Metrics:
- **Feed Load Time**: <100ms for 95% of requests globally
- **User Engagement**: >30 minutes average session time
- **Content Delivery**: <200ms for media loading worldwide
- **Real-time Messaging**: <500ms message delivery
- **System Availability**: 99.99% uptime during peak usage
- **User Growth**: Support 10% monthly active user growth
- **Content Moderation**: <1% inappropriate content visible to users

**🎯 This design demonstrates massive-scale social platforms, real-time systems, ML-driven personalization, global content distribution, and building systems that connect billions of people worldwide while maintaining performance and safety.**
