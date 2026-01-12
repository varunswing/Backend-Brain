# B2B Notification Service - System Design Interview

## Problem Statement
*"Design a B2B notification service like SendGrid that handles multi-channel notifications (email, SMS, push) for enterprise clients with high throughput and delivery tracking."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What channels?" → Email, SMS, push notifications, webhooks, Slack
- "Do we need templates?" → Yes, dynamic templates with personalization
- "Should we support bulk campaigns?" → Yes, marketing to millions of recipients
- "Do we need delivery tracking?" → Yes, real-time status, open/click tracking
- "What about rate limiting?" → Yes, per-client limits, throttling
- "Should we support scheduling?" → Yes, scheduled sends, timezone optimization

**Non-Functional Requirements:**
- "What's our throughput?" → 1M+ notifications/hour, 10K+ RPS peak
- "Expected latency?" → <30 seconds transactional, <1 hour bulk
- "How many clients?" → 10K+ enterprise clients, multi-tenant
- "Availability?" → 99.99% uptime (business critical)

### Requirements Summary:
- **Scale**: 1M+ notifications/hour, 10K clients, global delivery
- **Channels**: Email, SMS, push, webhooks, chat integrations
- **Features**: Templates, bulk campaigns, scheduling, tracking
- **Performance**: <30s transactional, 99.99% uptime

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Volume:
```
Hourly notifications: 1M/hour
Peak RPS: 10K requests/second
Daily volume: 24M notifications/day
Clients: 10K enterprise clients
Channel split: 70% email, 20% SMS, 8% push, 2% webhook
```

### Storage Requirements:
```
Templates: 100K × 50KB = 5GB
Message queue: 10M pending × 5KB = 50GB
Delivery logs: 24M/day × 2KB × 90 days = 4.3TB
Client data: 10K clients × 1MB = 10GB
Total: ~5TB with retention
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐
│Client Apps  │  │Admin Portal │
│- API Calls  │  │- Templates  │
└─────────────┘  └─────────────┘
       │                │
       └────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│         API Gateway             │
│- Auth - Rate limits - Tracking │
└─────────────────────────────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Notification│ │Template │ │Campaign  │
│API        │ │Service  │ │Service   │
│           │ │         │ │          │
│- Validate │ │- Render │ │- Bulk    │
│- Enqueue  │ │- A/B    │ │- Schedule│
└──────────┘ └──────────┘ └──────────┘
                │
                ▼
┌─────────────────────────────────┐
│         Apache Kafka            │
│                                 │
│ Email Queue | SMS Queue | Push  │
└─────────────────────────────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Email     │ │SMS       │ │Push      │
│Processor │ │Processor │ │Processor │
│          │ │          │ │          │
│- SendGrid│ │- Twilio  │ │- FCM     │
│- AWS SES │ │- AWS SNS │ │- APNS    │
└──────────┘ └──────────┘ └──────────┘
                │
                ▼
┌─────────────────────────────────┐
│      Supporting Services        │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │Delivery │ │Analytics│ │Rate │ │
│ │Tracker  │ │Service  │ │Limit│ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│          Data Layer             │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │PostgreSQL│ │Redis    │ │ClickH│ │
│ │- Clients│ │- Cache  │ │-Events│ │
│ │-Templates│ │- Limits │ │-Logs │ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
```

### Key Components:
- **Notification API**: Request handling, validation, enqueueing
- **Template Service**: Dynamic rendering with personalization
- **Message Processors**: Channel-specific delivery logic
- **Delivery Tracker**: Status updates, webhooks, retry logic

---

## Phase 4: Database Design (8 minutes)

### PostgreSQL Schema:
```sql
-- Clients (tenants)
CREATE TABLE clients (
    client_id UUID PRIMARY KEY,
    client_name VARCHAR(200),
    api_key VARCHAR(100) UNIQUE,
    plan_type VARCHAR(50) DEFAULT 'standard',
    monthly_quota INTEGER DEFAULT 100000,
    rate_limit_per_minute INTEGER DEFAULT 1000,
    webhook_url TEXT,
    client_status VARCHAR(20) DEFAULT 'active',
    
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_clients_api_key (api_key)
);

-- Templates
CREATE TABLE templates (
    template_id UUID PRIMARY KEY,
    client_id UUID REFERENCES clients(client_id),
    template_name VARCHAR(300),
    channel VARCHAR(20) NOT NULL,
    subject_template TEXT,
    body_template TEXT NOT NULL,
    template_variables JSONB DEFAULT '[]',
    is_active BOOLEAN DEFAULT true,
    
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_templates_client_channel (client_id, channel)
);

-- Notifications
CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY,
    client_id UUID REFERENCES clients(client_id),
    template_id UUID REFERENCES templates(template_id),
    recipient_email VARCHAR(255),
    recipient_phone VARCHAR(20),
    channel VARCHAR(20) NOT NULL,
    notification_status VARCHAR(30) DEFAULT 'queued',
    provider_name VARCHAR(50),
    provider_message_id VARCHAR(200),
    
    scheduled_at TIMESTAMP DEFAULT NOW(),
    sent_at TIMESTAMP,
    delivered_at TIMESTAMP,
    failed_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_notifications_client_status (client_id, notification_status),
    INDEX idx_notifications_scheduled (scheduled_at, notification_status)
);
```

### Redis Caching:
```javascript
// Rate limiting
"rate_limit:{client_id}:{minute}": {
  "count": 450,
  "limit": 1000,
  "ttl": 60
}

// Template cache
"template:{template_id}": {
  "subject": "Welcome {{user_name}}!",
  "body": "Hello {{user_name}}!",
  "ttl": 3600
}

// Delivery status
"delivery_status:{notification_id}": {
  "status": "delivered",
  "provider": "sendgrid",
  "ttl": 86400
}
```

---

## Phase 5: Critical Flow - Notification Processing (8 minutes)

### Step-by-Step Flow:
```
1. API request:
   POST /api/v1/notifications
   {"template_id": "uuid", "recipient": "user@example.com", "data": {...}}

2. Validation:
   - Authenticate client via API key
   - Check rate limits and quotas
   - Validate template exists

3. Template rendering:
   - Load template from cache
   - Substitute variables with data
   - Apply A/B test logic if configured

4. Enqueueing:
   - Create notification record
   - Add to Kafka topic (email/sms/push)
   - Return notification ID

5. Processing:
   - Worker pulls from queue
   - Select provider (SendGrid, Twilio)
   - Send via provider API
   - Update status and track delivery
```

### Technical Challenges:
**Multi-Provider Management**: "Fallback providers, load balancing"
**Template Rendering**: "Safe variable substitution, performance"
**Rate Limiting**: "Per-client quotas, burst handling"
**Delivery Tracking**: "Real-time status, webhook reliability"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **API throughput** - 10K+ RPS handling
2. **Queue processing** - 1M+ messages/hour
3. **Provider limits** - External API rate limits
4. **Database writes** - High-frequency status updates

### Scaling Solutions:
**API**: Horizontal scaling, Redis caching, connection pooling
**Queue**: Kafka partitioning, parallel processing, dead letter queues
**Providers**: Multiple integrations, circuit breakers, dynamic routing
**Database**: Read replicas, data archiving, batch updates

### Trade-offs:
- **Speed vs Cost**: Premium providers vs standard delivery
- **Reliability vs Latency**: Strong guarantees vs fast processing
- **Personalization vs Performance**: Complex templates vs simple rendering

---

## Success Metrics:
- **Throughput**: >1M notifications/hour
- **Delivery Rate**: >99% success rate
- **API Latency**: <100ms P95
- **Processing Latency**: <30s transactional
- **Uptime**: >99.9% availability

**🎯 Demonstrates high-throughput messaging, multi-tenancy, and communication infrastructure used by businesses.**