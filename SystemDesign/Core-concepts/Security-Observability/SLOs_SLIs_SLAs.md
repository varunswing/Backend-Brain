# SLOs, SLIs, and SLAs

## Overview

Service Level concepts define and measure reliability targets for systems.

```
┌────────────────────────────────────────────────────────────┐
│                    Relationship                             │
│                                                            │
│   SLI (Indicator)  →  SLO (Objective)  →  SLA (Agreement)  │
│   "What we measure"   "What we target"    "What we promise" │
│                                                            │
│   Example:                                                 │
│   Latency p99       <200ms (target)      <500ms (contract) │
└────────────────────────────────────────────────────────────┘
```

---

## Definitions

### SLI (Service Level Indicator)
**What you measure** - A quantitative measure of service behavior.

```
SLI = (Good events / Total events) × 100

Examples:
- Request latency (p50, p95, p99)
- Error rate (5xx responses / total)
- Availability (successful requests / total)
- Throughput (requests per second)
```

### SLO (Service Level Objective)
**What you target** - Internal reliability target for an SLI.

```
SLO = "SLI should be X% over time period Y"

Examples:
- 99.9% of requests complete < 200ms over 30 days
- Error rate < 0.1% over rolling 7 days
- 99.95% availability per month
```

### SLA (Service Level Agreement)
**What you promise** - Contractual commitment with consequences.

```
SLA = SLO + Business Agreement + Consequences

Examples:
- 99.9% uptime guaranteed, or service credits
- Response time < 500ms, or penalty
- Support response within 4 hours, or refund
```

---

## Common SLIs

### The Four Golden Signals (Google SRE)

| Signal | Description | SLI Example |
|--------|-------------|-------------|
| **Latency** | Time to serve request | p99 < 200ms |
| **Traffic** | Demand on system | Requests/sec |
| **Errors** | Rate of failures | Error rate < 0.1% |
| **Saturation** | Resource utilization | CPU < 80% |

### Additional SLIs

| Category | SLI |
|----------|-----|
| **Availability** | Uptime percentage |
| **Throughput** | Successful requests/second |
| **Correctness** | % of correct responses |
| **Freshness** | Data age/staleness |
| **Durability** | Data loss rate |

---

## Calculating SLIs

### Availability SLI

```python
# Time-based
availability = (total_time - downtime) / total_time * 100

# Request-based (preferred)
availability = successful_requests / total_requests * 100

# Example
total_requests = 1_000_000
failed_requests = 500
availability = (1_000_000 - 500) / 1_000_000 * 100
# = 99.95%
```

### Latency SLI

```python
# Percentage of requests under threshold
latency_sli = requests_under_200ms / total_requests * 100

# Using percentiles
p50 = 50th_percentile(latencies)  # Median
p95 = 95th_percentile(latencies)  # Most users
p99 = 99th_percentile(latencies)  # Tail latency
```

### Error Rate SLI

```python
error_rate = error_responses / total_responses * 100

# Only count user-facing errors
error_responses = count(status >= 500)  # Server errors
# Exclude 4xx (client errors)
```

---

## Setting SLOs

### Choosing Targets

```
Too aggressive (99.999%):
- Extremely expensive to achieve
- Little room for innovation
- Engineering time spent on reliability

Too relaxed (95%):
- Users unhappy
- Competitive disadvantage
- Real issues masked

Sweet spot (99.9%):
- Balances reliability and velocity
- Leaves room for planned maintenance
- Achievable with good practices
```

### The "Nines" Table

| Availability | Downtime/Year | Downtime/Month |
|--------------|---------------|----------------|
| 99% | 3.65 days | 7.3 hours |
| 99.9% | 8.76 hours | 43.8 minutes |
| 99.95% | 4.38 hours | 21.9 minutes |
| 99.99% | 52.6 minutes | 4.38 minutes |
| 99.999% | 5.26 minutes | 26.3 seconds |

### SLO Best Practices

```python
# Define SLOs clearly
slo = {
    'name': 'API Availability',
    'sli': 'successful_requests / total_requests',
    'target': 99.9,
    'window': '30 days rolling',
    'measurement': 'per minute aggregation',
    'exclusions': ['planned maintenance', 'dependency failures']
}
```

---

## Error Budget

The amount of unreliability you can "spend" while meeting SLO.

```
Error Budget = 100% - SLO Target

Example:
SLO = 99.9% availability
Error Budget = 0.1% = 43.8 minutes/month

If you've used 30 minutes of downtime:
Remaining budget = 13.8 minutes
Budget consumed = 30/43.8 = 68.5%
```

### Error Budget Policy

```python
class ErrorBudgetPolicy:
    def __init__(self, slo_target, window_days=30):
        self.slo_target = slo_target
        self.error_budget = (100 - slo_target) / 100
        self.window = timedelta(days=window_days)
    
    def get_remaining_budget(self, current_availability):
        used = (100 - current_availability) / 100
        remaining = self.error_budget - used
        return max(0, remaining / self.error_budget * 100)
    
    def should_freeze_releases(self, budget_remaining_percent):
        """Freeze releases if budget is low"""
        if budget_remaining_percent < 10:
            return True, "Budget critical - freeze all releases"
        elif budget_remaining_percent < 25:
            return True, "Budget low - freeze non-critical releases"
        return False, "Budget healthy - releases allowed"

# Usage
policy = ErrorBudgetPolicy(slo_target=99.9)
remaining = policy.get_remaining_budget(current_availability=99.85)
freeze, message = policy.should_freeze_releases(remaining)
```

### Error Budget Actions

| Budget Remaining | Action |
|------------------|--------|
| > 50% | Normal development, experiments allowed |
| 25-50% | Caution, prioritize reliability work |
| 10-25% | Freeze non-critical changes |
| < 10% | Freeze all changes, incident response mode |

---

## Monitoring SLOs

### Prometheus/Grafana Example

```yaml
# Prometheus recording rules
groups:
- name: slo-rules
  rules:
  # Availability SLI
  - record: sli:availability:ratio
    expr: |
      sum(rate(http_requests_total{status!~"5.."}[5m]))
      /
      sum(rate(http_requests_total[5m]))
  
  # Latency SLI (% under 200ms)
  - record: sli:latency:ratio
    expr: |
      sum(rate(http_request_duration_seconds_bucket{le="0.2"}[5m]))
      /
      sum(rate(http_request_duration_seconds_count[5m]))
  
  # Error budget remaining
  - record: slo:error_budget:remaining
    expr: |
      1 - (
        (1 - sli:availability:ratio) 
        / 
        (1 - 0.999)  # SLO target
      )
```

### SLO Dashboard

```python
# Key metrics to display
dashboard_panels = [
    {
        'title': 'Current Availability (30d)',
        'query': 'avg_over_time(sli:availability:ratio[30d]) * 100',
        'threshold': 99.9
    },
    {
        'title': 'Error Budget Remaining',
        'query': 'slo:error_budget:remaining * 100',
        'thresholds': [10, 25, 50]  # Red, yellow, green
    },
    {
        'title': 'Error Budget Burn Rate',
        'query': 'rate of budget consumption',
        'alert_threshold': 2  # 2x normal burn rate
    },
    {
        'title': 'Time Until Budget Exhausted',
        'query': 'budget_remaining / burn_rate',
        'unit': 'hours'
    }
]
```

---

## Alerting on SLOs

### Multi-Window Alerts

```yaml
# Alert if burning budget too fast
# Fast burn: 2% budget in 1 hour (would exhaust in ~2 days)
- alert: SLOHighBurnRate
  expr: |
    (
      error_rate_1h > (14.4 * 0.001)  # 14.4x burn rate
      and
      error_rate_5m > (14.4 * 0.001)
    )
  labels:
    severity: critical
  annotations:
    summary: "High error budget burn rate"

# Slow burn: 5% budget in 6 hours (would exhaust in ~5 days)  
- alert: SLOSlowBurnRate
  expr: |
    (
      error_rate_6h > (6 * 0.001)
      and
      error_rate_30m > (6 * 0.001)
    )
  labels:
    severity: warning
```

---

## SLA Examples

### Cloud Provider SLA

```
Service: Cloud Compute
SLA: 99.99% monthly uptime

Credit Schedule:
- 99.99% - 99.0%: 10% credit
- 99.0% - 95.0%: 25% credit
- < 95.0%: 50% credit

Exclusions:
- Scheduled maintenance
- Customer-caused issues
- Force majeure
```

### Internal SLA Template

```yaml
service: Payment API
version: 1.0
effective_date: 2024-01-01

slos:
  - name: Availability
    target: 99.95%
    measurement_window: calendar month
    
  - name: Latency
    target: 99% of requests < 200ms
    measurement_window: rolling 7 days
    
  - name: Error Rate
    target: < 0.1%
    measurement_window: rolling 24 hours

escalation:
  - level: 1
    condition: "budget < 50%"
    action: "Notify engineering lead"
    
  - level: 2
    condition: "budget < 25%"
    action: "Page on-call, freeze releases"
    
  - level: 3
    condition: "budget < 10%"
    action: "Incident declared, all hands"
```

---

## Interview Questions

**Q: Explain SLI, SLO, and SLA.**
> SLI: metric measuring service (latency, availability). SLO: internal target for SLI (99.9% available). SLA: external contract with consequences (credits if SLO missed). SLI measures, SLO targets, SLA promises.

**Q: What is an error budget?**
> Allowed unreliability = 100% - SLO. For 99.9% SLO, budget is 0.1% (43 min/month). Used to balance reliability and feature velocity. When exhausted, freeze releases and fix issues.

**Q: How do you choose an SLO target?**
> Consider: user expectations, business requirements, cost of reliability, competitor benchmarks. Start conservative, adjust based on data. Too high = expensive, too low = unhappy users. 99.9% is common starting point.

---

## Quick Reference

| Concept | Definition | Example |
|---------|------------|---------|
| **SLI** | Measurement | Error rate = 0.05% |
| **SLO** | Target | Error rate < 0.1% |
| **SLA** | Contract | 99.9% or credits |
| **Error Budget** | Allowed failures | 0.1% = 43 min/month |
| **Burn Rate** | Budget consumption | 2x normal = alert |
