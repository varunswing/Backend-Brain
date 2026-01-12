# Monitoring and Observability

## Overview
Monitoring and observability are essential for understanding system behavior, detecting issues, and ensuring reliability. While related, they have distinct purposes.

## Monitoring vs Observability

| Aspect | Monitoring | Observability |
|--------|------------|---------------|
| **Focus** | Predefined metrics | System understanding |
| **Approach** | Known unknowns | Unknown unknowns |
| **Questions** | "Is it working?" | "Why isn't it working?" |
| **Data** | Metrics, alerts | Metrics, logs, traces |

## Three Pillars of Observability

### 1. Metrics
Numerical measurements over time.

**Types**:
- **Counter**: Cumulative (total requests)
- **Gauge**: Current value (CPU usage)
- **Histogram**: Distribution (response times)
- **Summary**: Percentiles (p95 latency)

**Example**:
```
# Counter
http_requests_total{method="GET", status="200"} 12345

# Gauge  
cpu_usage_percent{host="server1"} 75.5

# Histogram
http_request_duration_seconds_bucket{le="0.1"} 5000
```

### 2. Logs
Timestamped records of events.

**Structured Logging**:
```json
{
  "timestamp": "2024-01-01T10:00:00Z",
  "level": "ERROR",
  "service": "order-service",
  "trace_id": "abc123",
  "message": "Payment failed",
  "user_id": "12345",
  "error": "Insufficient funds"
}
```

**Log Levels**:
- **DEBUG**: Detailed diagnostic info
- **INFO**: General operational events
- **WARN**: Potential issues
- **ERROR**: Errors that need attention
- **FATAL**: System-stopping errors

### 3. Traces
Request flow across services.

**Components**:
- **Trace**: End-to-end request path
- **Span**: Individual operation within trace
- **Context**: Metadata propagated across services

**Example Trace**:
```
Trace ID: abc123
├── API Gateway (10ms)
│   └── Auth Service (5ms)
├── Order Service (50ms)
│   ├── Inventory Check (20ms)
│   └── Database Query (30ms)
└── Payment Service (100ms)
    └── External Gateway (80ms)
```

## Key Metrics to Monitor

### Four Golden Signals (Google SRE)

1. **Latency**: Time to serve requests
2. **Traffic**: Request volume
3. **Errors**: Rate of failed requests
4. **Saturation**: Resource utilization

### RED Method (for services)

- **R**ate: Requests per second
- **E**rrors: Failed requests per second
- **D**uration: Request latency distribution

### USE Method (for resources)

- **U**tilization: % time resource is busy
- **S**aturation: Queued work
- **E**rrors: Error count

## Tools and Technologies

### Metrics
- **Prometheus**: Open-source, pull-based
- **InfluxDB**: Time-series database
- **Datadog**: SaaS monitoring
- **CloudWatch**: AWS native

### Logging
- **ELK Stack**: Elasticsearch, Logstash, Kibana
- **Loki**: Log aggregation by Grafana
- **Splunk**: Enterprise logging
- **CloudWatch Logs**: AWS native

### Tracing
- **Jaeger**: Open-source, CNCF
- **Zipkin**: Twitter's distributed tracing
- **AWS X-Ray**: AWS native tracing
- **Datadog APM**: SaaS tracing

### Visualization
- **Grafana**: Dashboard and visualization
- **Kibana**: ELK visualization
- **Datadog**: Unified dashboards

## Alerting Strategies

### Alert Design

**Good Alerts**:
- Actionable
- Relevant to user impact
- Clear and contextual
- Prioritized appropriately

**Alert Fatigue Prevention**:
- Remove noisy alerts
- Use appropriate thresholds
- Group related alerts
- Escalation policies

### Alert Levels

| Level | Response | Example |
|-------|----------|---------|
| **Critical** | Immediate (page) | Service down |
| **Warning** | Soon (hours) | High memory usage |
| **Info** | Business hours | Unusual traffic |

## SLOs, SLIs, and SLAs

### Service Level Indicator (SLI)
Quantitative measure of service behavior.

```
SLI = (Good events / Total events) × 100

Example: 
Latency SLI = (Requests < 200ms / Total requests) × 100
```

### Service Level Objective (SLO)
Target value for an SLI.

```
SLO: 99.9% of requests complete in < 200ms
```

### Service Level Agreement (SLA)
Business contract with consequences.

```
SLA: 99.5% availability, or refund applies
```

### Error Budget
```
Error Budget = 100% - SLO

If SLO = 99.9%, Error Budget = 0.1%
In 30 days = 43.2 minutes of allowed downtime
```

## Implementation Patterns

### Distributed Tracing
```python
from opentelemetry import trace

tracer = trace.get_tracer(__name__)

def process_order(order_id):
    with tracer.start_as_current_span("process_order") as span:
        span.set_attribute("order_id", order_id)
        
        # Processing logic
        validate_order(order_id)
        process_payment(order_id)
        
        return result
```

### Metrics Collection
```python
from prometheus_client import Counter, Histogram

REQUEST_COUNT = Counter(
    'http_requests_total',
    'Total HTTP requests',
    ['method', 'endpoint', 'status']
)

REQUEST_LATENCY = Histogram(
    'http_request_duration_seconds',
    'HTTP request latency',
    ['method', 'endpoint']
)

def handle_request(method, endpoint):
    with REQUEST_LATENCY.labels(method, endpoint).time():
        result = process_request()
        REQUEST_COUNT.labels(method, endpoint, result.status).inc()
        return result
```

## Best Practices

### Logging
1. Use structured logging
2. Include correlation IDs
3. Log at appropriate levels
4. Don't log sensitive data
5. Centralize log aggregation

### Metrics
1. Follow naming conventions
2. Use appropriate metric types
3. Add meaningful labels
4. Set retention policies
5. Create useful dashboards

### Tracing
1. Propagate context across services
2. Add business context to spans
3. Sample appropriately at scale
4. Correlate traces with logs

### Alerting
1. Alert on symptoms, not causes
2. Include runbook links
3. Set up escalation policies
4. Review and tune regularly
5. Avoid alert fatigue

## Anti-patterns

1. **Too many metrics**: Causes confusion
2. **Metrics without dashboards**: Useless data
3. **Alert on everything**: Alert fatigue
4. **No correlation**: Siloed data
5. **Missing business context**: Technical-only view

## Conclusion

**Key Principles**:
- Observe don't just monitor
- Correlate metrics, logs, and traces
- Alert on user-impacting issues
- Use SLOs to guide decisions
- Continuous improvement

**Remember**: Good observability enables faster incident response, better capacity planning, and improved user experience.
