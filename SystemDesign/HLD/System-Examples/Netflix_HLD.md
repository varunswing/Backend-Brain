# Netflix - High Level Design

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

1. **Content Catalog**
   - Browse movies and TV shows
   - Search by title, genre, actor
   - View content details and trailers

2. **Video Streaming**
   - Stream video content
   - Adaptive bitrate streaming
   - Resume playback from where left off
   - Support multiple devices

3. **User Management**
   - User registration and authentication
   - Multiple profiles per account
   - Viewing history
   - Watchlist management

4. **Recommendations**
   - Personalized content suggestions
   - "Because you watched" recommendations
   - Trending content
   - Top 10 lists

5. **Subscription & Billing**
   - Multiple subscription tiers
   - Payment processing
   - Download for offline viewing

### Non-Functional Requirements

1. **Scale**: 200M+ subscribers, 190+ countries
2. **Availability**: 99.99% uptime
3. **Latency**: Video start < 2 seconds
4. **Bandwidth**: Support variable network conditions
5. **Consistency**: Eventual consistency for viewing data
6. **Quality**: Highest possible quality for bandwidth

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Total subscribers: 200 Million
- Peak concurrent streams: 10 Million
- Average watch time: 2 hours/day per active user
- Daily Active Users: 100 Million

Streaming:
- Peak bandwidth: 15+ Tbps globally
- Average video bitrate: 5 Mbps
- Peak concurrent: 10M * 5 Mbps = 50 Tbps

API Requests:
- Browse/search: 100M requests/day
- Playback requests: 500M/day
- Recommendation updates: 200M/day
```

### Storage Estimates

```
Video Content:
- Total titles: 15,000+
- Average movie: ~10 GB (multiple quality levels)
- Average TV episode: ~3 GB (multiple quality levels)
- Total content storage: ~10 PB (before replication)

Encoding Formats:
- Per title: 10-20 different quality/resolution combinations
- Total encoded content: ~100 PB

User Data:
- Viewing history: 500 bytes/user * 200M = 100 GB
- Profiles/preferences: 1 KB/user * 200M = 200 GB
- Watch progress: 100 bytes/item * 1B items = 100 GB
```

### Bandwidth Estimates

```
Peak Streaming:
- 10M concurrent streams
- Average 5 Mbps per stream
- Total: 50 Tbps

Daily Transfer:
- 100M users * 2 hours * 5 Mbps * 3600 sec / 8
- = 450 PB/day transfer
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                            │
│                    (Smart TV, Mobile, Web, Gaming Consoles)                         │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          OPEN CONNECT CDN (Netflix's CDN)                            │
│                                                                                     │
│  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐          │
│  │   ISP PoP   │    │   ISP PoP   │    │   ISP PoP   │    │   ISP PoP   │          │
│  │   Cache     │    │   Cache     │    │   Cache     │    │   Cache     │          │
│  │  (10,000+)  │    │             │    │             │    │             │          │
│  └─────────────┘    └─────────────┘    └─────────────┘    └─────────────┘          │
│                                                                                     │
│  Video Content Delivery (95%+ traffic)                                              │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                              ┌──────────┴──────────┐
                              │                     │
                              ▼                     ▼
┌─────────────────────────────────────┐   ┌───────────────────────────────────────────┐
│         AWS REGION (Control Plane)   │   │        VIDEO STREAMING                    │
│                                      │   │                                           │
│  ┌────────────────────────────────┐ │   │  Client → CDN PoP → Stream video         │
│  │       API GATEWAY              │ │   │                                           │
│  │  (Rate Limiting, Auth, Route)  │ │   │  (Video content served directly           │
│  └────────────┬───────────────────┘ │   │   from Open Connect CDN)                  │
│               │                      │   │                                           │
│   ┌───────────┴───────────┐         │   └───────────────────────────────────────────┘
│   │                       │         │
│   ▼                       ▼         │
│ ┌─────────┐          ┌─────────┐   │
│ │ User    │          │ Content │   │
│ │ Service │          │ Service │   │
│ └────┬────┘          └────┬────┘   │
│      │                    │        │
│      │    ┌───────────────┴───┐    │
│      │    │                   │    │
│      ▼    ▼                   ▼    │
│ ┌─────────────┐         ┌─────────────┐
│ │Playback     │         │Recommendation│
│ │Service      │         │Service       │
│ └──────┬──────┘         └──────┬──────┘
│        │                       │        │
│        └───────────┬───────────┘        │
│                    │                     │
│                    ▼                     │
│    ┌───────────────────────────────────┐│
│    │         MESSAGE QUEUE             ││
│    │          (Kafka)                  ││
│    └───────────────────────────────────┘│
│                    │                     │
│    ┌───────────────┴───────────────┐    │
│    ▼               ▼               ▼    │
│ ┌──────┐      ┌──────────┐    ┌───────┐│
│ │Events│      │Analytics │    │ML/AI  ││
│ │Stream│      │Pipeline  │    │Pipeline│
│ └──────┘      └──────────┘    └───────┘│
│                                         │
└─────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├───────────────┬───────────────┬───────────────┬───────────────┬─────────────────────┤
│   USER DB     │   CONTENT DB  │   CACHE       │   SEARCH      │   OBJECT STORAGE    │
│  (Cassandra)  │  (Cassandra)  │   (EVCache)   │(Elasticsearch)│      (S3)           │
│               │               │               │               │                     │
│ - Profiles    │ - Metadata    │ - Sessions    │ - Title search│ - Video files       │
│ - History     │ - Availability│ - User data   │ - Cast search │ - Images            │
│ - Watchlist   │ - Regions     │ - Content     │ - Genre filter│ - Subtitles         │
└───────────────┴───────────────┴───────────────┴───────────────┴─────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           VIDEO PROCESSING PIPELINE                                  │
│                                                                                     │
│  ┌───────────┐    ┌───────────┐    ┌───────────┐    ┌───────────┐                  │
│  │  Ingest   │───>│ Encoding  │───>│  QC       │───>│   CDN     │                  │
│  │  (Upload) │    │ (Multiple │    │(Quality   │    │  Deploy   │                  │
│  │           │    │  formats) │    │  Check)   │    │           │                  │
│  └───────────┘    └───────────┘    └───────────┘    └───────────┘                  │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Services

1. **User Service**: Authentication, profiles, preferences
2. **Content Service**: Catalog management, metadata
3. **Playback Service**: Stream URLs, DRM, bookmarks
4. **Recommendation Service**: Personalized suggestions
5. **Search Service**: Full-text search
6. **Billing Service**: Subscriptions, payments
7. **Encoding Service**: Video transcoding
8. **CDN Service**: Content distribution

---

## Request Flows

### Video Playback Flow

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌────────┐  ┌─────────┐  ┌────────┐
│ Client │  │ LB  │  │ API GW  │  │ Playback  │  │  DRM   │  │Steering │  │  CDN   │
│        │  │     │  │         │  │ Service   │  │ Server │  │ Service │  │  PoP   │
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └───┬────┘  └────┬────┘  └───┬────┘
    │          │          │             │            │            │           │
    │ Play     │          │             │            │            │           │
    │ Request  │          │             │            │            │           │
    │─────────>│          │             │            │            │           │
    │          │─────────>│             │            │            │           │
    │          │          │ Validate    │            │            │           │
    │          │          │────────────>│            │            │           │
    │          │          │             │            │            │           │
    │          │          │             │ Get License│            │           │
    │          │          │             │───────────>│            │           │
    │          │          │             │            │            │           │
    │          │          │             │<───────────│            │           │
    │          │          │             │ DRM License│            │           │
    │          │          │             │            │            │           │
    │          │          │             │ Select CDN │            │           │
    │          │          │             │────────────────────────>│           │
    │          │          │             │            │            │           │
    │          │          │             │<────────────────────────│           │
    │          │          │             │ CDN URLs   │            │           │
    │          │          │             │            │            │           │
    │          │<─────────│<────────────│            │            │           │
    │<─────────│ Manifest │             │            │            │           │
    │          │ + URLs   │             │            │            │           │
    │          │          │             │            │            │           │
    │ Request video segments directly from CDN      │            │           │
    │─────────────────────────────────────────────────────────────────────────>│
    │          │          │             │            │            │           │
    │<────────────────────────────────────────────────────────────────────────│
    │ Video    │          │             │            │            │           │
    │ Segment  │          │             │            │            │           │
```

### Content Browsing Flow

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌───────────┐
│ Client │  │ LB  │  │ API GW  │  │ Content   │  │  Cache  │  │Content DB │
│        │  │     │  │         │  │ Service   │  │(EVCache)│  │(Cassandra)│
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └─────┬─────┘
    │          │          │             │             │             │
    │ GET      │          │             │             │             │
    │ /browse  │          │             │             │             │
    │─────────>│          │             │             │             │
    │          │─────────>│             │             │             │
    │          │          │────────────>│             │             │
    │          │          │             │ Check Cache │             │
    │          │          │             │────────────>│             │
    │          │          │             │             │             │
    │          │          │             │ Cache HIT   │             │
    │          │          │             │<────────────│             │
    │          │          │             │             │             │
    │          │          │             │ (If MISS)   │             │
    │          │          │             │─────────────────────────>│
    │          │          │             │             │             │
    │          │          │             │<─────────────────────────│
    │          │          │             │             │             │
    │          │          │             │ Update Cache│             │
    │          │          │             │────────────>│             │
    │          │          │             │             │             │
    │<─────────│<─────────│<────────────│             │             │
    │ Content  │          │             │             │             │
    │ List     │          │             │             │             │
```

### Recommendation Flow

```
┌────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐  ┌───────────┐
│ Client │  │  API GW   │  │  Reco     │  │   ML      │  │ User Data │
│        │  │           │  │ Service   │  │  Model    │  │    DB     │
└───┬────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘  └─────┬─────┘
    │             │              │              │              │
    │ GET /reco   │              │              │              │
    │────────────>│              │              │              │
    │             │─────────────>│              │              │
    │             │              │ Get user history           │
    │             │              │─────────────────────────────>
    │             │              │              │              │
    │             │              │<─────────────────────────────
    │             │              │              │              │
    │             │              │ Get predictions            │
    │             │              │─────────────>│              │
    │             │              │              │              │
    │             │              │<─────────────│              │
    │             │              │ Ranked titles│              │
    │             │              │              │              │
    │             │              │ Enrich with metadata        │
    │             │              │───────────(Content DB)      │
    │             │              │              │              │
    │<────────────│<─────────────│              │              │
    │ Personalized│              │              │              │
    │ Content     │              │              │              │
```

---

## Detailed Component Design

### 1. Open Connect CDN (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          OPEN CONNECT ARCHITECTURE                                   │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Why Custom CDN:                                                                    │
│  - 95% of Netflix traffic is video                                                 │
│  - Traditional CDNs expensive at Netflix scale                                     │
│  - Need specialized video delivery optimization                                     │
│                                                                                     │
│  Architecture:                                                                      │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                          TIER 1: AWS ORIGIN                                  │   │
│  │                                                                              │   │
│  │  ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │  │                         S3 BUCKETS                                   │   │   │
│  │  │                    (Master copies of all content)                    │   │   │
│  │  └─────────────────────────────────────────────────────────────────────┘   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      │ Nightly fill (proactive push)              │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                      TIER 2: REGIONAL PoPs (IXPs)                            │   │
│  │                                                                              │   │
│  │  ┌───────────────┐    ┌───────────────┐    ┌───────────────┐               │   │
│  │  │  IXP Location │    │  IXP Location │    │  IXP Location │               │   │
│  │  │  (Los Angeles)│    │   (Chicago)   │    │   (New York)  │               │   │
│  │  │               │    │               │    │               │               │   │
│  │  │  100+ TB SSD  │    │  100+ TB SSD  │    │  100+ TB SSD  │               │   │
│  │  └───────────────┘    └───────────────┘    └───────────────┘               │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      │ On-demand or predicted content             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                      TIER 3: ISP EMBEDDED APPLIANCES                         │   │
│  │                                                                              │   │
│  │  ┌───────────────┐    ┌───────────────┐    ┌───────────────┐               │   │
│  │  │   Comcast     │    │     AT&T      │    │   Verizon     │               │   │
│  │  │   Datacenter  │    │   Datacenter  │    │   Datacenter  │               │   │
│  │  │               │    │               │    │               │               │   │
│  │  │  OCAs (Open   │    │     OCAs      │    │     OCAs      │               │   │
│  │  │   Connect     │    │               │    │               │               │   │
│  │  │  Appliances)  │    │               │    │               │               │   │
│  │  │               │    │               │    │               │               │   │
│  │  │  10-100 TB    │    │   10-100 TB   │    │   10-100 TB   │               │   │
│  │  └───────────────┘    └───────────────┘    └───────────────┘               │   │
│  │                                                                              │   │
│  │  10,000+ appliances deployed globally                                       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Content Distribution:                                                              │
│  1. New content uploaded to S3                                                     │
│  2. Proactive push to regional PoPs (during off-peak)                             │
│  3. Push to ISP appliances based on predicted demand                              │
│  4. 95%+ requests served from ISP appliances                                      │
│                                                                                     │
│  Steering Service:                                                                  │
│  - Select best OCA for each client                                                 │
│  - Consider: network proximity, server load, content availability                  │
│  - Real-time health monitoring                                                     │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class CDNSteeringService:
    def __init__(self):
        self.oca_health = OCHealthMonitor()
        self.content_index = ContentLocationIndex()
        
    async def get_streaming_urls(self, user_id: str, title_id: str, device_info: dict):
        # Get user's network info
        user_ip = get_client_ip()
        user_location = geoip_lookup(user_ip)
        user_isp = get_isp(user_ip)
        
        # Find OCAs with this content
        ocas_with_content = await self.content_index.find_ocas(title_id)
        
        # Filter and rank OCAs
        candidate_ocas = []
        for oca in ocas_with_content:
            score = self.score_oca(oca, user_location, user_isp, device_info)
            if score > 0:
                candidate_ocas.append((oca, score))
        
        # Sort by score
        candidate_ocas.sort(key=lambda x: x[1], reverse=True)
        
        # Return top 3 (for failover)
        return [oca.get_url(title_id) for oca, _ in candidate_ocas[:3]]
    
    def score_oca(self, oca, user_location, user_isp, device_info):
        score = 100
        
        # Prefer ISP-embedded OCAs
        if oca.is_embedded and oca.isp == user_isp:
            score += 50
        
        # Network proximity
        distance = calculate_network_distance(user_location, oca.location)
        score -= distance * 0.1
        
        # Current load
        load = self.oca_health.get_load(oca.id)
        score -= load * 0.5
        
        # Bandwidth capacity
        if device_info.get('supports_4k') and oca.supports_4k:
            score += 20
        
        return score
```

### 2. Video Encoding Pipeline (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          VIDEO ENCODING PIPELINE                                     │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Input: Master file (4K, HDR, Dolby Atmos, etc.)                                   │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         STEP 1: ANALYSIS                                     │   │
│  │                                                                              │   │
│  │  - Shot detection (scene boundaries)                                         │   │
│  │  - Complexity analysis per shot                                              │   │
│  │  - Motion analysis                                                           │   │
│  │  - Color space analysis                                                      │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                    STEP 2: PER-SHOT ENCODING (CABR)                          │   │
│  │                                                                              │   │
│  │  Chunk-Based Adaptive Bitrate:                                              │   │
│  │  - Each shot encoded separately                                              │   │
│  │  - Bitrate allocated based on complexity                                     │   │
│  │  - Simple scenes: lower bitrate                                              │   │
│  │  - Complex scenes: higher bitrate                                            │   │
│  │                                                                              │   │
│  │  ┌────────────────────────────────────────────────────────────────────┐     │   │
│  │  │  Shot 1 (Simple)  →  1 Mbps                                        │     │   │
│  │  │  Shot 2 (Complex) →  8 Mbps                                        │     │   │
│  │  │  Shot 3 (Medium)  →  4 Mbps                                        │     │   │
│  │  │  ...                                                               │     │   │
│  │  │  Average: 5 Mbps (same quality at lower bitrate)                  │     │   │
│  │  └────────────────────────────────────────────────────────────────────┘     │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                    STEP 3: MULTI-RESOLUTION ENCODING                         │   │
│  │                                                                              │   │
│  │  Output Ladder (typical movie):                                             │   │
│  │                                                                              │   │
│  │  Resolution    Bitrate Range    Codec       Use Case                        │   │
│  │  ─────────────────────────────────────────────────────────────              │   │
│  │  4K UHD        15-25 Mbps       HEVC/VP9    Smart TVs, high-end             │   │
│  │  1080p         5-8 Mbps         H.264/HEVC  Laptops, good connection        │   │
│  │  720p          2.5-4 Mbps       H.264       Mobile WiFi                     │   │
│  │  480p          1-1.5 Mbps       H.264       Mobile cellular                 │   │
│  │  360p          0.5-0.8 Mbps     H.264       Very slow connections           │   │
│  │  240p          0.2-0.4 Mbps     H.264       Emergency fallback              │   │
│  │                                                                              │   │
│  │  + Multiple audio tracks (5.1, Atmos, stereo)                               │   │
│  │  + Multiple subtitle tracks (30+ languages)                                  │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                       STEP 4: QUALITY CONTROL                                │   │
│  │                                                                              │   │
│  │  Automated checks:                                                           │   │
│  │  - VMAF score (perceptual quality metric)                                   │   │
│  │  - Audio sync verification                                                   │   │
│  │  - Subtitle timing validation                                                │   │
│  │  - DRM encryption verification                                               │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Processing Stats:                                                                  │
│  - Encoding time: ~8 hours per hour of content (parallel)                          │
│  - 1000+ encoding servers                                                          │
│  - Cost: ~$0.20 per minute of content                                              │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Recommendation System (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          RECOMMENDATION SYSTEM                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Input Signals:                                                                     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  User Signals:                     Content Signals:                          │   │
│  │  - Viewing history                 - Genre, cast, director                   │   │
│  │  - Watch completion %              - Release date                            │   │
│  │  - Time of day                     - Content rating                          │   │
│  │  - Device type                     - Similar titles                          │   │
│  │  - Explicit ratings                - User ratings distribution               │   │
│  │  - Search queries                  - Watch patterns                          │   │
│  │  - Browse behavior                 - Engagement metrics                      │   │
│  │  - Skip/rewind patterns                                                      │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Recommendation Pipeline:                                                           │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  STAGE 1: CANDIDATE GENERATION                                               │   │
│  │                                                                              │   │
│  │  ┌────────────────┐    ┌────────────────┐    ┌────────────────┐            │   │
│  │  │  Collaborative │    │  Content-Based │    │  Trending/     │            │   │
│  │  │  Filtering     │    │  Filtering     │    │  Popular       │            │   │
│  │  │                │    │                │    │                │            │   │
│  │  │ "Users like    │    │ "Similar to    │    │ "Popular in    │            │   │
│  │  │  you watched"  │    │  what you     │    │  your region"  │            │   │
│  │  │                │    │  watched"      │    │                │            │   │
│  │  └────────┬───────┘    └────────┬───────┘    └────────┬───────┘            │   │
│  │           │                     │                     │                     │   │
│  │           └─────────────────────┴─────────────────────┘                     │   │
│  │                                 │                                            │   │
│  │                                 ▼                                            │   │
│  │                        Candidate Pool                                        │   │
│  │                        (~10,000 titles)                                      │   │
│  │                                                                              │   │
│  └──────────────────────────────────┬──────────────────────────────────────────┘   │
│                                     │                                             │
│                                     ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  STAGE 2: RANKING                                                            │   │
│  │                                                                              │   │
│  │  Deep Learning Model (Two-Tower):                                           │   │
│  │                                                                              │   │
│  │  ┌──────────────┐              ┌──────────────┐                             │   │
│  │  │  User Tower  │              │ Content Tower │                             │   │
│  │  │              │              │              │                             │   │
│  │  │ User features│              │Content features│                           │   │
│  │  │      ↓       │              │      ↓       │                             │   │
│  │  │ Dense layers │              │ Dense layers │                             │   │
│  │  │      ↓       │              │      ↓       │                             │   │
│  │  │User embedding│              │Content embed │                             │   │
│  │  └──────┬───────┘              └──────┬───────┘                             │   │
│  │         │                             │                                      │   │
│  │         └────────────┬────────────────┘                                      │   │
│  │                      │                                                       │   │
│  │                      ▼                                                       │   │
│  │              Dot Product → Score                                             │   │
│  │                                                                              │   │
│  │  Output: Ranked list of ~1000 titles                                        │   │
│  │                                                                              │   │
│  └──────────────────────────────────┬──────────────────────────────────────────┘   │
│                                     │                                             │
│                                     ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  STAGE 3: ROW GENERATION                                                     │   │
│  │                                                                              │   │
│  │  Organize into UI rows:                                                      │   │
│  │  - "Because You Watched X"                                                   │   │
│  │  - "Trending Now"                                                            │   │
│  │  - "Top Picks for You"                                                       │   │
│  │  - "Continue Watching"                                                       │   │
│  │  - Genre-specific rows                                                       │   │
│  │                                                                              │   │
│  │  Each row: 40-75 titles, personalized order                                 │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class RecommendationService:
    def __init__(self):
        self.user_model = load_model("user_tower")
        self.content_model = load_model("content_tower")
        self.content_embeddings = load_precomputed_embeddings()
        
    async def get_recommendations(self, user_id: str, context: dict):
        # Get user features
        user_features = await self.get_user_features(user_id)
        
        # Compute user embedding
        user_embedding = self.user_model.predict(user_features)
        
        # Candidate generation
        candidates = await self.generate_candidates(user_id, user_embedding)
        
        # Rank candidates
        ranked = self.rank_candidates(user_embedding, candidates, context)
        
        # Generate rows
        rows = self.generate_rows(ranked, user_features)
        
        return rows
    
    async def generate_candidates(self, user_id: str, user_embedding):
        # Collaborative filtering candidates
        cf_candidates = await self.cf_model.get_candidates(user_id, limit=3000)
        
        # Content-based candidates
        recently_watched = await self.get_recently_watched(user_id)
        cb_candidates = self.get_similar_content(recently_watched, limit=3000)
        
        # Trending/popular
        trending = await self.get_trending(user_region, limit=1000)
        
        # Deduplicate and return
        return set(cf_candidates + cb_candidates + trending)
    
    def rank_candidates(self, user_embedding, candidates, context):
        scores = []
        for title_id in candidates:
            content_embedding = self.content_embeddings[title_id]
            
            # Base score from embedding similarity
            base_score = np.dot(user_embedding, content_embedding)
            
            # Context adjustments
            if context.get('time_of_day') == 'evening':
                # Boost longer content in evening
                base_score *= 1.1 if self.is_long_content(title_id) else 1.0
            
            if context.get('device') == 'mobile':
                # Boost shorter content on mobile
                base_score *= 1.1 if not self.is_long_content(title_id) else 0.9
            
            scores.append((title_id, base_score))
        
        return sorted(scores, key=lambda x: x[1], reverse=True)
```

---

## Trade-offs and Tech Choices

### 1. Streaming Protocol

| Protocol | Pros | Cons | Use Case |
|----------|------|------|----------|
| HLS | Wide support, HTTP-based | Higher latency | iOS, Safari |
| DASH | Flexible, industry standard | Less iOS support | Android, Web |
| CMAF | Unified format | Newer standard | Modern clients |

**Decision:** Support both HLS and DASH, with CMAF as common segment format

### 2. Codec Selection

| Codec | Quality | Device Support | Encoding Cost |
|-------|---------|----------------|---------------|
| H.264 | Good | Universal | Low |
| HEVC/H.265 | Better | Modern devices | Medium |
| VP9 | Better | Chrome, Android | Medium |
| AV1 | Best | Limited | High |

**Decision:** Encode in multiple codecs, serve based on device capability

### 3. Database Selection

| Data | Database | Reason |
|------|----------|--------|
| User data | Cassandra | High availability, geo-distribution |
| Content metadata | Cassandra | Read-heavy, eventually consistent |
| Session data | EVCache (Memcached) | Low latency, ephemeral |
| Search | Elasticsearch | Full-text search |
| Analytics | Druid/Presto | Time-series, aggregations |

### 4. Adaptive Bitrate Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│              ADAPTIVE BITRATE (ABR) ALGORITHM                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Client-side ABR:                                               │
│                                                                 │
│  function selectQuality(bufferLevel, bandwidth, lastQuality):   │
│                                                                 │
│    // Buffer-based selection                                    │
│    if bufferLevel < 10 seconds:                                │
│      return LOWEST_QUALITY  // Prevent rebuffer                 │
│                                                                 │
│    if bufferLevel > 30 seconds:                                │
│      // Can try higher quality                                  │
│      targetBitrate = bandwidth * 0.8  // 80% of measured       │
│    else:                                                        │
│      targetBitrate = bandwidth * 0.6  // Conservative          │
│                                                                 │
│    // Find closest quality level                                │
│    for quality in QUALITY_LEVELS:                              │
│      if quality.bitrate <= targetBitrate:                      │
│        return quality                                           │
│                                                                 │
│    return LOWEST_QUALITY                                        │
│                                                                 │
│  Netflix additions:                                             │
│  - Per-title complexity adjustment                              │
│  - Device capability awareness                                  │
│  - Historical bandwidth patterns                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Failure Scenarios and Bottlenecks

### 1. CDN Node Failure

```
┌─────────────────────────────────────────────────────────────────┐
│                    CDN FAILOVER                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Detection:                                                      │
│  - Health checks every 10 seconds                               │
│  - Client-reported errors                                       │
│  - Bandwidth/latency degradation                                │
│                                                                 │
│  Response:                                                       │
│  1. Steering service marks node unhealthy                       │
│  2. New requests directed to backup nodes                       │
│  3. Active streams: client automatically tries backup URL       │
│  4. Manifest includes 3 backup URLs per segment                 │
│                                                                 │
│  Client behavior:                                                │
│  - Timeout on segment: 5 seconds                                │
│  - Retry with exponential backoff                               │
│  - After 3 failures: try backup URL                             │
│  - Report failure to analytics                                  │
│                                                                 │
│  Recovery time: < 10 seconds for new streams                    │
│                 < 30 seconds for active streams                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. AWS Region Failure

```
Problem: Entire AWS region becomes unavailable

Solution: Multi-region active-active

┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  Architecture:                                                   │
│  - 3 AWS regions: US-East, US-West, EU-West                    │
│  - Each region fully independent                                │
│  - Cassandra multi-region replication                          │
│  - Route 53 health-based routing                               │
│                                                                 │
│  Failover:                                                       │
│  1. Health check fails (3 consecutive)                          │
│  2. Route 53 removes region from DNS                           │
│  3. Traffic shifts to healthy regions                          │
│  4. Capacity auto-scales in remaining regions                  │
│                                                                 │
│  Recovery time: ~60 seconds (DNS TTL)                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3. Peak Load (New Season Release)

```
Problem: Major show release causes 10x traffic spike

Mitigation:

1. Pre-warming:
   - Deploy content to all CDN nodes before release
   - Pre-compute recommendations for likely viewers
   - Scale up API servers proactively

2. Release strategy:
   - Staggered release by timezone (midnight local time)
   - Spread load over 24 hours
   - Or: simultaneous global release with extra capacity

3. Graceful degradation:
   - Reduce recommendation complexity
   - Serve cached content lists
   - Disable non-essential features (ratings sync)

4. Client-side caching:
   - Cache browse data for 5 minutes
   - Prefetch likely-to-view content
```

### 4. Content Delivery Bottleneck

```
Problem: Popular new release exhausts CDN capacity

Solution:

1. Predictive distribution:
   - ML model predicts popular content
   - Pre-position on more nodes
   - Increase replication factor

2. Real-time scaling:
   - Monitor per-title bandwidth
   - Dynamically replicate to more nodes
   - Shift traffic away from congested ISPs

3. Quality adjustment:
   - Temporarily cap maximum quality
   - Encourage off-peak viewing (messaging)
```

---

## Future Improvements

### 1. AV1 Codec Adoption

```
Benefits:
- 30% better compression than HEVC
- 50% better than H.264
- Royalty-free

Challenges:
- Encoding is 10x slower/more expensive
- Limited device support (improving)

Strategy:
- Encode new content in AV1 + fallback
- Re-encode popular catalog
- Prioritize 4K content
```

### 2. Interactive Content

- Branching narratives (Bandersnatch)
- Multi-language real-time dubbing (AI)
- Personalized trailers
- Social viewing features

### 3. Live Streaming

- Sports rights (already started)
- Live events
- Different CDN requirements (lower latency)

---

## Interviewer Questions & Answers

### Q1: How does Netflix's CDN work?

**Answer:**

Netflix built their own CDN called **Open Connect** because:
1. 95%+ of their traffic is video
2. Traditional CDNs are expensive at their scale
3. Need specialized video optimization

**Architecture:**

1. **Origin (S3):** Master copies of all content
2. **Regional PoPs (IXPs):** Located at internet exchange points
3. **ISP-embedded appliances (OCAs):** Servers inside ISP datacenters

**How it works:**
```
1. Content encoded → Stored in S3
2. Nightly: Popular content pushed to regional PoPs
3. Predictive: Content pushed to ISP appliances based on expected demand
4. User plays: Steering service selects best OCA
5. Video streamed directly from OCA (not through Netflix servers)
```

**Steering Service:**
- Considers: network proximity, server load, content availability
- Returns multiple URLs (primary + backups)
- Real-time health monitoring

**Benefits:**
- 95%+ traffic served from ISP appliances
- Reduced bandwidth costs
- Better quality (closer to user)
- ISPs benefit from reduced external traffic

---

### Q2: How do you implement adaptive bitrate streaming?

**Answer:**

**Encoding:**
```
Each video encoded at multiple quality levels:
- 4K: 15-25 Mbps (HEVC)
- 1080p: 5-8 Mbps (H.264/HEVC)
- 720p: 2.5-4 Mbps
- 480p: 1-1.5 Mbps
- Lower for slow connections
```

**Segmentation:**
- Video split into 2-4 second segments
- Each segment available at all quality levels
- Manifest file lists all segments and qualities

**Client-side algorithm:**
```python
def select_quality(buffer_level, bandwidth, history):
    # Prevent rebuffering
    if buffer_level < 10:
        return LOWEST_QUALITY
    
    # Calculate safe bitrate
    if buffer_level > 30:
        safe_bitrate = bandwidth * 0.8
    else:
        safe_bitrate = bandwidth * 0.6
    
    # Select quality
    for quality in sorted(QUALITIES, reverse=True):
        if quality.bitrate <= safe_bitrate:
            return quality
    
    return LOWEST_QUALITY
```

**Netflix innovations:**
- Per-title encoding (complex scenes get more bits)
- Device-aware quality (mobile vs TV)
- Per-shot encoding (CABR)

---

### Q3: How does the recommendation system work?

**Answer:**

**Input signals:**
- Viewing history (what, when, how long)
- Explicit ratings
- Search queries
- Browse behavior
- Device, time of day
- Skip/rewind patterns

**Pipeline:**

1. **Candidate Generation:**
   - Collaborative filtering: "Users like you watched..."
   - Content-based: "Similar to what you watched..."
   - Trending/popular
   - → ~10,000 candidates

2. **Ranking:**
   - Two-tower neural network
   - User embedding + Content embedding → Score
   - Context adjustments (device, time)
   - → ~1,000 ranked items

3. **Row Generation:**
   - Organize into themed rows
   - "Because you watched X"
   - "Trending Now"
   - "Top Picks"
   - Each row personalized

**Offline computation:**
- User embeddings updated hourly
- Content embeddings updated daily
- Full ranking run periodically

**Real-time adjustments:**
- Current session behavior
- Time of day
- Device type

---

### Q4: How do you handle content encryption and DRM?

**Answer:**

**DRM Systems:**
- **Widevine** (Google) - Android, Chrome
- **FairPlay** (Apple) - iOS, Safari
- **PlayReady** (Microsoft) - Windows, Edge, Smart TVs

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                    DRM FLOW                                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Content encrypted with Content Encryption Key (CEK)         │
│  2. CEK encrypted with License Server key                       │
│  3. Encrypted CEK stored in manifest                            │
│                                                                 │
│  Playback:                                                       │
│  1. Client downloads manifest (with encrypted CEK)              │
│  2. Client requests license from License Server                 │
│     - Sends device certificate, user info                       │
│  3. License Server:                                              │
│     - Validates subscription                                     │
│     - Checks device limits                                       │
│     - Returns decryption key                                    │
│  4. Client decrypts and plays                                   │
│                                                                 │
│  Security levels:                                                │
│  - L1: Hardware-based (4K allowed)                              │
│  - L2: Software + hardware                                      │
│  - L3: Software only (limited quality)                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Additional protections:**
- HDCP for external displays
- Device limits per account
- Watermarking for forensic tracking

---

### Q5: How do you scale to handle 200 million subscribers?

**Answer:**

**Control Plane (AWS):**
```
- Microservices architecture (~1000 services)
- Stateless services, auto-scaling
- Regional deployment (3+ regions)
- Cassandra for data (multi-region)
- EVCache for session/cache data
```

**Data Plane (Open Connect):**
```
- 10,000+ OCA appliances
- Deployed inside 1000+ ISPs globally
- Each handles 10-40 Gbps
- Total capacity: 15+ Tbps
```

**Scaling strategies:**

1. **Horizontal scaling:**
   - Add more service instances
   - Add more database nodes
   - Add more CDN appliances

2. **Caching:**
   - EVCache for hot data
   - Client-side caching
   - CDN caching

3. **Precomputation:**
   - Recommendations computed offline
   - Content distributed proactively
   - ABR decisions on client

4. **Geographic distribution:**
   - Control plane in multiple regions
   - CDN everywhere
   - Route users to nearest resources

---

### Q6: How do you handle peak traffic (new show release)?

**Answer:**

**Prediction:**
- ML models predict viewership
- Based on: show popularity, marketing, time slot
- Historical patterns for similar shows

**Pre-warming:**
```
Before release:
1. Deploy content to all CDN nodes
2. Pre-compute recommendations
3. Scale up API servers (2-3x)
4. Warm caches with metadata
```

**Release strategies:**

1. **Midnight release (by timezone):**
   - Content available at midnight local time
   - Natural load distribution over 24 hours
   - Reduces peak by ~10x

2. **Global simultaneous (for major releases):**
   - Extra capacity provisioned
   - Graceful degradation ready
   - Monitor and respond in real-time

**Graceful degradation:**
```
Tier 1 (normal): Full features
Tier 2 (high load): Simplified recommendations
Tier 3 (critical): Static content lists, cached data
Tier 4 (emergency): Essential playback only
```

---

### Q7: How do you ensure video quality?

**Answer:**

**Encoding quality:**
- VMAF (Video Multimethod Assessment Fusion) scoring
- Per-title optimization
- Per-shot bitrate allocation

**Per-title encoding:**
```
Problem: Fixed bitrate ladder wastes bits on simple content

Solution:
1. Analyze each title's complexity
2. Create custom bitrate ladder
3. Simple animations: lower bitrates sufficient
4. Action movies: need higher bitrates

Result: Same quality at 20% lower bandwidth
```

**Quality monitoring:**
```python
class QualityMonitor:
    def track_session(self, session_id, event):
        # Track rebuffers
        if event.type == 'rebuffer':
            self.metrics.increment('rebuffers')
            self.metrics.add('rebuffer_duration', event.duration)
        
        # Track quality switches
        if event.type == 'quality_change':
            self.metrics.increment('quality_switches')
        
        # Track playback start time
        if event.type == 'playback_start':
            self.metrics.add('start_time', event.timestamp - session.request_time)
```

**Metrics:**
- Rebuffer rate: < 0.5%
- Rebuffer duration: < 1 second average
- Start time: < 2 seconds
- Average quality: Track by device/network

---

### Q8: How do you implement search functionality?

**Answer:**

**Architecture:**
```
User query → API → Search Service → Elasticsearch → Ranked results
```

**Index design:**
```json
{
  "title": {
    "type": "text",
    "analyzer": "netflix_analyzer"
  },
  "cast": {
    "type": "text"
  },
  "genres": {
    "type": "keyword"
  },
  "description": {
    "type": "text"
  },
  "release_year": {
    "type": "integer"
  },
  "popularity_score": {
    "type": "float"
  }
}
```

**Search features:**

1. **Autocomplete:**
   - Prefix matching on titles
   - Show suggestions as user types
   - Personalized by viewing history

2. **Full-text search:**
   - Match on title, cast, description
   - Boost exact title matches
   - Fuzzy matching for typos

3. **Filters:**
   - Genre, year, rating
   - Available in region
   - Audio/subtitle language

**Ranking:**
```python
def rank_search_results(query, results, user):
    for result in results:
        score = result.text_score
        
        # Boost popular content
        score *= 1 + (result.popularity_score * 0.1)
        
        # Boost based on user preferences
        if result.genre in user.preferred_genres:
            score *= 1.2
        
        # Boost recent content
        recency = (2024 - result.release_year) / 100
        score *= 1 - recency
        
        result.final_score = score
    
    return sorted(results, key=lambda r: r.final_score, reverse=True)
```

---

### Q9: How do you handle multiple profiles per account?

**Answer:**

**Data model:**
```sql
-- Account level
CREATE TABLE accounts (
    account_id UUID PRIMARY KEY,
    email TEXT,
    subscription_tier TEXT,
    payment_info BLOB
);

-- Profile level
CREATE TABLE profiles (
    account_id UUID,
    profile_id UUID,
    name TEXT,
    avatar_url TEXT,
    is_kids BOOLEAN,
    maturity_rating TEXT,
    language TEXT,
    PRIMARY KEY ((account_id), profile_id)
);

-- Viewing history per profile
CREATE TABLE viewing_history (
    profile_id UUID,
    title_id UUID,
    watched_at TIMESTAMP,
    progress_seconds INT,
    PRIMARY KEY ((profile_id), watched_at, title_id)
) WITH CLUSTERING ORDER BY (watched_at DESC);
```

**Profile-specific data:**
- Viewing history
- Watchlist
- Recommendations
- Maturity settings
- Language preferences

**Implementation:**
```python
class ProfileService:
    async def switch_profile(self, account_id: str, profile_id: str):
        # Validate profile belongs to account
        profile = await self.get_profile(account_id, profile_id)
        if not profile:
            raise InvalidProfileError()
        
        # Create session with profile context
        session = Session(
            account_id=account_id,
            profile_id=profile_id,
            maturity_rating=profile.maturity_rating,
            language=profile.language
        )
        
        return session
    
    async def get_recommendations(self, profile_id: str):
        # Use profile-specific viewing history
        history = await self.get_viewing_history(profile_id)
        
        # Generate personalized recommendations
        return await self.recommendation_service.generate(
            profile_id=profile_id,
            history=history
        )
```

---

### Q10: How do you support offline downloads?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                  OFFLINE DOWNLOAD                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Download Request:                                               │
│  1. Client requests download token                              │
│  2. Server checks:                                              │
│     - Subscription allows downloads                             │
│     - Device count within limits                                │
│     - Title available for download                              │
│  3. Server returns:                                              │
│     - Download URLs for selected quality                        │
│     - Offline DRM license                                       │
│     - Expiration date                                           │
│                                                                 │
│  DRM for offline:                                               │
│  - License with longer validity (7-30 days)                     │
│  - Bound to specific device                                     │
│  - Phone-home required periodically                             │
│                                                                 │
│  Storage:                                                        │
│  - Encrypted on device                                          │
│  - Includes audio tracks, subtitles                             │
│  - Quality selected at download time                            │
│                                                                 │
│  Limits:                                                         │
│  - Max downloads per device                                     │
│  - Max devices per account                                      │
│  - Title-specific restrictions                                  │
│  - Auto-delete after expiration                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class DownloadService:
    async def request_download(self, profile_id: str, title_id: str, quality: str):
        account = await self.get_account(profile_id)
        
        # Check subscription tier
        if not account.can_download:
            raise SubscriptionError("Upgrade to download")
        
        # Check device limits
        devices = await self.get_download_devices(account.id)
        if len(devices) >= account.max_download_devices:
            raise DeviceLimitError()
        
        # Check title availability
        title = await self.get_title(title_id)
        if not title.download_allowed:
            raise TitleNotAvailableError()
        
        # Generate offline license
        license = await self.drm_service.generate_offline_license(
            title_id=title_id,
            device_id=request.device_id,
            expiration=datetime.now() + timedelta(days=title.offline_days)
        )
        
        # Get download URLs
        urls = await self.cdn_service.get_download_urls(
            title_id=title_id,
            quality=quality
        )
        
        return DownloadToken(
            urls=urls,
            license=license,
            expires_at=license.expiration
        )
```

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                  NETFLIX - FINAL ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 200M subscribers, 15+ Tbps peak video                   │
│                                                                 │
│  Control Plane (AWS):                                           │
│  ├── API Gateway - Authentication, routing                      │
│  ├── Microservices (~1000) - Business logic                    │
│  ├── Cassandra - User data, content metadata                   │
│  ├── EVCache - Sessions, hot data                              │
│  ├── Elasticsearch - Search                                    │
│  └── Kafka - Event streaming                                   │
│                                                                 │
│  Data Plane (Open Connect):                                     │
│  ├── Origin (S3) - Master content                              │
│  ├── Regional PoPs - IXP locations                             │
│  ├── ISP Appliances (10,000+) - Edge delivery                  │
│  └── Steering Service - OCA selection                          │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── Custom CDN for video (Open Connect)                       │
│  ├── Per-title encoding optimization                           │
│  ├── Client-side ABR                                           │
│  ├── Multi-DRM support                                         │
│  ├── Precomputed recommendations                               │
│  └── Multi-region active-active                                │
│                                                                 │
│  SLA: 99.99% availability, <2s video start                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
