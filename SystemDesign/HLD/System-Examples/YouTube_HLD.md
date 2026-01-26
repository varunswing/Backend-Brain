# YouTube - High Level Design (Video Sharing Platform)

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

1. **Video Upload**
   - Upload videos of any length
   - Support multiple formats
   - Resumable uploads
   - Progress tracking

2. **Video Playback**
   - Stream videos on demand
   - Adaptive bitrate streaming
   - Support multiple devices
   - Offline download

3. **Video Processing**
   - Transcoding to multiple qualities
   - Thumbnail generation
   - Content analysis (ML)

4. **Discovery & Search**
   - Video search by title, description
   - Recommendations
   - Trending videos
   - Categories/channels

5. **Social Features**
   - Like, comment, share
   - Subscribe to channels
   - Notifications
   - Playlists

6. **Monetization**
   - Ads (pre-roll, mid-roll, post-roll)
   - Premium subscriptions
   - Creator revenue sharing

### Non-Functional Requirements

1. **Scale**: 2B+ users, 500 hours uploaded/minute
2. **Availability**: 99.99% uptime
3. **Latency**: Video start < 2 seconds
4. **Global**: Low latency worldwide
5. **Storage**: Exabytes of video content
6. **Bandwidth**: Petabits per second

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Monthly Active Users: 2 Billion
- Daily Active Users: 1 Billion
- Daily video views: 5 Billion

Uploads:
- 500 hours of video uploaded per minute
- 500 * 60 = 30,000 hours/hour
- 720,000 hours/day
- ~100 videos/second uploaded

Views:
- 5 Billion views/day
- 58,000 views/second average
- Peak: 200,000+ views/second

Watch time:
- Average session: 40 minutes
- Concurrent streams (peak): 100 Million
```

### Storage Estimates

```
Video Storage:
- New video per day: 720,000 hours
- Average raw video: 1 GB/hour
- Raw daily: 720 TB/day

After encoding (multiple qualities):
- Each video: ~5x raw size (multiple qualities + formats)
- Daily encoded: 720 TB * 5 = 3.6 PB/day
- Yearly: 1.3 EB/year

Total storage (10 years): ~15 Exabytes

Metadata:
- Per video: 10 KB
- 1 Billion videos: 10 TB
```

### Bandwidth Estimates

```
Streaming:
- Average bitrate: 5 Mbps
- 100M concurrent: 100M * 5 Mbps = 500 Pbps
- Daily transfer: 5B views * 10 min * 5 Mbps = 1.8 EB/day

Upload:
- 500 hours/min * 60 min * 1 GB/hour = 30 TB/hour
- 720 TB/day incoming
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                            │
│                    (Web, iOS, Android, Smart TV, Gaming Consoles)                   │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                      CDN                                             │
│                          (Google's Global Edge Network)                              │
│                                                                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                     │
│  │   Edge PoP 1    │  │   Edge PoP 2    │  │   Edge PoP N    │                     │
│  │   (Video Cache) │  │   (Video Cache) │  │   (Video Cache) │                     │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘                     │
│                                                                                     │
│  95%+ of video traffic served from edge                                             │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    │                                         │
                    ▼                                         ▼
┌───────────────────────────────────────┐    ┌───────────────────────────────────────┐
│           API SERVERS                  │    │         UPLOAD SERVERS                │
│                                        │    │                                       │
│  - Video metadata                      │    │  - Resumable uploads                  │
│  - User management                     │    │  - Chunked upload                     │
│  - Search                              │    │  - Progress tracking                  │
│  - Comments/likes                      │    │  - Initial validation                 │
│  - Subscriptions                       │    │                                       │
└───────────────────┬────────────────────┘    └──────────────────┬────────────────────┘
                    │                                             │
                    │                                             │
┌───────────────────┴─────────────────────────────────────────────┴───────────────────┐
│                              MESSAGE QUEUE (Pub/Sub)                                 │
│                                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐           │
│  │   Upload     │  │  Transcode   │  │  Thumbnail   │  │   Notify     │           │
│  │   Events     │  │   Events     │  │   Events     │  │   Events     │           │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘           │
│                                                                                     │
└────────────────────────────────────────┬────────────────────────────────────────────┘
                                         │
         ┌───────────────────────────────┼───────────────────────────────┐
         ▼                               ▼                               ▼
┌─────────────────────┐    ┌─────────────────────────┐    ┌─────────────────────────┐
│  TRANSCODING FARM   │    │   RECOMMENDATION        │    │   CONTENT MODERATION    │
│                     │    │       SERVICE           │    │       SERVICE           │
│  - FFmpeg workers   │    │                         │    │                         │
│  - GPU encoding     │    │  - ML models            │    │  - ML classification    │
│  - Multiple formats │    │  - User personalization │    │  - Copyright detection  │
│  - Quality ladder   │    │  - Trending             │    │  - Community flagging   │
└─────────────────────┘    └─────────────────────────┘    └─────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├──────────────────┬──────────────────┬──────────────────┬────────────────────────────┤
│   VIDEO STORE    │   METADATA DB    │   SEARCH INDEX   │        CACHE               │
│   (Colossus/     │   (Spanner/      │  (Elasticsearch) │       (Redis/              │
│    Blob Store)   │    Bigtable)     │                  │      Memcached)            │
│                  │                  │                  │                            │
│  - Raw videos    │  - Video info    │  - Title/desc    │  - Video metadata          │
│  - Encoded       │  - User data     │  - Tags          │  - User sessions           │
│    versions      │  - Channels      │  - Transcripts   │  - Trending                │
│  - Thumbnails    │  - Comments      │                  │  - View counts             │
└──────────────────┴──────────────────┴──────────────────┴────────────────────────────┘
```

### Core Components

1. **Upload Service**: Handle video uploads
2. **Transcoding Service**: Convert to multiple formats
3. **CDN**: Global video delivery
4. **API Service**: Metadata, search, social
5. **Recommendation Service**: Personalized suggestions
6. **Ads Service**: Ad selection and insertion

---

## Request Flows

### Video Upload Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│ Client │  │ Upload  │  │  Blob   │  │ Transcode │  │Metadata │  │   CDN   │
│        │  │ Server  │  │  Store  │  │  Service  │  │   DB    │  │         │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ 1. Request upload       │             │             │            │
    │───────────>│            │             │             │            │
    │            │            │             │             │            │
    │ 2. Return upload URL    │             │             │            │
    │<───────────│            │             │             │            │
    │            │            │             │             │            │
    │ 3. Upload chunks        │             │             │            │
    │───────────────────────>│             │             │            │
    │ (resumable)             │             │             │            │
    │            │            │             │             │            │
    │<───────────│ Progress   │             │             │            │
    │            │            │             │             │            │
    │ 4. Complete upload      │             │             │            │
    │───────────>│            │             │             │            │
    │            │ Store raw  │             │             │            │
    │            │───────────>│             │             │            │
    │            │            │             │             │            │
    │            │ 5. Queue transcode       │             │            │
    │            │──────────────────────────>│             │            │
    │            │            │             │             │            │
    │            │            │             │ 6. Transcode (async)     │
    │            │            │             │───(multiple qualities)   │
    │            │            │             │             │            │
    │            │            │             │ 7. Store encoded         │
    │            │            │◄────────────│             │            │
    │            │            │             │             │            │
    │            │            │             │ 8. Update metadata       │
    │            │            │             │────────────>│            │
    │            │            │             │             │            │
    │            │            │             │ 9. Push to CDN (hot regions)
    │            │            │             │───────────────────────>│
    │            │            │             │             │            │
    │<───────────│ Processing complete      │             │            │
    │ (or via notification)   │             │             │            │
```

### Video Playback Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐
│ Client │  │   API   │  │   CDN   │  │  Origin   │  │Analytics│
│        │  │ Server  │  │  Edge   │  │  Storage  │  │         │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘
    │            │            │             │             │
    │ 1. Request video page   │             │             │
    │───────────>│            │             │             │
    │            │            │             │             │
    │ 2. Return metadata      │             │             │
    │    + manifest URL       │             │             │
    │<───────────│            │             │             │
    │            │            │             │             │
    │ 3. Request manifest (MPD/M3U8)        │             │
    │────────────────────────>│             │             │
    │            │            │             │             │
    │ 4. Return manifest      │             │             │
    │<────────────────────────│             │             │
    │            │            │             │             │
    │ 5. Request video segment (based on bandwidth)       │
    │────────────────────────>│             │             │
    │            │            │ Cache HIT?  │             │
    │            │            │             │             │
    │            │            │ (If MISS)   │             │
    │            │            │────────────>│             │
    │            │            │<────────────│             │
    │            │            │             │             │
    │<────────────────────────│             │             │
    │ 6. Video segment        │             │             │
    │            │            │             │             │
    │ 7. Continue requesting segments       │             │
    │    (ABR adjusts quality based on bandwidth)         │
    │            │            │             │             │
    │ 8. Log view event       │             │             │
    │────────────────────────────────────────────────────>│
```

### Recommendation Flow

```
┌────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│ Client │  │   API   │  │   Reco    │  │   ML    │  │  User   │
│        │  │ Server  │  │  Service  │  │  Model  │  │  Data   │
└───┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │             │             │            │
    │ GET /recommendations     │             │            │
    │───────────>│             │             │            │
    │            │────────────>│             │            │
    │            │             │ Get user    │            │
    │            │             │ history     │            │
    │            │             │────────────────────────>│
    │            │             │             │            │
    │            │             │<────────────────────────│
    │            │             │             │            │
    │            │             │ Get predictions         │
    │            │             │────────────>│            │
    │            │             │             │            │
    │            │             │<────────────│            │
    │            │             │ Ranked videos           │
    │            │             │             │            │
    │            │             │ Apply business rules   │
    │            │             │ (diversity, freshness) │
    │            │             │             │            │
    │<───────────│<────────────│             │            │
    │ Recommendations          │             │            │
```

---

## Detailed Component Design

### 1. Video Transcoding Pipeline

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          VIDEO TRANSCODING PIPELINE                                  │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Input: Raw uploaded video (any format, any resolution)                             │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         STEP 1: ANALYSIS                                     │   │
│  │                                                                              │   │
│  │  - Codec detection (H.264, H.265, VP9, AV1, ProRes, etc.)                   │   │
│  │  - Resolution detection                                                      │   │
│  │  - Frame rate detection                                                      │   │
│  │  - Audio tracks detection                                                    │   │
│  │  - Duration calculation                                                      │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                    STEP 2: QUALITY LADDER DETERMINATION                      │   │
│  │                                                                              │   │
│  │  Based on source resolution:                                                │   │
│  │                                                                              │   │
│  │  Source 4K (2160p):                                                         │   │
│  │  ├── 2160p @ 20 Mbps (HEVC)                                                │   │
│  │  ├── 1440p @ 10 Mbps (HEVC)                                                │   │
│  │  ├── 1080p @ 5 Mbps (H.264)                                                │   │
│  │  ├── 720p @ 2.5 Mbps (H.264)                                               │   │
│  │  ├── 480p @ 1 Mbps (H.264)                                                 │   │
│  │  ├── 360p @ 0.6 Mbps (H.264)                                               │   │
│  │  └── 240p @ 0.3 Mbps (H.264)                                               │   │
│  │                                                                              │   │
│  │  + Audio variants: AAC 128kbps stereo, AAC 256kbps 5.1                     │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                    STEP 3: PARALLEL ENCODING                                 │   │
│  │                                                                              │   │
│  │  Approach: Split video into chunks, encode in parallel                      │   │
│  │                                                                              │   │
│  │  ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │  │                                                                      │   │   │
│  │  │  Video: [═══════════════════════════════════════════════════════]  │   │   │
│  │  │          ↓         ↓         ↓         ↓         ↓                 │   │   │
│  │  │  Chunks:[Chunk 1][Chunk 2][Chunk 3][Chunk 4][Chunk 5]              │   │   │
│  │  │          ↓         ↓         ↓         ↓         ↓                 │   │   │
│  │  │  Workers: W1       W2        W3        W4        W5                │   │   │
│  │  │          ↓         ↓         ↓         ↓         ↓                 │   │   │
│  │  │  Encoded:[E1]     [E2]      [E3]      [E4]      [E5]              │   │   │
│  │  │                                                                      │   │   │
│  │  │  Final: Concatenate chunks (seamless)                               │   │   │
│  │  │                                                                      │   │   │
│  │  └─────────────────────────────────────────────────────────────────────┘   │   │
│  │                                                                              │   │
│  │  Each chunk encoded for ALL quality levels in parallel                      │   │
│  │  Total workers per video: chunks * quality_levels (e.g., 5 * 7 = 35)       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                    STEP 4: PACKAGING                                         │   │
│  │                                                                              │   │
│  │  Generate streaming manifests:                                              │   │
│  │  - DASH (MPD) for most devices                                              │   │
│  │  - HLS (M3U8) for Apple devices                                             │   │
│  │  - Smooth Streaming for legacy                                               │   │
│  │                                                                              │   │
│  │  Segment videos for adaptive streaming:                                     │   │
│  │  - 2-10 second segments                                                     │   │
│  │  - Each segment independently decodable                                     │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Processing Stats:                                                                  │
│  - 1 hour video: ~15 minutes to fully process (parallel)                          │
│  - GPU encoding: 10x faster than CPU                                               │
│  - Cost: ~$0.10 per minute of video                                               │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Content Delivery Network

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              CDN ARCHITECTURE                                        │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Three-tier caching:                                                                │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         TIER 1: EDGE PoPs                                    │   │
│  │                                                                              │   │
│  │  - 100+ locations globally                                                  │   │
│  │  - Located at ISPs and IXPs                                                 │   │
│  │  - SSD storage (fast access)                                                │   │
│  │  - Cache popular content (~20% of videos = 95% of traffic)                 │   │
│  │                                                                              │   │
│  │  ┌───────────┐   ┌───────────┐   ┌───────────┐   ┌───────────┐            │   │
│  │  │ New York  │   │  London   │   │   Tokyo   │   │  Sydney   │            │   │
│  │  │  Edge     │   │   Edge    │   │   Edge    │   │   Edge    │            │   │
│  │  └───────────┘   └───────────┘   └───────────┘   └───────────┘            │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      │ Cache MISS                                  │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                       TIER 2: REGIONAL CACHES                                │   │
│  │                                                                              │   │
│  │  - ~20 regional data centers                                                │   │
│  │  - Larger storage capacity                                                  │   │
│  │  - Cache less popular content                                               │   │
│  │                                                                              │   │
│  │  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐        │   │
│  │  │   US-East       │    │    EU-West      │    │    AP-South     │        │   │
│  │  │   Regional      │    │    Regional     │    │    Regional     │        │   │
│  │  └─────────────────┘    └─────────────────┘    └─────────────────┘        │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                      │                                             │
│                                      │ Cache MISS                                  │
│                                      ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         TIER 3: ORIGIN                                       │   │
│  │                                                                              │   │
│  │  - Main data centers (3-5 globally)                                         │   │
│  │  - Complete video library                                                   │   │
│  │  - Cold storage for rarely accessed                                         │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Cache Warming Strategy:                                                            │
│  1. New uploads: Push to regional caches based on uploader location               │
│  2. Trending videos: Proactively push to all edge PoPs                            │
│  3. Predicted popular: ML model predicts viral, pre-warm                          │
│                                                                                     │
│  Cache Eviction:                                                                    │
│  - LRU with popularity weighting                                                   │
│  - Keep at least 1080p and 720p versions                                          │
│  - Evict 4K first (less devices support, re-fetch faster)                         │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Recommendation System

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          RECOMMENDATION SYSTEM                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Input Signals:                                                                     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  User Signals:                     Video Signals:                            │   │
│  │  - Watch history                   - Title, description, tags               │   │
│  │  - Watch time (% completion)       - Category                               │   │
│  │  - Likes/dislikes                  - Upload date                            │   │
│  │  - Subscriptions                   - View count, like ratio                 │   │
│  │  - Search queries                  - Average watch time                     │   │
│  │  - Demographics                    - Content features (ML extracted)        │   │
│  │  - Device type                     - Creator info                           │   │
│  │  - Time of day                     - Language                               │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Model Architecture (Simplified):                                                   │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  STAGE 1: CANDIDATE GENERATION                                               │   │
│  │                                                                              │   │
│  │  ┌────────────────┐    ┌────────────────┐    ┌────────────────┐            │   │
│  │  │  Collaborative │    │  Content-Based │    │    Trending    │            │   │
│  │  │   Filtering    │    │   Filtering    │    │                │            │   │
│  │  │                │    │                │    │                │            │   │
│  │  │ "Users like    │    │ "Similar to    │    │ "Popular now"  │            │   │
│  │  │  you watched"  │    │  what you      │    │                │            │   │
│  │  │                │    │  watched"      │    │                │            │   │
│  │  └────────┬───────┘    └────────┬───────┘    └────────┬───────┘            │   │
│  │           │                     │                     │                     │   │
│  │           └─────────────────────┴─────────────────────┘                     │   │
│  │                                 │                                            │   │
│  │                                 ▼                                            │   │
│  │                    Candidate Pool (~1000 videos)                            │   │
│  │                                                                              │   │
│  └──────────────────────────────────┬──────────────────────────────────────────┘   │
│                                     │                                             │
│                                     ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  STAGE 2: RANKING                                                            │   │
│  │                                                                              │   │
│  │  Deep Neural Network predicting:                                            │   │
│  │  - P(click) - Will user click?                                              │   │
│  │  - P(watch) - Will user watch substantial portion?                          │   │
│  │  - P(like) - Will user like?                                                │   │
│  │  - P(subscribe) - Will user subscribe to channel?                           │   │
│  │                                                                              │   │
│  │  Combined score: w1*P(click) + w2*P(watch) + w3*P(like) + ...              │   │
│  │                                                                              │   │
│  │  Features:                                                                   │   │
│  │  - User embedding (from watch history)                                      │   │
│  │  - Video embedding (from content + metadata)                                │   │
│  │  - Context (time, device, session)                                          │   │
│  │  - Cross features (user-video interactions)                                 │   │
│  │                                                                              │   │
│  └──────────────────────────────────┬──────────────────────────────────────────┘   │
│                                     │                                             │
│                                     ▼                                             │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  STAGE 3: RE-RANKING & DIVERSITY                                             │   │
│  │                                                                              │   │
│  │  Business rules:                                                             │   │
│  │  - Diversity: Don't show 5 videos from same creator                        │   │
│  │  - Freshness: Include some recent uploads                                   │   │
│  │  - Exploration: 10% slots for discovery                                     │   │
│  │  - Ads: Insert ad opportunities                                             │   │
│  │  - Safety: Filter policy-violating content                                  │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### 1. Video Encoding

| Codec | Quality | Encoding Speed | Device Support |
|-------|---------|----------------|----------------|
| H.264 | Good | Fast | Universal |
| H.265/HEVC | Better | Slow | Modern devices |
| VP9 | Better | Slow | Web, Android |
| AV1 | Best | Very slow | Limited (growing) |

**Decision:** Multi-codec delivery - H.264 default, HEVC/VP9 for modern, AV1 for progressive

### 2. Streaming Protocol

| Protocol | Pros | Cons |
|----------|------|------|
| HLS | iOS native, widespread | Higher latency |
| DASH | Flexible, industry standard | Less iOS support |
| CMAF | Unified segments | Newer |

**Decision:** Support both HLS and DASH, use CMAF segments

### 3. Live vs VOD Architecture

| Aspect | VOD | Live |
|--------|-----|------|
| Latency requirement | Seconds OK | <5 seconds |
| Caching | Aggressive | Limited |
| Quality | Pre-encoded | Real-time |

**Decision:** Separate pipelines optimized for each use case

---

## Failure Scenarios and Bottlenecks

### 1. Viral Video (Sudden Spike)

```
┌─────────────────────────────────────────────────────────────────┐
│                 VIRAL VIDEO HANDLING                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Detection:                                                      │
│  - View rate exceeds threshold (e.g., 10x baseline)             │
│  - Geographic spread (views from many regions)                  │
│  - Social sharing spike                                         │
│                                                                 │
│  Response:                                                       │
│  1. Promote to edge caches globally (within minutes)            │
│  2. Scale origin capacity for this video                        │
│  3. Enable "turbo mode" - skip some encoding qualities         │
│  4. Pre-warm CDN with most requested qualities (1080p, 720p)   │
│                                                                 │
│  Example:                                                        │
│  Video goes viral: 0 → 10M views in 1 hour                      │
│  - T+5min: Detected as viral                                    │
│  - T+10min: Pushed to all Tier 1 edge PoPs                     │
│  - T+15min: 95% of requests served from edge                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Transcoding Backlog

```
Problem: Upload spike causes encoding queue to grow

Mitigation:
1. Priority queues (Premium creators, trending topics)
2. Auto-scale encoding farm (GPU instances)
3. Quality ladder reduction (skip 4K if backlogged)
4. Fast-track for short videos (<10 min)
5. Geographic distribution of encoding

SLA: 95% of videos processed within 1 hour
```

### 3. CDN Origin Overload

```
Problem: Cache miss storm hits origin

Solutions:
1. Request coalescing (only one request to origin per video)
2. Stale-while-revalidate (serve old cache while fetching)
3. Multiple origin replicas
4. Popularity-based replication (hot videos in more locations)
```

---

## Future Improvements

### 1. AI-Generated Content

- Automatic dubbing (different languages)
- AI summaries and chapters
- Content-aware skip (skip intros)
- Deepfake detection

### 2. Interactive Video

- Branching narratives
- Shoppable videos
- Live polls and Q&A
- AR/VR content

### 3. Edge Computing

- Transcoding at edge
- ML inference at edge
- Lower latency personalization

---

## Interviewer Questions & Answers

### Q1: How do you design the video upload system?

**Answer:**

**Resumable upload protocol:**
```
1. Client: POST /upload/init
   - File metadata (size, type, name)
   Response: {upload_id, upload_url, chunk_size}

2. Client: PUT /upload/{upload_id}/chunk/{n}
   - Upload 5MB chunks
   - Include Content-MD5 for verification
   Response: {confirmed: true}

3. Client: Can resume from any chunk
   GET /upload/{upload_id}/status
   Response: {uploaded_chunks: [0,1,2], next_chunk: 3}

4. Client: POST /upload/{upload_id}/complete
   Server: Validates all chunks, initiates processing
```

**Why chunked:**
- Resume interrupted uploads
- Parallel upload from client
- Progress tracking
- Early validation (reject bad content sooner)

**Storage flow:**
```
Client → Upload Server → Temp Storage (chunks) → Blob Storage (assembled) → Transcoding Queue
```

---

### Q2: How does adaptive bitrate streaming work?

**Answer:**

**Process:**
1. Video encoded at multiple quality levels (ladder)
2. Packaged into small segments (2-10 seconds)
3. Manifest file lists all qualities and segment URLs
4. Client measures bandwidth, requests appropriate quality

**Manifest example (simplified HLS):**
```
#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
1080p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720
720p/playlist.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1000000,RESOLUTION=854x480
480p/playlist.m3u8
```

**Client algorithm:**
```python
def select_quality(available_bandwidth, buffer_level):
    # Buffer-based decision
    if buffer_level < 5 seconds:
        return LOWEST_QUALITY  # Prevent rebuffer
    
    if buffer_level > 30 seconds:
        # Can try higher quality
        target = bandwidth * 0.8
    else:
        target = bandwidth * 0.6  # Conservative
    
    # Select highest quality under target
    for quality in sorted(QUALITIES, reverse=True):
        if quality.bitrate <= target:
            return quality
```

---

### Q3: How do you handle video recommendations at scale?

**Answer:**

**Architecture:**
```
User Request → Candidate Generation → Ranking → Re-ranking → Response
                    (1000s)          (100s)     (10s)
```

**Candidate generation (fast, recall-focused):**
- Collaborative filtering (ALS matrix factorization)
- Content-based (video embeddings)
- Subscription videos
- Trending

**Ranking (slower, precision-focused):**
- Deep neural network
- Predicts: click, watch time, like, subscribe
- Features: user history, video metadata, context

**Implementation:**
```python
class RecommendationService:
    def get_recommendations(self, user_id, context):
        # Stage 1: Candidates (parallel)
        candidates = await asyncio.gather(
            self.collab_filter.get_candidates(user_id, 500),
            self.content_filter.get_candidates(user_id, 300),
            self.trending.get_videos(context.region, 200)
        )
        
        # Deduplicate
        all_candidates = dedupe(flatten(candidates))
        
        # Stage 2: Ranking
        user_features = await self.get_user_features(user_id)
        scores = self.ranking_model.predict(user_features, all_candidates)
        
        # Stage 3: Re-ranking
        final = self.apply_diversity(scores[:100])
        final = self.insert_ads(final)
        
        return final[:20]
```

---

### Q4: How do you handle copyright/content moderation?

**Answer:**

**Multi-layer approach:**

1. **Upload-time checks:**
   ```python
   async def check_upload(video):
       # Audio fingerprint (Content ID)
       audio_match = await content_id.check_audio(video)
       if audio_match.is_copyrighted:
           return BlockOrMonetize(audio_match.owner)
       
       # Video fingerprint
       video_match = await content_id.check_video(video)
       
       # ML classification
       safety = await ml_classifier.check(video)
       if safety.violence > 0.9 or safety.adult > 0.9:
           return RequireReview()
       
       return Approved()
   ```

2. **Content ID system:**
   - Rights holders upload reference files
   - All uploads compared against references
   - Match → Block, monetize, or track

3. **Post-upload moderation:**
   - User reports
   - ML scanning of published videos
   - Human review queue

4. **Appeals process:**
   - Creator can dispute
   - Human review of disputes
   - Counter-notification system

---

### Q5: How do you ensure low latency globally?

**Answer:**

**Multi-layer CDN:**
```
User → Edge PoP (city) → Regional Cache → Origin
       [<10ms]           [<50ms]         [<200ms]
```

**Edge PoP selection:**
```python
def select_edge_pop(user_ip, video_id):
    user_location = geoip(user_ip)
    user_isp = get_isp(user_ip)
    
    # Find PoPs with video cached
    available_pops = get_pops_with_video(video_id)
    
    # Score by proximity and health
    scores = []
    for pop in available_pops:
        score = 0
        score -= pop.distance_to(user_location) * 0.1
        score -= pop.current_load * 0.2
        if pop.isp == user_isp:
            score += 50  # Same ISP bonus
        scores.append((pop, score))
    
    return max(scores, key=lambda x: x[1])[0]
```

**Optimizations:**
- Pre-position popular videos
- Predictive caching (time-based patterns)
- Anycast DNS for automatic PoP selection
- Connection reuse (HTTP/2, QUIC)

---

### Q6: How do you handle live streaming differently?

**Answer:**

**Key differences from VOD:**

| Aspect | VOD | Live |
|--------|-----|------|
| Latency goal | 2-10 sec buffer OK | <5 sec end-to-end |
| Encoding | Pre-encoded, multiple passes | Real-time, single pass |
| CDN caching | Aggressive | Edge-only, short TTL |
| Quality | Optimized | Best-effort |

**Live architecture:**
```
Broadcaster → Ingest Server → Real-time Encoder → Origin → CDN → Viewers
              (RTMP/SRT)      (GPU cluster)       (Packager)
```

**Low-latency techniques:**
- Shorter segments (1-2 seconds)
- Chunked transfer encoding
- CMAF low-latency mode
- WebRTC for sub-second (small audience)

**Chat integration:**
- Separate WebSocket service
- Synchronize with video timeline
- Rate limiting for spam

---

### Q7: How do you monetize with ads?

**Answer:**

**Ad types:**
- Pre-roll: Before video
- Mid-roll: During video (>8 min)
- Post-roll: After video
- Overlay: Banner during video

**Ad selection:**
```python
class AdService:
    def select_ad(self, user, video, position):
        # Get targeting criteria
        targeting = {
            "demographics": user.demographics,
            "interests": user.interests,
            "content_category": video.category,
            "device": user.device,
            "geo": user.location
        }
        
        # Auction among eligible campaigns
        eligible = self.get_eligible_campaigns(targeting)
        
        # Run second-price auction
        bids = [(c, c.calculate_bid(targeting)) for c in eligible]
        winner, _ = max(bids, key=lambda x: x[1])
        second_price = sorted(bids, key=lambda x: x[1])[-2][1]
        
        return Ad(
            campaign=winner,
            price=second_price,
            creative=winner.get_creative(video.duration)
        )
```

**Ad insertion:**
- Server-side ad insertion (SSAI) for TV/OTT
- Client-side for web (more flexible)
- Dynamic ad pods based on content length

---

### Q8: How do you design the search system?

**Answer:**

**Index structure:**
```
Video document:
{
  "video_id": "abc123",
  "title": "How to bake bread",
  "description": "Step by step guide...",
  "tags": ["baking", "bread", "tutorial"],
  "transcript": "Welcome to my kitchen...",
  "channel": "CookingChannel",
  "category": "Howto",
  "upload_date": "2024-01-15",
  "view_count": 1500000,
  "like_ratio": 0.95
}
```

**Search ranking:**
```python
def rank_search_results(query, results, user):
    for video in results:
        score = video.text_score  # Elasticsearch BM25
        
        # Popularity boost
        score *= 1 + log(video.view_count) * 0.1
        
        # Freshness for time-sensitive queries
        if is_time_sensitive(query):
            age_days = (now() - video.upload_date).days
            score *= exp(-age_days / 30)
        
        # Personalization
        if video.category in user.preferred_categories:
            score *= 1.2
        
        video.final_score = score
    
    return sorted(results, key=lambda v: v.final_score, reverse=True)
```

**Autocomplete:**
- Prefix trie for suggestions
- Personalized by user history
- Trending queries boosted

---

### Q9: How do you handle video analytics?

**Answer:**

**Event tracking:**
```javascript
// Client sends events
{
  "event_type": "watch_progress",
  "video_id": "abc123",
  "user_id": "user456",
  "timestamp": 1705312800,
  "video_time": 125.5,  // seconds
  "quality": "1080p",
  "buffer_health": 15.2,  // seconds
  "device": "mobile_ios"
}
```

**Pipeline:**
```
Client → Kafka → Flink (real-time) → ClickHouse (analytics)
              ↘ Spark (batch) → Data Warehouse
```

**Metrics computed:**
- View count (30+ seconds = 1 view)
- Watch time
- Average view duration
- Audience retention curve
- Traffic sources
- Demographics breakdown

**Real-time dashboards:**
- Creator Studio analytics
- Ad performance
- System health

---

### Q10: How do you ensure video quality?

**Answer:**

**Quality metrics:**
```python
class QualityMonitor:
    def track_session(self, events):
        metrics = {
            "startup_time": events.first_frame_time - events.play_request_time,
            "rebuffer_ratio": sum(e.duration for e in events if e.type == 'rebuffer') / session.duration,
            "avg_bitrate": mean(e.bitrate for e in events if e.type == 'play'),
            "quality_switches": count(e for e in events if e.type == 'quality_change')
        }
        return metrics
```

**Targets:**
- Startup time: <2 seconds
- Rebuffer ratio: <1%
- Avg quality: 720p+ on broadband

**Encoding quality:**
- VMAF scoring (perceptual quality)
- Per-title encoding (optimize for content complexity)
- A/B testing different encoding settings

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                  YOUTUBE - FINAL ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 2B MAU, 500h uploaded/min, 1B hours watched/day        │
│                                                                 │
│  Core Components:                                               │
│  ├── Upload Service - Resumable chunked uploads                │
│  ├── Transcoding Farm - GPU encoding, parallel processing      │
│  ├── CDN (3-tier) - Edge, Regional, Origin                     │
│  ├── API Service - Metadata, social features                   │
│  ├── Recommendation - ML-based personalization                 │
│  ├── Search - Elasticsearch + custom ranking                   │
│  ├── Ads Service - Real-time bidding, insertion                │
│  └── Analytics - Real-time + batch processing                  │
│                                                                 │
│  Data Stores:                                                   │
│  ├── Blob Storage - Video files (exabytes)                     │
│  ├── Bigtable/Spanner - Metadata, user data                    │
│  ├── Elasticsearch - Search index                              │
│  └── ClickHouse - Analytics                                    │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── Multi-codec encoding (H.264 + HEVC + VP9 + AV1)          │
│  ├── Adaptive bitrate streaming (DASH + HLS)                   │
│  ├── 3-tier CDN with predictive caching                        │
│  ├── Two-stage recommendation (candidate + ranking)            │
│  └── Content ID for copyright protection                       │
│                                                                 │
│  SLA: 99.99% availability, <2s video start                     │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
