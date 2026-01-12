# Fanout Pattern

## What is Fanout?

Fanout is a messaging pattern where a single message is delivered to multiple destinations simultaneously. One input produces many outputs.

```
                    ┌──→ Consumer A
                    │
Publisher ──→ Fanout┼──→ Consumer B
                    │
                    └──→ Consumer C
```

---

## Types of Fanout

### 1. Fanout on Write

Pre-compute and distribute data when an event occurs.

```
User posts update → Write to all followers' feeds immediately

┌─────────────────────────────────────────────────────┐
│                    Post Created                      │
│                         │                            │
│          ┌──────────────┼──────────────┐            │
│          │              │              │            │
│          ▼              ▼              ▼            │
│   ┌──────────┐   ┌──────────┐   ┌──────────┐       │
│   │Follower A│   │Follower B│   │Follower C│       │
│   │  Feed    │   │  Feed    │   │  Feed    │       │
│   └──────────┘   └──────────┘   └──────────┘       │
└─────────────────────────────────────────────────────┘
```

**Pros:**
- Fast reads (pre-computed)
- Simple read path
- Low read latency

**Cons:**
- High write amplification
- Storage overhead
- Slow writes for popular users (celebrities)

**Use When:**
- Read-heavy workloads
- Most users have few followers
- Read latency is critical

### 2. Fanout on Read

Compute data when requested.

```
User requests feed → Query all followed users' posts → Merge

┌─────────────────────────────────────────────────────┐
│                   User Requests Feed                 │
│                         │                            │
│                         ▼                            │
│                  ┌─────────────┐                    │
│                  │Query Engine │                    │
│                  └──────┬──────┘                    │
│          ┌──────────────┼──────────────┐           │
│          │              │              │           │
│          ▼              ▼              ▼           │
│   ┌──────────┐   ┌──────────┐   ┌──────────┐      │
│   │ User A   │   │ User B   │   │ User C   │      │
│   │ Posts    │   │ Posts    │   │ Posts    │      │
│   └──────────┘   └──────────┘   └──────────┘      │
│                         │                          │
│                         ▼                          │
│                  ┌─────────────┐                   │
│                  │Merge & Sort │                   │
│                  └─────────────┘                   │
└─────────────────────────────────────────────────────┘
```

**Pros:**
- Simple writes
- No storage overhead
- Always fresh data

**Cons:**
- Slow reads (compute on demand)
- High read latency
- Complex aggregation

**Use When:**
- Write-heavy workloads
- Following many users
- Storage is limited

### 3. Hybrid Approach

Combine both based on user characteristics.

```
Celebrity (1M followers) → Fanout on Read
Regular user (100 followers) → Fanout on Write

┌────────────────────────────────────────────────────────────┐
│                      New Post                               │
│                         │                                   │
│                         ▼                                   │
│              ┌──────────────────┐                          │
│              │ Celebrity Check  │                          │
│              └────────┬─────────┘                          │
│                       │                                    │
│         ┌─────────────┴─────────────┐                     │
│         │                           │                     │
│    Followers < 1000            Followers > 1000           │
│         │                           │                     │
│         ▼                           ▼                     │
│   Fanout on Write              Store Post Only            │
│   (Push to feeds)              (Pull on read)             │
└────────────────────────────────────────────────────────────┘
```

---

## Fanout in Message Queues

### RabbitMQ Fanout Exchange

```python
import pika

# Publisher
connection = pika.BlockingConnection(pika.ConnectionParameters('localhost'))
channel = connection.channel()

# Declare fanout exchange
channel.exchange_declare(exchange='notifications', exchange_type='fanout')

# Publish message (goes to all bound queues)
channel.basic_publish(
    exchange='notifications',
    routing_key='',  # Ignored for fanout
    body='New notification!'
)

# Consumer (each consumer gets a copy)
channel.queue_declare(queue='email_notifications')
channel.queue_bind(exchange='notifications', queue='email_notifications')

channel.queue_declare(queue='push_notifications')
channel.queue_bind(exchange='notifications', queue='push_notifications')
```

### AWS SNS Fanout

```python
import boto3

sns = boto3.client('sns')

# Create topic
topic = sns.create_topic(Name='order-events')
topic_arn = topic['TopicArn']

# Subscribe multiple endpoints
sns.subscribe(TopicArn=topic_arn, Protocol='sqs', Endpoint='arn:aws:sqs:...:email-queue')
sns.subscribe(TopicArn=topic_arn, Protocol='sqs', Endpoint='arn:aws:sqs:...:inventory-queue')
sns.subscribe(TopicArn=topic_arn, Protocol='lambda', Endpoint='arn:aws:lambda:...:process-order')

# Publish (fans out to all subscribers)
sns.publish(
    TopicArn=topic_arn,
    Message=json.dumps({'order_id': '123', 'status': 'completed'})
)
```

### Kafka Consumer Groups (Inverse Fanout)

```
Topic with 4 partitions → Consumer Group with 4 consumers
Each partition → One consumer (load distribution, not fanout)

For true fanout in Kafka:
- Multiple consumer groups
- Each group gets all messages
```

---

## Real-World Examples

### Twitter/X Feed

```
User posts tweet:

Option A: Fanout on Write
  - Push tweet to all 10M followers' timelines
  - Fast reads, slow writes
  - Problems for celebrities

Option B: Fanout on Read  
  - Store tweet once
  - On timeline request: query all followed users
  - Slow reads, fast writes

Twitter's Solution: Hybrid
  - Regular users: Fanout on Write
  - Celebrities: Fanout on Read
  - Merge at read time
```

### Notification System

```
Order Completed Event
        │
        ▼
   ┌─────────┐
   │ Fanout  │
   └────┬────┘
        │
   ┌────┼────┬─────────┐
   │    │    │         │
   ▼    ▼    ▼         ▼
 Email Push  SMS    Analytics
```

### Cache Invalidation

```
Data Updated
     │
     ▼
┌─────────┐
│ Fanout  │
└────┬────┘
     │
┌────┼────┬────────┐
│    │    │        │
▼    ▼    ▼        ▼
CDN  App  Browser  API
Edge Cache Cache   Cache
```

---

## Implementation Patterns

### Database Fanout (Social Feed)

```python
class FeedService:
    def __init__(self, redis, db):
        self.redis = redis
        self.db = db
        self.CELEBRITY_THRESHOLD = 10000
    
    def post(self, user_id, content):
        # Create post
        post_id = self.db.posts.insert({
            'user_id': user_id,
            'content': content,
            'created_at': datetime.utcnow()
        })
        
        follower_count = self.get_follower_count(user_id)
        
        if follower_count < self.CELEBRITY_THRESHOLD:
            # Fanout on write for regular users
            self.fanout_to_followers(user_id, post_id)
        # Celebrities: posts fetched on read
        
        return post_id
    
    def fanout_to_followers(self, user_id, post_id):
        followers = self.get_followers(user_id)
        
        # Batch push to follower feeds
        pipe = self.redis.pipeline()
        for follower_id in followers:
            feed_key = f"feed:{follower_id}"
            pipe.lpush(feed_key, post_id)
            pipe.ltrim(feed_key, 0, 999)  # Keep last 1000
        pipe.execute()
    
    def get_feed(self, user_id):
        # Get pre-computed feed
        feed_key = f"feed:{user_id}"
        post_ids = self.redis.lrange(feed_key, 0, 49)
        
        # Also fetch from celebrities followed
        celebrity_posts = self.get_celebrity_posts(user_id)
        
        # Merge and sort
        all_posts = self.get_posts(post_ids + celebrity_posts)
        return sorted(all_posts, key=lambda p: p['created_at'], reverse=True)[:50]
```

### Event Fanout with Async Processing

```python
import asyncio
from dataclasses import dataclass

@dataclass
class Event:
    type: str
    data: dict

class EventFanout:
    def __init__(self):
        self.handlers = {}
    
    def subscribe(self, event_type, handler):
        if event_type not in self.handlers:
            self.handlers[event_type] = []
        self.handlers[event_type].append(handler)
    
    async def publish(self, event: Event):
        handlers = self.handlers.get(event.type, [])
        
        # Fanout to all handlers concurrently
        tasks = [handler(event) for handler in handlers]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        
        # Log any failures
        for handler, result in zip(handlers, results):
            if isinstance(result, Exception):
                print(f"Handler {handler.__name__} failed: {result}")

# Usage
fanout = EventFanout()

async def send_email(event):
    print(f"Sending email for {event.data}")

async def update_analytics(event):
    print(f"Updating analytics for {event.data}")

async def notify_slack(event):
    print(f"Notifying Slack for {event.data}")

fanout.subscribe('order.completed', send_email)
fanout.subscribe('order.completed', update_analytics)
fanout.subscribe('order.completed', notify_slack)

# Single event fans out to 3 handlers
await fanout.publish(Event('order.completed', {'order_id': '123'}))
```

---

## Best Practices

### Do's ✅
1. Use hybrid approach for variable audience sizes
2. Implement async fanout for scalability
3. Set limits on fanout (max followers)
4. Monitor fanout latency
5. Use batching for efficiency

### Don'ts ❌
1. Synchronous fanout to millions
2. Unbounded fanout operations
3. Ignore failure handling
4. Skip idempotency checks

---

## Interview Questions

**Q: Explain fanout on write vs fanout on read.**
> Write: push data to all recipients when event occurs (fast reads, slow writes). Read: compute/aggregate when requested (slow reads, fast writes). Choose based on read/write ratio and follower distribution.

**Q: How would you design Twitter's timeline?**
> Hybrid approach: regular users get fanout on write (push to followers' feeds). Celebrities (>10K followers) use fanout on read. Merge at read time. Use Redis for pre-computed feeds, database for celebrity posts.

**Q: How do you handle fanout to millions of users?**
> Async processing with queues, batch operations, rate limiting, priority queues (close friends first), eventual consistency, use fanout on read for very large audiences.

---

## Quick Reference

| Approach | Write Speed | Read Speed | Storage | Best For |
|----------|-------------|------------|---------|----------|
| Fanout on Write | Slow | Fast | High | Read-heavy |
| Fanout on Read | Fast | Slow | Low | Write-heavy |
| Hybrid | Medium | Medium | Medium | Variable audience |
