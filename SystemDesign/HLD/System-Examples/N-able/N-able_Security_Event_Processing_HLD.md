# N-able Security Event Processing System - High Level Design
## (Adlumin MDR - Managed Detection & Response)

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

1. **Event Ingestion**
   - Collect security events from 9M+ endpoints
   - EDR (Endpoint Detection & Response) telemetry
   - Network logs (firewall, IDS/IPS)
   - Identity events (Active Directory, Azure AD, Microsoft 365)
   - Application logs (web servers, databases)
   - Support 500B+ events per month

2. **Real-Time Threat Detection**
   - Signature-based detection (known threats)
   - Behavior-based detection (anomalies)
   - Machine learning models for zero-day threats
   - Threat intelligence integration
   - MITRE ATT&CK framework mapping

3. **Automated Response (70% auto-remediation)**
   - Isolate compromised endpoints
   - Block malicious IPs/domains
   - Kill malicious processes
   - Quarantine files
   - Reset compromised credentials

4. **Alert Management**
   - De-duplication and correlation
   - Severity scoring
   - Alert enrichment with context
   - Escalation to SOC analysts (30% requiring human review)
   - Integration with ticketing systems

5. **Threat Hunting**
   - Historical event search
   - Query language for analysts
   - Threat hunting playbooks
   - Investigation workflows

6. **Identity Threat Detection (ITDR)**
   - Monitor authentication events
   - Detect credential theft
   - Impossible travel detection
   - Privilege escalation detection
   - Compromised account detection

7. **Compliance & Reporting**
   - Audit logs
   - Compliance dashboards (SOC 2, HIPAA, PCI-DSS)
   - Executive reports
   - Threat intelligence reports

8. **Multi-Tenancy**
   - Per-tenant detection rules
   - Isolated investigation workspaces
   - Tenant-specific threat intelligence
   - RBAC

### Non-Functional Requirements

1. **Scale**: 
   - 500B+ security events per month
   - 9M+ monitored endpoints
   - 25K+ MSP partners
   - Real-time processing (< 10 seconds)

2. **Availability**: 99.99% uptime

3. **Latency**:
   - Event ingestion: < 5 seconds
   - Threat detection: < 10 seconds
   - Auto-remediation: < 30 seconds
   - Search query: < 3 seconds (recent data)

4. **Throughput**: 
   - 200K events/second sustained
   - 500K events/second peak

5. **Data Retention**:
   - Hot storage (OpenSearch): 30 days
   - Warm storage (S3): 1 year
   - Cold storage (S3 Glacier): 7 years

6. **Accuracy**:
   - False positive rate: < 5%
   - False negative rate: < 1%
   - Auto-remediation accuracy: 95%+

---

## Capacity Estimation

### Traffic Estimates

```
Security Events:
- Total events per month: 500 Billion
- Events per second: 500B / (30*86400) ≈ 193K events/sec
- Peak (business hours): 300K events/sec

Event Sources:
- Endpoint events (EDR): 60% = 116K/sec
- Network events: 20% = 39K/sec
- Identity events: 10% = 19K/sec
- Application logs: 10% = 19K/sec

Alerts Generated:
- Alert rate: 0.1% of events = 193 alerts/sec
- After deduplication: ~20 alerts/sec
- Requiring human review (30%): 6 alerts/sec

Automated Actions:
- Auto-remediation (70% of alerts): 14 actions/sec
```

### Storage Estimates

```
Event Storage:
- Average event size: 500 bytes (JSON)
- Daily events: 193K/sec * 86400 = 16.7B events/day
- Daily storage: 16.7B * 500 bytes = 8.35 TB/day

Retention:
- Hot (30 days): 8.35 TB * 30 = 250 TB (OpenSearch)
- Warm (1 year): 8.35 TB * 365 = 3 PB (S3)
- Cold (7 years): 8.35 TB * 365 * 7 = 21 PB (S3 Glacier)

With compression (5:1):
- Hot: 50 TB
- Warm: 600 TB
- Cold: 4.2 PB

Total: ~5 PB

Alerts:
- 20 alerts/sec * 500 bytes = 10 KB/sec
- Per day: 10 KB * 86400 = 864 MB/day
- 90 day retention: 77 GB

ML Models:
- Model artifacts: 10 GB
- Training data: 1 TB
- Feature store: 100 GB
```

### Bandwidth Estimates

```
Inbound (Event Ingestion):
- 193K events/sec * 500 bytes = 96 MB/sec = 768 Mbps
- Peak: 1.2 Gbps

Outbound (Queries & Dashboards):
- 1000 concurrent MSP users
- Avg query: 100 KB response
- 10 queries/minute per user
- Total: 1000 * 100 KB * 10 / 60 = 16.7 MB/sec = 133 Mbps

Command & Control (Remediation):
- 14 actions/sec * 5 KB = 70 KB/sec = 560 Kbps

Total: ~2 Gbps inbound, 150 Mbps outbound
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         EVENT SOURCES (9M+ Endpoints)                                   │
│                                                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Endpoint   │  │   Network    │  │   Identity   │  │ Application  │              │
│  │   (EDR Agent)│  │  (Firewall,  │  │  (AD, Azure  │  │   (Web,      │              │
│  │              │  │   IDS/IPS)   │  │   AD, M365)  │  │   Database)  │              │
│  │ - Process    │  │              │  │              │  │              │              │
│  │ - File       │  │ - Traffic    │  │ - Auth       │  │ - Access     │              │
│  │ - Network    │  │ - Packets    │  │ - Privilege  │  │ - Errors     │              │
│  │ - Registry   │  │ - DNS        │  │ - Changes    │  │ - Anomalies  │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         │                 │                 │                 │                       │
└─────────┼─────────────────┼─────────────────┼─────────────────┼───────────────────────┘
          │                 │                 │                 │
          └─────────────────┴─────────────────┴─────────────────┘
                            │
                            │ HTTPS/TLS, Syslog, API
                            ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                          API GATEWAY / LOAD BALANCER                                    │
│                                                                                         │
│  - TLS termination                                                                      │
│  - Rate limiting (per tenant)                                                           │
│  - Protocol normalization                                                               │
│  - Routing to ingestion services                                                        │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         EVENT INGESTION SERVICE (ECS/Go)                                │
│                               (200K events/sec)                                         │
│                                                                                         │
│  - Parse and normalize events (CEF, JSON, Syslog)                                      │
│  - Enrich with metadata (device info, geo-location, threat intel)                      │
│  - Validate and deduplicate                                                             │
│  - Batch and compress                                                                   │
│  - Push to stream                                                                       │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         MESSAGE STREAM (Kinesis / Kafka)                                │
│                                                                                         │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐          │
│  │   Endpoint    │  │   Network     │  │   Identity    │  │   Raw Events  │          │
│  │   Events      │  │   Events      │  │   Events      │  │   (Archive)   │          │
│  │   Stream      │  │   Stream      │  │   Stream      │  │   Stream      │          │
│  └───────────────┘  └───────────────┘  └───────────────┘  └───────────────┘          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                            │
          ┌─────────────────┴─────────────────────────────────────┐
          │                                                       │
          ▼                                                       ▼
┌──────────────────────────────┐                  ┌──────────────────────────────┐
│   DETECTION ENGINE (ECS)     │                  │   STORAGE PIPELINE (Lambda)  │
│                              │                  │                              │
│  ┌────────────────────────┐  │                  │  - Batch events              │
│  │ Signature Detection    │  │                  │  - Compress (Zstd)           │
│  │ (YARA, Sigma)          │  │                  │  - Write to OpenSearch       │
│  └────────────────────────┘  │                  │  - Archive to S3             │
│                              │                  └──────────────────────────────┘
│  ┌────────────────────────┐  │                              │
│  │ Behavior Detection     │  │                              ▼
│  │ (Rules engine)         │  │                  ┌──────────────────────────────┐
│  └────────────────────────┘  │                  │  OpenSearch Cluster          │
│                              │                  │                              │
│  ┌────────────────────────┐  │                  │  - Hot data (30 days)        │
│  │ ML Models              │  │                  │  - Fast search               │
│  │ (Anomaly detection)    │  │                  │  - Aggregations              │
│  └────────────────────────┘  │                  │  - Dashboards                │
│                              │                  └──────────────────────────────┘
│  ┌────────────────────────┐  │                              │
│  │ Threat Intel Matching  │  │                              ▼
│  │ (IOC feeds)            │  │                  ┌──────────────────────────────┐
│  └────────────────────────┘  │                  │         S3 (Archive)         │
│                              │                  │                              │
└──────────────┬───────────────┘                  │  - Warm: 1 year              │
               │                                  │  - Cold: 7 years             │
               │ Detections                       │  - Lifecycle policies        │
               ▼                                  └──────────────────────────────┘
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            ALERT MANAGEMENT SERVICE (ECS)                               │
│                                                                                         │
│  - Alert creation                                                                       │
│  - Deduplication (group similar alerts)                                                 │
│  - Correlation (link related alerts)                                                    │
│  - Enrichment (context, asset info, user info)                                          │
│  - Severity scoring                                                                     │
│  - Routing (auto-remediate vs escalate)                                                 │
└─────────────────────────────────────────────────────────────────────────────────────────┘
               │
               ├──────────────────────┬──────────────────────┐
               │                      │                      │
               ▼ (70%)                ▼ (30%)                ▼
┌──────────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│ AUTOMATED RESPONSE       │  │   SOC ANALYST        │  │  NOTIFICATION        │
│ SERVICE (Lambda/ECS)     │  │   QUEUE              │  │  SERVICE (SNS)       │
│                          │  │                      │  │                      │
│ - Isolate endpoint       │  │ - Prioritized queue  │  │ - Email              │
│ - Block IP/domain        │  │ - Investigation UI   │  │ - SMS                │
│ - Kill process           │  │ - Playbooks          │  │ - Webhook            │
│ - Quarantine file        │  │ - Case management    │  │ - Slack/Teams        │
│ - Reset credentials      │  │                      │  │ - Ticketing          │
│ - Collect forensics      │  │                      │  │                      │
└──────────────────────────┘  └──────────────────────┘  └──────────────────────┘
               │
               ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                          COMMAND & CONTROL SERVICE (ECS)                                │
│                                                                                         │
│  - Send commands to agents (via RMM platform integration)                              │
│  - Track action status                                                                  │
│  - Rollback if needed                                                                   │
└─────────────────────────────────────────────────────────────────────────────────────────┘
               │
               ▼
        (Back to agents)

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         ML & THREAT INTELLIGENCE LAYER                                  │
│                                                                                         │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐         │
│  │  ML Training         │  │  Threat Intel Feeds  │  │  Behavior Baselines  │         │
│  │  Pipeline            │  │                      │  │                      │         │
│  │                      │  │  - MISP              │  │  - Per-tenant        │         │
│  │  - Feature store     │  │  - AlienVault OTX    │  │  - User behavior     │         │
│  │  - Model training    │  │  - VirusTotal        │  │  - Entity behavior   │         │
│  │  - Model deployment  │  │  - Custom feeds      │  │  - Network patterns  │         │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────────┘         │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                             │
├──────────────────────┬──────────────────────┬──────────────────────┬───────────────────┤
│  ALERT DB            │  DETECTION RULES DB  │   THREAT INTEL DB    │   CASE MGT DB     │
│  (DocumentDB)        │  (DocumentDB)        │   (DocumentDB)       │  (DocumentDB)     │
│                      │                      │                      │                   │
│ - Active alerts      │ - Signatures         │ - IOCs               │ - Investigations  │
│ - Alert history      │ - YARA rules         │ - IP reputation      │ - Notes           │
│ - Correlation data   │ - Sigma rules        │ - Domain reputation  │ - Timeline        │
│ - Response actions   │ - ML models          │ - File hashes        │ - Evidence        │
└──────────────────────┴──────────────────────┴──────────────────────┴───────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            CACHE LAYER (Redis / ElastiCache)                            │
│                                                                                         │
│  - Active alert state                                                                   │
│  - Threat intelligence cache                                                            │
│  - User session data                                                                    │
│  - Rate limiting counters                                                               │
│  - Deduplication tracking                                                               │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              WEB API & DASHBOARD (GraphQL)                              │
│                                                                                         │
│  - Real-time alert dashboard                                                            │
│  - Threat hunting interface                                                             │
│  - Investigation workbench                                                              │
│  - Compliance reports                                                                   │
│  - Executive dashboards                                                                 │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **Event Ingestion Service**: High-throughput event collection
2. **Detection Engine**: Multi-layered threat detection
3. **Alert Management**: Deduplication, correlation, enrichment
4. **Automated Response**: 70% auto-remediation
5. **SOC Analyst Queue**: 30% requiring human review
6. **Storage Pipeline**: OpenSearch + S3 archive
7. **ML Training Pipeline**: Continuous model improvement
8. **Threat Intelligence**: External feed integration

---

## Request Flows

### Event Ingestion and Detection Flow

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  Agent   │  │   ALB    │  │Ingestion │  │ Kinesis  │  │Detection │  │  Alert   │
│  (EDR)   │  │          │  │ Service  │  │          │  │ Engine   │  │  Mgmt    │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │             │
     │ Event:      │             │             │             │             │
     │ New process │             │             │             │             │
     │ created:    │             │             │             │             │
     │ powershell  │             │             │             │             │
     │ -enc ...    │             │             │             │             │
     │             │             │             │             │             │
     │ POST /events│             │             │             │             │
     │ { type: "process_start",  │             │             │             │
     │   process: "powershell",  │             │             │             │
     │   cmdline: "-enc ...",    │             │             │             │
     │   user: "john",           │             │             │             │
     │   device_id: "dev_123",   │             │             │             │
     │   timestamp: ... }        │             │             │             │
     │────────────>│────────────>│             │             │             │
     │             │             │ Parse & normalize         │             │
     │             │             │             │             │             │
     │             │             │ Enrich:     │             │             │
     │             │             │ - Device info (OS, IP)    │             │
     │             │             │ - User info (role, dept)  │             │
     │             │             │ - Geo-location            │             │
     │             │             │ - Historical context      │             │
     │             │             │             │             │             │
     │<────────────│<────────────│             │             │             │
     │ 202 Accepted│             │             │             │             │
     │ (fast ack)  │             │             │             │             │
     │             │             │             │             │             │
     │             │             │ Publish to stream         │             │
     │             │             │────────────>│             │             │
     │             │             │             │             │             │
     │             │             │             │ Consumer pulls            │
     │             │             │             │────────────>│             │
     │             │             │             │             │             │
     │             │             │             │             │ Detection checks:
     │             │             │             │             │             │
     │             │             │             │             │ 1. Signature match
     │             │             │             │             │    (YARA rule: 
     │             │             │             │             │     encoded PS = suspicious)
     │             │             │             │             │             │
     │             │             │             │             │ 2. Behavior analysis
     │             │             │             │             │    (PowerShell spawn 
     │             │             │             │             │     from Outlook = bad)
     │             │             │             │             │             │
     │             │             │             │             │ 3. Threat intel
     │             │             │             │             │    (cmdline hash matches
     │             │             │             │             │     known malware)
     │             │             │             │             │             │
     │             │             │             │             │ 4. ML model
     │             │             │             │             │    (anomaly score: 0.95)
     │             │             │             │             │             │
     │             │             │             │             │ → DETECTED: Malicious
     │             │             │             │             │             │
     │             │             │             │             │ Create alert
     │             │             │             │             │────────────>│
     │             │             │             │             │             │
     │             │             │             │             │             │ Enrich alert:
     │             │             │             │             │             │ - MITRE: T1059.001
     │             │             │             │             │             │ - Severity: Critical
     │             │             │             │             │             │ - Context: User had
     │             │             │             │             │             │   phishing email
     │             │             │             │             │             │   5 min ago
     │             │             │             │             │             │
     │             │             │             │             │             │ Decision:
     │             │             │             │             │             │ Confidence=98%
     │             │             │             │             │             │ → Auto-remediate
```

### Automated Response Flow

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  Alert   │  │Auto-     │  │ Command  │  │   RMM    │  │  Agent   │  │Notif Svc │
│  Mgmt    │  │Response  │  │  & Ctrl  │  │Integration  │  (EDR)   │  │          │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │             │
     │ Alert:      │             │             │             │             │
     │ Malicious   │             │             │             │             │
     │ PowerShell  │             │             │             │             │
     │ (confidence │             │             │             │             │
     │  98%)       │             │             │             │             │
     │────────────>│             │             │             │             │
     │             │ Select response playbook  │             │             │
     │             │ "Malicious Process"       │             │             │
     │             │             │             │             │             │
     │             │ Actions:    │             │             │             │
     │             │ 1. Kill process           │             │             │
     │             │ 2. Isolate endpoint       │             │             │
     │             │ 3. Collect forensics      │             │             │
     │             │             │             │             │             │
     │             │ Execute actions           │             │             │
     │             │────────────>│             │             │             │
     │             │             │             │             │             │
     │             │             │ Send kill command         │             │
     │             │             │───────────>│             │             │
     │             │             │             │────────────>│             │
     │             │             │             │             │             │
     │             │             │             │             │ Terminate   │
     │             │             │             │             │ process PID │
     │             │             │             │             │ 4567        │
     │             │             │             │             │             │
     │             │             │             │<────────────│             │
     │             │             │<───────────│ ACK: killed │             │
     │             │             │             │             │             │
     │             │             │ Send isolate command      │             │
     │             │             │───────────>│────────────>│             │
     │             │             │             │             │             │
     │             │             │             │             │ Block all   │
     │             │             │             │             │ network     │
     │             │             │             │             │ (except C&C)│
     │             │             │             │             │             │
     │             │             │             │<────────────│             │
     │             │             │<───────────│ ACK: isolated              │
     │             │             │             │             │             │
     │             │             │ Collect forensics         │             │
     │             │             │───────────>│────────────>│             │
     │             │             │             │             │             │
     │             │             │             │             │ Dump memory │
     │             │             │             │             │ Collect logs│
     │             │             │             │             │ Screenshot  │
     │             │             │             │             │             │
     │             │             │<───────────│<────────────│             │
     │             │             │ Forensic data             │             │
     │             │             │             │             │             │
     │             │<────────────│             │             │             │
     │             │ Response complete         │             │             │
     │             │             │             │             │             │
     │             │ Update alert status       │             │             │
     │             │ "Contained"               │             │             │
     │             │             │             │             │             │
     │             │ Send notification         │             │             │
     │             │─────────────────────────────────────────────────────>│
     │             │             │             │             │             │
     │             │             │             │             │             │ Email/SMS
     │             │             │             │             │             │ to MSP:
     │             │             │             │             │             │ "Threat
     │             │             │             │             │             │  contained
     │             │             │             │             │             │  on dev_123"
```

### SOC Analyst Investigation Flow

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  Alert   │  │   SOC    │  │   Web    │  │OpenSearch│  │Forensics │
│  Queue   │  │ Analyst  │  │   API    │  │          │  │  Store   │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │
     │ Alert       │             │             │             │
     │ requiring   │             │             │             │
     │ review      │             │             │             │
     │ (low conf)  │             │             │             │
     │────────────>│             │             │             │
     │ "Unusual    │             │             │             │
     │  login from │             │             │             │
     │  China"     │             │             │             │
     │             │ View alert in dashboard   │             │
     │             │────────────>│             │             │
     │             │             │             │             │
     │             │<────────────│             │             │
     │             │ Alert details             │             │
     │             │             │             │             │
     │             │ Investigate:│             │             │
     │             │ "Show all logins for      │             │
     │             │  user in last 24 hours"   │             │
     │             │────────────>│             │             │
     │             │             │ Query       │             │
     │             │             │────────────>│             │
     │             │             │             │             │
     │             │             │<────────────│             │
     │             │<────────────│ [15 login events]         │
     │             │             │             │             │
     │             │ Analysis:   │             │             │
     │             │ - User in USA yesterday   │             │
     │             │ - Sudden login from China │             │
     │             │ - Impossible travel       │             │             │
     │             │             │             │             │
     │             │ Check if VPN/Proxy        │             │
     │             │────────────>│             │             │
     │             │             │             │             │
     │             │<────────────│             │             │
     │             │ No VPN usage recorded     │             │
     │             │             │             │             │
     │             │ Retrieve forensics        │             │
     │             │─────────────────────────────────────────>│
     │             │             │             │             │
     │             │<─────────────────────────────────────────│
     │             │ Session logs, keystroke timing, etc.    │
     │             │             │             │             │
     │             │ Conclusion: │             │             │
     │             │ Compromised credentials   │             │
     │             │             │             │             │
     │             │ Take action:│             │             │
     │             │ 1. Reset password         │             │
     │             │ 2. Revoke sessions        │             │
     │             │ 3. Notify user            │             │
     │             │────────────>│             │             │
     │             │             │ Execute     │             │
     │             │             │             │             │
     │             │ Close alert │             │             │
     │             │────────────>│             │             │
     │<────────────│             │             │             │
     │ Alert closed│             │             │             │
```

---

## Detailed Component Design

### 1. Detection Engine

```go
package detection

type DetectionEngine struct {
    SignatureDetector  *SignatureDetector
    BehaviorDetector   *BehaviorDetector
    MLDetector         *MLDetector
    ThreatIntel        *ThreatIntelligence
    AlertManager       *AlertManager
}

type Event struct {
    ID          string                 `json:"id"`
    Timestamp   time.Time              `json:"timestamp"`
    Type        string                 `json:"type"`  // process_start, file_write, network_conn, etc.
    DeviceID    string                 `json:"device_id"`
    TenantID    string                 `json:"tenant_id"`
    UserID      string                 `json:"user_id"`
    Attributes  map[string]interface{} `json:"attributes"`
    Enrichment  *Enrichment            `json:"enrichment"`
}

type Enrichment struct {
    DeviceInfo  *DeviceInfo
    UserInfo    *UserInfo
    GeoLocation *GeoLocation
    Context     *HistoricalContext
}

type Detection struct {
    EventID     string
    DetectorType string  // signature, behavior, ml, threat_intel
    Rule        string
    Severity    string  // critical, high, medium, low
    Confidence  float64 // 0-1
    MITRE       []string  // ATT&CK technique IDs
    Description string
    Evidence    map[string]interface{}
}

// Main detection pipeline
func (d *DetectionEngine) ProcessEvent(event *Event) error {
    // Run all detectors in parallel
    detectionsChan := make(chan *Detection, 10)
    
    var wg sync.WaitGroup
    wg.Add(4)
    
    // 1. Signature-based detection
    go func() {
        defer wg.Done()
        if det := d.SignatureDetector.Check(event); det != nil {
            detectionsChan <- det
        }
    }()
    
    // 2. Behavior-based detection
    go func() {
        defer wg.Done()
        if det := d.BehaviorDetector.Check(event); det != nil {
            detectionsChan <- det
        }
    }()
    
    // 3. ML-based detection
    go func() {
        defer wg.Done()
        if det := d.MLDetector.Check(event); det != nil {
            detectionsChan <- det
        }
    }()
    
    // 4. Threat intelligence matching
    go func() {
        defer wg.Done()
        if det := d.ThreatIntel.Check(event); det != nil {
            detectionsChan <- det
        }
    }()
    
    // Close channel when all detectors complete
    go func() {
        wg.Wait()
        close(detectionsChan)
    }()
    
    // Collect all detections
    detections := []*Detection{}
    for det := range detectionsChan {
        detections = append(detections, det)
    }
    
    // If any detection found, create alert
    if len(detections) > 0 {
        alert := d.createAlert(event, detections)
        d.AlertManager.HandleAlert(alert)
    }
    
    return nil
}

// Signature-based detection (YARA, Sigma rules)
type SignatureDetector struct {
    YaraRules  *yara.Rules
    SigmaRules []*SigmaRule
}

func (s *SignatureDetector) Check(event *Event) *Detection {
    // Check YARA rules (for file events)
    if event.Type == "file_write" || event.Type == "file_read" {
        filePath := event.Attributes["file_path"].(string)
        
        // Scan file with YARA
        matches, err := s.YaraRules.ScanFile(filePath, 0, 0)
        if err == nil && len(matches) > 0 {
            return &Detection{
                EventID:      event.ID,
                DetectorType: "signature",
                Rule:         matches[0].Rule,
                Severity:     "critical",
                Confidence:   1.0,
                Description:  fmt.Sprintf("YARA rule matched: %s", matches[0].Rule),
            }
        }
    }
    
    // Check Sigma rules (for all event types)
    for _, rule := range s.SigmaRules {
        if rule.Match(event) {
            return &Detection{
                EventID:      event.ID,
                DetectorType: "signature",
                Rule:         rule.ID,
                Severity:     rule.Severity,
                Confidence:   1.0,
                MITRE:        rule.MITRE,
                Description:  rule.Description,
            }
        }
    }
    
    return nil
}

// Behavior-based detection
type BehaviorDetector struct {
    Rules []*BehaviorRule
}

type BehaviorRule struct {
    ID          string
    Name        string
    Description string
    Severity    string
    MITRE       []string
    Conditions  []Condition
}

type Condition struct {
    Field    string
    Operator string
    Value    interface{}
}

func (b *BehaviorDetector) Check(event *Event) *Detection {
    for _, rule := range b.Rules {
        if b.evaluateRule(rule, event) {
            return &Detection{
                EventID:      event.ID,
                DetectorType: "behavior",
                Rule:         rule.ID,
                Severity:     rule.Severity,
                Confidence:   0.85,
                MITRE:        rule.MITRE,
                Description:  rule.Description,
            }
        }
    }
    return nil
}

func (b *BehaviorDetector) evaluateRule(rule *BehaviorRule, event *Event) bool {
    // Example rule: PowerShell spawned by Outlook
    // Conditions:
    //   - process_name = powershell.exe
    //   - parent_process = outlook.exe
    
    for _, cond := range rule.Conditions {
        if !b.evaluateCondition(cond, event) {
            return false  // All conditions must match
        }
    }
    return true
}

func (b *BehaviorDetector) evaluateCondition(cond Condition, event *Event) bool {
    value := event.Attributes[cond.Field]
    
    switch cond.Operator {
    case "equals":
        return value == cond.Value
    case "contains":
        if str, ok := value.(string); ok {
            return strings.Contains(str, cond.Value.(string))
        }
    case "regex":
        if str, ok := value.(string); ok {
            matched, _ := regexp.MatchString(cond.Value.(string), str)
            return matched
        }
    }
    
    return false
}

// ML-based anomaly detection
type MLDetector struct {
    Models map[string]*MLModel
}

type MLModel struct {
    Name       string
    ModelPath  string
    Threshold  float64
    Predictor  *Predictor
}

func (m *MLDetector) Check(event *Event) *Detection {
    // Select appropriate model based on event type
    model, ok := m.Models[event.Type]
    if !ok {
        return nil  // No model for this event type
    }
    
    // Extract features
    features := m.extractFeatures(event)
    
    // Predict
    anomalyScore, err := model.Predictor.Predict(features)
    if err != nil {
        log.Error("ML prediction failed", err)
        return nil
    }
    
    // Check if anomalous
    if anomalyScore > model.Threshold {
        return &Detection{
            EventID:      event.ID,
            DetectorType: "ml",
            Rule:         model.Name,
            Severity:     m.scoreSeverity(anomalyScore),
            Confidence:   anomalyScore,
            Description:  fmt.Sprintf("Anomalous behavior detected (score: %.2f)", anomalyScore),
            Evidence: map[string]interface{}{
                "anomaly_score": anomalyScore,
                "features":      features,
            },
        }
    }
    
    return nil
}

func (m *MLDetector) extractFeatures(event *Event) []float64 {
    // Extract numerical features for ML model
    features := []float64{}
    
    // Example features:
    // - Time of day (normalized 0-1)
    features = append(features, float64(event.Timestamp.Hour())/24.0)
    
    // - Day of week (normalized 0-1)
    features = append(features, float64(event.Timestamp.Weekday())/7.0)
    
    // - Process path length
    if processPath, ok := event.Attributes["process_path"].(string); ok {
        features = append(features, float64(len(processPath))/100.0)
    }
    
    // - Command line entropy
    if cmdline, ok := event.Attributes["cmdline"].(string); ok {
        entropy := calculateEntropy(cmdline)
        features = append(features, entropy)
    }
    
    // - Network: remote IP reputation
    if remoteIP, ok := event.Attributes["remote_ip"].(string); ok {
        reputation := m.getIPReputation(remoteIP)
        features = append(features, reputation)
    }
    
    // ... more features
    
    return features
}

func calculateEntropy(s string) float64 {
    if len(s) == 0 {
        return 0
    }
    
    freq := make(map[rune]int)
    for _, c := range s {
        freq[c]++
    }
    
    entropy := 0.0
    length := float64(len(s))
    
    for _, count := range freq {
        p := float64(count) / length
        entropy -= p * math.Log2(p)
    }
    
    return entropy
}

// Threat Intelligence matching
type ThreatIntelligence struct {
    IOCStore *IOCStore
    Cache    *cache.Cache
}

type IOC struct {
    Type       string  // ip, domain, file_hash, url
    Value      string
    Source     string
    Severity   string
    LastSeen   time.Time
}

func (t *ThreatIntelligence) Check(event *Event) *Detection {
    // Check file hashes
    if fileHash, ok := event.Attributes["file_hash"].(string); ok {
        if ioc := t.lookupIOC("file_hash", fileHash); ioc != nil {
            return &Detection{
                EventID:      event.ID,
                DetectorType: "threat_intel",
                Rule:         "ioc_match",
                Severity:     ioc.Severity,
                Confidence:   0.95,
                Description:  fmt.Sprintf("Known malicious file: %s (source: %s)", 
                                         fileHash, ioc.Source),
                Evidence: map[string]interface{}{
                    "ioc": ioc,
                },
            }
        }
    }
    
    // Check IPs
    if remoteIP, ok := event.Attributes["remote_ip"].(string); ok {
        if ioc := t.lookupIOC("ip", remoteIP); ioc != nil {
            return &Detection{
                EventID:      event.ID,
                DetectorType: "threat_intel",
                Rule:         "ioc_match",
                Severity:     ioc.Severity,
                Confidence:   0.90,
                Description:  fmt.Sprintf("Connection to malicious IP: %s", remoteIP),
                Evidence: map[string]interface{}{
                    "ioc": ioc,
                },
            }
        }
    }
    
    // Check domains
    if domain, ok := event.Attributes["domain"].(string); ok {
        if ioc := t.lookupIOC("domain", domain); ioc != nil {
            return &Detection{
                EventID:      event.ID,
                DetectorType: "threat_intel",
                Rule:         "ioc_match",
                Severity:     ioc.Severity,
                Confidence:   0.90,
                Description:  fmt.Sprintf("Connection to malicious domain: %s", domain),
                Evidence: map[string]interface{}{
                    "ioc": ioc,
                },
            }
        }
    }
    
    return nil
}

func (t *ThreatIntelligence) lookupIOC(iocType string, value string) *IOC {
    // Check cache first
    cacheKey := fmt.Sprintf("ioc:%s:%s", iocType, value)
    if cached, found := t.Cache.Get(cacheKey); found {
        if ioc, ok := cached.(*IOC); ok {
            return ioc
        }
    }
    
    // Lookup in database
    ioc, err := t.IOCStore.Get(iocType, value)
    if err != nil {
        return nil
    }
    
    // Cache result (including negative results)
    t.Cache.Set(cacheKey, ioc, 5*time.Minute)
    
    return ioc
}
```

### 2. Alert Management & Auto-Response

```go
package alerting

type AlertManager struct {
    Deduplicator    *Deduplicator
    Correlator      *Correlator
    Enricher        *Enricher
    AutoResponder   *AutoResponder
    NotificationSvc *NotificationService
    AlertRepo       AlertRepository
}

type Alert struct {
    ID            string
    TenantID      string
    DeviceID      string
    UserID        string
    Severity      string
    Title         string
    Description   string
    Detections    []*Detection
    CorrelatedWith []string  // Other alert IDs
    Status        string  // new, investigating, contained, resolved, false_positive
    AssignedTo    string
    CreatedAt     time.Time
    UpdatedAt     time.Time
    Evidence      map[string]interface{}
}

func (a *AlertManager) HandleAlert(alert *Alert) error {
    // 1. Deduplication: Check if similar alert exists
    if existingAlert := a.Deduplicator.FindDuplicate(alert); existingAlert != nil {
        // Merge with existing alert
        existingAlert.Detections = append(existingAlert.Detections, alert.Detections...)
        existingAlert.UpdatedAt = time.Now()
        return a.AlertRepo.Update(existingAlert)
    }
    
    // 2. Correlation: Find related alerts
    relatedAlerts := a.Correlator.FindRelated(alert)
    if len(relatedAlerts) > 0 {
        alert.CorrelatedWith = make([]string, len(relatedAlerts))
        for i, related := range relatedAlerts {
            alert.CorrelatedWith[i] = related.ID
        }
    }
    
    // 3. Enrichment: Add context
    a.Enricher.Enrich(alert)
    
    // 4. Calculate final confidence
    confidence := a.calculateConfidence(alert)
    
    // 5. Save alert
    if err := a.AlertRepo.Save(alert); err != nil {
        return err
    }
    
    // 6. Decide: Auto-remediate or escalate?
    if a.shouldAutoRemediate(alert, confidence) {
        // Auto-remediate (70% of alerts)
        go a.AutoResponder.Respond(alert)
    } else {
        // Escalate to SOC analysts (30% of alerts)
        go a.escalateToSOC(alert)
    }
    
    // 7. Send notification
    go a.NotificationSvc.SendAlert(alert)
    
    return nil
}

func (a *AlertManager) shouldAutoRemediate(alert *Alert, confidence float64) bool {
    // Auto-remediate if:
    // 1. High confidence (>95%)
    // 2. Known attack pattern
    // 3. Low-risk response action
    
    if confidence < 0.95 {
        return false
    }
    
    // Check if response action is low-risk
    responseRisk := a.AutoResponder.GetResponseRisk(alert)
    if responseRisk == "high" {
        return false  // Require human approval
    }
    
    return true
}

func (a *AlertManager) calculateConfidence(alert *Alert) float64 {
    // Weighted average of detection confidences
    totalConfidence := 0.0
    weights := map[string]float64{
        "signature":    1.0,
        "threat_intel": 0.95,
        "ml":           0.7,
        "behavior":     0.85,
    }
    
    totalWeight := 0.0
    
    for _, det := range alert.Detections {
        weight := weights[det.DetectorType]
        totalConfidence += det.Confidence * weight
        totalWeight += weight
    }
    
    if totalWeight == 0 {
        return 0
    }
    
    return totalConfidence / totalWeight
}

// Automated Response Service
type AutoResponder struct {
    CommandService *CommandService
    Playbooks      map[string]*ResponsePlaybook
}

type ResponsePlaybook struct {
    ID          string
    Name        string
    Trigger     TriggerCondition
    Actions     []ResponseAction
    RiskLevel   string
}

type ResponseAction struct {
    Type        string  // isolate, kill_process, block_ip, etc.
    Parameters  map[string]interface{}
    Timeout     time.Duration
    Rollback    *RollbackAction
}

func (a *AutoResponder) Respond(alert *Alert) error {
    log.Info("Auto-remediating alert", alert.ID)
    
    // Select appropriate playbook
    playbook := a.selectPlaybook(alert)
    if playbook == nil {
        log.Warn("No playbook found for alert", alert.ID)
        return errors.New("no playbook found")
    }
    
    log.Info("Executing playbook", playbook.Name)
    
    // Execute actions sequentially
    executedActions := []string{}
    
    for _, action := range playbook.Actions {
        if err := a.executeAction(alert, action); err != nil {
            log.Error("Action failed", action.Type, err)
            
            // Rollback previous actions
            a.rollback(executedActions, alert)
            
            // Update alert status
            alert.Status = "auto_remediation_failed"
            alert.Evidence["error"] = err.Error()
            
            return err
        }
        
        executedActions = append(executedActions, action.Type)
    }
    
    // Update alert status
    alert.Status = "contained"
    alert.Evidence["remediation"] = map[string]interface{}{
        "playbook": playbook.Name,
        "actions":  executedActions,
        "time":     time.Now(),
    }
    
    log.Info("Auto-remediation completed", alert.ID)
    
    return nil
}

func (a *AutoResponder) executeAction(alert *Alert, action ResponseAction) error {
    switch action.Type {
    case "kill_process":
        pid := alert.Evidence["process_id"].(int)
        return a.CommandService.KillProcess(alert.DeviceID, pid)
        
    case "isolate_endpoint":
        return a.CommandService.IsolateEndpoint(alert.DeviceID)
        
    case "block_ip":
        ip := action.Parameters["ip"].(string)
        return a.CommandService.BlockIP(alert.DeviceID, ip)
        
    case "quarantine_file":
        filePath := action.Parameters["file_path"].(string)
        return a.CommandService.QuarantineFile(alert.DeviceID, filePath)
        
    case "reset_credentials":
        userID := alert.UserID
        return a.CommandService.ResetCredentials(userID)
        
    case "collect_forensics":
        return a.CommandService.CollectForensics(alert.DeviceID)
        
    default:
        return fmt.Errorf("unknown action type: %s", action.Type)
    }
}

func (a *AutoResponder) GetResponseRisk(alert *Alert) string {
    // Determine risk level of automated response
    // High risk: Actions that could impact business operations
    // Low risk: Actions that only affect single endpoint
    
    if alert.DeviceID == "critical_server_123" {
        return "high"  // Don't auto-remediate on critical servers
    }
    
    // Check if user is admin/executive
    if alert.Evidence["user_role"] == "executive" {
        return "high"  // Require human approval for VIPs
    }
    
    return "low"
}
```

---

## Trade-offs and Tech Choices

### 1. Event Storage: OpenSearch vs Splunk vs ELK

| Solution | Pros | Cons | Decision |
|----------|------|------|----------|
| **OpenSearch** | Open-source, Fast search, Cost-effective | Less mature than Splunk | ✅ **Chosen** |
| **Splunk** | Best-in-class, Rich features | Expensive, Vendor lock-in | ❌ Too expensive at scale |
| **ELK Stack** | Popular, Flexible | Complex operations | ❌ OpenSearch is fork with improvements |

**Decision**: **OpenSearch**
- Cost-effective at 500B events/month scale
- Fast full-text search
- Real-time indexing
- Good aggregation for dashboards

### 2. Stream Processing: Kafka vs Kinesis

| Solution | Pros | Cons | Decision |
|----------|------|------|----------|
| **Kafka** | High throughput, Replay, Self-hosted | Complex operations, Expensive | ❌ Operations overhead |
| **Kinesis** | AWS-managed, Simple, Auto-scaling | AWS lock-in | ✅ **Chosen** |

**Decision**: **AWS Kinesis**
- Managed service (less ops)
- Auto-scales to 500K events/sec
- Replay capability for reprocessing
- S3 integration for archiving

### 3. Auto-Remediation: Conservative vs Aggressive

| Approach | False Positive Impact | Coverage | Decision |
|----------|----------------------|----------|----------|
| **Conservative (50%)** | Low business disruption | Miss some threats | ❌ Not enough coverage |
| **Balanced (70%)** | Acceptable disruption | Good coverage | ✅ **Chosen (N-able's claim)** |
| **Aggressive (90%)** | High disruption risk | Maximum coverage | ❌ Too risky |

**Decision**: **70% auto-remediation with high confidence threshold**
```
Auto-remediate when:
- Confidence > 95%
- Known attack pattern
- Low-risk action
- Non-critical asset

Escalate to SOC when:
- Confidence < 95%
- High-risk action needed
- Critical asset affected
- Requires investigation
```

---

## Summary

This security event processing system achieves:

- **500B+ events/month**: Kinesis streaming + OpenSearch
- **70% auto-remediation**: ML-powered confidence scoring
- **< 10s detection**: Real-time pipeline
- **99.99% uptime**: AWS managed services
- **Multi-layered detection**: Signatures, behavior, ML, threat intel

**Key Technologies**:
- Go (services)
- AWS Kinesis (streaming)
- OpenSearch (event storage & search)
- Redis (caching)
- DocumentDB (metadata)
- ML models (anomaly detection)
- YARA + Sigma (signatures)

This demonstrates enterprise-grade security event processing that N-able Adlumin MDR operates at scale, protecting millions of endpoints with AI-powered detection and automated response.
