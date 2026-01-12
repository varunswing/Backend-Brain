# Common HLD Interview Problems: Quick Reference

## Overview
This guide covers the most frequently asked High-Level Design problems in senior software developer interviews. Each problem includes key components, trade-offs, and talking points.

---

## 1. URL Shortener (TinyURL)

### Requirements
- **Functional**: Shorten URL, redirect to original, custom aliases, analytics
- **Non-Functional**: Low latency (<100ms), high availability, 100M URLs/day

### Key Components
```
┌────────┐     ┌───────────┐     ┌─────────────┐     ┌──────────┐
│ Client │────→│ API Gateway│────→│ URL Service │────→│ Database │
└────────┘     └───────────┘     └─────────────┘     └──────────┘
                                        │
                                 ┌──────▼──────┐
                                 │    Cache    │
                                 └─────────────┘
```

### Key Decisions
| Decision | Choice | Reason |
|----------|--------|--------|
| ID Generation | Base62 encoding | Short, URL-safe |
| Storage | NoSQL (Cassandra) | High write throughput |
| Cache | Redis | Fast redirects |
| Hash Collision | Counter + Hash | Unique IDs |

### Talking Points
- Encoding: Base62 vs Base64 (URL-safe)
- ID generation: Counter vs Hash vs Snowflake
- Read-heavy: 100:1 read/write ratio
- Cache strategy: Cache aside for hot URLs

---

## 2. Twitter/X Design

### Requirements
- **Functional**: Post tweets, follow users, timeline, search
- **Non-Functional**: 500M users, 500M tweets/day

### Key Components
```
┌──────────────────────────────────────────────────────────────┐
│                        API Gateway                            │
└────────────────────────────┬─────────────────────────────────┘
                             │
      ┌──────────────────────┼──────────────────────┐
      ▼                      ▼                      ▼
┌───────────┐         ┌───────────┐          ┌───────────┐
│  Tweet    │         │  Timeline │          │  Search   │
│  Service  │         │  Service  │          │  Service  │
└─────┬─────┘         └─────┬─────┘          └─────┬─────┘
      │                     │                      │
      ▼                     ▼                      ▼
┌───────────┐         ┌───────────┐          ┌───────────┐
│  Tweet DB │         │ Timeline  │          │Elasticsearch│
│ (Cassandra)│        │  Cache    │          └───────────┘
└───────────┘         │  (Redis)  │
                      └───────────┘
```

### Key Decisions
- **Fan-out**: On write (for users <10K followers), on read (for celebrities)
- **Timeline**: Pre-computed in Redis for fast reads
- **Search**: Elasticsearch for tweet search

### Talking Points
- Celebrity problem (fan-out challenge)
- Timeline generation strategies
- Eventual consistency for social features
- Sharding by user ID

---

## 3. Instagram/Photo Sharing

### Requirements
- **Functional**: Upload photos, follow, feed, likes, comments
- **Non-Functional**: 500M DAU, 2B photos stored

### Key Components
```
┌────────┐     ┌─────────────┐     ┌──────────────┐
│ Client │────→│ CDN (Images)│     │ Object Store │
└────────┘     └─────────────┘     │    (S3)      │
     │                              └──────────────┘
     │                                     ↑
     │         ┌─────────────┐             │
     └────────→│ API Gateway │─────────────┘
               └──────┬──────┘
                      │
        ┌─────────────┼─────────────┐
        ▼             ▼             ▼
   ┌─────────┐  ┌─────────┐   ┌─────────┐
   │  Photo  │  │  Feed   │   │  User   │
   │ Service │  │ Service │   │ Service │
   └─────────┘  └─────────┘   └─────────┘
```

### Key Decisions
- **Image Storage**: S3 + CDN
- **Metadata**: MySQL/PostgreSQL (sharded)
- **Feed**: Redis sorted sets
- **Image Processing**: Async (queue-based)

---

## 4. WhatsApp/Messaging System

### Requirements
- **Functional**: 1:1 chat, group chat, online status, read receipts
- **Non-Functional**: 2B users, 100B messages/day

### Key Components
```
┌────────────────────────────────────────────────────────────┐
│                     WebSocket Gateway                       │
└─────────────────────────────┬──────────────────────────────┘
                              │
         ┌────────────────────┼────────────────────┐
         ▼                    ▼                    ▼
   ┌───────────┐        ┌───────────┐       ┌───────────┐
   │  Message  │        │  Group    │       │  Presence │
   │  Service  │        │  Service  │       │  Service  │
   └─────┬─────┘        └───────────┘       └─────┬─────┘
         │                                        │
         ▼                                        ▼
   ┌───────────┐                            ┌───────────┐
   │ Message Q │                            │   Redis   │
   │  (Kafka)  │                            │  (Status) │
   └─────┬─────┘                            └───────────┘
         ▼
   ┌───────────┐
   │ Cassandra │
   └───────────┘
```

### Key Decisions
- **Protocol**: WebSocket for real-time
- **Message Storage**: Cassandra (write-optimized)
- **Delivery**: Push + pull with message queue
- **Group Messages**: Fan-out on send

### Talking Points
- End-to-end encryption
- Message ordering (sequence numbers)
- Offline message handling
- Read receipts implementation

---

## 5. Netflix/Video Streaming

### Requirements
- **Functional**: Upload, stream, search, recommendations
- **Non-Functional**: 200M subscribers, 100+ countries

### Key Components
```
                         ┌─────────────┐
                         │    CDN      │
                         │  (Edges)    │
                         └──────┬──────┘
                                │
┌────────┐              ┌──────▼──────┐
│ Client │─────────────→│   API GW    │
└────────┘              └──────┬──────┘
                               │
    ┌──────────────────────────┼──────────────────────────┐
    ▼                          ▼                          ▼
┌─────────┐              ┌───────────┐              ┌───────────┐
│ Search  │              │ Streaming │              │   Rec.    │
│ Service │              │  Service  │              │  Service  │
└─────────┘              └───────────┘              └───────────┘
```

### Key Decisions
- **Video Encoding**: Multiple formats/bitrates (ABR)
- **CDN**: Custom Open Connect CDN
- **Recommendations**: ML-based personalization
- **Streaming**: HLS/DASH adaptive streaming

### Talking Points
- Adaptive bitrate streaming
- Pre-positioning content on CDN
- Video encoding pipeline
- A/B testing at scale

---

## 6. Uber/Ride Sharing

### Requirements
- **Functional**: Request ride, match driver, real-time tracking, payments
- **Non-Functional**: 10M rides/day, real-time location

### Key Components
```
┌────────────┐     ┌────────────┐
│ Rider App  │     │ Driver App │
└─────┬──────┘     └──────┬─────┘
      │                   │
      └─────────┬─────────┘
                │
         ┌──────▼──────┐
         │  API Gateway │
         └──────┬──────┘
                │
    ┌───────────┼───────────┬───────────┐
    ▼           ▼           ▼           ▼
┌───────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
│ Trip  │ │ Matching│ │Location │ │ Payment │
│Service│ │ Service │ │ Service │ │ Service │
└───────┘ └─────────┘ └─────────┘ └─────────┘
```

### Key Decisions
- **Location**: Geospatial index (Geohash/S2)
- **Matching**: Real-time with proximity search
- **ETA**: Google Maps / own ML model
- **Surge**: Dynamic pricing algorithm

### Talking Points
- Geospatial indexing strategies
- Driver-rider matching algorithm
- Real-time location updates (WebSocket)
- Surge pricing implementation

---

## 7. Google Docs/Collaborative Editing

### Requirements
- **Functional**: Real-time editing, multiple users, version history
- **Non-Functional**: Low latency collaboration, conflict resolution

### Key Components
```
┌──────────┐     ┌──────────┐     ┌──────────┐
│ Client 1 │     │ Client 2 │     │ Client 3 │
└────┬─────┘     └────┬─────┘     └────┬─────┘
     │                │                │
     └────────────────┼────────────────┘
                      │
               ┌──────▼──────┐
               │  WebSocket  │
               │   Gateway   │
               └──────┬──────┘
                      │
               ┌──────▼──────┐
               │  Document   │
               │   Service   │
               └──────┬──────┘
                      │
          ┌───────────┼───────────┐
          ▼           ▼           ▼
    ┌─────────┐ ┌───────────┐ ┌─────────┐
    │ Doc DB  │ │ Operation │ │  Redis  │
    │(MongoDB)│ │   Log     │ │ (Sync)  │
    └─────────┘ └───────────┘ └─────────┘
```

### Key Decisions
- **Conflict Resolution**: OT (Operational Transformation) or CRDT
- **Sync**: WebSocket for real-time
- **Storage**: Operation log + snapshots

---

## 8. Notification System

### Requirements
- **Functional**: Push notifications, email, SMS, in-app
- **Non-Functional**: 10M notifications/hour, delivery guarantees

### Key Components
```
┌─────────────┐
│ Event Source│
└──────┬──────┘
       │
┌──────▼──────┐
│ Notification│
│   Service   │
└──────┬──────┘
       │
┌──────▼──────┐
│   Queue     │
│   (Kafka)   │
└──────┬──────┘
       │
┌──────┴────────────────────────┐
│               │               │
▼               ▼               ▼
┌─────┐    ┌────────┐    ┌─────────┐
│Push │    │  Email │    │   SMS   │
│Service│   │ Service│    │ Service │
│(FCM) │    │(SendGrid)   │(Twilio) │
└─────┘    └────────┘    └─────────┘
```

### Key Decisions
- **Priority Queues**: Different SLAs for different types
- **Rate Limiting**: Per user, per channel
- **Template Engine**: Personalization
- **Delivery Tracking**: Store status in DB

---

## Interview Framework

### For Any HLD Problem:

1. **Clarify Requirements** (5 min)
   - Users, scale, key features
   - Read vs write ratio
   - Consistency requirements

2. **High-Level Design** (15 min)
   - Core components
   - Data flow
   - APIs

3. **Deep Dive** (15 min)
   - Database schema
   - Caching strategy
   - Key algorithms

4. **Scale & Trade-offs** (10 min)
   - Bottlenecks
   - Scaling strategies
   - Failure handling

---

## Quick Reference: Common Components

| Need | Solution |
|------|----------|
| High read throughput | Cache (Redis), CDN |
| High write throughput | Message Queue, NoSQL |
| Real-time | WebSocket, SSE |
| Search | Elasticsearch |
| Location | Geospatial DB, Geohash |
| Consistency | SQL, Distributed transactions |
| Analytics | Data warehouse, Stream processing |
