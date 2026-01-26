# Dropbox/Google Drive - High Level Design (Cloud File Storage & Sync)

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

1. **File Operations**
   - Upload files (any size, any type)
   - Download files
   - Delete files
   - Update/overwrite files
   - Move and rename files

2. **Folder Management**
   - Create, delete, rename folders
   - Nested folder hierarchy
   - Move files between folders

3. **Synchronization**
   - Sync files across multiple devices
   - Real-time sync when online
   - Offline access with later sync
   - Conflict resolution

4. **Sharing & Collaboration**
   - Share files/folders with users
   - Public links with optional password
   - Permission levels (view, edit)
   - Real-time collaboration

5. **Version History**
   - Track file versions
   - Restore previous versions
   - View change history

### Non-Functional Requirements

1. **Scale**: 500M+ users, 15B+ files
2. **Availability**: 99.99% uptime
3. **Durability**: 99.999999999% (11 nines) - No data loss
4. **Latency**: Sync within seconds for small files
5. **Bandwidth**: Efficient transfer (chunking, delta sync)
6. **Consistency**: Eventual consistency with conflict resolution

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Total users: 500 Million
- Daily Active Users (DAU): 100 Million
- Files per user: 200 average
- Total files: 500M * 200 = 100 Billion files

Operations:
- Uploads per day: 1 Billion
- Downloads per day: 2 Billion
- Sync operations: 5 Billion/day

- Uploads/sec: 1B / 86400 ≈ 11,500/sec
- Downloads/sec: 2B / 86400 ≈ 23,000/sec
```

### Storage Estimates

```
File Storage:
- Average file size: 1 MB
- Total storage: 100B files * 1 MB = 100 PB
- With replication (3x): 300 PB
- Daily new data: 1B uploads * 1 MB = 1 PB/day

Metadata:
- Per file: 1 KB (name, path, owner, timestamps, etc.)
- Total: 100B * 1 KB = 100 TB
```

### Bandwidth Estimates

```
Upload:
- 11,500/sec * 1 MB = 11.5 GB/sec

Download:
- 23,000/sec * 1 MB = 23 GB/sec

Total: ~35 GB/sec
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                            │
│                    (Desktop App, Mobile App, Web Browser)                           │
│                                                                                     │
│  ┌──────────────────────────────────────────────────────────────────────────────┐  │
│  │                         LOCAL SYNC ENGINE                                     │  │
│  │  - File watcher (inotify/FSEvents)                                           │  │
│  │  - Chunking engine                                                            │  │
│  │  - Local database (SQLite)                                                    │  │
│  │  - Conflict resolver                                                          │  │
│  └──────────────────────────────────────────────────────────────────────────────┘  │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                              HTTPS / WebSocket
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LOAD BALANCER                                           │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
              ┌──────────────────────────┴──────────────────────────┐
              │                                                      │
              ▼                                                      ▼
┌──────────────────────────────────┐           ┌──────────────────────────────────────┐
│         API SERVERS               │           │        BLOCK SERVERS                 │
│                                   │           │                                      │
│  - Authentication                 │           │  - Handle chunk uploads              │
│  - Metadata operations            │           │  - Handle chunk downloads            │
│  - Sharing/permissions            │           │  - Compression                       │
│  - Folder operations              │           │  - Deduplication                     │
└───────────────┬───────────────────┘           └─────────────────┬────────────────────┘
                │                                                  │
                │                                                  │
┌───────────────┴───────────────────────────────────────────────────┴──────────────────┐
│                              MESSAGE QUEUE (Kafka)                                    │
│                                                                                      │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐            │
│  │   Sync       │  │   Index      │  │   Share      │  │  Notification│            │
│  │   Events     │  │   Events     │  │   Events     │  │    Events    │            │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘            │
│                                                                                      │
└──────────────────────────────────────────────────────────────────────────────────────┘
                                         │
         ┌───────────────────────────────┼───────────────────────────────┐
         ▼                               ▼                               ▼
┌─────────────────────┐    ┌─────────────────────────┐    ┌─────────────────────────┐
│    SYNC SERVICE     │    │    INDEXING SERVICE     │    │   NOTIFICATION SERVICE  │
│                     │    │                         │    │                         │
│ - Sync state machine│    │ - Full-text search      │    │ - Push notifications    │
│ - Conflict detection│    │ - File indexing         │    │ - WebSocket updates     │
│ - Delta computation │    │ - Tag management        │    │ - Email notifications   │
└─────────────────────┘    └─────────────────────────┘    └─────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├──────────────────┬──────────────────┬──────────────────┬────────────────────────────┤
│   METADATA DB    │   BLOCK STORE    │   SEARCH INDEX   │        CACHE               │
│   (MySQL/        │   (S3/GCS/       │  (Elasticsearch) │       (Redis)              │
│    Vitess)       │    Azure Blob)   │                  │                            │
│                  │                  │                  │                            │
│ - File metadata  │ - File chunks    │ - File content   │ - User sessions            │
│ - User data      │ - Deduplicated   │ - Metadata       │ - Hot metadata             │
│ - Permissions    │   blocks         │ - Permissions    │ - Block locations          │
│ - Folder tree    │ - Versioned      │                  │                            │
└──────────────────┴──────────────────┴──────────────────┴────────────────────────────┘
```

### Core Components

1. **API Servers**: Handle metadata operations, authentication
2. **Block Servers**: Handle file chunk upload/download
3. **Sync Service**: Manage synchronization across devices
4. **Metadata Database**: Store file/folder structure
5. **Block Store**: Store actual file content (chunked)
6. **Notification Service**: Real-time sync notifications

---

## Request Flows

### File Upload Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│ Client │  │   API   │  │  Block  │  │   Block   │  │Metadata │  │  Sync   │
│        │  │ Server  │  │ Server  │  │   Store   │  │   DB    │  │ Service │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ 1. Request upload       │             │             │            │
    │───────────>│            │             │             │            │
    │            │            │             │             │            │
    │ 2. Return upload URL + chunk info     │             │            │
    │<───────────│            │             │             │            │
    │            │            │             │             │            │
    │ 3. Split file into chunks             │             │            │
    │ (client-side)           │             │             │            │
    │            │            │             │             │            │
    │ 4. For each chunk:      │             │             │            │
    │ Calculate hash          │             │             │            │
    │────────────────────────>│             │             │            │
    │            │            │ Check if exists           │            │
    │            │            │────────────>│             │            │
    │            │            │             │             │            │
    │            │            │<────────────│             │            │
    │            │            │ (If new)    │             │            │
    │            │            │ Store chunk │             │            │
    │            │            │────────────>│             │            │
    │<────────────────────────│ ACK         │             │            │
    │            │            │             │             │            │
    │ 5. After all chunks:    │             │             │            │
    │ Commit file metadata    │             │             │            │
    │───────────>│            │             │             │            │
    │            │───────────────────────────────────────>│            │
    │            │            │             │             │            │
    │            │ 6. Notify sync service   │             │            │
    │            │────────────────────────────────────────────────────>│
    │            │            │             │             │            │
    │<───────────│ Success    │             │             │            │
```

### File Sync Flow (Multi-Device)

```
┌────────┐  ┌────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐
│Device A│  │Device B│  │   Sync  │  │ Metadata  │  │  Block  │
│        │  │        │  │ Service │  │    DB     │  │  Store  │
└───┬────┘  └───┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘
    │           │            │             │             │
    │ File changed           │             │             │
    │──────────────────────>│             │             │
    │           │            │ Store change│             │
    │           │            │────────────>│             │
    │           │            │             │             │
    │           │            │ Get Device B subscription │
    │           │            │────────────>│             │
    │           │            │             │             │
    │           │ Push notification        │             │
    │           │<───────────│             │             │
    │           │            │             │             │
    │           │ Request changes          │             │
    │           │───────────>│             │             │
    │           │            │ Get delta   │             │
    │           │            │────────────>│             │
    │           │            │             │             │
    │           │<───────────│ Changes list│             │
    │           │            │             │             │
    │           │ Download changed chunks  │             │
    │           │─────────────────────────────────────>│
    │           │            │             │             │
    │           │<─────────────────────────────────────│
    │           │ Chunks     │             │             │
    │           │            │             │             │
    │           │ Reconstruct file         │             │
    │           │ (local)    │             │             │
    │           │            │             │             │
    │           │ ACK sync complete        │             │
    │           │───────────>│             │             │
```

### Conflict Resolution Flow

```
┌────────┐  ┌────────┐  ┌─────────┐
│Device A│  │Device B│  │   Sync  │
│        │  │        │  │ Service │
└───┬────┘  └───┬────┘  └────┬────┘
    │           │            │
    │ Edit file.txt (v1→v2)  │
    │──────────────────────>│
    │           │            │
    │           │ Edit file.txt (v1→v2')
    │           │───────────>│
    │           │            │
    │           │            │ Detect conflict!
    │           │            │ (Both based on v1)
    │           │            │
    │           │            │ Resolution Strategy:
    │           │            │ 1. Last-write-wins, OR
    │           │            │ 2. Create conflict copy
    │           │            │
    │           │            │ (Using conflict copy)
    │<──────────────────────│
    │ Your version: file.txt │
    │ Conflict: file (conflict).txt
    │           │            │
    │           │<───────────│
    │           │ Your version: file.txt
    │           │ Conflict: file (conflict).txt
```

---

## Detailed Component Design

### 1. Chunking and Deduplication

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          FILE CHUNKING SYSTEM                                        │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Why Chunking:                                                                      │
│  - Resume interrupted uploads                                                        │
│  - Efficient delta sync (only changed chunks)                                       │
│  - Deduplication across files                                                       │
│  - Parallel upload/download                                                         │
│                                                                                     │
│  Chunking Strategies:                                                               │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  FIXED-SIZE CHUNKING                                                        │   │
│  │                                                                              │   │
│  │  File: [████████████████████████████████████████████████]                   │   │
│  │        └──4MB──┘└──4MB──┘└──4MB──┘└──4MB──┘└──4MB──┘└2MB┘                   │   │
│  │                                                                              │   │
│  │  Pros: Simple, predictable                                                  │   │
│  │  Cons: Insert at beginning → all chunks change                              │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  CONTENT-DEFINED CHUNKING (CDC) - Recommended                               │   │
│  │                                                                              │   │
│  │  Uses rolling hash (Rabin fingerprint) to find chunk boundaries             │   │
│  │  Boundary when: hash(window) mod M == 0                                     │   │
│  │                                                                              │   │
│  │  File: [████████|███████████|████|█████████████|███████]                    │   │
│  │        └─3.2MB──┘└───4.8MB──┘└1MB┘└────5.1MB───┘└─2.9MB─┘                   │   │
│  │                                                                              │   │
│  │  Pros: Insert doesn't affect other chunks, better dedup                     │   │
│  │  Cons: Variable sizes, more complex                                         │   │
│  │                                                                              │   │
│  │  Parameters:                                                                 │   │
│  │  - Min chunk: 1 MB (prevent tiny chunks)                                    │   │
│  │  - Max chunk: 8 MB (cap for very uniform data)                              │   │
│  │  - Average: 4 MB (target size)                                              │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Deduplication:                                                                     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  1. Compute SHA-256 hash of each chunk                                      │   │
│  │  2. Check if hash exists in block store                                     │   │
│  │  3. If exists: Reference existing block (no upload)                         │   │
│  │  4. If new: Upload and store                                                │   │
│  │                                                                              │   │
│  │  Example:                                                                    │   │
│  │  User A uploads file.zip with chunks [A, B, C]                             │   │
│  │  User B uploads same file (hash matches)                                    │   │
│  │  → No new storage needed, just reference [A, B, C]                         │   │
│  │                                                                              │   │
│  │  Storage savings: 20-50% typically                                          │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class ContentDefinedChunker:
    def __init__(self):
        self.min_chunk = 1 * 1024 * 1024   # 1 MB
        self.max_chunk = 8 * 1024 * 1024   # 8 MB
        self.target = 4 * 1024 * 1024      # 4 MB target
        self.mask = (1 << 22) - 1          # For ~4MB average
        
    def chunk_file(self, file_path: str) -> List[Chunk]:
        chunks = []
        
        with open(file_path, 'rb') as f:
            buffer = b''
            window = RollingHash(48)  # 48-byte window
            
            while True:
                byte = f.read(1)
                if not byte:
                    # End of file - emit final chunk
                    if buffer:
                        chunks.append(self.create_chunk(buffer))
                    break
                
                buffer += byte
                window.update(byte)
                
                # Check for chunk boundary
                if len(buffer) >= self.min_chunk:
                    if len(buffer) >= self.max_chunk or \
                       (window.hash() & self.mask) == 0:
                        chunks.append(self.create_chunk(buffer))
                        buffer = b''
                        window.reset()
        
        return chunks
    
    def create_chunk(self, data: bytes) -> Chunk:
        return Chunk(
            hash=hashlib.sha256(data).hexdigest(),
            size=len(data),
            data=data
        )

class BlockStore:
    async def store_chunk(self, chunk: Chunk) -> bool:
        # Check if already exists (deduplication)
        if await self.exists(chunk.hash):
            return False  # Already stored
        
        # Compress and encrypt
        compressed = zlib.compress(chunk.data)
        encrypted = self.encrypt(compressed)
        
        # Store in object storage
        await self.s3.put_object(
            Bucket='blocks',
            Key=chunk.hash,
            Body=encrypted
        )
        
        return True
```

### 2. Sync Protocol (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          SYNC PROTOCOL                                               │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Client State Machine:                                                              │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │         ┌──────────┐                                                        │   │
│  │         │  IDLE    │◄────────────────────────────────────┐                  │   │
│  │         └────┬─────┘                                     │                  │   │
│  │              │ File change detected                      │                  │   │
│  │              ▼                                           │                  │   │
│  │         ┌──────────┐                                     │                  │   │
│  │         │ SCANNING │ Detect all local changes            │                  │   │
│  │         └────┬─────┘                                     │                  │   │
│  │              │                                           │                  │   │
│  │              ▼                                           │                  │   │
│  │         ┌──────────┐                                     │                  │   │
│  │         │ CHUNKING │ Split changed files                 │                  │   │
│  │         └────┬─────┘                                     │                  │   │
│  │              │                                           │                  │   │
│  │              ▼                                           │                  │   │
│  │         ┌──────────┐                                     │                  │   │
│  │         │UPLOADING │ Upload new chunks                   │                  │   │
│  │         └────┬─────┘                                     │                  │   │
│  │              │                                           │                  │   │
│  │              ▼                                           │                  │   │
│  │         ┌──────────┐                                     │                  │   │
│  │         │COMMITTING│ Update server metadata              │                  │   │
│  │         └────┬─────┘                                     │                  │   │
│  │              │                                           │                  │   │
│  │              ▼                                           │                  │   │
│  │         ┌──────────┐                                     │                  │   │
│  │         │CONFIRMING│ Wait for server ACK ────────────────┘                  │   │
│  │         └──────────┘                                                        │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Sync Metadata:                                                                     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Each file has:                                                              │   │
│  │  {                                                                           │   │
│  │    "file_id": "uuid",                                                       │   │
│  │    "path": "/documents/report.pdf",                                         │   │
│  │    "version": 42,                                                           │   │
│  │    "server_modified": "2024-01-15T10:30:00Z",                              │   │
│  │    "local_modified": "2024-01-15T10:30:00Z",                               │   │
│  │    "content_hash": "sha256:abc123...",                                     │   │
│  │    "blocks": [                                                              │   │
│  │      {"hash": "sha256:block1...", "offset": 0, "size": 4194304},          │   │
│  │      {"hash": "sha256:block2...", "offset": 4194304, "size": 3145728}     │   │
│  │    ],                                                                       │   │
│  │    "size": 7340032                                                         │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Delta Sync:                                                                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Old file: [Block A][Block B][Block C][Block D]                            │   │
│  │  New file: [Block A][Block B'][Block C][Block D][Block E]                  │   │
│  │                        ↑                         ↑                          │   │
│  │                     Changed                    Added                        │   │
│  │                                                                              │   │
│  │  Upload needed: Only Block B' and Block E                                   │   │
│  │  Bandwidth saved: 60% (3 out of 5 blocks reused)                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Metadata Database Design

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          METADATA SCHEMA                                             │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  -- Users table                                                              │   │
│  │  CREATE TABLE users (                                                        │   │
│  │      user_id BIGINT PRIMARY KEY,                                            │   │
│  │      email VARCHAR(255) UNIQUE,                                             │   │
│  │      root_folder_id BIGINT,                                                 │   │
│  │      storage_quota BIGINT,                                                  │   │
│  │      storage_used BIGINT,                                                   │   │
│  │      created_at TIMESTAMP                                                    │   │
│  │  );                                                                          │   │
│  │                                                                              │   │
│  │  -- Files/Folders (unified)                                                 │   │
│  │  CREATE TABLE entries (                                                      │   │
│  │      entry_id BIGINT PRIMARY KEY,                                           │   │
│  │      parent_id BIGINT,                                                      │   │
│  │      owner_id BIGINT,                                                       │   │
│  │      name VARCHAR(255),                                                     │   │
│  │      is_folder BOOLEAN,                                                     │   │
│  │      size BIGINT,                                                           │   │
│  │      content_hash VARCHAR(64),                                              │   │
│  │      version INT,                                                           │   │
│  │      created_at TIMESTAMP,                                                  │   │
│  │      modified_at TIMESTAMP,                                                 │   │
│  │      deleted_at TIMESTAMP,  -- Soft delete                                  │   │
│  │                                                                              │   │
│  │      INDEX (parent_id, name),                                               │   │
│  │      INDEX (owner_id, modified_at)                                          │   │
│  │  );                                                                          │   │
│  │                                                                              │   │
│  │  -- File blocks (chunk references)                                          │   │
│  │  CREATE TABLE file_blocks (                                                 │   │
│  │      entry_id BIGINT,                                                       │   │
│  │      version INT,                                                           │   │
│  │      block_hash VARCHAR(64),                                                │   │
│  │      block_offset BIGINT,                                                   │   │
│  │      block_size INT,                                                        │   │
│  │      sequence INT,                                                          │   │
│  │                                                                              │   │
│  │      PRIMARY KEY (entry_id, version, sequence)                              │   │
│  │  );                                                                          │   │
│  │                                                                              │   │
│  │  -- Sharing                                                                 │   │
│  │  CREATE TABLE shares (                                                       │   │
│  │      share_id BIGINT PRIMARY KEY,                                           │   │
│  │      entry_id BIGINT,                                                       │   │
│  │      shared_with_id BIGINT,  -- NULL for public link                       │   │
│  │      permission ENUM('view', 'edit'),                                       │   │
│  │      link_token VARCHAR(64),                                                │   │
│  │      password_hash VARCHAR(255),                                            │   │
│  │      expires_at TIMESTAMP                                                   │   │
│  │  );                                                                          │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Sharding Strategy:                                                                 │
│  - Shard by user_id (all user's files on same shard)                              │
│  - Enables efficient "list folder" queries                                         │
│  - Cross-user shares handled via async replication                                 │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### 1. Chunking Strategy

| Approach | Dedup Efficiency | Complexity | Use Case |
|----------|------------------|------------|----------|
| Fixed 4MB | Low | Simple | Simple systems |
| Content-defined | High | Complex | Production |
| Rsync (rolling) | Highest | Very complex | Binary diff |

**Decision:** Content-defined chunking (CDC) with Rabin fingerprinting

### 2. Conflict Resolution

| Strategy | Data Loss | UX | Complexity |
|----------|-----------|-----|------------|
| Last-write-wins | Possible | Simple | Low |
| Conflict copies | None | More files | Medium |
| 3-way merge | None | Complex | High |

**Decision:** Conflict copies for documents, last-write-wins for settings

### 3. Storage Backend

| Option | Durability | Cost | Latency |
|--------|------------|------|---------|
| S3 | 11 nines | $$ | Higher |
| GCS | 11 nines | $$ | Medium |
| Self-hosted | Lower | $$$ | Lowest |

**Decision:** S3/GCS for blocks, self-managed for metadata

---

## Failure Scenarios and Bottlenecks

### 1. Upload Interruption

```
┌─────────────────────────────────────────────────────────────────┐
│              RESUMABLE UPLOAD                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Problem: Upload fails at 80% complete                          │
│                                                                 │
│  Solution: Chunk-based resume                                   │
│                                                                 │
│  1. Client tracks uploaded chunks locally                       │
│  2. On resume: Query server for confirmed chunks                │
│  3. Upload only missing chunks                                  │
│  4. Commit when all chunks present                              │
│                                                                 │
│  Implementation:                                                 │
│  POST /upload/start → upload_session_id                         │
│  PUT /upload/{session}/chunk/{index}                            │
│  GET /upload/{session}/status → {chunks: [0,1,2], missing: [3]} │
│  POST /upload/{session}/commit                                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Sync Storm (Many devices)

```
Problem: User has 10 devices, file change triggers 10 sync operations

Mitigation:
1. Debounce notifications (batch changes for 5 seconds)
2. Smart polling (exponential backoff when idle)
3. Priority sync (active device gets priority)
4. Delta sync (only changed chunks)
```

### 3. Metadata Database Overload

```
Problem: Hot user with millions of files

Solutions:
1. Caching: Redis cache for folder listings
2. Pagination: Cursor-based pagination
3. Sharding: User-based sharding
4. Denormalization: Pre-compute folder sizes
```

---

## Future Improvements

### 1. Smart Sync

```
- Only sync recently accessed files locally
- Stream others on-demand
- Predictive pre-fetching based on usage patterns
```

### 2. Real-time Collaboration

```
- Operational Transform for documents
- Live cursors and presence
- Integrated with Google Docs-like editing
```

### 3. AI Features

```
- Automatic file organization
- Content-based search
- Duplicate detection
- Smart sharing suggestions
```

---

## Interviewer Questions & Answers

### Q1: How do you handle file synchronization across multiple devices?

**Answer:**

**Architecture:**
```
Device A → Sync Service → Notification Service → Device B
              ↓
         Metadata DB
```

**Sync Protocol:**
1. **Local watcher** detects file change (inotify/FSEvents)
2. **Chunking engine** splits file, computes hashes
3. **Delta upload**: Only upload new/changed chunks
4. **Metadata update**: Server records new version
5. **Push notification**: Notify other devices via WebSocket
6. **Pull sync**: Other devices fetch changes

**Conflict handling:**
```python
def sync_file(local_file, server_version):
    if local_file.version == server_version.version:
        # No conflict - straightforward sync
        return sync_local_changes()
    
    if local_file.base_version == server_version.version:
        # Server unchanged, upload local changes
        return upload_local_changes()
    
    # Both changed - conflict!
    if local_file.base_version < server_version.version:
        # Create conflict copy
        rename(local_file, f"{name} (conflict).{ext}")
        download(server_version)
        notify_user("Conflict detected")
```

---

### Q2: Explain your chunking strategy and why you chose it.

**Answer:**

**Content-Defined Chunking (CDC):**

Uses rolling hash to find natural boundaries in file content.

**Why CDC over fixed-size:**
```
Scenario: Insert 10 bytes at beginning of 100MB file

Fixed-size (4MB chunks):
- All 25 chunks shift
- Must re-upload entire file

CDC:
- Only first chunk boundary shifts
- 24/25 chunks unchanged
- Upload only 1 new chunk

Result: 96% bandwidth savings
```

**Algorithm:**
```python
def find_chunk_boundaries(file):
    boundaries = []
    window = RollingHash(48)  # 48-byte window
    
    for pos, byte in enumerate(file):
        window.update(byte)
        
        if pos >= MIN_CHUNK_SIZE:
            if window.hash() % AVG_CHUNK_SIZE == MAGIC:
                boundaries.append(pos)
                window.reset()
        
        if pos - last_boundary >= MAX_CHUNK_SIZE:
            boundaries.append(pos)
            window.reset()
    
    return boundaries
```

**Parameters chosen:**
- Min: 1MB (prevent tiny chunks)
- Max: 8MB (limit memory usage)
- Average: 4MB (good balance)

---

### Q3: How do you achieve 11 nines of durability?

**Answer:**

**Multi-layer protection:**

1. **Object Storage (S3/GCS):**
   - Already provides 11 nines
   - Automatic replication across AZs
   - Erasure coding

2. **Additional measures:**
   ```
   - Cross-region replication for critical data
   - Immutable storage (versioned blocks)
   - Checksums at every layer
   - Regular integrity verification
   ```

3. **Block-level durability:**
   ```python
   class BlockStore:
       def store_block(self, block):
           # Compute checksum
           checksum = sha256(block.data)
           
           # Store in primary region
           await s3_primary.put(block.hash, block.data)
           
           # Async replicate to DR region
           await queue.publish("replicate", {
               "hash": block.hash,
               "checksum": checksum
           })
           
           # Verify write
           stored = await s3_primary.head(block.hash)
           assert stored.etag == checksum
   ```

4. **Metadata durability:**
   - MySQL with synchronous replication
   - Point-in-time recovery
   - Regular backups to cold storage

---

### Q4: How do you handle deduplication?

**Answer:**

**Block-level deduplication:**

```python
async def upload_file(user_id, file):
    chunks = chunk_file(file)
    
    for chunk in chunks:
        hash = sha256(chunk.data)
        
        # Check if block exists
        if await block_store.exists(hash):
            # Reference existing block
            await metadata_db.add_reference(user_id, file_id, hash)
        else:
            # Upload new block
            await block_store.store(hash, chunk.data)
            await metadata_db.add_block(user_id, file_id, hash)
    
    return file_id
```

**Dedup scope:**
- **User-level:** Safe, no privacy concerns
- **Global:** Better savings, but security considerations

**Security for global dedup:**
```
1. Convergent encryption:
   key = hash(plaintext)
   ciphertext = encrypt(plaintext, key)
   
2. Only dedup if user proves possession:
   - Client sends hash
   - Server challenges with random block offsets
   - Client must provide correct bytes
```

**Savings:** 20-50% typically, 70%+ for backup scenarios

---

### Q5: How do you implement file sharing?

**Answer:**

**Share types:**
1. **User share:** Share with specific users
2. **Link share:** Anyone with link can access
3. **Public:** Indexed, searchable

**Data model:**
```sql
CREATE TABLE shares (
    share_id BIGINT PRIMARY KEY,
    entry_id BIGINT,
    shared_by BIGINT,
    shared_with BIGINT NULL,  -- NULL for link share
    permission ENUM('view', 'comment', 'edit'),
    link_token VARCHAR(64) UNIQUE,
    password_hash VARCHAR(255),
    expires_at TIMESTAMP,
    download_count INT DEFAULT 0,
    max_downloads INT
);
```

**Permission check:**
```python
async def check_access(user_id, entry_id, required_permission):
    # Check ownership
    entry = await get_entry(entry_id)
    if entry.owner_id == user_id:
        return True
    
    # Check direct share
    share = await get_share(entry_id, user_id)
    if share and permission_level(share.permission) >= required_permission:
        return True
    
    # Check parent folder shares (inheritance)
    parent = entry.parent_id
    while parent:
        share = await get_share(parent, user_id)
        if share and share.permission >= required_permission:
            return True
        parent = (await get_entry(parent)).parent_id
    
    return False
```

---

### Q6: How do you handle large file uploads (10GB+)?

**Answer:**

**Multipart upload strategy:**

```
┌─────────────────────────────────────────────────────────────────┐
│                 LARGE FILE UPLOAD                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  1. Initiate upload session                                     │
│     POST /upload/init                                           │
│     → {session_id, chunk_size, upload_urls[]}                  │
│                                                                 │
│  2. Upload chunks in parallel (5-10 concurrent)                 │
│     PUT /upload/{session}/chunk/{n}                            │
│     Headers: Content-MD5 for verification                       │
│                                                                 │
│  3. Track progress                                              │
│     Client maintains: {uploaded: [0,1,2], pending: [3,4,5]}    │
│     Server maintains: {confirmed: [0,1,2]}                     │
│                                                                 │
│  4. Resume on failure                                           │
│     GET /upload/{session}/status                               │
│     Resume from last confirmed chunk                            │
│                                                                 │
│  5. Complete upload                                              │
│     POST /upload/{session}/complete                            │
│     Server verifies all chunks, assembles metadata              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Bandwidth optimization:**
- Client-side compression for text files
- Skip upload for deduplicated blocks
- Bandwidth throttling during peak hours

---

### Q7: How do you design the desktop sync client?

**Answer:**

**Architecture:**
```
┌─────────────────────────────────────────────────────────────────┐
│                 DESKTOP CLIENT ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                    FILE WATCHER                            │ │
│  │  - inotify (Linux)                                        │ │
│  │  - FSEvents (macOS)                                       │ │
│  │  - ReadDirectoryChangesW (Windows)                        │ │
│  └─────────────────────────┬─────────────────────────────────┘ │
│                            │                                   │
│                            ▼                                   │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                  EVENT QUEUE                               │ │
│  │  - Debounce rapid changes                                 │ │
│  │  - Coalesce related events                                │ │
│  │  - Prioritize by recency                                  │ │
│  └─────────────────────────┬─────────────────────────────────┘ │
│                            │                                   │
│                            ▼                                   │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                  SYNC ENGINE                               │ │
│  │  - Chunking (CDC algorithm)                               │ │
│  │  - Hash computation                                       │ │
│  │  - Upload/download queue                                  │ │
│  │  - Conflict resolution                                    │ │
│  └─────────────────────────┬─────────────────────────────────┘ │
│                            │                                   │
│                            ▼                                   │
│  ┌───────────────────────────────────────────────────────────┐ │
│  │                  LOCAL DATABASE (SQLite)                   │ │
│  │  - File metadata cache                                    │ │
│  │  - Sync state                                             │ │
│  │  - Pending changes queue                                  │ │
│  └───────────────────────────────────────────────────────────┘ │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key decisions:**
- SQLite for local state (reliable, embedded)
- Background service (survives app close)
- Tray icon for status (non-intrusive)

---

### Q8: How do you implement version history?

**Answer:**

**Storage strategy:**
```python
# Each file version stored as list of block references
# Blocks are immutable and deduplicated

class FileVersion:
    file_id: str
    version: int
    created_at: datetime
    blocks: List[BlockRef]  # References to immutable blocks
    size: int
    modified_by: str

# Old versions share unchanged blocks with current version
# Only new/modified blocks consume additional storage
```

**Retention policy:**
```
Free tier:
- 30 days of versions
- Automatic cleanup

Pro tier:
- 180 days of versions
- Manual restore

Business tier:
- Unlimited history
- Legal hold support
```

**Restore flow:**
```python
async def restore_version(file_id, target_version):
    # Get target version metadata
    version = await get_version(file_id, target_version)
    
    # Create new version with same blocks
    new_version = await create_version(
        file_id,
        blocks=version.blocks,  # Reuse same blocks
        parent_version=get_current_version(file_id)
    )
    
    # Notify sync service
    await notify_sync(file_id, new_version)
```

---

### Q9: How do you handle offline mode?

**Answer:**

**Offline capabilities:**

1. **Local cache:**
   - SQLite database with full folder structure
   - Pinned files stored locally
   - Recent files cached

2. **Change tracking:**
   ```python
   class OfflineChangeTracker:
       def record_change(self, file_path, change_type):
           self.db.insert(
               "pending_changes",
               {
                   "path": file_path,
                   "type": change_type,  # create, modify, delete
                   "timestamp": now(),
                   "synced": False
               }
           )
   ```

3. **Sync on reconnect:**
   ```python
   async def sync_offline_changes(self):
       # Get server state
       server_cursor = await api.get_cursor()
       
       # Get local pending changes
       local_changes = self.db.get_pending_changes()
       
       # Get remote changes since last sync
       remote_changes = await api.get_changes(self.last_cursor)
       
       # Resolve conflicts
       for local in local_changes:
           remote = find_conflicting(local, remote_changes)
           if remote:
               # Conflict - create conflict copy
               resolve_conflict(local, remote)
           else:
               # No conflict - upload
               await upload_change(local)
       
       # Download remote changes
       for remote in remote_changes:
           if not is_conflicting(remote, local_changes):
               await download_change(remote)
   ```

---

### Q10: How do you ensure security for stored files?

**Answer:**

**Multi-layer security:**

1. **Encryption at rest:**
   ```
   - AES-256 encryption for all blocks
   - Unique key per file (or per user)
   - Keys stored in KMS (AWS KMS, HashiCorp Vault)
   ```

2. **Encryption in transit:**
   ```
   - TLS 1.3 for all connections
   - Certificate pinning in clients
   ```

3. **Zero-knowledge option:**
   ```python
   # Client-side encryption
   class ZeroKnowledgeClient:
       def encrypt_file(self, file, user_key):
           # Derive file key from user key
           file_key = kdf(user_key, file.id)
           
           # Encrypt before chunking
           encrypted = aes_encrypt(file.content, file_key)
           
           # Chunk and upload encrypted data
           chunks = chunk(encrypted)
           upload(chunks)
           
           # Server never sees plaintext or key
   ```

4. **Access control:**
   ```
   - OAuth 2.0 / OIDC for authentication
   - Scoped tokens for API access
   - IP allowlisting for enterprise
   - 2FA enforcement
   ```

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│           DROPBOX/GDRIVE - FINAL ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 500M users, 100B files, 300 PB storage                 │
│                                                                 │
│  Core Components:                                               │
│  ├── Desktop/Mobile Client - File watcher, sync engine         │
│  ├── API Servers - Metadata operations, auth                   │
│  ├── Block Servers - Chunk upload/download                     │
│  ├── Sync Service - Cross-device synchronization               │
│  ├── Notification Service - Real-time push                     │
│  └── Search Service - Full-text file search                    │
│                                                                 │
│  Data Stores:                                                   │
│  ├── MySQL/Vitess - Metadata (sharded by user)                 │
│  ├── S3/GCS - Block storage (11 nines durability)              │
│  ├── Redis - Session cache, hot metadata                       │
│  └── Elasticsearch - File search index                         │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── Content-defined chunking (CDC) for efficient delta sync   │
│  ├── Block-level deduplication (20-50% storage savings)        │
│  ├── Conflict copies (no data loss)                            │
│  ├── WebSocket for real-time sync notifications                │
│  └── Client-side encryption option for zero-knowledge          │
│                                                                 │
│  SLA: 99.99% availability, 11 nines durability                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
