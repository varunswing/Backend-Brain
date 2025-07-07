Designing a **Food Delivery System** (like Swiggy/Zomato/UberEats) involves multiple subsystems — user management, restaurant onboarding, order management, real-time tracking, delivery assignment, notifications, and payment processing.

Below is a **complete end-to-end System Design** including:

---

## ✅ 1. Functional Requirements

* User registration/login
* Browse/search restaurants & dishes
* Place/cancel orders
* Real-time order & delivery tracking
* Restaurant order processing
* Delivery partner assignment
* Notifications (SMS/Email/Push)
* Payments (wallet, UPI, cards, cash)
* Ratings & reviews

---

## ✅ 2. Non-Functional Requirements

| Category            | Goal                                   |
| ------------------- | -------------------------------------- |
| **Scalability**     | Handle millions of orders daily        |
| **Availability**    | 99.99% uptime (high availability)      |
| **Consistency**     | Eventual consistency for order states  |
| **Latency**         | Low latency (<300ms) for API responses |
| **Fault Tolerance** | Retry, circuit breakers, DLQs          |
| **Security**        | JWT Auth, rate limiting, HTTPS, RBAC   |
| **Observability**   | Metrics, logging, tracing, alerts      |

---

## ✅ 3. High-Level Components

```plaintext
+-------------+           +-------------------+
|   Customer  | <-------> |  Gateway/API Layer |
+-------------+           +-------------------+
                                |
                                v
   +-----------+      +-----------------+      +--------------------+
   | Order Svc | ---> | Restaurant Svc  | ---> | Delivery Svc       |
   +-----------+      +-----------------+      +--------------------+
        |                    |                        |
        v                    v                        v
+---------------+   +-----------------+       +-----------------+
| Notification  |   | Menu & Pricing  |       | Driver Location |
|    Svc        |   |   DB/Cache      |       |   Service       |
+---------------+   +-----------------+       +-----------------+
        |
        v
+--------------------+
| Payment Gateway Svc|
+--------------------+
```

---

## ✅ 4. Database Schema (Relational for core, NoSQL for caching)

### 🔹 Users

```sql
id UUID PK,
name TEXT,
email TEXT,
phone TEXT,
address JSON,
role ENUM('USER', 'DELIVERY', 'RESTAURANT'),
created_at TIMESTAMP
```

### 🔹 Restaurants

```sql
id UUID PK,
name TEXT,
location GEOGRAPHY,
rating FLOAT,
status ENUM('OPEN', 'CLOSED')
```

### 🔹 MenuItems

```sql
id UUID PK,
restaurant_id UUID FK,
name TEXT,
price DECIMAL,
available BOOLEAN
```

### 🔹 Orders

```sql
id UUID PK,
user_id UUID,
restaurant_id UUID,
status ENUM('PLACED', 'CONFIRMED', 'PICKED_UP', 'DELIVERED', 'CANCELLED'),
total_price DECIMAL,
created_at TIMESTAMP
```

### 🔹 OrderItems

```sql
order_id UUID FK,
item_id UUID FK,
quantity INT
```

### 🔹 Delivery

```sql
id UUID PK,
order_id UUID FK,
delivery_partner_id UUID,
location_start GEOGRAPHY,
location_end GEOGRAPHY,
status ENUM('ASSIGNED', 'ON_ROUTE', 'DELIVERED')
```

---

## ✅ 5. API Endpoints

### 🔹 User

* `POST /signup`
* `GET /restaurants?lat=..&lng=..`
* `GET /restaurant/{id}/menu`
* `POST /orders`
* `GET /orders/{id}`
* `POST /orders/{id}/cancel`

### 🔹 Admin / Restaurant

* `POST /restaurant/{id}/menu`
* `PATCH /menu/{item_id}/availability`

### 🔹 Delivery Partner

* `GET /delivery/tasks`
* `POST /delivery/{id}/status`

---

## ✅ 6. Service Responsibilities

### 🔸 Order Service

* Validate request
* Calculate price
* Lock inventory
* Send to delivery service
* Initiate payment

### 🔸 Restaurant Service

* Menu and availability
* Real-time updates
* Integration with restaurant tablets/kitchens

### 🔸 Delivery Service

* Location tracking (via GPS ping from mobile app)
* Assign delivery partner
* Update delivery status

### 🔸 Notification Service

* Push/SMS/Email
* Events: placed, confirmed, out for delivery, delivered

### 🔸 Payment Service

* Payment initiation and confirmation
* Refunds
* Idempotency with payment tokens

---

## ✅ 7. Design Patterns Involved

| Pattern                   | Use                                    |
| ------------------------- | -------------------------------------- |
| **Observer/Event-driven** | Notify services on order status change |
| **Strategy**              | Payment type selection                 |
| **Circuit Breaker**       | External services like payment gateway |
| **Builder**               | Order summary                          |
| **Retry Pattern**         | Notification failures                  |
| **Load Balancer + Proxy** | Scalable REST services                 |

---

## ✅ 8. Caching and Performance Optimization

| Use Case               | Tool                      |
| ---------------------- | ------------------------- |
| Restaurant list nearby | Redis / GeoHash           |
| Menu                   | Redis                     |
| Delivery location      | Redis / WebSocket         |
| Order state polling    | WebSocket or Long Polling |

---

## ✅ 9. Fault Tolerance

| Scenario             | Handling                                   |
| -------------------- | ------------------------------------------ |
| Payment failure      | Retry or fallback, status `PAYMENT_FAILED` |
| Restaurant down      | Mark unavailable                           |
| Driver unassigned    | Reassign driver from pool                  |
| Notification failure | Retry → DLQ                                |

---

## ✅ 10. Scalability and Sharding

* **Partition Orders** by `user_id` or `restaurant_id`
* **Cache heavy reads** like menus & restaurants
* **Shard Delivery DB** by location/city
* **Kafka** for asynchronous communication between services

---

## ✅ 11. Real-Time Order Tracking Flow

1. Customer places order
2. OrderService → DeliveryService (assign driver)
3. Driver mobile app sends GPS every X seconds
4. Customer app uses WebSocket to receive live tracking
5. Notification service sends order updates

---

## ✅ 12. Cross Questions Interviewer Might Ask

| Topic               | Question                                                   |
| ------------------- | ---------------------------------------------------------- |
| Order Management    | How to prevent double order creation?                      |
| Delivery Assignment | How to find the nearest available driver efficiently?      |
| Fault Handling      | What if the restaurant cancels the order after confirming? |
| Tracking            | How do you reduce WebSocket load during surge?             |
| Data Storage        | Why SQL for orders and NoSQL for location?                 |
| Load                | How would you scale to 1M orders/day?                      |
| Security            | How to secure delivery location data?                      |
| Extensibility       | How to support grocery/medicine delivery later?            |

---

Let's dive **deep into designing the Notification Service** for a Food Delivery System, with special focus on:

* 📩 **Notification types & channels**
* 🏗️ **System architecture**
* 💡 **Scaling strategies**
* 🔁 **Retry & fault tolerance**
* 🔐 **Security**
* 📊 **Observability**

---

## ✅ 1. Core Responsibilities of Notification Service

The Notification Service handles **all communications to customers, restaurants, and delivery partners**, across multiple **channels** like:

| Event                     | Channels         |
| ------------------------- | ---------------- |
| Order placed              | SMS, Push, Email |
| Order confirmed/cancelled | SMS, Push        |
| Order out for delivery    | Push, App banner |
| Delivery delayed/failed   | SMS, Email       |
| Driver assignment         | App notification |
| Promo campaigns           | Email, Push      |

---

## ✅ 2. Architecture Diagram

```plaintext
        +------------------+
        |   Order Service  |
        +------------------+
                 |
         Kafka Topic (order-events)
                 ↓
     +-----------------------------+
     |  Notification Orchestrator |
     +-----------------------------+
                 |
   +------+------+--------+--------+
   |      |               |        |
EmailSvc SMSSvc      PushSvc   WhatsAppSvc
```

---

## ✅ 3. DB Design (for tracking & deduplication)

### `NotificationEvents`

```sql
id UUID PK,
event_id UUID,             -- e.g. order_id or internal tracking
user_id UUID,
channel ENUM('SMS', 'PUSH', 'EMAIL'),
event_type TEXT,           -- ORDER_PLACED, DELIVERED, etc.
payload JSONB,
status ENUM('PENDING', 'SENT', 'FAILED'),
created_at TIMESTAMP,
retry_count INT
```

### `UserPreferences`

```sql
user_id UUID PK,
channel TEXT,
event_type TEXT,
enabled BOOLEAN
```

---

## ✅ 4. Notification Flow (Event-driven)

1. **Order Service** publishes to Kafka (`order-events`)
2. **Notification Orchestrator** consumes, builds messages
3. Checks **UserPreferences**
4. Sends through appropriate **channel handler**
5. Saves delivery status + retries if needed

---

## ✅ 5. Channel Handler Design

```java
interface NotificationChannel {
    boolean send(NotificationMessage message);
}
```

Implementations:

* `EmailNotificationService implements NotificationChannel`
* `SMSNotificationService implements NotificationChannel`
* `PushNotificationService implements NotificationChannel`

Each handler:

* Formats template
* Sends to 3rd-party provider (e.g., Twilio, SendGrid, Firebase)
* Handles failure & response parsing

---

## ✅ 6. Scaling the Notification Service

### Horizontal Scaling

* Stateless microservice → scale with Kubernetes, ECS, etc.
* Partition Kafka topics for high-throughput parallel consumers

### Caching

* **User preferences** in Redis
* **Template caching** for faster rendering

### Asynchronous Dispatching

* Use **message queues** internally (Kafka → job queue → worker)
* Webhook APIs = async + retries

### Bulk Processing

* Campaign/promo engine uses **batch processing + throttling**

---

## ✅ 7. Fault Tolerance & Retry

| Failure                  | Handling Strategy                           |
| ------------------------ | ------------------------------------------- |
| Channel down (e.g., SMS) | Retry with exponential backoff              |
| All retries fail         | Move to DLQ and alert ops                   |
| Partial message failure  | Log per-recipient, retry individually       |
| Duplicates               | Use idempotency key: `(event_id + channel)` |

### Retry Logic

* Use Redis or DB to store attempts
* Exponential backoff: 1s → 5s → 30s → DLQ
* Add jitter to prevent retry bursts

---

## ✅ 8. Observability (Critical for Production)

| Metric                           | Tool               |
| -------------------------------- | ------------------ |
| Delivery success rate            | Prometheus/Grafana |
| Queue lag (Kafka, Redis)         | Prometheus         |
| Failed notification count        | Alerting           |
| Average send latency per channel | Grafana            |
| DLQ volume                       | Logging + Alerts   |

**Logs:**

* Request payload
* Response from 3rd-party
* Correlation IDs to trace across services

**Tracing:**

* Use OpenTelemetry/Jaeger to trace events from `OrderService → Kafka → NotificationService → Twilio/SendGrid`

---

## ✅ 9. Security

* Encrypt PII (email, phone)
* Rate limit outgoing messages
* Validate template injection (user-controlled fields)
* JWT or API key auth for internal communication

---

## ✅ 10. Extending Channels (e.g., WhatsApp)

```java
class WhatsAppNotificationService implements NotificationChannel {
    public boolean send(NotificationMessage msg) {
        // Call WhatsApp Business API
    }
}
```

Plug it into orchestrator dynamically via **Strategy Pattern**.

---

## ✅ 11. Cross Questions from Interviewer

| Topic              | Sample Question                                               |
| ------------------ | ------------------------------------------------------------- |
| Kafka              | What if Kafka is down – how will notifications be sent?       |
| Latency            | How to ensure sub-second SMS/PUSH delivery?                   |
| Channel Failure    | How do you fallback from SMS to Email?                        |
| Retry              | How to avoid duplicate sends on retry?                        |
| Preferences        | How would you store and enforce user-level opt-outs?          |
| Bulk Notifications | How would you send 1M promo emails in 1 hour?                 |
| Observability      | How would you debug notification delays?                      |
| Template Injection | How do you secure dynamic message content?                    |
| GDPR               | How do you delete a user's notification history if requested? |

---

## Summary

✅ **Stateless, horizontally scalable, event-driven**
✅ Kafka-based ingestion + channel-based dispatch
✅ Retry + backoff + idempotency for resilience
✅ Observability: metrics, alerts, tracing
✅ Extensible via clean interface per channel

---