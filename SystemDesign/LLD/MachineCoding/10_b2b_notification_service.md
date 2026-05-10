# B2B Notification Service - LLD / Machine Coding Interview

## 1. Problem Statement

Design a B2B notification service that enables enterprise clients (tenants) to send multi-channel notifications (email, SMS, push, webhook, in-app) to their end-users. The system must support templated messages, delivery tracking, retries, and multi-tenancy with per-tenant rate limits and preferences.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement |
|----|-------------|
| FR1 | Support multiple channels: EMAIL, SMS, PUSH, WEBHOOK, IN_APP |
| FR2 | Store and render notification templates with variable substitution |
| FR3 | Send notifications to recipients with configurable priority |
| FR4 | Track delivery status (queued, sent, delivered, failed) per attempt |
| FR5 | Support retries on failure with configurable max attempts |
| FR6 | Per-tenant (client) isolation and rate limiting |
| FR7 | User preferences for channel opt-in/opt-out |
| FR8 | Audit trail of all delivery attempts |

### Non-Functional Requirements

| ID | Requirement |
|----|-------------|
| NFR1 | High throughput: 1M+ notifications/hour |
| NFR2 | Low latency: <30s for transactional notifications |
| NFR3 | 99.9% availability |
| NFR4 | Multi-tenant with data isolation |
| NFR5 | Extensible for new channels without core changes |

---

## 3. Database Design with Explanations

```sql
-- WHY: Tenants represent B2B clients (enterprises). Each tenant has isolated data,
-- rate limits, and API credentials. Multi-tenancy is core to B2B SaaS.
CREATE TABLE tenants (
    tenant_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    api_key         VARCHAR(100) UNIQUE NOT NULL,
    rate_limit_rpm   INTEGER DEFAULT 1000,
    status          VARCHAR(20) DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
-- WHY index: Lookup by api_key on every API request for auth
CREATE INDEX idx_tenants_api_key ON tenants(api_key);

-- WHY: Channels define supported delivery mechanisms. Decoupled from tenants
-- so we can add new channels globally without schema changes per tenant.
CREATE TABLE channels (
    channel_id      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    channel_type    VARCHAR(20) UNIQUE NOT NULL,  -- EMAIL, SMS, PUSH, WEBHOOK, IN_APP
    provider_name   VARCHAR(50),
    is_active       BOOLEAN DEFAULT true,
    created_at      TIMESTAMP DEFAULT NOW()
);
-- WHY: No extra index needed; channel_type is UNIQUE and used for lookups

-- WHY: Templates store reusable message patterns per tenant. Variables like
-- {{user_name}} enable personalization. FK to tenant for isolation.
CREATE TABLE notification_templates (
    template_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    channel_id      UUID NOT NULL REFERENCES channels(channel_id),
    name            VARCHAR(300) NOT NULL,
    subject_template TEXT,           -- For EMAIL
    body_template   TEXT NOT NULL,
    variables       JSONB DEFAULT '[]',  -- ["user_name", "order_id"]
    is_active       BOOLEAN DEFAULT true,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(tenant_id, name)
);
-- WHY FK tenant_id: Multi-tenant isolation; CASCADE deletes templates when tenant removed
-- WHY FK channel_id: Template is channel-specific (email subject vs SMS body)
-- WHY index: List templates by tenant; filter by tenant+channel for rendering
CREATE INDEX idx_templates_tenant_channel ON notification_templates(tenant_id, channel_id);

-- WHY: Core entity - each notification is one send request. Tracks lifecycle
-- from creation through delivery. FK to tenant, template for audit trail.
CREATE TABLE notifications (
    notification_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(tenant_id),
    template_id     UUID REFERENCES notification_templates(template_id),
    channel_type    VARCHAR(20) NOT NULL,
    recipient        VARCHAR(255) NOT NULL,   -- email, phone, device_id, webhook URL
    priority        VARCHAR(20) DEFAULT 'NORMAL',
    status          VARCHAR(30) DEFAULT 'QUEUED',
    payload         JSONB,                    -- Rendered content + metadata
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMP DEFAULT NOW(),
    scheduled_at    TIMESTAMP,
    expires_at      TIMESTAMP
);
-- WHY FK tenant_id: Enforce tenant isolation; quota/rate limit checks
-- WHY FK template_id: Nullable for ad-hoc notifications without template
-- WHY index: List notifications by tenant+status for dashboards, retries
CREATE INDEX idx_notifications_tenant_status ON notifications(tenant_id, status);
-- WHY index: Process scheduled/queued notifications in order
CREATE INDEX idx_notifications_scheduled ON notifications(scheduled_at) WHERE status = 'QUEUED';

-- WHY: Delivery attempts track each try (initial + retries). One notification
-- can have multiple attempts. Enables retry logic and delivery analytics.
CREATE TABLE notification_deliveries (
    delivery_id     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id UUID NOT NULL REFERENCES notifications(notification_id) ON DELETE CASCADE,
    attempt_number  INTEGER NOT NULL DEFAULT 1,
    status          VARCHAR(30) NOT NULL,       -- SENT, DELIVERED, FAILED
    provider_id     VARCHAR(100),             -- External provider message ID
    error_message   TEXT,
    sent_at         TIMESTAMP,
    delivered_at    TIMESTAMP,
    created_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(notification_id, attempt_number)
);
-- WHY FK notification_id: Each delivery belongs to one notification; CASCADE for cleanup
-- WHY index: Fetch delivery history for a notification; find failed for retry
CREATE INDEX idx_deliveries_notification ON notification_deliveries(notification_id);
CREATE INDEX idx_deliveries_status ON notification_deliveries(status) WHERE status = 'FAILED';

-- WHY: User preferences allow opt-in/opt-out per channel. Per recipient
-- (identified by user_id or email) per tenant. GDPR/frequency capping.
CREATE TABLE user_preferences (
    preference_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(tenant_id) ON DELETE CASCADE,
    user_identifier VARCHAR(255) NOT NULL,    -- email, user_id, or phone
    channel_type    VARCHAR(20) NOT NULL,
    opted_in        BOOLEAN DEFAULT true,
    updated_at      TIMESTAMP DEFAULT NOW(),
    UNIQUE(tenant_id, user_identifier, channel_type)
);
-- WHY FK tenant_id: Preferences are tenant-scoped (same user, different tenants)
-- WHY index: Check opt-in before sending; bulk preference lookups
CREATE INDEX idx_preferences_tenant_user ON user_preferences(tenant_id, user_identifier);
```

---

## 4. Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `DeliveryChannelStrategy` + `EmailChannel`, `SMSChannel`, `PushChannel`, `WebhookChannel` | Each channel has different delivery logic; Strategy lets us swap implementations without changing the orchestrator. Open/Closed: add new channel = new strategy class. |
| **Template Method** | `NotificationProcessor` (validate → render → deliver → track) | Defines the skeleton of notification processing. Subclasses override steps (e.g., different validation for bulk vs single). Reduces duplication. |
| **Observer** | `DeliveryObserver` with `MetricsObserver`, `RetryHandlerObserver`, `AuditObserver` | Multiple concerns (metrics, retry, audit) react to delivery events. Loose coupling; add new observers without modifying delivery logic. |
| **Factory** | `ChannelFactory` | Creates the right `DeliveryChannelStrategy` by `ChannelType`. Encapsulates channel instantiation; single place to add new channels. |
| **Builder** | `Notification.Builder` | Notification has many optional fields (scheduled_at, priority, metadata). Builder avoids telescoping constructors and improves readability. |
| **Dependency Injection** | `NotificationService` receives `ChannelFactory`, `TemplateEngine`, `DeliveryObserver` | Testability (mock dependencies), flexibility (swap implementations), Single Responsibility (service orchestrates, doesn't implement). |

---

## 5. SOLID Principles

| Principle | Application |
|-----------|-------------|
| **S - Single Responsibility** | `EmailChannel` only sends email; `TemplateEngine` only renders; `NotificationService` only orchestrates. Each class has one reason to change. |
| **O - Open/Closed** | New channel = new `DeliveryChannelStrategy` impl + register in `ChannelFactory`. No changes to `NotificationService` or other channels. |
| **L - Liskov Substitution** | Any `DeliveryChannelStrategy` can replace another. `ChannelFactory` returns strategy; caller doesn't care which concrete type. |
| **I - Interface Segregation** | `DeliveryChannelStrategy` has only `deliver(Notification)`. No fat interface forcing implementors to implement unused methods. |
| **D - Dependency Inversion** | `NotificationService` depends on `DeliveryChannelStrategy` (interface), not `EmailChannel` (concrete). High-level module doesn't depend on low-level. |

---

## 6. Code Implementation in Java

### Enums

```java
/** Notification category for routing and analytics */
public enum NotificationType {
    TRANSACTIONAL,   // Password reset, OTP
    MARKETING,       // Promotions, newsletters
    ALERT            // System alerts, critical
}

/** Supported delivery channels. IN_APP for in-app banners/toasts. */
public enum ChannelType {
    EMAIL,
    SMS,
    PUSH,
    WEBHOOK,
    IN_APP
}

/** Lifecycle of a notification / delivery attempt */
public enum DeliveryStatus {
    QUEUED,
    SENT,       // Accepted by provider
    DELIVERED,  // Confirmed (e.g., open tracking)
    FAILED
}

/** Priority affects queue ordering and retry backoff */
public enum Priority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}
```

### Models (OOP)

```java
/** Immutable template with variable placeholders. OOP: encapsulation of template data. */
public class NotificationTemplate {
    private final String templateId;
    private final String tenantId;
    private final ChannelType channelType;
    private final String name;
    private final String subjectTemplate;
    private final String bodyTemplate;
    private final List<String> variables;

    private NotificationTemplate(Builder b) {
        this.templateId = b.templateId;
        this.tenantId = b.tenantId;
        this.channelType = b.channelType;
        this.name = b.name;
        this.subjectTemplate = b.subjectTemplate;
        this.bodyTemplate = b.bodyTemplate;
        this.variables = Collections.unmodifiableList(b.variables);
    }

    public String getTemplateId() { return templateId; }
    public String getTenantId() { return tenantId; }
    public ChannelType getChannelType() { return channelType; }
    public String getSubjectTemplate() { return subjectTemplate; }
    public String getBodyTemplate() { return bodyTemplate; }
    public List<String> getVariables() { return variables; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private String templateId, tenantId, name, subjectTemplate, bodyTemplate;
        private ChannelType channelType;
        private List<String> variables = new ArrayList<>();
        public Builder templateId(String id) { this.templateId = id; return this; }
        public Builder tenantId(String id) { this.tenantId = id; return this; }
        public Builder channelType(ChannelType ct) { this.channelType = ct; return this; }
        public Builder name(String n) { this.name = n; return this; }
        public Builder subjectTemplate(String s) { this.subjectTemplate = s; return this; }
        public Builder bodyTemplate(String b) { this.bodyTemplate = b; return this; }
        public Builder variables(List<String> v) { this.variables = v; return this; }
        public NotificationTemplate build() { return new NotificationTemplate(this); }
    }
}

/** Single delivery attempt. OOP: value object for attempt metadata. */
public class DeliveryAttempt {
    private final String deliveryId;
    private final String notificationId;
    private final int attemptNumber;
    private final DeliveryStatus status;
    private final String providerId;
    private final String errorMessage;
    private final Instant sentAt;
    private final Instant deliveredAt;

    public DeliveryAttempt(String deliveryId, String notificationId, int attemptNumber,
                           DeliveryStatus status, String providerId, String errorMessage,
                           Instant sentAt, Instant deliveredAt) {
        this.deliveryId = deliveryId;
        this.notificationId = notificationId;
        this.attemptNumber = attemptNumber;
        this.status = status;
        this.providerId = providerId;
        this.errorMessage = errorMessage;
        this.sentAt = sentAt;
        this.deliveredAt = deliveredAt;
    }
    // Getters...
}

/** Core domain entity. Builder pattern for fluent construction. */
public class Notification {
    private final String notificationId;
    private final String tenantId;
    private final String templateId;
    private final ChannelType channelType;
    private final String recipient;
    private final Priority priority;
    private final DeliveryStatus status;
    private final Map<String, Object> payload;
    private final Map<String, Object> metadata;
    private final Instant createdAt;
    private final Instant scheduledAt;
    private final Instant expiresAt;

    private Notification(Builder b) {
        this.notificationId = b.notificationId;
        this.tenantId = b.tenantId;
        this.templateId = b.templateId;
        this.channelType = b.channelType;
        this.recipient = b.recipient;
        this.priority = b.priority;
        this.status = b.status;
        this.payload = b.payload != null ? Map.copyOf(b.payload) : Map.of();
        this.metadata = b.metadata != null ? Map.copyOf(b.metadata) : Map.of();
        this.createdAt = b.createdAt;
        this.scheduledAt = b.scheduledAt;
        this.expiresAt = b.expiresAt;
    }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private String notificationId, tenantId, templateId, recipient;
        private ChannelType channelType;
        private Priority priority = Priority.NORMAL;
        private DeliveryStatus status = DeliveryStatus.QUEUED;
        private Map<String, Object> payload, metadata;
        private Instant createdAt = Instant.now(), scheduledAt, expiresAt;

        public Builder notificationId(String id) { this.notificationId = id; return this; }
        public Builder tenantId(String id) { this.tenantId = id; return this; }
        public Builder templateId(String id) { this.templateId = id; return this; }
        public Builder channelType(ChannelType ct) { this.channelType = ct; return this; }
        public Builder recipient(String r) { this.recipient = r; return this; }
        public Builder priority(Priority p) { this.priority = p; return this; }
        public Builder status(DeliveryStatus s) { this.status = s; return this; }
        public Builder payload(Map<String, Object> p) { this.payload = p; return this; }
        public Builder metadata(Map<String, Object> m) { this.metadata = m; return this; }
        public Builder scheduledAt(Instant t) { this.scheduledAt = t; return this; }
        public Builder expiresAt(Instant t) { this.expiresAt = t; return this; }
        public Notification build() { return new Notification(this); }
    }
    // Getters...
}
```

### Strategy: DeliveryChannelStrategy

```java
/** Strategy: Each channel implements its own delivery logic. Caller uses interface. */
public interface DeliveryChannelStrategy {
    ChannelType getChannelType();
    DeliveryAttempt deliver(Notification notification);
}

public class EmailChannel implements DeliveryChannelStrategy {
    private final EmailProvider provider; // e.g., SendGrid client

    public EmailChannel(EmailProvider provider) { this.provider = provider; }
    @Override public ChannelType getChannelType() { return ChannelType.EMAIL; }

    @Override
    public DeliveryAttempt deliver(Notification notification) {
        String to = notification.getRecipient();
        String subject = (String) notification.getPayload().getOrDefault("subject", "");
        String body = (String) notification.getPayload().getOrDefault("body", "");
        try {
            String providerId = provider.send(to, subject, body);
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, DeliveryStatus.SENT, providerId, null, Instant.now(), null);
        } catch (Exception e) {
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, DeliveryStatus.FAILED, null, e.getMessage(), null, null);
        }
    }
}

public class SMSChannel implements DeliveryChannelStrategy {
    private final SMSProvider provider;
    public SMSChannel(SMSProvider provider) { this.provider = provider; }
    @Override public ChannelType getChannelType() { return ChannelType.SMS; }
    @Override
    public DeliveryAttempt deliver(Notification notification) {
        String body = (String) notification.getPayload().getOrDefault("body", "");
        try {
            String providerId = provider.send(notification.getRecipient(), body);
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, DeliveryStatus.SENT, providerId, null, Instant.now(), null);
        } catch (Exception e) {
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, DeliveryStatus.FAILED, null, e.getMessage(), null, null);
        }
    }
}

public class PushChannel implements DeliveryChannelStrategy {
    private final PushProvider provider;
    public PushChannel(PushProvider provider) { this.provider = provider; }
    @Override public ChannelType getChannelType() { return ChannelType.PUSH; }
    @Override
    public DeliveryAttempt deliver(Notification notification) {
        // device_id in recipient, title/body in payload
        try {
            String providerId = provider.send(notification.getRecipient(), notification.getPayload());
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, DeliveryStatus.SENT, providerId, null, Instant.now(), null);
        } catch (Exception e) {
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, DeliveryStatus.FAILED, null, e.getMessage(), null, null);
        }
    }
}

public class WebhookChannel implements DeliveryChannelStrategy {
    private final HttpClient httpClient;
    public WebhookChannel(HttpClient httpClient) { this.httpClient = httpClient; }
    @Override public ChannelType getChannelType() { return ChannelType.WEBHOOK; }
    @Override
    public DeliveryAttempt deliver(Notification notification) {
        String url = notification.getRecipient(); // URL is recipient for webhook
        try {
            HttpResponse resp = httpClient.post(url, notification.getPayload());
            String providerId = resp.getHeader("X-Request-Id");
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, resp.isSuccess() ? DeliveryStatus.SENT : DeliveryStatus.FAILED,
                    providerId, resp.isSuccess() ? null : resp.getBody(), Instant.now(), null);
        } catch (Exception e) {
            return new DeliveryAttempt(UUID.randomUUID().toString(), notification.getNotificationId(),
                    1, DeliveryStatus.FAILED, null, e.getMessage(), null, null);
        }
    }
}
```

### Template Method: NotificationProcessor

```java
/** Template Method: Skeleton of processing. Subclasses override steps. */
public abstract class NotificationProcessor {
    public final Notification process(Notification notification) {
        validate(notification);
        Notification rendered = render(notification);
        DeliveryAttempt attempt = deliver(rendered);
        track(rendered, attempt);
        return rendered;
    }
    protected abstract void validate(Notification n);
    protected abstract Notification render(Notification n);
    protected abstract DeliveryAttempt deliver(Notification n);
    protected abstract void track(Notification n, DeliveryAttempt a);
}

/** Concrete processor with DI for template engine and channel. */
public class DefaultNotificationProcessor extends NotificationProcessor {
    private final TemplateEngine templateEngine;
    private final ChannelFactory channelFactory;
    private final List<DeliveryObserver> observers;

    public DefaultNotificationProcessor(TemplateEngine te, ChannelFactory cf, List<DeliveryObserver> obs) {
        this.templateEngine = te;
        this.channelFactory = cf;
        this.observers = obs;
    }

    @Override
    protected void validate(Notification n) {
        if (n.getRecipient() == null || n.getRecipient().isBlank())
            throw new IllegalArgumentException("Recipient required");
        if (n.getChannelType() == null) throw new IllegalArgumentException("Channel required");
    }

    @Override
    protected Notification render(Notification n) {
        if (n.getTemplateId() == null) return n;
        NotificationTemplate template = templateEngine.load(n.getTemplateId());
        Map<String, Object> rendered = templateEngine.render(template, n.getMetadata());
        return Notification.builder()
                .notificationId(n.getNotificationId())
                .tenantId(n.getTenantId())
                .templateId(n.getTemplateId())
                .channelType(n.getChannelType())
                .recipient(n.getRecipient())
                .priority(n.getPriority())
                .payload(rendered)
                .metadata(n.getMetadata())
                .build();
    }

    @Override
    protected DeliveryAttempt deliver(Notification n) {
        DeliveryChannelStrategy channel = channelFactory.getChannel(n.getChannelType());
        return channel.deliver(n);
    }

    @Override
    protected void track(Notification n, DeliveryAttempt a) {
        observers.forEach(o -> o.onDelivery(n, a));
    }
}
```

### Observer: DeliveryObserver

```java
/** Observer: Multiple listeners react to delivery events. Loose coupling. */
public interface DeliveryObserver {
    void onDelivery(Notification notification, DeliveryAttempt attempt);
}

public class MetricsObserver implements DeliveryObserver {
    private final MetricsRegistry metrics;
    public MetricsObserver(MetricsRegistry metrics) { this.metrics = metrics; }
    @Override
    public void onDelivery(Notification n, DeliveryAttempt a) {
        metrics.increment("notifications.delivered", "channel", n.getChannelType().name(), "status", a.getStatus().name());
    }
}

public class RetryHandlerObserver implements DeliveryObserver {
    private final NotificationRepository repo;
    private final int maxRetries;
    public RetryHandlerObserver(NotificationRepository repo, int maxRetries) {
        this.repo = repo; this.maxRetries = maxRetries;
    }
    @Override
    public void onDelivery(Notification n, DeliveryAttempt a) {
        if (a.getStatus() == DeliveryStatus.FAILED && a.getAttemptNumber() < maxRetries)
            repo.enqueueForRetry(n.getNotificationId(), a.getAttemptNumber() + 1);
    }
}

public class AuditObserver implements DeliveryObserver {
    private final AuditLogRepository auditLog;
    public AuditObserver(AuditLogRepository auditLog) { this.auditLog = auditLog; }
    @Override
    public void onDelivery(Notification n, DeliveryAttempt a) {
        auditLog.append(n.getNotificationId(), n.getTenantId(), a.getStatus(), a.getErrorMessage());
    }
}
```

### Factory: ChannelFactory

```java
/** Factory: Creates the right channel by type. Single place to add new channels. */
public class ChannelFactory {
    private final Map<ChannelType, DeliveryChannelStrategy> channels = new HashMap<>();

    public ChannelFactory() {
        // In production, inject providers via DI
        channels.put(ChannelType.EMAIL, new EmailChannel(new SendGridProvider()));
        channels.put(ChannelType.SMS, new SMSChannel(new TwilioProvider()));
        channels.put(ChannelType.PUSH, new PushChannel(new FCMProvider()));
        channels.put(ChannelType.WEBHOOK, new WebhookChannel(HttpClient.create()));
        channels.put(ChannelType.IN_APP, new InAppChannel(new RedisInAppStorage()));
    }

    public void register(ChannelType type, DeliveryChannelStrategy strategy) {
        channels.put(type, strategy);
    }

    public DeliveryChannelStrategy getChannel(ChannelType type) {
        DeliveryChannelStrategy s = channels.get(type);
        if (s == null) throw new IllegalArgumentException("Unsupported channel: " + type);
        return s;
    }
}
```

### NotificationService (Orchestrator with DI)

```java
/** Orchestrator: Coordinates components. Depends on abstractions (DI). */
public class NotificationService {
    private final NotificationProcessor processor;
    private final NotificationRepository repository;
    private final UserPreferenceRepository preferenceRepo;
    private final RateLimiter rateLimiter;

    public NotificationService(NotificationProcessor processor,
                              NotificationRepository repository,
                              UserPreferenceRepository preferenceRepo,
                              RateLimiter rateLimiter) {
        this.processor = processor;
        this.repository = repository;
        this.preferenceRepo = preferenceRepo;
        this.rateLimiter = rateLimiter;
    }

    public Notification send(NotificationRequest request) {
        rateLimiter.checkLimit(request.getTenantId());
        if (!preferenceRepo.isOptedIn(request.getTenantId(), request.getRecipient(), request.getChannelType()))
            throw new UserOptedOutException("User has opted out of " + request.getChannelType());

        Notification notification = Notification.builder()
                .notificationId(UUID.randomUUID().toString())
                .tenantId(request.getTenantId())
                .templateId(request.getTemplateId())
                .channelType(request.getChannelType())
                .recipient(request.getRecipient())
                .priority(request.getPriority())
                .metadata(request.getVariables())
                .build();

        repository.save(notification);
        processor.process(notification);
        return notification;
    }
}
```

---

## 7. Edge Cases & Tests

| # | Edge Case | Test Scenario | Expected Behavior |
|---|-----------|---------------|-------------------|
| 1 | **User opted out** | Send to user who opted out of SMS | Throw `UserOptedOutException`; no delivery |
| 2 | **Rate limit exceeded** | Tenant sends 1001 requests in 1 min (limit 1000) | 1001st request rejected with 429; no notification created |
| 3 | **Template variable missing** | Template has `{{order_id}}` but request has no `order_id` | Render with empty string or throw; document expected behavior |
| 4 | **Provider timeout** | Email provider times out after 30s | DeliveryAttempt status=FAILED; RetryHandlerObserver enqueues retry |
| 5 | **Expired notification** | Process notification where `expires_at` < now | Skip delivery; mark as FAILED with "expired" |
| 6 | **Duplicate send (idempotency)** | Same idempotency key sent twice | Return same notification_id; no duplicate delivery |
| 7 | **Invalid recipient format** | Email channel with invalid email format | Validate in `validate()`; throw before render |
| 8 | **Webhook returns 5xx** | Webhook URL returns 503 | DeliveryAttempt FAILED; retry with backoff |

---

## 8. Summary

| Aspect | Summary |
|--------|---------|
| **Problem** | B2B multi-channel notification service with templates, tracking, multi-tenancy |
| **DB** | tenants, channels, notification_templates, notifications, notification_deliveries, user_preferences |
| **Patterns** | Strategy (channels), Template Method (processor), Observer (metrics/retry/audit), Factory (channels), Builder (Notification) |
| **SOLID** | SRP per class; OCP for new channels; LSP for strategies; ISP for lean interfaces; DIP via DI |
| **Key Classes** | `NotificationService` (orchestrator), `DeliveryChannelStrategy` (Strategy), `NotificationProcessor` (Template Method), `DeliveryObserver` (Observer), `ChannelFactory` (Factory) |
| **Edge Cases** | Opt-out, rate limit, missing vars, timeouts, expiry, idempotency, validation, retries |
