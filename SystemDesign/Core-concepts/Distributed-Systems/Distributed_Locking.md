# Distributed Locking

## What is Distributed Locking?

A distributed lock ensures that only one process across multiple nodes can access a shared resource or execute a critical section at a time.

```
Without Lock:
  Process A ──┬── Read balance: $100
  Process B ──┤── Read balance: $100
  Process A ──┤── Deduct $50, Write: $50
  Process B ──┴── Deduct $50, Write: $50
  Result: $50 (lost $50!)

With Distributed Lock:
  Process A ──┬── Acquire lock ✓
  Process B ──┤── Acquire lock (blocked)
  Process A ──┤── Read $100, Deduct $50, Write $50
  Process A ──┤── Release lock
  Process B ──┤── Acquire lock ✓
  Process B ──┴── Read $50, Deduct $50, Write $0
  Result: $0 (correct!)
```

---

## Why Distributed Locks?

| Use Case | Example |
|----------|---------|
| **Mutual Exclusion** | Only one node processes a job |
| **Leader Election** | Only one node is the leader |
| **Rate Limiting** | Global request limits |
| **Resource Access** | Exclusive file/database access |
| **Idempotency** | Prevent duplicate processing |

---

## Properties of a Good Distributed Lock

### 1. Mutual Exclusion (Safety)
Only one client holds the lock at any time.

### 2. Deadlock Freedom (Liveness)
Lock is eventually released (via TTL).

### 3. Fault Tolerance
Lock service remains available despite failures.

### 4. Fairness (Optional)
Requests are granted in order.

---

## Redis Distributed Lock

### Basic Implementation (SETNX)

```python
import redis
import uuid
import time

class RedisLock:
    def __init__(self, redis_client, lock_name, ttl=10):
        self.redis = redis_client
        self.lock_name = f"lock:{lock_name}"
        self.ttl = ttl
        self.token = str(uuid.uuid4())  # Unique identifier
    
    def acquire(self, blocking=True, timeout=None):
        """Attempt to acquire the lock"""
        start_time = time.time()
        
        while True:
            # SET key value NX EX seconds
            # NX = Only set if not exists
            # EX = Expire after seconds
            acquired = self.redis.set(
                self.lock_name,
                self.token,
                nx=True,
                ex=self.ttl
            )
            
            if acquired:
                return True
            
            if not blocking:
                return False
            
            if timeout and (time.time() - start_time) > timeout:
                return False
            
            time.sleep(0.1)  # Retry delay
    
    def release(self):
        """Release the lock (only if we own it)"""
        # Lua script for atomic check-and-delete
        lua_script = """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """
        return self.redis.eval(lua_script, 1, self.lock_name, self.token)
    
    def extend(self, additional_time):
        """Extend lock TTL (only if we own it)"""
        lua_script = """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("expire", KEYS[1], ARGV[2])
        else
            return 0
        end
        """
        return self.redis.eval(lua_script, 1, self.lock_name, self.token, additional_time)

# Usage
redis_client = redis.Redis(host='localhost', port=6379)
lock = RedisLock(redis_client, "order-processing")

if lock.acquire(timeout=5):
    try:
        # Critical section
        process_order()
    finally:
        lock.release()
```

### Redlock Algorithm (Multi-Node)

For higher reliability, acquire locks on multiple Redis nodes:

```python
class RedLock:
    def __init__(self, redis_nodes, lock_name, ttl=10):
        self.redis_nodes = redis_nodes  # List of Redis clients
        self.lock_name = lock_name
        self.ttl = ttl
        self.quorum = len(redis_nodes) // 2 + 1  # Majority
    
    def acquire(self):
        token = str(uuid.uuid4())
        start_time = time.time()
        
        # Try to acquire on all nodes
        acquired = 0
        for node in self.redis_nodes:
            try:
                if node.set(self.lock_name, token, nx=True, ex=self.ttl):
                    acquired += 1
            except:
                pass  # Node failure
        
        # Calculate time elapsed
        elapsed = time.time() - start_time
        validity_time = self.ttl - elapsed
        
        # Check if we have quorum and validity time left
        if acquired >= self.quorum and validity_time > 0:
            return token, validity_time
        else:
            # Release from all nodes
            self.release(token)
            return None, 0
    
    def release(self, token):
        for node in self.redis_nodes:
            try:
                lua_script = """
                if redis.call("get", KEYS[1]) == ARGV[1] then
                    return redis.call("del", KEYS[1])
                else
                    return 0
                end
                """
                node.eval(lua_script, 1, self.lock_name, token)
            except:
                pass
```

---

## ZooKeeper Distributed Lock

ZooKeeper uses sequential ephemeral nodes for locking:

```
/locks/
  └── resource-1/
        ├── lock-0000000001 (client A)
        ├── lock-0000000002 (client B)
        └── lock-0000000003 (client C)

Client A has lowest number → holds the lock
Client B watches 0000000001
Client C watches 0000000002
```

### Implementation

```python
from kazoo.client import KazooClient
from kazoo.recipe.lock import Lock

# Connect to ZooKeeper
zk = KazooClient(hosts='127.0.0.1:2181')
zk.start()

# Create lock
lock = Lock(zk, "/locks/my-resource")

# Acquire with timeout
if lock.acquire(blocking=True, timeout=10):
    try:
        # Critical section
        do_work()
    finally:
        lock.release()

# Or use context manager
with lock:
    do_work()
```

### How It Works
1. Client creates sequential ephemeral znode `/locks/resource/lock-`
2. Client gets all children and sorts them
3. If client's node is lowest → has lock
4. Otherwise, watch the next-lowest node
5. On notification, repeat from step 2

**Advantages**:
- Fair (FIFO ordering)
- Automatic cleanup (ephemeral nodes)
- Strong consistency

---

## Database-Based Locking

### PostgreSQL Advisory Locks

```sql
-- Acquire lock (blocking)
SELECT pg_advisory_lock(12345);

-- Acquire lock (non-blocking)
SELECT pg_try_advisory_lock(12345);  -- Returns true/false

-- Release lock
SELECT pg_advisory_unlock(12345);
```

```python
import psycopg2

def with_advisory_lock(conn, lock_id, func):
    cursor = conn.cursor()
    try:
        cursor.execute("SELECT pg_advisory_lock(%s)", (lock_id,))
        return func()
    finally:
        cursor.execute("SELECT pg_advisory_unlock(%s)", (lock_id,))
```

### Database Row Locking

```sql
-- Optimistic locking with version
UPDATE orders 
SET status = 'processing', version = version + 1
WHERE id = 123 AND version = 5;  -- Check version

-- If no rows updated → conflict → retry

-- Pessimistic locking
SELECT * FROM orders WHERE id = 123 FOR UPDATE;
-- Row is locked until transaction commits
```

---

## Common Patterns

### Lock with Fencing Token

Prevent issues with expired locks:

```python
class FencedLock:
    def __init__(self, redis_client, lock_name):
        self.redis = redis_client
        self.lock_name = lock_name
        self.fence_key = f"fence:{lock_name}"
    
    def acquire(self):
        # Increment fence token atomically
        fence_token = self.redis.incr(self.fence_key)
        
        if self.redis.set(self.lock_name, fence_token, nx=True, ex=10):
            return fence_token
        return None
    
    def verify_fence(self, token, resource):
        """Resource checks if token is still valid"""
        current = resource.get_fence_token()
        return token >= current

# Usage
lock = FencedLock(redis, "inventory")
token = lock.acquire()

if token:
    # Pass token to storage system
    # Storage rejects writes with old tokens
    storage.write(item, token)
```

### Reentrant Lock

Same thread can acquire lock multiple times:

```python
import threading

class ReentrantLock:
    def __init__(self, redis_client, lock_name):
        self.redis = redis_client
        self.lock_name = lock_name
        self.local = threading.local()
    
    def acquire(self):
        if not hasattr(self.local, 'count'):
            self.local.count = 0
            self.local.token = None
        
        if self.local.count > 0:
            # Already hold the lock
            self.local.count += 1
            return True
        
        token = str(uuid.uuid4())
        if self.redis.set(self.lock_name, token, nx=True, ex=30):
            self.local.token = token
            self.local.count = 1
            return True
        return False
    
    def release(self):
        self.local.count -= 1
        if self.local.count == 0:
            self.redis.delete(self.lock_name)
```

---

## Handling Edge Cases

### 1. Lock Expiration During Work

```python
def process_with_renewal(lock, work_func):
    # Start background renewal
    stop_renewal = threading.Event()
    
    def renew():
        while not stop_renewal.is_set():
            lock.extend(30)
            time.sleep(10)
    
    renewal_thread = threading.Thread(target=renew)
    renewal_thread.start()
    
    try:
        work_func()
    finally:
        stop_renewal.set()
        renewal_thread.join()
        lock.release()
```

### 2. Network Partition

```
Client A acquires lock
Network partition occurs
Client A thinks it has lock
Lock expires (client A didn't renew)
Client B acquires lock
Network heals
Both clients think they have lock!
```

**Solution**: Fencing tokens

### 3. Clock Skew

```
Node 1 thinks TTL = 10s
Node 2 thinks TTL = 8s (clock behind)
Lock released early on Node 2
```

**Solution**: Use logical clocks, not wall clocks

---

## Best Practices

### Do's ✅
1. Always set TTL (prevent deadlock)
2. Use unique tokens (prevent releasing others' locks)
3. Release in finally block
4. Use fencing for critical resources
5. Monitor lock contention

### Don'ts ❌
1. Don't hold locks for long operations
2. Don't rely on clocks for correctness
3. Don't ignore network failures
4. Don't use locks for performance (use queues)

---

## Interview Questions

**Q: How does a distributed lock differ from a local lock?**
> Local lock: single process, memory-based, OS handles. Distributed lock: multiple processes/nodes, requires coordination service (Redis, ZooKeeper), must handle network failures, clock issues, and partial failures.

**Q: What is the Redlock algorithm?**
> Acquire locks on N Redis nodes, need majority (N/2+1) to succeed. Handles single node failures. Calculate validity time = TTL - acquisition time. Release from all nodes.

**Q: How do you handle lock expiration during long operations?**
> Background thread renews TTL periodically. Use fencing tokens so storage rejects stale writes. Set conservative TTL. Consider if lock is right solution for long operations.

**Q: What are fencing tokens and why are they important?**
> Monotonically increasing tokens given with each lock acquisition. Storage systems reject writes with tokens lower than last seen. Prevents issues when locks expire but old holder continues writing.

---

## Quick Reference

| Tool | Mechanism | Consistency | Use Case |
|------|-----------|-------------|----------|
| Redis (single) | SETNX + TTL | Weak | Simple cases |
| Redlock | Multi-node | Stronger | Production |
| ZooKeeper | Sequential znodes | Strong | Critical systems |
| PostgreSQL | Advisory locks | Strong | DB-centric apps |
| etcd | Lease + Key | Strong | Kubernetes |
