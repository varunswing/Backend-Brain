# Cove Data Protection System - High Level Design (Cloud-First Backup & DR)

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

1. **Backup Operations**
   - Full, incremental, and differential backups
   - File-level and image-level (bare-metal) backups
   - Microsoft 365 backup (Exchange, OneDrive, SharePoint, Teams)
   - Database backups (SQL Server, MySQL, PostgreSQL)
   - Support for servers, workstations, VMs, cloud instances

2. **Backup Scheduling**
   - Flexible schedules (hourly, daily, weekly)
   - As frequent as every 15 minutes (using deduplication)
   - Retention policies (daily, weekly, monthly, yearly)
   - Backup windows and throttling

3. **Deduplication & Compression**
   - Global deduplication (cross-device)
   - Block-level deduplication
   - Compression (60x smaller backups as mentioned in N-able marketing)
   - Minimal bandwidth usage

4. **Disaster Recovery**
   - Bare-metal restore
   - File/folder level restore
   - Point-in-time recovery
   - Instant VM recovery (boot from backup)
   - Cross-platform restore (P2V, V2P)

5. **Immutability**
   - Immutable backups (ransomware protection)
   - Air-gapped storage (logically isolated)
   - Tamper-proof audit logs

6. **Recovery Verification**
   - Automated backup verification
   - Screenshot verification (boot VM from backup)
   - Integrity checks

7. **Multi-Tenancy**
   - Per-tenant backup policies
   - Per-tenant encryption keys
   - Isolated storage
   - White-label branding

### Non-Functional Requirements

1. **Scale**: 
   - 3M+ Microsoft 365 users
   - Millions of backup devices
   - Petabytes of backup data
   - 100K+ concurrent backup jobs

2. **Availability**: 99.99% for backup service, 99.999% for restore

3. **Performance**:
   - Backup speed: 10+ GB/hour per device
   - Restore speed: Near-instant for VMs, minutes for files
   - Deduplication ratio: 60:1 average
   - Compression ratio: 2:1 average

4. **RPO (Recovery Point Objective)**: 15 minutes (with frequent backups)

5. **RTO (Recovery Time Objective)**: 
   - File restore: < 5 minutes
   - Full system restore: < 2 hours
   - Instant VM boot: < 1 minute

6. **Durability**: 99.999999999% (11 nines) - S3 Standard

7. **Security**:
   - End-to-end encryption (AES-256)
   - Zero-knowledge encryption (customer-controlled keys)
   - FIPS 140-2 compliant
   - Immutable storage

8. **Compliance**: SOC 2, HIPAA, GDPR, PCI-DSS

---

## Capacity Estimation

### Traffic Estimates

```
Backup Jobs:
- Total devices backing up: 5M (servers, workstations, M365)
- Average backup frequency: Once per day
- Jobs per day: 5M
- Jobs per second: 5M / 86400 ≈ 58 jobs/sec
- Peak hours (8-hour window): 5M / (8*3600) = 174 jobs/sec

Data Transfer:
- Average daily change rate: 5% of total data
- Average device data: 500 GB
- Daily changed data per device: 25 GB
- Total daily backup: 5M × 25 GB = 125 PB/day
- With 60:1 deduplication: 125 PB / 60 = 2.08 PB/day
- Bandwidth: 2.08 PB / 86400 sec = 24 GB/sec = 192 Gbps

Restore Operations:
- Restore requests: 1% of devices per month = 50K/month
- Per day: 50K / 30 = 1,667 restores/day
- Per second: 1,667 / 86400 ≈ 0.02 restores/sec
- Average restore size: 50 GB
- Restore bandwidth: 0.02 × 50 GB = 1 GB/sec = 8 Gbps
```

### Storage Estimates

```
Total Managed Data (before dedup/compression):
- 5M devices × 500 GB average = 2.5 EB

After Deduplication (60:1) and Compression (2:1):
- 2.5 EB / 60 / 2 = 21 PB stored

Retention (30 daily + 12 weekly + 12 monthly + 7 yearly):
- Incremental model: 30 days full coverage + weekly/monthly/yearly incrementals
- Approximate storage with incrementals: 21 PB × 1.5 = 31.5 PB

Metadata:
- Per file: 200 bytes (path, hash, timestamps)
- Average files per device: 100K
- Total files: 5M × 100K = 500 Billion files
- Metadata storage: 500B × 200 bytes = 100 TB

Total Storage: ~32 PB (backup data + metadata)

Daily Growth:
- New data: 125 PB/day
- After dedup/compression: 2.08 PB/day
```

### Bandwidth Estimates

```
Inbound (Backups):
- Peak: 192 Gbps during backup windows
- Distributed across devices, so per-device: ~1 Mbps average

Outbound (Restores):
- Average: 8 Gbps
- Peak: 80 Gbps (10x during DR scenarios)

Metadata Operations:
- Dedup lookups: 1M lookups/sec during peak
- Hash computations
- Database queries
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                            BACKUP SOURCES (5M+ Devices)                                 │
│                                                                                         │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │
│  │   Servers    │  │ Workstations │  │  Microsoft   │  │   Virtual    │              │
│  │   (Linux/    │  │  (Windows/   │  │     365      │  │   Machines   │              │
│  │   Windows)   │  │    macOS)    │  │  (Exchange,  │  │  (VMware,    │              │
│  │              │  │              │  │   OneDrive,  │  │   Hyper-V)   │              │
│  │              │  │              │  │  SharePoint) │  │              │              │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘              │
│         │                 │                 │                 │                       │
└─────────┼─────────────────┼─────────────────┼─────────────────┼───────────────────────┘
          │                 │                 │                 │
          │    ┌────────────┴────────┐        │                 │
          │    │  BACKUP AGENT (Go)  │        │                 │
          │    │                     │        │                 │
          │    │  - File monitor     │        │                 │
          │    │  - Change detection │        │                 │
          │    │  - Chunking         │        │                 │
          │    │  - Deduplication    │        │                 │
          │    │  - Compression      │        │                 │
          │    │  - Encryption       │        │                 │
          │    └─────────────────────┘        │                 │
          │                 │                 │                 │
          └─────────────────┴─────────────────┴─────────────────┘
                            │
                            │ HTTPS/TLS
                            ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                          API GATEWAY / LOAD BALANCER (ALB)                              │
│                                                                                         │
│  - TLS termination                                                                      │
│  - Authentication (mTLS)                                                                │
│  - Rate limiting (per tenant)                                                           │
│  - Request routing                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────────┘
                            │
          ┌─────────────────┴─────────────────┐
          │                                   │
          ▼                                   ▼
┌─────────────────────────────┐   ┌─────────────────────────────┐
│   BACKUP INGESTION SERVICE  │   │    RESTORE SERVICE          │
│          (ECS/Go)           │   │        (ECS/Go)             │
│                             │   │                             │
│  - Receive chunks           │   │  - Retrieve chunks          │
│  - Dedup lookup             │   │  - Reassemble files         │
│  - Store new chunks         │   │  - Decompress               │
│  - Update metadata          │   │  - Decrypt                  │
│  - Job coordination         │   │  - Stream to client         │
└─────────────┬───────────────┘   └─────────────┬───────────────┘
              │                                 │
              ▼                                 ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                          DEDUPLICATION ENGINE (Redis + DB)                              │
│                                                                                         │
│  ┌──────────────────────────────────────────────────────────────────────────────────┐  │
│  │                      CONTENT-ADDRESSABLE STORAGE INDEX                           │  │
│  │                                                                                  │  │
│  │  chunk_hash (SHA-256) → {                                                       │  │
│  │    s3_location: "s3://cove-chunks/ab/cd/abcd1234...",                           │  │
│  │    size: 4194304,  // 4 MB                                                      │  │
│  │    ref_count: 1523,  // How many devices reference this chunk                  │  │
│  │    created_at: timestamp                                                        │  │
│  │  }                                                                              │  │
│  │                                                                                  │  │
│  │  - Redis for hot chunks (recently used)                                         │  │
│  │  - DocumentDB for all chunks                                                    │  │
│  │  - Bloom filter for existence check                                             │  │
│  └──────────────────────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                         OBJECT STORAGE (S3 with Object Lock)                            │
│                                                                                         │
│  Bucket Structure:                                                                      │
│  /chunks/{first_2_chars}/{next_2_chars}/{full_hash}                                    │
│  /chunks/ab/cd/abcd1234567890...                                                        │
│                                                                                         │
│  Features:                                                                              │
│  - S3 Object Lock (WORM - Write Once Read Many)                                        │
│  - Immutable for retention period                                                       │
│  - Intelligent Tiering (auto-move to cheaper storage)                                   │
│  - Cross-region replication (DR)                                                        │
│  - Versioning enabled                                                                   │
│                                                                                         │
│  Storage Classes:                                                                       │
│  - Hot data (< 30 days): S3 Standard                                                    │
│  - Warm data (30-90 days): S3 Infrequent Access                                         │
│  - Cold data (> 90 days): S3 Glacier Flexible Retrieval                                 │
│  - Archive (> 1 year): S3 Glacier Deep Archive                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                               METADATA DATABASE                                         │
├──────────────────────────────┬──────────────────────────────┬──────────────────────────┤
│   BACKUP METADATA DB         │    FILE CATALOG DB           │   JOB STATE DB           │
│    (DocumentDB)              │    (DocumentDB)              │    (DocumentDB)          │
│                              │                              │                          │
│ backup_sessions: {           │ files: {                     │ jobs: {                  │
│   session_id,                │   device_id,                 │   job_id,                │
│   device_id,                 │   file_path,                 │   device_id,             │
│   tenant_id,                 │   backup_session_id,         │   type: "backup",        │
│   start_time,                │   file_size,                 │   status: "running",     │
│   end_time,                  │   modified_time,             │   progress: 45%,         │
│   status: "completed",       │   chunks: [                  │   start_time,            │
│   backup_type: "incremental",│     {hash, offset, size},    │   bytes_transferred,     │
│   bytes_backed_up,           │     ...                      │   errors: []             │
│   dedup_ratio,               │   ],                         │ }                        │
│   chunks_uploaded            │   encrypted: true,           │                          │
│ }                            │   compression: "zstd"        │                          │
│                              │ }                            │                          │
└──────────────────────────────┴──────────────────────────────┴──────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                             BACKUP VERIFICATION SERVICE                                 │
│                                  (Lambda/ECS)                                           │
│                                                                                         │
│  - Automated integrity checks                                                           │
│  - Screenshot verification (boot VM from backup)                                        │
│  - File hash verification                                                               │
│  - Scheduled verification jobs                                                          │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                 RECOVERY SERVICES                                       │
│                                                                                         │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐         │
│  │  File/Folder Restore │  │ Bare-Metal Restore   │  │  Instant VM Boot     │         │
│  │                      │  │                      │  │                      │         │
│  │  - Browse backups    │  │  - Bootable ISO      │  │  - Mount backup as   │         │
│  │  - Point-in-time     │  │  - Driver injection  │  │    VM disk           │         │
│  │  - Download          │  │  - Full system       │  │  - Boot in cloud     │         │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────────┘         │
└─────────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                          MONITORING & ALERTING (CloudWatch)                             │
│                                                                                         │
│  - Backup success/failure rates                                                         │
│  - Deduplication ratios                                                                 │
│  - Storage utilization                                                                  │
│  - RTO/RPO metrics                                                                      │
│  - Failed backup alerts                                                                 │
└─────────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **Backup Agent**: Monitors files, performs chunking, dedup, compression, encryption
2. **Ingestion Service**: Receives chunks, performs global dedup lookup
3. **Deduplication Engine**: Content-addressable storage with Redis cache
4. **Object Storage**: Immutable S3 storage with lifecycle policies
5. **Metadata Database**: Tracks backup sessions, files, chunks
6. **Restore Service**: Retrieves and reassembles data
7. **Verification Service**: Automated backup testing

---

## Request Flows

### Backup Flow (Incremental with Deduplication)

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  Agent   │  │   ALB    │  │Ingestion │  │  Dedup   │  │    S3    │  │Metadata  │
│          │  │          │  │ Service  │  │  Engine  │  │          │  │    DB    │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │             │
     │ Scheduled   │             │             │             │             │
     │ backup      │             │             │             │             │
     │ triggered   │             │             │             │             │
     │             │             │             │             │             │
     │ Scan filesystem for changed files      │             │             │
     │ (using change journals, inotify)       │             │             │
     │             │             │             │             │             │
     │ File: /data/document.pdf (modified)    │             │             │
     │             │             │             │             │             │
     │ Read file and split into 4MB chunks    │             │             │
     │ Chunk 1: [bytes 0-4MB]                 │             │             │
     │ Chunk 2: [bytes 4MB-8MB]               │             │             │
     │             │             │             │             │             │
     │ For each chunk:                        │             │             │
     │   - Compress (Zstd)                    │             │             │
     │   - Compute SHA-256 hash               │             │             │
     │   - Encrypt (AES-256)                  │             │             │
     │             │             │             │             │             │
     │ POST /backup/chunks      │             │             │             │
     │ {                        │             │             │             │
     │   session_id,            │             │             │             │
     │   chunks: [              │             │             │             │
     │     {hash: "abc123...",  │             │             │             │
     │      size: 2097152,      │             │             │             │
     │      encrypted_data}     │             │             │             │
     │   ]                      │             │             │             │
     │ }                        │             │             │             │
     │────────────>│────────────>│             │             │             │
     │             │             │             │             │             │
     │             │             │ For each chunk hash       │             │
     │             │             │────────────>│             │             │
     │             │             │ Check if chunk exists     │             │
     │             │             │ (Bloom filter first,      │             │
     │             │             │  then Redis, then DB)     │             │
     │             │             │             │             │             │
     │             │             │<────────────│             │             │
     │             │             │ Chunk abc123: EXISTS      │             │
     │             │             │ (ref_count: 523)          │             │
     │             │             │             │             │             │
     │             │             │ Chunk def456: NEW         │             │
     │             │             │             │             │             │
     │             │             │ For NEW chunks only:      │             │
     │             │             │ Upload to S3              │             │
     │             │             │───────────────────────────>│             │
     │             │             │ PUT /chunks/de/f4/def456  │             │
     │             │             │             │             │             │
     │             │             │ Update dedup index        │             │
     │             │             │────────────>│             │             │
     │             │             │ Set chunk_hash → location │             │
     │             │             │             │             │             │
     │             │             │ Increment ref_count for existing chunks │
     │             │             │────────────>│             │             │
     │             │             │             │             │             │
     │             │             │ Save file metadata        │             │
     │             │             │─────────────────────────────────────────>│
     │             │             │ {                         │             │
     │             │             │   file_path: "/data/doc.pdf",           │
     │             │             │   chunks: [               │             │
     │             │             │     {hash: "abc123", offset: 0, size: 4MB},
     │             │             │     {hash: "def456", offset: 4MB, size: 2MB}
     │             │             │   ]                       │             │
     │             │             │ }                         │             │
     │             │             │             │             │             │
     │<────────────│<────────────│             │             │             │
     │ 200 OK      │             │             │             │             │
     │ {           │             │             │             │             │
     │   chunks_uploaded: 1,     │             │             │             │
     │   chunks_deduplicated: 1, │             │             │             │
     │   bytes_saved: 4MB        │             │             │             │
     │ }           │             │             │             │             │
     │             │             │             │             │             │
     │ Continue for all files...│             │             │             │
     │             │             │             │             │             │
     │ POST /backup/complete    │             │             │             │
     │────────────>│────────────>│             │             │             │
     │             │             │ Update session status     │             │
     │             │             │─────────────────────────────────────────>│
     │             │             │ { status: "completed",    │             │
     │             │             │   dedup_ratio: 60:1 }     │             │
```

### Restore Flow (File/Folder)

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│MSP User/ │  │   Web    │  │ Restore  │  │ Metadata │  │    S3    │  │  Agent   │
│  Agent   │  │   API    │  │ Service  │  │    DB    │  │          │  │          │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │             │
     │ Browse      │             │             │             │             │
     │ available   │             │             │             │             │
     │ backups     │             │             │             │             │
     │             │             │             │             │             │
     │ GET /backups?device_id=X&date=...      │             │             │
     │────────────>│             │             │             │             │
     │             │ Query backup sessions     │             │             │
     │             │───────────────────────────>│             │             │
     │             │             │             │             │             │
     │             │<───────────────────────────│             │             │
     │<────────────│ [                         │             │             │
     │             │   {session_id, date, size}│             │             │
     │             │ ]                         │             │             │
     │             │             │             │             │             │
     │ Select backup session & files to restore│             │             │
     │             │             │             │             │             │
     │ POST /restore            │             │             │             │
     │ { session_id,            │             │             │             │
     │   files: ["/data/doc.pdf"],            │             │             │
     │   destination: "device_X" }            │             │             │
     │────────────>│             │             │             │             │
     │             │ Create restore job        │             │             │
     │             │────────────>│             │             │             │
     │             │             │             │             │             │
     │             │             │ Fetch file metadata       │             │
     │             │             │───────────>│             │             │
     │             │             │             │             │             │
     │             │             │<───────────│             │             │
     │             │             │ { chunks: [{hash, offset, size}, ...] }
     │             │             │             │             │             │
     │             │             │ For each chunk, retrieve from S3        │
     │             │             │───────────────────────────>│             │
     │             │             │ GET /chunks/ab/cd/abc123  │             │
     │             │             │             │             │             │
     │             │             │<───────────────────────────│             │
     │             │             │ (encrypted chunk data)    │             │
     │             │             │             │             │             │
     │             │             │ Reassemble file:          │             │
     │             │             │ - Decrypt chunks          │             │
     │             │             │ - Decompress              │             │
     │             │             │ - Concatenate in order    │             │
     │             │             │             │             │             │
     │             │             │ Stream to agent           │             │
     │             │             │─────────────────────────────────────────>│
     │             │             │             │             │             │
     │             │             │             │             │             │ Write to
     │             │             │             │             │             │ filesystem
     │             │             │             │             │             │
     │             │             │             │             │<────────────│
     │             │             │             │             │ Restore complete
     │             │             │<────────────────────────────────────────│
     │             │<────────────│             │             │             │
     │<────────────│ 200 OK      │             │             │             │
     │             │ { status: "completed",    │             │             │
     │             │   files_restored: 1 }     │             │             │
```

### Instant VM Boot Flow

```
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│MSP User  │  │   Web    │  │ VM Boot  │  │ Metadata │  │    S3    │
│          │  │   API    │  │ Service  │  │    DB    │  │          │
└────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘
     │             │             │             │             │
     │ Disaster    │             │             │             │
     │ scenario:   │             │             │             │
     │ Server down │             │             │             │
     │             │             │             │             │
     │ POST /vm/boot-from-backup│             │             │
     │ { backup_session_id }    │             │             │
     │────────────>│             │             │             │
     │             │ Create VM instance in cloud             │
     │             │────────────>│             │             │
     │             │             │             │             │
     │             │             │ Fetch backup metadata     │
     │             │             │───────────>│             │
     │             │             │ (disk layout, partitions) │
     │             │             │             │             │
     │             │             │ Create virtual disk       │
     │             │             │ (sparse, on-demand load)  │
     │             │             │             │             │
     │             │             │ Mount S3 as backend       │
     │             │             │ (FUSE filesystem or      │
     │             │             │  NBD - Network Block Dev)│
     │             │             │             │             │
     │             │             │ Boot VM                   │
     │             │             │ (reads blocks on-demand   │
     │             │             │  from S3)                 │
     │             │             │───────────────────────────>│
     │             │             │ GET /chunks/{hash}        │
     │             │             │ (only accessed blocks)    │
     │             │             │             │             │
     │<────────────│<────────────│             │             │
     │ 200 OK      │             │             │             │
     │ { vm_ip: "10.0.1.50",     │             │             │
     │   status: "running",      │             │             │
     │   boot_time: "45 seconds" }            │             │
     │             │             │             │             │
     │ MSP can now access VM temporarily while physical      │
     │ server is being repaired                              │
```

---

## Detailed Component Design

### 1. Backup Agent Architecture

```go
package agent

import (
    "crypto/sha256"
    "github.com/klauspost/compress/zstd"
)

type BackupAgent struct {
    DeviceID     string
    TenantID     string
    Config       *BackupConfig
    APIClient    *APIClient
    FileMonitor  *FileMonitor
    Chunker      *Chunker
    Deduplicator *LocalDeduplicator
    Encryptor    *Encryptor
}

type BackupConfig struct {
    Schedule          string        // "0 2 * * *" (cron format)
    Paths             []string      // Paths to backup
    Exclusions        []string      // Paths to exclude
    RetentionPolicy   RetentionPolicy
    EncryptionKey     []byte
    CompressionLevel  int
    ChunkSize         int           // 4 MB default
    Bandwidth         int           // Max MB/sec
}

type RetentionPolicy struct {
    Daily   int  // Keep 30 daily backups
    Weekly  int  // Keep 12 weekly backups
    Monthly int  // Keep 12 monthly backups
    Yearly  int  // Keep 7 yearly backups
}

// Main backup process
func (a *BackupAgent) RunBackup() error {
    log.Info("Starting backup job")
    
    // Create backup session
    session := &BackupSession{
        SessionID:  generateID(),
        DeviceID:   a.DeviceID,
        TenantID:   a.TenantID,
        StartTime:  time.Now(),
        BackupType: a.determineBackupType(),  // Full or Incremental
    }
    
    // Get list of files to backup
    files, err := a.FileMonitor.GetChangedFiles(session.BackupType)
    if err != nil {
        return err
    }
    
    log.Info("Found", len(files), "files to backup")
    
    // Process each file
    for _, file := range files {
        if err := a.backupFile(session, file); err != nil {
            log.Error("Failed to backup file", file.Path, err)
            session.Errors = append(session.Errors, err)
            continue
        }
    }
    
    // Complete session
    session.EndTime = time.Now()
    session.Status = "completed"
    
    if err := a.APIClient.CompleteBackupSession(session); err != nil {
        return err
    }
    
    log.Info("Backup completed", "duration:", session.Duration())
    return nil
}

func (a *BackupAgent) backupFile(session *BackupSession, file *FileInfo) error {
    // Open file
    f, err := os.Open(file.Path)
    if err != nil {
        return err
    }
    defer f.Close()
    
    // Split file into chunks
    chunks := []*Chunk{}
    
    for {
        chunk, err := a.Chunker.NextChunk(f)
        if err == io.EOF {
            break
        }
        if err != nil {
            return err
        }
        
        // Process chunk: compress → hash → encrypt
        processedChunk, err := a.processChunk(chunk)
        if err != nil {
            return err
        }
        
        chunks = append(chunks, processedChunk)
    }
    
    // Check local dedup cache
    newChunks := []*Chunk{}
    cachedChunks := 0
    
    for _, chunk := range chunks {
        if a.Deduplicator.Exists(chunk.Hash) {
            cachedChunks++
            continue
        }
        newChunks = append(newChunks, chunk)
    }
    
    log.Debug("File", file.Path, "chunks:", len(chunks), 
              "new:", len(newChunks), "cached:", cachedChunks)
    
    // Upload new chunks in batches
    batchSize := 100
    for i := 0; i < len(newChunks); i += batchSize {
        end := i + batchSize
        if end > len(newChunks) {
            end = len(newChunks)
        }
        
        batch := newChunks[i:end]
        
        result, err := a.APIClient.UploadChunks(session.SessionID, batch)
        if err != nil {
            return err
        }
        
        // Update local dedup cache
        for _, chunk := range batch {
            a.Deduplicator.Add(chunk.Hash)
        }
        
        session.BytesUploaded += result.BytesUploaded
        session.BytesDeduplicated += result.BytesDeduplicated
    }
    
    // Save file metadata
    fileMetadata := &FileMetadata{
        SessionID:    session.SessionID,
        Path:         file.Path,
        Size:         file.Size,
        ModifiedTime: file.ModifiedTime,
        Chunks:       chunkReferences(chunks),
        Encrypted:    true,
        Compression:  "zstd",
    }
    
    if err := a.APIClient.SaveFileMetadata(fileMetadata); err != nil {
        return err
    }
    
    return nil
}

func (a *BackupAgent) processChunk(data []byte) (*Chunk, error) {
    // 1. Compress
    compressed, err := a.compress(data)
    if err != nil {
        return nil, err
    }
    
    // 2. Compute hash (before encryption for dedup)
    hash := sha256.Sum256(compressed)
    
    // 3. Encrypt
    encrypted, err := a.Encryptor.Encrypt(compressed)
    if err != nil {
        return nil, err
    }
    
    return &Chunk{
        Hash:          hex.EncodeToString(hash[:]),
        Size:          len(compressed),
        EncryptedData: encrypted,
    }, nil
}

func (a *BackupAgent) compress(data []byte) ([]byte, error) {
    // Use Zstandard compression (fast + high ratio)
    encoder, err := zstd.NewWriter(nil, 
        zstd.WithEncoderLevel(zstd.SpeedDefault))
    if err != nil {
        return nil, err
    }
    defer encoder.Close()
    
    return encoder.EncodeAll(data, make([]byte, 0, len(data))), nil
}

// Content-Defined Chunking (CDC) for better deduplication
type Chunker struct {
    MinSize    int
    AvgSize    int
    MaxSize    int
    RollingHash *RabinHash
}

func (c *Chunker) NextChunk(r io.Reader) ([]byte, error) {
    buf := make([]byte, c.MaxSize)
    chunk := []byte{}
    
    for len(chunk) < c.MaxSize {
        b := make([]byte, 1)
        n, err := r.Read(b)
        if err != nil {
            if err == io.EOF && len(chunk) > 0 {
                return chunk, nil
            }
            return nil, err
        }
        if n == 0 {
            break
        }
        
        chunk = append(chunk, b[0])
        
        // Check if we hit a chunk boundary (using rolling hash)
        c.RollingHash.Roll(b[0])
        
        if len(chunk) >= c.MinSize && c.RollingHash.IsBoundary() {
            return chunk, nil
        }
    }
    
    return chunk, nil
}

// Rabin fingerprint for content-defined chunking
type RabinHash struct {
    hash       uint64
    windowSize int
    prime      uint64
}

func (r *RabinHash) Roll(b byte) {
    r.hash = (r.hash * r.prime + uint64(b)) % (1 << 32)
}

func (r *RabinHash) IsBoundary() bool {
    // Check if lower N bits are zero (configurable boundary)
    // Average chunk size = 2^N
    return (r.hash & 0xFFF) == 0  // Average 4KB chunks
}
```

### 2. Deduplication Engine

```go
package dedup

import (
    "github.com/bits-and-blooms/bloom/v3"
)

type DeduplicationEngine struct {
    Redis        *redis.Client
    MetadataRepo ChunkMetadataRepository
    BloomFilter  *bloom.BloomFilter
    S3Client     *s3.S3
}

type ChunkMetadata struct {
    Hash       string    `json:"hash"`
    S3Location string    `json:"s3_location"`
    Size       int64     `json:"size"`
    RefCount   int64     `json:"ref_count"`
    CreatedAt  time.Time `json:"created_at"`
}

// Check if chunk exists (3-level check: Bloom → Redis → DB)
func (e *DeduplicationEngine) ChunkExists(hash string) (bool, *ChunkMetadata, error) {
    // Level 1: Bloom filter (fast negative check)
    if !e.BloomFilter.TestString(hash) {
        // Definitely doesn't exist
        return false, nil, nil
    }
    
    // Level 2: Redis cache (fast positive check)
    cacheKey := fmt.Sprintf("chunk:%s", hash)
    
    var metadata ChunkMetadata
    if err := e.Redis.Get(ctx, cacheKey).Scan(&metadata); err == nil {
        return true, &metadata, nil
    }
    
    // Level 3: Database (definitive check)
    metadata, err := e.MetadataRepo.GetByHash(hash)
    if err != nil {
        if errors.Is(err, ErrNotFound) {
            return false, nil, nil
        }
        return false, nil, err
    }
    
    // Populate cache
    e.Redis.Set(ctx, cacheKey, metadata, 24*time.Hour)
    
    return true, &metadata, nil
}

// Store new chunk
func (e *DeduplicationEngine) StoreChunk(chunk *Chunk) error {
    // Generate S3 key (sharded for performance)
    // /chunks/ab/cd/abcd1234567890...
    s3Key := fmt.Sprintf("chunks/%s/%s/%s", 
        chunk.Hash[:2], 
        chunk.Hash[2:4], 
        chunk.Hash)
    
    // Upload to S3
    _, err := e.S3Client.PutObject(&s3.PutObjectInput{
        Bucket:               aws.String("cove-backup-chunks"),
        Key:                  aws.String(s3Key),
        Body:                 bytes.NewReader(chunk.EncryptedData),
        ServerSideEncryption: aws.String("AES256"),
        StorageClass:         aws.String("INTELLIGENT_TIERING"),
        ObjectLockMode:       aws.String("COMPLIANCE"),  // Immutable
        ObjectLockRetainUntilDate: aws.Time(time.Now().Add(30 * 24 * time.Hour)),
    })
    if err != nil {
        return err
    }
    
    // Save metadata
    metadata := &ChunkMetadata{
        Hash:       chunk.Hash,
        S3Location: s3Key,
        Size:       int64(len(chunk.EncryptedData)),
        RefCount:   1,
        CreatedAt:  time.Now(),
    }
    
    if err := e.MetadataRepo.Save(metadata); err != nil {
        return err
    }
    
    // Update Bloom filter
    e.BloomFilter.AddString(chunk.Hash)
    
    // Cache in Redis
    cacheKey := fmt.Sprintf("chunk:%s", chunk.Hash)
    e.Redis.Set(ctx, cacheKey, metadata, 24*time.Hour)
    
    return nil
}

// Increment reference count for existing chunk
func (e *DeduplicationEngine) IncrementRefCount(hash string) error {
    // Update in database
    if err := e.MetadataRepo.IncrementRefCount(hash); err != nil {
        return err
    }
    
    // Invalidate cache (will be refreshed on next read)
    cacheKey := fmt.Sprintf("chunk:%s", hash)
    e.Redis.Del(ctx, cacheKey)
    
    return nil
}

// Decrement reference count (for backup deletion)
func (e *DeduplicationEngine) DecrementRefCount(hash string) error {
    metadata, err := e.MetadataRepo.DecrementRefCount(hash)
    if err != nil {
        return err
    }
    
    // If ref count reaches 0, delete chunk from S3
    if metadata.RefCount == 0 {
        go e.deleteChunk(hash, metadata.S3Location)
    }
    
    return nil
}

func (e *DeduplicationEngine) deleteChunk(hash string, s3Location string) {
    // Delete from S3
    _, err := e.S3Client.DeleteObject(&s3.DeleteObjectInput{
        Bucket: aws.String("cove-backup-chunks"),
        Key:    aws.String(s3Location),
    })
    if err != nil {
        log.Error("Failed to delete chunk from S3", err)
        return
    }
    
    // Delete metadata
    if err := e.MetadataRepo.Delete(hash); err != nil {
        log.Error("Failed to delete chunk metadata", err)
    }
    
    // Remove from cache
    cacheKey := fmt.Sprintf("chunk:%s", hash)
    e.Redis.Del(ctx, cacheKey)
    
    log.Info("Deleted unreferenced chunk", hash)
}

// Calculate global deduplication ratio
func (e *DeduplicationEngine) GetDedupStats() (*DedupStats, error) {
    stats := &DedupStats{}
    
    // Query all backup sessions from last 30 days
    sessions, err := e.MetadataRepo.GetRecentSessions(30 * 24 * time.Hour)
    if err != nil {
        return nil, err
    }
    
    totalBytesScanned := int64(0)
    totalBytesStored := int64(0)
    
    for _, session := range sessions {
        totalBytesScanned += session.BytesScanned
        totalBytesStored += session.BytesUploaded
    }
    
    if totalBytesStored > 0 {
        stats.DedupRatio = float64(totalBytesScanned) / float64(totalBytesStored)
    }
    
    stats.TotalBytesScanned = totalBytesScanned
    stats.TotalBytesStored = totalBytesStored
    stats.BytesSaved = totalBytesScanned - totalBytesStored
    
    return stats, nil
}
```

### 3. Restore Service

```go
package restore

type RestoreService struct {
    MetadataRepo  FileMetadataRepository
    DeduplicationEngine *dedup.DeduplicationEngine
    S3Client      *s3.S3
    Decryptor     *Decryptor
}

func (s *RestoreService) RestoreFile(sessionID string, 
                                     filePath string, 
                                     destination io.Writer) error {
    // Get file metadata
    fileMetadata, err := s.MetadataRepo.GetFile(sessionID, filePath)
    if err != nil {
        return err
    }
    
    log.Info("Restoring file", filePath, "with", len(fileMetadata.Chunks), "chunks")
    
    // Retrieve and reassemble chunks in order
    for i, chunkRef := range fileMetadata.Chunks {
        log.Debug("Retrieving chunk", i+1, "of", len(fileMetadata.Chunks))
        
        // Get chunk metadata (for S3 location)
        exists, chunkMetadata, err := s.DeduplicationEngine.ChunkExists(chunkRef.Hash)
        if !exists || err != nil {
            return fmt.Errorf("chunk not found: %s", chunkRef.Hash)
        }
        
        // Download from S3
        result, err := s.S3Client.GetObject(&s3.GetObjectInput{
            Bucket: aws.String("cove-backup-chunks"),
            Key:    aws.String(chunkMetadata.S3Location),
        })
        if err != nil {
            return err
        }
        defer result.Body.Close()
        
        // Read encrypted data
        encryptedData, err := io.ReadAll(result.Body)
        if err != nil {
            return err
        }
        
        // Decrypt
        compressed, err := s.Decryptor.Decrypt(encryptedData)
        if err != nil {
            return err
        }
        
        // Decompress
        data, err := s.decompress(compressed)
        if err != nil {
            return err
        }
        
        // Write to destination
        if _, err := destination.Write(data); err != nil {
            return err
        }
    }
    
    log.Info("File restored successfully", filePath)
    return nil
}

func (s *RestoreService) decompress(data []byte) ([]byte, error) {
    decoder, err := zstd.NewReader(nil)
    if err != nil {
        return nil, err
    }
    defer decoder.Close()
    
    return decoder.DecodeAll(data, nil)
}

// Restore entire directory tree
func (s *RestoreService) RestoreDirectory(sessionID string, 
                                          dirPath string, 
                                          destinationRoot string) error {
    // Get all files in directory from backup
    files, err := s.MetadataRepo.GetFilesInDirectory(sessionID, dirPath)
    if err != nil {
        return err
    }
    
    log.Info("Restoring", len(files), "files from", dirPath)
    
    // Restore each file
    for _, fileMetadata := range files {
        // Construct destination path
        relativePath := strings.TrimPrefix(fileMetadata.Path, dirPath)
        destPath := filepath.Join(destinationRoot, relativePath)
        
        // Create directory structure
        if err := os.MkdirAll(filepath.Dir(destPath), 0755); err != nil {
            return err
        }
        
        // Create file
        f, err := os.Create(destPath)
        if err != nil {
            return err
        }
        
        // Restore file content
        if err := s.RestoreFile(sessionID, fileMetadata.Path, f); err != nil {
            f.Close()
            return err
        }
        
        f.Close()
        
        // Restore timestamps
        os.Chtimes(destPath, fileMetadata.AccessTime, fileMetadata.ModifiedTime)
    }
    
    return nil
}

// Instant VM boot (mount backup as virtual disk)
func (s *RestoreService) MountAsVirtualDisk(sessionID string) (*VirtualDisk, error) {
    // Get disk metadata
    diskMetadata, err := s.MetadataRepo.GetDiskLayout(sessionID)
    if err != nil {
        return nil, err
    }
    
    // Create NBD (Network Block Device) or FUSE server
    vdisk := &VirtualDisk{
        SessionID:  sessionID,
        Size:       diskMetadata.Size,
        BlockSize:  4096,
        RestoreSvc: s,
        BlockCache: make(map[int64][]byte),
    }
    
    // Start NBD server
    go vdisk.ServeNBD()
    
    return vdisk, nil
}

type VirtualDisk struct {
    SessionID  string
    Size       int64
    BlockSize  int
    RestoreSvc *RestoreService
    BlockCache map[int64][]byte
    mu         sync.RWMutex
}

// Serve block device requests (on-demand loading)
func (v *VirtualDisk) ReadBlock(blockNum int64) ([]byte, error) {
    // Check cache first
    v.mu.RLock()
    if data, ok := v.BlockCache[blockNum]; ok {
        v.mu.RUnlock()
        return data, nil
    }
    v.mu.RUnlock()
    
    // Calculate which chunks contain this block
    offset := blockNum * int64(v.BlockSize)
    
    // Fetch chunks and extract block
    data, err := v.RestoreSvc.ReadDataAtOffset(v.SessionID, offset, v.BlockSize)
    if err != nil {
        return nil, err
    }
    
    // Cache block
    v.mu.Lock()
    v.BlockCache[blockNum] = data
    v.mu.Unlock()
    
    return data, nil
}
```

### 4. Backup Verification Service

```go
package verification

type VerificationService struct {
    MetadataRepo    BackupMetadataRepository
    RestoreService  *restore.RestoreService
    VMManager       *VMManager
}

// Automated backup verification (runs nightly)
func (v *VerificationService) VerifyBackups() {
    ticker := time.NewTicker(24 * time.Hour)
    defer ticker.Stop()
    
    for {
        select {
        case <-ticker.C:
            v.runVerification()
        }
    }
}

func (v *VerificationService) runVerification() {
    log.Info("Starting backup verification")
    
    // Get backups from last 24 hours
    sessions, err := v.MetadataRepo.GetRecentSessions(24 * time.Hour)
    if err != nil {
        log.Error("Failed to get sessions", err)
        return
    }
    
    // Verify a sample (10% of backups)
    sampleSize := len(sessions) / 10
    if sampleSize == 0 {
        sampleSize = len(sessions)
    }
    
    // Random sample
    rand.Shuffle(len(sessions), func(i, j int) {
        sessions[i], sessions[j] = sessions[j], sessions[i]
    })
    
    for i := 0; i < sampleSize; i++ {
        session := sessions[i]
        
        result := v.verifyBackupSession(session)
        
        // Save verification result
        if err := v.MetadataRepo.SaveVerificationResult(result); err != nil {
            log.Error("Failed to save verification result", err)
        }
        
        // Alert if verification failed
        if !result.Success {
            v.alertVerificationFailure(session, result)
        }
    }
    
    log.Info("Backup verification completed")
}

func (v *VerificationService) verifyBackupSession(session *BackupSession) *VerificationResult {
    result := &VerificationResult{
        SessionID:  session.SessionID,
        VerifiedAt: time.Now(),
    }
    
    // Test 1: Metadata integrity
    if err := v.verifyMetadata(session); err != nil {
        result.Success = false
        result.Error = err.Error()
        return result
    }
    
    // Test 2: Random file restore test
    if err := v.testFileRestore(session); err != nil {
        result.Success = false
        result.Error = err.Error()
        return result
    }
    
    // Test 3: Screenshot verification (for system backups)
    if session.BackupType == "system" {
        screenshot, err := v.screenshotVerification(session)
        if err != nil {
            result.Success = false
            result.Error = err.Error()
            return result
        }
        result.Screenshot = screenshot
    }
    
    result.Success = true
    return result
}

// Screenshot verification: Boot VM from backup and take screenshot
func (v *VerificationService) screenshotVerification(session *BackupSession) (string, error) {
    log.Info("Running screenshot verification for", session.SessionID)
    
    // Mount backup as virtual disk
    vdisk, err := v.RestoreService.MountAsVirtualDisk(session.SessionID)
    if err != nil {
        return "", err
    }
    defer vdisk.Unmount()
    
    // Boot VM from virtual disk
    vm, err := v.VMManager.BootFromDisk(vdisk)
    if err != nil {
        return "", err
    }
    defer vm.Shutdown()
    
    // Wait for boot (30 seconds timeout)
    if err := vm.WaitForBoot(30 * time.Second); err != nil {
        return "", err
    }
    
    // Take screenshot
    screenshot, err := vm.TakeScreenshot()
    if err != nil {
        return "", err
    }
    
    // Upload screenshot to S3
    screenshotURL := fmt.Sprintf("s3://cove-verification/screenshots/%s.png", 
                                 session.SessionID)
    
    v.uploadScreenshot(screenshotURL, screenshot)
    
    log.Info("Screenshot verification completed", session.SessionID)
    
    return screenshotURL, nil
}

func (v *VerificationService) testFileRestore(session *BackupSession) error {
    // Get list of files in backup
    files, err := v.MetadataRepo.GetFilesInSession(session.SessionID)
    if err != nil {
        return err
    }
    
    if len(files) == 0 {
        return nil  // No files to test
    }
    
    // Test random file
    randomFile := files[rand.Intn(len(files))]
    
    // Restore to memory
    var buf bytes.Buffer
    if err := v.RestoreService.RestoreFile(session.SessionID, 
                                           randomFile.Path, 
                                           &buf); err != nil {
        return err
    }
    
    // Verify size matches
    if int64(buf.Len()) != randomFile.Size {
        return fmt.Errorf("size mismatch: expected %d, got %d", 
                          randomFile.Size, buf.Len())
    }
    
    log.Info("File restore test passed", randomFile.Path)
    return nil
}
```

---

## Trade-offs and Tech Choices

### 1. Deduplication: Global vs Per-Device

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Global (cross-device)** | Maximum space savings (60:1 ratio), Efficient | Security concerns, Complexity | ✅ **Chosen with safeguards** |
| **Per-tenant** | Better isolation, Simpler | Less efficient (~10:1 ratio) | ❌ Lower savings |
| **Per-device** | Simplest, Most secure | Minimal savings (~2:1 ratio) | ❌ Wasteful |

**Decision**: **Global deduplication with encryption before dedup**
- Hash computed on compressed data (before encryption)
- Each tenant has unique encryption key
- Chunks encrypted separately per tenant (no cross-tenant access)
- Immense storage savings (60:1 ratio claimed by N-able)

**Security Model**:
```
Tenant A: Data → Compress → Hash: "abc123" → Encrypt with Key_A → Store
Tenant B: Same Data → Compress → Hash: "abc123" (same) → Encrypt with Key_B → Store

Result: Same chunk hash detected, but stored twice (encrypted differently)

Trade-off: Slightly less dedup for security, but still ~50:1 effective ratio
```

### 2. Chunking: Fixed vs Content-Defined

| Approach | Pros | Cons | Decision |
|----------|------|------|----------|
| **Fixed-size chunks** | Simple, Fast | Poor dedup (files shift) | ❌ Inefficient |
| **Content-Defined (CDC)** | Best dedup, Handles inserts/deletes | More CPU | ✅ **Chosen** |

**Decision**: **Content-Defined Chunking (Rabin fingerprinting)**
- Average chunk size: 4 MB
- Boundaries detected by rolling hash
- Inserts in middle of file don't break all chunks
- Better deduplication across versions

### 3. Storage: S3 Standard vs Glacier

| Storage Class | Pros | Cons | Use Case |
|---------------|------|------|----------|
| **S3 Standard** | Fast access, No retrieval delay | Expensive for archive | ✅ Recent backups (< 30 days) |
| **S3 IA** | Lower cost, Fast access | Retrieval fees | ✅ 30-90 days |
| **S3 Glacier** | Very cheap | Hours retrieval time | ✅ 90+ days |
| **S3 Glacier Deep** | Cheapest | 12 hours retrieval | ✅ Yearly backups |

**Decision**: **S3 Intelligent Tiering + Lifecycle Policies**
```
0-30 days: S3 Standard (fast restore)
30-90 days: S3 Infrequent Access
90+ days: S3 Glacier Flexible Retrieval
1+ year: S3 Glacier Deep Archive

Auto-transition based on access patterns
```

### 4. Backup Schedule: Frequent vs Daily

| Frequency | RPO | Pros | Cons | Decision |
|-----------|-----|------|------|----------|
| **Every 15 min** | 15 min | Minimal data loss | High bandwidth, storage | ✅ **Optional for critical** |
| **Hourly** | 1 hour | Good balance | Some data loss risk | ✅ **Standard** |
| **Daily** | 24 hours | Low overhead | Significant data loss risk | ✅ **Basic tier** |

**Decision**: **Flexible scheduling with deduplication**
- Basic: Daily backups
- Standard: Hourly backups
- Premium: 15-minute backups (only possible with 60:1 dedup)

---

## Failure Scenarios and Bottlenecks

### 1. Backup Job Failure Mid-Upload

**Scenario**: Network interruption during backup

**Handling**:
```go
// Agent implements checkpoint/resume
func (a *BackupAgent) resumeBackup(session *BackupSession) error {
    // Get list of already uploaded chunks
    uploadedChunks, err := a.APIClient.GetUploadedChunks(session.SessionID)
    if err != nil {
        return err
    }
    
    // Build set for fast lookup
    uploaded := make(map[string]bool)
    for _, hash := range uploadedChunks {
        uploaded[hash] = true
    }
    
    // Resume from where we left off
    for _, file := range session.PendingFiles {
        chunks := a.prepareChunks(file)
        
        // Filter out already uploaded chunks
        newChunks := []Chunk{}
        for _, chunk := range chunks {
            if !uploaded[chunk.Hash] {
                newChunks = append(newChunks, chunk)
            }
        }
        
        // Upload remaining chunks
        a.uploadChunks(newChunks)
    }
}
```

### 2. Deduplication Index Corruption

**Scenario**: Chunk metadata becomes inconsistent

**Mitigation**:
```
1. Background Verification:
   - Periodically verify chunk hashes match S3 objects
   - Rebuild index from S3 if corruption detected
   
2. Redundancy:
   - Store chunk index in both Redis and DocumentDB
   - Cross-check on suspicious operations
   
3. Audit Log:
   - Track all chunk operations
   - Enable rollback if needed
   
4. Chunk Validation:
   - Verify checksum on upload and download
   - Detect bit rot
```

### 3. Restore Performance Degradation

**Scenario**: Restoring TBs of data takes too long

**Optimizations**:
```
1. Parallel Chunk Retrieval:
   - Fetch multiple chunks concurrently
   - S3 Transfer Acceleration for global users
   
2. Caching:
   - Cache frequently accessed chunks (e.g., OS files)
   - Reduce redundant S3 calls
   
3. Restore Prioritization:
   - Restore critical files first
   - Background restore for less important data
   
4. Instant Recovery:
   - Boot VM from backup immediately
   - Lazy-load blocks on demand
   - Migrate to local storage in background
```

### 4. Thundering Herd (Mass Restore Event)

**Scenario**: Ransomware attack → 1000 customers restore simultaneously

**Mitigation**:
```
1. S3 Burst Capacity:
   - S3 auto-scales
   - Use S3 Transfer Acceleration
   
2. Queue Management:
   - Priority queue for restores
   - Critical systems first
   
3. Resource Allocation:
   - Pre-provision restore infrastructure
   - Auto-scale ECS tasks
   
4. Assisted Recovery:
   - N-able recovery team helps coordinate
   - Provide local restore options (ship hard drives if needed)
```

---

## Summary

**Key Achievements**:
- **60:1 Deduplication**: Content-defined chunking + global dedup
- **15-minute RPO**: Frequent backups enabled by dedup
- **Immutable Backups**: S3 Object Lock (ransomware protection)
- **Instant Recovery**: Mount backup as virtual disk
- **99.999999999% Durability**: S3 Standard storage

**Technologies**:
- Go (agents and services)
- AWS S3 + Object Lock (immutable storage)
- Redis (dedup cache)
- DocumentDB (metadata)
- Content-Defined Chunking (Rabin fingerprinting)
- Zstandard compression
- AES-256 encryption
- S3 Intelligent Tiering (cost optimization)

This demonstrates enterprise-grade backup capabilities that N-able Cove Data Protection operates at scale, protecting millions of devices with air-gapped, immutable, cloud-first architecture.
