Perfect! Let’s expand your **Locker-Based Package Storage System** into a complete LLD with all components and **cross questions** an interviewer might ask.

---

## ✅ 1. Functional & Non-Functional Requirements

### Functional Requirements

* Add lockers of various fixed sizes: S, M, L, XL, XXL
* Store an incoming package into a suitable empty locker
* Remove a package from a locker
* View available lockers by size
* Prevent assigning a locker that's already in use

### Non-Functional Requirements

* Scalable for 1000s of lockers
* Consistent view across threads (thread-safe)
* High availability
* Quick response time for find/store requests

---

## ✅ 2. Locker Size Categories

```java
enum LockerSize {
    SMALL(10, 10, 10),
    MEDIUM(20, 20, 20),
    LARGE(30, 30, 30),
    XL(40, 40, 40),
    XXL(50, 50, 50);

    int length, width, height;
}
```

---

## ✅ 3. Class Design

```java
class Locker {
    UUID id;
    LockerSize size;
    boolean isOccupied;
    Package currentPackage;
}
```

```java
class Package {
    UUID id;
    String label;
    LockerSize size;
}
```

```java
class LockerService {
    Map<LockerSize, List<Locker>> lockerMap;

    Locker findAvailableLocker(LockerSize size) { ... }
    void addLocker(Locker locker) { ... }
    boolean storePackage(Package pkg) { ... }
    void removePackage(UUID lockerId) { ... }
}
```

---

## ✅ 4. DB Schema

### `Lockers`

```sql
CREATE TABLE Lockers (
  id UUID PRIMARY KEY,
  size TEXT CHECK (size IN ('S','M','L','XL','XXL')),
  is_occupied BOOLEAN DEFAULT false,
  package_id UUID NULL
);
```

### `Packages`

```sql
CREATE TABLE Packages (
  id UUID PRIMARY KEY,
  label TEXT,
  size TEXT CHECK (size IN ('S','M','L','XL','XXL'))
);
```

---

## ✅ 5. Key Methods (with concurrency handling)

### Method: `storePackage`

```java
public synchronized boolean storePackage(Package pkg) {
    List<Locker> lockers = lockerMap.get(pkg.size);
    for (Locker locker : lockers) {
        if (!locker.isOccupied) {
            locker.currentPackage = pkg;
            locker.isOccupied = true;
            return true;
        }
    }
    return false;
}
```

### Method: `removePackage`

```java
public synchronized void removePackage(UUID lockerId) {
    for (List<Locker> lockers : lockerMap.values()) {
        for (Locker locker : lockers) {
            if (locker.id.equals(lockerId) && locker.isOccupied) {
                locker.currentPackage = null;
                locker.isOccupied = false;
            }
        }
    }
}
```

---

## ✅ 6. API Design

### `POST /lockers`

```json
{
  "size": "M"
}
```

### `POST /packages/store`

```json
{
  "packageId": "pkg-123",
  "size": "L"
}
```

### `DELETE /packages/remove/{lockerId}`

### `GET /lockers/available?size=M`

---

## ✅ 7. Design Patterns Involved

| Pattern       | Use Case                               |
| ------------- | -------------------------------------- |
| **Factory**   | Create lockers of specific size        |
| **Singleton** | LockerService singleton across threads |
| **Strategy**  | (Optional) for locker selection logic  |

---

## ✅ 8. Handling Concurrency

* Use `synchronized` in Java or `ReentrantLock` to avoid double-booking lockers
* Alternatively, use **AtomicBoolean** for `isOccupied` flag

---

## ✅ 9. Edge Cases

| Case                      | Handling                                |
| ------------------------- | --------------------------------------- |
| No lockers available      | Return appropriate error                |
| Duplicate locker addition | Enforce unique lockerId                 |
| Storing in smaller locker | Check size compatibility before storing |
| Concurrent store calls    | Use thread-safe structure or DB lock    |

---

## ✅ 10. Cross Questions Interviewer Might Ask

| Area                   | Sample Cross Questions                                                                                                    |
| ---------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| 🎯 **Modeling**        | Why did you choose fixed locker sizes over dynamic dimensions? <br> Could we support volume-based matching instead?       |
| 🔄 **Concurrency**     | What happens if two users try to store into the same locker at the same time? <br> How would you implement thread-safety? |
| 📦 **Storage Logic**   | If a package fits into both M and L, which one should be chosen?                                                          |
| 📊 **Scalability**     | How would this design change if you had 1M lockers across warehouses?                                                     |
| 🧠 **API Design**      | How would you allow searching by multiple locker sizes (e.g., L or XL)?                                                   |
| ⚠️ **Fault Tolerance** | What happens if the service crashes after locking a locker but before storing package info?                               |
| 💽 **DB Layer**        | What DB transactions are needed to ensure consistency in case of a store/remove?                                          |
| 📈 **Metrics**         | How would you track % locker utilization by size?                                                                         |

---

Handling **concurrency** in a **Locker-based Package Storage System** is critical to ensure:

* **No two packages** are stored in the same locker
* A **single package** is not stored multiple times
* **Locker state** remains consistent during simultaneous operations

Let’s break this down in detail:

---

## ✅ 1. **Where Can Concurrency Issues Happen?**

| Scenario                                                         | Problem                                 |
| ---------------------------------------------------------------- | --------------------------------------- |
| Two threads try to store a package at the same time              | Both may pick the same available locker |
| One thread reads a locker as available, another stores before it | Leads to overwriting locker state       |
| Remove and store operations overlap                              | Inconsistent `isOccupied` flags         |

---

## ✅ 2. **Concurrency Control Techniques**

### 🔐 A. In-Memory Locking (Java/Spring Example)

#### **1. Use `synchronized` or `ReentrantLock`**

```java
public class LockerService {
    private final Map<LockerSize, List<Locker>> lockerMap;
    private final ReentrantLock lock = new ReentrantLock();

    public boolean storePackage(Package pkg) {
        lock.lock(); // start critical section
        try {
            List<Locker> lockers = lockerMap.get(pkg.getSize());
            for (Locker locker : lockers) {
                if (!locker.isOccupied()) {
                    locker.setOccupied(true);
                    locker.setCurrentPackage(pkg);
                    return true;
                }
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
}
```

✅ **Pros:** Easy to implement
❌ **Cons:** Doesn't work in distributed setup

---

### 🗃️ B. Database-Level Locking

#### **1. Pessimistic Locking (SQL: `FOR UPDATE`)**

```sql
BEGIN;

SELECT * FROM Lockers
WHERE size = 'M' AND is_occupied = false
ORDER BY id
LIMIT 1
FOR UPDATE;

-- Reserve it
UPDATE Lockers
SET is_occupied = true, package_id = 'pkg-123'
WHERE id = 'locker-999';

COMMIT;
```

✅ **Pros:** Safe even in distributed systems
❌ **Cons:** Can cause blocking and slow transactions

---

#### **2. Optimistic Locking (Version Field)**

Add a version field:

```sql
ALTER TABLE Lockers ADD version INT DEFAULT 0;
```

Use conditional update:

```sql
UPDATE Lockers
SET is_occupied = true, package_id = 'pkg-123', version = version + 1
WHERE id = 'locker-999' AND version = 0;
```

✅ **Pros:** Non-blocking
❌ **Cons:** Needs retry logic if conflict occurs

---

### ⚛️ C. Atomic Operations (for in-memory lockers)

Use `AtomicBoolean` instead of `boolean`:

```java
class Locker {
    AtomicBoolean isOccupied = new AtomicBoolean(false);

    public boolean tryLock() {
        return isOccupied.compareAndSet(false, true);
    }
}
```

In service:

```java
for (Locker locker : lockerMap.get(pkg.getSize())) {
    if (locker.tryLock()) {
        locker.setCurrentPackage(pkg);
        return true;
    }
}
```

✅ **Pros:** Efficient for in-memory solutions
❌ **Cons:** Not useful across multiple servers

---

### ☁️ D. Distributed Locking (for microservices)

Use **Redis-based distributed lock** (e.g., Redisson or Redis `SETNX`):

```bash
SET lock:locker:123 true NX EX 10
```

Only one request will succeed in acquiring the lock.

✅ **Pros:** Works across services
❌ **Cons:** Needs expiry logic, failure handling

---

## ✅ 3. Best Practice: Combine Layers

For production systems:

| Layer                  | Purpose                            |
| ---------------------- | ---------------------------------- |
| In-memory lock         | Avoid concurrency in same instance |
| DB lock (`FOR UPDATE`) | Prevent conflict between instances |
| Redis distributed lock | Ensure global consistency          |

---

## ✅ 4. Preventing Deadlocks

When locking multiple lockers or tables:

* **Always lock in the same order** (e.g., by Locker ID)
* Use **timeouts** on locks
* Release unused locks early

---

## ✅ 5. Handling Failures & Rollbacks

If failure occurs **after** locker is marked occupied but **before** package is linked:

* Use **transactions** to rollback the update
* Or run **background cleaner** to reset partially reserved lockers

---

## ✅ 6. Interview Cross Questions

| Question                                                              | Purpose                                                |
| --------------------------------------------------------------------- | ------------------------------------------------------ |
| What happens if two threads read the same locker as free?             | Tests your concurrency handling depth                  |
| How will you prevent double-booking in a distributed system?          | Tests knowledge of distributed locks                   |
| Why not only use optimistic locking?                                  | Checks understanding of trade-offs                     |
| How will you handle locker reservation if DB crashes mid-transaction? | Fault tolerance                                        |
| Can race conditions still occur with AtomicBoolean?                   | Yes, if multiple processes involved                    |
| How will you test for concurrency bugs?                               | Look for answers like stress/load tests, chaos testing |

---

To efficiently manage locker availability, the **best data structure** depends on the use case you're optimizing for:

---

## ✅ Your Use Cases

1. **Find an available locker of a given size** – fast lookup
2. **Add/remove lockers dynamically** – maintainable and updatable
3. **Store and retrieve locker info** – object-based, easy access
4. **Support concurrency** – thread-safe operations if in-memory

---

## 🎯 Recommended Design (Hybrid Approach)

### 🔹 1. Use `Map<LockerSize, PriorityQueue<Locker>>` (Best Choice)

```java
Map<LockerSize, PriorityQueue<Locker>> lockerPool;
```

### Why?

* **Map** lets you look up lockers by size in O(1)
* **PriorityQueue** helps you pick the locker with smallest ID / least recently used / earliest available — flexible strategy
* Can easily remove/add lockers
* You can maintain the `PriorityQueue` with a custom comparator (e.g., based on ID or timestamp)

### Example:

```java
lockerPool.get(LockerSize.MEDIUM).poll();  // get next available medium locker
```

---

## 🔐 For Concurrency:

* Use `ConcurrentHashMap` instead of `HashMap`
* Use `ConcurrentLinkedQueue` or thread-safe wrappers around `PriorityQueue`

```java
Map<LockerSize, Queue<Locker>> lockerPool = new ConcurrentHashMap<>();
```

---

## ⚡ Alternative Approaches

| Data Structure     | Use When You Need       | Pros                                     | Cons               |
| ------------------ | ----------------------- | ---------------------------------------- | ------------------ |
| `List<Locker>`     | Simplicity              | Easy to use                              | Linear scan = slow |
| `Set<Locker>`      | Fast add/remove         | Uniqueness                               | No ordering        |
| `TreeMap`          | Nearest size match      | Range queries (e.g., L if M unavailable) | More complex logic |
| `Redis Sorted Set` | Distributed scalability | Persistent, shared across nodes          | Needs Redis infra  |

---

## 🧠 Bonus: Add Metadata Indexing (Optional)

You can also maintain:

```java
Map<UUID, Locker> lockerById;
```

For fast access by locker ID when removing or updating.

---

## 🧪 Interview Cross Questions

| Question                                                                    | Reason                                            |
| --------------------------------------------------------------------------- | ------------------------------------------------- |
| Why use a priority queue over list or set?                                  | Checks your understanding of retrieval efficiency |
| What happens if two threads poll the same locker?                           | Concurrency check                                 |
| Can TreeMap help find fallback sizes if current size is unavailable?        | Range flexibility                                 |
| How would you design this if the lockers were distributed across locations? | Scaling check                                     |
| What if many packages are coming in parallel — what bottleneck appears?     | Thread contention                                 |

---
