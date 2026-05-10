# Ticket Booking System - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database Design with Explanations](#database-design-with-explanations)
4. [Class Diagram](#class-diagram)
5. [Design Patterns Used](#design-patterns-used)
6. [SOLID Principles Applied](#solid-principles-applied)
7. [Code Implementation](#code-implementation)
8. [Concurrency Deep Dive](#concurrency-deep-dive)
9. [Edge Cases & Tests](#edge-cases--tests)

---

## Problem Statement

Design a ticket booking system (BookMyShow / Ticketmaster) that handles seat selection, temporary seat holds, payment, and high-concurrency booking with ZERO double-bookings.

---

## Requirements

### Functional Requirements
1. Browse events by type, date, venue
2. View seat map with real-time availability
3. Select and temporarily hold seats (10-minute lock)
4. Process payment and confirm booking
5. Cancel booking with refund rules
6. Support multiple event types (movie, concert, sports)
7. Dynamic pricing per seat type (premium, regular, balcony)
8. Generate QR code tickets

### Non-Functional Requirements
- Zero double-bookings (strong consistency)
- Handle 100K+ concurrent users during flash sales
- < 200ms seat selection, < 500ms booking confirmation
- Seat lock auto-expires after 10 minutes
- Support waitlist for sold-out events

---

## Database Design with Explanations

```sql
-- ============================================================================
-- TABLE: venues
-- WHY: A venue is the physical location (cinema hall, stadium, theater).
--      We separate venues from events because:
--      1. The SAME venue hosts MANY events (a cinema shows different movies)
--      2. Venue has its own data (address, capacity, seating layout)
--      3. Seating layout belongs to venue (physical seats), not event
--      Without this table, we'd duplicate venue info for every event.
-- ============================================================================
CREATE TABLE venues (
    venue_id UUID PRIMARY KEY,
    venue_name VARCHAR(200) NOT NULL,
    address TEXT NOT NULL,
    city VARCHAR(100) NOT NULL,
    capacity INTEGER NOT NULL,
    venue_type VARCHAR(50) NOT NULL,      -- CINEMA, STADIUM, THEATER, ARENA

    -- Seating layout is a JSON blueprint of the venue's physical seat arrangement.
    -- WHY JSON? The layout varies wildly between venues (stadium vs cinema).
    -- A normalized table would need dozens of columns. JSON is flexible here.
    seating_layout JSONB NOT NULL,        -- {"sections": [{"name": "A", "rows": 10, "seats_per_row": 20}]}
    
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? Users search events by city. Without it, every search
-- scans the entire venues table.
CREATE INDEX idx_venues_city ON venues(city);

-- ============================================================================
-- TABLE: events
-- WHY: An event is a specific show at a specific time at a specific venue.
--      "Avengers 5 at PVR Phoenix, 7:00 PM on Jan 15" is one event.
--      "Same movie, 9:30 PM" is a DIFFERENT event (different show time).
--      This is the most queried table — users browse and search events.
--
-- RELATIONSHIP: events → venues (Many-to-One)
--   WHY FK? An event MUST take place at a valid venue. This ensures:
--   - No event without a venue
--   - We can query "all events at venue X"
--   - If venue is deleted (rare), we decide what happens to events (RESTRICT)
--
-- WHY NOT embed venue data in events?
--   Because venue data (address, layout) doesn't change per event.
--   Embedding would duplicate venue data thousands of times.
-- ============================================================================
CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    venue_id UUID NOT NULL REFERENCES venues(venue_id),
    event_name VARCHAR(300) NOT NULL,
    event_type VARCHAR(50) NOT NULL,      -- MOVIE, CONCERT, SPORTS, THEATER
    event_date TIMESTAMP NOT NULL,
    duration_minutes INTEGER,
    description TEXT,
    
    -- Booking configuration
    booking_opens_at TIMESTAMP NOT NULL,  -- When tickets go on sale
    booking_closes_at TIMESTAMP,          -- Stop selling (e.g., 30min before show)
    max_tickets_per_user INTEGER DEFAULT 10,
    cancellation_allowed BOOLEAN DEFAULT true,
    
    -- Status tracking
    event_status VARCHAR(20) DEFAULT 'UPCOMING', -- UPCOMING, LIVE, COMPLETED, CANCELLED
    total_seats INTEGER NOT NULL,
    available_seats INTEGER NOT NULL,     -- Denormalized counter for fast availability check
    
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY this composite index? The #1 query is "show me upcoming movies in my city this weekend".
-- This index covers event_type + event_date efficiently.
CREATE INDEX idx_events_type_date ON events(event_type, event_date);

-- WHY this index? Booking window queries: "Is this event currently open for booking?"
CREATE INDEX idx_events_booking_window ON events(booking_opens_at, booking_closes_at)
    WHERE event_status = 'UPCOMING';

-- ============================================================================
-- TABLE: seats
-- WHY: Each row represents ONE physical seat for ONE specific event.
--      We create seats PER EVENT (not just per venue) because:
--      1. The SAME physical seat can have different PRICES for different events
--         (premium seats for a concert vs regular for a movie)
--      2. The SAME physical seat has different STATUS per event
--         (booked for show A, available for show B)
--      3. Some events may BLOCK certain seats (stage setup for concerts)
--
-- RELATIONSHIP: seats → events (Many-to-One)
--   WHY FK? A seat is for a specific event. When event is created, all seats
--   are generated from the venue's seating_layout template.
--
-- RELATIONSHIP: seats → venues (Many-to-One)
--   WHY FK? Redundant with events→venues, but we include it because:
--   1. Direct venue lookup without joining events table (performance)
--   2. Venue provides the physical seat position (section, row, number)
--
-- WHY NOT use a junction table events_seats?
--   Because seats have their own attributes (price, status) that vary per event.
--   A junction table would still need these columns, so it's cleaner as its own table.
-- ============================================================================
CREATE TABLE seats (
    seat_id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(event_id) ON DELETE CASCADE,
    venue_id UUID NOT NULL REFERENCES venues(venue_id),
    
    -- Physical location in venue
    section VARCHAR(10) NOT NULL,         -- "A", "B", "VIP", "BALCONY"
    row_number VARCHAR(10) NOT NULL,      -- "1", "2", "AA"
    seat_number VARCHAR(10) NOT NULL,     -- "1", "2", "15"
    
    -- Pricing (varies per event)
    seat_type VARCHAR(20) DEFAULT 'REGULAR', -- PREMIUM, REGULAR, BALCONY, VIP
    base_price DECIMAL(8,2) NOT NULL,
    
    -- Booking status
    seat_status VARCHAR(20) DEFAULT 'AVAILABLE',
    -- AVAILABLE  → No one has claimed this seat
    -- LOCKED     → Temporarily held during checkout (10-min timer)
    -- BOOKED     → Paid and confirmed
    -- BLOCKED    → Not for sale (stage setup, restricted view, etc.)
    
    -- Lock tracking
    locked_by UUID,                       -- user_id who locked it
    locked_until TIMESTAMP,               -- When lock expires (NOW + 10 min)
    
    -- Booking reference
    booking_id UUID,                      -- Set when seat is BOOKED
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    -- UNIQUE: Prevents two seat records for the same physical seat in the same event.
    -- WHY? If this constraint is missing, a bug could create duplicate seat records,
    -- leading to double-booking. This is our DATABASE-LEVEL safety net.
    UNIQUE(event_id, section, row_number, seat_number)
);

-- WHY this index? The #1 query is "get all available seats for event X".
-- This composite index serves that query directly.
CREATE INDEX idx_seats_event_status ON seats(event_id, seat_status);

-- WHY this index? Background job needs to find expired locks to release them.
-- Partial index (only LOCKED seats) keeps the index small and fast.
CREATE INDEX idx_seats_lock_expiry ON seats(locked_until) 
    WHERE seat_status = 'LOCKED';

-- ============================================================================
-- TABLE: bookings
-- WHY: This is the TRANSACTION record. When a user confirms and pays,
--      a booking is created. It ties together: WHO booked, WHICH event,
--      HOW MUCH they paid, and WHAT seats they got.
--
--      Separated from seats because:
--      1. One booking can have MULTIPLE seats (user books 4 seats together)
--      2. Booking has its own lifecycle (pending → confirmed → cancelled)
--      3. Booking stores payment info, which is separate from seat info
--      4. Cancellation affects the booking, not individual seats directly
--
-- RELATIONSHIP: bookings → events (Many-to-One)
--   WHY FK? A booking is for a specific event. We need this to:
--   - Query "all bookings for event X" (for event organizer)
--   - Enforce business rules per event (max tickets per user)
--
-- WHY NOT store booking_id directly on seats?
--   We DO store it on seats (as a denormalized reference for fast lookups).
--   But the booking table is the SOURCE OF TRUTH for the booking.
--   The seat.booking_id is a convenience pointer, not the canonical record.
-- ============================================================================
CREATE TABLE bookings (
    booking_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    event_id UUID NOT NULL REFERENCES events(event_id),
    
    -- Booking details
    booking_status VARCHAR(20) DEFAULT 'PENDING',
    -- PENDING    → Seats locked, awaiting payment
    -- CONFIRMED  → Paid and confirmed
    -- CANCELLED  → User cancelled or payment failed
    -- EXPIRED    → Lock timer ran out before payment
    
    total_seats INTEGER NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    
    -- Timing
    booking_expires_at TIMESTAMP,         -- When the hold expires
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    
    -- Extras
    promo_code VARCHAR(50),
    discount_amount DECIMAL(8,2) DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_bookings_user ON bookings(user_id, created_at DESC);
CREATE INDEX idx_bookings_event ON bookings(event_id);

-- WHY this index? Background job finds expired pending bookings to release seats.
CREATE INDEX idx_bookings_expires ON bookings(booking_expires_at) 
    WHERE booking_status = 'PENDING';

-- ============================================================================
-- TABLE: booking_seats
-- WHY: This is a JUNCTION TABLE (Many-to-Many relationship resolver).
--      One booking has many seats, and (over time) one seat could appear
--      in many bookings (booked, cancelled, re-booked by someone else).
--
--      Without this table, we'd need an array of seat_ids in bookings
--      (loses referential integrity) or duplicate booking data per seat.
--
-- RELATIONSHIP: booking_seats → bookings (Many-to-One)
-- RELATIONSHIP: booking_seats → seats (Many-to-One)
--   Together they form: bookings ←→ seats (Many-to-Many)
--
-- WHY store seat_price here instead of just reading from seats table?
--   Because prices can change! If we re-price the event after booking,
--   the user should be charged what they AGREED to, not the new price.
--   This is called "price snapshotting" — capture price at transaction time.
-- ============================================================================
CREATE TABLE booking_seats (
    id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(booking_id) ON DELETE CASCADE,
    seat_id UUID NOT NULL REFERENCES seats(seat_id),
    seat_price DECIMAL(8,2) NOT NULL,     -- Price snapshot at booking time
    
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_booking_seats_booking ON booking_seats(booking_id);
CREATE INDEX idx_booking_seats_seat ON booking_seats(seat_id);

-- ============================================================================
-- TABLE: payments
-- WHY: Same reasoning as parking lot system — separated from bookings because:
--      1. Multiple payment attempts per booking (first fails, retry succeeds)
--      2. Payment has its own lifecycle and external references
--      3. Refunds are separate payment records (negative amount, linked to original)
--      4. PCI compliance: isolate payment data
--
-- RELATIONSHIP: payments → bookings (Many-to-One)
--   WHY Many-to-One? A booking may have:
--   - Failed first payment + successful retry
--   - Original payment + partial refund + full refund
-- ============================================================================
CREATE TABLE payments (
    payment_id UUID PRIMARY KEY,
    booking_id UUID NOT NULL REFERENCES bookings(booking_id),
    amount DECIMAL(10,2) NOT NULL,
    payment_type VARCHAR(20) NOT NULL,    -- CHARGE, REFUND
    payment_method VARCHAR(20) NOT NULL,  -- CREDIT_CARD, UPI, WALLET
    payment_status VARCHAR(20) DEFAULT 'PENDING',
    transaction_reference VARCHAR(100),
    paid_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_payments_booking ON payments(booking_id);
```

### Entity Relationship Summary

```
venues (1) ──── (N) events (1) ──── (N) seats
                      │                    │
                      │ (1)                │ (N)
                      │                    │
                 bookings (N) ────── booking_seats (junction)
                      │
                      │ (1)
                      │
                 payments (N)

KEY RELATIONSHIPS:
- venues → events: A venue HOSTS many events (1 cinema, many shows)
- events → seats: An event HAS many seats (generated from venue layout)
- bookings → booking_seats → seats: Many-to-Many via junction table
  (1 booking = multiple seats, 1 seat = multiple bookings over time)
- bookings → payments: 1 booking can have multiple payment attempts/refunds
```

---

## Class Diagram

```
┌──────────────────────────────────────────────────────────────────────────────┐
│                        <<interface>>                                         │
│                      SeatLockingStrategy                                     │
│  ──────────────────────────────────────                                     │
│  + lockSeats(eventId, seatIds, userId): LockResult                          │
│  + releaseLock(eventId, seatIds): void                                      │
│  + isLocked(seatId): boolean                                                │
├──────────────────────────────────────────────────────────────────────────────┤
│           ▲                              ▲                                   │
│  OptimisticLockStrategy         PessimisticLockStrategy                      │
│  (DB version check)             (Redis distributed lock)                     │
└──────────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────────┐
│                        <<interface>>                                         │
│                       PricingStrategy                                        │
│  ──────────────────────────────────────                                     │
│  + calculatePrice(seat, event): Money                                       │
├──────────────────────────────────────────────────────────────────────────────┤
│       ▲               ▲                    ▲                                 │
│  FixedPricing   DemandBasedPricing   EarlyBirdPricing                       │
└──────────────────────────────────────────────────────────────────────────────┘

┌────────────────┐ uses  ┌────────────────┐ uses  ┌────────────────┐
│ BookingService │──────>│ SeatService    │──────>│ PaymentService │
│                │       │                │       │                │
│ + createBooking│       │ + lockSeats()  │       │ + charge()     │
│ + confirmBooking       │ + releaseSeats │       │ + refund()     │
│ + cancelBooking│       │ + getAvailable │       │                │
└────────────────┘       └────────────────┘       └────────────────┘
                                │
                                │ uses
                                ▼
                      ┌────────────────────┐
                      │ SeatLockingStrategy│
                      └────────────────────┘
```

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | SeatLockingStrategy, PricingStrategy | Swap locking mechanism (optimistic vs pessimistic) and pricing algorithm without changing service code |
| **Template Method** | BookingWorkflow | Define skeleton of booking flow (lock → calculate → pay → confirm) with customizable steps |
| **Observer** | SeatStatusObserver | Notify dashboard, analytics, waitlist when seat status changes |
| **Factory** | SeatFactory | Generate seats from venue layout template |
| **State** | BookingState | Booking transitions (PENDING → CONFIRMED → CANCELLED) with state-specific behavior |
| **Command** | BookingCommand | Encapsulate booking operations for undo/redo (cancellation = undo of booking) |

## SOLID Principles Applied

| Principle | How Applied |
|-----------|------------|
| **S - Single Responsibility** | `SeatService` manages seats only. `BookingService` orchestrates bookings. `PaymentService` handles money. Each service has ONE reason to change. |
| **O - Open/Closed** | Adding "IMAX" seat type doesn't change SeatService. Adding "Auction pricing" just means a new PricingStrategy implementation. |
| **L - Liskov Substitution** | `OptimisticLockStrategy` and `PessimisticLockStrategy` are interchangeable. `BookingService` works with either — it just calls `lockSeats()`. |
| **I - Interface Segregation** | `SeatLockingStrategy` only has lock/release methods. `PricingStrategy` only has `calculatePrice()`. No fat interfaces. |
| **D - Dependency Inversion** | `BookingService` depends on `SeatLockingStrategy` interface, NOT on `RedisLockStrategy` directly. Strategies are injected. |

---

## Code Implementation

### Enums & Value Objects

```java
public enum SeatType {
    VIP(4),
    PREMIUM(3),
    REGULAR(2),
    BALCONY(1);

    private final int tier;
    SeatType(int tier) { this.tier = tier; }
    public int getTier() { return tier; }
}

public enum SeatStatus {
    AVAILABLE,
    LOCKED,      // Temporarily held during checkout
    BOOKED,      // Paid and confirmed
    BLOCKED      // Not for sale
}

public enum BookingStatus {
    PENDING,     // Seats locked, awaiting payment
    CONFIRMED,   // Paid
    CANCELLED,   // User cancelled
    EXPIRED      // Lock timed out
}

public enum EventType {
    MOVIE, CONCERT, SPORTS, THEATER
}
```

### Model Classes

```java
// ============================================================================
// MODEL: Seat
// OOP: Encapsulation — Seat manages its own lock state. External code
// cannot directly set locked_by or locked_until. Must go through lock/unlock
// methods which enforce business rules.
//
// Thread Safety: All state-changing methods are synchronized.
// In a distributed system, we'd use Redis locks instead (see SeatLockingStrategy).
// ============================================================================
public class Seat {
    private final String seatId;
    private final String eventId;
    private final String section;
    private final String rowNumber;
    private final String seatNumber;
    private final SeatType seatType;
    private final BigDecimal basePrice;

    private SeatStatus status;
    private String lockedByUserId;
    private LocalDateTime lockedUntil;
    private String bookingId;
    
    // Version field for Optimistic Locking (prevents lost updates)
    private long version;

    public Seat(String seatId, String eventId, String section, String rowNumber,
                String seatNumber, SeatType seatType, BigDecimal basePrice) {
        this.seatId = seatId;
        this.eventId = eventId;
        this.section = section;
        this.rowNumber = rowNumber;
        this.seatNumber = seatNumber;
        this.seatType = seatType;
        this.basePrice = basePrice;
        this.status = SeatStatus.AVAILABLE;
        this.version = 0;
    }

    /**
     * Attempt to lock this seat for a user.
     * Lock expires after the given duration (typically 10 minutes).
     *
     * WHY synchronized? In-process thread safety. In a real distributed system,
     * the SeatLockingStrategy handles distributed locking via Redis.
     * This synchronized block is a LOCAL safety net.
     *
     * @throws SeatNotAvailableException if seat is already locked or booked
     */
    public synchronized void lock(String userId, Duration lockDuration) {
        if (status != SeatStatus.AVAILABLE) {
            throw new SeatNotAvailableException(
                String.format("Seat %s-%s-%s is %s, cannot lock", 
                    section, rowNumber, seatNumber, status));
        }
        this.status = SeatStatus.LOCKED;
        this.lockedByUserId = userId;
        this.lockedUntil = LocalDateTime.now().plus(lockDuration);
        this.version++;
    }

    /**
     * Release lock on this seat (timeout or explicit release).
     * Only the user who locked it (or the system for expired locks) can release.
     */
    public synchronized void releaseLock(String userId) {
        if (status != SeatStatus.LOCKED) {
            return; // Idempotent — releasing an unlocked seat is a no-op
        }
        if (lockedByUserId != null && !lockedByUserId.equals(userId) 
            && !"SYSTEM".equals(userId)) {
            throw new UnauthorizedAccessException("Only lock owner or system can release");
        }
        this.status = SeatStatus.AVAILABLE;
        this.lockedByUserId = null;
        this.lockedUntil = null;
        this.version++;
    }

    /**
     * Confirm booking — transition from LOCKED to BOOKED.
     * Only works if seat is currently locked by the same user.
     */
    public synchronized void confirmBooking(String userId, String bookingId) {
        if (status != SeatStatus.LOCKED) {
            throw new IllegalStateException("Seat must be LOCKED before confirming");
        }
        if (!lockedByUserId.equals(userId)) {
            throw new UnauthorizedAccessException("Only lock owner can confirm booking");
        }
        this.status = SeatStatus.BOOKED;
        this.bookingId = bookingId;
        this.lockedByUserId = null;
        this.lockedUntil = null;
        this.version++;
    }

    /**
     * Check if the lock has expired.
     * Used by background job to auto-release stale locks.
     */
    public boolean isLockExpired() {
        return status == SeatStatus.LOCKED 
            && lockedUntil != null 
            && LocalDateTime.now().isAfter(lockedUntil);
    }

    public boolean isAvailable() { return status == SeatStatus.AVAILABLE; }
    
    // Getters
    public String getSeatId() { return seatId; }
    public String getDisplayLabel() { return section + "-" + rowNumber + "-" + seatNumber; }
    public SeatType getSeatType() { return seatType; }
    public BigDecimal getBasePrice() { return basePrice; }
    public SeatStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public String getLockedByUserId() { return lockedByUserId; }
}

// ============================================================================
// MODEL: Event
// Holds event metadata. Manages total/available seat counts.
// ============================================================================
public class Event {
    private final String eventId;
    private final String venueId;
    private final String eventName;
    private final EventType eventType;
    private final LocalDateTime eventDate;
    private final LocalDateTime bookingOpensAt;
    private final int maxTicketsPerUser;

    private int totalSeats;
    private int availableSeats;

    public Event(String eventId, String venueId, String eventName, EventType eventType,
                 LocalDateTime eventDate, LocalDateTime bookingOpensAt, int maxTicketsPerUser,
                 int totalSeats) {
        this.eventId = eventId;
        this.venueId = venueId;
        this.eventName = eventName;
        this.eventType = eventType;
        this.eventDate = eventDate;
        this.bookingOpensAt = bookingOpensAt;
        this.maxTicketsPerUser = maxTicketsPerUser;
        this.totalSeats = totalSeats;
        this.availableSeats = totalSeats;
    }

    public boolean isBookingOpen() {
        LocalDateTime now = LocalDateTime.now();
        return now.isAfter(bookingOpensAt) && now.isBefore(eventDate);
    }

    public synchronized void decrementAvailable(int count) {
        if (availableSeats < count) {
            throw new InsufficientSeatsException("Not enough seats available");
        }
        this.availableSeats -= count;
    }

    public synchronized void incrementAvailable(int count) {
        this.availableSeats = Math.min(totalSeats, availableSeats + count);
    }

    // Getters
    public String getEventId() { return eventId; }
    public String getEventName() { return eventName; }
    public int getMaxTicketsPerUser() { return maxTicketsPerUser; }
    public int getAvailableSeats() { return availableSeats; }
}

// ============================================================================
// MODEL: Booking
// PATTERN: State — Booking behavior changes based on its current status.
// A PENDING booking can be confirmed or expired.
// A CONFIRMED booking can be cancelled.
// A CANCELLED or EXPIRED booking cannot change further.
// ============================================================================
public class Booking {
    private final String bookingId;
    private final String userId;
    private final String eventId;
    private final List<Seat> seats;
    private final BigDecimal totalAmount;
    private final LocalDateTime expiresAt;

    private BookingStatus status;
    private LocalDateTime confirmedAt;
    private LocalDateTime cancelledAt;

    public Booking(String bookingId, String userId, String eventId,
                   List<Seat> seats, BigDecimal totalAmount, LocalDateTime expiresAt) {
        this.bookingId = bookingId;
        this.userId = userId;
        this.eventId = eventId;
        this.seats = new ArrayList<>(seats);
        this.totalAmount = totalAmount;
        this.expiresAt = expiresAt;
        this.status = BookingStatus.PENDING;
    }

    /**
     * PATTERN: State — Confirm is only valid from PENDING state.
     * This encapsulates valid state transitions inside the model.
     * External code can't set status to CONFIRMED from CANCELLED.
     */
    public void confirm() {
        if (status != BookingStatus.PENDING) {
            throw new IllegalStateException("Can only confirm a PENDING booking, current: " + status);
        }
        this.status = BookingStatus.CONFIRMED;
        this.confirmedAt = LocalDateTime.now();
    }

    public void cancel() {
        if (status != BookingStatus.PENDING && status != BookingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot cancel a " + status + " booking");
        }
        this.status = BookingStatus.CANCELLED;
        this.cancelledAt = LocalDateTime.now();
    }

    public void expire() {
        if (status != BookingStatus.PENDING) {
            throw new IllegalStateException("Only PENDING bookings can expire");
        }
        this.status = BookingStatus.EXPIRED;
    }

    public boolean isExpired() {
        return status == BookingStatus.PENDING && LocalDateTime.now().isAfter(expiresAt);
    }

    // Getters
    public String getBookingId() { return bookingId; }
    public String getUserId() { return userId; }
    public String getEventId() { return eventId; }
    public List<Seat> getSeats() { return Collections.unmodifiableList(seats); }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BookingStatus getStatus() { return status; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
```

### Strategy Implementations

```java
// ============================================================================
// INTERFACE: SeatLockingStrategy
// PATTERN: Strategy — Different locking mechanisms for different scale needs.
// WHY? At low scale, DB optimistic locking is fine. At high scale (flash sales),
// we need Redis distributed locks for speed. Strategy lets us swap without
// changing BookingService.
// SOLID (D): BookingService depends on this interface, not Redis/DB directly.
// ============================================================================
public interface SeatLockingStrategy {
    /**
     * Attempt to lock multiple seats atomically.
     * Either ALL seats are locked, or NONE (all-or-nothing).
     */
    LockResult lockSeats(String eventId, List<String> seatIds, String userId, Duration lockDuration);
    
    void releaseSeats(String eventId, List<String> seatIds, String userId);
    
    boolean isSeatLocked(String seatId);
}

// ============================================================================
// Optimistic Locking Strategy (Database-based)
// HOW: Read seat with version → Check available → Update with version check.
//      If version changed between read and write, another user got there first.
// WHEN: Low-medium concurrency (regular movies, weekday shows)
// WHY NOT always use this? Under extreme contention (concert flash sale),
//      optimistic locking causes many retries, wasting DB resources.
// ============================================================================
public class OptimisticLockStrategy implements SeatLockingStrategy {
    
    private final SeatRepository seatRepository;

    public OptimisticLockStrategy(SeatRepository seatRepository) {
        this.seatRepository = seatRepository;
    }

    @Override
    public LockResult lockSeats(String eventId, List<String> seatIds, 
                                String userId, Duration lockDuration) {
        List<Seat> lockedSeats = new ArrayList<>();
        
        try {
            for (String seatId : seatIds) {
                Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new SeatNotFoundException("Seat not found: " + seatId));
                
                // Optimistic lock: Update only if version matches
                // SQL: UPDATE seats SET status='LOCKED', version=version+1 
                //      WHERE seat_id=? AND version=? AND status='AVAILABLE'
                long currentVersion = seat.getVersion();
                seat.lock(userId, lockDuration);
                
                int updated = seatRepository.updateWithVersionCheck(seat, currentVersion);
                if (updated == 0) {
                    // Version mismatch — another user modified this seat
                    throw new SeatNotAvailableException("Seat " + seatId + " was taken");
                }
                
                lockedSeats.add(seat);
            }
            
            return new LockResult(true, lockedSeats, "Seats locked successfully");
            
        } catch (Exception e) {
            // ROLLBACK: If any seat fails, release all previously locked seats
            for (Seat seat : lockedSeats) {
                seat.releaseLock(userId);
                seatRepository.save(seat);
            }
            return new LockResult(false, List.of(), "Lock failed: " + e.getMessage());
        }
    }

    @Override
    public void releaseSeats(String eventId, List<String> seatIds, String userId) {
        for (String seatId : seatIds) {
            seatRepository.findById(seatId).ifPresent(seat -> {
                seat.releaseLock(userId);
                seatRepository.save(seat);
            });
        }
    }

    @Override
    public boolean isSeatLocked(String seatId) {
        return seatRepository.findById(seatId)
            .map(seat -> seat.getStatus() == SeatStatus.LOCKED && !seat.isLockExpired())
            .orElse(false);
    }
}

// ============================================================================
// Pessimistic Locking Strategy (Redis-based distributed lock)
// HOW: Use Redis SET NX (Set if Not eXists) for atomic lock acquisition.
//      Redis is single-threaded, so SET NX is naturally atomic.
// WHEN: High concurrency (flash sales, concert releases with 100K users)
// WHY REDIS? 1) Sub-millisecond latency, 2) Built-in TTL for auto-expiry,
//      3) Atomic operations prevent race conditions, 4) Scales horizontally.
// ============================================================================
public class PessimisticLockStrategy implements SeatLockingStrategy {
    
    private final RedisTemplate<String, String> redis;
    private final SeatRepository seatRepository;
    
    private static final String LOCK_PREFIX = "seat_lock:";

    public PessimisticLockStrategy(RedisTemplate<String, String> redis,
                                    SeatRepository seatRepository) {
        this.redis = redis;
        this.seatRepository = seatRepository;
    }

    @Override
    public LockResult lockSeats(String eventId, List<String> seatIds, 
                                String userId, Duration lockDuration) {
        List<String> acquiredLocks = new ArrayList<>();
        
        try {
            // STEP 1: Try to acquire Redis locks for ALL seats atomically
            for (String seatId : seatIds) {
                String lockKey = LOCK_PREFIX + seatId;
                
                // SET NX = Set if Not eXists. Returns true only if key didn't exist.
                // This is ATOMIC in Redis (single-threaded). No race condition possible.
                Boolean acquired = redis.opsForValue()
                    .setIfAbsent(lockKey, userId, lockDuration);
                
                if (Boolean.FALSE.equals(acquired)) {
                    // Someone else already locked this seat
                    throw new SeatNotAvailableException("Seat " + seatId + " is locked");
                }
                
                acquiredLocks.add(seatId);
            }
            
            // STEP 2: All Redis locks acquired — now update database
            List<Seat> seats = new ArrayList<>();
            for (String seatId : seatIds) {
                Seat seat = seatRepository.findById(seatId)
                    .orElseThrow(() -> new SeatNotFoundException(seatId));
                seat.lock(userId, lockDuration);
                seatRepository.save(seat);
                seats.add(seat);
            }
            
            return new LockResult(true, seats, "Seats locked via Redis");
            
        } catch (Exception e) {
            // ROLLBACK: Release all acquired Redis locks
            for (String seatId : acquiredLocks) {
                redis.delete(LOCK_PREFIX + seatId);
            }
            return new LockResult(false, List.of(), "Lock failed: " + e.getMessage());
        }
    }

    @Override
    public void releaseSeats(String eventId, List<String> seatIds, String userId) {
        for (String seatId : seatIds) {
            String lockKey = LOCK_PREFIX + seatId;
            
            // Only release if current user owns the lock (Lua script for atomicity)
            String lockOwner = redis.opsForValue().get(lockKey);
            if (userId.equals(lockOwner)) {
                redis.delete(lockKey);
            }
            
            // Also update database
            seatRepository.findById(seatId).ifPresent(seat -> {
                seat.releaseLock(userId);
                seatRepository.save(seat);
            });
        }
    }

    @Override
    public boolean isSeatLocked(String seatId) {
        return Boolean.TRUE.equals(redis.hasKey(LOCK_PREFIX + seatId));
    }
}

// Result DTO for lock operations
public record LockResult(boolean success, List<Seat> lockedSeats, String message) {}

// ============================================================================
// INTERFACE: PricingStrategy
// Different events price differently. Movies use fixed pricing.
// Concerts use demand-based pricing. Sports use early-bird discounts.
// ============================================================================
public interface PricingStrategy {
    BigDecimal calculatePrice(Seat seat, Event event);
}

public class FixedPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculatePrice(Seat seat, Event event) {
        return seat.getBasePrice();
    }
}

// ============================================================================
// Demand-based pricing: Price increases as seats fill up.
// WHY? Concerts and popular events use dynamic pricing to maximize revenue.
// As availability drops below thresholds, price increases.
// ============================================================================
public class DemandBasedPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculatePrice(Seat seat, Event event) {
        BigDecimal base = seat.getBasePrice();
        double fillRate = 1.0 - ((double) event.getAvailableSeats() / event.getTotalSeats());
        
        // Price multiplier based on fill rate
        BigDecimal multiplier;
        if (fillRate > 0.9) {
            multiplier = new BigDecimal("1.50");      // 90%+ full → 50% premium
        } else if (fillRate > 0.7) {
            multiplier = new BigDecimal("1.25");      // 70-90% → 25% premium
        } else {
            multiplier = BigDecimal.ONE;              // Under 70% → base price
        }
        
        return base.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }
}
```

### Core Services

```java
// ============================================================================
// SeatService — Manages seat availability and locking.
//
// SOLID (S): Only handles seat operations. Doesn't know about bookings or payments.
// SOLID (D): Depends on SeatLockingStrategy interface, injected via constructor.
// ============================================================================
public class SeatService {
    
    private final SeatRepository seatRepository;
    private final SeatLockingStrategy lockingStrategy;
    private final List<SeatStatusObserver> observers;

    /**
     * Constructor Injection — Dependencies provided externally.
     * WHY? Testability (mock the locking strategy) + Flexibility (swap strategies).
     */
    public SeatService(SeatRepository seatRepository, SeatLockingStrategy lockingStrategy) {
        this.seatRepository = seatRepository;
        this.lockingStrategy = lockingStrategy;
        this.observers = new ArrayList<>();
    }

    public void addObserver(SeatStatusObserver observer) {
        observers.add(observer);
    }

    public List<Seat> getAvailableSeats(String eventId) {
        return seatRepository.findByEventIdAndStatus(eventId, SeatStatus.AVAILABLE);
    }

    public Map<SeatType, List<Seat>> getAvailableSeatsByType(String eventId) {
        return getAvailableSeats(eventId).stream()
            .collect(Collectors.groupingBy(Seat::getSeatType));
    }

    /**
     * Lock seats for a user during checkout.
     * All-or-nothing: either ALL seats are locked, or NONE.
     */
    public LockResult lockSeats(String eventId, List<String> seatIds, String userId) {
        Duration lockDuration = Duration.ofMinutes(10);
        LockResult result = lockingStrategy.lockSeats(eventId, seatIds, userId, lockDuration);
        
        if (result.success()) {
            notifyObservers(result.lockedSeats(), SeatStatus.AVAILABLE, SeatStatus.LOCKED);
        }
        
        return result;
    }

    public void releaseSeats(String eventId, List<String> seatIds, String userId) {
        lockingStrategy.releaseSeats(eventId, seatIds, userId);
        // Notify observers that seats are available again
    }

    /**
     * Confirm seats as booked (after payment succeeds).
     */
    public void confirmSeats(List<Seat> seats, String userId, String bookingId) {
        for (Seat seat : seats) {
            seat.confirmBooking(userId, bookingId);
            seatRepository.save(seat);
        }
        notifyObservers(seats, SeatStatus.LOCKED, SeatStatus.BOOKED);
    }

    private void notifyObservers(List<Seat> seats, SeatStatus from, SeatStatus to) {
        for (SeatStatusObserver obs : observers) {
            for (Seat seat : seats) {
                obs.onSeatStatusChanged(seat, from, to);
            }
        }
    }
}

// ============================================================================
// BookingService — The main orchestrator for the booking workflow.
//
// PATTERN: Template Method — The booking flow follows a fixed sequence:
//   1. Validate → 2. Lock seats → 3. Calculate price → 4. Create booking
//   Each step can be customized (pricing strategy, locking strategy).
//
// SOLID (S): Orchestrates the booking flow. Delegates seat locking to SeatService,
//   pricing to PricingStrategy, payment to PaymentService.
// SOLID (O): Adding new pricing or locking doesn't change this class.
// SOLID (D): All dependencies are interfaces, injected via constructor.
// ============================================================================
public class BookingService {

    private final SeatService seatService;
    private final PricingStrategy pricingStrategy;
    private final EventRepository eventRepository;
    private final BookingRepository bookingRepository;

    public BookingService(SeatService seatService, PricingStrategy pricingStrategy,
                          EventRepository eventRepository, BookingRepository bookingRepository) {
        this.seatService = seatService;
        this.pricingStrategy = pricingStrategy;
        this.eventRepository = eventRepository;
        this.bookingRepository = bookingRepository;
    }

    /**
     * Create a booking: Lock seats → Calculate total → Save booking.
     * This returns a PENDING booking. User must pay within 10 minutes.
     *
     * FLOW:
     * 1. Validate event is open for booking
     * 2. Validate user hasn't exceeded max tickets
     * 3. Lock selected seats (via SeatLockingStrategy)
     * 4. Calculate total price (via PricingStrategy)
     * 5. Create PENDING booking record
     */
    public Booking createBooking(String userId, String eventId, List<String> seatIds) {
        // STEP 1: Validate event
        Event event = eventRepository.findById(eventId)
            .orElseThrow(() -> new EventNotFoundException("Event not found: " + eventId));
        
        if (!event.isBookingOpen()) {
            throw new BookingNotOpenException("Booking is not open for " + event.getEventName());
        }

        // STEP 2: Validate ticket limit per user
        int existingBookings = bookingRepository.countByUserAndEvent(userId, eventId);
        if (existingBookings + seatIds.size() > event.getMaxTicketsPerUser()) {
            throw new MaxTicketsExceededException(
                "Max " + event.getMaxTicketsPerUser() + " tickets per user");
        }

        // STEP 3: Lock seats (delegates to SeatService → SeatLockingStrategy)
        LockResult lockResult = seatService.lockSeats(eventId, seatIds, userId);
        if (!lockResult.success()) {
            throw new SeatNotAvailableException(lockResult.message());
        }

        // STEP 4: Calculate total price using pricing strategy
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (Seat seat : lockResult.lockedSeats()) {
            BigDecimal price = pricingStrategy.calculatePrice(seat, event);
            totalAmount = totalAmount.add(price);
        }

        // STEP 5: Create PENDING booking
        Booking booking = new Booking(
            UUID.randomUUID().toString(),
            userId,
            eventId,
            lockResult.lockedSeats(),
            totalAmount,
            LocalDateTime.now().plusMinutes(10)  // Expires in 10 minutes
        );

        bookingRepository.save(booking);
        event.decrementAvailable(seatIds.size());

        return booking;
    }

    /**
     * Confirm booking after successful payment.
     * Transitions seats from LOCKED → BOOKED.
     */
    public Booking confirmBooking(String bookingId, String userId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (booking.isExpired()) {
            cancelBookingInternal(booking, "Booking expired");
            throw new BookingExpiredException("Booking has expired");
        }

        // Confirm booking (State Pattern: validates PENDING → CONFIRMED transition)
        booking.confirm();
        
        // Confirm all seats (LOCKED → BOOKED)
        seatService.confirmSeats(booking.getSeats(), userId, bookingId);
        
        bookingRepository.save(booking);
        return booking;
    }

    /**
     * Cancel a booking. Release all seats.
     * For CONFIRMED bookings, refund logic would be handled by PaymentService.
     */
    public Booking cancelBooking(String bookingId, String userId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId));

        if (!booking.getUserId().equals(userId)) {
            throw new UnauthorizedAccessException("Only booking owner can cancel");
        }

        cancelBookingInternal(booking, "User cancelled");
        return booking;
    }

    private void cancelBookingInternal(Booking booking, String reason) {
        booking.cancel();
        
        // Release all locked/booked seats back to AVAILABLE
        List<String> seatIds = booking.getSeats().stream()
            .map(Seat::getSeatId)
            .collect(Collectors.toList());
        
        seatService.releaseSeats(booking.getEventId(), seatIds, booking.getUserId());
        
        // Restore available count
        Event event = eventRepository.findById(booking.getEventId()).orElseThrow();
        event.incrementAvailable(booking.getSeats().size());
        
        bookingRepository.save(booking);
    }
}

// ============================================================================
// Background job: Release expired locks and bookings.
// WHY? If a user locks seats but never pays (closes browser, loses connection),
// seats stay locked forever without this cleanup job.
// Runs every minute to find and release expired locks.
// ============================================================================
public class BookingExpirationJob {

    private final BookingRepository bookingRepository;
    private final SeatService seatService;
    private final EventRepository eventRepository;

    public BookingExpirationJob(BookingRepository bookingRepository,
                                 SeatService seatService,
                                 EventRepository eventRepository) {
        this.bookingRepository = bookingRepository;
        this.seatService = seatService;
        this.eventRepository = eventRepository;
    }

    /**
     * Scheduled job: Runs every 60 seconds.
     * Finds PENDING bookings past their expiration time and releases seats.
     * 
     * In Spring Boot: @Scheduled(fixedRate = 60000)
     */
    public void releaseExpiredBookings() {
        List<Booking> expired = bookingRepository.findExpiredPendingBookings(LocalDateTime.now());
        
        for (Booking booking : expired) {
            try {
                booking.expire();
                
                List<String> seatIds = booking.getSeats().stream()
                    .map(Seat::getSeatId)
                    .collect(Collectors.toList());
                
                seatService.releaseSeats(booking.getEventId(), seatIds, "SYSTEM");
                
                Event event = eventRepository.findById(booking.getEventId()).orElseThrow();
                event.incrementAvailable(seatIds.size());
                
                bookingRepository.save(booking);
                
                System.out.println("Expired booking: " + booking.getBookingId());
            } catch (Exception e) {
                System.err.println("Failed to expire booking: " + booking.getBookingId());
            }
        }
    }
}
```

### Observer Pattern

```java
// ============================================================================
// PATTERN: Observer — Decouple seat status changes from reactions.
// WHY? When a seat becomes available (lock expired), multiple things happen:
//   1. Update the real-time seat map on all connected clients
//   2. Notify waitlisted users
//   3. Update analytics counters
// Without Observer, SeatService would need to know about ALL these systems.
// ============================================================================
public interface SeatStatusObserver {
    void onSeatStatusChanged(Seat seat, SeatStatus oldStatus, SeatStatus newStatus);
}

// Updates the real-time seat map via WebSocket
public class SeatMapBroadcastObserver implements SeatStatusObserver {
    private final WebSocketService webSocketService;

    public SeatMapBroadcastObserver(WebSocketService webSocketService) {
        this.webSocketService = webSocketService;
    }

    @Override
    public void onSeatStatusChanged(Seat seat, SeatStatus oldStatus, SeatStatus newStatus) {
        webSocketService.broadcast("event:" + seat.getEventId(), Map.of(
            "seatId", seat.getSeatId(),
            "label", seat.getDisplayLabel(),
            "oldStatus", oldStatus.name(),
            "newStatus", newStatus.name()
        ));
    }
}

// Notifies waitlisted users when seats become available
public class WaitlistNotificationObserver implements SeatStatusObserver {
    private final WaitlistService waitlistService;

    public WaitlistNotificationObserver(WaitlistService waitlistService) {
        this.waitlistService = waitlistService;
    }

    @Override
    public void onSeatStatusChanged(Seat seat, SeatStatus oldStatus, SeatStatus newStatus) {
        if (newStatus == SeatStatus.AVAILABLE && oldStatus == SeatStatus.LOCKED) {
            // Seat became available (lock expired) — notify waitlist
            waitlistService.notifyNextInLine(seat.getEventId(), seat.getSeatType());
        }
    }
}
```

### Putting It Together

```java
public class TicketBookingDemo {
    public static void main(String[] args) {
        // ── Setup repositories (in-memory for demo) ──────────────────
        SeatRepository seatRepo = new InMemorySeatRepository();
        EventRepository eventRepo = new InMemoryEventRepository();
        BookingRepository bookingRepo = new InMemoryBookingRepository();

        // ── Create event with seats ──────────────────────────────────
        Event concert = new Event("evt-1", "venue-1", "Coldplay Concert",
            EventType.CONCERT, LocalDateTime.now().plusDays(30),
            LocalDateTime.now().minusDays(1), 6, 100);
        eventRepo.save(concert);

        // Create seats for the event
        for (int i = 1; i <= 20; i++) {
            Seat seat = new Seat("seat-" + i, "evt-1", "A", "1",
                String.valueOf(i), i <= 5 ? SeatType.VIP : SeatType.REGULAR,
                i <= 5 ? new BigDecimal("500") : new BigDecimal("200"));
            seatRepo.save(seat);
        }

        // ── Choose strategies (Dependency Injection) ─────────────────
        
        // For flash sale → use Redis pessimistic locking
        // For regular shows → use DB optimistic locking
        SeatLockingStrategy lockingStrategy = new OptimisticLockStrategy(seatRepo);
        
        SeatService seatService = new SeatService(seatRepo, lockingStrategy);
        
        // Concert → demand-based pricing
        PricingStrategy pricing = new DemandBasedPricingStrategy();
        
        BookingService bookingService = new BookingService(
            seatService, pricing, eventRepo, bookingRepo);

        // ── Flow: User books tickets ─────────────────────────────────
        
        // 1. Browse available seats
        List<Seat> available = seatService.getAvailableSeats("evt-1");
        System.out.println("Available seats: " + available.size());

        // 2. Select seats and create booking (locks seats for 10 min)
        Booking booking = bookingService.createBooking(
            "user-1", "evt-1", List.of("seat-1", "seat-2", "seat-6"));
        System.out.println("Booking created: " + booking.getBookingId());
        System.out.println("Status: " + booking.getStatus()); // PENDING
        System.out.println("Total: ₹" + booking.getTotalAmount());
        System.out.println("Expires at: " + booking.getExpiresAt());

        // 3. User pays (handled by PaymentService, not shown here)

        // 4. Confirm booking after payment
        Booking confirmed = bookingService.confirmBooking(booking.getBookingId(), "user-1");
        System.out.println("Status after confirm: " + confirmed.getStatus()); // CONFIRMED

        // Check available seats decreased
        available = seatService.getAvailableSeats("evt-1");
        System.out.println("Available after booking: " + available.size()); // 17
    }
}
```

---

## Concurrency Deep Dive

### How We Prevent Double Booking

```
LAYER 1: Application (SeatLockingStrategy)
  ├── Optimistic Lock: Read version → Update with version check → Retry on conflict
  └── Pessimistic Lock: Redis SET NX → Only one user can lock a seat at a time

LAYER 2: Database (SQL constraints)
  ├── UNIQUE(event_id, section, row, seat_number) → Can't create duplicate seats
  ├── Optimistic Lock: WHERE version = ? → Only succeeds if no one else changed it
  └── CHECK(seat_status IN ('AVAILABLE','LOCKED','BOOKED','BLOCKED')) → Valid states only

LAYER 3: Business Logic (Seat.lock() method)
  └── synchronized + state check → Even if two threads reach this code,
      only one succeeds. The other gets SeatNotAvailableException.

RESULT: Triple-layered protection. Even if one layer has a bug,
the other two prevent double booking.
```

### Optimistic vs Pessimistic Locking Decision

```
                    Low Contention              High Contention
                    (Regular movie)             (Concert flash sale)
                    
Optimistic Lock     ✅ Best choice              ❌ Too many retries
(DB version check)  - Low overhead              - Wastes DB connections
                    - Simple code               - Users see errors
                    
Pessimistic Lock    ❌ Overkill                 ✅ Best choice
(Redis SETNX)       - Unnecessary complexity    - Sub-ms lock acquisition
                    - Redis dependency          - No retries needed
                                                - Scales to 100K concurrent
```

---

## Edge Cases & Tests

```java
// 1. Two users try to book the same seat simultaneously
@Test
void concurrentBooking_sameSeat_onlyOneSucceeds() throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    
    Future<Booking> user1 = executor.submit(() -> 
        bookingService.createBooking("user-1", "evt-1", List.of("seat-1")));
    Future<Booking> user2 = executor.submit(() -> 
        bookingService.createBooking("user-2", "evt-1", List.of("seat-1")));
    
    // One succeeds, one throws SeatNotAvailableException
    int successes = 0;
    int failures = 0;
    try { user1.get(); successes++; } catch (ExecutionException e) { failures++; }
    try { user2.get(); successes++; } catch (ExecutionException e) { failures++; }
    
    assertEquals(1, successes, "Exactly one user should book the seat");
    assertEquals(1, failures, "The other user should fail");
}

// 2. Booking expires after 10 minutes without payment
@Test
void booking_expiresAfterTimeout_seatsReleased() {
    Booking booking = bookingService.createBooking("user-1", "evt-1", List.of("seat-1"));
    
    // Simulate time passing (in test, manually set expiry to past)
    booking.setExpiresAtForTest(LocalDateTime.now().minusMinutes(1));
    
    expirationJob.releaseExpiredBookings();
    
    assertEquals(BookingStatus.EXPIRED, booking.getStatus());
    Seat seat = seatRepo.findById("seat-1").get();
    assertEquals(SeatStatus.AVAILABLE, seat.getStatus());
}

// 3. User exceeds max tickets per event
@Test
void booking_exceedsMaxTickets_throwsException() {
    // Event allows max 4 tickets per user
    bookingService.createBooking("user-1", "evt-1", List.of("seat-1", "seat-2"));
    bookingService.confirmBooking(lastBookingId, "user-1");
    
    // Try to book 3 more (total would be 5 > max 4)
    assertThrows(MaxTicketsExceededException.class, () ->
        bookingService.createBooking("user-1", "evt-1", List.of("seat-3", "seat-4", "seat-5")));
}

// 4. Cancel confirmed booking → seats released
@Test
void cancelBooking_releasesSeats() {
    Booking booking = bookingService.createBooking("user-1", "evt-1", List.of("seat-1"));
    bookingService.confirmBooking(booking.getBookingId(), "user-1");
    
    bookingService.cancelBooking(booking.getBookingId(), "user-1");
    
    assertEquals(BookingStatus.CANCELLED, booking.getStatus());
    assertTrue(seatService.getAvailableSeats("evt-1").stream()
        .anyMatch(s -> s.getSeatId().equals("seat-1")));
}

// 5. Booking for event that hasn't opened yet
@Test
void booking_beforeOpeningTime_throwsException() {
    Event futureEvent = new Event("evt-future", "v1", "Future Show", EventType.MOVIE,
        LocalDateTime.now().plusDays(30), LocalDateTime.now().plusDays(7), 4, 100);
    
    assertThrows(BookingNotOpenException.class, () ->
        bookingService.createBooking("user-1", "evt-future", List.of("seat-1")));
}

// 6. Partial seat lock failure — ALL seats released (atomicity)
@Test
void lockSeats_partialFailure_rollsBackAll() {
    // Lock seat-1 by another user first
    seatService.lockSeats("evt-1", List.of("seat-1"), "user-other");
    
    // Try to lock seat-1 AND seat-2 — seat-1 should fail
    LockResult result = seatService.lockSeats("evt-1", List.of("seat-2", "seat-1"), "user-1");
    
    assertFalse(result.success());
    // seat-2 should NOT be locked either (all-or-nothing)
    assertFalse(seatService.isSeatLocked("seat-2"));
}
```

---

## Summary of Patterns & Principles

```
┌─────────────────────────────────────────────────────────────────┐
│  DESIGN PATTERNS                                                │
│                                                                 │
│  Strategy   → SeatLockingStrategy (Optimistic vs Pessimistic)  │
│             → PricingStrategy (Fixed vs Demand-based)           │
│  Observer   → SeatStatusObserver (broadcast, waitlist, analytics│
│  State      → BookingStatus transitions with validation         │
│  Builder    → Booking construction (optional fields)            │
│  Factory    → Seat generation from venue layout template        │
│  Template   → Booking workflow: validate→lock→price→save       │
│                                                                 │
│  SOLID PRINCIPLES                                               │
│                                                                 │
│  S → SeatService, BookingService, PaymentService (separate)    │
│  O → New pricing/locking strategies = new class, no changes    │
│  L → OptimisticLock & PessimisticLock are interchangeable      │
│  I → Small interfaces: SeatLockingStrategy, PricingStrategy    │
│  D → All services depend on interfaces, not implementations    │
│                                                                 │
│  CONCURRENCY HANDLING                                           │
│                                                                 │
│  Layer 1: Application → synchronized methods + Strategy         │
│  Layer 2: Cache → Redis SET NX for distributed locks           │
│  Layer 3: Database → Optimistic lock (version) + UNIQUE index  │
│  Result: ZERO double bookings guaranteed                        │
└─────────────────────────────────────────────────────────────────┘
```
