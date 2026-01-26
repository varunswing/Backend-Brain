# WhatsApp - High Level Design

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

1. **One-to-One Messaging**
   - Send text messages
   - Send media (images, videos, documents, voice notes)
   - Message delivery status (sent, delivered, read)
   - Reply to specific messages

2. **Group Messaging**
   - Create groups (up to 1024 members)
   - Send messages to groups
   - Group admin controls
   - Add/remove members

3. **User Presence**
   - Online/offline status
   - Last seen timestamp
   - Typing indicators

4. **Media Sharing**
   - Send images, videos, audio, documents
   - Compress media for efficient transfer
   - View once media

5. **Voice/Video Calls**
   - One-to-one calls
   - Group calls
   - Call encryption

6. **End-to-End Encryption**
   - All messages encrypted
   - Server cannot read content
   - Key exchange for new devices

### Non-Functional Requirements

1. **Scale**: 2B+ users, 100B+ messages/day
2. **Availability**: 99.99% uptime
3. **Latency**: Message delivery < 500ms (when recipient online)
4. **Durability**: Messages never lost
5. **Security**: End-to-end encryption for all communications
6. **Ordering**: Messages must maintain order within conversation

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Total Users: 2 Billion
- Daily Active Users (DAU): 1.5 Billion
- Concurrent users at peak: 500 Million

Messages:
- Messages per day: 100 Billion
- Messages per second: 100B / 86400 = 1.16 Million msg/sec
- Peak: 2-3x average = 3 Million msg/sec

Message Breakdown:
- Text messages: 70% (70B/day)
- Media messages: 20% (20B/day)
- Voice messages: 10% (10B/day)

Groups:
- Active groups: 500 Million
- Average group size: 15 members
- Group messages: 40% of total messages
```

### Storage Estimates

```
Message Storage:
- Average text message: 200 bytes (with metadata)
- Text messages/day: 70B * 200 bytes = 14 TB/day
- Retention: 30 days on server (until delivered)

Media Storage:
- Average image: 200 KB (compressed)
- Average video: 5 MB (compressed)
- Images/day: 15B * 200 KB = 3 PB/day
- Videos/day: 5B * 5 MB = 25 PB/day
- Total media/day: ~28 PB

User Metadata:
- Per user: 1 KB
- Total: 2B * 1 KB = 2 TB

Connection Metadata:
- Per connection: 500 bytes
- 500M concurrent: 250 GB
```

### Bandwidth Estimates

```
Incoming Messages:
- Text: 1.16M/sec * 200 bytes = 232 MB/sec
- Media: ~3 TB/sec (peak)

Outgoing Messages:
- Similar to incoming (delivery)
- Plus: group fanout multiplier (~15x for groups)

Total Bandwidth: ~10+ TB/sec at peak
```

### WebSocket Connections

```
Concurrent Connections: 500 Million
- Each connection: ~2 KB memory
- Total memory: 500M * 2 KB = 1 TB

Connection Servers:
- Each server handles ~100K connections
- Servers needed: 500M / 100K = 5,000 servers
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                            │
│                         (iOS, Android, Web, Desktop)                                │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        │ WebSocket / HTTP
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LOAD BALANCER (L4)                                      │
│                            (TCP level for WebSocket)                                 │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                        │
                    ┌───────────────────┴───────────────────┐
                    │                                       │
                    ▼                                       ▼
┌───────────────────────────────────────┐   ┌───────────────────────────────────────┐
│        CONNECTION SERVERS              │   │         API SERVERS                   │
│       (WebSocket Handlers)             │   │     (HTTP REST Endpoints)            │
│                                        │   │                                       │
│  ┌──────────┐  ┌──────────┐           │   │  - User registration                 │
│  │  Server  │  │  Server  │  ...      │   │  - Media upload                      │
│  │    1     │  │    2     │           │   │  - Group management                  │
│  │  100K    │  │  100K    │           │   │  - Profile updates                   │
│  │  conns   │  │  conns   │           │   │                                       │
│  └──────────┘  └──────────┘           │   │                                       │
└───────────────────┬───────────────────┘   └───────────────────────────────────────┘
                    │
                    │ Connection events
                    ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          SESSION MANAGEMENT SERVICE                                  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         REDIS CLUSTER                                        │   │
│  │   user_id → {server_id, connection_id, device_id, last_seen}               │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE QUEUE (Kafka)                                   │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐               │
│   │  Messages   │  │  Presence   │  │   Media     │  │   Calls     │               │
│   │   Topic     │  │   Topic     │  │   Topic     │  │   Topic     │               │
│   └─────────────┘  └─────────────┘  └─────────────┘  └─────────────┘               │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                        │
         ┌──────────────────────────────┼──────────────────────────────┐
         ▼                              ▼                              ▼
┌─────────────────────┐   ┌─────────────────────────┐   ┌─────────────────────┐
│  MESSAGE SERVICE    │   │   PRESENCE SERVICE      │   │   MEDIA SERVICE     │
│                     │   │                         │   │                     │
│ - Message routing   │   │ - Online/offline        │   │ - Upload handling   │
│ - Delivery tracking │   │ - Last seen             │   │ - Compression       │
│ - Group fanout      │   │ - Typing indicators     │   │ - CDN distribution  │
└─────────┬───────────┘   └─────────────────────────┘   └─────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├─────────────────┬─────────────────┬─────────────────┬───────────────────────────────┤
│   MESSAGE DB    │   USER DB       │   MEDIA STORE   │      PRESENCE CACHE          │
│   (Cassandra)   │  (PostgreSQL)   │     (S3/CDN)    │        (Redis)               │
│                 │                 │                 │                               │
│ - Messages      │ - User profiles │ - Images        │ - Online status              │
│ - Delivery      │ - Contacts      │ - Videos        │ - Last seen                  │
│   receipts      │ - Groups        │ - Documents     │ - Typing                     │
│ - Read receipts │ - Keys          │ - Voice notes   │                               │
└─────────────────┴─────────────────┴─────────────────┴───────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              PUSH NOTIFICATION SERVICE                               │
│                           (For offline message delivery)                             │
│   ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐        │
│   │        APNs         │  │        FCM          │  │    Web Push         │        │
│   │      (iOS)          │  │     (Android)       │  │                     │        │
│   └─────────────────────┘  └─────────────────────┘  └─────────────────────┘        │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Services

1. **Connection Service**: Manages WebSocket connections
2. **Message Service**: Handles message routing and delivery
3. **Presence Service**: Tracks user online status
4. **Group Service**: Manages group operations
5. **Media Service**: Handles media upload/download
6. **Call Service**: Manages voice/video calls
7. **Notification Service**: Sends push notifications

---

## Request Flows

### Message Sending Flow (Recipient Online)

```
┌────────┐  ┌───────────┐  ┌───────────┐  ┌─────────┐  ┌───────────┐  ┌────────┐
│Sender  │  │Connection │  │  Message  │  │ Session │  │Connection │  │Recipient│
│Client  │  │  Server   │  │  Service  │  │  Cache  │  │  Server   │  │ Client │
└───┬────┘  └─────┬─────┘  └─────┬─────┘  └────┬────┘  └─────┬─────┘  └───┬────┘
    │             │              │             │             │             │
    │ Send Msg    │              │             │             │             │
    │ (encrypted) │              │             │             │             │
    │────────────>│              │             │             │             │
    │             │ Forward Msg  │             │             │             │
    │             │─────────────>│             │             │             │
    │             │              │ Store Msg   │             │             │
    │             │              │─────────────────(DB)      │             │
    │             │              │             │             │             │
    │             │              │ Get recipient server      │             │
    │             │              │────────────>│             │             │
    │             │              │             │             │             │
    │             │              │<────────────│             │             │
    │             │              │             │             │             │
    │             │              │ Route to server           │             │
    │             │              │───────────────────────────>│             │
    │             │              │             │             │ Push Msg    │
    │             │              │             │             │────────────>│
    │             │              │             │             │             │
    │             │              │             │             │ Delivery ACK│
    │             │              │             │             │<────────────│
    │             │              │<───────────────────────────│             │
    │             │<─────────────│             │             │             │
    │ Delivered ✓ │              │             │             │             │
    │<────────────│              │             │             │             │
    │             │              │             │             │             │
    │             │              │             │             │ Read ACK    │
    │             │              │             │             │<────────────│
    │             │              │<───────────────────────────│             │
    │             │<─────────────│             │             │             │
    │ Read ✓✓     │              │             │             │             │
    │<────────────│              │             │             │             │
```

### Message Sending Flow (Recipient Offline)

```
┌────────┐  ┌───────────┐  ┌───────────┐  ┌─────────┐  ┌───────────┐  ┌────────┐
│Sender  │  │Connection │  │  Message  │  │ Session │  │   Push    │  │Recipient│
│Client  │  │  Server   │  │  Service  │  │  Cache  │  │  Service  │  │ Client │
└───┬────┘  └─────┬─────┘  └─────┬─────┘  └────┬────┘  └─────┬─────┘  └───┬────┘
    │             │              │             │             │             │
    │ Send Msg    │              │             │             │             │
    │────────────>│              │             │             │             │
    │             │─────────────>│             │             │             │
    │             │              │ Store Msg   │             │             │
    │             │              │─────────────────(DB)      │             │
    │             │              │             │             │             │
    │             │              │ Check status│             │             │
    │             │              │────────────>│             │             │
    │             │              │ OFFLINE     │             │             │
    │             │              │<────────────│             │             │
    │             │              │             │             │             │
    │             │              │ Send Push   │             │             │
    │             │              │───────────────────────────>│             │
    │             │              │             │             │ Push Notif  │
    │             │              │             │             │────────────>│
    │             │<─────────────│             │             │             │
    │ Sent ✓      │              │             │             │             │
    │<────────────│              │             │             │             │
    │             │              │             │             │             │
    │             │              │     (Recipient comes online later)      │
    │             │              │             │             │             │
    │             │              │             │             │ Sync Msgs   │
    │             │              │<─────────────────────────────────────────│
    │             │              │             │             │             │
    │             │              │ Return pending messages   │             │
    │             │              │─────────────────────────────────────────>│
```

### Group Message Flow

```
┌────────┐  ┌───────────┐  ┌───────────┐  ┌─────────┐  ┌───────────────────────────┐
│Sender  │  │Connection │  │  Message  │  │ Group   │  │     RECIPIENTS            │
│Client  │  │  Server   │  │  Service  │  │ Service │  │ (Multiple Users)          │
└───┬────┘  └─────┬─────┘  └─────┬─────┘  └────┬────┘  └───────────────────────────┘
    │             │              │             │                    │
    │ Send Group  │              │             │                    │
    │ Message     │              │             │                    │
    │────────────>│              │             │                    │
    │             │─────────────>│             │                    │
    │             │              │ Get Group   │                    │
    │             │              │ Members     │                    │
    │             │              │────────────>│                    │
    │             │              │             │                    │
    │             │              │<────────────│                    │
    │             │              │ [user1, user2, ..., userN]      │
    │             │              │             │                    │
    │             │              │ For each member:                 │
    │             │              │ ┌─────────────────────────────┐  │
    │             │              │ │ 1. Check online status      │  │
    │             │              │ │ 2. If online: route to WS   │  │
    │             │              │ │ 3. If offline: store + push │  │
    │             │              │ └─────────────────────────────┘  │
    │             │              │             │                    │
    │             │              │ Parallel Fanout ─────────────────>
    │             │              │             │                    │
    │             │<─────────────│             │                    │
    │ Sent ✓      │              │             │                    │
    │<────────────│              │             │                    │
```

---

## Detailed Component Design

### 1. Connection Service (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          CONNECTION SERVICE ARCHITECTURE                             │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Connection Lifecycle:                                                              │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  1. Client initiates WebSocket connection                                   │   │
│  │     └── TLS handshake + HTTP upgrade                                        │   │
│  │                                                                              │   │
│  │  2. Authentication                                                           │   │
│  │     └── Verify JWT token                                                     │   │
│  │     └── Validate device ID                                                   │   │
│  │                                                                              │   │
│  │  3. Session Registration                                                     │   │
│  │     └── Register in Redis: user_id → {server_id, conn_id, device_id}       │   │
│  │     └── Mark user as online                                                  │   │
│  │                                                                              │   │
│  │  4. Connection Active                                                        │   │
│  │     └── Heartbeat every 30 seconds                                          │   │
│  │     └── Receive and forward messages                                         │   │
│  │                                                                              │   │
│  │  5. Disconnection                                                            │   │
│  │     └── Remove from session registry                                         │   │
│  │     └── Update last seen                                                     │   │
│  │     └── Mark offline (after grace period)                                   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Server Architecture:                                                               │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │                    CONNECTION SERVER (per instance)                          │   │
│  │                                                                              │   │
│  │  ┌────────────────────────────────────────────────────────────────────┐     │   │
│  │  │                     EPOLL EVENT LOOP                                │     │   │
│  │  │                  (Non-blocking I/O)                                 │     │   │
│  │  └────────────────────────────────────────────────────────────────────┘     │   │
│  │                              │                                               │   │
│  │              ┌───────────────┼───────────────┐                              │   │
│  │              ▼               ▼               ▼                              │   │
│  │         Connection 1    Connection 2    Connection N                        │   │
│  │              │               │               │                              │   │
│  │              └───────────────┼───────────────┘                              │   │
│  │                              │                                               │   │
│  │  ┌────────────────────────────────────────────────────────────────────┐     │   │
│  │  │                    CONNECTION MAP                                   │     │   │
│  │  │         user_id → WebSocket connection object                      │     │   │
│  │  │         (Local to this server, 100K entries max)                   │     │   │
│  │  └────────────────────────────────────────────────────────────────────┘     │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class ConnectionServer:
    def __init__(self, server_id: str):
        self.server_id = server_id
        self.connections: Dict[str, WebSocket] = {}  # user_id → websocket
        self.redis = Redis()
        self.kafka = KafkaProducer()
        
    async def handle_connection(self, websocket: WebSocket, user_id: str, device_id: str):
        # Register connection
        await self.register_session(user_id, device_id)
        self.connections[user_id] = websocket
        
        try:
            # Send pending messages
            await self.sync_pending_messages(user_id)
            
            # Handle incoming messages
            async for message in websocket:
                await self.handle_message(user_id, message)
                
        except WebSocketDisconnect:
            pass
        finally:
            await self.handle_disconnect(user_id)
    
    async def register_session(self, user_id: str, device_id: str):
        session_data = {
            'server_id': self.server_id,
            'device_id': device_id,
            'connected_at': datetime.utcnow().isoformat()
        }
        
        # Store session with TTL (refreshed by heartbeat)
        await self.redis.hset(f"session:{user_id}", device_id, json.dumps(session_data))
        await self.redis.expire(f"session:{user_id}", 120)  # 2 minute TTL
        
        # Mark user as online
        await self.redis.set(f"online:{user_id}", "1", ex=120)
        
        # Broadcast presence to contacts
        await self.broadcast_presence(user_id, "online")
    
    async def send_to_user(self, user_id: str, message: dict):
        if user_id in self.connections:
            # User is connected to this server
            await self.connections[user_id].send(json.dumps(message))
            return True
        return False
    
    async def handle_disconnect(self, user_id: str):
        # Remove from local connections
        self.connections.pop(user_id, None)
        
        # Update Redis (don't immediately mark offline - grace period)
        await self.redis.setex(f"last_seen:{user_id}", 86400, datetime.utcnow().isoformat())
        
        # Schedule offline check after grace period
        await self.kafka.send("presence_checks", {
            "user_id": user_id,
            "check_at": (datetime.utcnow() + timedelta(seconds=30)).isoformat()
        })
```

### 2. Message Delivery System (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          MESSAGE DELIVERY SYSTEM                                     │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Message States:                                                                    │
│                                                                                     │
│  ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐                          │
│  │ PENDING │───>│  SENT   │───>│DELIVERED│───>│  READ   │                          │
│  └─────────┘    └─────────┘    └─────────┘    └─────────┘                          │
│       │                              │              │                               │
│       │                              │              │                               │
│       ▼                              ▼              ▼                               │
│  Stored in DB              Server received     Recipient                            │
│  waiting for               from server         device ACK'd                         │
│  delivery                                                                           │
│                                                                                     │
│  Message Storage (Cassandra):                                                       │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  CREATE TABLE messages (                                                     │   │
│  │      conversation_id UUID,                                                   │   │
│  │      message_id TIMEUUID,                                                   │   │
│  │      sender_id UUID,                                                        │   │
│  │      recipient_id UUID,         -- NULL for groups                          │   │
│  │      encrypted_content BLOB,    -- E2E encrypted                            │   │
│  │      message_type TEXT,         -- text, image, video, etc.                 │   │
│  │      created_at TIMESTAMP,                                                  │   │
│  │      PRIMARY KEY ((conversation_id), message_id)                            │   │
│  │  ) WITH CLUSTERING ORDER BY (message_id DESC);                              │   │
│  │                                                                              │   │
│  │  CREATE TABLE pending_messages (                                             │   │
│  │      recipient_id UUID,                                                     │   │
│  │      message_id TIMEUUID,                                                   │   │
│  │      conversation_id UUID,                                                  │   │
│  │      PRIMARY KEY ((recipient_id), message_id)                               │   │
│  │  ) WITH default_time_to_live = 2592000;  -- 30 days                         │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Delivery Algorithm:                                                                │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  1. Receive message from sender                                             │   │
│  │  2. Validate and store in messages table                                    │   │
│  │  3. Add to pending_messages for recipient                                   │   │
│  │  4. Check recipient online status in Redis                                  │   │
│  │     │                                                                       │   │
│  │     ├── ONLINE: Get server_id from session, route message                  │   │
│  │     │           └── On delivery ACK: Remove from pending_messages          │   │
│  │     │                                                                       │   │
│  │     └── OFFLINE: Send push notification                                    │   │
│  │                  └── On next connect: Sync all pending_messages            │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class MessageService:
    def __init__(self):
        self.cassandra = CassandraClient()
        self.redis = Redis()
        self.kafka = KafkaProducer()
        
    async def send_message(self, message: Message) -> MessageStatus:
        # Store message
        await self.store_message(message)
        
        # Add to pending for recipient
        await self.add_to_pending(message.recipient_id, message.id, message.conversation_id)
        
        # Try to deliver
        delivery_result = await self.attempt_delivery(message)
        
        if delivery_result.delivered:
            # Remove from pending
            await self.remove_from_pending(message.recipient_id, message.id)
            return MessageStatus.DELIVERED
        else:
            # Send push notification
            await self.send_push_notification(message)
            return MessageStatus.SENT
    
    async def attempt_delivery(self, message: Message) -> DeliveryResult:
        # Check if recipient is online
        session = await self.redis.hgetall(f"session:{message.recipient_id}")
        
        if not session:
            return DeliveryResult(delivered=False, reason="offline")
        
        # Get the server handling this user
        server_id = session.get('server_id')
        
        # Route message to that server via Kafka
        await self.kafka.send(f"server_{server_id}_messages", {
            "type": "deliver",
            "user_id": message.recipient_id,
            "message": message.to_dict()
        })
        
        # Wait for delivery ACK (with timeout)
        try:
            ack = await asyncio.wait_for(
                self.wait_for_ack(message.id),
                timeout=5.0
            )
            return DeliveryResult(delivered=True)
        except asyncio.TimeoutError:
            return DeliveryResult(delivered=False, reason="timeout")
    
    async def sync_pending_messages(self, user_id: str):
        """Called when user comes online"""
        pending = await self.cassandra.execute("""
            SELECT message_id, conversation_id 
            FROM pending_messages 
            WHERE recipient_id = %s
            ORDER BY message_id ASC
        """, (user_id,))
        
        for row in pending:
            message = await self.get_message(row.conversation_id, row.message_id)
            yield message
```

### 3. End-to-End Encryption (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          END-TO-END ENCRYPTION                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Protocol: Signal Protocol (Double Ratchet)                                         │
│                                                                                     │
│  Key Types:                                                                         │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Identity Key (IK):     Long-term key pair, unique per user                 │   │
│  │  Signed Pre-Key (SPK):  Medium-term key, rotated periodically               │   │
│  │  One-Time Pre-Keys:     Single-use keys for initial key exchange            │   │
│  │  Session Keys:          Derived keys for each conversation                   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Initial Key Exchange (X3DH):                                                       │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Alice wants to message Bob:                                                │   │
│  │                                                                              │   │
│  │  1. Alice fetches from server:                                              │   │
│  │     - Bob's Identity Key (IKB)                                              │   │
│  │     - Bob's Signed Pre-Key (SPKB)                                           │   │
│  │     - One of Bob's One-Time Pre-Keys (OPKB)                                 │   │
│  │                                                                              │   │
│  │  2. Alice generates ephemeral key pair (EKA)                                │   │
│  │                                                                              │   │
│  │  3. Alice computes shared secret:                                           │   │
│  │     DH1 = DH(IKA, SPKB)                                                     │   │
│  │     DH2 = DH(EKA, IKB)                                                      │   │
│  │     DH3 = DH(EKA, SPKB)                                                     │   │
│  │     DH4 = DH(EKA, OPKB)                                                     │   │
│  │     SK = KDF(DH1 || DH2 || DH3 || DH4)                                      │   │
│  │                                                                              │   │
│  │  4. Alice sends: IKA, EKA, OPKB_id, encrypted_message                       │   │
│  │                                                                              │   │
│  │  5. Bob receives and derives same SK using his private keys                 │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Double Ratchet (Ongoing Messages):                                                 │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Each message:                                                               │   │
│  │  1. Derive new message key from chain key (KDF ratchet)                     │   │
│  │  2. Encrypt message with message key                                        │   │
│  │  3. Delete message key after use                                            │   │
│  │                                                                              │   │
│  │  Periodically (on reply):                                                    │   │
│  │  1. Generate new DH key pair                                                │   │
│  │  2. Perform DH ratchet to derive new chain key                              │   │
│  │                                                                              │   │
│  │  Forward Secrecy: Compromised keys don't reveal past messages               │   │
│  │  Break-in Recovery: New keys generated frequently                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Server's Role:                                                                     │
│  - Stores public keys only                                                         │
│  - Routes encrypted messages                                                        │
│  - Cannot decrypt message content                                                   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation (Simplified):**

```python
class E2EEncryption:
    def __init__(self, user_id: str):
        self.user_id = user_id
        self.identity_key = self.load_or_generate_identity_key()
        self.signed_prekey = self.generate_signed_prekey()
        self.sessions: Dict[str, Session] = {}
    
    async def initialize_session(self, recipient_id: str):
        """X3DH key exchange to establish session"""
        # Fetch recipient's keys from server
        keys = await self.fetch_prekeys(recipient_id)
        
        # Generate ephemeral key
        ephemeral_key = generate_keypair()
        
        # X3DH agreement
        dh1 = dh(self.identity_key.private, keys.signed_prekey)
        dh2 = dh(ephemeral_key.private, keys.identity_key)
        dh3 = dh(ephemeral_key.private, keys.signed_prekey)
        dh4 = dh(ephemeral_key.private, keys.one_time_prekey)
        
        shared_secret = kdf(dh1 + dh2 + dh3 + dh4)
        
        # Initialize session
        session = Session(
            recipient_id=recipient_id,
            root_key=shared_secret,
            sending_chain_key=derive_chain_key(shared_secret, "sending"),
            receiving_chain_key=None  # Set when receiving
        )
        
        self.sessions[recipient_id] = session
        return session, ephemeral_key.public
    
    def encrypt_message(self, recipient_id: str, plaintext: bytes) -> bytes:
        session = self.sessions[recipient_id]
        
        # Derive message key from chain key
        message_key, new_chain_key = kdf_chain(session.sending_chain_key)
        session.sending_chain_key = new_chain_key
        
        # Encrypt with AES-256-GCM
        nonce = generate_nonce()
        ciphertext = aes_gcm_encrypt(plaintext, message_key, nonce)
        
        # Delete message key (forward secrecy)
        del message_key
        
        return nonce + ciphertext
    
    def decrypt_message(self, sender_id: str, ciphertext: bytes) -> bytes:
        session = self.sessions[sender_id]
        
        # Extract nonce
        nonce = ciphertext[:12]
        encrypted = ciphertext[12:]
        
        # Derive message key
        message_key, new_chain_key = kdf_chain(session.receiving_chain_key)
        session.receiving_chain_key = new_chain_key
        
        # Decrypt
        plaintext = aes_gcm_decrypt(encrypted, message_key, nonce)
        
        del message_key
        return plaintext
```

---

## Trade-offs and Tech Choices

### 1. WebSocket vs HTTP Polling

| Aspect | WebSocket | HTTP Polling |
|--------|-----------|--------------|
| Latency | Real-time | Polling interval delay |
| Server resources | 1 connection per user | Many short connections |
| Battery (mobile) | Keep-alive drain | Periodic wake-ups |
| Scalability | 100K conns/server | More requests/sec |

**Decision:** WebSocket for real-time delivery, with HTTP fallback

### 2. Message Storage

| Option | Pros | Cons |
|--------|------|------|
| PostgreSQL | ACID, familiar | Scaling writes difficult |
| Cassandra | High write throughput, partition by conversation | Eventual consistency |
| ScyllaDB | Cassandra-compatible, faster | Newer, less ecosystem |

**Decision:** Cassandra for messages (write-heavy, time-series-like)

### 3. Push Notification Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                PUSH NOTIFICATION STRATEGY                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  When to send push:                                             │
│  - User offline AND message pending > 5 seconds                 │
│  - Batch multiple messages into one push                        │
│                                                                 │
│  Content:                                                        │
│  - "You have N new messages" (E2E means we can't show content)  │
│  - Or: "Message from [Contact Name]" (if allowed by settings)  │
│                                                                 │
│  Rate limiting:                                                  │
│  - Max 1 push per conversation per minute                       │
│  - Silent pushes for sync triggers                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4. Group Message Delivery

| Approach | Pros | Cons |
|----------|------|------|
| Fan-out on send | Simple, immediate | N messages stored |
| Single storage + pointer | Efficient storage | Complex delivery |
| Hybrid | Best of both | Implementation complexity |

**Decision:** Hybrid - Store once, create delivery records per member

---

## Failure Scenarios and Bottlenecks

### 1. Connection Server Failure

```
┌─────────────────────────────────────────────────────────────────┐
│               CONNECTION SERVER FAILURE HANDLING                 │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Detection:                                                      │
│  - Health check failure (3 consecutive)                         │
│  - Session TTL expiration (no heartbeat refresh)                │
│                                                                 │
│  Recovery:                                                       │
│  1. Load balancer marks server unhealthy                        │
│  2. Clients detect disconnect                                   │
│  3. Clients reconnect to different server (LB routes)           │
│  4. New server registers session                                │
│  5. Sync pending messages                                       │
│                                                                 │
│  Data Loss Prevention:                                           │
│  - All messages persisted to Cassandra before ACK               │
│  - Pending messages table survives server failure               │
│  - Client retries on disconnect                                 │
│                                                                 │
│  Recovery Time: ~5-10 seconds (client reconnect)                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Message Queue Backlog

```
Problem: Kafka consumer lag > 100K messages

Mitigation:
1. Auto-scale consumers based on lag
2. Priority queues (DMs > Group messages)
3. Batch processing for groups
4. Backpressure on producers if critical

Monitoring:
- Alert at lag > 10K
- Critical at lag > 100K
- Auto-scale triggers at lag > 5K
```

### 3. Hot Group Problem

```
Problem: Group with 1000 members, high message frequency

Solution:
┌─────────────────────────────────────────────────────────────────┐
│                    HOT GROUP HANDLING                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Detection:                                                      │
│  - Track message rate per group                                 │
│  - Threshold: > 100 messages/minute                             │
│                                                                 │
│  Optimization:                                                   │
│  1. Dedicated partition in Kafka for hot groups                 │
│  2. Batch message delivery (every 1 second instead of instant)  │
│  3. Collapse notifications ("99+ new messages")                 │
│  4. Lazy loading of message content                             │
│                                                                 │
│  Trade-off: Slight delivery delay for very active groups        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 4. Database Overload

```
Problem: Write throughput exceeds Cassandra capacity

Solution:
1. Add more nodes to cluster (linear scaling)
2. Optimize partition keys (avoid hot partitions)
3. Adjust consistency level (QUORUM → LOCAL_ONE for writes)
4. Enable write-behind caching for non-critical data
```

---

## Future Improvements

### 1. Multi-Device Support

```
┌─────────────────────────────────────────────────────────────────┐
│                    MULTI-DEVICE ARCHITECTURE                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Challenge: E2E encryption with multiple devices                │
│                                                                 │
│  Solution: Per-device encryption keys                           │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                                                          │   │
│  │  User A has 3 devices:                                   │   │
│  │  - Phone: Identity Key A1                                │   │
│  │  - Tablet: Identity Key A2                               │   │
│  │  - Desktop: Identity Key A3                              │   │
│  │                                                          │   │
│  │  User B sends message:                                   │   │
│  │  - Encrypt once for each of A's devices                 │   │
│  │  - 3 encrypted copies with different keys               │   │
│  │                                                          │   │
│  │  Key verification:                                       │   │
│  │  - QR code scan between devices                         │   │
│  │  - Safety numbers comparison                             │   │
│  │                                                          │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Message Reactions & Editing

- Reaction messages (like email threads)
- Edit window (15 minutes after send)
- Delete for everyone
- Quoted replies

### 3. Large Group Optimization

- Communities (hierarchical groups)
- Announcement channels (one-way broadcast)
- Group call improvements (selective forwarding)

---

## Interviewer Questions & Answers

### Q1: How do you ensure message delivery when the recipient is offline?

**Answer:**

**Storage & Retry Architecture:**

1. **On message send:**
   ```python
   async def send_message(message):
       # Always persist first
       await cassandra.insert_message(message)
       
       # Add to pending queue for recipient
       await cassandra.insert_pending(message.recipient_id, message.id)
       
       # Check if recipient is online
       if await redis.exists(f"online:{message.recipient_id}"):
           # Attempt immediate delivery
           delivered = await attempt_delivery(message)
           if delivered:
               await cassandra.delete_pending(message.recipient_id, message.id)
       
       # Either way, send push notification after delay
       await schedule_push_notification(message, delay=5)
   ```

2. **On recipient reconnect:**
   ```python
   async def on_user_connect(user_id):
       # Fetch all pending messages
       pending = await cassandra.get_pending_messages(user_id)
       
       # Deliver in order
       for msg_id in pending:
           message = await cassandra.get_message(msg_id)
           await websocket.send(message)
           
           # Wait for delivery ACK
           ack = await wait_for_ack(msg_id, timeout=10)
           if ack:
               await cassandra.delete_pending(user_id, msg_id)
   ```

3. **Retention:**
   - Pending messages kept for 30 days
   - After 30 days, marked as expired
   - Sender notified if message couldn't be delivered

---

### Q2: How would you design the group messaging feature?

**Answer:**

**Data Model:**
```sql
-- Groups table
CREATE TABLE groups (
    group_id UUID PRIMARY KEY,
    name TEXT,
    created_by UUID,
    created_at TIMESTAMP
);

-- Group members
CREATE TABLE group_members (
    group_id UUID,
    user_id UUID,
    role TEXT,  -- admin, member
    joined_at TIMESTAMP,
    PRIMARY KEY ((group_id), user_id)
);

-- Reverse lookup
CREATE TABLE user_groups (
    user_id UUID,
    group_id UUID,
    PRIMARY KEY ((user_id), group_id)
);
```

**Message Delivery:**
```python
async def send_group_message(group_id, sender_id, message):
    # Get group members
    members = await cassandra.get_group_members(group_id)
    
    # Store message once
    message_id = await cassandra.insert_group_message(group_id, message)
    
    # Fan-out to members
    for member_id in members:
        if member_id == sender_id:
            continue
            
        # Add to each member's pending queue
        await cassandra.insert_pending(member_id, message_id)
        
        # Attempt delivery if online
        if await is_online(member_id):
            await deliver_to_user(member_id, message)
```

**Optimization for large groups:**
- Batch delivery (collect 1 second of messages, deliver together)
- Lazy loading (send notification, fetch content on view)
- Mute settings respected server-side

---

### Q3: How do you handle message ordering?

**Answer:**

**Challenge:** Network delays can cause messages to arrive out of order

**Solution:**

1. **Server-assigned timestamps:**
   ```python
   async def receive_message(message):
       # Assign server timestamp
       message.server_timestamp = datetime.utcnow()
       
       # Store with server timestamp as sort key
       await cassandra.insert(
           message,
           sort_key=message.server_timestamp
       )
   ```

2. **Client-side ordering:**
   - Display messages sorted by server timestamp
   - Local messages shown immediately (optimistic)
   - Reorder when server timestamp received

3. **Conversation-level sequence numbers:**
   ```python
   async def send_message(conversation_id, message):
       # Get and increment sequence
       seq = await redis.incr(f"seq:{conversation_id}")
       message.sequence = seq
       
       # Use sequence as secondary sort
       # Primary: timestamp, Secondary: sequence
   ```

4. **Handling late arrivals:**
   - If message arrives with older timestamp, insert in correct position
   - Client re-renders conversation if needed
   - Usually within same "batch" (appears natural)

---

### Q4: Explain the end-to-end encryption implementation.

**Answer:**

**Protocol: Signal Protocol (used by WhatsApp)**

**Key Components:**

1. **Identity Keys:**
   - Long-term key pair per user
   - Public key stored on server
   - Private key never leaves device

2. **Session Establishment (X3DH):**
   ```
   Alice wants to message Bob (first time):
   
   1. Alice fetches from server:
      - Bob's Identity Key (IKB)
      - Bob's Signed Pre-Key (SPKB)  
      - One of Bob's One-Time Pre-Keys (OPKB)
   
   2. Alice computes shared secret:
      SK = KDF(DH(IKA, SPKB) || DH(EKA, IKB) || DH(EKA, SPKB) || DH(EKA, OPKB))
   
   3. Alice sends: her IK, ephemeral key, OPKB id, encrypted message
   
   4. Bob computes same SK using his private keys
   ```

3. **Double Ratchet:**
   - Every message uses a new key (derived from chain)
   - Periodically, new DH exchange refreshes root key
   - Compromised key doesn't reveal past or future messages

4. **Server's role:**
   - Store public keys
   - Route encrypted blobs
   - Never sees plaintext

**Group encryption:**
- Sender key protocol
- Sender generates key, shares with all members (encrypted per-member)
- Messages encrypted once, decrypted by all

---

### Q5: How do you implement read receipts?

**Answer:**

**Implementation:**

```python
# When recipient reads message
async def mark_as_read(user_id, conversation_id, message_ids):
    # Update local read state
    await cassandra.update_read_receipts(conversation_id, user_id, message_ids)
    
    # Send read receipt to sender
    for msg_id in message_ids:
        message = await get_message(msg_id)
        
        # Only send if sender wants receipts
        if await user_wants_read_receipts(message.sender_id):
            receipt = ReadReceipt(
                message_id=msg_id,
                read_by=user_id,
                read_at=datetime.utcnow()
            )
            
            await send_to_user(message.sender_id, receipt)
```

**Optimizations:**

1. **Batching:** Send one read receipt for multiple messages
2. **Debouncing:** Wait 2 seconds before sending (user might keep scrolling)
3. **Privacy:** Respect "hide read receipts" setting

**Data model:**
```sql
CREATE TABLE read_receipts (
    conversation_id UUID,
    message_id TIMEUUID,
    user_id UUID,
    read_at TIMESTAMP,
    PRIMARY KEY ((conversation_id), message_id, user_id)
);
```

---

### Q6: How would you implement typing indicators?

**Answer:**

**Requirements:**
- Show when contact is typing
- Minimal latency (feel real-time)
- Minimal server load (very frequent events)

**Implementation:**

```python
# Client-side throttling
class TypingIndicator:
    THROTTLE_MS = 3000  # Send at most every 3 seconds
    
    def on_key_press(self, conversation_id):
        now = time.time()
        if now - self.last_sent > self.THROTTLE_MS / 1000:
            self.send_typing(conversation_id)
            self.last_sent = now

# Server-side
async def handle_typing(user_id, conversation_id):
    # Don't persist - ephemeral
    # Broadcast to conversation participants
    participants = await get_conversation_participants(conversation_id)
    
    for participant_id in participants:
        if participant_id != user_id and await is_online(participant_id):
            await send_to_user(participant_id, {
                "type": "typing",
                "user_id": user_id,
                "conversation_id": conversation_id,
                "expires_in": 5000  # Client hides after 5s if no update
            })
```

**Key decisions:**
- No persistence (ephemeral)
- Client-side throttling (reduce traffic)
- Auto-expire on client (don't need "stopped typing" message)
- Only send to online users (no push)

---

### Q7: How do you handle high availability for the connection servers?

**Answer:**

**Architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│                CONNECTION SERVER HA                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Load Balancer (L4):                                            │
│  - Health checks every 5 seconds                                │
│  - Remove unhealthy servers within 15 seconds                   │
│  - Least-connections routing                                    │
│                                                                 │
│  Server Pool:                                                   │
│  - Minimum 3 servers per availability zone                      │
│  - Auto-scaling based on connection count                       │
│  - Rolling deployments (drain connections first)                │
│                                                                 │
│  Session State:                                                  │
│  - Stored in Redis (not local to server)                        │
│  - Client reconnect picks up where left off                     │
│                                                                 │
│  Graceful Shutdown:                                              │
│  1. Stop accepting new connections                              │
│  2. Send "reconnect" message to all clients                     │
│  3. Wait 30 seconds for clients to migrate                      │
│  4. Force disconnect remaining                                  │
│  5. Shutdown                                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Failure Recovery:**
```python
# Client-side reconnection
class ChatClient:
    async def connect(self):
        while True:
            try:
                await self.websocket.connect(self.server_url)
                await self.sync_messages()
                await self.listen()
            except ConnectionError:
                await asyncio.sleep(self.backoff())  # Exponential backoff
                continue
    
    def backoff(self):
        delay = min(30, 2 ** self.retry_count)
        self.retry_count += 1
        return delay + random.uniform(0, 1)  # Jitter
```

---

### Q8: How would you implement voice/video calls?

**Answer:**

**Architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    CALL ARCHITECTURE                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Protocol: WebRTC                                               │
│                                                                 │
│  Components:                                                     │
│  ├── Signaling Server (existing WebSocket)                      │
│  ├── STUN Servers (NAT traversal)                               │
│  ├── TURN Servers (relay when P2P fails)                        │
│  └── SFU (Selective Forwarding Unit for group calls)           │
│                                                                 │
│  Call Flow (1:1):                                               │
│                                                                 │
│  Alice                Server               Bob                  │
│    │                    │                   │                   │
│    │─── Call Request ───>│                   │                   │
│    │                    │─── Push/WS ───────>│                   │
│    │                    │                   │                   │
│    │                    │<── Accept ─────────│                   │
│    │<── SDP Offer ──────│                   │                   │
│    │                    │                   │                   │
│    │─── SDP Answer ─────>│                   │                   │
│    │                    │─────────────────────>│                   │
│    │                    │                   │                   │
│    │<──────────── P2P Media Stream ───────────>│                   │
│    │                    │                   │                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class CallService:
    async def initiate_call(self, caller_id, callee_id, call_type):
        call_id = generate_uuid()
        
        # Create call record
        call = Call(
            id=call_id,
            caller_id=caller_id,
            callee_id=callee_id,
            type=call_type,
            status="ringing"
        )
        
        # Notify callee
        if await is_online(callee_id):
            await send_to_user(callee_id, {
                "type": "incoming_call",
                "call_id": call_id,
                "caller_id": caller_id,
                "call_type": call_type
            })
        else:
            # VoIP push for iOS
            await send_voip_push(callee_id, call)
        
        # Set timeout (30 seconds to answer)
        await schedule_timeout(call_id, 30)
        
        return call_id
    
    async def exchange_sdp(self, call_id, user_id, sdp):
        call = await get_call(call_id)
        other_user = call.caller_id if user_id == call.callee_id else call.callee_id
        
        await send_to_user(other_user, {
            "type": "sdp",
            "call_id": call_id,
            "sdp": sdp
        })
```

**End-to-end encryption for calls:**
- SRTP (Secure Real-time Transport Protocol)
- Keys derived from existing E2E session
- Server cannot decrypt media

---

### Q9: How do you handle presence (online/offline status)?

**Answer:**

**Architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    PRESENCE SYSTEM                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Storage: Redis                                                  │
│  ├── online:{user_id} = "1" (TTL: 2 minutes)                   │
│  ├── last_seen:{user_id} = timestamp                           │
│  └── presence_subscribers:{user_id} = [user_ids...]            │
│                                                                 │
│  Updates:                                                        │
│  ├── Heartbeat every 30s refreshes TTL                          │
│  ├── Disconnect → wait 30s → mark offline                       │
│  └── Presence change → broadcast to subscribers                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Implementation:**
```python
class PresenceService:
    async def update_presence(self, user_id: str, status: str):
        if status == "online":
            await redis.setex(f"online:{user_id}", 120, "1")
        else:
            await redis.delete(f"online:{user_id}")
            await redis.set(f"last_seen:{user_id}", datetime.utcnow().isoformat())
        
        # Broadcast to interested users
        await self.broadcast_presence_change(user_id, status)
    
    async def broadcast_presence_change(self, user_id: str, status: str):
        # Get users who want to know about this user's status
        # (typically: users whose conversations include this user)
        subscribers = await redis.smembers(f"presence_subscribers:{user_id}")
        
        for subscriber_id in subscribers:
            if await self.is_online(subscriber_id):
                await send_to_user(subscriber_id, {
                    "type": "presence",
                    "user_id": user_id,
                    "status": status,
                    "last_seen": await redis.get(f"last_seen:{user_id}")
                })
    
    async def get_presence(self, user_id: str) -> dict:
        is_online = await redis.exists(f"online:{user_id}")
        last_seen = await redis.get(f"last_seen:{user_id}")
        
        return {
            "online": bool(is_online),
            "last_seen": last_seen
        }
```

**Privacy:**
- Users can hide online status (setting)
- If hidden: always return "last seen: a long time ago"

---

### Q10: How do you scale to handle 100 billion messages per day?

**Answer:**

**Scaling Strategy:**

```
┌─────────────────────────────────────────────────────────────────┐
│                    SCALING ARCHITECTURE                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  100B messages/day = 1.16M messages/second                      │
│                                                                 │
│  Connection Layer:                                              │
│  ├── 500M concurrent connections                                │
│  ├── 100K connections per server                                │
│  ├── 5,000 connection servers                                   │
│  └── Horizontally scalable, stateless                          │
│                                                                 │
│  Message Processing:                                            │
│  ├── Kafka: 1000+ partitions                                   │
│  ├── Consumers: Auto-scaled based on lag                       │
│  ├── Processing: Stateless, parallel                           │
│  └── Target: < 1 second processing time                        │
│                                                                 │
│  Database (Cassandra):                                          │
│  ├── 100+ nodes per datacenter                                 │
│  ├── 3 datacenters (US, EU, APAC)                              │
│  ├── Replication factor: 3                                     │
│  ├── Consistency: LOCAL_QUORUM                                 │
│  └── Partition by conversation_id                               │
│                                                                 │
│  Caching (Redis):                                               │
│  ├── Sessions: 1M ops/second per cluster                       │
│  ├── Multiple clusters by region                               │
│  └── ~10TB total cache                                         │
│                                                                 │
│  Geographic Distribution:                                       │
│  ├── Users connect to nearest region                           │
│  ├── Cross-region messages via dedicated links                 │
│  └── Regional data residency compliance                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Optimizations:**
1. **Batch processing:** Aggregate small messages
2. **Connection multiplexing:** Share TCP connections
3. **Compression:** Reduce bandwidth
4. **Local caching:** Reduce Redis hits
5. **Async processing:** Don't block on non-critical

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                 WHATSAPP - FINAL ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 2B users, 100B messages/day                             │
│                                                                 │
│  Core Components:                                               │
│  ├── Connection Servers (5000+) - WebSocket handling           │
│  ├── Message Service - Routing and delivery                    │
│  ├── Presence Service - Online/offline tracking                │
│  ├── Group Service - Group management                          │
│  ├── Media Service - S3 + CDN                                  │
│  └── Push Service - APNs, FCM                                  │
│                                                                 │
│  Data Stores:                                                   │
│  ├── Cassandra - Messages (100+ nodes/DC)                      │
│  ├── PostgreSQL - User data, groups                            │
│  ├── Redis - Sessions, presence (10TB)                         │
│  └── S3 - Media storage                                        │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── WebSocket for real-time                                   │
│  ├── Signal Protocol for E2E encryption                        │
│  ├── Pending message queue for offline delivery                │
│  ├── Kafka for async processing                                │
│  └── Geographic distribution (3 regions)                       │
│                                                                 │
│  SLA: 99.99% availability, <500ms message delivery             │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
