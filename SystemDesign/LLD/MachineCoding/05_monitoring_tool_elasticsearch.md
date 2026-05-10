# Monitoring Tool with Elasticsearch - LLD / Machine Coding

---

## 1. Problem Statement

Design a monitoring platform (like Datadog) that ingests high-volume logs and metrics, provides real-time search, configurable alerting with multiple evaluation strategies, and customizable dashboards. The system must support multi-tenancy, tiered data storage (hot/warm/cold), and multiple notification channels for alerts.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement | Description |
|----|-------------|-------------|
| FR1 | Log Ingestion | Ingest application logs with structured fields (timestamp, level, message, service, host, trace_id) |
| FR2 | Metric Ingestion | Ingest numeric metrics with tags (name, value, timestamp, service) |
| FR3 | Full-Text Search | Search logs by message, level, service, time range, and structured filters |
| FR4 | Metric Search | Query metrics by name, tags, and time range |
| FR5 | Alert Rules | Define rules with threshold, anomaly detection, or rate-of-change evaluation |
| FR6 | Alert Notifications | Send alerts via Email, Slack, PagerDuty |
| FR7 | Dashboards | Create and manage dashboards with saved queries and visualizations |
| FR8 | Multi-Tenancy | Isolate data by organization; users belong to orgs |
| FR9 | Data Tiers | Store data in HOT (7d), WARM (90d), COLD (2y) with ILM |

### Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR1 | Ingestion Throughput | 1M+ events/second peak |
| NFR2 | Search Latency | <1s for complex queries, <100ms for simple |
| NFR3 | Availability | 99.95% uptime |
| NFR4 | Scalability | Horizontal scaling for ingestion and search |
| NFR5 | Extensibility | Easy to add new alert types and notification channels |

---

## 3. Database Design with Explanations

### 3.1 PostgreSQL (Metadata Store)

**WHY PostgreSQL?** Relational metadata (orgs, users, alert rules, dashboards) requires ACID, joins, and strong consistency. PostgreSQL excels at structured data, JSONB for flexible rule/query storage, and is widely supported.

```sql
-- WHY: Organizations are the top-level tenant boundary. All data is scoped by org_id.
-- org_id is used for multi-tenant isolation in Elasticsearch indices and queries.
CREATE TABLE organizations (
    org_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_name VARCHAR(200) NOT NULL,
    data_retention_days INTEGER DEFAULT 90,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- WHY: Users belong to orgs for RBAC. email is unique for login; role controls permissions.
-- org_id FK enforces tenant isolation at the user level.
CREATE TABLE users (
    user_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(org_id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'viewer' CHECK (role IN ('admin', 'editor', 'viewer')),
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(org_id, email)
);

-- WHY: Index for fast lookup of users by org (dashboard/alert ownership, permission checks).
CREATE INDEX idx_users_org_id ON users(org_id);

-- WHY: Alert rules are configuration, not time-series. PostgreSQL provides durable storage,
-- JSONB for flexible query/condition definitions without schema changes per rule type.
CREATE TABLE alert_rules (
    alert_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(org_id) ON DELETE CASCADE,
    rule_name VARCHAR(300) NOT NULL,
    rule_type VARCHAR(50) NOT NULL CHECK (rule_type IN ('threshold', 'anomaly', 'rate_of_change')),
    query JSONB NOT NULL,           -- ES query DSL or metric selector
    condition JSONB NOT NULL,       -- threshold value, sensitivity, etc.
    is_enabled BOOLEAN DEFAULT true,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- WHY: Fast lookup of enabled rules per org for alert evaluation scheduler.
CREATE INDEX idx_alert_rules_org_enabled ON alert_rules(org_id, is_enabled) WHERE is_enabled = true;

-- WHY: Dashboards are user-created configs. JSONB stores panels, layout, saved queries.
-- org_id scopes dashboards for multi-tenancy.
CREATE TABLE dashboards (
    dashboard_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(org_id) ON DELETE CASCADE,
    created_by UUID REFERENCES users(user_id) ON DELETE SET NULL,
    title VARCHAR(300) NOT NULL,
    panels JSONB NOT NULL DEFAULT '[]',  -- [{type, query, visualization, position}]
    created_at TIMESTAMPTZ DEFAULT NOW(),
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- WHY: List dashboards by org for UI; filter by creator for "my dashboards".
CREATE INDEX idx_dashboards_org_id ON dashboards(org_id);

-- WHY: Notification channels are reusable across alert rules. Storing config as JSONB
-- allows different channel types (email, slack, pagerduty) with type-specific fields.
CREATE TABLE notification_channels (
    channel_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id UUID NOT NULL REFERENCES organizations(org_id) ON DELETE CASCADE,
    channel_type VARCHAR(50) NOT NULL CHECK (channel_type IN ('email', 'slack', 'pagerduty')),
    name VARCHAR(200) NOT NULL,
    config JSONB NOT NULL,  -- {email_to, slack_webhook, pagerduty_key, etc.}
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- WHY: Many-to-many: one alert rule can notify multiple channels; one channel can serve many rules.
CREATE TABLE alert_rule_channels (
    alert_id UUID NOT NULL REFERENCES alert_rules(alert_id) ON DELETE CASCADE,
    channel_id UUID NOT NULL REFERENCES notification_channels(channel_id) ON DELETE CASCADE,
    PRIMARY KEY (alert_id, channel_id)
);
```

### 3.2 Elasticsearch (Logs & Metrics)

**WHY Elasticsearch?** Logs and metrics are high-volume, append-only, and require full-text search, aggregations, and time-range queries. ES is purpose-built for this. PostgreSQL would not scale for billions of events.

#### Index Templates

**WHY index templates?** New time-based indices (e.g., `logs-2025-03-02`) are created daily. Templates ensure consistent mappings and settings without manual setup per index.

```json
{
  "index_patterns": ["logs-*"],
  "priority": 100,
  "template": {
    "settings": {
      "number_of_shards": 5,
      "number_of_replicas": 1,
      "index.lifecycle.name": "logs-policy"
    },
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" },
        "org_id": { "type": "keyword" },
        "service": { "type": "keyword" },
        "level": { "type": "keyword" },
        "message": { "type": "text", "fields": { "keyword": { "type": "keyword" } } },
        "host": { "type": "keyword" },
        "trace_id": { "type": "keyword" },
        "duration_ms": { "type": "long" },
        "error": {
          "properties": {
            "type": { "type": "keyword" },
            "message": { "type": "text" }
          }
        }
      }
    }
  }
}
```

**WHY these field types?**
- `keyword` for `org_id`, `service`, `level`, `host`, `trace_id`: exact match, aggregations, filters.
- `text` for `message`: full-text search; `keyword` subfield for exact/sort.
- `date` for `@timestamp`: time-range queries and sorting.

```json
{
  "index_patterns": ["metrics-*"],
  "priority": 100,
  "template": {
    "settings": {
      "number_of_shards": 5,
      "number_of_replicas": 1,
      "index.lifecycle.name": "metrics-policy"
    },
    "mappings": {
      "properties": {
        "@timestamp": { "type": "date" },
        "org_id": { "type": "keyword" },
        "metric_name": { "type": "keyword" },
        "value": { "type": "double" },
        "tags": { "type": "flattened" },
        "service": { "type": "keyword" }
      }
    }
  }
}
```

**WHY `flattened` for tags?** Tags are dynamic key-value pairs. `flattened` avoids mapping explosion while allowing search/aggregation on tag keys and values.

#### Index Lifecycle Management (ILM)

**WHY ILM?** Data has different access patterns over time. Hot data (recent) needs fast SSD and high throughput; warm/cold need cost optimization. ILM automates rollover, shrink, freeze, and delete.

```json
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_size": "50gb",
            "max_age": "1d"
          }
        }
      },
      "warm": {
        "min_age": "7d",
        "actions": {
          "shrink": { "number_of_shards": 1 },
          "forcemerge": { "max_num_segments": 1 }
        }
      },
      "cold": {
        "min_age": "90d",
        "actions": { "freeze": {} }
      },
      "delete": {
        "min_age": "2y",
        "actions": { "delete": {} }
      }
    }
  }
}
```

| Phase | WHY |
|-------|-----|
| Hot | Recent data; SSD, many shards for write throughput. Rollover prevents huge indices. |
| Warm | Read-heavy, older data. Shrink + forcemerge reduces shard count and segments for cheaper storage. |
| Cold | Rarely accessed. Freeze reduces memory; can move to cheaper storage tier. |
| Delete | Compliance/retention; automatic cleanup. |

---

## 4. Design Patterns Used

| Pattern | Where Used | Purpose |
|---------|------------|---------|
| **Strategy** | `AlertEvaluationStrategy` (Threshold, Anomaly, RateOfChange) | Swap alert evaluation algorithms without changing `AlertService` |
| **Observer** | `AlertObserver` (Email, Slack, PagerDuty) | Decouple alert firing from notification delivery; add channels without modifying core logic |
| **Factory** | `AlertRuleFactory` | Create correct `AlertRule` + strategy based on `rule_type` |
| **Chain of Responsibility** | `LogPipeline` (parse → enrich → transform → index) | Process logs through stages; each stage can pass or modify |
| **Dependency Injection** | Services receive repositories/clients via constructor | Testability, loose coupling, swap implementations |
| **Repository** | `AlertRuleRepository`, `DashboardRepository` | Abstract data access; services depend on abstractions |

---

## 5. SOLID Principles Applied

| Principle | Application |
|-----------|-------------|
| **S**ingle Responsibility | `IngestionService` ingests; `SearchService` searches; `AlertService` evaluates and notifies. Each class has one reason to change. |
| **O**pen/Closed | New alert types (e.g., `CompositeAlert`) added by new strategy impl; new channels by new observer. No changes to existing code. |
| **L**iskov Substitution | Any `AlertEvaluationStrategy` can replace another; any `AlertObserver` can replace another. |
| **I**nterface Segregation | `AlertObserver` has only `onAlertFired()`. `LogPipelineStage` has only `process()`. No fat interfaces. |
| **D**ependency Inversion | Services depend on `AlertRuleRepository`, `ElasticsearchClient`, not concrete DB/ES implementations. |

---

## 6. Code Implementation in Java

### 6.1 Enums

```java
/** Log severity levels. Used for filtering and alert conditions. */
public enum LogLevel {
    DEBUG, INFO, WARN, ERROR, FATAL
}

/** Severity of an alert for prioritization and routing. */
public enum AlertSeverity {
    INFO, WARNING, CRITICAL
}

/** Lifecycle state of an alert. */
public enum AlertStatus {
    FIRING,    // Condition met, notifications sent
    RESOLVED,  // Condition no longer met
    PENDING    // Evaluated but not yet firing (e.g., for duration thresholds)
}

/** Data tier for storage and ILM. HOT=fast SSD, WARM=HDD, COLD=archive. */
public enum DataTier {
    HOT,   // 0-7 days, high throughput
    WARM,  // 7-90 days, cost-optimized
    COLD   // 90d-2y, rarely accessed
}
```

### 6.2 Models (with OOP comments)

```java
/** Immutable value object representing a log event. Encapsulates all log fields. */
public record LogEvent(
    String orgId,
    Instant timestamp,
    LogLevel level,
    String message,
    String service,
    String host,
    String traceId,
    Long durationMs,
    Map<String, Object> metadata
) {
    public Map<String, Object> toElasticsearchDocument() {
        return Map.of(
            "@timestamp", timestamp.toString(),
            "org_id", orgId,
            "level", level.name(),
            "message", message,
            "service", service,
            "host", host,
            "trace_id", traceId != null ? traceId : "",
            "duration_ms", durationMs != null ? durationMs : 0,
            "metadata", metadata != null ? metadata : Map.of()
        );
    }
}

/** Immutable value object for a metric data point. */
public record Metric(
    String orgId,
    Instant timestamp,
    String metricName,
    double value,
    Map<String, String> tags,
    String service
) {
    public Map<String, Object> toElasticsearchDocument() {
        return Map.of(
            "@timestamp", timestamp.toString(),
            "org_id", orgId,
            "metric_name", metricName,
            "value", value,
            "tags", tags != null ? tags : Map.of(),
            "service", service != null ? service : ""
        );
    }
}

/** Entity representing an alert rule. Uses composition for evaluation strategy (Strategy pattern). */
public class AlertRule {
    private final UUID alertId;
    private final UUID orgId;
    private final String ruleName;
    private final String ruleType;
    private final Map<String, Object> query;
    private final Map<String, Object> condition;
    private final boolean enabled;
    private final AlertEvaluationStrategy evaluationStrategy;

    public AlertRule(UUID alertId, UUID orgId, String ruleName, String ruleType,
                     Map<String, Object> query, Map<String, Object> condition,
                     boolean enabled, AlertEvaluationStrategy evaluationStrategy) {
        this.alertId = alertId;
        this.orgId = orgId;
        this.ruleName = ruleName;
        this.ruleType = ruleType;
        this.query = query;
        this.condition = condition;
        this.enabled = enabled;
        this.evaluationStrategy = evaluationStrategy;
    }

    public boolean evaluate(SearchService searchService) {
        return evaluationStrategy.evaluate(this, searchService);
    }

    public UUID getAlertId() { return alertId; }
    public UUID getOrgId() { return orgId; }
    public String getRuleName() { return ruleName; }
    public Map<String, Object> getQuery() { return query; }
    public Map<String, Object> getCondition() { return condition; }
    public boolean isEnabled() { return enabled; }
}

/** Entity for a dashboard. Panels stored as JSON; UI reconstructs layout. */
public class Dashboard {
    private final UUID dashboardId;
    private final UUID orgId;
    private final UUID createdBy;
    private final String title;
    private final List<DashboardPanel> panels;

    public Dashboard(UUID dashboardId, UUID orgId, UUID createdBy, String title, List<DashboardPanel> panels) {
        this.dashboardId = dashboardId;
        this.orgId = orgId;
        this.createdBy = createdBy;
        this.title = title;
        this.panels = panels != null ? panels : List.of();
    }

    public UUID getDashboardId() { return dashboardId; }
    public UUID getOrgId() { return orgId; }
    public String getTitle() { return title; }
    public List<DashboardPanel> getPanels() { return panels; }
}

public record DashboardPanel(String type, Map<String, Object> query, String visualization, Map<String, Integer> position) {}
```

### 6.3 Strategy: AlertEvaluationStrategy

```java
/** Strategy interface: different alert evaluation algorithms. Open/Closed - add new types without modifying AlertService. */
public interface AlertEvaluationStrategy {
    boolean evaluate(AlertRule rule, SearchService searchService);
}

/** Threshold: fire when metric/log count exceeds a value. E.g., error_count > 100. */
public class ThresholdAlertStrategy implements AlertEvaluationStrategy {
    @Override
    public boolean evaluate(AlertRule rule, SearchService searchService) {
        var condition = rule.getCondition();
        double threshold = ((Number) condition.get("threshold")).doubleValue();
        String operator = (String) condition.getOrDefault("operator", "gt");
        long actual = searchService.executeCountQuery(rule.getOrgId(), rule.getQuery());
        return switch (operator) {
            case "gt" -> actual > threshold;
            case "gte" -> actual >= threshold;
            case "lt" -> actual < threshold;
            case "lte" -> actual <= threshold;
            default -> false;
        };
    }
}

/** Anomaly: fire when value deviates from baseline (simplified: use moving avg + std dev). */
public class AnomalyDetectionAlertStrategy implements AlertEvaluationStrategy {
    @Override
    public boolean evaluate(AlertRule rule, SearchService searchService) {
        var condition = rule.getCondition();
        double sensitivity = ((Number) condition.getOrDefault("sensitivity", 3.0)).doubleValue();
        double current = searchService.executeMetricQuery(rule.getOrgId(), rule.getQuery());
        double baseline = searchService.getMetricBaseline(rule.getOrgId(), rule.getQuery(), 24);
        double stdDev = searchService.getMetricStdDev(rule.getOrgId(), rule.getQuery(), 24);
        return Math.abs(current - baseline) > sensitivity * stdDev;
    }
}

/** Rate of change: fire when value changes rapidly. E.g., (current - previous) / previous > 0.5. */
public class RateOfChangeAlertStrategy implements AlertEvaluationStrategy {
    @Override
    public boolean evaluate(AlertRule rule, SearchService searchService) {
        var condition = rule.getCondition();
        double maxRate = ((Number) condition.get("max_rate")).doubleValue();
        double current = searchService.executeMetricQuery(rule.getOrgId(), rule.getQuery());
        double previous = searchService.executeMetricQuery(rule.getOrgId(), rule.getQuery(), -1); // 1 interval ago
        if (previous == 0) return false;
        double rate = Math.abs((current - previous) / previous);
        return rate > maxRate;
    }
}
```

### 6.4 Observer: AlertObserver

```java
/** Observer interface: notification channels. Add new channels without changing AlertService. */
public interface AlertObserver {
    void onAlertFired(AlertRule rule, AlertSeverity severity, String message);
    String getChannelType();
}

public class EmailAlertObserver implements AlertObserver {
    private final EmailClient emailClient;
    private final List<String> recipients;

    public EmailAlertObserver(EmailClient emailClient, List<String> recipients) {
        this.emailClient = emailClient;
        this.recipients = recipients;
    }

    @Override
    public void onAlertFired(AlertRule rule, AlertSeverity severity, String message) {
        emailClient.send(recipients, "Alert: " + rule.getRuleName(), message);
    }

    @Override
    public String getChannelType() { return "email"; }
}

public class SlackAlertObserver implements AlertObserver {
    private final String webhookUrl;

    public SlackAlertObserver(String webhookUrl) { this.webhookUrl = webhookUrl; }

    @Override
    public void onAlertFired(AlertRule rule, AlertSeverity severity, String message) {
        // HTTP POST to webhookUrl with formatted message
    }

    @Override
    public String getChannelType() { return "slack"; }
}

public class PagerDutyAlertObserver implements AlertObserver {
    private final String integrationKey;

    public PagerDutyAlertObserver(String integrationKey) { this.integrationKey = integrationKey; }

    @Override
    public void onAlertFired(AlertRule rule, AlertSeverity severity, String message) {
        // Create PagerDuty incident via API
    }

    @Override
    public String getChannelType() { return "pagerduty"; }
}
```

### 6.5 Factory: AlertRuleFactory

```java
/** Factory: creates AlertRule with correct strategy based on rule_type. Encapsulates creation logic. */
public class AlertRuleFactory {
    public AlertRule create(UUID alertId, UUID orgId, String ruleName, String ruleType,
                            Map<String, Object> query, Map<String, Object> condition, boolean enabled) {
        AlertEvaluationStrategy strategy = switch (ruleType) {
            case "threshold" -> new ThresholdAlertStrategy();
            case "anomaly" -> new AnomalyDetectionAlertStrategy();
            case "rate_of_change" -> new RateOfChangeAlertStrategy();
            default -> throw new IllegalArgumentException("Unknown rule type: " + ruleType);
        };
        return new AlertRule(alertId, orgId, ruleName, ruleType, query, condition, enabled, strategy);
    }
}
```

### 6.6 Chain of Responsibility: LogPipeline

```java
/** Stage in the pipeline. Chain of Responsibility: each stage processes and passes to next. */
public interface LogPipelineStage {
    LogEvent process(LogEvent event);
}

/** Parse: extract structured fields from raw message. */
public class ParseStage implements LogPipelineStage {
    @Override
    public LogEvent process(LogEvent event) {
        // Parse JSON/log format, extract level, trace_id, etc.
        return event;
    }
}

/** Enrich: add org metadata, host info, geo, etc. */
public class EnrichStage implements LogPipelineStage {
    @Override
    public LogEvent process(LogEvent event) {
        // Add derived fields
        return event;
    }
}

/** Transform: normalize, redact PII, apply retention tags. */
public class TransformStage implements LogPipelineStage {
    @Override
    public LogEvent process(LogEvent event) {
        // Normalize, redact
        return event;
    }
}

/** Index: send to Elasticsearch. Final stage. */
public class IndexStage implements LogPipelineStage {
    private final ElasticsearchClient esClient;

    public IndexStage(ElasticsearchClient esClient) { this.esClient = esClient; }

    @Override
    public LogEvent process(LogEvent event) {
        esClient.index("logs-" + event.orgId(), event.toElasticsearchDocument());
        return event;
    }
}

/** Pipeline: chains stages. Each stage processes and passes to next. */
public class LogPipeline {
    private final List<LogPipelineStage> stages;

    public LogPipeline(List<LogPipelineStage> stages) { this.stages = stages; }

    public void process(LogEvent event) {
        LogEvent current = event;
        for (var stage : stages) {
            current = stage.process(current);
        }
    }
}
```

### 6.7 Services with Dependency Injection

```java
/** Ingestion: receives logs/metrics, runs through pipeline, indexes. Single responsibility. */
public class IngestionService {
    private final LogPipeline logPipeline;
    private final ElasticsearchClient esClient;

    public IngestionService(LogPipeline logPipeline, ElasticsearchClient esClient) {
        this.logPipeline = logPipeline;
        this.esClient = esClient;
    }

    public void ingestLog(LogEvent event) {
        logPipeline.process(event);
    }

    public void ingestMetric(Metric metric) {
        esClient.index("metrics-" + metric.orgId(), metric.toElasticsearchDocument());
    }
}

/** Search: executes queries against ES. Depends on abstraction (ElasticsearchClient). */
public class SearchService {
    private final ElasticsearchClient esClient;

    public SearchService(ElasticsearchClient esClient) { this.esClient = esClient; }

    public List<Map<String, Object>> searchLogs(UUID orgId, String query, Instant from, Instant to) {
        return esClient.search("logs-" + orgId, query, from, to);
    }

    public long executeCountQuery(UUID orgId, Map<String, Object> query) {
        return esClient.count("logs-" + orgId, query);
    }

    public double executeMetricQuery(UUID orgId, Map<String, Object> query) {
        return esClient.aggregate("metrics-" + orgId, query);
    }

    public double executeMetricQuery(UUID orgId, Map<String, Object> query, int offsetIntervals) {
        return esClient.aggregate("metrics-" + orgId, query, offsetIntervals);
    }

    public double getMetricBaseline(UUID orgId, Map<String, Object> query, int hours) {
        return esClient.avgOverRange("metrics-" + orgId, query, hours);
    }

    public double getMetricStdDev(UUID orgId, Map<String, Object> query, int hours) {
        return esClient.stdDevOverRange("metrics-" + orgId, query, hours);
    }
}

/** Alert: evaluates rules, notifies observers. Uses Strategy + Observer. */
public class AlertService {
    private final AlertRuleRepository alertRuleRepository;
    private final SearchService searchService;
    private final AlertRuleFactory ruleFactory;
    private final Map<UUID, List<AlertObserver>> observersByRule = new HashMap<>();

    public AlertService(AlertRuleRepository alertRuleRepository, SearchService searchService,
                        AlertRuleFactory ruleFactory) {
        this.alertRuleRepository = alertRuleRepository;
        this.searchService = searchService;
        this.ruleFactory = ruleFactory;
    }

    public void registerObserver(UUID alertId, AlertObserver observer) {
        observersByRule.computeIfAbsent(alertId, k -> new ArrayList<>()).add(observer);
    }

    public void evaluateAlerts(UUID orgId) {
        var rules = alertRuleRepository.findEnabledByOrg(orgId);
        for (var ruleDto : rules) {
            var rule = ruleFactory.create(ruleDto.alertId(), ruleDto.orgId(), ruleDto.ruleName(),
                    ruleDto.ruleType(), ruleDto.query(), ruleDto.condition(), true);
            if (rule.evaluate(searchService)) {
                notifyObservers(rule, AlertSeverity.WARNING, "Alert condition met");
            }
        }
    }

    private void notifyObservers(AlertRule rule, AlertSeverity severity, String message) {
        observersByRule.getOrDefault(rule.getAlertId(), List.of())
                .forEach(o -> o.onAlertFired(rule, severity, message));
    }
}
```

---

## 7. Edge Cases & Tests

| # | Edge Case | Test Description |
|---|-----------|-------------------|
| 1 | **Empty search results** | Given a query with no matches, assert empty list and total=0. |
| 2 | **Alert threshold boundary** | Threshold=100, actual=100 with operator "gt" → no fire; "gte" → fire. |
| 3 | **Anomaly with zero std dev** | When baseline has no variance (stdDev=0), avoid division by zero; use configurable fallback. |
| 4 | **Rate of change with previous=0** | When previous value is 0, rate is undefined; assert no fire (or handle with special logic). |
| 5 | **Log pipeline stage failure** | If ParseStage throws, pipeline should not index; assert event not in ES. |
| 6 | **Multi-tenant isolation** | User from org A cannot see/search logs from org B. Assert org_id filter in all queries. |
| 7 | **Disabled alert rule** | Disabled rules should not be evaluated. Assert evaluateAlerts skips them. |
| 8 | **No observers registered** | Alert fires but no observers → no exception; assert graceful no-op. |

### Sample Test Snippets

```java
@Test
void thresholdAlert_firesWhenGte() {
    var strategy = new ThresholdAlertStrategy();
    var rule = createRule(Map.of("threshold", 100, "operator", "gte"));
    when(searchService.executeCountQuery(any(), any())).thenReturn(100L);
    assertTrue(strategy.evaluate(rule, searchService));
}

@Test
void anomalyAlert_handlesZeroStdDev() {
    var strategy = new AnomalyDetectionAlertStrategy();
    when(searchService.getMetricStdDev(any(), any(), anyInt())).thenReturn(0.0);
    // Should not throw; use fallback or no-fire
}

@Test
void logPipeline_doesNotIndexOnParseFailure() {
    var parseStage = new ParseStage(); // throws on malformed
    var indexStage = mock(IndexStage.class);
    var pipeline = new LogPipeline(List.of(parseStage, indexStage));
    assertThrows(Exception.class, () -> pipeline.process(malformedEvent));
    verify(indexStage, never()).process(any());
}
```

---

## 8. Summary

| Aspect | Choice |
|--------|--------|
| **Metadata DB** | PostgreSQL – ACID, joins, JSONB for flexible rules/dashboards |
| **Logs/Metrics** | Elasticsearch – full-text search, aggregations, time-series, scale |
| **Alert evaluation** | Strategy pattern – Threshold, Anomaly, RateOfChange |
| **Notifications** | Observer pattern – Email, Slack, PagerDuty |
| **Log processing** | Chain of Responsibility – parse → enrich → transform → index |
| **Rule creation** | Factory – create rule + strategy from `rule_type` |
| **Data lifecycle** | ILM – hot (7d) → warm (90d) → cold (2y) → delete |
| **Multi-tenancy** | `org_id` in all tables and indices |

**Demonstrates:** Observability systems, design patterns (Strategy, Observer, Factory, Chain of Responsibility), SOLID, PostgreSQL + Elasticsearch hybrid, and ILM for cost-effective retention.
