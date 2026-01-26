# N-able Patch Management System - High Level Design

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

1. **Patch Discovery**
   - Detect available patches for Windows, Linux, macOS
   - Integration with vendor APIs (Microsoft, Red Hat, etc.)
   - Identify security vs feature patches
   - Track CVE mappings

2. **Patch Assessment**
   - Scan endpoints for missing patches
   - Report compliance status
   - Risk scoring based on severity and exposure
   - Dependency detection

3. **Patch Approval Workflow**
   - Test patches in sandbox/pilot environments
   - Manual approval by MSP
   - Auto-approve based on rules (e.g., Critical security patches)
   - Rollback capability

4. **Patch Deployment**
   - Scheduled deployments (maintenance windows)
   - Staged rollouts (pilot → production)
   - Download optimization (caching, P2P)
   - Pre/post deployment scripts

5. **Monitoring & Reporting**
   - Track deployment status (pending, installing, success, failed)
   - Compliance reporting (% patched)
   - Failure analysis and remediation
   - Audit trail

6. **Multi-Tenancy**
   - Per-tenant patch policies
   - Custom maintenance windows
   - Tenant-specific approvals
   - Isolated patch repositories

### Non-Functional Requirements

1. **Scale**: 
   - 9M managed endpoints
   - 25K MSP partners
   - 10K new patches per month across all OS types
   - 99% patch success rate

2. **Availability**: 99.99% uptime for patch management service

3. **Performance**:
   - Patch scan: < 5 minutes per device
   - Deployment scheduling: < 10 seconds
   - Status reporting: Real-time

4. **Bandwidth Optimization**:
   - Minimize patch downloads (deduplication, caching)
   - P2P distribution within sites
   - Differential updates

5. **Safety**:
   - Rollback on failure
   - Snapshot before patching
   - Gradual rollout to detect issues
   - Zero critical system outages

6. **Compliance**:
   - Audit logging for all patch activities
   - SOC2, HIPAA compliance support
   - Retention: 7 years

---

## Capacity Estimation

### Traffic Estimates

```
Patch Scanning:
- Total endpoints: 9M
- Scan frequency: Once per day
- Scans per second: 9M / 86400 = 104 scans/sec
- Peak (business hours - 8 hours): 9M / (8*3600) = 312 scans/sec

Patch Metadata:
- New patches per month: 10K
- Patches per scan: 50 available patches average
- Patch metadata lookups: 104 scans/sec × 50 = 5,200 lookups/sec

Patch Downloads:
- Devices needing patches: 50% (4.5M)
- Patches per device: 10 patches average
- Total downloads per month: 45M patch installations
- Downloads per second: 45M / (30*86400) ≈ 17 downloads/sec
- Average patch size: 50 MB
- Download bandwidth: 17 × 50 MB = 850 MB/sec = 6.8 Gbps

Deployment Status Updates:
- Active deployments: 100K concurrent
- Status updates per deployment: 10 (downloading, installing, rebooting, etc.)
- Updates per second: 100K × 10 / 3600 = 278 updates/sec
```

### Storage Estimates

```
Patch Repository:
- Total unique patches (all time): 100K
- Average patch size: 50 MB
- Total storage: 100K × 50 MB = 5 PB
- With caching and deduplication: ~500 TB

Patch Metadata:
- Per patch: 10 KB (description, CVEs, dependencies)
- Total: 100K × 10 KB = 1 GB

Scan Results:
- Per scan: 5 KB (list of missing patches)
- Per device per day: 5 KB
- 30 day retention: 9M × 5 KB × 30 = 1.35 TB

Deployment History:
- Per deployment: 2 KB (device, patch, status, timestamp)
- Per day: 45M / 30 = 1.5M deployments
- Per day storage: 1.5M × 2 KB = 3 GB
- 7 year retention: 3 GB × 365 × 7 = 7.7 TB

Total Storage: ~500 TB (patches) + 1 GB (metadata) + 1.35 TB (scans) + 7.7 TB (history) ≈ 510 TB
```

### Bandwidth Estimates

```
Inbound:
- Scan results: 104 scans/sec × 5 KB = 520 KB/sec
- Status updates: 278 updates/sec × 1 KB = 278 KB/sec
- Total inbound: ~1 MB/sec

Outbound:
- Patch downloads: 17 downloads/sec × 50 MB = 850 MB/sec
- Patch metadata: 5,200 lookups/sec × 10 KB = 52 MB/sec
- Total outbound: ~900 MB/sec = 7.2 Gbps

Note: With CDN and caching, actual outbound reduced by 80% → 1.4 Gbps
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              VENDOR PATCH SOURCES                                        │
│                                                                                         │
│   ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐             │
│   │  Microsoft   │  │   Red Hat    │  │   Ubuntu     │  │    macOS     │             │
│   │   Update     │  │    RHEL      │  │  Canonical   │  │    Apple     │             │
│   │   Catalog    │  │   (Yum/DNF)  │  │     (APT)    │  │   Updates    │             │
│   └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘             │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                           PATCH DISCOVERY SERVICE (Scheduled Jobs)                      │
│                                                                                         │
│  - Poll vendor APIs for new patches                                                     │
│  - Extract metadata (version, severity, CVEs)                                           │
│  - Download patches to repository                                                       │
│  - Classify: Security vs Feature                                                        │
│  - Calculate risk scores                                                                │
│                                                                                         │
│  Frequency: Hourly for security, Daily for feature patches                             │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            PATCH REPOSITORY (S3 + CloudFront CDN)                       │
│                                                                                         │
│  ┌─────────────────────────────────────────────────────────────────────────────────┐   │
│  │                               S3 Buckets                                         │   │
│  │                                                                                  │   │
│  │  /patches/windows/{patch_id}/package.msu                                        │   │
│  │  /patches/linux/rhel/{patch_id}/package.rpm                                     │   │
│  │  /patches/linux/ubuntu/{patch_id}/package.deb                                   │   │
│  │  /patches/macos/{patch_id}/package.pkg                                          │   │
│  │                                                                                  │   │
│  │  - Versioned                                                                     │   │
│  │  - Immutable                                                                     │   │
│  │  - Cached by CloudFront (edge locations)                                        │   │
│  └─────────────────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                               PATCH METADATA DB (DocumentDB)                            │
│                                                                                         │
│  patches: {                                                                             │
│    patch_id: "KB5012345",                                                               │
│    title: "Security Update for Windows",                                               │
│    os: "Windows",                                                                       │
│    os_version: "10, 11",                                                                │
│    severity: "Critical",                                                                │
│    type: "Security",                                                                    │
│    cves: ["CVE-2024-1234"],                                                             │
│    release_date: "2024-01-15",                                                          │
│    supersedes: ["KB5012000"],                                                           │
│    dependencies: ["KB5011999"],                                                         │
│    download_url: "s3://patches/windows/KB5012345/...",                                  │
│    size_bytes: 52428800,                                                                │
│    reboot_required: true                                                                │
│  }                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                    AGENTS (9M Endpoints)                                │
│                                                                                         │
│  ┌────────────────────────────────────────────────────────────────────────────────┐    │
│  │                        PATCH SCANNER MODULE                                     │    │
│  │                                                                                 │    │
│  │  - Query installed patches (Windows Update API, rpm, dpkg)                     │    │
│  │  - Compare with available patches                                              │    │
│  │  - Report missing patches                                                      │    │
│  │  - Schedule: Daily or on-demand                                                │    │
│  └────────────────────────────────────────────────────────────────────────────────┘    │
│                                                                                         │
│  ┌────────────────────────────────────────────────────────────────────────────────┐    │
│  │                        PATCH INSTALLER MODULE                                   │    │
│  │                                                                                 │    │
│  │  - Download patches from CDN                                                    │    │
│  │  - Verify checksums                                                             │    │
│  │  - Create snapshot/restore point                                               │    │
│  │  - Install patch                                                                │    │
│  │  - Reboot if required                                                           │    │
│  │  - Report status                                                                │    │
│  │  - Rollback on failure                                                          │    │
│  └────────────────────────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                       Scan Results       │      Deployment Status
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            API GATEWAY / LOAD BALANCER                                  │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                                          │
                    ┌─────────────────────┴─────────────────────┐
                    │                                           │
                    ▼                                           ▼
┌──────────────────────────────────┐        ┌──────────────────────────────────┐
│   PATCH ASSESSMENT SERVICE       │        │   PATCH DEPLOYMENT SERVICE       │
│          (ECS/Go)                │        │          (ECS/Go)                │
│                                  │        │                                  │
│  - Receive scan results          │        │  - Schedule deployments          │
│  - Calculate compliance          │        │  - Staged rollouts               │
│  - Risk scoring                  │        │  - Track deployment state        │
│  - Recommend patches             │        │  - Handle failures               │
│  - Send to Kafka                 │        │  - Rollback management           │
└──────────────────────────────────┘        └──────────────────────────────────┘
                    │                                           │
                    ▼                                           ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE QUEUE (Kafka + SQS)                                │
│                                                                                         │
│  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐  ┌───────────────┐          │
│  │  Scan Results │  │  Deployment   │  │  Deployment   │  │   Approval    │          │
│  │     Topic     │  │   Commands    │  │    Status     │  │    Workflow   │          │
│  │    (Kafka)    │  │     (SQS)     │  │    (Kafka)    │  │     (SQS)     │          │
│  └───────────────┘  └───────────────┘  └───────────────┘  └───────────────┘          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
         │                       │                       │                       │
         ▼                       ▼                       ▼                       ▼
┌─────────────┐       ┌─────────────┐       ┌─────────────┐       ┌─────────────┐
│ Compliance  │       │  Deployment │       │   Status    │       │  Approval   │
│  Analyzer   │       │ Orchestrator│       │  Tracker    │       │  Service    │
│  (Lambda)   │       │   (ECS)     │       │  (Lambda)   │       │  (ECS)      │
└─────────────┘       └─────────────┘       └─────────────┘       └─────────────┘
         │                       │                       │                       │
         └───────────────────────┴───────────────────────┴───────────────────────┘
                                          │
                                          ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                             │
├──────────────────────┬──────────────────────┬──────────────────────┬───────────────────┤
│  DEVICE PATCH STATE  │  DEPLOYMENT HISTORY  │   POLICY CONFIG DB   │   CACHE (Redis)   │
│    (DocumentDB)      │    (DocumentDB)      │    (DocumentDB)      │  (ElastiCache)    │
│                      │                      │                      │                   │
│ - Current patches    │ - Deployment logs    │ - Patch policies     │ - Pending jobs    │
│ - Missing patches    │ - Success/failure    │ - Maintenance        │ - Active deploys  │
│ - Compliance status  │ - Rollback history   │   windows            │ - Scan cache      │
│ - Last scan time     │ - Audit trail        │ - Approval rules     │                   │
└──────────────────────┴──────────────────────┴──────────────────────┴───────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            WEB API / DASHBOARD (GraphQL)                                │
│                                                                                         │
│  - View compliance reports                                                              │
│  - Approve patches                                                                      │
│  - Configure policies                                                                   │
│  - Schedule deployments                                                                 │
│  - Monitor deployment status                                                            │
│  - Rollback failed patches                                                              │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **Patch Discovery Service**: Polls vendor APIs for new patches
2. **Patch Repository**: S3 + CloudFront for global distribution
3. **Agent Scanner**: Scans endpoints for missing patches
4. **Assessment Service**: Analyzes compliance and risk
5. **Approval Workflow**: Manual/auto approval of patches
6. **Deployment Orchestrator**: Schedules and manages deployments
7. **Status Tracker**: Monitors deployment progress

---

## Request Flows

### Patch Discovery Flow

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
│  Scheduler   │  │   Discovery  │  │   Vendor     │  │   S3 Patch   │  │   Metadata   │
│   (Cron)     │  │   Service    │  │     API      │  │  Repository  │  │      DB      │
└──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘
       │                 │                 │                 │                 │
       │ Trigger hourly  │                 │                 │                 │
       │────────────────>│                 │                 │                 │
       │                 │                 │                 │                 │
       │                 │ GET /updates    │                 │                 │
       │                 │────────────────>│                 │                 │
       │                 │                 │                 │                 │
       │                 │<────────────────│                 │                 │
       │                 │ [ KB5012345,    │                 │                 │
       │                 │   KB5012346 ]   │                 │                 │
       │                 │                 │                 │                 │
       │                 │ Check if exists in DB            │                 │
       │                 │─────────────────────────────────────────────────────>│
       │                 │                 │                 │                 │
       │                 │<─────────────────────────────────────────────────────│
       │                 │ KB5012345: NEW  │                 │                 │
       │                 │                 │                 │                 │
       │                 │ Download patch  │                 │                 │
       │                 │────────────────>│                 │                 │
       │                 │                 │                 │                 │
       │                 │<────────────────│                 │                 │
       │                 │ (patch binary)  │                 │                 │
       │                 │                 │                 │                 │
       │                 │ Upload to S3                      │                 │
       │                 │───────────────────────────────────>│                 │
       │                 │                 │                 │                 │
       │                 │ Save metadata                     │                 │
       │                 │─────────────────────────────────────────────────────>│
       │                 │ { patch_id, title, severity,      │                 │
       │                 │   CVEs, download_url }            │                 │
       │                 │                 │                 │                 │
       │                 │ Notify MSPs of new critical patch │                 │
       │                 │ (if severity = Critical)          │                 │
```

### Patch Scanning Flow

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  Agent   │  │   ALB    │  │Assessment│  │   Kafka  │  │Compliance│  │   DB     │
│ (Device) │  │          │  │ Service  │  │          │  │ Analyzer │  │          │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │             │
     │ Scheduled   │             │             │             │             │
     │ scan (daily)│             │             │             │             │
     │             │             │             │             │             │
     │ Query installed patches locally         │             │             │
     │ (Windows: wmic qfe, Linux: rpm -qa)     │             │             │
     │             │             │             │             │             │
     │ POST /agent/patches/scan                │             │             │
     │ { device_id, installed: [               │             │             │
     │   {patch_id: "KB5012000", ...},         │             │             │
     │   {patch_id: "KB5011999", ...}          │             │             │
     │ ]}          │             │             │             │             │
     │────────────>│────────────>│             │             │             │
     │             │             │ Fetch available patches for OS           │
     │             │             │─────────────────────────────────────────>│
     │             │             │             │             │             │
     │             │             │<─────────────────────────────────────────│
     │             │             │ (all patches)│             │             │
     │             │             │             │             │             │
     │             │             │ Calculate missing patches                │
     │             │             │ (available - installed)                  │
     │             │             │             │             │             │
     │             │             │ Publish to Kafka         │             │
     │             │             │────────────>│             │             │
     │             │             │ { device_id, missing: [...] }            │
     │             │             │             │             │             │
     │<────────────│<────────────│             │             │             │
     │ 200 OK      │             │             │             │             │
     │ { missing_count: 5 }      │             │             │             │
     │             │             │             │             │             │
     │             │             │             │ Consumer pulls            │
     │             │             │             │────────────>│             │
     │             │             │             │             │             │
     │             │             │             │             │ Calculate compliance
     │             │             │             │             │ Update device state
     │             │             │             │             │────────────>│
     │             │             │             │             │             │
     │             │             │             │             │ Trigger alerts if
     │             │             │             │             │ compliance < threshold
```

### Patch Approval & Deployment Flow

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│MSP User  │  │  Web API │  │ Approval │  │Deployment│  │   SQS    │  │  Agent   │
│(Dashboard)  │          │  │ Service  │  │Orchestrtr│  │          │  │          │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │             │
     │ Review      │             │             │             │             │
     │ missing     │             │             │             │             │
     │ patches     │             │             │             │             │
     │             │             │             │             │             │
     │ POST /patches/approve     │             │             │             │
     │ { patch_ids: [...],       │             │             │             │
     │   device_group: "prod",   │             │             │             │
     │   schedule: "2024-01-20   │             │             │             │
     │              02:00 AM" }  │             │             │             │
     │────────────>│             │             │             │             │
     │             │ Validate    │             │             │             │
     │             │ Check permissions         │             │             │
     │             │             │             │             │             │
     │             │ Create approval record    │             │             │
     │             │────────────>│             │             │             │
     │             │             │             │             │             │
     │             │             │ Create deployment plan    │             │
     │             │             │────────────>│             │             │
     │             │             │             │             │             │
     │             │             │             │ Calculate staged rollout  │
     │             │             │             │ - Pilot: 1% devices       │
     │             │             │             │ - Stage 1: 10% (if pilot OK)
     │             │             │             │ - Stage 2: 50%            │
     │             │             │             │ - Stage 3: 100%           │
     │             │             │             │             │             │
     │<────────────│<────────────│<────────────│             │             │
     │ 202 Accepted             │             │             │             │
     │ { deployment_id,          │             │             │             │
     │   status: "scheduled" }   │             │             │             │
     │             │             │             │             │             │
     │             │             │             │ Wait for scheduled time   │
     │             │             │             │             │             │
     │             │             │             │ Pilot stage: Select 1% devices
     │             │             │             │             │             │
     │             │             │             │ For each device, push command
     │             │             │             │────────────>│             │
     │             │             │             │ { device_id, patch_id,    │
     │             │             │             │   action: "install" }     │
     │             │             │             │             │             │
     │             │             │             │             │ Agent polls │
     │             │             │             │             │<────────────│
     │             │             │             │             │             │
     │             │             │             │             │────────────>│
     │             │             │             │             │ Command     │
     │             │             │             │             │             │
     │             │             │             │             │             │ Download
     │             │             │             │             │             │ patch from
     │             │             │             │             │             │ CloudFront
     │             │             │             │             │             │
     │             │             │             │             │             │ Create
     │             │             │             │             │             │ snapshot
     │             │             │             │             │             │
     │             │             │             │             │             │ Install
     │             │             │             │             │             │ patch
     │             │             │             │             │             │
     │             │             │             │             │<────────────│
     │             │             │             │             │ Status: success
     │             │             │             │             │             │
     │             │             │             │<────────────│             │
     │             │             │             │ Pilot results: 99% success
     │             │             │             │             │             │
     │             │             │             │ Proceed to Stage 1 (10%)  │
```

### Rollback Flow

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│MSP User  │  │  Web API │  │Deployment│  │   SQS    │  │  Agent   │
│          │  │          │  │Orchestrtr│  │          │  │          │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │
     │ Notice      │             │             │             │
     │ issues after│             │             │             │
     │ deployment  │             │             │             │
     │             │             │             │             │
     │ POST /deployments/{id}/rollback        │             │
     │────────────>│             │             │             │
     │             │             │             │             │
     │             │ Cancel pending stages     │             │
     │             │────────────>│             │             │
     │             │             │             │             │
     │             │             │ For devices with patch installed:
     │             │             │ Push uninstall command   │             │
     │             │             │────────────>│             │
     │             │             │             │             │
     │             │             │             │────────────>│
     │             │             │             │             │
     │             │             │             │             │ Uninstall
     │             │             │             │             │ patch
     │             │             │             │             │
     │             │             │             │             │ Restore
     │             │             │             │             │ snapshot
     │             │             │             │             │ (if exists)
     │             │             │             │             │
     │             │             │             │<────────────│
     │             │             │             │ Rollback success
     │             │             │             │             │
     │             │             │<────────────│             │
     │             │             │             │             │
     │<────────────│<────────────│             │             │
     │ Rollback complete         │             │             │
     │ { rolled_back: 150,       │             │             │
     │   failed: 2 }             │             │             │
```

---

## Detailed Component Design

### 1. Patch Discovery Service

```go
package discovery

import (
    "github.com/aws/aws-sdk-go/service/s3"
)

type PatchDiscoveryService struct {
    MicrosoftAPI  *MicrosoftUpdateAPI
    RedHatAPI     *RedHatAPI
    UbuntuAPI     *UbuntuAPI
    S3Client      *s3.S3
    MetadataRepo  PatchMetadataRepository
}

type Patch struct {
    ID              string    `json:"patch_id"`
    Title           string    `json:"title"`
    Description     string    `json:"description"`
    OS              string    `json:"os"`           // Windows, Linux, macOS
    OSVersion       []string  `json:"os_version"`   // ["10", "11"]
    Severity        string    `json:"severity"`     // Critical, Important, Moderate, Low
    Type            string    `json:"type"`         // Security, Feature, Update
    CVEs            []string  `json:"cves"`
    ReleaseDate     time.Time `json:"release_date"`
    Supersedes      []string  `json:"supersedes"`   // Previous patches replaced
    Dependencies    []string  `json:"dependencies"` // Required patches
    DownloadURL     string    `json:"download_url"`
    SizeBytes       int64     `json:"size_bytes"`
    Checksum        string    `json:"checksum"`
    RebootRequired  bool      `json:"reboot_required"`
    RiskScore       int       `json:"risk_score"`   // 1-10
}

// Run discovery hourly
func (s *PatchDiscoveryService) Run() {
    ticker := time.NewTicker(1 * time.Hour)
    defer ticker.Stop()
    
    for {
        select {
        case <-ticker.C:
            s.discoverPatches()
        }
    }
}

func (s *PatchDiscoveryService) discoverPatches() {
    // Discover Windows patches
    go s.discoverWindows()
    
    // Discover Linux patches
    go s.discoverLinux()
    
    // Discover macOS patches
    go s.discoverMacOS()
}

func (s *PatchDiscoveryService) discoverWindows() error {
    // Query Microsoft Update Catalog API
    updates, err := s.MicrosoftAPI.GetLatestUpdates()
    if err != nil {
        return err
    }
    
    for _, update := range updates {
        // Check if already in our database
        exists, _ := s.MetadataRepo.Exists(update.ID)
        if exists {
            continue  // Already discovered
        }
        
        // Parse metadata
        patch := s.parseWindowsUpdate(update)
        
        // Download patch file
        patchData, err := s.MicrosoftAPI.Download(update.DownloadURL)
        if err != nil {
            log.Error("Failed to download patch", err)
            continue
        }
        
        // Upload to S3
        s3Key := fmt.Sprintf("patches/windows/%s/package.msu", patch.ID)
        if err := s.uploadToS3(s3Key, patchData); err != nil {
            log.Error("Failed to upload to S3", err)
            continue
        }
        
        // Update download URL to S3
        patch.DownloadURL = fmt.Sprintf("https://cdn.n-able.com/%s", s3Key)
        
        // Calculate risk score
        patch.RiskScore = s.calculateRiskScore(patch)
        
        // Save metadata
        if err := s.MetadataRepo.Save(patch); err != nil {
            log.Error("Failed to save metadata", err)
            continue
        }
        
        // Notify MSPs if critical
        if patch.Severity == "Critical" {
            s.notifyCriticalPatch(patch)
        }
        
        log.Info("Discovered new patch", patch.ID)
    }
    
    return nil
}

func (s *PatchDiscoveryService) calculateRiskScore(patch *Patch) int {
    score := 0
    
    // Severity
    switch patch.Severity {
    case "Critical":
        score += 5
    case "Important":
        score += 3
    case "Moderate":
        score += 1
    }
    
    // CVE count
    if len(patch.CVEs) > 0 {
        score += min(len(patch.CVEs), 3)
    }
    
    // Reboot required (lower risk if no reboot)
    if !patch.RebootRequired {
        score -= 1
    }
    
    // Age (newer = higher risk to deploy quickly)
    age := time.Since(patch.ReleaseDate)
    if age < 7*24*time.Hour {
        score += 2
    }
    
    return min(max(score, 1), 10)
}

func (s *PatchDiscoveryService) uploadToS3(key string, data []byte) error {
    _, err := s.S3Client.PutObject(&s3.PutObjectInput{
        Bucket:               aws.String("n-able-patches"),
        Key:                  aws.String(key),
        Body:                 bytes.NewReader(data),
        ServerSideEncryption: aws.String("AES256"),
        StorageClass:         aws.String("INTELLIGENT_TIERING"),
    })
    return err
}
```

### 2. Agent Patch Scanner

```go
package agent

type PatchScanner struct {
    DeviceID  string
    OS        string
    APIClient *APIClient
}

func (s *PatchScanner) Scan() error {
    var installedPatches []InstalledPatch
    var err error
    
    // Query installed patches based on OS
    switch s.OS {
    case "Windows":
        installedPatches, err = s.scanWindows()
    case "Linux":
        installedPatches, err = s.scanLinux()
    case "macOS":
        installedPatches, err = s.scanMacOS()
    default:
        return fmt.Errorf("unsupported OS: %s", s.OS)
    }
    
    if err != nil {
        return err
    }
    
    // Send to platform
    result := ScanResult{
        DeviceID:  s.DeviceID,
        OS:        s.OS,
        OSVersion: s.getOSVersion(),
        Installed: installedPatches,
        Timestamp: time.Now(),
    }
    
    return s.APIClient.SendScanResult(result)
}

func (s *PatchScanner) scanWindows() ([]InstalledPatch, error) {
    // Use Windows Management Instrumentation (WMI)
    cmd := exec.Command("wmic", "qfe", "list", "brief")
    output, err := cmd.Output()
    if err != nil {
        return nil, err
    }
    
    // Parse output
    patches := []InstalledPatch{}
    lines := strings.Split(string(output), "\n")
    
    for _, line := range lines {
        if strings.Contains(line, "KB") {
            parts := strings.Fields(line)
            if len(parts) >= 2 {
                patches = append(patches, InstalledPatch{
                    ID:            extractKBNumber(parts[1]),
                    InstalledDate: parseDate(parts[2]),
                })
            }
        }
    }
    
    return patches, nil
}

func (s *PatchScanner) scanLinux() ([]InstalledPatch, error) {
    // Detect package manager
    if _, err := exec.LookPath("rpm"); err == nil {
        // RHEL/CentOS (RPM-based)
        return s.scanRPM()
    } else if _, err := exec.LookPath("dpkg"); err == nil {
        // Ubuntu/Debian (DEB-based)
        return s.scanDEB()
    }
    
    return nil, fmt.Errorf("unsupported Linux distribution")
}

func (s *PatchScanner) scanRPM() ([]InstalledPatch, error) {
    cmd := exec.Command("rpm", "-qa", "--queryformat", 
                        "%{NAME}-%{VERSION}-%{RELEASE}|%{INSTALLTIME}\n")
    output, err := cmd.Output()
    if err != nil {
        return nil, err
    }
    
    patches := []InstalledPatch{}
    lines := strings.Split(string(output), "\n")
    
    for _, line := range lines {
        if line == "" {
            continue
        }
        
        parts := strings.Split(line, "|")
        if len(parts) == 2 {
            installTime, _ := strconv.ParseInt(parts[1], 10, 64)
            patches = append(patches, InstalledPatch{
                ID:            parts[0],
                InstalledDate: time.Unix(installTime, 0),
            })
        }
    }
    
    return patches, nil
}

func (s *PatchScanner) scanDEB() ([]InstalledPatch, error) {
    cmd := exec.Command("dpkg-query", "-W", "-f", 
                        "${Package}=${Version}|${Status}\n")
    output, err := cmd.Output()
    if err != nil {
        return nil, err
    }
    
    patches := []InstalledPatch{}
    lines := strings.Split(string(output), "\n")
    
    for _, line := range lines {
        if !strings.Contains(line, "install ok installed") {
            continue
        }
        
        parts := strings.Split(line, "|")
        if len(parts) >= 1 {
            patches = append(patches, InstalledPatch{
                ID: parts[0],
            })
        }
    }
    
    return patches, nil
}
```

### 3. Patch Assessment Service

```go
package assessment

type AssessmentService struct {
    MetadataRepo  PatchMetadataRepository
    DeviceRepo    DeviceRepository
    KafkaProducer *kafka.Producer
}

func (s *AssessmentService) HandleScanResult(result *ScanResult) error {
    // Get available patches for this OS and version
    availablePatches, err := s.MetadataRepo.GetPatchesForOS(
        result.OS, 
        result.OSVersion,
    )
    if err != nil {
        return err
    }
    
    // Calculate missing patches
    installedMap := make(map[string]bool)
    for _, installed := range result.Installed {
        installedMap[installed.ID] = true
    }
    
    missing := []Patch{}
    for _, available := range availablePatches {
        if !installedMap[available.ID] {
            // Check if superseded by installed patch
            if !s.isSuperseded(available, result.Installed) {
                missing = append(missing, available)
            }
        }
    }
    
    // Calculate compliance
    compliance := float64(len(result.Installed)) / 
                  float64(len(result.Installed) + len(missing)) * 100
    
    // Update device state
    deviceState := DevicePatchState{
        DeviceID:        result.DeviceID,
        InstalledCount:  len(result.Installed),
        MissingCount:    len(missing),
        MissingPatches:  missing,
        Compliance:      compliance,
        LastScanTime:    result.Timestamp,
        CriticalMissing: s.countCritical(missing),
    }
    
    if err := s.DeviceRepo.UpdatePatchState(deviceState); err != nil {
        return err
    }
    
    // Publish to Kafka for compliance analytics
    event := PatchComplianceEvent{
        DeviceID:        result.DeviceID,
        TenantID:        result.TenantID,
        Compliance:      compliance,
        CriticalMissing: deviceState.CriticalMissing,
        Timestamp:       time.Now(),
    }
    
    s.KafkaProducer.Produce("patch-compliance", event)
    
    // Generate alert if compliance below threshold
    if compliance < 80.0 {
        s.generateComplianceAlert(deviceState)
    }
    
    return nil
}

func (s *AssessmentService) isSuperseded(patch Patch, 
                                         installed []InstalledPatch) bool {
    // Check if any installed patch supersedes this patch
    for _, installedPatch := range installed {
        metadata, _ := s.MetadataRepo.Get(installedPatch.ID)
        if metadata != nil {
            for _, superseded := range metadata.Supersedes {
                if superseded == patch.ID {
                    return true
                }
            }
        }
    }
    return false
}

func (s *AssessmentService) countCritical(patches []Patch) int {
    count := 0
    for _, p := range patches {
        if p.Severity == "Critical" {
            count++
        }
    }
    return count
}
```

### 4. Deployment Orchestrator

```go
package deployment

type DeploymentOrchestrator struct {
    DeviceRepo    DeviceRepository
    DeploymentRepo DeploymentRepository
    CommandQueue  *sqs.SQS
    Redis         *redis.Client
}

type DeploymentPlan struct {
    ID            string
    TenantID      string
    PatchIDs      []string
    DeviceGroup   string
    Schedule      time.Time
    Strategy      RolloutStrategy
    Status        DeploymentStatus
    CreatedBy     string
    CreatedAt     time.Time
}

type RolloutStrategy struct {
    Stages []Stage
}

type Stage struct {
    Name            string
    Percentage      int       // % of devices
    SuccessRate     float64   // Required success rate to proceed
    WaitDuration    time.Duration
}

type DeploymentStatus string

const (
    StatusScheduled  DeploymentStatus = "scheduled"
    StatusPilot      DeploymentStatus = "pilot"
    StatusStage1     DeploymentStatus = "stage1"
    StatusStage2     DeploymentStatus = "stage2"
    StatusCompleted  DeploymentStatus = "completed"
    StatusPaused     DeploymentStatus = "paused"
    StatusRolledBack DeploymentStatus = "rolled_back"
    StatusFailed     DeploymentStatus = "failed"
)

func (o *DeploymentOrchestrator) CreateDeployment(plan *DeploymentPlan) error {
    // Default staged rollout strategy
    if plan.Strategy.Stages == nil {
        plan.Strategy = RolloutStrategy{
            Stages: []Stage{
                {Name: "pilot", Percentage: 1, SuccessRate: 0.95, WaitDuration: 30 * time.Minute},
                {Name: "stage1", Percentage: 10, SuccessRate: 0.95, WaitDuration: 2 * time.Hour},
                {Name: "stage2", Percentage: 50, SuccessRate: 0.95, WaitDuration: 4 * time.Hour},
                {Name: "stage3", Percentage: 100, SuccessRate: 0.90, WaitDuration: 0},
            },
        }
    }
    
    // Save deployment plan
    plan.Status = StatusScheduled
    if err := o.DeploymentRepo.Save(plan); err != nil {
        return err
    }
    
    // Schedule deployment
    go o.scheduleDeployment(plan)
    
    return nil
}

func (o *DeploymentOrchestrator) scheduleDeployment(plan *DeploymentPlan) {
    // Wait until scheduled time
    time.Sleep(time.Until(plan.Schedule))
    
    // Start pilot stage
    o.executeStage(plan, 0)
}

func (o *DeploymentOrchestrator) executeStage(plan *DeploymentPlan, 
                                              stageIndex int) {
    if stageIndex >= len(plan.Strategy.Stages) {
        // All stages completed
        plan.Status = StatusCompleted
        o.DeploymentRepo.Update(plan)
        return
    }
    
    stage := plan.Strategy.Stages[stageIndex]
    
    // Get devices for this deployment
    devices, err := o.DeviceRepo.GetByGroup(plan.TenantID, plan.DeviceGroup)
    if err != nil {
        log.Error("Failed to get devices", err)
        return
    }
    
    // Calculate device count for this stage
    deviceCount := len(devices) * stage.Percentage / 100
    if deviceCount == 0 {
        deviceCount = 1  // At least 1 device
    }
    
    // Select devices (randomized)
    selectedDevices := o.selectDevices(devices, deviceCount, plan.ID, stageIndex)
    
    log.Info("Starting stage", stage.Name, "with", len(selectedDevices), "devices")
    
    // Send deployment commands
    for _, device := range selectedDevices {
        for _, patchID := range plan.PatchIDs {
            command := DeployCommand{
                DeploymentID: plan.ID,
                DeviceID:     device.ID,
                PatchID:      patchID,
                Stage:        stage.Name,
                CreatedAt:    time.Now(),
            }
            
            // Push to SQS
            o.sendCommand(command)
        }
    }
    
    // Wait for stage completion
    time.Sleep(stage.WaitDuration)
    
    // Check success rate
    successRate := o.calculateSuccessRate(plan.ID, stage.Name)
    
    log.Info("Stage", stage.Name, "success rate:", successRate)
    
    if successRate >= stage.SuccessRate {
        // Proceed to next stage
        log.Info("Proceeding to next stage")
        o.executeStage(plan, stageIndex+1)
    } else {
        // Pause deployment for review
        log.Warn("Stage failed, pausing deployment")
        plan.Status = StatusPaused
        o.DeploymentRepo.Update(plan)
        
        // Notify MSP
        o.notifyFailure(plan, stage, successRate)
    }
}

func (o *DeploymentOrchestrator) calculateSuccessRate(deploymentID string, 
                                                      stage string) float64 {
    // Query deployment status from database
    results, _ := o.DeploymentRepo.GetStageResults(deploymentID, stage)
    
    if len(results) == 0 {
        return 0.0
    }
    
    successful := 0
    for _, result := range results {
        if result.Status == "success" {
            successful++
        }
    }
    
    return float64(successful) / float64(len(results))
}

func (o *DeploymentOrchestrator) selectDevices(devices []Device, 
                                               count int, 
                                               deploymentID string,
                                               stageIndex int) []Device {
    // Use deployment ID as seed for reproducibility
    seed := hashString(fmt.Sprintf("%s-%d", deploymentID, stageIndex))
    rng := rand.New(rand.NewSource(seed))
    
    // Shuffle devices
    shuffled := make([]Device, len(devices))
    copy(shuffled, devices)
    rng.Shuffle(len(shuffled), func(i, j int) {
        shuffled[i], shuffled[j] = shuffled[j], shuffled[i]
    })
    
    // Return first 'count' devices
    if count > len(shuffled) {
        count = len(shuffled)
    }
    
    return shuffled[:count]
}

func (o *DeploymentOrchestrator) Rollback(deploymentID string) error {
    plan, err := o.DeploymentRepo.Get(deploymentID)
    if err != nil {
        return err
    }
    
    // Get all devices that received patches
    deployedDevices, err := o.DeploymentRepo.GetDeployedDevices(deploymentID)
    if err != nil {
        return err
    }
    
    // Send uninstall commands
    for _, device := range deployedDevices {
        for _, patchID := range plan.PatchIDs {
            command := RollbackCommand{
                DeploymentID: deploymentID,
                DeviceID:     device.ID,
                PatchID:      patchID,
                Action:       "uninstall",
            }
            
            o.sendRollbackCommand(command)
        }
    }
    
    // Update deployment status
    plan.Status = StatusRolledBack
    o.DeploymentRepo.Update(plan)
    
    return nil
}
```

### 5. Agent Patch Installer

```go
package agent

type PatchInstaller struct {
    DeviceID  string
    OS        string
    APIClient *APIClient
}

func (i *PatchInstaller) Install(patchID string, downloadURL string) error {
    log.Info("Installing patch", patchID)
    
    // Report status: downloading
    i.reportStatus(patchID, "downloading", "")
    
    // Download patch
    patchFile, err := i.downloadPatch(downloadURL)
    if err != nil {
        i.reportStatus(patchID, "failed", err.Error())
        return err
    }
    defer os.Remove(patchFile)
    
    // Verify checksum
    if err := i.verifyChecksum(patchFile, patchID); err != nil {
        i.reportStatus(patchID, "failed", "checksum mismatch")
        return err
    }
    
    // Create snapshot/restore point
    i.reportStatus(patchID, "creating_snapshot", "")
    snapshotID, err := i.createSnapshot()
    if err != nil {
        log.Warn("Failed to create snapshot", err)
        // Continue anyway
    }
    
    // Install patch
    i.reportStatus(patchID, "installing", "")
    rebootRequired, err := i.installPatch(patchFile)
    if err != nil {
        i.reportStatus(patchID, "failed", err.Error())
        
        // Attempt rollback
        if snapshotID != "" {
            i.restoreSnapshot(snapshotID)
        }
        
        return err
    }
    
    // Reboot if required
    if rebootRequired {
        i.reportStatus(patchID, "reboot_pending", "")
        go i.scheduleReboot(5 * time.Minute)
    } else {
        i.reportStatus(patchID, "success", "")
    }
    
    log.Info("Patch installed successfully", patchID)
    return nil
}

func (i *PatchInstaller) downloadPatch(url string) (string, error) {
    // Download to temp directory
    tempFile := filepath.Join(os.TempDir(), fmt.Sprintf("patch-%s", generateID()))
    
    out, err := os.Create(tempFile)
    if err != nil {
        return "", err
    }
    defer out.Close()
    
    // HTTP GET
    resp, err := http.Get(url)
    if err != nil {
        return "", err
    }
    defer resp.Body.Close()
    
    // Copy to file with progress tracking
    _, err = io.Copy(out, resp.Body)
    if err != nil {
        return "", err
    }
    
    return tempFile, nil
}

func (i *PatchInstaller) installPatch(patchFile string) (bool, error) {
    switch i.OS {
    case "Windows":
        return i.installWindows(patchFile)
    case "Linux":
        return i.installLinux(patchFile)
    case "macOS":
        return i.installMacOS(patchFile)
    default:
        return false, fmt.Errorf("unsupported OS: %s", i.OS)
    }
}

func (i *PatchInstaller) installWindows(patchFile string) (bool, error) {
    // Use wusa.exe to install Windows Update package
    cmd := exec.Command("wusa.exe", patchFile, "/quiet", "/norestart")
    
    output, err := cmd.CombinedOutput()
    if err != nil {
        return false, fmt.Errorf("installation failed: %s", string(output))
    }
    
    // Check if reboot is required
    // Windows Update returns exit code 3010 if reboot required
    if exitErr, ok := err.(*exec.ExitError); ok {
        if exitErr.ExitCode() == 3010 {
            return true, nil  // Reboot required
        }
    }
    
    return false, nil  // No reboot required
}

func (i *PatchInstaller) installLinux(patchFile string) (bool, error) {
    // Detect package manager
    if strings.HasSuffix(patchFile, ".rpm") {
        return i.installRPM(patchFile)
    } else if strings.HasSuffix(patchFile, ".deb") {
        return i.installDEB(patchFile)
    }
    
    return false, fmt.Errorf("unknown package format")
}

func (i *PatchInstaller) installRPM(patchFile string) (bool, error) {
    cmd := exec.Command("rpm", "-Uvh", patchFile)
    
    output, err := cmd.CombinedOutput()
    if err != nil {
        return false, fmt.Errorf("rpm install failed: %s", string(output))
    }
    
    // Check if kernel update (requires reboot)
    if strings.Contains(patchFile, "kernel") {
        return true, nil
    }
    
    return false, nil
}

func (i *PatchInstaller) createSnapshot() (string, error) {
    switch i.OS {
    case "Windows":
        // Create Windows System Restore point
        cmd := exec.Command("powershell", "-Command",
            "Checkpoint-Computer", "-Description", "Pre-Patch Snapshot")
        if err := cmd.Run(); err != nil {
            return "", err
        }
        return "restore-point-created", nil
        
    case "Linux":
        // LVM snapshot or filesystem snapshot
        // Implementation depends on storage configuration
        return "", fmt.Errorf("not implemented")
        
    default:
        return "", fmt.Errorf("unsupported OS")
    }
}

func (i *PatchInstaller) reportStatus(patchID string, 
                                      status string, 
                                      message string) {
    statusReport := PatchStatus{
        DeviceID:  i.DeviceID,
        PatchID:   patchID,
        Status:    status,
        Message:   message,
        Timestamp: time.Now(),
    }
    
    i.APIClient.ReportPatchStatus(statusReport)
}
```

---

## Trade-offs and Tech Choices

### 1. Patch Distribution: CDN vs P2P

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **CDN (CloudFront)** | Fast global distribution, Reliable, Simple | Bandwidth costs | ✅ **Primary** |
| **P2P within sites** | Reduced bandwidth costs, Faster for large sites | Complex, Firewall issues | 🔄 **Future enhancement** |
| **Regional cache servers** | Lower latency, Reduced costs | Infrastructure overhead | ❌ Not yet needed |

**Decision**: **CloudFront CDN**
- 99.99% availability
- Edge caching reduces latency
- Cost optimized with S3 Intelligent Tiering

**Future**: P2P for large customer sites (>100 devices)

### 2. Deployment Strategy: Big Bang vs Staged Rollout

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Big Bang** | Fast, Simple | High risk, Hard to rollback | ❌ Too risky |
| **Staged Rollout** | Safe, Detect issues early | Slower, More complex | ✅ **Chosen** |
| **Blue-Green** | Zero downtime, Easy rollback | Requires double resources | ❌ Not applicable to patches |

**Decision**: **Staged Rollout (Pilot → 10% → 50% → 100%)**
- Pilot catches major issues
- Pause between stages for monitoring
- Auto-rollback if success rate < threshold

### 3. Patch Storage: Shared vs Per-Tenant

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Shared repository** | Cost-effective, Deduplication | Potential contention | ✅ **Chosen** |
| **Per-tenant storage** | Strong isolation, Custom patches | Expensive, Duplication | ❌ Unnecessary |

**Decision**: **Shared repository with access controls**
- Same patch served to all tenants
- Custom tenant patches stored separately
- Access control enforced at application layer

### 4. Approval Workflow: Auto vs Manual

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Auto-approve all** | Fast, No intervention | Risky | ❌ Too dangerous |
| **Manual approval all** | Safe, Full control | Slow, Labor-intensive | ❌ Doesn't scale |
| **Rule-based** | Balanced, Flexible | Complex rules | ✅ **Chosen** |

**Decision**: **Rule-based approval**

```
Rules:
- Auto-approve: Critical security patches after 7-day delay (vendor testing period)
- Auto-approve: Patches tested in pilot (99% success)
- Manual approval: Feature updates
- Manual approval: Patches affecting production servers
```

---

## Failure Scenarios and Bottlenecks

### 1. Patch Download Failure

**Scenario**: Agent fails to download patch from CDN

**Handling**:
```go
func (i *PatchInstaller) downloadPatchWithRetry(url string) (string, error) {
    maxRetries := 3
    backoff := 5 * time.Second
    
    for attempt := 1; attempt <= maxRetries; attempt++ {
        file, err := i.downloadPatch(url)
        if err == nil {
            return file, nil
        }
        
        log.Warn("Download failed, attempt", attempt, "of", maxRetries)
        
        if attempt < maxRetries {
            time.Sleep(backoff)
            backoff *= 2  // Exponential backoff
        }
    }
    
    return "", fmt.Errorf("download failed after %d attempts", maxRetries)
}
```

### 2. Patch Installation Failure

**Scenario**: Patch installation fails on device

**Mitigation**:
1. **Pre-installation checks**: Verify dependencies, disk space, memory
2. **Snapshot before install**: Create restore point
3. **Auto-rollback**: If install fails, restore snapshot
4. **Report failure**: Send detailed error logs
5. **Pause deployment**: If failure rate > 5%, pause rollout

### 3. Mass Deployment Overwhelming CDN

**Scenario**: 100K devices try to download at once

**Mitigation**:
```
1. Staggered Downloads:
   - Agent adds random jitter (0-30 minutes)
   - Spreads load over time
   
2. Rate Limiting:
   - Limit concurrent downloads per tenant
   - Queue management
   
3. CDN Scaling:
   - CloudFront auto-scales
   - Pre-warm cache for large deployments
   
4. P2P Fallback (future):
   - Devices within same site share patches
   - Reduces CDN load by 80%+
```

### 4. Rollback Complications

**Scenario**: Patch uninstall fails or makes things worse

**Challenges**:
- Some patches can't be uninstalled
- Uninstall might break dependencies
- Snapshot restoration requires reboot

**Mitigation**:
```
1. Test Rollback in Pilot:
   - Verify patches can be uninstalled
   - Test restore process
   
2. Keep Previous Version:
   - Don't supersede immediately
   - Allow downgrade
   
3. Manual Intervention:
   - If auto-rollback fails, flag for manual review
   - Provide detailed instructions
   
4. Full System Restore:
   - Last resort: Restore from backup
   - Cove Data Protection integration
```

---

## Future Improvements

### 1. AI-Powered Patch Prioritization

```
Current: Static risk scores
Future: ML model predicts patch impact

Factors:
- Historical success rates
- Device similarity
- Dependency conflicts
- Production vs non-production
- Customer risk tolerance

Benefit: Optimize patch order to minimize failures
```

### 2. Predictive Patching

```
Current: React to available patches
Future: Predict which devices need patching

Use Case:
- "Device X likely to need critical patch next week"
- Pre-download patches
- Schedule maintenance proactively

Benefit: Reduce time-to-patch by 50%
```

### 3. Continuous Compliance Monitoring

```
Current: Daily scans
Future: Real-time monitoring

Implementation:
- Agent monitors registry/filesystem for changes
- Detect patches installed outside N-central
- Update compliance instantly

Benefit: Always accurate compliance state
```

### 4. Integration with Vulnerability Scanners

```
Current: Patch based on vendor releases
Future: Patch based on actual vulnerabilities

Integration:
- Vulnerability scanner (Qualys, Nessus, etc.)
- Map vulnerabilities to patches
- Prioritize patches that fix exploited CVEs

Benefit: Focus on actual risks, not theoretical
```

---

## Summary

This patch management system design achieves:

- **99% Success Rate**: Staged rollouts, pre-checks, snapshots
- **Safety**: Pilot testing, auto-rollback, compliance monitoring
- **Scale**: 9M endpoints, 45M patches/month
- **Efficiency**: CDN distribution, smart caching
- **Flexibility**: Rule-based approvals, custom policies

**Key Technologies**:
- Go (services and agents)
- AWS S3 + CloudFront (patch distribution)
- DocumentDB (metadata and state)
- Kafka + SQS (event processing)
- Redis (caching and coordination)

This demonstrates enterprise-grade patch management capabilities that N-able operates at scale.
