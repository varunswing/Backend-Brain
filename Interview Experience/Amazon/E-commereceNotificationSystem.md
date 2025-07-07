Designing an **E-commerce Order Notification System** involves ensuring reliable, real-time, and asynchronous communication of events like order placed, shipped, delivered, delayed, or cancelled — across **email**, **SMS**, **push notifications**, etc.

---

## ✅ 1. Functional Requirements

1. Notify users when:

   * Order is placed, confirmed, packed, shipped, delivered, or cancelled.
2. Send notifications through multiple channels:

   * Email, SMS, Push, In-app
3. Retry on failure (e.g., SMS fails → retry or fallback to email)
4. Allow users to opt-in/out of notification types
5. Support admin-facing events (e.g., fraud alerts, delivery failure)

---

## ✅ 2. Non-Functional Requirements

| NFR              | Goal                                      |
| ---------------- | ----------------------------------------- |
| **Scalable**     | Should handle millions of orders/day      |
| **Reliable**     | Guaranteed delivery (at least once)       |
| **Asynchronous** | Don’t block order flow                    |
| **Extensible**   | Add new channels easily (WhatsApp, voice) |
| **Low Latency**  | Delivery < 1s after event                 |

---

## ✅ 3. System Components

```
Order Service
   │
   └──▶ Event Bus (Kafka/SQS/PubSub)
             └──▶ Notification Orchestrator
                        ├──▶ Email Service
                        ├──▶ SMS Service
                        ├──▶ Push Notification Service
                        └──▶ User Preference DB
```

---

## ✅ 4. DB Design

### `Users`

```sql
id UUID PK,
email TEXT,
phone TEXT,
push_token TEXT
```

### `NotificationPreferences`

```sql
user_id UUID FK,
event_type TEXT,            -- e.g., ORDER_PLACED, ORDER_SHIPPED
channel TEXT,               -- EMAIL/SMS/PUSH
enabled BOOLEAN
```

### `NotificationEvents`

```sql
event_id UUID PK,
user_id UUID,
event_type TEXT,
channel TEXT,
payload JSONB,
status TEXT,                -- QUEUED/SENT/FAILED
created_at TIMESTAMP
```

---

## ✅ 5. Key Classes & Responsibilities

```java
class NotificationEvent {
    UUID userId;
    EventType type;
    Map<String, String> metadata; // e.g., orderId, trackingUrl
}

interface NotificationChannel {
    boolean send(NotificationEvent event);
}

class EmailNotification implements NotificationChannel { ... }
class SMSNotification implements NotificationChannel { ... }
class PushNotification implements NotificationChannel { ... }
```

```java
class NotificationOrchestrator {
    void handleEvent(NotificationEvent event) {
        List<NotificationChannel> channels = getChannels(event.userId, event.type);
        for (channel : channels) {
            if (!channel.send(event)) retryOrFallback(event);
        }
    }
}
```

---

## ✅ 6. API Endpoints (Internal / Admin)

### 1. Trigger Notification (from Order System)

```http
POST /notifications/trigger
{
  "eventType": "ORDER_SHIPPED",
  "userId": "uuid",
  "metadata": {
    "orderId": "ORD123",
    "trackingUrl": "https://track.com/ORD123"
  }
}
```

### 2. Update Preferences

```http
POST /notifications/preferences
{
  "userId": "uuid",
  "eventType": "ORDER_CANCELLED",
  "channel": "SMS",
  "enabled": false
}
```

---

## ✅ 7. Event Flow (With Kafka)

1. Order Service publishes to Kafka topic: `order-events`
2. `NotificationConsumer` listens and creates `NotificationEvent`
3. NotificationOrchestrator:

   * Fetches user preferences
   * Dispatches to available channels
   * Logs success/failure
4. Failed deliveries go to a **DLQ (Dead Letter Queue)** or **Retry Queue**

---

## ✅ 8. Fault Tolerance

| Strategy             | Details                                      |
| -------------------- | -------------------------------------------- |
| ✅ Retry Queue        | Retry failed events with exponential backoff |
| ✅ DLQ                | Log failed events after N attempts           |
| ✅ Circuit Breakers   | Avoid flooding failing email/SMS APIs        |
| ✅ Timeout & fallback | If SMS fails, fallback to email              |

---

## ✅ 9. Design Patterns Involved

| Pattern             | Role                                           |
| ------------------- | ---------------------------------------------- |
| **Observer**        | Event-driven pub/sub model                     |
| **Strategy**        | Switch between SMS/Email/Push dynamically      |
| **Builder**         | Build message templates per channel            |
| **Template Method** | Abstract send flow with channel-specific logic |
| **Retry Pattern**   | Reliable retry for failures                    |

---

## ✅ 10. Observability

* Track delivery success/failure per channel
* Log retries and fallbacks
* Alert on spike in failures (e.g., SMS provider down)
* Metrics: queue lag, time-to-delivery, latency per channel

---

## ✅ 11. Cross Questions Interviewer Might Ask

| Topic         | Sample Question                                                                 |
| ------------- | ------------------------------------------------------------------------------- |
| Kafka         | What happens if NotificationService crashes after consuming but before sending? |
| Preferences   | How will you store and enforce per-user preferences efficiently?                |
| Retry         | How do you avoid message duplication?                                           |
| Scale         | How would you scale for Black Friday surge in orders?                           |
| Fallback      | How do you handle channel outages (e.g., email service down)?                   |
| Idempotency   | What prevents double-sending the same notification?                             |
| Security      | How do you protect against misuse of SMS/email API?                             |
| Latency       | How do you ensure sub-second delivery?                                          |
| Extensibility | How would you add WhatsApp as a new channel?                                    |
| Testing       | How would you test notification delivery end-to-end?                            |

---

Great! Here's how you can confidently answer each of the **cross questions** for your **E-commerce Notification System**:

---

### ✅ **1. Kafka – What happens if NotificationService crashes after consuming but before sending?**

**Answer:**

* Use **at-least-once delivery semantics**.
* Kafka consumer should **not commit the offset** until the message is successfully delivered to the notification channel.
* On crash recovery:

  * Consumer re-reads the same message.
  * Ensure **idempotency** at the notification-sending layer to avoid duplicate sends.

**Techniques:**

* Manual offset commits
* Atomic DB writes with offset tracking
* Deduplication token (event ID)

---

### ✅ **2. Preferences – How will you store and enforce per-user preferences efficiently?**

**Answer:**

* Store preferences in a dedicated table: `(user_id, event_type, channel, enabled)`
* Load preferences in batch into an in-memory **cache (Redis/Guava)** on service startup.
* Enforce logic in the Notification Orchestrator to skip channels marked as disabled.

**Optimization:**

* Precompute and cache “active channels per event type” for high-performance lookup.

---

### ✅ **3. Retry – How do you avoid message duplication?**

**Answer:**

* Use **deduplication IDs** (e.g., event UUID + channel name).
* Store delivery attempts in a table or cache. Skip if already processed.
* Apply **idempotent send logic** for external APIs (e.g., pass `X-Idempotency-Key` header).

**Bonus Tip:**

* Retry using **exponential backoff** and move permanently failed messages to **DLQ**.

---

### ✅ **4. Scale – How would you scale for Black Friday surge in orders?**

**Answer:**

* **Kafka** decouples event production and consumption (buffering surge).
* **Horizontal scale** Notification Service consumers based on queue lag.
* Use **async processing**, **connection pooling**, and **batch APIs** for downstream services.

**Infra:**

* Auto-scaling (K8s + HPA)
* Rate-limiting & circuit breaking

---

### ✅ **5. Fallback – How do you handle channel outages (e.g., email service down)?**

**Answer:**

* Detect failure via response codes or timeouts.
* Retry with backoff (N times).
* If failed consistently:

  * Fallback to another enabled channel (e.g., push or SMS).
  * Log in DLQ for post-mortem.

**Tip:** Use a **Circuit Breaker pattern** to prevent cascading failures.

---

### ✅ **6. Idempotency – What prevents double-sending the same notification?**

**Answer:**

* Each notification event has a **unique ID** (UUID).
* Before sending, check if `(eventId + channel)` exists in `NotificationEvents` table.
* If yes → skip; else → proceed & mark as SENT.

**Optional:** Use Redis for fast deduplication.

---

### ✅ **7. Security – How do you protect against misuse of SMS/email API?**

**Answer:**

* Expose only internal, authenticated API endpoints (not public).
* Rate-limit notification triggers per user/IP/device.
* Validate payload schema and sender authorization.
* API tokens + scoped permissions (admin vs user).

**Bonus:** Audit logs for all notification events.

---

### ✅ **8. Latency – How do you ensure sub-second delivery?**

**Answer:**

* Use **in-memory queues** or **Kafka with low-latency config** (batch size, linger.ms).
* Keep channel integrations **non-blocking & async** (e.g., use CompletableFuture in Java).
* Optimize network calls using **connection pooling** and **parallel dispatching**.

**Infra Tip:** Use colocated services (e.g., AWS Lambda or Fargate near SNS/SMS/SMTP endpoints).

---

### ✅ **9. Extensibility – How would you add WhatsApp as a new channel?**

**Answer:**

* Implement `NotificationChannel` interface (e.g., `WhatsAppNotification`)
* Plug it into orchestrator via **Strategy pattern**
* Add to user preferences and configuration
* Ensure retry + fallback are consistent with other channels

**Bonus:** Use feature flag to control rollout.

---

### ✅ **10. Testing – How would you test notification delivery end-to-end?**

**Answer:**

* **Unit tests**: test each channel independently using mocks
* **Integration tests**: simulate Kafka event → full notification flow
* **Load testing**: generate 10k events/sec and observe queue lag, latencies
* **Chaos testing**: simulate downstream failures (e.g., SMS provider down)
* **Synthetic user monitoring**: fake account gets all event types to detect regressions

---
