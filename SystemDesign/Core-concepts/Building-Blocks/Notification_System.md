# Notification System Design

## Overview

A notification system delivers messages to users across multiple channels (email, push, SMS, in-app).

```
┌─────────────────────────────────────────────────────────────────┐
│                    Notification System                           │
│                                                                  │
│  Event ──→ [Notification Service] ──→ [Channel Router] ──→      │
│                     │                        │                   │
│                     ▼                        ├──→ Email          │
│              [Template Engine]               ├──→ Push           │
│                     │                        ├──→ SMS            │
│                     ▼                        └──→ In-App         │
│              [User Preferences]                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Core Components

### 1. Event Ingestion

```python
from dataclasses import dataclass
from enum import Enum

class NotificationType(Enum):
    ORDER_CONFIRMED = "order_confirmed"
    PAYMENT_RECEIVED = "payment_received"
    SHIPMENT_UPDATE = "shipment_update"
    PASSWORD_RESET = "password_reset"
    MARKETING = "marketing"

@dataclass
class NotificationEvent:
    event_type: NotificationType
    user_id: str
    data: dict
    priority: str = "normal"  # high, normal, low
    scheduled_at: datetime = None

# API to trigger notifications
@app.post('/api/notifications')
def create_notification(event: NotificationEvent):
    # Validate
    validate_event(event)
    
    # Queue for processing
    notification_queue.publish(event)
    
    return {'status': 'queued'}
```

### 2. User Preferences

```python
class NotificationPreferences:
    def __init__(self, db):
        self.db = db
    
    def get_preferences(self, user_id: str) -> dict:
        return self.db.find_one('notification_preferences', {'user_id': user_id}) or {
            'email': True,
            'push': True,
            'sms': False,
            'in_app': True,
            'quiet_hours': {'start': '22:00', 'end': '08:00'},
            'frequency': 'realtime',  # realtime, digest, weekly
            'unsubscribed': []  # List of notification types
        }
    
    def should_send(self, user_id: str, channel: str, notification_type: str) -> bool:
        prefs = self.get_preferences(user_id)
        
        # Check channel enabled
        if not prefs.get(channel, False):
            return False
        
        # Check unsubscribed
        if notification_type in prefs.get('unsubscribed', []):
            return False
        
        # Check quiet hours (except high priority)
        if self._is_quiet_hours(prefs):
            return False
        
        return True
```

### 3. Template Engine

```python
from jinja2 import Environment, FileSystemLoader

class NotificationTemplate:
    def __init__(self, template_dir):
        self.env = Environment(loader=FileSystemLoader(template_dir))
    
    def render(self, notification_type: str, channel: str, data: dict, locale: str = 'en') -> dict:
        # Load template
        template_name = f"{notification_type}/{channel}/{locale}.html"
        template = self.env.get_template(template_name)
        
        # Render
        content = template.render(**data)
        
        # Get subject for email
        subject_template = self.env.get_template(f"{notification_type}/subject/{locale}.txt")
        subject = subject_template.render(**data)
        
        return {
            'subject': subject,
            'body': content
        }

# Template example (order_confirmed/email/en.html)
"""
<html>
<body>
    <h1>Order Confirmed!</h1>
    <p>Hi {{ user_name }},</p>
    <p>Your order #{{ order_id }} has been confirmed.</p>
    <p>Total: ${{ total }}</p>
    <p>Expected delivery: {{ delivery_date }}</p>
</body>
</html>
"""
```

### 4. Channel Providers

```python
from abc import ABC, abstractmethod

class NotificationChannel(ABC):
    @abstractmethod
    def send(self, recipient: str, content: dict) -> bool:
        pass

class EmailChannel(NotificationChannel):
    def __init__(self, smtp_config):
        self.smtp = SMTPClient(smtp_config)
    
    def send(self, recipient: str, content: dict) -> bool:
        return self.smtp.send_email(
            to=recipient,
            subject=content['subject'],
            body=content['body']
        )

class PushChannel(NotificationChannel):
    def __init__(self, fcm_config):
        self.fcm = FCMClient(fcm_config)
    
    def send(self, recipient: str, content: dict) -> bool:
        # Get user's device tokens
        tokens = self.get_device_tokens(recipient)
        
        for token in tokens:
            self.fcm.send(
                token=token,
                title=content['title'],
                body=content['body'],
                data=content.get('data', {})
            )
        return True

class SMSChannel(NotificationChannel):
    def __init__(self, twilio_config):
        self.twilio = TwilioClient(twilio_config)
    
    def send(self, recipient: str, content: dict) -> bool:
        phone = self.get_phone_number(recipient)
        return self.twilio.send_sms(to=phone, body=content['body'])

class InAppChannel(NotificationChannel):
    def __init__(self, db, websocket_manager):
        self.db = db
        self.ws = websocket_manager
    
    def send(self, recipient: str, content: dict) -> bool:
        # Store in database
        notification_id = self.db.insert('in_app_notifications', {
            'user_id': recipient,
            'title': content['title'],
            'body': content['body'],
            'read': False,
            'created_at': datetime.utcnow()
        })
        
        # Push via WebSocket if online
        self.ws.send_to_user(recipient, {
            'type': 'notification',
            'data': content
        })
        
        return True
```

### 5. Notification Processor

```python
class NotificationProcessor:
    def __init__(self, preferences, templates, channels):
        self.preferences = preferences
        self.templates = templates
        self.channels = channels
    
    def process(self, event: NotificationEvent):
        user = self.get_user(event.user_id)
        
        # Determine which channels to use
        for channel_name, channel in self.channels.items():
            # Check preferences
            if not self.preferences.should_send(
                event.user_id, 
                channel_name, 
                event.event_type.value
            ):
                continue
            
            try:
                # Render content
                content = self.templates.render(
                    event.event_type.value,
                    channel_name,
                    {**event.data, 'user': user}
                )
                
                # Send
                success = channel.send(event.user_id, content)
                
                # Log
                self.log_delivery(event, channel_name, success)
                
            except Exception as e:
                self.log_error(event, channel_name, e)
                # Retry logic handled by queue
                raise
```

---

## Advanced Features

### Rate Limiting

```python
class NotificationRateLimiter:
    """Prevent notification spam"""
    
    LIMITS = {
        'email': {'count': 10, 'period': 3600},    # 10/hour
        'push': {'count': 20, 'period': 3600},     # 20/hour
        'sms': {'count': 5, 'period': 86400},      # 5/day
    }
    
    def __init__(self, redis):
        self.redis = redis
    
    def is_allowed(self, user_id: str, channel: str) -> bool:
        key = f"notif_rate:{user_id}:{channel}"
        limit = self.LIMITS.get(channel, {'count': 100, 'period': 3600})
        
        count = self.redis.incr(key)
        if count == 1:
            self.redis.expire(key, limit['period'])
        
        return count <= limit['count']
```

### Deduplication

```python
class NotificationDeduplicator:
    """Prevent duplicate notifications"""
    
    def __init__(self, redis):
        self.redis = redis
    
    def is_duplicate(self, event: NotificationEvent) -> bool:
        # Create unique key based on event
        key = f"notif_dedup:{event.user_id}:{event.event_type}:{hash(str(event.data))}"
        
        # Check if sent recently (1 hour)
        if self.redis.exists(key):
            return True
        
        # Mark as sent
        self.redis.setex(key, 3600, '1')
        return False
```

### Batching/Digest

```python
class NotificationBatcher:
    """Batch notifications into digests"""
    
    def __init__(self, redis, db):
        self.redis = redis
        self.db = db
    
    def add_to_batch(self, event: NotificationEvent):
        """Add notification to user's batch"""
        key = f"notif_batch:{event.user_id}"
        self.redis.rpush(key, json.dumps(event.to_dict()))
        self.redis.expire(key, 86400)  # 24 hour expiry
    
    def send_digest(self, user_id: str):
        """Send batched notifications as digest"""
        key = f"notif_batch:{user_id}"
        
        # Get all batched notifications
        items = self.redis.lrange(key, 0, -1)
        if not items:
            return
        
        notifications = [json.loads(item) for item in items]
        
        # Group by type
        grouped = {}
        for notif in notifications:
            event_type = notif['event_type']
            grouped.setdefault(event_type, []).append(notif)
        
        # Send digest email
        self.send_digest_email(user_id, grouped)
        
        # Clear batch
        self.redis.delete(key)
```

### Priority Queue

```python
class PriorityNotificationQueue:
    """Handle notifications by priority"""
    
    QUEUES = {
        'high': 'notifications:high',      # Password reset, 2FA
        'normal': 'notifications:normal',  # Order updates
        'low': 'notifications:low'         # Marketing
    }
    
    def publish(self, event: NotificationEvent):
        queue = self.QUEUES.get(event.priority, self.QUEUES['normal'])
        self.redis.lpush(queue, json.dumps(event.to_dict()))
    
    def consume(self):
        """Process high priority first"""
        # BRPOP with priority (check high first, then normal, then low)
        result = self.redis.brpop(
            [self.QUEUES['high'], self.QUEUES['normal'], self.QUEUES['low']],
            timeout=1
        )
        if result:
            queue, data = result
            return json.loads(data)
        return None
```

---

## Scaling Considerations

### Architecture for Scale

```
┌─────────────────────────────────────────────────────────────────┐
│                    High-Scale Architecture                       │
│                                                                  │
│   API ──→ [Kafka] ──→ [Worker Pool] ──→ [Channel Queues]       │
│             │              │                  │                  │
│             │         Rate Limiter        ┌───┴───┐             │
│             │              │              │       │             │
│             ▼              ▼              ▼       ▼             │
│      [Event Store]   [Preferences    [Email   [Push            │
│                        Cache]        Workers] Workers]          │
│                        (Redis)                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Database Schema

```sql
-- Notifications log
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    type VARCHAR(100) NOT NULL,
    channel VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,  -- pending, sent, failed, delivered
    content JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    sent_at TIMESTAMP,
    delivered_at TIMESTAMP,
    
    INDEX idx_user_created (user_id, created_at DESC),
    INDEX idx_status (status) WHERE status = 'pending'
);

-- User preferences
CREATE TABLE notification_preferences (
    user_id UUID PRIMARY KEY,
    email_enabled BOOLEAN DEFAULT TRUE,
    push_enabled BOOLEAN DEFAULT TRUE,
    sms_enabled BOOLEAN DEFAULT FALSE,
    quiet_hours_start TIME,
    quiet_hours_end TIME,
    digest_frequency VARCHAR(20) DEFAULT 'realtime',
    unsubscribed_types TEXT[] DEFAULT '{}'
);
```

---

## Best Practices

### Do's ✅
1. Make unsubscribe easy and obvious
2. Respect user preferences and quiet hours
3. Implement rate limiting
4. Use templates for consistency
5. Log all notification attempts
6. Support multiple channels gracefully

### Don'ts ❌
1. Send without user consent
2. Ignore unsubscribe requests
3. Send at inappropriate times
4. Overload users with notifications
5. Use generic messages

---

## Interview Questions

**Q: How would you design a notification system?**
> Components: Event ingestion, User preferences, Template engine, Channel providers (email/push/SMS), Queue for async processing. Key: respect preferences, rate limit, deduplicate, prioritize.

**Q: How do you handle notification at scale?**
> Message queue (Kafka) for ingestion, worker pools per channel, Redis for preferences caching, rate limiting, batching for digests, separate queues by priority.

**Q: How do you prevent notification spam?**
> Rate limiting per user/channel, deduplication by content hash, batching similar notifications, user-controlled frequency settings, priority queues.

---

## Quick Reference

| Component | Purpose |
|-----------|---------|
| Event Queue | Async processing |
| Preferences | User settings |
| Templates | Consistent messaging |
| Rate Limiter | Prevent spam |
| Deduplicator | No duplicates |
| Priority Queue | Urgent first |
