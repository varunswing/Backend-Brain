# Message Queue System - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database/Storage Design with Explanations](#databasestorage-design-with-explanations)
4. [Design Patterns Used](#design-patterns-used)
5. [SOLID Principles Applied](#solid-principles-applied)
6. [Code Implementation in Java](#code-implementation-in-java)
7. [Edge Cases & Tests](#edge-cases--tests)
8. [Summary](#summary)

---

## Problem Statement

Design a message queue system (like Kafka/SQS) that supports publish-subscribe messaging with topics, partitions, delivery guarantees (at-least-once, at-most-once, exactly-once), consumer groups, and message lifecycle management. The system must handle producer-consumer decoupling with configurable partitioning strategies.

---

## Requirements

### Functional Requirements
1. **Topic Management**: Create topics with configurable partition count
2. **Message Publishing**: Producers publish messages to topics with optional partition key
3. **Message Consumption**: Consumers subscribe to topics and receive messages
4. **Consumer Groups**: Multiple consumers in a group share partition load (each partition → one consumer)
5. **Delivery Guarantees**: Support AtLeastOnce, AtMostOnce, ExactlyOnce
6. **Acknowledgement Modes**: Fire-and-forget, leader-only, quorum (all replicas)
7. **Partition Assignment**: Round-robin, key-based hashing, custom strategy
8. **Message Lifecycle**: Track PENDING → DELIVERED → ACKNOWLEDGED states
9. **Offset Tracking**: Consumers track their read position per partition

### Non-Functional Requirements
- **Extensibility**: Easy to add new delivery strategies and partition strategies
- **Testability**: Constructor injection for mockable dependencies
- **Thread Safety**: Broker handles concurrent produce/consume
- **Low Latency**: In-memory queue for hot path, configurable retention

---

## Database/Storage Design with Explanations

```sql
-- ============================================================================
-- TABLE: topics
-- WHY: A topic is the logical channel for message categorization. Producers
--      publish to topics; consumers subscribe to topics. Without topics, we'd
--      have a single flat queue—no way to route "order_events" vs "user_events".
--      Topics enable pub-sub semantics: many producers → one topic → many consumers.
--
-- WHY partition_count? Partitions enable parallelism. More partitions = more
--      concurrent consumers. Each partition is an ordered log (FIFO within partition).
--
-- WHY retention_ms? Messages can't live forever. Retention defines how long
--      we keep messages before compaction/deletion. Default 7 days balances
--      storage cost vs replay capability.
-- ============================================================================
CREATE TABLE topics (
    topic_id UUID PRIMARY KEY,
    topic_name VARCHAR(255) NOT NULL UNIQUE,
    partition_count INTEGER NOT NULL DEFAULT 4,
    replication_factor INTEGER NOT NULL DEFAULT 1,
    retention_ms BIGINT NOT NULL DEFAULT 604800000,  -- 7 days
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? Topic lookup by name is the #1 operation (produce/consume).
CREATE UNIQUE INDEX idx_topics_name ON topics(topic_name);

-- ============================================================================
-- TABLE: partitions
-- WHY: Each topic is split into partitions for horizontal scaling. A partition
--      is an ordered, append-only log. We need this table to:
--      1. Map partition_id to physical storage (log segment path)
--      2. Track high_water_mark (last committed offset) for consumer visibility
--      3. Support partition-level operations (rebalance, reassign)
--
-- RELATIONSHIP: partitions → topics (Many-to-One)
--   WHY FK? Every partition MUST belong to a topic. Orphan partitions are invalid.
--   When a topic is deleted, we cascade-delete its partitions.
--
-- WHY leader_broker_id? In distributed mode, each partition has a leader broker.
--   Consumers/producers route to the leader. Enables future multi-broker support.
-- ============================================================================
CREATE TABLE partitions (
    partition_id UUID PRIMARY KEY,
    topic_id UUID NOT NULL REFERENCES topics(topic_id) ON DELETE CASCADE,
    partition_index INTEGER NOT NULL,           -- 0, 1, 2, ... (ordinal within topic)
    high_water_mark BIGINT NOT NULL DEFAULT 0,  -- Last committed offset
    leader_broker_id UUID,
    created_at TIMESTAMP DEFAULT NOW(),

    UNIQUE(topic_id, partition_index)
);

-- WHY this composite index? "Get all partitions for topic X" is the most common query.
CREATE INDEX idx_partitions_topic ON partitions(topic_id);

-- ============================================================================
-- TABLE: messages
-- WHY: The core payload. Each row is one message. We store messages because:
--      1. Durability—messages survive broker restart
--      2. Replay—consumers can re-read from offset
--      3. Audit—we can trace message flow
--
-- RELATIONSHIP: messages → partitions (Many-to-One)
--   WHY FK? Every message belongs to exactly one partition. Partition determines
--   ordering (FIFO within partition). Without this, we lose ordering guarantees.
--
-- WHY offset? Offset is the sequence number within a partition. It's the
--   consumer's bookmark: "I've read up to offset 1000." Enables exactly-once
--   and at-least-once semantics.
--
-- WHY partition_key? Used for partitioning: hash(partition_key) % partition_count
--   ensures same key always goes to same partition (ordering by key).
--
-- WHY status? Message lifecycle: PENDING (just produced), DELIVERED (sent to consumer),
--   ACKNOWLEDGED (consumer confirmed). Enables retry logic and dead-letter handling.
-- ============================================================================
CREATE TABLE messages (
    message_id UUID PRIMARY KEY,
    partition_id UUID NOT NULL REFERENCES partitions(partition_id) ON DELETE CASCADE,
    offset BIGINT NOT NULL,                     -- Sequence within partition
    partition_key VARCHAR(512),                 -- Optional; used for partitioning
    payload BYTEA NOT NULL,
    headers JSONB,                              -- {"source": "web", "trace_id": "abc"}
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, DELIVERED, ACKNOWLEDGED
    produced_at TIMESTAMP NOT NULL DEFAULT NOW(),
    delivered_at TIMESTAMP,
    acknowledged_at TIMESTAMP,

    UNIQUE(partition_id, offset)
);

-- WHY this composite index? Consumer fetches "next N messages from partition P after offset O".
CREATE INDEX idx_messages_partition_offset ON messages(partition_id, offset);

-- WHY this index? Cleanup job: delete messages older than retention where status=ACKNOWLEDGED.
CREATE INDEX idx_messages_status_produced ON messages(status, produced_at);

-- ============================================================================
-- TABLE: consumer_groups
-- WHY: Consumer groups enable load balancing. Without groups, every consumer
--      would get every message (fan-out). With groups: partitions are distributed
--      among group members. One partition → one consumer in the group.
--      "analytics_group" might have 4 consumers reading 12 partitions (3 each).
--
-- WHY group_name UNIQUE? Group names must be globally unique. "order-processors"
--      and "analytics" are distinct groups subscribing to the same topic.
-- ============================================================================
CREATE TABLE consumer_groups (
    group_id UUID PRIMARY KEY,
    group_name VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- TABLE: consumer_group_offsets
-- WHY: Tracks each consumer group's read position per partition. Without this,
--      a consumer wouldn't know where to resume after restart. "analytics_group
--      has read partition 0 up to offset 5000."
--
-- RELATIONSHIP: consumer_group_offsets → consumer_groups (Many-to-One)
--   WHY FK? Offsets belong to a group. Delete group → delete its offsets.
--
-- RELATIONSHIP: consumer_group_offsets → partitions (Many-to-One)
--   WHY FK? Offsets are per-partition. Each (group, partition) has one offset.
--
-- WHY UNIQUE(group_id, partition_id)? One offset per group per partition.
--   Prevents duplicate offset rows for same group+partition.
-- ============================================================================
CREATE TABLE consumer_group_offsets (
    offset_id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES consumer_groups(group_id) ON DELETE CASCADE,
    partition_id UUID NOT NULL REFERENCES partitions(partition_id) ON DELETE CASCADE,
    committed_offset BIGINT NOT NULL DEFAULT -1,  -- -1 = start from beginning
    last_updated TIMESTAMP DEFAULT NOW(),

    UNIQUE(group_id, partition_id)
);

-- WHY this index? "Get offset for group G, partition P" — the core consume path.
CREATE UNIQUE INDEX idx_offsets_group_partition ON consumer_group_offsets(group_id, partition_id);
```

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `DeliveryStrategy` (AtLeastOnce, AtMostOnce, ExactlyOnce) | Delivery semantics vary; encapsulate each as a strategy. Producer can switch guarantees without changing produce logic. |
| **Strategy** | `PartitionStrategy` (RoundRobin, KeyHash, Custom) | Partition assignment logic varies. Encapsulate so we can add Sticky, Range strategies without modifying Producer. |
| **Observer** | `MessageListener` + `Consumer` | Consumers observe new messages. When Broker has new messages, it notifies subscribed listeners. Decouples Broker from consumer implementations. |
| **Factory** | `MessageQueueFactory` | Creates Producer/Consumer with correct dependencies. Centralizes object creation; clients don't need to wire DeliveryStrategy, PartitionStrategy, etc. |
| **Builder** | `Message.MessageBuilder` | Message has many optional fields (key, headers, partition). Builder avoids telescoping constructors and makes intent clear. |
| **State** | `MessageStatus` (PENDING → DELIVERED → ACKNOWLEDGED) | Message lifecycle is stateful. State pattern enables different behavior per state (e.g., retry only PENDING). |
| **Singleton** | `Broker` (in-memory impl) | Single broker instance manages all topics/partitions. Ensures consistent state; avoids duplicate topic creation. |

---

## SOLID Principles Applied

| Principle | How |
|-----------|-----|
| **S - Single Responsibility** | `Broker` manages topics/partitions; `Producer` only produces; `Consumer` only consumes; `DeliveryStrategy` only handles delivery logic. |
| **O - Open/Closed** | New delivery guarantees (e.g., AtLeastOnce) added by implementing `DeliveryStrategy`—no change to `Producer`. New partition strategies by implementing `PartitionStrategy`. |
| **O - Open/Closed** | New message listeners added without modifying `Broker`—Observer pattern. |
| **L - Liskov Substitution** | Any `DeliveryStrategy` implementation can replace another; any `PartitionStrategy` can replace another. Subtypes honor contracts. |
| **I - Interface Segregation** | `Producer` and `Consumer` are separate interfaces. Clients that only produce don't depend on consume methods. |
| **D - Dependency Inversion** | `Producer` depends on `DeliveryStrategy` and `PartitionStrategy` interfaces, not concrete classes. Constructor injection allows swapping implementations. |
| **D - Dependency Inversion** | `Broker` depends on abstractions; `MessageQueueFactory` injects concrete strategies. High-level modules don't depend on low-level modules. |

---

## Code Implementation in Java

### Imports (for reference)

```java
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
```

### Enums

```java
/**
 * Delivery guarantee semantics for message publishing.
 * STRATEGY PATTERN: Each value maps to a DeliveryStrategy implementation.
 * WHY: Producers need to choose semantics; enum provides type-safe options.
 */
public enum DeliveryGuarantee {
    AT_MOST_ONCE,    // Fire-and-forget; no retries; may lose messages
    AT_LEAST_ONCE,   // Retry until ack; may duplicate
    EXACTLY_ONCE     // Idempotent producer + transactional commit; no loss, no dupes
}

/**
 * Message lifecycle state.
 * STATE PATTERN: Message transitions through these states.
 * WHY: Enables retry logic (only retry PENDING), dead-letter (failed ACK), metrics.
 */
public enum MessageStatus {
    PENDING,        // Produced, not yet delivered to any consumer
    DELIVERED,      // Sent to consumer, awaiting ack
    ACKNOWLEDGED    // Consumer confirmed; safe to compact/delete
}

/**
 * Acknowledgement mode for producer durability.
 * WHY: Trade-off between latency (acks=0 fast, acks=all slow) and durability.
 * Maps to Kafka's acks config: 0, 1, all.
 */
public enum AckMode {
    NONE,           // acks=0: No wait, fire-and-forget
    LEADER_ONLY,    // acks=1: Wait for leader to persist
    ALL             // acks=all: Wait for all replicas (quorum)
}
```

### Model Classes

```java
/**
 * Immutable message payload.
 * BUILDER PATTERN: MessageBuilder for fluent construction with optional fields.
 * WHY Builder? Message has required (payload) + optional (key, headers, partition).
 * Telescoping constructors would be unreadable.
 *
 * SOLID: Single Responsibility - only holds message data.
 */
public class Message {
    private final String id;
    private final String partitionKey;
    private final byte[] payload;
    private final Map<String, String> headers;
    private MessageStatus status;
    private final long timestamp;

    private Message(MessageBuilder builder) {
        this.id = builder.id;
        this.partitionKey = builder.partitionKey;
        this.payload = builder.payload;
        this.headers = builder.headers != null ? Map.copyOf(builder.headers) : Map.of();
        this.status = MessageStatus.PENDING;
        this.timestamp = System.currentTimeMillis();
    }

    public static MessageBuilder builder(byte[] payload) {
        return new MessageBuilder(payload);
    }

    public static class MessageBuilder {
        private final String id;
        private final byte[] payload;
        private String partitionKey;
        private Map<String, String> headers;

        public MessageBuilder(byte[] payload) {
            this.id = UUID.randomUUID().toString();
            this.payload = payload;
        }

        public MessageBuilder partitionKey(String key) {
            this.partitionKey = key;
            return this;
        }

        public MessageBuilder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Message build() {
            return new Message(this);
        }
    }

    // Getters and status mutator
    public void setStatus(MessageStatus status) { this.status = status; }
    public MessageStatus getStatus() { return status; }
    public String getId() { return id; }
    public String getPartitionKey() { return partitionKey; }
    public byte[] getPayload() { return payload; }
    public Map<String, String> getHeaders() { return headers; }
    public long getTimestamp() { return timestamp; }
}

/**
 * Topic - logical channel for messages.
 * WHY: Topics enable pub-sub. Producers publish to topic; consumers subscribe.
 * OOP: Encapsulates topic metadata; partition count determines parallelism.
 */
public class Topic {
    private final String id;
    private final String name;
    private final int partitionCount;
    private final List<Partition> partitions;

    public Topic(String name, int partitionCount) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.partitionCount = partitionCount;
        this.partitions = new ArrayList<>();
        for (int i = 0; i < partitionCount; i++) {
            partitions.add(new Partition(id, i));
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public int getPartitionCount() { return partitions.size(); }
    public List<Partition> getPartitions() { return partitions; }
    public Partition getPartition(int index) { return partitions.get(index); }
}

/**
 * Partition - ordered log within a topic.
 * WHY: Partitions enable parallelism. Each partition is FIFO; consumers read from assigned partitions.
 * OOP: Encapsulates messages and offset; single responsibility for partition-level state.
 */
public class Partition {
    private final String topicId;
    private final int index;
    private final List<Message> messages;
    private long nextOffset;

    public Partition(String topicId, int index) {
        this.topicId = topicId;
        this.index = index;
        this.messages = new ArrayList<>();
        this.nextOffset = 0;
    }

    public synchronized long append(Message message) {
        long offset = nextOffset++;
        messages.add(message);
        return offset;
    }

    public List<Message> getMessagesAfter(long offset, int maxCount) {
        return messages.stream()
                .skip(offset + 1)
                .limit(maxCount)
                .toList();
    }

    public String getTopicId() { return topicId; }
    public int getIndex() { return index; }
    public long getNextOffset() { return nextOffset; }
}

/**
 * ConsumerGroup - group of consumers sharing partition load.
 * WHY: Enables load balancing. Partitions are assigned to group members; each partition has one consumer.
 * OOP: Tracks group name and offset per partition for resume capability.
 */
public class ConsumerGroup {
    private final String id;
    private final String name;
    private final Map<String, Long> partitionOffsets;  // partitionId -> committed offset

    public ConsumerGroup(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.partitionOffsets = new ConcurrentHashMap<>();
    }

    public void commitOffset(String partitionId, long offset) {
        partitionOffsets.put(partitionId, offset);
    }

    public long getOffset(String partitionId) {
        return partitionOffsets.getOrDefault(partitionId, -1L);
    }

    public String getId() { return id; }
    public String getName() { return name; }
}
```

### Strategy Pattern: DeliveryStrategy

```java
/**
 * STRATEGY PATTERN: Encapsulates delivery guarantee logic.
 * WHY: AtLeastOnce, AtMostOnce, ExactlyOnce have different retry/ack behavior.
 * Producer delegates to strategy—Open/Closed: add new guarantees without changing Producer.
 *
 * SOLID: Dependency Inversion - Producer depends on this interface.
 */
public interface DeliveryStrategy {
    boolean shouldRetry(Message message, int attemptCount);
    AckMode getAckMode();
}

public class AtMostOnceStrategy implements DeliveryStrategy {
    @Override
    public boolean shouldRetry(Message message, int attemptCount) {
        return false;  // No retries
    }
    @Override
    public AckMode getAckMode() { return AckMode.NONE; }
}

public class AtLeastOnceStrategy implements DeliveryStrategy {
    private static final int MAX_RETRIES = 3;

    @Override
    public boolean shouldRetry(Message message, int attemptCount) {
        return attemptCount < MAX_RETRIES;
    }
    @Override
    public AckMode getAckMode() { return AckMode.LEADER_ONLY; }
}

public class ExactlyOnceStrategy implements DeliveryStrategy {
    @Override
    public boolean shouldRetry(Message message, int attemptCount) {
        return true;  // Retry until success; use idempotent keys
    }
    @Override
    public AckMode getAckMode() { return AckMode.ALL; }
}
```

### Strategy Pattern: PartitionStrategy

```java
/**
 * STRATEGY PATTERN: Determines which partition receives a message.
 * WHY: Round-robin for even distribution; KeyHash for ordering by key.
 * Producer delegates—add Sticky, Range strategies without modifying Producer.
 *
 * SOLID: Dependency Inversion - Producer depends on interface.
 */
public interface PartitionStrategy {
    int selectPartition(Message message, int partitionCount);
}

public class RoundRobinStrategy implements PartitionStrategy {
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public int selectPartition(Message message, int partitionCount) {
        return Math.abs(counter.getAndIncrement() % partitionCount);
    }
}

public class KeyHashStrategy implements PartitionStrategy {
    @Override
    public int selectPartition(Message message, int partitionCount) {
        String key = message.getPartitionKey();
        if (key == null) return 0;
        return Math.abs(key.hashCode() % partitionCount);
    }
}
```

### Observer Pattern: MessageListener

```java
/**
 * OBSERVER PATTERN: Listener for new messages.
 * WHY: Decouples Broker from consumer implementations. Broker notifies all listeners
 * when new messages arrive; listeners (consumers) react. Add new consumer types
 * without modifying Broker.
 *
 * SOLID: Open/Closed - new listeners without changing Broker.
 */
public interface MessageListener {
    void onMessage(String topicName, int partitionIndex, Message message, long offset);
}

/**
 * Consumer implements MessageListener to receive messages.
 * OBSERVER: Registers with Broker; receives callbacks on new messages.
 */
public class Consumer implements MessageListener {
    private final String consumerGroupName;
    private final Broker broker;
    private final Set<String> subscribedTopics;

    public Consumer(String consumerGroupName, Broker broker) {
        this.consumerGroupName = consumerGroupName;
        this.broker = broker;
        this.subscribedTopics = new HashSet<>();
    }

    public void subscribe(String topicName) {
        subscribedTopics.add(topicName);
        broker.registerListener(topicName, this);
    }

    @Override
    public void onMessage(String topicName, int partitionIndex, Message message, long offset) {
        if (subscribedTopics.contains(topicName)) {
            // Process message; in real impl, would ack to broker
            message.setStatus(MessageStatus.DELIVERED);
        }
    }

    public void acknowledge(String topicName, int partitionIndex, long offset) {
        broker.acknowledge(consumerGroupName, topicName, partitionIndex, offset);
    }
}
```

### Producer and Consumer Interfaces

```java
/**
 * Producer interface for publishing messages.
 * SOLID: Interface Segregation - produce-only contract.
 * DEPENDENCY INVERSION: Implementations depend on DeliveryStrategy, PartitionStrategy interfaces.
 */
public interface Producer {
    void send(String topicName, Message message);
}

/**
 * Producer implementation with Strategy injection.
 * CONSTRUCTOR INJECTION: Dependencies (DeliveryStrategy, PartitionStrategy) injected.
 * WHY: Testability—inject mocks; Flexibility—swap strategies at runtime.
 *
 * SOLID: Dependency Inversion - depends on abstractions.
 */
public class DefaultProducer implements Producer {
    private final Broker broker;
    private final DeliveryStrategy deliveryStrategy;
    private final PartitionStrategy partitionStrategy;

    public DefaultProducer(Broker broker, DeliveryStrategy deliveryStrategy, PartitionStrategy partitionStrategy) {
        this.broker = broker;
        this.deliveryStrategy = deliveryStrategy;
        this.partitionStrategy = partitionStrategy;
    }

    @Override
    public void send(String topicName, Message message) {
        Topic topic = broker.getTopic(topicName);
        if (topic == null) throw new IllegalArgumentException("Topic not found: " + topicName);

        int partitionIndex = partitionStrategy.selectPartition(message, topic.getPartitionCount());
        broker.publish(topicName, partitionIndex, message);
    }
}
```

### Broker Class

```java
/**
 * Broker - central message router and storage.
 * SINGLETON (in-memory): Single instance manages all topics/partitions.
 * OBSERVER: Maintains listener registry; notifies on new messages.
 *
 * SOLID: Single Responsibility - topic/partition management, routing, notification.
 */
public class Broker {
    private final Map<String, Topic> topics;
    private final Map<String, List<MessageListener>> topicListeners;
    private final Map<String, ConsumerGroup> consumerGroups;

    public Broker() {
        this.topics = new ConcurrentHashMap<>();
        this.topicListeners = new ConcurrentHashMap<>();
        this.consumerGroups = new ConcurrentHashMap<>();
    }

    public Topic createTopic(String name, int partitionCount) {
        return topics.computeIfAbsent(name, n -> new Topic(n, partitionCount));
    }

    public Topic getTopic(String name) {
        return topics.get(name);
    }

    public void registerListener(String topicName, MessageListener listener) {
        topicListeners.computeIfAbsent(topicName, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void publish(String topicName, int partitionIndex, Message message) {
        Topic topic = topics.get(topicName);
        if (topic == null) throw new IllegalArgumentException("Topic not found: " + topicName);

        Partition partition = topic.getPartition(partitionIndex);
        long offset = partition.append(message);

        // OBSERVER: Notify all listeners
        List<MessageListener> listeners = topicListeners.getOrDefault(topicName, List.of());
        for (MessageListener l : listeners) {
            l.onMessage(topicName, partitionIndex, message, offset);
        }
    }

    public List<Message> consume(String topicName, String groupName, int partitionIndex, long fromOffset, int maxCount) {
        ConsumerGroup group = consumerGroups.computeIfAbsent(groupName, ConsumerGroup::new);
        Topic topic = topics.get(topicName);
        if (topic == null) return List.of();

        Partition partition = topic.getPartition(partitionIndex);
        long startOffset = fromOffset >= 0 ? fromOffset : group.getOffset(partition.getTopicId() + "-" + partitionIndex);
        return partition.getMessagesAfter(startOffset, maxCount);
    }

    public void acknowledge(String groupName, String topicName, int partitionIndex, long offset) {
        ConsumerGroup group = consumerGroups.get(groupName);
        if (group != null) {
            String partitionId = topicName + "-" + partitionIndex;
            group.commitOffset(partitionId, offset);
        }
    }
}
```

### Factory

```java
/**
 * FACTORY PATTERN: Creates Producer/Consumer with correct dependencies.
 * WHY: Centralizes wiring. Clients call createProducer(AT_LEAST_ONCE) instead of
 * manually constructing DefaultProducer with AtLeastOnceStrategy, KeyHashStrategy, etc.
 *
 * SOLID: Dependency Inversion - factory injects concrete strategies.
 */
public class MessageQueueFactory {
    private final Broker broker;

    public MessageQueueFactory(Broker broker) {
        this.broker = broker;
    }

    public Producer createProducer(DeliveryGuarantee guarantee, boolean useKeyPartitioning) {
        DeliveryStrategy deliveryStrategy = switch (guarantee) {
            case AT_MOST_ONCE -> new AtMostOnceStrategy();
            case AT_LEAST_ONCE -> new AtLeastOnceStrategy();
            case EXACTLY_ONCE -> new ExactlyOnceStrategy();
        };
        PartitionStrategy partitionStrategy = useKeyPartitioning ? new KeyHashStrategy() : new RoundRobinStrategy();
        return new DefaultProducer(broker, deliveryStrategy, partitionStrategy);
    }

    public Consumer createConsumer(String groupName) {
        return new Consumer(groupName, broker);
    }
}
```

---

## Edge Cases & Tests

```java
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

public class MessageQueueTest {

    private Broker broker;
    private MessageQueueFactory factory;

    @BeforeEach
    void setUp() {
        broker = new Broker();
        factory = new MessageQueueFactory(broker);
        broker.createTopic("orders", 4);
    }

    @Test
    void testProduceAndConsume_RoundRobin_DistributesEvenly() {
        Producer producer = factory.createProducer(DeliveryGuarantee.AT_LEAST_ONCE, false);
        List<Integer> partitionCounts = new ArrayList<>(List.of(0, 0, 0, 0));

        for (int i = 0; i < 8; i++) {
            Message msg = Message.builder(("msg" + i).getBytes()).build();
            producer.send("orders", msg);
        }

        Topic topic = broker.getTopic("orders");
        for (Partition p : topic.getPartitions()) {
            assertTrue(p.getNextOffset() >= 1, "Each partition should have at least 1 message");
        }
    }

    @Test
    void testKeyHashStrategy_SameKeySamePartition() {
        Producer producer = factory.createProducer(DeliveryGuarantee.AT_LEAST_ONCE, true);
        Message m1 = Message.builder("a".getBytes()).partitionKey("user_123").build();
        Message m2 = Message.builder("b".getBytes()).partitionKey("user_123").build();

        producer.send("orders", m1);
        producer.send("orders", m2);

        Topic topic = broker.getTopic("orders");
        long countWithMessages = topic.getPartitions().stream()
                .filter(p -> p.getNextOffset() > 0)
                .count();
        assertEquals(1, countWithMessages, "Same key should go to same partition");
    }

    @Test
    void testConsumerGroup_OffsetTracking() {
        Producer producer = factory.createProducer(DeliveryGuarantee.AT_LEAST_ONCE, false);
        producer.send("orders", Message.builder("m1".getBytes()).build());
        producer.send("orders", Message.builder("m2".getBytes()).build());

        List<Message> batch1 = broker.consume("orders", "group1", 0, -1, 1);
        assertEquals(1, batch1.size());
        broker.acknowledge("group1", "orders", 0, 0);

        List<Message> batch2 = broker.consume("orders", "group1", 0, -1, 1);
        assertEquals(1, batch2.size());
        assertEquals("m2", new String(batch2.get(0).getPayload()));
    }

    @Test
    void testProduceToNonExistentTopic_Throws() {
        Producer producer = factory.createProducer(DeliveryGuarantee.AT_MOST_ONCE, false);
        Message msg = Message.builder("x".getBytes()).build();

        assertThrows(IllegalArgumentException.class, () -> producer.send("nonexistent", msg));
    }

    @Test
    void testMessageBuilder_OptionalFields() {
        Message msg = Message.builder("payload".getBytes())
                .partitionKey("key1")
                .headers(Map.of("trace", "abc"))
                .build();

        assertEquals("key1", msg.getPartitionKey());
        assertEquals("abc", msg.getHeaders().get("trace"));
        assertEquals(MessageStatus.PENDING, msg.getStatus());
    }

    @Test
    void testDeliveryStrategies_DifferentAckModes() {
        assertEquals(AckMode.NONE, new AtMostOnceStrategy().getAckMode());
        assertEquals(AckMode.LEADER_ONLY, new AtLeastOnceStrategy().getAckMode());
        assertEquals(AckMode.ALL, new ExactlyOnceStrategy().getAckMode());
    }

    @Test
    void testObserver_ListenerReceivesMessages() {
        List<Message> received = new CopyOnWriteArrayList<>();
        MessageListener listener = (topic, partition, msg, offset) -> received.add(msg);

        broker.registerListener("orders", listener);
        Producer producer = factory.createProducer(DeliveryGuarantee.AT_MOST_ONCE, false);
        producer.send("orders", Message.builder("hello".getBytes()).build());

        assertEquals(1, received.size());
        assertEquals("hello", new String(received.get(0).getPayload()));
    }

    @Test
    void testAtLeastOnce_ShouldRetry() {
        AtLeastOnceStrategy strategy = new AtLeastOnceStrategy();
        Message msg = Message.builder("x".getBytes()).build();
        assertTrue(strategy.shouldRetry(msg, 0));
        assertTrue(strategy.shouldRetry(msg, 2));
        assertFalse(strategy.shouldRetry(msg, 3));
    }
}
```

---

## Summary

| Aspect | Summary |
|--------|---------|
| **Problem** | Message queue with topics, partitions, delivery guarantees, consumer groups |
| **Patterns** | Strategy (delivery, partitioning), Observer (listeners), Factory, Builder, State |
| **SOLID** | SRP (Broker/Producer/Consumer), OCP (new strategies), DIP (constructor injection) |
| **Key Classes** | `Broker`, `Producer`, `Consumer`, `Topic`, `Partition`, `Message`, `DeliveryStrategy`, `PartitionStrategy` |
| **Enums** | `DeliveryGuarantee`, `MessageStatus`, `AckMode` |
| **Storage** | Topics → Partitions → Messages; ConsumerGroups → Offsets per partition |
| **Tests** | Round-robin, key-hash, offset tracking, error handling, builder, strategies, observer |

---

**Use this document for LLD/Machine Coding interviews on message queue systems. Focus on explaining patterns, SOLID, and trade-offs (throughput vs ordering, durability vs latency).**
