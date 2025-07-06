# Que 1. Rate three caching designs (write-through, read-through, no-cache) by latency, consistency, cost

Absolutely. Let’s take a **deep-dive** into each of the 3 caching strategies — **Write-Through**, **Read-Through**, and **No-Cache** — with **real-world behavior**, **detailed trade-offs**, and **how they'd apply to MMT hotel systems**.

---

# 🧠 What is Caching?

Caching is the practice of storing **frequently accessed data** in a fast-access storage layer (like **in-memory Redis**, **Memcached**) to improve:

* ✅ Response time (latency)
* ✅ Scalability (reduced DB load)
* ✅ User experience (faster UIs)

---

## 🔹 1. Write-Through Cache

### 🎯 What is it?

> **Every write** operation updates both:
>
> * the **cache**
> * and the **database** (at the same time)

### 🔁 How it works:

* **PUT or UPDATE**: write to cache **and** DB simultaneously.
* **GET**: read from cache.

```java
public void updateHotelDetails(Hotel hotel) {
    cache.put(hotel.getId(), hotel);  // update cache
    database.update(hotel);          // also write to DB
}
```

### ✅ Pros:

* **Strong Consistency** — DB and cache always in sync.
* **Simple GET logic** — just read from cache.

### ❌ Cons:

* **Slower writes** — every write is **synchronous** to both cache and DB.
* **More expensive** — higher infra cost, double the writes.

### 📌 Use Case in MMT Hotel System:

* Updating **inventory count** or **wallet balance** — where **no stale value** can be shown.
* Room availability updates from OTA partners.

---

## 🔹 2. Read-Through Cache

### 🎯 What is it?

> **Read-first** strategy.
> On cache **miss**, read from DB and populate the cache.

### 🔁 How it works:

* **GET**: check cache

  * If hit → return value.
  * If miss → read from DB, cache it, then return.
* **PUT**: write only to DB, **not to cache** (optional)

```java
public Hotel getHotel(String id) {
    return cache.get(id, () -> database.findById(id));
}
```

### ✅ Pros:

* **Fast reads** (when cache hits)
* **Simple writes** — only write to DB
* **Efficient** for **read-heavy systems** like hotel details

### ❌ Cons:

* Can return **stale data** if not invalidated on DB update.
* Needs **TTL/eviction policies** or **manual invalidation**

### 📌 Use Case in MMT Hotel System:

* **Hotel description**, **images**, **amenities**, **policy**
* Data doesn’t change frequently, so caching improves speed.
* Low risk if cache is a little stale.

---

## 🔹 3. No Cache

### 🎯 What is it?

> Directly read/write from the **database** only.
> No in-memory caching layer involved.

### 🔁 How it works:

* Every GET/PUT hits the database directly.

```java
public Hotel getHotel(String id) {
    return database.findById(id); // always from DB
}
```

### ✅ Pros:

* **Perfect consistency** — no cache to go stale.
* **Simpler design** — no TTL, no eviction, no cache logic.

### ❌ Cons:

* **Slower performance**
* **Increased load** on DB (bad at scale)
* Not scalable if traffic is high.

### 📌 Use Case in MMT Hotel System:

* **OTP validation**, **login audit**, **booking logs**
* Anything security-sensitive or rarely accessed.

---

# 📊 Detailed Comparison Table

| Feature             | Write-Through                   | Read-Through                 | No-Cache             |
| ------------------- | ------------------------------- | ---------------------------- | -------------------- |
| **Latency (read)**  | ⚡ Fast (cache hit)              | ⚡ Fast (on hit)              | 🐢 Slow (always DB)  |
| **Latency (write)** | 🐢 Slow (writes to 2 places)    | ⚡ Fast (DB only)             | ⚡ Fast               |
| **Consistency**     | ✅ Strong                        | ⚠️ Depends on TTL            | ✅ Strong             |
| **Infra cost**      | 💸 High (double write)          | ⚖️ Medium                    | ✅ Low                |
| **Complexity**      | ⚠️ Medium                       | ✅ Simple on read path        | ✅ Simple             |
| **Failure Risk**    | If DB fails, cache may be stale | If cache misses, DB fallback | If DB fails, no data |
| **Use When**        | Room availability, wallets      | Hotel data, metadata         | OTP, logs, audit     |

---

# 🏨 Example in MMT Hotel System

| Feature                                | Suggested Cache Strategy |
| -------------------------------------- | ------------------------ |
| **Hotel Info (description, images)**   | ✅ Read-through cache     |
| **Availability, Lock Room, Inventory** | ⚠️ Write-through + TTL   |
| **User Login, OTP, Booking logs**      | ❌ No-cache (must hit DB) |

---

# ✅ Summary

| Goal                                   | Best Strategy |
| -------------------------------------- | ------------- |
| **Ultra low-latency** read             | Read-through  |
| **High consistency** write             | Write-through |
| **Low infra and consistency-critical** | No-cache      |

---
