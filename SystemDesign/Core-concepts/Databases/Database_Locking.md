# 🚀 Locking System in Database: Pessimistic vs Optimistic

---

## Overview

Database locking is essential for maintaining data integrity when multiple transactions access the same data concurrently. There are two primary approaches: **Pessimistic Locking** and **Optimistic Locking**.

---

## 🔥 Pessimistic Locking

**Assumes conflicts will happen.**

### Characteristics:
- **Locks data immediately** when reading or writing
- Other transactions **must wait** until the lock is released
- Common in **high-contention** systems

### ✅ Good for:
- Heavy write conflicts
- Critical financial operations
- High-contention scenarios
- When conflicts are frequent

### Example (SQL):
```sql
SELECT * FROM users WHERE id=5 FOR UPDATE;
-- This locks the row immediately
```

---

## ⚡ Optimistic Locking

**Assumes conflicts are rare.**

### Characteristics:
- **No lock while reading**
- When updating, it **checks if data changed** (using version number or timestamp)
- If changed, **transaction fails** and must retry

### ✅ Good for:
- Read-heavy, less conflict-prone systems
- Better performance in low contention
- Web applications with many concurrent users

### Example (Workflow):
1. Read row with version `v1`
2. Try to update with `WHERE version=v1`
3. If no row updated → data changed → retry

---

## 🎯 Quick Comparison

| Aspect | Pessimistic Locking | Optimistic Locking |
|:-------|:--------------------|:-------------------|
| **When Applied** | Before conflict | After detecting conflict |
| **Locking** | Immediate | During commit |
| **Performance** | Slower under low conflict | Faster under low conflict |
| **Use Case** | High contention | Low contention |
| **Deadlock Risk** | Higher | Lower |
| **Scalability** | Lower | Higher |

---

## 🔒 Pessimistic Locking Example (Java/Spring Boot)

```java
@Entity
public class User {
    @Id
    private Long id;
    private String name;
}

public interface UserRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    User findByIdForUpdate(@Param("id") Long id);
}
```

**Explanation:**
- Acquires **DB lock** immediately
- Other updates will **block** until this one finishes

---

## ⚡ Optimistic Locking Example (Java/Spring Boot)

```java
@Entity
public class User {
    @Id
    private Long id;
    private String name;

    @Version
    private Long version; // Auto-managed by JPA
}

public interface UserRepository extends JpaRepository<User, Long> {
}
```

**Usage:**
```java
User user = userRepository.findById(1L).orElseThrow();
// Do some changes
user.setName("New Name");
userRepository.save(user); // JPA will check the version automatically
```

**Explanation:**
- No DB lock while reading
- During `save()`, **version** is checked
- If someone else updated in between → **OptimisticLockException** is thrown

---

## 🔵 Quick Reference

| Annotation | Locking Type |
|:-----------|:-------------|
| `@Lock(PESSIMISTIC_WRITE)` | Pessimistic |
| `@Lock(PESSIMISTIC_READ)` | Pessimistic (shared lock) |
| `@Version` | Optimistic |

---

## 🏦 Real-World Example: Banking Transaction System

### Problem:
- Money transfer between two accounts
- Must **guarantee** no double withdrawal
- Must also be **fast** under normal load

### 💥 Smart Strategy

| Operation | Locking Type | Why |
|:----------|:-------------|:----|
| Withdraw money | **Pessimistic Locking** | Critical section: **lock immediately** to prevent double deduction |
| Update user profile (non-money data) | **Optimistic Locking** | Changes are rare: **no need to block**. Just retry if collision |

---

### 🔥 Implementation Flow

#### 1. Withdrawal (`Account` Entity)
```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
Account findAccountForUpdate(Long id);
```
- Lock account row immediately
- Deduct amount safely

#### 2. Profile Update (`UserProfile` Entity)
```java
@Version
private Long version;
```
- Read and edit user email, address, etc.
- Save without locking DB
- Retry if someone else changed at the same time

---

## 🎯 Why This is Smart

- **Critical operations** (like money) are *safely locked* → **No loss**
- **Non-critical operations** are *fast and scalable* → **Better user experience**

---

## When to Use Each

### Use Pessimistic Locking When:
- ✅ High probability of conflicts
- ✅ Critical data integrity (financial transactions)
- ✅ Short transaction duration
- ✅ Can tolerate blocking

### Use Optimistic Locking When:
- ✅ Low probability of conflicts
- ✅ Read-heavy workloads
- ✅ Long-running transactions
- ✅ High scalability needed
- ✅ Can handle retries gracefully

---

## Lock Types Reference

### Pessimistic Lock Modes (JPA)

| Mode | Description |
|:-----|:------------|
| `PESSIMISTIC_READ` | Shared lock - allows other reads |
| `PESSIMISTIC_WRITE` | Exclusive lock - blocks all |
| `PESSIMISTIC_FORCE_INCREMENT` | Exclusive + increment version |

### Database Lock Types

| Lock | Description |
|:-----|:------------|
| **Row Lock** | Locks specific row(s) |
| **Table Lock** | Locks entire table |
| **Shared Lock (S)** | Multiple reads allowed |
| **Exclusive Lock (X)** | Single write, no reads |

---

## Best Practices

1. **Keep lock duration short** - Release locks as quickly as possible
2. **Use appropriate isolation levels** - Match isolation to your needs
3. **Handle deadlocks** - Implement retry logic
4. **Monitor lock contention** - Track blocked queries
5. **Consider hybrid approach** - Use different strategies for different operations
6. **Test under load** - Verify behavior with concurrent users
