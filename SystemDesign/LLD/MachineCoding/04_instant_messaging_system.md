# Instant Messaging System (WhatsApp) - System Design Interview

## Problem Statement
*"Design an instant messaging system like WhatsApp that supports real-time messaging, group chats, media sharing, and handles billions of users globally with low latency."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What messaging features do we need?" → One-on-one chat, group messaging, media sharing (images, videos, files)
- "Do we need message status?" → Yes, sent/delivered/read receipts like WhatsApp
- "Should we support voice/video calls?" → Yes, but let's focus on text messaging first
- "What about user presence?" → Online/offline status, last seen timestamp
- "Do we need message search?" → Yes, search within conversations
- "Security requirements?" → End-to-end encryption for all messages

**Non-Functional Requirements:**
- "What's our user scale?" → 1B+ users globally, 100M+ concurrent connections
- "Message volume?" → 50B+ messages per day during peak
- "Expected latency?" → <100ms for message delivery
- "Availability requirement?" → 99.99% uptime (critical communication app)
- "Message retention?" → Store messages indefinitely, with local device storage

### Requirements Summary:
- **Users**: 1B+ global users, 100M+ concurrent connections
- **Messages**: 50B+ messages/day, real-time delivery
- **Features**: 1-on-1 chat, group chat, media sharing, read receipts
- **Performance**: <100ms message delivery, 99.99% availability
- **Security**: End-to-end encryption, secure media transfer

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Daily Active Users: 500M
Messages per user per day: 100
Total messages per day: 500M × 100 = 50B messages
Peak RPS: 50B / 86,400 = ~580K messages/second
Connection management: 100M concurrent WebSocket connections
```

### Storage Estimation:
```
Average message size: 100 bytes (text)
Daily message storage: 50B × 100 bytes = 5TB/day
Monthly storage: 5TB × 30 = 150TB/month
Media messages (20%): 150TB × 5 (avg media size) = 750TB/month
Total storage: ~900TB/month (~10PB/year)
```

### Memory Requirements:
```
Active connections: 100M × 1KB (WebSocket state) = 100GB
Message routing cache: 500K RPS × 1KB × 60 seconds = 30GB
User presence cache: 500M users × 100 bytes = 50GB
Total memory: ~200GB distributed across servers
```

---

## Phase 3: High-Level Architecture (12 minutes)

### Architecture Evolution:
```
Step 1: [Mobile Apps] → [WebSocket Gateway] → [Message Service] → [Database]

Step 2: Complete Distributed Architecture
```

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Mobile Apps  │  │Web Client   │  │Desktop App  │
│- iOS/Android│  │- WebSocket  │  │- Electron   │
│- WebSocket  │  │- React      │  │- WebSocket  │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│              Load Balancer                      │
│- Sticky sessions  - Health checks  - SSL       │
└─────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│           WebSocket Gateway                     │
│- Connection Management  - Authentication       │
│- Message Routing       - Presence Updates      │
└─────────────────────────────────────────────────┘
                        │
        ┌───────────────┼───────────────┐
        │               │               │
        ▼               ▼               ▼
┌──────────────┐ ┌──────────────┐ ┌──────────────┐
│Message       │ │User Service  │ │Notification  │
│Service       │ │              │ │Service       │
│              │ │- User Profile│ │              │
│- Send/Receive│ │- Friends     │ │- Push Notif  │
│- Group Msg   │ │- Auth        │ │- Offline     │
│- Persistence │ │- Presence    │ │  Users       │
└──────────────┘ └──────────────┘ └──────────────┘
        │               │               │
        └───────────────┼───────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│              Supporting Services                │
│                                                 │
│ ┌─────────────┐  ┌─────────────┐  ┌──────────┐  │
│ │Media Service│  │Encryption   │  │Analytics │  │
│ │             │  │Service      │  │Service   │  │
│ │- File Upload│  │- E2E Keys   │  │- Metrics │  │
│ │- Compression│  │- Encryption │  │- Logs    │  │
│ │- CDN        │  │- Key Mgmt   │  │- Monitor │  │
│ └─────────────┘  └─────────────┘  └──────────┘  │
└─────────────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────┐
│                 Data Layer                      │
│                                                 │
│ ┌─────────────┐  ┌─────────────┐  ┌──────────┐  │
│ │  Cassandra  │  │    Redis    │  │  Kafka   │  │
│ │             │  │             │  │          │  │
│ │- Messages   │  │- User       │  │- Message │  │
│ │- User Data  │  │  Presence   │  │  Queue   │  │
│ │- Groups     │  │- Active     │  │- Events  │  │
│ │- Metadata   │  │  Sessions   │  │- Stream  │  │
│ └─────────────┘  └─────────────┘  └──────────┘  │
└─────────────────────────────────────────────────┘
```

### Key Components:
- **WebSocket Gateway**: Manages 100M+ concurrent connections, message routing
- **Message Service**: Core messaging logic, delivery, persistence
- **User Service**: Authentication, user profiles, friend relationships
- **Media Service**: File uploads, compression, CDN integration
- **Notification Service**: Push notifications for offline users

---

## Phase 4: Database Design (8 minutes)

### Core Entities:
- Users (profiles, authentication, presence)
- Conversations (1-on-1, group chats)
- Messages (content, metadata, status)
- Media (files, images, videos)

### Cassandra Schema (NoSQL for scale):

```sql
-- Users table
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    phone_number VARCHAR(15) UNIQUE,
    username VARCHAR(50),
    profile_pic_url TEXT,
    public_key TEXT,              -- For E2E encryption
    last_seen TIMESTAMP,
    created_at TIMESTAMP,
    status VARCHAR(20) DEFAULT 'offline'
);

-- Conversations (both 1-on-1 and group)
CREATE TABLE conversations (
    conversation_id UUID PRIMARY KEY,
    conversation_type VARCHAR(10),  -- 'direct' or 'group'
    participants SET<UUID>,         -- Set of user_ids
    created_by UUID,
    created_at TIMESTAMP,
    last_message_at TIMESTAMP,
    
    -- Group-specific fields
    group_name TEXT,
    group_description TEXT,
    admin_users SET<UUID>
);

-- Messages (partitioned by conversation for performance)
CREATE TABLE messages (
    conversation_id UUID,
    message_id UUID,
    sender_id UUID,
    message_type VARCHAR(20),       -- 'text', 'image', 'video', 'file'
    content TEXT,                   -- Encrypted message content
    media_url TEXT,                 -- S3 URL for media files
    timestamp TIMESTAMP,
    
    -- Message status tracking
    delivered_to SET<UUID>,         -- Users who received the message
    read_by SET<UUID>,              -- Users who read the message
    
    PRIMARY KEY (conversation_id, timestamp, message_id)
) WITH CLUSTERING ORDER BY (timestamp DESC);

-- User conversations (for efficient conversation listing)
CREATE TABLE user_conversations (
    user_id UUID,
    conversation_id UUID,
    last_read_message_id UUID,
    unread_count INT DEFAULT 0,
    is_muted BOOLEAN DEFAULT false,
    joined_at TIMESTAMP,
    
    PRIMARY KEY (user_id, conversation_id)
);
```

### Redis Cache Strategy:

```javascript
// User presence and online status
"user_presence:{user_id}": {
  "status": "online",
  "last_seen": "2024-01-01T12:00:00Z",
  "device_id": "mobile_ios_123",
  "ttl": 300  // 5 minutes
}

// Active WebSocket connections
"user_connections:{user_id}": {
  "connections": [
    {"device_id": "mobile_123", "gateway_server": "ws-gateway-01"},
    {"device_id": "web_456", "gateway_server": "ws-gateway-03"}
  ],
  "ttl": 3600
}

// Recent conversations cache
"user_conversations:{user_id}": {
  "conversations": [
    {
      "conversation_id": "uuid",
      "last_message": "Hey, how are you?",
      "timestamp": "2024-01-01T12:00:00Z",
      "unread_count": 2
    }
  ],
  "ttl": 1800  // 30 minutes
}

// Message delivery queue (for offline users)
"offline_messages:{user_id}": {
  "messages": [
    {"message_id": "uuid", "sender": "uuid", "content": "encrypted_text"}
  ],
  "ttl": 604800  // 7 days
}
```

### Design Decisions:
- **Cassandra for messages**: Handles billions of messages with time-series data
- **Redis for real-time**: User presence, connections, message queuing
- **Partitioning by conversation**: Efficient message retrieval and storage
- **Denormalized data**: User conversations table for fast conversation listing

---

## Phase 5: Critical Flow - Send Message (8 minutes)

### Most Critical Flow: Real-time Message Delivery

**1. Message Send Request**
```
User types message → WebSocket → Gateway Server

WebSocket message:
{
  "type": "send_message",
  "conversation_id": "uuid",
  "content": "Hello, how are you?",
  "message_type": "text",
  "timestamp": 1704110400000
}
```

**2. Message Processing**
```
WebSocket Gateway processing:

1. Validate user authentication and WebSocket connection
2. Check rate limiting (prevent spam)
3. Route to Message Service for processing
4. Generate unique message_id
5. Encrypt message content for E2E encryption
```

**3. Message Persistence & Routing**
```
Message Service processing:

1. Store message in Cassandra with conversation_id partition
2. Update conversation's last_message_at timestamp
3. Update user_conversations table for all participants
4. Add message to Kafka stream for async processing
```

**4. Real-time Delivery**
```
Message delivery to recipients:

1. Look up recipient user_ids from conversation
2. Check Redis for active connections of each recipient
3. Route message to appropriate WebSocket gateways
4. Send message through WebSocket connections
5. Track delivery status in message record
```

**5. Offline User Handling**
```
For offline recipients:

1. Add message to Redis offline_messages queue
2. Trigger push notification via Notification Service
3. Store notification payload with message preview
4. When user comes online, deliver queued messages
```

### Technical Challenges:

**Connection Management:**
- "Load balance 100M+ WebSocket connections across gateway servers"
- "Maintain connection registry in Redis for message routing"

**Message Ordering:**
- "Use timestamp-based ordering with tie-breaking by message_id"
- "Cassandra clustering ensures chronological message storage"

**Delivery Guarantees:**
- "At-least-once delivery with idempotent message handling"
- "Message deduplication using message_id on client side"

**Group Message Optimization:**
- "Fan-out on write: Send to all group members immediately"
- "Batch delivery to reduce database writes"

**End-to-End Encryption:**
- "Messages encrypted on sender device, decrypted on recipient device"
- "Key exchange using Signal Protocol or similar"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **WebSocket connections**: Limited by server memory and network
2. **Message fan-out**: Group messages to thousands of users
3. **Database writes**: 580K messages/second write load
4. **Hot conversations**: Popular group chats creating hotspots

### Scaling Solutions:

**Connection Scaling:**
```
- Horizontal scaling: Multiple WebSocket gateway servers
- Connection sharding: Distribute users across gateway servers
- Connection pooling: Efficient server resource utilization
- Auto-scaling: Scale gateways based on active connections
```

**Database Scaling:**
```
- Cassandra cluster: Multi-datacenter replication
- Partition by conversation_id: Distribute write load
- Read replicas: Handle conversation listing queries
- Data archiving: Move old messages to cold storage
```

**Message Delivery Optimization:**
```
- Kafka partitioning: Parallel message processing
- Batch processing: Group operations for efficiency
- Priority queues: VIP users get faster delivery
- Geographic distribution: Regional message routing
```

**Performance Improvements:**
```
- CDN for media: Global content delivery network
- Message compression: Reduce bandwidth usage
- Connection keep-alive: Efficient WebSocket management
- Caching layers: Multi-level caching strategy
```

### Trade-offs:
- **Consistency vs Availability**: Eventual consistency for message delivery
- **Storage vs Performance**: Denormalized data for faster queries
- **Security vs Latency**: E2E encryption adds processing overhead

---

## Success Metrics:
- **Message Delivery Time**: <100ms P95 for real-time messages
- **Connection Success Rate**: >99.9% WebSocket connection establishment
- **Message Delivery Rate**: >99.99% successful message delivery
- **System Availability**: 99.99% uptime
- **Concurrent Users**: Handle 100M+ simultaneous connections

**🎯 This design demonstrates real-time systems expertise, WebSocket management, distributed messaging, and handling massive scale while maintaining low latency.**
