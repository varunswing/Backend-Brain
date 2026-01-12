# Elasticsearch Monitoring System - System Design Interview

## Problem Statement
*"Design a monitoring platform like Datadog that ingests millions of logs/metrics per second, provides real-time search, alerting, and dashboards for distributed systems."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What types of data?" → Application logs, infrastructure metrics, APM traces
- "Do we need real-time alerting?" → Yes, threshold alerts, anomaly detection
- "Should we support custom dashboards?" → Yes, drag-drop builder, shared views
- "What about log search?" → Full-text search, structured queries, time filtering
- "Data retention needs?" → Hot (7 days), warm (90 days), cold (2 years)

**Non-Functional Requirements:**
- "Data ingestion volume?" → 100TB/day, 1M+ events/second peak
- "Search latency?" → <1 second complex queries, <100ms simple lookups  
- "Availability?" → 99.95% uptime (monitoring is critical)
- "Concurrent users?" → 10K engineers, 1K concurrent dashboards

### Requirements Summary:
- **Scale**: 100TB/day ingestion, 1M+ events/second, 10K users
- **Features**: Log search, metrics, alerting, dashboards, APM
- **Performance**: <1s search, real-time ingestion
- **Storage**: Multi-tier (hot/warm/cold) with retention policies

---

## Phase 2: Capacity Estimation (5 minutes)

### Data Volume:
```
Log ingestion: 100TB/day = 1.2GB/second
Event rate: 1M events/second peak
Average event size: 1KB logs, 100B metrics
Daily events: 86.4B events/day
```

### Storage Requirements:
```
Hot storage (7 days): 700TB SSD
Warm storage (90 days): 9PB HDD  
Cold storage (2 years): 73PB archived
Total with replicas: ~15PB
```

### Infrastructure:
```
Elasticsearch nodes: 200+ for hot data
Memory for search: 70TB RAM total
Ingestion nodes: 50+ for 1.2GB/sec
Network: 10GB/sec for replication
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐
│Applications │  │Infrastructure│
│- App Logs   │  │- System Logs │
│- Metrics    │  │- Metrics     │
└─────────────┘  └─────────────┘
       │                │
       └────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│        Ingestion Layer          │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │Filebeat │ │Logstash │ │Agents│ │
│ │- Ship   │ │- Parse  │ │- APM │ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│         Apache Kafka            │
│- High throughput buffering      │
│- Partitioned topics            │
└─────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│       Processing Layer          │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │Stream   │ │Batch    │ │ML   │ │
│ │Process  │ │ETL      │ │Anomaly│ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│    Elasticsearch Cluster        │
│                                 │
│ Hot Nodes   Warm Nodes  Cold    │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │SSD      │ │HDD      │ │Archive│ │
│ │7 days   │ │90 days  │ │2 years│ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│      Application Layer          │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │Search   │ │Dashboard│ │Alert│ │
│ │API      │ │Kibana   │ │Mgr  │ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
```

### Key Components:
- **Kafka**: High-throughput message queue for buffering
- **Elasticsearch**: Distributed search and storage
- **Processing**: Stream for real-time, batch for aggregations
- **Alerting**: Rule engine with notifications

---

## Phase 4: Database Design (8 minutes)

### Elasticsearch Index Templates:
```json
// Logs index template
{
  "index_patterns": ["logs-*"],
  "template": {
    "mappings": {
      "properties": {
        "@timestamp": {"type": "date"},
        "service": {"type": "keyword"},
        "level": {"type": "keyword"},
        "message": {"type": "text"},
        "host": {"type": "keyword"},
        "trace_id": {"type": "keyword"},
        "duration_ms": {"type": "long"},
        "error": {
          "properties": {
            "type": {"type": "keyword"},
            "message": {"type": "text"}
          }
        }
      }
    }
  }
}

// Metrics index template
{
  "index_patterns": ["metrics-*"],
  "template": {
    "mappings": {
      "properties": {
        "@timestamp": {"type": "date"},
        "metric_name": {"type": "keyword"},
        "value": {"type": "double"},
        "tags": {"type": "flattened"},
        "service": {"type": "keyword"}
      }
    }
  }
}
```

### Index Lifecycle Management:
```json
{
  "hot_phase": {
    "actions": {
      "rollover": {"max_size": "50gb", "max_age": "1d"}
    }
  },
  "warm_phase": {
    "min_age": "7d",
    "actions": {"shrink": {"number_of_shards": 1}}
  },
  "cold_phase": {
    "min_age": "90d",
    "actions": {"freeze": {}}
  },
  "delete_phase": {"min_age": "2y"}
}
```

### PostgreSQL for Metadata:
```sql
-- Organizations and users
CREATE TABLE organizations (
    org_id UUID PRIMARY KEY,
    org_name VARCHAR(200),
    data_retention_days INTEGER DEFAULT 90
);

CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    org_id UUID REFERENCES organizations(org_id),
    email VARCHAR(255) UNIQUE,
    role VARCHAR(50) DEFAULT 'viewer'
);

-- Alert rules
CREATE TABLE alert_rules (
    alert_id UUID PRIMARY KEY,
    org_id UUID REFERENCES organizations(org_id),
    rule_name VARCHAR(300),
    query JSONB NOT NULL,
    condition JSONB NOT NULL,
    is_enabled BOOLEAN DEFAULT true,
    notification_channels JSONB DEFAULT '[]'
);
```

### Redis Caching:
```javascript
// Search result cache
"search_cache:{query_hash}": {
  "results": [...],
  "total_hits": 50000,
  "ttl": 300
}

// Alert state
"alert_state:{alert_id}": {
  "is_firing": true,
  "last_check": 1704110400,
  "ttl": 60
}
```

---

## Phase 5: Critical Flow - Log Search (8 minutes)

### Step-by-Step Flow:
```
1. Search request:
   POST /api/search
   {"query": "ERROR AND service:user-service", "time_range": "now-1h"}

2. Query processing:
   - Validate permissions and syntax
   - Check Redis cache for results
   - Add organization filters for multi-tenancy

3. Elasticsearch execution:
   - Route to appropriate indices/shards
   - Execute distributed search
   - Apply time range and text filters

4. Response handling:
   - Cache results (5 min TTL)
   - Return paginated results with metadata
```

### Technical Challenges:
**Search Performance**: "Index optimization, query caching, hot/warm/cold tiers"
**High-Volume Ingestion**: "Kafka buffering, batch processing, circuit breakers"
**Multi-tenancy**: "Index-level security, organization isolation"
**Scalability**: "Horizontal Elasticsearch scaling, auto-scaling"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Ingestion throughput** - 1M+ events/second
2. **Search performance** - Complex queries across time ranges
3. **Storage costs** - 100TB+ daily with retention
4. **Memory pressure** - Large aggregations

### Scaling Solutions:
**Ingestion**: Kafka partitioning, bulk indexing, dedicated ingest nodes
**Search**: Query caching, index optimization, search replicas
**Storage**: ILM policies, tiered storage, compression
**Performance**: JVM tuning, resource isolation, query optimization

### Trade-offs:
- **Real-time vs Cost**: Immediate indexing vs batch efficiency
- **Search Speed vs Storage**: More replicas/memory vs costs
- **Retention vs Performance**: Long retention vs query speed

---

## Success Metrics:
- **Ingestion**: >1M events/second with <1% data loss
- **Search Latency**: <1 second P95 for complex queries
- **Uptime**: 99.95% availability
- **Alert Accuracy**: <5% false positive rate
- **Storage Cost**: <$0.10 per GB per month averaged

**🎯 Demonstrates observability expertise, distributed search systems, and mission-critical monitoring infrastructure.**