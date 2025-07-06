Great! Let's design a **Booking Service like BookMyShow** with a full LLD breakdown, including all points you listed: concurrency, edge cases, observability, etc.

---

## ✅ 1. Functional & Non-Functional Requirements

### 🎯 Functional:

* Search cities, movies, shows, and available seats
* Reserve seats temporarily (hold)
* Confirm booking with payment
* Cancel booking
* Auto-release held seats on timeout
* Track booking and payment status

### 🔧 Non-Functional:

* High availability and low latency
* Idempotent operations
* Consistent seat reservation (no double-booking)
* Scalable across cities, multiplexes, and shows
* Secure with JWT + RBAC
* Rate-limited to prevent abuse

---

## ✅ 2. DB Schema

### 🎬 `Shows`

```sql
CREATE TABLE Shows (
  id UUID PRIMARY KEY,
  movie_id UUID,
  auditorium_id UUID,
  start_time TIMESTAMP,
  end_time TIMESTAMP,
  language TEXT,
  screen_type TEXT, -- e.g., 2D, 3D, IMAX
  price_map JSONB -- seatType -> price
);
```

### 🪑 `Seats`

```sql
CREATE TABLE Seats (
  id UUID PRIMARY KEY,
  show_id UUID REFERENCES Shows(id),
  seat_number TEXT,
  seat_type TEXT,
  status TEXT CHECK (status IN ('AVAILABLE', 'HELD', 'BOOKED')),
  held_by UUID, -- userId
  hold_token UUID, -- for idempotency
  hold_time TIMESTAMP
);
```

### 📑 `Reservations`

```sql
CREATE TABLE Reservations (
  id UUID PRIMARY KEY,
  user_id UUID,
  show_id UUID,
  seat_ids UUID[],
  status TEXT CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'FAILED')),
  hold_token UUID UNIQUE,
  created_at TIMESTAMP
);
```

### 💳 `Transactions`

```sql
CREATE TABLE Transactions (
  id UUID PRIMARY KEY,
  reservation_id UUID REFERENCES Reservations(id),
  payment_status TEXT CHECK (payment_status IN ('SUCCESS', 'FAILED', 'PENDING')),
  payment_reference TEXT,
  amount NUMERIC,
  created_at TIMESTAMP
);
```

---

## ✅ 3. UML Class Diagram (High-Level)

```
+------------+      +---------+     +---------------+
|   Show     |------|  Seats  |     | Reservations  |
+------------+      +---------+     +---------------+
| id         |      | id      |     | id            |
| movie_id   |      | show_id |     | user_id       |
| start_time |      | seat_no |     | seat_ids[]    |
| price_map  |      | status  |     | status        |
+------------+      +---------+     +---------------+
                                       |
                                       |
                                   +------------+
                                   | Transaction|
                                   +------------+
                                   | status     |
                                   | amount     |
                                   +------------+
```

---

## ✅ 4. API Endpoints

### 🔍 Search Shows

```http
GET /api/shows?city=Delhi&movie=Inception&date=2025-07-01
```

### 🪑 Get Available Seats

```http
GET /api/shows/{showId}/seats
```

### 🛑 Hold Seats (Reserve Temporarily)

```http
POST /api/reservations/hold
Authorization: Bearer <JWT>
Body:
{
  "showId": "abc",
  "seatIds": ["s1", "s2"],
  "holdToken": "uuid-1234" // for idempotency
}
```

### ✅ Confirm Booking

```http
POST /api/reservations/confirm
{
  "holdToken": "uuid-1234",
  "paymentReference": "txn-789"
}
```

### ❌ Cancel Booking

```http
POST /api/reservations/cancel
{
  "reservationId": "res-123"
}
```

---

## ✅ 5. Service Layer & Code (Simplified)

### 🔐 `BookingService.java`

```java
public class BookingService {
    public HoldResponse holdSeats(UUID userId, UUID showId, List<UUID> seatIds, UUID holdToken) {
        // Check if holdToken already exists for idempotency
        if (reservationRepo.existsByHoldToken(holdToken)) return previousResponse;

        for (UUID seatId : seatIds) {
            if (!seatRepo.tryHoldSeat(showId, seatId, userId, holdToken)) {
                throw new SeatUnavailableException();
            }
        }

        reservationRepo.createPendingReservation(userId, showId, seatIds, holdToken);
        return new HoldResponse("Held for 5 mins", holdToken);
    }

    public ConfirmResponse confirm(UUID holdToken, PaymentInfo paymentInfo) {
        Reservation res = reservationRepo.getByHoldToken(holdToken);
        transactionService.charge(paymentInfo);
        reservationRepo.markConfirmed(res.getId());
        seatRepo.markBooked(res.getSeatIds());
        return new ConfirmResponse("Success", res.getId());
    }
}
```

---

## ✅ 6. Concurrency Handling

| Problem                   | Solution                                                                               |
| ------------------------- | -------------------------------------------------------------------------------------- |
| Double-booking seat       | **Pessimistic Locking** (`SELECT ... FOR UPDATE`) or DB unique constraint on seat+show |
| Multiple payment requests | Use **idempotency token** (holdToken)                                                  |
| Simultaneous holds        | Redis/DB atomic update or Redis `SETNX`                                                |

---

## ✅ 7. Throughput Optimization

* **Redis cache** show metadata and seat status (`AVAILABLE/BOOKED`)
* Use **rate-limiting** middleware (e.g., token bucket) per user/IP
* Async processing of **payment callbacks** and notification

---

## ✅ 8. Fault Tolerance

| Issue                           | Handling Strategy                                  |
| ------------------------------- | -------------------------------------------------- |
| User retries hold/confirm       | Use idempotency token to return same result        |
| App crashes before confirmation | Seats expire after 5 mins using cron job/Redis TTL |
| Partial payment success         | Flag payment as `PENDING`, retry async or notify   |
| User tries booking sold-out     | Return error, show latest status from cache        |

---

## ✅ 9. Observability

| Metric                | How to Capture                                               |
| --------------------- | ------------------------------------------------------------ |
| Success Rate          | % of `CONFIRMED` bookings / total requests                   |
| Avg. Reservation Time | `confirmed_at - held_at`                                     |
| Alerts                | Sentry/NewRelic/Grafana alerts on failures, long holds, etc. |
| Logs                  | Trace userId, showId, holdToken                              |

---

## ✅ 10. Scalability Plan

| Component       | Scaling Strategy                             |
| --------------- | -------------------------------------------- |
| Seats Table     | **Partition by auditorium\_id or show\_id**  |
| Booking Service | **Horizontal scaling** via stateless APIs    |
| Cache Layer     | **Redis cluster** for fast reads             |
| Event Queue     | Kafka for async notification/payment updates |

---

## ✅ 11. Security

* 🔐 **JWT Auth**: Users must include token in header (`Authorization: Bearer <token>`)
* 🔐 **RBAC**: Roles:

  * Admin: create shows, manage pricing
  * User: search, book, cancel
* ✅ Rate-limit APIs per user/IP
* ✅ Input validation + sanitization to prevent SQL injection

---

## ✅ 12. Hold Expiration Flow (Auto Cleanup)

* Redis key: `seat:hold:{seatId}` with TTL = 5 mins
* Cron job or Redis pub/sub:

  * Reverts status from `HELD` → `AVAILABLE` if no confirm

---

This is the **core concurrency problem** in any booking system — **how to prevent double-booking** when multiple users attempt to book the **same seat** simultaneously.

Let’s solve it step by step:

---

## 🔴 Problem Statement

* There is only **1 seat (S1)** available.
* Two users (User A and User B) simultaneously try to **reserve or confirm** the booking for S1.
* We must ensure **only one** of them succeeds.

---

## ✅ Goals

* **Ensure only one person can reserve/book the seat.**
* **Handle race conditions** due to parallel requests.
* **Avoid overselling** or inconsistent state.
* Provide a **good user experience** (retry or fail fast with message).

---

## 🛠️ Solution Options

### ✅ 1. **Pessimistic Locking (DB-level Lock)**

Use database transactions with a `SELECT ... FOR UPDATE` when trying to reserve the seat.

```sql
BEGIN;
SELECT * FROM Seats WHERE id = 'S1' AND status = 'AVAILABLE' FOR UPDATE;
-- If seat is available, update it
UPDATE Seats SET status = 'HELD', held_by = 'UserA' WHERE id = 'S1';
COMMIT;
```

### Pros:

* Strong consistency.
* Prevents concurrent modification.

### Cons:

* DB locks can block under high load.
* Risk of deadlocks if not managed properly.

---

### ✅ 2. **Optimistic Locking (Version Number / Timestamp)**

Each seat row includes a version/timestamp. Only allow update if version hasn’t changed.

```sql
UPDATE Seats
SET status = 'HELD', version = version + 1
WHERE id = 'S1' AND version = 3 AND status = 'AVAILABLE';
```

If update count = 0 → someone else got it first.

### Pros:

* No DB locks.
* Scales better for read-heavy systems.

### Cons:

* You must handle retries in app logic.
* Slightly more complex to implement.

---

### ✅ 3. **Redis with Atomic Commands**

Use Redis `SETNX` or Lua script to atomically "lock" the seat.

```bash
SETNX seat:lock:S1 UserA  # Only one succeeds
```

Use TTL(time to live) to auto-expire locks in case of crash.

### Pros:

* Fast (microseconds).
* Works well for short-lived holds.

### Cons:

* Requires coordination between Redis and DB.
* Need a background job to clean up expired locks.

---

## 💡 Recommended Strategy (Production)

Use a **hybrid approach**:

* Use **Redis for fast lock** (low latency hold logic).
* Use **pessimistic locking in DB** during final booking (confirm).

### Hold Phase:

```redis
SETNX seat:hold:S1 holdToken123 TTL 5min
```

* Only one user gets it.
* Others get error: "Seat already held".

### Confirm Phase (DB):

```sql
BEGIN;
SELECT * FROM Reservations WHERE seat_id='S1' FOR UPDATE;
IF seat.status = 'HELD' and hold_token matches:
  Update to status = 'BOOKED';
COMMIT;
```

---

## 🧪 How to Test

Simulate race condition:

* Spin up 100 threads trying to hold same seat
* Only 1 should succeed in holding it
* Others should get “seat not available” error

---

## 💬 Error Message to Users

* ❌ “Sorry, this seat was just taken by someone else. Please pick another seat.”
* ✅ Or auto-refresh the seat map after rejection

---

## ✅ Observability

Track metrics:

* `seat_reservation_conflicts`: how often double-book is attempted
* `seat_lock_failures`: how often Redis/DB lock fails
* Log user IDs and timestamps for debugging

---

## 🔐 Bonus: Idempotency

To protect from retries:

* Always use a `holdToken` or `reservationId`
* Make your seat hold and confirm APIs **idempotent**

---
