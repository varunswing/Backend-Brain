# Google Docs - High Level Design (Real-Time Collaborative Editing)

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

1. **Document Management**
   - Create, read, update, delete documents
   - Rich text editing (formatting, images, tables)
   - Document organization (folders, search)
   - Version history and restore

2. **Real-Time Collaboration**
   - Multiple users editing simultaneously
   - See other users' cursors and selections
   - Real-time conflict resolution
   - Presence indicators (who's viewing)

3. **Sharing & Permissions**
   - Share with specific users or publicly
   - Permission levels (view, comment, edit)
   - Access control and revocation

4. **Comments & Suggestions**
   - Inline comments on text
   - Suggestion mode (track changes)
   - Comment threads and resolution

5. **Offline Support**
   - Edit while offline
   - Sync when reconnected
   - Conflict handling

### Non-Functional Requirements

1. **Scale**: 1B+ documents, 100M+ daily active users
2. **Availability**: 99.99% uptime
3. **Latency**: <100ms for local edits, <300ms for sync
4. **Consistency**: Strong eventual consistency for document state
5. **Durability**: Zero data loss
6. **Concurrency**: Support 100+ simultaneous editors

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Total users: 1 Billion
- Daily Active Users (DAU): 100 Million
- Concurrent users (peak): 20 Million
- Concurrent editors per document (avg): 3

Documents:
- Total documents: 10 Billion
- Active documents (last 30 days): 500 Million
- New documents per day: 50 Million

Operations:
- Edits per active user per day: 500
- Total edits per day: 100M * 500 = 50 Billion
- Edits per second: 50B / 86400 ≈ 580,000 edits/sec
```

### Storage Estimates

```
Document Storage:
- Average document size: 50 KB (content)
- With history: 500 KB average (10 versions)
- 10B documents * 500 KB = 5 PB

Operation Log:
- Each operation: 100 bytes
- Operations per day: 50 Billion
- Daily storage: 50B * 100 = 5 TB/day
- Retention (30 days): 150 TB

Metadata:
- Per document: 1 KB
- Total: 10B * 1 KB = 10 TB
```

### Bandwidth Estimates

```
Operations:
- 580K/sec * 100 bytes = 58 MB/sec incoming
- Broadcast to collaborators (3x): 174 MB/sec outgoing

Document loads:
- 10M loads/hour * 50 KB = 139 GB/hour = 39 MB/sec

Total: ~300 MB/sec sustained
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                            │
│                        (Web, iOS, Android, Desktop)                                 │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                          WebSocket / HTTP│
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LOAD BALANCER                                           │
│                          (Sticky sessions by doc_id)                                │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    │                                         │
                    ▼                                         ▼
┌───────────────────────────────────────┐    ┌───────────────────────────────────────┐
│        COLLABORATION SERVERS           │    │          API SERVERS                  │
│       (WebSocket Connections)          │    │        (HTTP REST)                    │
│                                        │    │                                       │
│  - Handle real-time edits              │    │  - Document CRUD                      │
│  - Manage document sessions            │    │  - User authentication                │
│  - Broadcast operations                │    │  - Sharing/permissions                │
│  - Conflict resolution (OT/CRDT)       │    │  - Search                             │
└───────────────────┬───────────────────┘    └───────────────────────────────────────┘
                    │
                    │
┌───────────────────┴───────────────────────────────────────────────────────────────┐
│                           DOCUMENT SESSION MANAGER                                 │
│                                                                                   │
│  ┌─────────────────────────────────────────────────────────────────────────────┐ │
│  │                           REDIS CLUSTER                                      │ │
│  │                                                                              │ │
│  │  doc_session:{doc_id} → {                                                   │ │
│  │    server_id: "server_1",                                                   │ │
│  │    active_users: ["user_1", "user_2"],                                     │ │
│  │    version: 12345,                                                          │ │
│  │    pending_operations: [...]                                                │ │
│  │  }                                                                          │ │
│  │                                                                              │ │
│  └─────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                   │
└───────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE QUEUE (Kafka)                                   │
│   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                    │
│   │   Operation     │  │    Document     │  │   Notification  │                    │
│   │     Log         │  │     Events      │  │     Events      │                    │
│   └─────────────────┘  └─────────────────┘  └─────────────────┘                    │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├───────────────────┬───────────────────┬───────────────────┬─────────────────────────┤
│   DOCUMENT STORE  │   OPERATION LOG   │   METADATA DB     │   SEARCH INDEX          │
│    (Spanner/      │   (Cassandra)     │   (Spanner)       │  (Elasticsearch)        │
│    CockroachDB)   │                   │                   │                         │
│                   │                   │                   │                         │
│ - Document content│ - Operation history│ - User data      │ - Full-text search      │
│ - Current state   │ - Undo/redo       │ - Permissions    │ - Title search          │
│ - Snapshots       │ - Conflict logs   │ - Sharing        │                         │
└───────────────────┴───────────────────┴───────────────────┴─────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           OBJECT STORAGE (GCS/S3)                                   │
│                                                                                     │
│  - Document snapshots                                                               │
│  - Embedded images                                                                  │
│  - Attachments                                                                      │
│  - Export files (PDF, DOCX)                                                        │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Components

1. **Collaboration Server**: Handle real-time editing sessions
2. **Operation Transform Engine**: Resolve concurrent edits
3. **Document Session Manager**: Track active editing sessions
4. **Version Control**: Maintain document history
5. **Sync Service**: Handle offline sync
6. **Search Service**: Full-text document search

---

## Request Flows

### Opening a Document for Editing

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│ Client │  │ LB  │  │ Collab  │  │  Session  │  │   Doc   │  │  Redis  │
│        │  │     │  │ Server  │  │  Manager  │  │   DB    │  │         │
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │          │          │             │             │            │
    │ Connect  │          │             │             │            │
    │ (WS)     │          │             │             │            │
    │─────────>│          │             │             │            │
    │          │─────────>│             │             │            │
    │          │          │ Check session             │            │
    │          │          │────────────────────────────────────────>
    │          │          │             │             │            │
    │          │          │             │             │ Session    │
    │          │          │             │             │ exists?    │
    │          │          │<───────────────────────────────────────│
    │          │          │             │             │            │
    │          │          │ (If no session: create)   │            │
    │          │          │ Load document             │            │
    │          │          │───────────────────────────>│            │
    │          │          │             │             │            │
    │          │          │<───────────────────────────│            │
    │          │          │             │             │            │
    │          │          │ Register in session       │            │
    │          │          │────────────────────────────────────────>
    │          │          │             │             │            │
    │<─────────│<─────────│             │             │            │
    │ Document │          │             │             │            │
    │ + Version│          │             │             │            │
    │          │          │             │             │            │
    │ Subscribe to updates│             │             │            │
    │─────────>│─────────>│             │             │            │
```

### Editing Flow (Operational Transform)

```
┌────────┐  ┌────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐  ┌────────┐
│Client A│  │Client B│  │  Collab   │  │   OT    │  │  Redis  │  │  Doc   │
│        │  │        │  │  Server   │  │ Engine  │  │ (State) │  │   DB   │
└───┬────┘  └───┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘  └───┬────┘
    │           │             │             │            │           │
    │ Edit: Insert "Hello" at pos 0        │            │           │
    │ (version: 5)           │             │            │           │
    │──────────────────────>│             │            │           │
    │           │             │             │            │           │
    │           │             │ Get current version     │           │
    │           │             │───────────────────────>│           │
    │           │             │             │            │           │
    │           │             │<───────────────────────│           │
    │           │             │ version: 5  │            │           │
    │           │             │             │            │           │
    │           │             │ Transform op│            │           │
    │           │             │────────────>│            │           │
    │           │             │             │            │           │
    │           │             │<────────────│            │           │
    │           │             │ Transformed │            │           │
    │           │             │             │            │           │
    │           │             │ Update version          │           │
    │           │             │───────────────────────>│           │
    │           │             │             │            │           │
    │           │             │ Persist operation       │           │
    │           │             │─────────────────────────────────────>
    │           │             │             │            │           │
    │           │             │ Broadcast to others     │           │
    │           │             │────────────────────────────────────>│
    │           │<────────────│             │            │           │
    │<──────────│ ACK (v: 6)  │             │            │           │
    │           │ Op: Insert "Hello"        │            │           │
```

### Concurrent Edit Resolution

```
┌────────┐                    ┌───────────┐                    ┌────────┐
│Client A│                    │  Server   │                    │Client B│
└───┬────┘                    └─────┬─────┘                    └───┬────┘
    │                               │                              │
    │ Both start at version 5      │                              │
    │ Document: "abc"              │                              │
    │                               │                              │
    │ Insert "X" at pos 1          │       Insert "Y" at pos 2   │
    │ (local: "aXbc")              │       (local: "abYc")        │
    │                               │                              │
    │──────────────────────────────>│                              │
    │                               │<─────────────────────────────│
    │                               │                              │
    │                    Server receives A's op first:             │
    │                    "abc" → "aXbc" (version 6)                │
    │                               │                              │
    │                    Now apply B's op:                         │
    │                    Original: Insert "Y" at pos 2             │
    │                    Transform: Insert "Y" at pos 3            │
    │                    (because X was inserted before pos 2)     │
    │                    "aXbc" → "aXbYc" (version 7)              │
    │                               │                              │
    │<──────────────────────────────│                              │
    │ ACK: version 6               │                              │
    │ Incoming: Insert Y at pos 3  │                              │
    │                               │─────────────────────────────>│
    │                               │ ACK: version 7               │
    │                               │ Incoming: Insert X at pos 1  │
    │                               │                              │
    │ Final: "aXbYc"               │              Final: "aXbYc"  │
```

---

## Detailed Component Design

### 1. Operational Transformation (OT) Engine

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          OPERATIONAL TRANSFORMATION                                  │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Core Concept:                                                                      │
│  - Each edit is an "operation" (insert, delete, retain)                            │
│  - Operations can be transformed against each other                                 │
│  - Server maintains single source of truth                                          │
│                                                                                     │
│  Operation Types:                                                                   │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  INSERT: { type: "insert", pos: 5, text: "hello" }                          │   │
│  │  DELETE: { type: "delete", pos: 5, length: 3 }                              │   │
│  │  RETAIN: { type: "retain", count: 10 }  // Skip characters                  │   │
│  │                                                                              │   │
│  │  Compound Operation:                                                         │   │
│  │  [retain(5), insert("hello"), retain(10), delete(3)]                        │   │
│  │  = Skip 5 chars, insert "hello", skip 10, delete 3                          │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Transform Function:                                                                │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  transform(op_a, op_b) → (op_a', op_b')                                     │   │
│  │                                                                              │   │
│  │  Where: apply(apply(doc, op_a), op_b') = apply(apply(doc, op_b), op_a')    │   │
│  │                                                                              │   │
│  │  Example:                                                                    │   │
│  │  doc = "abc"                                                                │   │
│  │  op_a = insert("X", pos=1)  // User A inserts at position 1                │   │
│  │  op_b = insert("Y", pos=2)  // User B inserts at position 2                │   │
│  │                                                                              │   │
│  │  transform(op_a, op_b):                                                     │   │
│  │    op_a' = insert("X", pos=1)  // Unchanged                                 │   │
│  │    op_b' = insert("Y", pos=3)  // Shifted by 1 (length of op_a)            │   │
│  │                                                                              │   │
│  │  Both paths lead to: "aXbYc"                                                │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Server Algorithm:                                                                  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  def handle_operation(client_op, client_version):                           │   │
│  │      server_version = get_current_version()                                 │   │
│  │                                                                              │   │
│  │      if client_version < server_version:                                    │   │
│  │          # Client is behind - need to transform                             │   │
│  │          missed_ops = get_ops_since(client_version)                         │   │
│  │          for missed_op in missed_ops:                                       │   │
│  │              client_op = transform(client_op, missed_op)                    │   │
│  │                                                                              │   │
│  │      # Apply transformed operation                                          │   │
│  │      apply_to_document(client_op)                                           │   │
│  │      save_operation(client_op, server_version + 1)                          │   │
│  │                                                                              │   │
│  │      # Broadcast to other clients                                           │   │
│  │      broadcast(client_op, server_version + 1)                               │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class OTEngine:
    def transform(self, op_a: Operation, op_b: Operation) -> Tuple[Operation, Operation]:
        """Transform two concurrent operations."""
        if op_a.type == "insert" and op_b.type == "insert":
            return self._transform_insert_insert(op_a, op_b)
        elif op_a.type == "insert" and op_b.type == "delete":
            return self._transform_insert_delete(op_a, op_b)
        elif op_a.type == "delete" and op_b.type == "insert":
            return self._transform_delete_insert(op_a, op_b)
        elif op_a.type == "delete" and op_b.type == "delete":
            return self._transform_delete_delete(op_a, op_b)
    
    def _transform_insert_insert(self, op_a, op_b):
        """Both operations are inserts."""
        if op_a.pos <= op_b.pos:
            # A comes first - B needs to shift
            new_op_b = op_b.copy()
            new_op_b.pos += len(op_a.text)
            return op_a, new_op_b
        else:
            # B comes first - A needs to shift
            new_op_a = op_a.copy()
            new_op_a.pos += len(op_b.text)
            return new_op_a, op_b
    
    def _transform_insert_delete(self, insert_op, delete_op):
        """Transform insert against delete."""
        if insert_op.pos <= delete_op.pos:
            # Insert before delete - delete shifts
            new_delete = delete_op.copy()
            new_delete.pos += len(insert_op.text)
            return insert_op, new_delete
        elif insert_op.pos >= delete_op.pos + delete_op.length:
            # Insert after deleted region - insert shifts back
            new_insert = insert_op.copy()
            new_insert.pos -= delete_op.length
            return new_insert, delete_op
        else:
            # Insert within deleted region - complex case
            # Split the insert or adjust positions
            new_insert = insert_op.copy()
            new_insert.pos = delete_op.pos
            return new_insert, delete_op

class CollaborationServer:
    def __init__(self):
        self.ot_engine = OTEngine()
        self.sessions = {}  # doc_id → DocumentSession
    
    async def handle_operation(self, doc_id: str, client_id: str, 
                                op: Operation, client_version: int):
        session = self.sessions[doc_id]
        
        async with session.lock:
            server_version = session.version
            
            # Transform against any operations client hasn't seen
            if client_version < server_version:
                missed_ops = session.get_ops_since(client_version)
                for missed_op in missed_ops:
                    op, _ = self.ot_engine.transform(op, missed_op)
            
            # Apply and save
            session.apply(op)
            session.version += 1
            await self.save_operation(doc_id, op, session.version)
            
            # Acknowledge sender
            await self.send_to_client(client_id, {
                "type": "ack",
                "version": session.version
            })
            
            # Broadcast to others
            for other_client in session.clients:
                if other_client != client_id:
                    await self.send_to_client(other_client, {
                        "type": "operation",
                        "op": op.to_dict(),
                        "version": session.version
                    })
```

### 2. CRDT Alternative (Conflict-free Replicated Data Types)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    CRDT                                              │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Alternative to OT - No transformation needed                                       │
│                                                                                     │
│  For text: Use character-wise unique IDs                                           │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Each character has:                                                         │   │
│  │  - Unique ID (user_id + sequence_number + timestamp)                        │   │
│  │  - Position: Between two other character IDs                                │   │
│  │                                                                              │   │
│  │  Example: "abc"                                                              │   │
│  │  a → (user1, 1, t1)                                                         │   │
│  │  b → (user1, 2, t2)                                                         │   │
│  │  c → (user1, 3, t3)                                                         │   │
│  │                                                                              │   │
│  │  User A inserts "X" between a and b:                                        │   │
│  │  X → (userA, 1, t4), position: (after a, before b)                          │   │
│  │                                                                              │   │
│  │  User B inserts "Y" between a and b:                                        │   │
│  │  Y → (userB, 1, t5), position: (after a, before b)                          │   │
│  │                                                                              │   │
│  │  Merge: Compare IDs to determine order                                       │   │
│  │  If userA < userB: "aXYbc"                                                  │   │
│  │  Result is deterministic regardless of order received                        │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Popular CRDT for text:                                                            │
│  - Yjs (used by many collaborative editors)                                        │
│  - Automerge                                                                        │
│  - RGA (Replicated Growable Array)                                                 │
│                                                                                     │
│  Pros:                                                                              │
│  - No server needed for conflict resolution                                        │
│  - Better for offline/P2P scenarios                                                │
│  - Simpler mental model                                                            │
│                                                                                     │
│  Cons:                                                                              │
│  - Higher memory overhead (character IDs)                                          │
│  - Tombstones for deleted characters                                               │
│  - Complex garbage collection                                                       │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Document Storage and Versioning

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          DOCUMENT STORAGE                                            │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Storage Strategy: Snapshot + Operation Log                                         │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  SNAPSHOT (stored periodically):                                             │   │
│  │  {                                                                           │   │
│  │    "doc_id": "abc123",                                                      │   │
│  │    "version": 1000,                                                         │   │
│  │    "content": "Full document text...",                                      │   │
│  │    "created_at": "2024-01-15T10:00:00Z"                                    │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  │  OPERATION LOG (append-only):                                               │   │
│  │  [                                                                           │   │
│  │    { "version": 1001, "op": {...}, "user": "user1", "ts": "..." },         │   │
│  │    { "version": 1002, "op": {...}, "user": "user2", "ts": "..." },         │   │
│  │    ...                                                                       │   │
│  │  ]                                                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Loading a Document:                                                                │
│  1. Load latest snapshot                                                            │
│  2. Replay operations since snapshot                                                │
│  3. Result: Current document state                                                  │
│                                                                                     │
│  Snapshot Strategy:                                                                 │
│  - Every N operations (e.g., 100)                                                  │
│  - Every T time (e.g., 5 minutes)                                                  │
│  - On significant events (save, export)                                            │
│                                                                                     │
│  Version History:                                                                   │
│  - Keep all operations for undo/redo                                               │
│  - Store named versions (user-triggered saves)                                     │
│  - Automatic versions every hour                                                    │
│  - Retention: Keep last 30 days detailed, then daily snapshots                     │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class DocumentStorage:
    def __init__(self):
        self.snapshot_store = SnapshotStore()  # GCS/S3
        self.operation_log = OperationLog()     # Cassandra
        
    async def load_document(self, doc_id: str, version: int = None) -> Document:
        # Get latest snapshot
        snapshot = await self.snapshot_store.get_latest(doc_id, before_version=version)
        
        # Get operations since snapshot
        ops = await self.operation_log.get_ops(
            doc_id, 
            from_version=snapshot.version, 
            to_version=version
        )
        
        # Reconstruct document
        doc = Document(content=snapshot.content, version=snapshot.version)
        for op in ops:
            doc.apply(op)
        
        return doc
    
    async def save_operation(self, doc_id: str, op: Operation, version: int):
        # Append to operation log
        await self.operation_log.append(doc_id, op, version)
        
        # Check if snapshot needed
        if version % 100 == 0:
            await self.create_snapshot(doc_id)
    
    async def create_snapshot(self, doc_id: str):
        doc = await self.load_document(doc_id)
        await self.snapshot_store.save(doc_id, doc.content, doc.version)
    
    async def get_version_history(self, doc_id: str) -> List[Version]:
        # Get snapshots + significant operations
        snapshots = await self.snapshot_store.list(doc_id)
        named_versions = await self.get_named_versions(doc_id)
        
        return sorted(snapshots + named_versions, key=lambda v: v.timestamp)
```

---

## Trade-offs and Tech Choices

### 1. OT vs CRDT

| Aspect | OT (Operational Transform) | CRDT |
|--------|---------------------------|------|
| Server requirement | Required | Optional (P2P possible) |
| Memory | Lower | Higher (character IDs) |
| Complexity | Transform functions | Data structure design |
| Latency | Server round-trip | Local-first |
| Offline | Limited | Excellent |
| History | Easy (operation log) | Complex |

**Decision:** Google Docs uses OT. For offline-first apps, CRDT may be better.

### 2. Session Management

| Approach | Pros | Cons |
|----------|------|------|
| Sticky sessions | Simple, no sync | Single point of failure |
| Distributed (Redis) | Fault tolerant | More complexity |
| Per-document leader | Good consistency | Leader election needed |

**Decision:** Per-document leader with Redis for state sharing

### 3. Storage Backend

| Data | Storage | Reason |
|------|---------|--------|
| Document content | Spanner/CockroachDB | Strong consistency, global |
| Operations | Cassandra | High write throughput |
| Snapshots | GCS/S3 | Cost-effective blobs |
| Session state | Redis | In-memory, fast |

---

## Failure Scenarios and Bottlenecks

### 1. Collaboration Server Failure

```
┌─────────────────────────────────────────────────────────────────┐
│              SERVER FAILURE HANDLING                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Detection:                                                      │
│  - Health check failure                                         │
│  - WebSocket disconnection                                      │
│  - Redis session timeout                                        │
│                                                                 │
│  Recovery:                                                       │
│  1. Client detects disconnect                                   │
│  2. Client reconnects to load balancer                          │
│  3. LB routes to new server                                     │
│  4. New server loads document from storage                      │
│  5. Client sends local pending operations                       │
│  6. Server transforms and applies                               │
│  7. Normal editing resumes                                      │
│                                                                 │
│  Data preserved:                                                 │
│  - Acknowledged operations are persisted                        │
│  - Client keeps unacknowledged operations                       │
│  - At most few seconds of work at risk                          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Operation Conflict Storm

```
Problem: 100 users editing same paragraph simultaneously

Mitigation:
1. Rate limiting per client (100 ops/sec max)
2. Operation batching (send every 100ms)
3. Paragraph-level locks for intensive edits
4. Split document into sections

Client-side:
- Buffer rapid keystrokes
- Combine adjacent operations
- Debounce cursor updates
```

### 3. Large Document Performance

```
Problem: 1000-page document is slow to load

Solutions:
1. Lazy loading
   - Load first few pages
   - Load more on scroll
   
2. Chunking
   - Split document into sections
   - Each section has own operation stream
   
3. Pagination
   - Only track operations for visible pages
   - Full document on explicit save
```

---

## Future Improvements

### 1. AI-Powered Features

- Smart compose (sentence completion)
- Grammar and style suggestions
- Automatic formatting
- Content summarization

### 2. Advanced Collaboration

- Voice/video alongside document
- Cursor awareness across apps
- Real-time comments resolution
- Suggested changes workflow

### 3. Offline-First Architecture

- Full CRDT implementation
- P2P sync when possible
- Background sync service

---

## Interviewer Questions & Answers

### Q1: How do you handle concurrent edits from multiple users?

**Answer:**

**Approach: Operational Transformation (OT)**

When two users edit simultaneously, their operations might conflict. OT resolves this:

```
User A types "X" at position 1
User B types "Y" at position 2
Original: "abc"

Without OT:
A's view: "aXbc" 
B's view: "abYc"
Merged wrong: "aXbYc" or "aXYbc" (inconsistent)

With OT:
1. Server receives A's operation first
   Apply: "abc" → "aXbc"

2. Server receives B's operation (based on "abc")
   Original: insert Y at pos 2
   Transform: insert Y at pos 3 (because X was inserted before)
   Apply: "aXbc" → "aXbYc"

3. Server sends transformed B to A:
   A applies: insert Y at pos 3
   Result: "aXbYc"

4. Server sends A to B (transformed for B's context):
   B applies: insert X at pos 1
   Result: "aXbYc"

Both converge to same state!
```

The transform function ensures that regardless of order operations are received, all clients converge to the same state.

---

### Q2: How do you store document history and enable version restore?

**Answer:**

**Storage strategy: Snapshots + Operation Log**

```
Document Timeline:
───────────────────────────────────────────────────────>
│           │           │           │
Snapshot 1  Ops 1-100   Snapshot 2  Ops 101-150 ...
(v: 0)                  (v: 100)    
```

**Loading a version:**
```python
def load_version(doc_id, target_version):
    # Find nearest snapshot before target
    snapshot = get_snapshot_before(doc_id, target_version)
    
    # Get operations from snapshot to target
    ops = get_operations(doc_id, snapshot.version, target_version)
    
    # Replay operations on snapshot
    doc = snapshot.content
    for op in ops:
        doc = apply_operation(doc, op)
    
    return doc
```

**Snapshot policy:**
- Every 100 operations
- Every 5 minutes during active editing
- On user-triggered "save"

**History features:**
- "See version history" - list snapshots + named versions
- "See changes by X" - filter operations by user
- "Restore to version" - load snapshot, create as new version

---

### Q3: How do you handle offline editing?

**Answer:**

**Client-side storage:**
```javascript
// Store pending operations locally
localStorage.setItem('pendingOps', JSON.stringify(operations));

// Store document state
indexedDB.put('documents', { id: docId, content: docContent });
```

**Sync on reconnect:**
```python
class OfflineSync:
    def sync(self, doc_id, local_version, pending_ops):
        # Get server state
        server_version = get_current_version(doc_id)
        
        if local_version < server_version:
            # Fetch missed operations
            missed_ops = get_ops_since(doc_id, local_version)
            
            # Transform pending operations against missed
            for missed in missed_ops:
                for i, pending in enumerate(pending_ops):
                    pending_ops[i], _ = transform(pending, missed)
        
        # Apply transformed operations
        for op in pending_ops:
            apply_operation(doc_id, op)
        
        # Return updated state to client
        return get_document(doc_id)
```

**Conflict resolution:**
- Most conflicts auto-resolve via OT
- Rare conflicts: Show diff to user
- Very rare: Create "conflict copy"

---

### Q4: How do you implement real-time cursor positions?

**Answer:**

**Cursor as metadata (not document content):**
```json
{
  "type": "cursor_update",
  "user_id": "user_123",
  "doc_id": "doc_abc",
  "cursor": {
    "position": 150,
    "selection_start": 150,
    "selection_end": 200,
    "color": "#FF5733"
  }
}
```

**Broadcasting:**
- Don't persist cursors (ephemeral)
- Throttle updates (50ms debounce)
- Only send to active collaborators

**Position mapping:**
```python
def transform_cursor(cursor_pos, operation):
    if operation.type == "insert" and operation.pos <= cursor_pos:
        return cursor_pos + len(operation.text)
    elif operation.type == "delete" and operation.pos < cursor_pos:
        deleted = min(operation.length, cursor_pos - operation.pos)
        return cursor_pos - deleted
    return cursor_pos
```

**Rendering:**
- Each user gets unique color
- Show name label near cursor
- Fade out after 30 seconds of inactivity

---

### Q5: How do you handle a document with 100 concurrent editors?

**Answer:**

**Scalability strategies:**

1. **Server-side:**
   - Dedicated server per hot document
   - Operation batching (process every 50ms)
   - In-memory document state

2. **Operation optimization:**
   ```python
   def batch_operations(ops):
       # Combine consecutive inserts at same position
       # Combine overlapping deletes
       # Result: fewer operations to transform
   ```

3. **Client-side:**
   - Debounce rapid typing (50ms)
   - Local prediction (optimistic UI)
   - Batch cursor updates

4. **Presence optimization:**
   - Only show nearby cursors
   - Aggregate distant users ("5 others viewing")

5. **Document sharding:**
   - Large documents split into sections
   - Each section independent operation stream
   - Cross-section operations are rare

**Performance targets:**
- 100 users: <100ms latency
- 1000 users: Degrade to polling mode for some
- Show warning: "Many editors - some delays expected"

---

### Q6: How do you implement undo/redo in a collaborative environment?

**Answer:**

**Challenge:** User A's undo shouldn't undo User B's changes

**Solution: Undo only your own operations**

```python
class UndoManager:
    def __init__(self, user_id):
        self.user_id = user_id
        self.undo_stack = []  # User's operations
        self.redo_stack = []
    
    def record_operation(self, op, version):
        if op.user_id == self.user_id:
            self.undo_stack.append((op, version))
            self.redo_stack.clear()
    
    def undo(self, current_version):
        if not self.undo_stack:
            return None
        
        op, op_version = self.undo_stack.pop()
        
        # Transform inverse operation against all subsequent ops
        inverse = get_inverse(op)
        
        for v in range(op_version + 1, current_version + 1):
            other_op = get_operation(v)
            inverse, _ = transform(inverse, other_op)
        
        self.redo_stack.append((op, inverse))
        return inverse
```

**Inverse operations:**
- Insert → Delete (same position, length)
- Delete → Insert (same position, deleted text)

**Edge cases:**
- Undoing deleted text that was modified by others
- Undoing within already-deleted region
- Solution: Best-effort reconstruction, show warning if imperfect

---

### Q7: How do you ensure data consistency across distributed servers?

**Answer:**

**Architecture: Leader per document**

```
Document A → Server 1 (leader)
Document B → Server 2 (leader)
Document C → Server 1 (leader)
```

**Leader election:**
- When document opened, check Redis for existing leader
- If no leader, server claims leadership (atomic operation)
- Leader handles all operations for that document

**Consistency guarantees:**
```python
class DocumentSession:
    async def claim_leadership(self, doc_id):
        # Atomic operation - only one succeeds
        result = await redis.set(
            f"doc_leader:{doc_id}",
            self.server_id,
            nx=True,  # Only if not exists
            ex=30     # 30 second lease
        )
        
        if result:
            # Start heartbeat to maintain lease
            self.start_lease_renewal(doc_id)
            return True
        return False
    
    async def maintain_lease(self, doc_id):
        while True:
            await asyncio.sleep(10)
            await redis.expire(f"doc_leader:{doc_id}", 30)
```

**Failure handling:**
- If leader dies, lease expires
- Next request triggers new leader election
- New leader loads from durable storage

---

### Q8: How do you implement commenting on specific text?

**Answer:**

**Comment anchoring:**
```json
{
  "comment_id": "comment_123",
  "doc_id": "doc_abc",
  "anchor": {
    "type": "text_range",
    "start_marker": "marker_start_123",
    "end_marker": "marker_end_123"
  },
  "content": "Should we rephrase this?",
  "author": "user_456",
  "created_at": "2024-01-15T10:00:00Z",
  "resolved": false,
  "thread": [...]
}
```

**Marker-based anchoring:**
- Insert invisible markers at comment boundaries
- Markers are part of document (go through OT)
- Comment references markers by ID

**When text is edited:**
```
Original: "Hello [marker_start]world[marker_end]!"
Comment on "world"

User deletes "world":
Result: "Hello [marker_start][marker_end]!"
Comment now anchors to empty range

User inserts in the middle:
"Hello [marker_start]won[marker_end]!"
Comment now anchors to "won"
```

**Edge cases:**
- Deleted anchor: Mark comment as "orphaned"
- Split anchor: Show warning, let user re-anchor

---

### Q9: How do you implement document search?

**Answer:**

**Architecture:**
```
Document Edit → Kafka → Search Indexer → Elasticsearch
```

**Index structure:**
```json
{
  "doc_id": "abc123",
  "title": "Q3 Planning Document",
  "content": "Full text content...",
  "owner": "user_456",
  "collaborators": ["user_789", "user_012"],
  "last_modified": "2024-01-15T10:00:00Z",
  "word_count": 1500,
  "headings": ["Introduction", "Goals", "Timeline"]
}
```

**Real-time indexing:**
```python
class SearchIndexer:
    def __init__(self):
        self.es = Elasticsearch()
        self.pending = {}  # doc_id → pending content
    
    async def handle_operation(self, doc_id, op):
        # Debounce - don't index every keystroke
        self.pending[doc_id] = op
        
        # Schedule index update
        if not self.scheduled.get(doc_id):
            self.scheduled[doc_id] = True
            asyncio.create_task(self.delayed_index(doc_id))
    
    async def delayed_index(self, doc_id):
        await asyncio.sleep(5)  # Wait for more edits
        
        doc = await load_document(doc_id)
        await self.es.index(
            index="documents",
            id=doc_id,
            document={
                "title": doc.title,
                "content": doc.content,
                "last_modified": datetime.now()
            }
        )
        self.scheduled[doc_id] = False
```

**Search features:**
- Full-text search in content
- Filter by owner, date, shared with me
- Autocomplete for titles
- Recent documents (no search)

---

### Q10: How do you handle permissions and sharing?

**Answer:**

**Permission model:**
```python
class Permission:
    VIEWER = "viewer"      # Read only
    COMMENTER = "commenter" # Read + comment
    EDITOR = "editor"       # Full edit access
    OWNER = "owner"         # Edit + manage permissions
```

**Storage:**
```sql
CREATE TABLE document_permissions (
    doc_id UUID,
    principal_type TEXT,  -- 'user', 'group', 'anyone'
    principal_id TEXT,    -- user_id, group_id, or '*'
    permission TEXT,
    granted_by UUID,
    granted_at TIMESTAMP,
    PRIMARY KEY (doc_id, principal_type, principal_id)
);
```

**Permission check:**
```python
async def check_permission(doc_id, user_id, required_permission):
    # Check direct user permission
    user_perm = await db.get_permission(doc_id, 'user', user_id)
    if user_perm and user_perm >= required_permission:
        return True
    
    # Check group permissions
    groups = await get_user_groups(user_id)
    for group_id in groups:
        group_perm = await db.get_permission(doc_id, 'group', group_id)
        if group_perm and group_perm >= required_permission:
            return True
    
    # Check 'anyone with link'
    link_perm = await db.get_permission(doc_id, 'anyone', '*')
    if link_perm and link_perm >= required_permission:
        return True
    
    return False
```

**Link sharing:**
- Generate unique share link
- Link encodes doc_id + permission level
- Optional: require sign-in, password protection

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│              GOOGLE DOCS - FINAL ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 1B documents, 100M DAU, 500K ops/sec                   │
│                                                                 │
│  Core Components:                                               │
│  ├── Collaboration Servers - Real-time OT processing           │
│  ├── Document Session Manager - Per-document leadership        │
│  ├── OT Engine - Conflict resolution                           │
│  ├── Version Control - Snapshots + operation log               │
│  ├── Permission Service - Access control                       │
│  └── Search Service - Full-text indexing                       │
│                                                                 │
│  Data Stores:                                                   │
│  ├── Spanner - Document content (strong consistency)           │
│  ├── Cassandra - Operation log (high write)                    │
│  ├── Redis - Session state, cursors                            │
│  ├── GCS - Snapshots, exports                                  │
│  └── Elasticsearch - Search index                              │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── Operational Transform for conflict resolution             │
│  ├── Per-document leader for consistency                       │
│  ├── Snapshot + operation log for versioning                   │
│  ├── Marker-based comment anchoring                            │
│  └── Debounced search indexing                                 │
│                                                                 │
│  SLA: 99.99% availability, <100ms edit latency                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
