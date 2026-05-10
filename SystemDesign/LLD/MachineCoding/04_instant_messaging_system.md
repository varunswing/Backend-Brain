# Instant Messaging System - LLD / Machine Coding

---

## 1. Problem Statement

Design an instant messaging system that supports one-on-one and group chats, real-time message delivery, message status tracking (sent/delivered/read), and offline message handling. The system should be extensible, maintainable, and demonstrate clean OOP design.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR1 | User registration and authentication | Must |
| FR2 | Create direct (1:1) and group conversations | Must |
| FR3 | Send text messages in a conversation | Must |
| FR4 | Support message types: text, image, video, file | Must |
| FR5 | Message status: SENT → DELIVERED → READ | Must |
| FR6 | Delivery receipts when message reaches recipient | Must |
| FR7 | Read receipts when recipient reads message | Must |
| FR8 | Push notifications for offline users | Should |
| FR9 | Fetch conversation history with pagination | Must |
| FR10 | List conversations for a user | Must |

### Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR1 | Low latency for online message delivery (< 100ms) |
| NFR2 | Offline messages delivered when user comes online |
| NFR3 | Scalable design for millions of users |
| NFR4 | Extensible for new message types and delivery strategies |

---

## 3. Database Design with Explanations

### Table: `users`

```sql
CREATE TABLE users (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    username        VARCHAR(50) NOT NULL UNIQUE,
    email           VARCHAR(100) NOT NULL UNIQUE,
    phone           VARCHAR(20),
    display_name    VARCHAR(100),
    avatar_url      VARCHAR(500),
    status          VARCHAR(20) DEFAULT 'offline',  -- online, offline, away
    last_seen_at    TIMESTAMP NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_users_email (email),
    INDEX idx_users_username (username),
    INDEX idx_users_status_last_seen (status, last_seen_at)
);
```

**WHY this table exists:** Core entity for all participants in the messaging system. Every message, conversation, and receipt is tied to a user.

**WHY `username` and `email` UNIQUE:** Ensures no duplicate accounts; used for login and lookup.

**WHY `status` and `last_seen_at`:** Support presence (online/offline) for delivery strategy selection (direct vs queue).

**WHY indexes on `email`, `username`:** Fast lookup during auth and search.

**WHY index on `(status, last_seen_at)`:** Efficient queries for "who is online" and presence updates.

---

### Table: `conversations`

```sql
CREATE TABLE conversations (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    type            VARCHAR(20) NOT NULL,           -- 'direct' or 'group'
    name            VARCHAR(100),                   -- NULL for direct, group name for group
    created_by      BIGINT NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_conversations_type (type),
    INDEX idx_conversations_created_by (created_by)
);
```

**WHY this table exists:** Represents a chat container. Separates conversation metadata from membership and messages.

**WHY `type` (direct vs group):** Different delivery logic—direct uses 1:1 routing; group uses fan-out. Factory creates appropriate conversation objects.

**WHY `created_by` FK:** Audit trail and ownership; RESTRICT prevents deleting users who own conversations.

**WHY `name` nullable:** Direct chats have no name; group chats have a display name.

**WHY separate from `conversation_members`:** Normalization—conversation metadata (name, type) is stable; membership can change (add/remove in groups).

---

### Table: `conversation_members` (Junction)

```sql
CREATE TABLE conversation_members (
    conversation_id BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    role            VARCHAR(20) DEFAULT 'member',   -- member, admin (for groups)
    joined_at       TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_read_at    TIMESTAMP NULL,

    PRIMARY KEY (conversation_id, user_id),
    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_conversation_members_user_id (user_id)
);
```

**WHY this table exists:** Many-to-many between users and conversations. One user in many conversations; one conversation has many members.

**WHY junction instead of embedding members in `conversations`:** Scalable—groups can have many members; avoids JSON/set columns; enables efficient "list my conversations" by `user_id`.

**WHY composite PK `(conversation_id, user_id)`:** One membership per user per conversation; natural unique constraint.

**WHY FK `ON DELETE CASCADE`:** When conversation or user is deleted, memberships are cleaned up.

**WHY `last_read_at`:** Drives read receipts and unread counts; per-user, per-conversation.

**WHY index on `user_id`:** Fast lookup of "all conversations for user X" (inverse of PK).

---

### Table: `messages`

```sql
CREATE TABLE messages (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    sender_id       BIGINT NOT NULL,
    type            VARCHAR(20) NOT NULL,           -- text, image, video, file
    content         TEXT NOT NULL,
    media_url       VARCHAR(500),                   -- for image/video/file
    status          VARCHAR(20) DEFAULT 'SENT',    -- SENT, DELIVERED, READ (aggregate)
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (conversation_id) REFERENCES conversations(id) ON DELETE CASCADE,
    FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE RESTRICT,
    INDEX idx_messages_conversation_created (conversation_id, created_at DESC),
    INDEX idx_messages_sender (sender_id)
);
```

**WHY this table exists:** Stores all message content and metadata. Central entity for delivery, receipts, and history.

**WHY `conversation_id` FK:** Every message belongs to exactly one conversation; CASCADE deletes messages when conversation is deleted.

**WHY `sender_id` FK:** Identifies who sent the message; RESTRICT prevents deleting users with sent messages (or use soft delete).

**WHY `type` and `content`/`media_url`:** Supports multiple message types (Strategy/Factory can extend); text in `content`, media in `media_url`.

**WHY `status` on message:** Aggregate view for sender (e.g., "all delivered" in group). Per-recipient status is in `message_receipts`.

**WHY index `(conversation_id, created_at DESC)`:** Primary access pattern—fetch messages for a conversation in chronological order; DESC for "latest first" pagination.

**WHY index on `sender_id`:** Queries like "messages sent by user X" for search or moderation.

---

### Table: `message_receipts`

```sql
CREATE TABLE message_receipts (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id      BIGINT NOT NULL,
    user_id         BIGINT NOT NULL,
    status          VARCHAR(20) NOT NULL,            -- SENT, DELIVERED, READ
    received_at     TIMESTAMP NULL,                 -- when DELIVERED
    read_at         TIMESTAMP NULL,                 -- when READ
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_message_user (message_id, user_id),
    FOREIGN KEY (message_id) REFERENCES messages(id) ON DELETE CASCADE,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    INDEX idx_message_receipts_user_status (user_id, status),
    INDEX idx_message_receipts_message (message_id)
);
```

**WHY this table exists:** Per-recipient status for each message. Sender doesn't need receipt; each recipient has one row. Enables delivery/read receipts and state machine transitions.

**WHY `(message_id, user_id)` UNIQUE:** One receipt per message per recipient; idempotent updates.

**WHY `status` with `received_at`/`read_at`:** State machine (SENT→DELIVERED→READ); timestamps for audit and UI.

**WHY FKs with CASCADE:** Receipts are meaningless without message or user; clean up on delete.

**WHY index on `(user_id, status)`:** "Unread messages for user" or "pending delivery for user" queries.

**WHY index on `message_id`:** "All receipts for this message" (e.g., group read status).

**WHY separate from `messages`:** Normalization—message is immutable; receipts are per-recipient and mutable. Avoids wide, sparse columns on `messages`.

---

## 4. Design Patterns Used

| Pattern | Where Used | Purpose |
|---------|------------|---------|
| **Strategy** | `MessageDeliveryStrategy` | Swap delivery logic (direct, queue, group fan-out) without changing caller |
| **Observer** | `MessageObserver` | Decouple receipt updates, push notifications from core send flow |
| **Factory** | `ConversationFactory` | Create `DirectConversation` or `GroupConversation` based on type |
| **State** | `MessageStatus` transitions | Enforce valid SENT→DELIVERED→READ transitions |
| **Repository** | `UserRepository`, `MessageRepository` | Abstract data access; testable with mocks |
| **Dependency Injection** | `ChatService` constructor | Inject strategies, observers, repositories for testability and flexibility |

---

## 5. SOLID Principles Applied

| Principle | Application |
|-----------|-------------|
| **S - Single Responsibility** | `ChatService` orchestrates; `MessageDeliveryStrategy` handles delivery; `MessageObserver` handles side effects |
| **O - Open/Closed** | New delivery strategies (e.g., `BroadcastDelivery`) without modifying `ChatService`; new observers without changing send logic |
| **L - Liskov Substitution** | Any `MessageDeliveryStrategy` implementation can replace another; any `MessageObserver` can be used interchangeably |
| **I - Interface Segregation** | `MessageDeliveryStrategy` has only `deliver()`; `MessageObserver` has only `onMessage*()`; no fat interfaces |
| **D - Dependency Inversion** | `ChatService` depends on `MessageDeliveryStrategy` and `MessageObserver` abstractions, not concrete classes |

---

## 6. Code Implementation in Java

### Enums

```java
/**
 * MessageType - Supports extensibility for new content types (Open/Closed Principle).
 * Future: add VOICE, POLL, etc. without changing Message model.
 */
public enum MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    FILE
}

/**
 * MessageStatus - State machine for message lifecycle.
 * Valid transitions: SENT -> DELIVERED -> READ (one-way).
 * Used in Message model and message_receipts table.
 */
public enum MessageStatus {
    SENT,       // Message persisted, not yet delivered to recipient
    DELIVERED,  // Recipient received (e.g., device acknowledged)
    READ       // Recipient opened/read the message
}

/**
 * ConversationType - Drives Factory and delivery strategy selection.
 * Direct: 1:1, uses DirectDelivery. Group: N:N, uses GroupFanOutDelivery.
 */
public enum ConversationType {
    DIRECT,
    GROUP
}
```

---

### Models (with Encapsulation + State Machine)

```java
/**
 * User - Encapsulates user data. Immutable-style with getters.
 * WHY: Single source of truth for user identity across conversations and messages.
 */
public class User {
    private final Long id;
    private final String username;
    private final String email;
    private String displayName;
    private String status;  // online, offline
    private java.time.Instant lastSeenAt;

    public User(Long id, String username, String email) {
        this.id = id;
        this.username = username;
        this.email = email;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public java.time.Instant getLastSeenAt() { return lastSeenAt; }

    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public void setStatus(String status) { this.status = status; }
    public void setLastSeenAt(java.time.Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    public boolean isOnline() { return "online".equalsIgnoreCase(status); }
}

/**
 * Conversation - Base for direct/group. Encapsulates conversation metadata.
 * WHY: Polymorphic base for ConversationFactory (direct vs group).
 */
public abstract class Conversation {
    private final Long id;
    private final ConversationType type;
    private final String name;
    private final Long createdBy;
    private final java.time.Instant createdAt;

    protected Conversation(Long id, ConversationType type, String name, Long createdBy, java.time.Instant createdAt) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public ConversationType getType() { return type; }
    public String getName() { return name; }
    public Long getCreatedBy() { return createdBy; }
    public java.time.Instant getCreatedAt() { return createdAt; }

    public abstract List<Long> getMemberIds();
}

public class DirectConversation extends Conversation {
    private final Long member1Id;
    private final Long member2Id;

    public DirectConversation(Long id, Long createdBy, Long member1Id, Long member2Id, java.time.Instant createdAt) {
        super(id, ConversationType.DIRECT, null, createdBy, createdAt);
        this.member1Id = member1Id;
        this.member2Id = member2Id;
    }

    @Override
    public List<Long> getMemberIds() {
        return List.of(member1Id, member2Id);
    }
}

public class GroupConversation extends Conversation {
    private final List<Long> memberIds;

    public GroupConversation(Long id, String name, Long createdBy, List<Long> memberIds, java.time.Instant createdAt) {
        super(id, ConversationType.GROUP, name, createdBy, createdAt);
        this.memberIds = new ArrayList<>(memberIds);
    }

    @Override
    public List<Long> getMemberIds() {
        return List.copyOf(memberIds);
    }
}

/**
 * Message - Encapsulates message data with state machine for status.
 * WHY: Central entity for delivery, receipts, and history.
 * State machine: only valid transitions (SENT->DELIVERED->READ) are allowed.
 */
public class Message {
    private final Long id;
    private final Long conversationId;
    private final Long senderId;
    private final MessageType type;
    private final String content;
    private final String mediaUrl;
    private MessageStatus status;  // Mutable: state machine
    private final java.time.Instant createdAt;

    public Message(Long id, Long conversationId, Long senderId, MessageType type,
                   String content, String mediaUrl, MessageStatus status, java.time.Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.senderId = senderId;
        this.type = type;
        this.content = content;
        this.mediaUrl = mediaUrl;
        this.status = status;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getConversationId() { return conversationId; }
    public Long getSenderId() { return senderId; }
    public MessageType getType() { return type; }
    public String getContent() { return content; }
    public String getMediaUrl() { return mediaUrl; }
    public MessageStatus getStatus() { return status; }
    public java.time.Instant getCreatedAt() { return createdAt; }

    /**
     * State machine: SENT -> DELIVERED -> READ.
     * Throws if invalid transition (encapsulation of business rules).
     */
    public void markDelivered() {
        if (status != MessageStatus.SENT) {
            throw new IllegalStateException("Cannot mark DELIVERED from " + status);
        }
        this.status = MessageStatus.DELIVERED;
    }

    public void markRead() {
        if (status == MessageStatus.SENT) {
            throw new IllegalStateException("Cannot mark READ from SENT; must be DELIVERED first");
        }
        this.status = MessageStatus.READ;
    }
}
```

---

### Strategy: MessageDeliveryStrategy

```java
/**
 * MessageDeliveryStrategy - Strategy pattern for different delivery modes.
 * WHY: Open/Closed - add new strategies (e.g., BroadcastDelivery) without changing ChatService.
 * WHY: Single Responsibility - each strategy handles one delivery scenario.
 */
public interface MessageDeliveryStrategy {
    void deliver(Message message, List<Long> recipientIds);
}

/**
 * DirectDelivery - For online recipient in 1:1 chat.
 * Sends immediately via WebSocket/push to connected device.
 */
public class DirectDeliveryStrategy implements MessageDeliveryStrategy {
    @Override
    public void deliver(Message message, List<Long> recipientIds) {
        if (recipientIds.size() != 1) {
            throw new IllegalArgumentException("DirectDelivery expects exactly one recipient");
        }
        Long recipientId = recipientIds.get(0);
        // In real system: lookup WebSocket connection, send message
        // Here: simulate immediate delivery
        System.out.println("DirectDelivery: message " + message.getId() + " -> user " + recipientId);
    }
}

/**
 * QueueDelivery - For offline recipients.
 * Persists to queue (e.g., Redis/Kafka); delivered when user comes online.
 * In full impl: inject MessageObserver list and call onMessageQueuedForOfflineUser for each recipient.
 */
public class QueueDeliveryStrategy implements MessageDeliveryStrategy {
    @Override
    public void deliver(Message message, List<Long> recipientIds) {
        for (Long recipientId : recipientIds) {
            // In real system: add to offline_messages:{recipientId}
            System.out.println("QueueDelivery: message " + message.getId() + " queued for user " + recipientId);
        }
    }
}

/**
 * GroupFanOutDelivery - For group chats.
 * Delivers to all members: direct for online, queue for offline.
 */
public class GroupFanOutDeliveryStrategy implements MessageDeliveryStrategy {
    private final java.util.function.Predicate<Long> isUserOnline;

    public GroupFanOutDeliveryStrategy(java.util.function.Predicate<Long> isUserOnline) {
        this.isUserOnline = isUserOnline;
    }

    @Override
    public void deliver(Message message, List<Long> recipientIds) {
        for (Long recipientId : recipientIds) {
            if (recipientId.equals(message.getSenderId())) continue;  // Skip sender
            if (isUserOnline.test(recipientId)) {
                new DirectDeliveryStrategy().deliver(message, List.of(recipientId));
            } else {
                new QueueDeliveryStrategy().deliver(message, List.of(recipientId));
            }
        }
    }
}
```

---

### Observer: MessageObserver

```java
/**
 * MessageObserver - Observer pattern for side effects on message events.
 * WHY: Decouples delivery receipts, read receipts, push notifications from core send flow.
 * WHY: Open/Closed - add new observers (e.g., AnalyticsObserver) without changing ChatService.
 */
public interface MessageObserver {
    void onMessageSent(Message message);
    void onMessageDelivered(Message message, Long recipientId);
    void onMessageRead(Message message, Long recipientId);
    default void onMessageQueuedForOfflineUser(Message message, Long recipientId) {}
}

/**
 * DeliveryReceiptObserver - Updates message_receipts when message is delivered.
 */
public class DeliveryReceiptObserver implements MessageObserver {
    private final MessageReceiptRepository receiptRepository;

    public DeliveryReceiptObserver(MessageReceiptRepository receiptRepository) {
        this.receiptRepository = receiptRepository;
    }

    @Override
    public void onMessageSent(Message message) { /* optional */ }

    @Override
    public void onMessageDelivered(Message message, Long recipientId) {
        receiptRepository.upsertReceipt(message.getId(), recipientId, MessageStatus.DELIVERED, null, java.time.Instant.now());
    }

    @Override
    public void onMessageRead(Message message, Long recipientId) {
        receiptRepository.upsertReceipt(message.getId(), recipientId, MessageStatus.READ, java.time.Instant.now(), null);
    }
}

/**
 * ReadReceiptObserver - Notifies sender when message is read.
 */
public class ReadReceiptObserver implements MessageObserver {
    @Override
    public void onMessageSent(Message message) { }

    @Override
    public void onMessageDelivered(Message message, Long recipientId) { }

    @Override
    public void onMessageRead(Message message, Long recipientId) {
        System.out.println("ReadReceipt: message " + message.getId() + " read by user " + recipientId);
        // In real system: push to sender's WebSocket
    }
}

/**
 * PushNotificationObserver - Sends push when user is offline.
 */
public class PushNotificationObserver implements MessageObserver {
    @Override
    public void onMessageSent(Message message) { }

    @Override
    public void onMessageDelivered(Message message, Long recipientId) { }

    @Override
    public void onMessageRead(Message message, Long recipientId) { }

    @Override
    public void onMessageQueuedForOfflineUser(Message message, Long recipientId) {
        System.out.println("PushNotification: message " + message.getId() + " -> push to user " + recipientId);
    }
}
```

---

### Factory: ConversationFactory

```java
/**
 * ConversationFactory - Factory pattern for creating Direct vs Group conversations.
 * WHY: Encapsulates creation logic; caller doesn't need to know constructor details.
 * WHY: Open/Closed - add new types (e.g., ChannelConversation) by extending factory.
 */
public class ConversationFactory {
    public Conversation create(ConversationType type, Long id, String name, Long createdBy,
                              List<Long> memberIds, java.time.Instant createdAt) {
        return switch (type) {
            case DIRECT -> {
                if (memberIds.size() != 2) throw new IllegalArgumentException("Direct conversation must have 2 members");
                yield new DirectConversation(id, createdBy, memberIds.get(0), memberIds.get(1), createdAt);
            }
            case GROUP -> new GroupConversation(id, name, createdBy, memberIds, createdAt);
        };
    }
}
```

---

### ChatService (Orchestrator with Constructor Injection)

```java
/**
 * ChatService - Orchestrator for messaging operations.
 * WHY: Dependency Inversion - depends on abstractions (Strategy, Observer, Repository).
 * WHY: Constructor injection - enables testing with mocks; explicit dependencies.
 * WHY: Single Responsibility - coordinates components; doesn't implement delivery or persistence.
 */
public class ChatService {
    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;
    private final MessageDeliveryStrategy deliveryStrategy;
    private final List<MessageObserver> observers;

    public ChatService(MessageRepository messageRepository,
                       ConversationRepository conversationRepository,
                       UserRepository userRepository,
                       MessageDeliveryStrategy deliveryStrategy,
                       List<MessageObserver> observers) {
        this.messageRepository = messageRepository;
        this.conversationRepository = conversationRepository;
        this.userRepository = userRepository;
        this.deliveryStrategy = deliveryStrategy;
        this.observers = new ArrayList<>(observers);
    }

    public Message sendMessage(Long conversationId, Long senderId, MessageType type, String content, String mediaUrl) {
        Conversation conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found"));
        List<Long> recipientIds = conv.getMemberIds().stream()
                .filter(id -> !id.equals(senderId))
                .toList();

        Message message = new Message(null, conversationId, senderId, type, content, mediaUrl,
                MessageStatus.SENT, java.time.Instant.now());
        message = messageRepository.save(message);

        // Notify observers
        observers.forEach(o -> o.onMessageSent(message));

        // Deliver via strategy (Strategy pattern)
        deliveryStrategy.deliver(message, recipientIds);

        return message;
    }

    public void markDelivered(Long messageId, Long recipientId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        message.markDelivered();
        messageRepository.update(message);
        observers.forEach(o -> o.onMessageDelivered(message, recipientId));
    }

    public void markRead(Long messageId, Long recipientId) {
        Message message = messageRepository.findById(messageId)
                .orElseThrow(() -> new IllegalArgumentException("Message not found"));
        message.markRead();
        messageRepository.update(message);
        observers.forEach(o -> o.onMessageRead(message, recipientId));
    }

    public List<Message> getConversationHistory(Long conversationId, int limit, Long beforeMessageId) {
        return messageRepository.findByConversationId(conversationId, limit, beforeMessageId);
    }
}

// Repository interfaces (abstractions for DIP)
interface MessageRepository {
    Message save(Message message);
    java.util.Optional<Message> findById(Long id);
    void update(Message message);
    List<Message> findByConversationId(Long conversationId, int limit, Long beforeMessageId);
}
interface ConversationRepository {
    java.util.Optional<Conversation> findById(Long id);
}
interface UserRepository {
    java.util.Optional<User> findById(Long id);
}
interface MessageReceiptRepository {
    void upsertReceipt(Long messageId, Long userId, MessageStatus status, java.time.Instant readAt, java.time.Instant receivedAt);
}
```

---

## 7. Edge Cases & Tests

| # | Test Case | Description | Expected |
|---|-----------|-------------|----------|
| 1 | **Send message in direct chat** | User A sends text to User B in 1:1 conversation | Message saved with SENT; delivery strategy invoked for B |
| 2 | **Send message in group chat** | User A sends to group of B, C, D | Message saved; GroupFanOutDelivery delivers to B, C, D (skip A) |
| 3 | **Offline user receives queued message** | User B is offline; A sends message | QueueDelivery queues for B; PushNotificationObserver fires |
| 4 | **Invalid status transition** | Call `markRead()` on message with status SENT | `IllegalStateException` (must be DELIVERED first) |
| 5 | **Direct conversation with wrong member count** | Create DIRECT with 3 members | `IllegalArgumentException` from ConversationFactory |
| 6 | **Mark delivered then read** | Message SENT → markDelivered → markRead | Status transitions SENT→DELIVERED→READ; observers notified |
| 7 | **Get conversation history pagination** | Request 20 messages before message X | Returns up to 20 messages older than X |
| 8 | **Send to non-existent conversation** | Send message with invalid conversationId | `IllegalArgumentException` ("Conversation not found") |

---

## 8. Summary

| Aspect | Summary |
|--------|---------|
| **Problem** | Instant messaging with 1:1/group chats, message status (SENT/DELIVERED/READ), offline handling |
| **DB** | `users`, `conversations`, `conversation_members`, `messages`, `message_receipts` with WHY comments |
| **Patterns** | Strategy (delivery), Observer (receipts/push), Factory (conversation type), State (message status) |
| **SOLID** | SRP, OCP, LSP, ISP, DIP applied across models, strategies, observers, and ChatService |
| **Key Design** | Constructor injection, encapsulation, state machine for status, junction table for membership |
