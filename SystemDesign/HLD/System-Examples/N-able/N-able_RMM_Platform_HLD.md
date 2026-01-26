# N-central RMM Platform - High Level Design (Remote Monitoring & Management)

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Capacity Estimation](#capacity-estimation)
3. [High-Level Design](#high-level-design)
4. [Request Flows](#request-flows)
5. [Detailed Component Design](#detailed-component-design)
6. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
7. [Failure Scenarios and Bottlenecks](#failure-scenarios-and-bottlenecks)
8. [Future Improvements](#future-improvements)
9. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **Device Management**
   - Register and onboard devices (endpoints: servers, workstations, network devices)
   - Maintain device inventory with hardware/software details
   - Group devices by customer/location/type
   - Device lifecycle management

2. **Real-Time Monitoring**
   - Collect metrics (CPU, memory, disk, network) from all endpoints
   - Receive heartbeats every 30 seconds
   - Real-time alerting on threshold violations
   - Custom monitoring scripts and checks
   - Service/process monitoring

3. **Remote Management**
   - Execute commands on remote devices
   - Deploy software/scripts
   - Remote desktop access
   - File transfer
   - Restart services/devices

4. **Alerting & Notifications**
   - Configurable alert rules
   - Multi-channel notifications (email, SMS, webhook, integration with ticketing)
   - Alert escalation policies
   - Alert suppression during maintenance windows

5. **Dashboard & Reporting**
   - Real-time health dashboard per MSP partner
   - Historical metrics and trends
   - Custom reports
   - SLA tracking

6. **Multi-Tenancy**
   - MSP partners manage multiple customers
   - Customer isolation (data, configuration, access)
   - Per-tenant customization (branding, alerts, thresholds)
   - Role-based access control (RBAC)

### Non-Functional Requirements

1. **Scale**: 
   - 9M+ managed endpoints globally
   - 25K+ MSP partners
   - Each MSP manages 50-100K devices (large partners)
   - Support 1000+ concurrent MSP users

2. **Availability**: 99.99% uptime (MSPs depend on monitoring)

3. **Latency**: 
   - Heartbeat processing: < 5 seconds
   - Critical alerts: < 10 seconds end-to-end
   - Dashboard queries: < 2 seconds
   - Command execution: < 3 seconds to reach agent

4. **Throughput**:
   - 9M devices × 2 heartbeats/min = 300K heartbeats/sec
   - 50M metric data points/sec
   - 10K commands/sec

5. **Data Retention**: 
   - Real-time metrics: 30 days (hot)
   - Aggregated metrics: 1 year (warm)
   - Historical trends: 3 years (cold)

6. **Consistency**: Eventual consistency acceptable for metrics; strong consistency for commands

7. **Security**: 
   - End-to-end encryption for agent communication
   - Certificate-based agent authentication
   - Multi-tenant data isolation
   - Audit logging

---

## Capacity Estimation

### Traffic Estimates

```
Endpoints:
- Total managed endpoints: 9 Million
- Active endpoints (online): 85% = 7.65M
- Heartbeat frequency: 30 seconds
- Heartbeats per second: 7.65M / 30 = 255K/sec

Metrics Collection:
- Metrics per heartbeat: 20 data points (CPU, memory, disk, network, etc.)
- Metrics per second: 255K × 20 = 5.1M metrics/sec
- Peak (business hours): 7M metrics/sec

MSP Partners:
- Total partners: 25,000
- Active partners (daily): 15,000
- Concurrent users (peak): 5,000
- Dashboard queries per user: 10/minute
- Total dashboard queries: 50K/min = 833 queries/sec

Commands:
- Commands per day: 50M
- Commands per second: 50M / 86400 ≈ 580 commands/sec
- Peak: 2000 commands/sec
```

### Storage Estimates

```
Endpoint Metadata:
- Per endpoint: 5 KB (hardware info, config, location)
- 9M endpoints × 5 KB = 45 GB

Time-Series Metrics (Hot - 30 days):
- 5M metrics/sec × 100 bytes/metric = 500 MB/sec
- Per day: 500 MB × 86400 = 43.2 TB/day
- 30 days: 43.2 TB × 30 = 1.3 PB (hot storage)

Time-Series Metrics (Warm - 1 year, downsampled):
- Downsample to 5-minute averages: 5M → 50K metrics/sec
- Per day: 50K × 100 × 86400 = 432 GB/day
- 1 year: 432 GB × 365 = 158 TB

Alerts:
- 1% of metrics generate alerts: 50K alerts/sec
- Per alert: 500 bytes (metadata, device, rule)
- Per day: 50K × 500 × 86400 = 2.16 TB/day
- 90 day retention: 194 TB

Command History:
- 50M commands/day × 2 KB = 100 GB/day
- 90 day retention: 9 TB

Total Storage: ~1.3 PB (hot) + 158 TB (warm) + 203 TB (alerts + commands) ≈ 1.7 PB
```

### Bandwidth Estimates

```
Inbound (from agents):
- Heartbeats: 255K/sec × 500 bytes = 127 MB/sec
- Metrics: 5M/sec × 100 bytes = 500 MB/sec
- Total inbound: 627 MB/sec ≈ 5 Gbps

Outbound (to agents):
- Commands: 580/sec × 5 KB = 2.9 MB/sec
- Config updates: 1K/sec × 10 KB = 10 MB/sec
- Total outbound: 13 MB/sec ≈ 104 Mbps

Dashboard API:
- 833 queries/sec × 50 KB average response = 41 MB/sec ≈ 328 Mbps

Total: 6 Gbps inbound + 500 Mbps outbound
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                  AGENTS (9M Endpoints)                                   │
│                         (Servers, Workstations, Network Devices)                        │
│                                                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │  Agent (Go)  │  │  Agent (Go)  │  │  Agent (Go)  │  │  Agent (Go)  │              │
│  │              │  │              │  │              │  │              │              │
│  │  - Heartbeat │  │  - Metrics   │  │  - Command   │  │  - Scripts   │              │
│  │  - Metrics   │  │  - Collect   │  │  - Executor  │  │  - Execution │              │
│  │  - TLS Auth  │  │  - Report    │  │  - TLS       │  │  - Report    │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         │                 │                 │                 │                       │
└─────────┼─────────────────┼─────────────────┼─────────────────┼───────────────────────┘
          │                 │                 │                 │
          │ HTTPS/TLS       │                 │                 │
          └─────────────────┴─────────────────┴─────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         API GATEWAY / LOAD BALANCER (ALB)                               │
│                                                                                         │
│  - TLS Termination                                                                      │
│  - Agent Authentication (mTLS)                                                          │
│  - Rate Limiting (per tenant)                                                           │
│  - Request Routing                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                    │
                    ┌───────────────┴───────────────┐
                    │                               │
                    ▼                               ▼
┌───────────────────────────────────┐   ┌───────────────────────────────────┐
│   AGENT INGESTION SERVICE (ECS)   │   │    WEB API SERVICE (ECS)          │
│       (Go Microservices)          │   │       (Go/GraphQL)                │
│                                   │   │                                   │
│  - Validate heartbeats            │   │  - MSP Dashboard API              │
│  - Parse metrics                  │   │  - Device management              │
│  - Enrich with metadata           │   │  - Alert configuration            │
│  - Write to message queue         │   │  - Reporting API                  │
│  - Command acknowledgment         │   │  - User authentication            │
│                                   │   │  - Multi-tenant isolation         │
└─────────────┬─────────────────────┘   └───────────────┬───────────────────┘
              │                                         │
              ▼                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE QUEUE (SQS + Kinesis)                              │
│                                                                                         │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐          │
│  │   Heartbeat   │  │    Metrics    │  │   Commands    │  │    Alerts     │          │
│  │     Queue     │  │    Stream     │  │     Queue     │  │    Queue      │          │
│  │    (SQS)      │  │   (Kinesis)   │  │    (SQS)      │  │   (SNS/SQS)   │          │
│  └───────────────┘  └───────────────┘  └───────────────┘  └───────────────┘          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
              │                 │                 │                 │
              ▼                 ▼                 ▼                 ▼
┌─────────────────────┐ ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│  HEARTBEAT          │ │    METRICS      │ │    COMMAND      │ │     ALERT       │
│  PROCESSOR          │ │   PROCESSOR     │ │   DISPATCHER    │ │   PROCESSOR     │
│  (Lambda/ECS)       │ │  (Lambda/ECS)   │ │  (Lambda/ECS)   │ │  (Lambda/ECS)   │
│                     │ │                 │ │                 │ │                 │
│ - Update status     │ │ - Aggregate     │ │ - Route to      │ │ - Evaluate      │
│ - Detect offline    │ │ - Evaluate      │ │   agents        │ │   rules         │
│ - Generate alerts   │ │   thresholds    │ │ - Track status  │ │ - Deduplicate   │
│                     │ │ - Store TSDB    │ │ - Retry logic   │ │ - Notify        │
└─────────────────────┘ └─────────────────┘ └─────────────────┘ └─────────────────┘
         │                       │                       │                 │
         ▼                       ▼                       ▼                 ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                 REDIS CLUSTER                                           │
│                          (ElastiCache - Real-time State)                                │
│                                                                                         │
│  device:{device_id}:status → { online: true, last_seen: ts, version: 12345 }           │
│  device:{device_id}:latest_metrics → { cpu: 45%, memory: 60%, ... }                    │
│  tenant:{msp_id}:devices → [device_1, device_2, ...]                                   │
│  command:{command_id}:status → { status: "pending", sent_at: ts }                      │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                   DATA LAYER                                            │
├──────────────────────┬──────────────────────┬──────────────────────┬───────────────────┤
│   DEVICE METADATA    │  TIME-SERIES DB      │   CONFIGURATION DB   │   COMMAND STORE   │
│   (DocumentDB)       │  (TimescaleDB/       │   (DocumentDB)       │   (DocumentDB)    │
│                      │   Prometheus)        │                      │                   │
│ - Device inventory   │ - Metrics history    │ - Tenant config      │ - Command history │
│ - Hardware specs     │ - Performance data   │ - Alert rules        │ - Execution logs  │
│ - Software list      │ - Aggregations       │ - Monitoring checks  │ - Scripts         │
│ - Tenant mapping     │ - Retention policies │ - RBAC policies      │ - Schedules       │
└──────────────────────┴──────────────────────┴──────────────────────┴───────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           SEARCH & ANALYTICS LAYER                                      │
├──────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  ┌──────────────────────────┐     ┌──────────────────────────┐                        │
│  │   OpenSearch Cluster     │     │   Analytics Service      │                        │
│  │                          │     │                          │                        │
│  │  - Device search         │     │  - SLA calculations      │                        │
│  │  - Alert search          │     │  - Trend analysis        │                        │
│  │  - Log aggregation       │     │  - Capacity planning     │                        │
│  │  - Full-text queries     │     │  - Custom reports        │                        │
│  └──────────────────────────┘     └──────────────────────────┘                        │
│                                                                                         │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         NOTIFICATION SERVICE (SNS + SES + SMS)                          │
│                                                                                         │
│  - Email notifications                                                                  │
│  - SMS alerts                                                                           │
│  - Webhook integrations                                                                 │
│  - Ticketing system integration (ServiceNow, Jira)                                      │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                             OBJECT STORAGE (S3)                                         │
│                                                                                         │
│  - Agent installer packages                                                             │
│  - Script library                                                                       │
│  - Report exports (PDF, CSV)                                                            │
│  - Audit logs (cold storage)                                                            │
│  - Metric archives (cold storage)                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **Agent**: Lightweight Go client running on endpoints
2. **Agent Ingestion Service**: High-throughput ingestion of agent data
3. **Message Queues**: Decouple ingestion from processing
4. **Metrics Processor**: Evaluate thresholds, store time-series data
5. **Command Dispatcher**: Route commands to agents
6. **Alert Engine**: Evaluate rules and send notifications
7. **Web API**: GraphQL/REST API for MSP dashboards
8. **Time-Series Database**: Store and query historical metrics

---

## Request Flows

### Agent Registration and Onboarding

```
┌────────┐  ┌─────────┐  ┌──────────┐  ┌───────────┐  ┌──────────┐  ┌────────┐
│ Agent  │  │   ALB   │  │  Web API │  │  Device   │  │  Config  │  │ Redis  │
│        │  │         │  │  Service │  │    DB     │  │    DB    │  │        │
└───┬────┘  └────┬────┘  └────┬─────┘  └─────┬─────┘  └────┬─────┘  └───┬────┘
    │            │            │              │             │            │
    │ POST /agent/register   │              │             │            │
    │ { device_id, msp_id,   │              │             │            │
    │   tenant_id, cert }    │              │             │            │
    │───────────>│───────────>│              │             │            │
    │            │            │ Validate cert│             │            │
    │            │            │ & tenant     │             │            │
    │            │            │              │             │            │
    │            │            │ Check device exists       │            │
    │            │            │─────────────>│             │            │
    │            │            │              │             │            │
    │            │            │<─────────────│             │            │
    │            │            │ (New device) │             │            │
    │            │            │              │             │            │
    │            │            │ Create device record      │            │
    │            │            │─────────────>│             │            │
    │            │            │              │             │            │
    │            │            │ Fetch monitoring config   │            │
    │            │            │──────────────────────────>│            │
    │            │            │              │             │            │
    │            │            │<──────────────────────────│            │
    │            │            │ (Check intervals, scripts)│            │
    │            │            │              │             │            │
    │            │            │ Cache device in Redis     │            │
    │            │            │───────────────────────────────────────>│
    │            │            │              │             │            │
    │<───────────│<───────────│              │             │            │
    │ 200 OK     │            │              │             │            │
    │ { device_id, config,   │              │             │            │
    │   heartbeat_interval,  │              │             │            │
    │   endpoints }          │              │             │            │
    │            │            │              │             │            │
    │ Agent starts heartbeat │              │             │            │
    │         loop           │              │             │            │
```

### Heartbeat and Metrics Collection

```
┌────────┐  ┌─────────┐  ┌──────────┐  ┌─────────┐  ┌─────────┐  ┌──────────┐  ┌────────┐
│ Agent  │  │   ALB   │  │ Ingestion│  │  SQS/   │  │ Metrics │  │  TSDB    │  │ Redis  │
│        │  │         │  │  Service │  │ Kinesis │  │Processor│  │          │  │        │
└───┬────┘  └────┬────┘  └────┬─────┘  └────┬────┘  └────┬────┘  └────┬─────┘  └───┬────┘
    │            │            │             │            │             │            │
    │ POST /agent/heartbeat  │             │            │             │            │
    │ { device_id, timestamp,│             │            │             │            │
    │   metrics: {cpu, mem,  │             │            │             │            │
    │   disk, network}, ... }│             │            │             │            │
    │───────────>│───────────>│             │            │             │            │
    │            │            │ Validate    │            │             │            │
    │            │            │ & enrich    │            │             │            │
    │            │            │             │            │             │            │
    │            │            │ Update last_seen in Redis│             │            │
    │            │            │─────────────────────────────────────────────────────>│
    │            │            │             │            │             │            │
    │            │            │ Push to message queue    │             │            │
    │            │            │────────────>│            │             │            │
    │            │            │             │            │             │            │
    │<───────────│<───────────│             │            │             │            │
    │ 200 OK (fast ack)       │             │            │             │            │
    │            │            │             │            │             │            │
    │            │            │             │ Consumer pulls batch     │            │
    │            │            │             │───────────>│             │            │
    │            │            │             │            │             │            │
    │            │            │             │            │ Aggregate & store        │
    │            │            │             │            │────────────>│            │
    │            │            │             │            │             │            │
    │            │            │             │            │ Update latest in Redis   │
    │            │            │             │            │─────────────────────────>│
    │            │            │             │            │             │            │
    │            │            │             │            │ Evaluate thresholds      │
    │            │            │             │            │ (if > threshold, alert)  │
```

### Command Execution Flow

```
┌────────┐  ┌─────────┐  ┌──────────┐  ┌─────────┐  ┌──────────┐  ┌────────┐
│MSP User│  │  Web    │  │ Command  │  │   SQS   │  │ Ingestion│  │ Agent  │
│        │  │  API    │  │ Service  │  │         │  │ Service  │  │        │
└───┬────┘  └────┬────┘  └────┬─────┘  └────┬────┘  └────┬─────┘  └───┬────┘
    │            │            │             │            │            │
    │ POST /commands/execute │             │            │            │
    │ { device_id, command,  │             │            │            │
    │   params, timeout }    │             │            │            │
    │───────────>│            │             │            │            │
    │            │ Validate auth & tenant   │            │            │
    │            │ Check permission         │            │            │
    │            │            │             │            │            │
    │            │ Create command record    │            │            │
    │            │───────────>│             │            │            │
    │            │            │             │            │            │
    │            │            │ Push to command queue    │            │
    │            │            │────────────>│            │            │
    │            │            │             │            │            │
    │<───────────│<───────────│             │            │            │
    │ 202 Accepted           │             │            │            │
    │ { command_id, status:  │             │            │            │
    │   "queued" }           │             │            │            │
    │            │            │             │            │            │
    │            │            │             │            │ Agent polls or receives │
    │            │            │             │            │ via long-polling/WS     │
    │            │            │             │<───────────│<───────────│
    │            │            │             │            │            │
    │            │            │             │ Command fetched         │
    │            │            │             │────────────────────────>│
    │            │            │             │            │            │
    │            │            │             │            │            │ Execute
    │            │            │             │            │            │ locally
    │            │            │             │            │            │
    │            │            │             │            │ POST /agent/command/result
    │            │            │             │            │<───────────│
    │            │            │             │            │            │
    │            │            │ Update command status    │            │
    │            │            │<────────────────────────│            │
    │            │            │             │            │            │
    │            │ (MSP polls for status)   │            │            │
    │───────────>│───────────>│             │            │            │
    │            │            │             │            │            │
    │<───────────│<───────────│             │            │            │
    │ { command_id, status:  │             │            │            │
    │   "completed", result }│             │            │            │
```

### Alert Generation and Notification

```
┌─────────┐  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌────────────┐
│ Metrics │  │  Alert  │  │  Rules   │  │   SNS    │  │   MSP      │
│Processor│  │ Engine  │  │   DB     │  │  (Notif) │  │   User     │
└────┬────┘  └────┬────┘  └────┬─────┘  └────┬─────┘  └─────┬──────┘
     │            │            │             │              │
     │ Metric: CPU = 95%       │             │              │
     │───────────>│            │             │              │
     │            │ Fetch alert rules        │              │
     │            │───────────>│             │              │
     │            │            │             │              │
     │            │<───────────│             │              │
     │            │ Rule: CPU > 80% for 5min│              │
     │            │            │             │              │
     │            │ Evaluate: TRIGGERED     │              │
     │            │            │             │              │
     │            │ Check alert state (dedupe)             │
     │            │ (Redis: last_alert_time)               │
     │            │            │             │              │
     │            │ Generate alert          │              │
     │            │            │             │              │
     │            │ Publish to SNS topic    │              │
     │            │────────────────────────>│              │
     │            │            │             │              │
     │            │            │             │ Email, SMS, Webhook
     │            │            │             │─────────────>│
     │            │            │             │              │
     │            │            │             │              │<─────
     │            │            │             │              │ View
     │            │            │             │              │ in
     │            │            │             │              │ Dashboard
```

---

## Detailed Component Design

### 1. Agent Architecture

```go
// Agent running on each endpoint (Go)
package main

import (
    "crypto/tls"
    "net/http"
    "time"
)

type Agent struct {
    DeviceID    string
    TenantID    string
    MSPID       string
    Config      *AgentConfig
    MetricsCollector *MetricsCollector
    CommandExecutor *CommandExecutor
    HTTPClient  *http.Client
    HeartbeatInterval time.Duration
}

type AgentConfig struct {
    APIEndpoint       string
    HeartbeatInterval time.Duration
    MetricsInterval   time.Duration
    CommandPollInterval time.Duration
    TLSCert           *tls.Certificate
}

type Metrics struct {
    DeviceID    string    `json:"device_id"`
    Timestamp   time.Time `json:"timestamp"`
    CPU         float64   `json:"cpu_percent"`
    Memory      float64   `json:"memory_percent"`
    DiskUsage   []DiskMetric `json:"disk_usage"`
    NetworkIO   NetworkMetric `json:"network_io"`
    Processes   []ProcessInfo `json:"processes"`
    Services    []ServiceStatus `json:"services"`
}

func (a *Agent) Start() {
    // Register with platform
    a.Register()
    
    // Start goroutines
    go a.HeartbeatLoop()
    go a.MetricsLoop()
    go a.CommandPollLoop()
    go a.WatchServices()
}

func (a *Agent) HeartbeatLoop() {
    ticker := time.NewTicker(a.HeartbeatInterval)
    defer ticker.Stop()
    
    for {
        select {
        case <-ticker.C:
            heartbeat := Heartbeat{
                DeviceID:  a.DeviceID,
                Timestamp: time.Now(),
                Status:    "online",
                Version:   AgentVersion,
            }
            
            // Send with retry logic
            if err := a.SendHeartbeat(heartbeat); err != nil {
                log.Error("Failed to send heartbeat", err)
                // Store locally for later sync
                a.StoreOffline(heartbeat)
            }
        }
    }
}

func (a *Agent) MetricsLoop() {
    ticker := time.NewTicker(a.Config.MetricsInterval)
    defer ticker.Stop()
    
    for {
        select {
        case <-ticker.C:
            metrics := a.MetricsCollector.Collect()
            
            if err := a.SendMetrics(metrics); err != nil {
                log.Error("Failed to send metrics", err)
                a.StoreOffline(metrics)
            }
        }
    }
}

func (a *Agent) CommandPollLoop() {
    // Long-polling or WebSocket connection
    ticker := time.NewTicker(a.Config.CommandPollInterval)
    defer ticker.Stop()
    
    for {
        select {
        case <-ticker.C:
            commands, err := a.FetchPendingCommands()
            if err != nil {
                log.Error("Failed to fetch commands", err)
                continue
            }
            
            for _, cmd := range commands {
                go a.ExecuteCommand(cmd)
            }
        }
    }
}

func (a *Agent) ExecuteCommand(cmd *Command) {
    result := &CommandResult{
        CommandID:  cmd.ID,
        DeviceID:   a.DeviceID,
        StartTime:  time.Now(),
        Status:     "running",
    }
    
    // Execute based on command type
    switch cmd.Type {
    case "script":
        output, err := a.CommandExecutor.RunScript(cmd.Script, cmd.Params)
        result.Output = output
        result.Error = err
    case "restart_service":
        err := a.CommandExecutor.RestartService(cmd.ServiceName)
        result.Error = err
    case "deploy_software":
        err := a.CommandExecutor.InstallSoftware(cmd.PackageURL)
        result.Error = err
    }
    
    result.EndTime = time.Now()
    result.Status = "completed"
    
    // Report back
    a.ReportCommandResult(result)
}

func (a *Agent) SendHeartbeat(hb Heartbeat) error {
    req, _ := http.NewRequest("POST", a.Config.APIEndpoint+"/agent/heartbeat", 
                               toJSON(hb))
    req.Header.Set("Content-Type", "application/json")
    
    ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
    defer cancel()
    
    resp, err := a.HTTPClient.Do(req.WithContext(ctx))
    if err != nil {
        return err
    }
    defer resp.Body.Close()
    
    if resp.StatusCode != 200 {
        return fmt.Errorf("unexpected status: %d", resp.StatusCode)
    }
    
    return nil
}
```

**Key Design Decisions:**

1. **Lightweight Agent**: Minimal resource footprint (<50 MB memory, <5% CPU)
2. **Resilient**: Store-and-forward for offline scenarios
3. **Secure**: mTLS for authentication, encrypted communication
4. **Efficient**: Batch metrics, compress data, adaptive polling
5. **Cross-platform**: Windows, Linux, macOS support

### 2. Agent Ingestion Service

```go
// High-throughput ingestion service (Go)
package ingestion

import (
    "github.com/aws/aws-sdk-go/service/sqs"
    "github.com/aws/aws-sdk-go/service/kinesis"
)

type IngestionService struct {
    SQSClient     *sqs.SQS
    KinesisClient *kinesis.Kinesis
    Redis         *redis.Client
    DeviceRepo    DeviceRepository
}

// Handle heartbeat with fast acknowledgment
func (s *IngestionService) HandleHeartbeat(ctx context.Context, 
                                           hb *Heartbeat) error {
    // 1. Validate device and tenant
    device, err := s.ValidateDevice(hb.DeviceID, hb.TenantID)
    if err != nil {
        return fmt.Errorf("invalid device: %w", err)
    }
    
    // 2. Update last_seen in Redis (fast operation)
    if err := s.UpdateDeviceStatus(ctx, device.ID, "online", time.Now()); err != nil {
        log.Error("Failed to update Redis", err)
        // Don't fail the request
    }
    
    // 3. Push to SQS for async processing
    message := HeartbeatMessage{
        DeviceID:  device.ID,
        TenantID:  device.TenantID,
        MSPID:     device.MSPID,
        Timestamp: hb.Timestamp,
        Metadata:  hb.Metadata,
    }
    
    if err := s.PushToSQS(ctx, "heartbeat-queue", message); err != nil {
        log.Error("Failed to push to SQS", err)
        // Store in DLQ or local buffer
        return err
    }
    
    // Fast response to agent
    return nil
}

// Handle metrics with batching
func (s *IngestionService) HandleMetrics(ctx context.Context, 
                                         metrics *Metrics) error {
    // 1. Validate and enrich
    device, err := s.ValidateDevice(metrics.DeviceID, metrics.TenantID)
    if err != nil {
        return fmt.Errorf("invalid device: %w", err)
    }
    
    // Enrich with tenant metadata
    metrics.MSPID = device.MSPID
    metrics.CustomerID = device.CustomerID
    metrics.Tags = device.Tags
    
    // 2. Update latest metrics in Redis (for dashboard queries)
    latestMetrics := LatestMetrics{
        CPU:       metrics.CPU,
        Memory:    metrics.Memory,
        DiskUsage: metrics.DiskUsage,
        UpdatedAt: time.Now(),
    }
    
    key := fmt.Sprintf("device:%s:latest_metrics", device.ID)
    s.Redis.Set(ctx, key, latestMetrics, 5*time.Minute)
    
    // 3. Push to Kinesis for time-series processing
    if err := s.PushToKinesis(ctx, "metrics-stream", metrics); err != nil {
        log.Error("Failed to push to Kinesis", err)
        return err
    }
    
    return nil
}

// Validate device with caching
func (s *IngestionService) ValidateDevice(deviceID, tenantID string) (*Device, error) {
    // Check Redis cache first
    cacheKey := fmt.Sprintf("device:%s", deviceID)
    
    var device Device
    if err := s.Redis.Get(ctx, cacheKey).Scan(&device); err == nil {
        // Verify tenant matches
        if device.TenantID != tenantID {
            return nil, errors.New("tenant mismatch")
        }
        return &device, nil
    }
    
    // Cache miss - fetch from database
    device, err := s.DeviceRepo.GetByID(deviceID)
    if err != nil {
        return nil, err
    }
    
    if device.TenantID != tenantID {
        return nil, errors.New("tenant mismatch")
    }
    
    // Cache for 5 minutes
    s.Redis.Set(ctx, cacheKey, device, 5*time.Minute)
    
    return device, nil
}

// Rate limiting per tenant
func (s *IngestionService) RateLimit(ctx context.Context, 
                                     tenantID string) error {
    key := fmt.Sprintf("ratelimit:%s:%d", tenantID, time.Now().Unix()/60)
    
    count, err := s.Redis.Incr(ctx, key).Result()
    if err != nil {
        return err
    }
    
    // Set expiry on first increment
    if count == 1 {
        s.Redis.Expire(ctx, key, 1*time.Minute)
    }
    
    // Limit: 10K requests per minute per tenant
    if count > 10000 {
        return errors.New("rate limit exceeded")
    }
    
    return nil
}
```

**Key Features:**

1. **Fast Acknowledgment**: Return 200 OK quickly, process asynchronously
2. **Multi-tenant Isolation**: Validate tenant on every request
3. **Rate Limiting**: Per-tenant rate limits to prevent noisy neighbors
4. **Caching**: Redis cache for device metadata to avoid DB lookups
5. **Resilience**: SQS/Kinesis for durability, DLQ for failed messages

### 3. Metrics Processing Pipeline

```go
// Metrics processor consuming from Kinesis
package metrics

type MetricsProcessor struct {
    KinesisClient *kinesis.Kinesis
    TSDB          TimeSeriesDB
    AlertEngine   *AlertEngine
    Redis         *redis.Client
}

func (p *MetricsProcessor) Start() {
    // Consume from Kinesis stream
    shardIterator := p.GetShardIterator("metrics-stream")
    
    for {
        records := p.GetRecords(shardIterator)
        
        // Process batch
        batch := make([]MetricPoint, 0, len(records))
        
        for _, record := range records {
            var metrics Metrics
            json.Unmarshal(record.Data, &metrics)
            
            // Convert to metric points
            points := p.ConvertToMetricPoints(metrics)
            batch = append(batch, points...)
            
            // Evaluate alert rules asynchronously
            go p.AlertEngine.Evaluate(metrics)
        }
        
        // Batch write to TSDB
        if len(batch) > 0 {
            if err := p.TSDB.WriteBatch(batch); err != nil {
                log.Error("Failed to write batch", err)
                // Retry or DLQ
            }
        }
        
        // Update shard iterator
        shardIterator = records.NextShardIterator
    }
}

func (p *MetricsProcessor) ConvertToMetricPoints(m Metrics) []MetricPoint {
    points := []MetricPoint{
        {
            Metric:    "cpu.percent",
            Value:     m.CPU,
            Timestamp: m.Timestamp,
            Tags: map[string]string{
                "device_id":   m.DeviceID,
                "tenant_id":   m.TenantID,
                "msp_id":      m.MSPID,
                "customer_id": m.CustomerID,
            },
        },
        {
            Metric:    "memory.percent",
            Value:     m.Memory,
            Timestamp: m.Timestamp,
            Tags: map[string]string{
                "device_id":   m.DeviceID,
                "tenant_id":   m.TenantID,
                "msp_id":      m.MSPID,
                "customer_id": m.CustomerID,
            },
        },
    }
    
    // Add disk metrics for each disk
    for _, disk := range m.DiskUsage {
        points = append(points, MetricPoint{
            Metric:    "disk.percent",
            Value:     disk.UsedPercent,
            Timestamp: m.Timestamp,
            Tags: map[string]string{
                "device_id":   m.DeviceID,
                "tenant_id":   m.TenantID,
                "msp_id":      m.MSPID,
                "customer_id": m.CustomerID,
                "disk":        disk.MountPoint,
            },
        })
    }
    
    return points
}
```

**Time-Series Database Schema (TimescaleDB/Prometheus):**

```sql
-- TimescaleDB schema
CREATE TABLE metrics (
    time        TIMESTAMPTZ NOT NULL,
    device_id   VARCHAR(64) NOT NULL,
    tenant_id   VARCHAR(64) NOT NULL,
    msp_id      VARCHAR(64) NOT NULL,
    metric_name VARCHAR(128) NOT NULL,
    value       DOUBLE PRECISION NOT NULL,
    tags        JSONB
);

-- Create hypertable for automatic partitioning by time
SELECT create_hypertable('metrics', 'time');

-- Create indexes for common queries
CREATE INDEX ON metrics (device_id, time DESC);
CREATE INDEX ON metrics (tenant_id, time DESC);
CREATE INDEX ON metrics (msp_id, time DESC);
CREATE INDEX ON metrics (metric_name, time DESC);

-- Retention policy: keep 30 days of raw data
SELECT add_retention_policy('metrics', INTERVAL '30 days');

-- Continuous aggregates for efficient querying
CREATE MATERIALIZED VIEW metrics_hourly
WITH (timescaledb.continuous) AS
SELECT 
    time_bucket('1 hour', time) AS bucket,
    device_id,
    tenant_id,
    msp_id,
    metric_name,
    AVG(value) as avg_value,
    MAX(value) as max_value,
    MIN(value) as min_value
FROM metrics
GROUP BY bucket, device_id, tenant_id, msp_id, metric_name;

-- Refresh policy for continuous aggregates
SELECT add_continuous_aggregate_policy('metrics_hourly',
    start_offset => INTERVAL '2 hours',
    end_offset => INTERVAL '1 hour',
    schedule_interval => INTERVAL '1 hour');
```

### 4. Alert Engine

```go
package alerting

type AlertEngine struct {
    RulesCache  *cache.Cache
    AlertStore  AlertRepository
    Redis       *redis.Client
    Notifier    *NotificationService
}

type AlertRule struct {
    ID          string
    TenantID    string
    Name        string
    Metric      string
    Condition   Condition
    Threshold   float64
    Duration    time.Duration  // Must be true for this long
    Severity    string         // critical, warning, info
    Enabled     bool
    Actions     []NotificationAction
}

type Condition string

const (
    ConditionGreaterThan    Condition = "gt"
    ConditionLessThan       Condition = "lt"
    ConditionEquals         Condition = "eq"
    ConditionNotEquals      Condition = "ne"
)

func (e *AlertEngine) Evaluate(metrics Metrics) {
    // Fetch alert rules for this tenant (cached)
    rules := e.GetRulesForTenant(metrics.TenantID)
    
    for _, rule := range rules {
        if !rule.Enabled {
            continue
        }
        
        // Extract relevant metric
        value := e.ExtractMetricValue(metrics, rule.Metric)
        if value == nil {
            continue
        }
        
        // Check if condition is met
        conditionMet := e.CheckCondition(*value, rule.Condition, rule.Threshold)
        
        if conditionMet {
            // Track state for duration-based alerts
            stateKey := fmt.Sprintf("alert_state:%s:%s", rule.ID, metrics.DeviceID)
            
            // Increment duration counter
            duration, _ := e.Redis.Incr(context.Background(), stateKey).Result()
            e.Redis.Expire(context.Background(), stateKey, 2*rule.Duration)
            
            // Convert duration to time (assuming metrics every 1 minute)
            actualDuration := time.Duration(duration) * time.Minute
            
            if actualDuration >= rule.Duration {
                // Alert should fire
                e.FireAlert(rule, metrics, *value)
                
                // Reset state
                e.Redis.Del(context.Background(), stateKey)
            }
        } else {
            // Condition not met, clear state
            stateKey := fmt.Sprintf("alert_state:%s:%s", rule.ID, metrics.DeviceID)
            e.Redis.Del(context.Background(), stateKey)
        }
    }
}

func (e *AlertEngine) FireAlert(rule AlertRule, metrics Metrics, value float64) {
    // Check for alert deduplication
    dedupKey := fmt.Sprintf("alert_fired:%s:%s", rule.ID, metrics.DeviceID)
    
    exists, _ := e.Redis.Exists(context.Background(), dedupKey).Result()
    if exists > 0 {
        // Alert already fired recently, don't re-fire
        return
    }
    
    // Create alert record
    alert := &Alert{
        ID:        generateID(),
        RuleID:    rule.ID,
        TenantID:  metrics.TenantID,
        MSPID:     metrics.MSPID,
        DeviceID:  metrics.DeviceID,
        Metric:    rule.Metric,
        Value:     value,
        Threshold: rule.Threshold,
        Severity:  rule.Severity,
        Message:   fmt.Sprintf("%s on device %s: %s %.2f (threshold: %.2f)",
                              rule.Name, metrics.DeviceID, rule.Metric, value, rule.Threshold),
        FiredAt:   time.Now(),
        Status:    "active",
    }
    
    // Save alert
    e.AlertStore.Save(alert)
    
    // Send notifications
    for _, action := range rule.Actions {
        go e.Notifier.Send(action, alert)
    }
    
    // Set deduplication key (don't re-fire for 15 minutes)
    e.Redis.SetEX(context.Background(), dedupKey, "1", 15*time.Minute)
}

func (e *AlertEngine) CheckCondition(value float64, condition Condition, 
                                     threshold float64) bool {
    switch condition {
    case ConditionGreaterThan:
        return value > threshold
    case ConditionLessThan:
        return value < threshold
    case ConditionEquals:
        return value == threshold
    case ConditionNotEquals:
        return value != threshold
    default:
        return false
    }
}
```

### 5. Multi-Tenant Isolation

```go
// Tenant isolation middleware
package middleware

func TenantIsolation(next http.Handler) http.Handler {
    return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
        // Extract tenant from JWT or API key
        tenantID := extractTenantID(r)
        if tenantID == "" {
            http.Error(w, "Unauthorized", http.StatusUnauthorized)
            return
        }
        
        // Add to context
        ctx := context.WithValue(r.Context(), "tenant_id", tenantID)
        
        // Continue
        next.ServeHTTP(w, r.WithContext(ctx))
    })
}

// Database queries with tenant filtering
type DeviceRepository struct {
    DB *mongo.Database
}

func (r *DeviceRepository) GetDevices(ctx context.Context, 
                                      tenantID string) ([]*Device, error) {
    // ALWAYS filter by tenant_id
    filter := bson.M{"tenant_id": tenantID}
    
    cursor, err := r.DB.Collection("devices").Find(ctx, filter)
    if err != nil {
        return nil, err
    }
    
    var devices []*Device
    if err := cursor.All(ctx, &devices); err != nil {
        return nil, err
    }
    
    return devices, nil
}

// Row-level security in PostgreSQL/TimescaleDB
-- Enable RLS
ALTER TABLE metrics ENABLE ROW LEVEL SECURITY;

-- Create policy to only allow access to own tenant's data
CREATE POLICY tenant_isolation_policy ON metrics
    USING (tenant_id = current_setting('app.current_tenant')::text);

-- Set tenant context in application
SET app.current_tenant = 'tenant_123';

-- Now all queries are automatically filtered
SELECT * FROM metrics WHERE device_id = 'device_456';
-- Implicitly becomes: 
-- SELECT * FROM metrics WHERE device_id = 'device_456' AND tenant_id = 'tenant_123';
```

### 6. Dashboard API (GraphQL)

```graphql
# GraphQL Schema for MSP Dashboard

type Query {
  # Get devices for the authenticated MSP
  devices(
    customerID: ID
    status: DeviceStatus
    limit: Int = 100
    offset: Int = 0
  ): DevicesConnection!
  
  # Get device details
  device(id: ID!): Device
  
  # Get metrics for a device
  deviceMetrics(
    deviceID: ID!
    metricNames: [String!]!
    from: DateTime!
    to: DateTime!
    aggregation: Aggregation = AVERAGE
  ): [MetricTimeSeries!]!
  
  # Get active alerts
  alerts(
    customerID: ID
    severity: AlertSeverity
    status: AlertStatus
    limit: Int = 100
  ): AlertsConnection!
}

type Mutation {
  # Execute a command on a device
  executeCommand(
    deviceID: ID!
    command: CommandInput!
  ): CommandExecution!
  
  # Update alert rule
  updateAlertRule(
    ruleID: ID!
    input: AlertRuleInput!
  ): AlertRule!
  
  # Acknowledge alert
  acknowledgeAlert(alertID: ID!): Alert!
}

type Subscription {
  # Real-time device status updates
  deviceStatusChanged(customerID: ID): DeviceStatus!
  
  # Real-time alerts
  alertFired(customerID: ID): Alert!
}

type Device {
  id: ID!
  name: String!
  status: DeviceStatus!
  lastSeen: DateTime!
  ipAddress: String
  operatingSystem: String
  customer: Customer!
  latestMetrics: LatestMetrics!
  alerts: [Alert!]!
}

type LatestMetrics {
  cpu: Float!
  memory: Float!
  diskUsage: [DiskUsage!]!
  updatedAt: DateTime!
}

type MetricTimeSeries {
  metric: String!
  dataPoints: [DataPoint!]!
}

type DataPoint {
  timestamp: DateTime!
  value: Float!
}

enum DeviceStatus {
  ONLINE
  OFFLINE
  WARNING
  CRITICAL
}

enum Aggregation {
  AVERAGE
  MAX
  MIN
  SUM
  COUNT
}
```

```go
// GraphQL resolver with tenant isolation
package graphql

type Resolver struct {
    DeviceRepo    DeviceRepository
    MetricsRepo   MetricsRepository
    CommandSvc    CommandService
    AlertRepo     AlertRepository
}

func (r *Resolver) Devices(ctx context.Context, 
                           args struct {
                               CustomerID *string
                               Status     *string
                               Limit      int32
                               Offset     int32
                           }) (*DevicesConnection, error) {
    // Extract tenant from context (set by auth middleware)
    tenantID := ctx.Value("tenant_id").(string)
    
    // CRITICAL: Always filter by tenant
    filter := DeviceFilter{
        TenantID:   tenantID,  // Enforced
        CustomerID: args.CustomerID,
        Status:     args.Status,
        Limit:      args.Limit,
        Offset:     args.Offset,
    }
    
    devices, err := r.DeviceRepo.Find(ctx, filter)
    if err != nil {
        return nil, err
    }
    
    return &DevicesConnection{
        Nodes: devices,
        TotalCount: len(devices),
    }, nil
}

func (r *Resolver) DeviceMetrics(ctx context.Context,
                                 args struct {
                                     DeviceID    string
                                     MetricNames []string
                                     From        time.Time
                                     To          time.Time
                                     Aggregation string
                                 }) ([]*MetricTimeSeries, error) {
    tenantID := ctx.Value("tenant_id").(string)
    
    // Verify device belongs to tenant (SECURITY CHECK)
    device, err := r.DeviceRepo.GetByID(ctx, args.DeviceID)
    if err != nil {
        return nil, err
    }
    
    if device.TenantID != tenantID {
        return nil, errors.New("unauthorized: device not in your tenant")
    }
    
    // Fetch metrics
    result := make([]*MetricTimeSeries, 0, len(args.MetricNames))
    
    for _, metricName := range args.MetricNames {
        dataPoints, err := r.MetricsRepo.Query(ctx, MetricQuery{
            DeviceID:    args.DeviceID,
            Metric:      metricName,
            From:        args.From,
            To:          args.To,
            Aggregation: args.Aggregation,
        })
        
        if err != nil {
            log.Error("Failed to query metric", err)
            continue
        }
        
        result = append(result, &MetricTimeSeries{
            Metric:     metricName,
            DataPoints: dataPoints,
        })
    }
    
    return result, nil
}

func (r *Resolver) ExecuteCommand(ctx context.Context,
                                  args struct {
                                      DeviceID string
                                      Command  CommandInput
                                  }) (*CommandExecution, error) {
    tenantID := ctx.Value("tenant_id").(string)
    userID := ctx.Value("user_id").(string)
    
    // Verify device belongs to tenant
    device, err := r.DeviceRepo.GetByID(ctx, args.DeviceID)
    if err != nil {
        return nil, err
    }
    
    if device.TenantID != tenantID {
        return nil, errors.New("unauthorized")
    }
    
    // Check user permissions
    if !r.hasPermission(ctx, userID, "execute_commands") {
        return nil, errors.New("forbidden: insufficient permissions")
    }
    
    // Create command
    cmd := &Command{
        ID:        generateID(),
        DeviceID:  args.DeviceID,
        TenantID:  tenantID,
        UserID:    userID,
        Type:      args.Command.Type,
        Params:    args.Command.Params,
        Timeout:   args.Command.Timeout,
        CreatedAt: time.Now(),
        Status:    "queued",
    }
    
    // Execute command
    execution, err := r.CommandSvc.Execute(ctx, cmd)
    if err != nil {
        return nil, err
    }
    
    return execution, nil
}
```

---

## Trade-offs and Tech Choices

### 1. Agent Communication: Long-Polling vs WebSocket vs MQTT

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Long-Polling (HTTP)** | Simple, works through firewalls, stateless | Higher latency (seconds), more overhead | ✅ **Chosen for commands** |
| **WebSocket** | Real-time, bidirectional, efficient | Stateful, harder to scale, connection mgmt | ✅ **Chosen for real-time features** |
| **MQTT** | Very lightweight, pub/sub, IoT-optimized | Additional protocol, complexity | ❌ Overkill |

**Decision**: 
- **Heartbeats/Metrics**: HTTP POST (simple, scalable)
- **Commands**: Long-polling HTTP (works through corporate firewalls)
- **Real-time dashboard**: WebSocket (for live updates)

### 2. Time-Series Database: TimescaleDB vs Prometheus vs InfluxDB

| Database | Pros | Cons | Decision |
|----------|------|------|----------|
| **TimescaleDB** | SQL, easy queries, continuous aggregates, compression | PostgreSQL overhead | ✅ **Chosen** |
| **Prometheus** | Purpose-built, efficient, good for alerts | Pull-based, limited multi-tenancy | ❌ Pull model doesn't fit |
| **InfluxDB** | Fast writes, good retention policies | Limited SQL, clustering costs | ❌ Vendor lock-in |

**Decision**: **TimescaleDB**
- Familiar SQL interface
- Excellent compression (90%+)
- Continuous aggregates for efficient long-term queries
- Strong multi-tenancy support with RLS

### 3. Message Queue: SQS vs Kafka vs Kinesis

| Service | Pros | Cons | Decision |
|---------|------|------|----------|
| **SQS** | Simple, fully managed, cheap | Limited throughput, no replay | ✅ **For commands/alerts** |
| **Kafka** | High throughput, replay, ordering | Complex ops, expensive | ❌ Too complex |
| **Kinesis** | High throughput, replay, AWS-native | More expensive than SQS | ✅ **For metrics stream** |

**Decision**: 
- **SQS**: Commands, alerts (lower volume, FIFO needed)
- **Kinesis**: Metrics stream (high throughput, need replay for reprocessing)

### 4. Multi-Tenancy: Separate DB vs Shared DB with Tenant ID

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Separate DB per tenant** | Strong isolation, easier compliance | Expensive, ops overhead | ❌ Too many tenants |
| **Shared DB with tenant_id** | Cost-effective, easier ops | Risk of data leakage | ✅ **With strong safeguards** |
| **Hybrid (large tenants separated)** | Balanced approach | Additional complexity | 🔄 **Future option** |

**Decision**: **Shared DB with tenant_id + Row-Level Security**
- Every query enforces `tenant_id` filter
- Database-level RLS as defense-in-depth
- Application-level checks in all APIs
- Redis caching includes tenant_id in key

### 5. Agent-Server Security

**Chosen Approach: Mutual TLS (mTLS)**

```
Agent Registration Flow:
1. Agent generates CSR (Certificate Signing Request)
2. Sends CSR to platform with initial auth token
3. Platform validates and signs certificate
4. Agent stores certificate
5. All future requests use mTLS

Benefits:
- Strong authentication
- Encrypts all communication
- Certificate revocation possible
- No passwords to manage
```

---

## Failure Scenarios and Bottlenecks

### 1. Agent Connectivity Issues

**Scenario**: Agent loses network connectivity

**Mitigation**:
```
Agent Behavior:
1. Store metrics/heartbeats locally (SQLite buffer)
2. Retry with exponential backoff
3. When reconnected, sync buffered data
4. Platform marks device "offline" after 2 missed heartbeats

Platform Behavior:
1. Detect offline: Last heartbeat > 1 minute
2. Generate "device offline" alert
3. Don't process stale metrics (> 5 minutes old)
4. When device reconnects, process buffered data
```

### 2. Time-Series Database Overload

**Scenario**: Write throughput exceeds TSDB capacity

**Symptoms**:
- Increased write latency
- Metrics processor lag
- Memory pressure

**Mitigation**:
```
1. Buffering Layer:
   - Kinesis buffers incoming metrics
   - Processor consumes at controlled rate
   
2. Batching:
   - Write 1000 metrics at a time
   - Reduces DB overhead
   
3. Downsampling:
   - Real-time: Every data point
   - After 1 day: 1-minute averages
   - After 7 days: 5-minute averages
   - After 30 days: 1-hour averages
   
4. Sharding:
   - Partition by tenant_id
   - Large tenants get dedicated shards
   
5. Rate Limiting:
   - Limit metrics per tenant per minute
   - Prevent runaway agents
```

### 3. Command Delivery Failures

**Scenario**: Command sent but agent doesn't receive it

**Handling**:
```go
type CommandStatus string

const (
    StatusQueued    CommandStatus = "queued"     // In SQS
    StatusSent      CommandStatus = "sent"       // Agent fetched
    StatusRunning   CommandStatus = "running"    // Agent executing
    StatusCompleted CommandStatus = "completed"  // Success
    StatusFailed    CommandStatus = "failed"     // Error
    StatusTimeout   CommandStatus = "timeout"    // Deadline exceeded
)

// Command expiration
func (s *CommandService) HandleExpiredCommands() {
    // Run every minute
    ticker := time.NewTicker(1 * time.Minute)
    
    for range ticker.C {
        // Find commands in "sent" or "running" state for > 5 minutes
        expiredCommands := s.CommandRepo.FindExpired(5 * time.Minute)
        
        for _, cmd := range expiredCommands {
            cmd.Status = StatusTimeout
            s.CommandRepo.Update(cmd)
            
            // Notify user
            s.NotifySvc.Send(Notification{
                TenantID: cmd.TenantID,
                Message:  fmt.Sprintf("Command %s timed out", cmd.ID),
            })
        }
    }
}
```

### 4. Hot Partition (Single Tenant Overwhelming System)

**Scenario**: Large MSP with 100K devices overwhelms a single processor

**Mitigation**:
```
1. Kinesis Sharding:
   - Use tenant_id as partition key
   - Large tenants automatically get more shards
   
2. Per-Tenant Rate Limiting:
   - Reject requests exceeding quota
   - Smooth out bursts with token bucket
   
3. Dedicated Resources:
   - Top 10 largest tenants get dedicated ECS tasks
   - Isolated processing pipelines
   
4. Auto-Scaling:
   - Scale ECS tasks based on Kinesis iterator age
   - Target: Process all messages within 30 seconds
```

### 5. Redis Failure

**Scenario**: Redis cluster becomes unavailable

**Impact**:
- Can't check device status quickly
- Can't cache device metadata
- Alert deduplication fails

**Mitigation**:
```
Graceful Degradation:
1. API continues to work (reads from DB)
2. Responses slower but functional
3. Alert deduplication disabled (may get duplicates)
4. Agent heartbeats still processed (writes to DB)

Redis Setup:
- ElastiCache with Multi-AZ
- Automatic failover enabled
- Read replicas for scaling
- Connection pooling with retry logic

Application Code:
func (s *Service) GetDeviceStatus(deviceID string) (*Status, error) {
    // Try Redis first
    status, err := s.Redis.Get(ctx, deviceID)
    if err == nil {
        return status, nil
    }
    
    // Fallback to database
    log.Warn("Redis unavailable, falling back to DB")
    return s.DB.GetDeviceStatus(deviceID)
}
```

### 6. Database Connection Pool Exhaustion

**Scenario**: Too many concurrent queries exhaust connection pool

**Mitigation**:
```
1. Connection Pooling:
   - Max connections: 100 per service instance
   - Connection timeout: 5 seconds
   - Idle timeout: 10 minutes
   
2. Query Optimization:
   - Index all tenant_id columns
   - Use prepared statements
   - Limit result sets (pagination)
   
3. Caching:
   - Redis for frequently accessed data
   - Reduce DB load by 80%+
   
4. Read Replicas:
   - Route dashboard queries to read replicas
   - Master for writes only
   
5. Circuit Breaker:
   - If DB errors > 50%, open circuit
   - Return cached data or error
   - Prevents cascading failure
```

### 7. Dashboard API Slow Queries

**Scenario**: MSP queries metrics for 10K devices at once

**Problem**:
```sql
-- Bad query (slow)
SELECT * FROM metrics
WHERE tenant_id = 'msp_123'
  AND time > NOW() - INTERVAL '24 hours'
ORDER BY time DESC;
```

**Solution**:
```sql
-- Optimized query with continuous aggregates
SELECT 
    device_id,
    time_bucket('5 minutes', time) AS bucket,
    AVG(value) as avg_value
FROM metrics
WHERE tenant_id = 'msp_123'
  AND device_id = ANY($1)  -- Filter by specific devices
  AND metric_name = 'cpu.percent'
  AND time > NOW() - INTERVAL '24 hours'
GROUP BY device_id, bucket
ORDER BY bucket DESC
LIMIT 1000;

-- Or use pre-computed continuous aggregate
SELECT * FROM metrics_5min
WHERE tenant_id = 'msp_123'
  AND device_id = ANY($1)
  AND bucket > NOW() - INTERVAL '24 hours'
ORDER BY bucket DESC;
```

**Additional Optimizations**:
1. **Pagination**: Return 100 devices at a time
2. **Aggregation**: Don't return raw data points
3. **Client-side caching**: Cache dashboard data for 30 seconds
4. **Incremental loading**: Load visible data first, lazy-load details

---

## Future Improvements

### 1. Predictive Monitoring

```
Current: Reactive alerts (CPU > 80%)
Future: Predictive alerts (CPU will reach 80% in 2 hours)

Implementation:
- Collect historical metrics
- Train ML models (time series forecasting)
- Predict resource exhaustion
- Alert proactively

Example:
"Disk on device_123 will be full in 4 days based on current trend"
```

### 2. Auto-Remediation

```
Current: Alert MSP when issue detected
Future: Automatically fix common issues

Examples:
- Disk space low → Clean temp files, rotate logs
- Service crashed → Restart service automatically
- Memory leak → Restart offending process
- Certificate expiring → Auto-renew

Implementation:
- Runbook automation
- Define safe remediation actions
- Execute with rollback capability
- Report actions taken
```

### 3. Anomaly Detection

```
Current: Static thresholds (CPU > 80%)
Future: Dynamic baselines (CPU 50% higher than normal)

Implementation:
- Learn normal behavior per device
- Detect deviations from baseline
- Consider time-of-day patterns
- Reduce false positives

Algorithm:
- Moving averages
- Standard deviation
- Seasonal decomposition
- Isolation forest
```

### 4. Global Distribution

```
Current: Single region deployment
Future: Multi-region for latency and compliance

Architecture:
- Regional ingestion endpoints
- Metrics stay in-region (GDPR compliance)
- Global control plane (device registry)
- Cross-region dashboards

Benefits:
- Lower latency for agents
- Data sovereignty compliance
- Disaster recovery
```

### 5. Advanced Dashboards

```
Current: Basic metrics visualization
Future: Custom dashboards, topology maps

Features:
- Drag-and-drop dashboard builder
- Network topology visualization
- Dependency mapping
- Custom SQL queries
- Embedded dashboards for end customers
```

### 6. Integration Ecosystem

```
Current: Limited integrations
Future: Rich integration marketplace

Integrations:
- Ticketing: ServiceNow, Jira, Zendesk
- Communication: Slack, Teams, PagerDuty
- ITSM tools
- Configuration management (Ansible, Terraform)
- Security tools (SIEM, EDR)

Implementation:
- Webhook-based integrations
- OAuth authentication
- Event streaming
- Bidirectional sync
```

---

## Interviewer Questions & Answers

### Q1: How do you handle 9M agents connecting simultaneously during a network partition recovery?

**Answer**:

```
Thundering Herd Problem:

1. Client-Side Jitter:
   - Agents don't reconnect immediately
   - Random backoff: 0-60 seconds
   - Spreads reconnection over time

2. Connection Rate Limiting:
   - ALB rate limit: 10K new connections/sec
   - Gradual ramp-up
   
3. Queueing:
   - SQS buffers incoming heartbeats
   - Processors catch up at their own pace
   
4. Priority:
   - Critical devices (servers) higher priority
   - Workstations can wait
   
5. Graceful Degradation:
   - Accept heartbeats, delay full processing
   - Update status quickly, process metrics later

Code:
```go
func (a *Agent) Reconnect() {
    // Exponential backoff with jitter
    backoff := time.Duration(rand.Intn(60)) * time.Second
    time.Sleep(backoff)
    
    // Attempt reconnection with retries
    for i := 0; i < 5; i++ {
        if err := a.Connect(); err == nil {
            return
        }
        time.Sleep(time.Duration(math.Pow(2, float64(i))) * time.Second)
    }
}
```

### Q2: How do you prevent one tenant from affecting another (noisy neighbor)?

**Answer**:

```
Multi-Tenant Isolation Strategies:

1. Resource Quotas:
   - Requests per minute: 10K per tenant
   - Concurrent connections: 1K per tenant
   - Storage: 10 TB per tenant
   
2. Rate Limiting:
   - Token bucket per tenant
   - Reject exceeding requests with 429 status
   
3. Queue Partitioning:
   - Separate SQS queues per large tenant
   - Shared queue for small tenants
   
4. Processing Isolation:
   - Large tenants (>10K devices) get dedicated ECS tasks
   - Prevents one tenant from clogging pipeline
   
5. Database Limits:
   - Query timeout: 10 seconds per tenant
   - Row limit: 10K rows per query
   - Statement timeout enforced

6. Circuit Breaker:
   - If tenant exceeds limits repeatedly, temporarily throttle
   - Alert tenant about excessive usage

Implementation:
```go
func (m *RateLimiter) Allow(tenantID string) bool {
    key := fmt.Sprintf("ratelimit:%s", tenantID)
    
    // Token bucket algorithm
    now := time.Now().Unix()
    tokens, _ := m.Redis.Get(ctx, key).Int64()
    
    // Refill tokens (1000 per minute = ~16/sec)
    tokensToAdd := (now - lastRefill) * 16
    tokens = min(tokens + tokensToAdd, 1000)
    
    if tokens > 0 {
        tokens--
        m.Redis.Set(ctx, key, tokens, time.Minute)
        return true
    }
    
    return false  // Rate limit exceeded
}
```

### Q3: How do you ensure commands are executed exactly once?

**Answer**:

```
Idempotency and Exactly-Once Semantics:

1. Idempotency Key:
   - Each command has unique ID
   - Agent tracks executed command IDs
   - If receives duplicate, returns cached result
   
2. Command States:
   queued → sent → received → running → completed/failed
   
3. Agent-Side Deduplication:
   ```go
   func (e *CommandExecutor) Execute(cmd *Command) {
       // Check if already executed
       if result := e.ResultCache.Get(cmd.ID); result != nil {
           return result  // Return cached result
       }
       
       // Mark as executing
       e.ExecutingCmds.Add(cmd.ID)
       defer e.ExecutingCmds.Remove(cmd.ID)
       
       // Execute command
       result := e.run(cmd)
       
       // Cache result for 24 hours
       e.ResultCache.Set(cmd.ID, result, 24*time.Hour)
       
       return result
   }
   ```

4. Server-Side Deduplication:
   - SQS FIFO queue ensures order
   - Deduplication window: 5 minutes
   - Command status tracked in DB

5. Timeout Handling:
   - If agent doesn't respond in 5 minutes, mark as timeout
   - Don't retry automatically (might not be idempotent)
   - Require manual retry from MSP

6. Acknowledgment:
   - Agent ACKs command receipt immediately
   - Reports progress periodically
   - Final result on completion
```

### Q4: How would you add support for monitoring Kubernetes clusters?

**Answer**:

```
Kubernetes Monitoring Extension:

1. Deployment Model:
   - DaemonSet: Agent on every node
   - Sidecar: Agent alongside workloads (optional)
   
2. What to Monitor:
   - Node metrics (existing agent works)
   - Pod metrics (new collector)
   - Container metrics
   - Cluster-level metrics (API server, etcd)
   - Events and logs
   
3. Data Collection:
   ```go
   type K8sCollector struct {
       clientset *kubernetes.Clientset
   }
   
   func (c *K8sCollector) CollectPodMetrics() []*Metric {
       pods, _ := c.clientset.CoreV1().Pods("").List(ctx, metav1.ListOptions{})
       
       metrics := []*Metric{}
       for _, pod := range pods.Items {
           metrics = append(metrics, &Metric{
               Name: "k8s.pod.status",
               Value: podStatusToValue(pod.Status.Phase),
               Tags: map[string]string{
                   "pod_name":      pod.Name,
                   "namespace":     pod.Namespace,
                   "cluster_id":    clusterID,
                   "device_id":     nodeID,
               },
           })
       }
       return metrics
   }
   ```

4. Schema Changes:
   - New entity type: "kubernetes_cluster"
   - Hierarchy: Cluster → Node → Pod → Container
   - Additional metrics tables
   
5. Dashboard Updates:
   - New "Kubernetes" section
   - Cluster health overview
   - Pod status grid
   - Resource utilization heatmaps
   
6. Alerts:
   - Pod crash looping
   - Node not ready
   - High pod restart rate
   - Resource quota exceeded
```

### Q5: How do you handle schema migrations across thousands of tenant databases?

**Answer**:

```
Schema Migration Strategy:

Current Architecture: Shared DB with tenant_id
Migration is simpler than separate DBs per tenant.

1. Blue-Green Deployment:
   - Run old and new schema simultaneously
   - Gradual cutover
   
2. Backward Compatible Changes:
   - Add new columns with defaults
   - Don't drop columns immediately
   - Deprecation period: 2 releases
   
3. Migration Process:
   ```sql
   -- Example: Adding new column
   
   -- Step 1: Add column (nullable)
   ALTER TABLE devices ADD COLUMN new_field VARCHAR(255);
   
   -- Step 2: Backfill data (batch by tenant)
   UPDATE devices 
   SET new_field = compute_value(old_field)
   WHERE tenant_id = 'tenant_123'
   LIMIT 1000;
   
   -- Repeat for all tenants
   
   -- Step 3: Deploy code using new column
   
   -- Step 4: Make column non-nullable
   ALTER TABLE devices ALTER COLUMN new_field SET NOT NULL;
   ```

4. Zero-Downtime Migration:
   - TimescaleDB supports online schema changes
   - Use `CREATE INDEX CONCURRENTLY`
   - Avoid table locks
   
5. Monitoring:
   - Track migration progress per tenant
   - Alert on failures
   - Rollback plan
   
6. Testing:
   - Test migration on staging
   - Select pilot tenants
   - Gradual rollout
```

### Q6: How would you optimize for a dashboard showing 10K devices at once?

**Answer**:

```
Dashboard Optimization Strategies:

1. Don't Load Everything:
   - Pagination: Show 100 devices at a time
   - Virtualization: Render only visible rows
   - Lazy loading: Load details on-demand
   
2. Aggregation:
   ```graphql
   query DashboardSummary {
     devices(mspID: "msp_123") {
       totalCount
       onlineCount
       offlineCount
       criticalAlerts
       
       # Only load summary stats, not all devices
       statusDistribution {
         status
         count
       }
       
       # Load first page of devices
       devices(limit: 100, offset: 0) {
         id
         name
         status
         # Minimal fields
       }
     }
   }
   ```

3. Caching:
   - Cache aggregated stats in Redis (1-minute TTL)
   - Cache per-tenant device list (5-minute TTL)
   - ETag/conditional requests
   
4. Incremental Updates:
   - WebSocket for real-time updates
   - Only send deltas (changed devices)
   ```json
   {
     "type": "device_status_update",
     "updates": [
       { "device_id": "dev_123", "status": "offline" }
     ]
   }
   ```

5. Database Query Optimization:
   ```sql
   -- Optimized query with index
   CREATE INDEX idx_devices_tenant_status 
   ON devices(tenant_id, status, last_seen DESC);
   
   -- Query with covering index
   SELECT id, name, status, last_seen
   FROM devices
   WHERE tenant_id = 'msp_123'
   ORDER BY last_seen DESC
   LIMIT 100 OFFSET 0;
   ```

6. Denormalization:
   - Store aggregated counts in Redis
   - Update on device status change
   ```
   tenant:msp_123:device_counts → {
     total: 10000,
     online: 8500,
     offline: 1200,
     warning: 300
   }
   ```

7. Frontend Optimization:
   - React virtualization (react-window)
   - Memoization
   - Debounced search/filters
```

### Q7: How do you ensure data consistency between Redis cache and database?

**Answer**:

```
Cache Consistency Strategies:

1. Cache-Aside Pattern (Current):
   ```go
   func (s *Service) GetDevice(deviceID string) (*Device, error) {
       // Try cache first
       if device := s.Cache.Get(deviceID); device != nil {
           return device, nil
       }
       
       // Cache miss - load from DB
       device, err := s.DB.GetDevice(deviceID)
       if err != nil {
           return nil, err
       }
       
       // Populate cache
       s.Cache.Set(deviceID, device, 5*time.Minute)
       
       return device, nil
   }
   
   func (s *Service) UpdateDevice(device *Device) error {
       // Update DB first
       if err := s.DB.UpdateDevice(device); err != nil {
           return err
       }
       
       // Invalidate cache
       s.Cache.Delete(device.ID)
       
       // Or update cache
       s.Cache.Set(device.ID, device, 5*time.Minute)
       
       return nil
   }
   ```

2. Write-Through Pattern (For critical data):
   ```go
   func (s *Service) UpdateDeviceStatus(deviceID string, status string) error {
       // Write to cache first (fast acknowledgment)
       s.Cache.Set(fmt.Sprintf("status:%s", deviceID), status, 5*time.Minute)
       
       // Async write to DB
       go func() {
           if err := s.DB.UpdateDeviceStatus(deviceID, status); err != nil {
               log.Error("Failed to persist status", err)
               // Retry or DLQ
           }
       }()
       
       return nil
   }
   ```

3. Cache Invalidation:
   - On UPDATE: Delete cache key
   - On DELETE: Delete cache key
   - TTL: All cache entries expire after 5 minutes
   
4. Eventual Consistency Acceptable:
   - Device status: OK if stale by 30 seconds
   - Metrics: OK if stale by 1 minute
   - Commands: NOT OK - don't cache
   
5. Redis as Source of Truth (for ephemeral data):
   - Device online/offline status
   - Latest metrics
   - Active sessions
   - Don't need persistence
   
6. Database as Source of Truth (for important data):
   - Device configuration
   - Alert rules
   - User permissions
   - Always persist to DB

7. Change Data Capture (Future):
   - DB triggers → Kafka → Cache invalidation
   - Ensures cache always consistent
   ```
   DB Change → CDC → Kafka Event → Cache Invalidator
   ```
```

---

## Summary

### Key Design Principles

1. **Scalability**: Handle 9M+ endpoints with horizontal scaling
2. **Multi-Tenancy**: Strong isolation between MSP partners
3. **Reliability**: 99.99% uptime with graceful degradation
4. **Performance**: Fast acknowledgments, async processing
5. **Security**: mTLS, tenant isolation, audit logging
6. **Cost Efficiency**: Downsample metrics, optimize storage

### Technologies Used

- **Language**: Go (agents and services)
- **Cloud**: AWS
- **Load Balancer**: ALB with TLS termination
- **Compute**: ECS (Fargate) for services, Lambda for event processing
- **Message Queues**: SQS (commands/alerts), Kinesis (metrics)
- **Cache**: Redis (ElastiCache) for real-time state
- **Databases**: 
  - DocumentDB (device metadata, configuration)
  - TimescaleDB (time-series metrics)
- **Search**: OpenSearch (device search, log aggregation)
- **Storage**: S3 (archives, installers)
- **API**: GraphQL (dashboard), REST (agents)
- **Monitoring**: CloudWatch, X-Ray

### Scale Targets

- **9M endpoints**: ✅ Horizontally scalable
- **5M metrics/sec**: ✅ Kinesis + TimescaleDB
- **300K heartbeats/sec**: ✅ SQS + parallel processors
- **99.99% uptime**: ✅ Multi-AZ, auto-scaling, circuit breakers
- **< 10s alert latency**: ✅ Real-time processing pipeline

---

This HLD demonstrates a production-ready RMM platform capable of managing millions of endpoints at scale with strong multi-tenancy, security, and performance characteristics—exactly what N-able operates at scale.
