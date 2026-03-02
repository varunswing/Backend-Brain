# Hotel Booking System - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database Design with Explanations](#database-design-with-explanations)
4. [Design Patterns & SOLID](#design-patterns--solid)
5. [Code Implementation](#code-implementation)
6. [Edge Cases & Tests](#edge-cases--tests)

---

## Problem Statement

Design a hotel booking system (Booking.com / MakeMyTrip) that supports searching hotels, checking room availability for date ranges, dynamic pricing, and booking with no overbooking.

---

## Requirements

### Functional
1. Search hotels by city, dates, guests, filters
2. View room availability for a date range
3. Book rooms with date range (no overbooking)
4. Dynamic pricing (seasonal, demand-based)
5. Cancel booking with refund rules
6. Guest reviews and ratings

### Non-Functional
- < 200ms search, < 500ms booking
- Zero overbooking (strong consistency for inventory)
- 50M bookings/year, 100M users

---

## Database Design with Explanations

```sql
-- ============================================================================
-- TABLE: hotels
-- WHY: Top-level entity representing a physical hotel property.
-- Every other entity (rooms, bookings, reviews) traces back here.
-- ============================================================================
CREATE TABLE hotels (
    hotel_id UUID PRIMARY KEY,
    hotel_name VARCHAR(300) NOT NULL,
    city VARCHAR(100) NOT NULL,
    country VARCHAR(50) NOT NULL,
    address TEXT NOT NULL,
    latitude DECIMAL(10, 8),
    longitude DECIMAL(11, 8),
    star_rating INTEGER CHECK (star_rating BETWEEN 1 AND 5),
    amenities TEXT[],                       -- {pool, gym, spa, wifi, parking}
    check_in_time TIME DEFAULT '15:00',
    check_out_time TIME DEFAULT '11:00',
    cancellation_policy VARCHAR(20) DEFAULT 'FLEXIBLE',
    -- FLEXIBLE: Free cancel 24hr before | MODERATE: 3 days | STRICT: Non-refundable
    average_rating DECIMAL(3,2) DEFAULT 0,
    total_reviews INTEGER DEFAULT 0,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_hotels_city ON hotels(city, is_active);
CREATE INDEX idx_hotels_rating ON hotels(average_rating DESC) WHERE is_active = true;

-- ============================================================================
-- TABLE: room_types
-- WHY: A hotel has multiple room TYPES (Deluxe King, Standard Twin, Suite).
--      Separated from hotels because:
--      1. One hotel has 5-20 room types — this is a 1-to-Many relationship
--      2. Each type has different pricing, capacity, amenities
--      3. Inventory is tracked per room type, not per individual room
--         (unlike a ticket system where each seat is unique)
--
-- WHY NOT track individual rooms (Room 101, Room 102)?
--   Hotels don't sell specific rooms — they sell ROOM TYPES.
--   "Deluxe King, 3 available" is how hotels work. Individual room
--   assignment happens at check-in (hotel's internal system, not ours).
-- ============================================================================
CREATE TABLE room_types (
    room_type_id UUID PRIMARY KEY,
    hotel_id UUID NOT NULL REFERENCES hotels(hotel_id),
    room_name VARCHAR(200) NOT NULL,        -- "Deluxe King", "Standard Twin"
    bed_type VARCHAR(50) NOT NULL,          -- KING, QUEEN, TWIN, SOFA
    max_occupancy INTEGER NOT NULL DEFAULT 2,
    room_size_sqft INTEGER,
    amenities TEXT[],                       -- {minibar, balcony, sea_view}
    base_price_per_night DECIMAL(10,2) NOT NULL,
    total_rooms INTEGER NOT NULL,           -- How many rooms of this type exist
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_room_types_hotel ON room_types(hotel_id);

-- ============================================================================
-- TABLE: room_inventory
-- WHY: This is the KEY table for availability and pricing.
--      Each row = "Room Type X has Y rooms available on Date Z at Price P".
--
--      WHY PER-DATE rows instead of a single total_rooms count?
--        Because availability varies BY DATE:
--        - Jan 15: 8 rooms available (low season)
--        - Feb 14: 0 rooms available (Valentine's Day sold out)
--        - Mar 1: 5 rooms available (some bookings)
--        A single count can't represent this. We need one row per date.
--
--      WHY store price per date?
--        Dynamic pricing: same room costs $100 on Tuesday, $200 on Saturday,
--        $500 on New Year's Eve. Price is a function of date + demand.
--
-- RELATIONSHIP: room_inventory → room_types (Many-to-One)
--   Each room type has 365 inventory rows (one per day of the year).
--   This lets us answer "how many Deluxe Kings are available on March 15?"
-- ============================================================================
CREATE TABLE room_inventory (
    inventory_id UUID PRIMARY KEY,
    room_type_id UUID NOT NULL REFERENCES room_types(room_type_id),
    inventory_date DATE NOT NULL,
    total_rooms INTEGER NOT NULL,           -- Total rooms of this type
    available_rooms INTEGER NOT NULL,       -- How many are available
    price_per_night DECIMAL(10,2) NOT NULL, -- Price for THIS specific date
    demand_multiplier DECIMAL(4,2) DEFAULT 1.00, -- Surge pricing factor
    min_stay_nights INTEGER DEFAULT 1,
    is_blocked BOOLEAN DEFAULT false,       -- Hotel blocked this date (maintenance)
    
    -- UNIQUE: One inventory record per room type per date.
    -- WHY? Without this, we could accidentally create duplicate inventory,
    -- leading to phantom availability or double-counted rooms.
    UNIQUE(room_type_id, inventory_date),
    
    -- Version for optimistic locking (prevents race conditions on booking)
    version INTEGER DEFAULT 0
);

-- WHY this index? The #1 query: "Find available rooms for date range"
-- This composite index directly serves that query.
CREATE INDEX idx_inventory_date_avail ON room_inventory(room_type_id, inventory_date)
    WHERE available_rooms > 0 AND is_blocked = false;

-- ============================================================================
-- TABLE: bookings
-- WHY: Records every reservation. Ties user, hotel, room type, dates together.
--
-- WHY store room_rate_per_night AND total_amount?
--   Price snapshotting. If the hotel changes prices after booking, the
--   user pays what they agreed to at booking time.
-- ============================================================================
CREATE TABLE bookings (
    booking_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    hotel_id UUID NOT NULL REFERENCES hotels(hotel_id),
    room_type_id UUID NOT NULL REFERENCES room_types(room_type_id),
    booking_reference VARCHAR(20) UNIQUE NOT NULL,
    
    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,
    nights INTEGER NOT NULL,
    rooms_booked INTEGER DEFAULT 1,
    guests INTEGER DEFAULT 2,
    
    -- Price snapshot at booking time
    room_rate_per_night DECIMAL(10,2) NOT NULL,
    total_room_cost DECIMAL(12,2) NOT NULL,
    taxes DECIMAL(10,2) DEFAULT 0,
    total_amount DECIMAL(12,2) NOT NULL,
    
    booking_status VARCHAR(20) DEFAULT 'CONFIRMED',
    -- CONFIRMED → CHECKED_IN → CHECKED_OUT
    -- CONFIRMED → CANCELLED
    -- CONFIRMED → NO_SHOW
    
    guest_name VARCHAR(200) NOT NULL,
    guest_email VARCHAR(255),
    special_requests TEXT,
    
    confirmed_at TIMESTAMP DEFAULT NOW(),
    cancelled_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_bookings_user ON bookings(user_id, created_at DESC);
CREATE INDEX idx_bookings_hotel_dates ON bookings(hotel_id, check_in_date, check_out_date);

-- ============================================================================
-- TABLE: reviews
-- WHY: Separate from bookings because:
--      1. Not all bookings have reviews (optional, async)
--      2. Reviews have their own lifecycle (pending moderation → published)
--      3. Query pattern differs: "show all reviews for hotel X" doesn't need booking data
--
-- RELATIONSHIP: reviews → bookings (One-to-One)
--   WHY FK to bookings? Only guests who actually stayed can review.
--   This prevents fake reviews (no booking = no review).
-- ============================================================================
CREATE TABLE reviews (
    review_id UUID PRIMARY KEY,
    booking_id UUID UNIQUE REFERENCES bookings(booking_id),  -- UNIQUE = One-to-One
    user_id UUID NOT NULL,
    hotel_id UUID NOT NULL REFERENCES hotels(hotel_id),
    overall_rating INTEGER CHECK (overall_rating BETWEEN 1 AND 5),
    cleanliness INTEGER CHECK (cleanliness BETWEEN 1 AND 5),
    service INTEGER CHECK (service BETWEEN 1 AND 5),
    location INTEGER CHECK (location BETWEEN 1 AND 5),
    value INTEGER CHECK (value BETWEEN 1 AND 5),
    review_text TEXT,
    is_verified BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_reviews_hotel ON reviews(hotel_id, created_at DESC);
```

---

## Design Patterns & SOLID

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | PricingStrategy (Seasonal, Demand, Weekend) | Different hotels price differently. Swap algorithms without changing booking logic. |
| **Template Method** | BookingWorkflow | Steps are always: validate → check inventory → calculate → reserve → confirm. Subclasses customize steps. |
| **Observer** | BookingEventObserver | Notify email, analytics, inventory sync on booking events. |
| **Builder** | Booking.Builder | Many fields, some optional (special requests, discount). Builder keeps construction clean. |

| SOLID | How |
|-------|-----|
| **S** | `InventoryService` manages room counts only. `PricingService` calculates prices only. `BookingService` orchestrates. |
| **O** | New pricing formula = new `PricingStrategy` class. No changes to `BookingService`. |
| **L** | Any `PricingStrategy` (Seasonal, Weekend, Demand) is interchangeable. |
| **I** | `PricingStrategy` has one method. `AvailabilityChecker` has one method. Focused contracts. |
| **D** | `BookingService` depends on `PricingStrategy` interface, not `SeasonalPricing` class. |

---

## Code Implementation

### Enums & Models

```java
public enum BookingStatus {
    CONFIRMED, CHECKED_IN, CHECKED_OUT, CANCELLED, NO_SHOW;
    
    public boolean canTransitionTo(BookingStatus next) {
        return switch (this) {
            case CONFIRMED -> next == CHECKED_IN || next == CANCELLED;
            case CHECKED_IN -> next == CHECKED_OUT;
            case CHECKED_OUT, CANCELLED, NO_SHOW -> false;
        };
    }
}

public enum CancellationPolicy {
    FLEXIBLE(1),      // Free cancel up to 1 day before
    MODERATE(3),      // Free cancel up to 3 days before
    STRICT(0);        // Non-refundable (0 = no free cancellation)
    
    private final int freeCancelDaysBefore;
    CancellationPolicy(int days) { this.freeCancelDaysBefore = days; }
    
    /**
     * Calculate refund percentage based on when cancellation happens.
     * WHY here? Cancellation logic is tied to the policy type.
     * Encapsulating it in the enum avoids if-else chains elsewhere.
     */
    public BigDecimal getRefundPercentage(LocalDate checkInDate) {
        long daysUntilCheckIn = ChronoUnit.DAYS.between(LocalDate.now(), checkInDate);
        if (daysUntilCheckIn >= freeCancelDaysBefore) {
            return BigDecimal.ONE; // 100% refund
        }
        return switch (this) {
            case FLEXIBLE -> new BigDecimal("0.50");  // 50% if late cancel
            case MODERATE -> new BigDecimal("0.25");   // 25% if late cancel
            case STRICT -> BigDecimal.ZERO;            // No refund
        };
    }
}

// ============================================================================
// MODEL: DateRange (Value Object)
// WHY a value object? Check-in and check-out dates always travel together.
// Immutable. Validates that checkout > checkin at construction.
// ============================================================================
public record DateRange(LocalDate checkIn, LocalDate checkOut) {
    public DateRange {
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("Check-out must be after check-in");
        }
    }
    
    public int nights() {
        return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
    }
    
    public List<LocalDate> dates() {
        return checkIn.datesUntil(checkOut).collect(Collectors.toList());
    }
}

// ============================================================================
// MODEL: RoomType — Represents a category of rooms in a hotel.
// ============================================================================
public class RoomType {
    private final String roomTypeId;
    private final String hotelId;
    private final String roomName;
    private final int maxOccupancy;
    private final BigDecimal basePricePerNight;
    private final int totalRooms;

    public RoomType(String roomTypeId, String hotelId, String roomName,
                    int maxOccupancy, BigDecimal basePricePerNight, int totalRooms) {
        this.roomTypeId = roomTypeId;
        this.hotelId = hotelId;
        this.roomName = roomName;
        this.maxOccupancy = maxOccupancy;
        this.basePricePerNight = basePricePerNight;
        this.totalRooms = totalRooms;
    }

    // Getters
    public String getRoomTypeId() { return roomTypeId; }
    public String getHotelId() { return hotelId; }
    public String getRoomName() { return roomName; }
    public int getMaxOccupancy() { return maxOccupancy; }
    public BigDecimal getBasePricePerNight() { return basePricePerNight; }
    public int getTotalRooms() { return totalRooms; }
}

// ============================================================================
// MODEL: Booking — With state machine for lifecycle management.
// PATTERN: State — transitions validated inside the model.
// PATTERN: Builder — for clean construction.
// ============================================================================
public class Booking {
    private final String bookingId;
    private final String userId;
    private final String hotelId;
    private final String roomTypeId;
    private final String bookingReference;
    private final DateRange dateRange;
    private final int roomsBooked;
    private final BigDecimal ratePerNight;
    private final BigDecimal totalAmount;
    private final String guestName;
    
    private BookingStatus status;

    private Booking(Builder builder) {
        this.bookingId = builder.bookingId;
        this.userId = builder.userId;
        this.hotelId = builder.hotelId;
        this.roomTypeId = builder.roomTypeId;
        this.bookingReference = builder.bookingReference;
        this.dateRange = builder.dateRange;
        this.roomsBooked = builder.roomsBooked;
        this.ratePerNight = builder.ratePerNight;
        this.totalAmount = builder.totalAmount;
        this.guestName = builder.guestName;
        this.status = BookingStatus.CONFIRMED;
    }

    public void cancel() {
        if (!status.canTransitionTo(BookingStatus.CANCELLED)) {
            throw new IllegalStateException("Cannot cancel a " + status + " booking");
        }
        this.status = BookingStatus.CANCELLED;
    }

    public void checkIn() {
        if (!status.canTransitionTo(BookingStatus.CHECKED_IN)) {
            throw new IllegalStateException("Cannot check in from " + status);
        }
        this.status = BookingStatus.CHECKED_IN;
    }

    // Getters
    public String getBookingId() { return bookingId; }
    public String getRoomTypeId() { return roomTypeId; }
    public DateRange getDateRange() { return dateRange; }
    public int getRoomsBooked() { return roomsBooked; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BookingStatus getStatus() { return status; }

    // Builder
    public static class Builder {
        private String bookingId, userId, hotelId, roomTypeId, bookingReference, guestName;
        private DateRange dateRange;
        private int roomsBooked = 1;
        private BigDecimal ratePerNight, totalAmount;

        public Builder bookingId(String id) { this.bookingId = id; return this; }
        public Builder userId(String id) { this.userId = id; return this; }
        public Builder hotelId(String id) { this.hotelId = id; return this; }
        public Builder roomTypeId(String id) { this.roomTypeId = id; return this; }
        public Builder bookingReference(String ref) { this.bookingReference = ref; return this; }
        public Builder dateRange(DateRange range) { this.dateRange = range; return this; }
        public Builder roomsBooked(int n) { this.roomsBooked = n; return this; }
        public Builder ratePerNight(BigDecimal rate) { this.ratePerNight = rate; return this; }
        public Builder totalAmount(BigDecimal total) { this.totalAmount = total; return this; }
        public Builder guestName(String name) { this.guestName = name; return this; }
        
        public Booking build() {
            Objects.requireNonNull(bookingId);
            Objects.requireNonNull(dateRange);
            Objects.requireNonNull(roomTypeId);
            return new Booking(this);
        }
    }

    public static Builder builder() { return new Builder(); }
}
```

### Strategy Implementations

```java
// ============================================================================
// INTERFACE: PricingStrategy
// PATTERN: Strategy — Different pricing models for different situations.
// SOLID (I): One method. Clean contract.
// ============================================================================
public interface PricingStrategy {
    BigDecimal calculateNightlyRate(RoomType roomType, LocalDate date, int occupancy);
}

public class StandardPricingStrategy implements PricingStrategy {
    @Override
    public BigDecimal calculateNightlyRate(RoomType roomType, LocalDate date, int occupancy) {
        return roomType.getBasePricePerNight();
    }
}

// ============================================================================
// Seasonal pricing: weekends and holidays cost more.
// Wraps a base strategy (Decorator-like composition).
// ============================================================================
public class SeasonalPricingStrategy implements PricingStrategy {
    private final PricingStrategy baseStrategy;
    private final Set<LocalDate> holidays;

    public SeasonalPricingStrategy(PricingStrategy baseStrategy, Set<LocalDate> holidays) {
        this.baseStrategy = baseStrategy;
        this.holidays = holidays;
    }

    @Override
    public BigDecimal calculateNightlyRate(RoomType roomType, LocalDate date, int occupancy) {
        BigDecimal base = baseStrategy.calculateNightlyRate(roomType, date, occupancy);
        BigDecimal multiplier = BigDecimal.ONE;

        if (isWeekend(date)) {
            multiplier = new BigDecimal("1.25"); // 25% weekend premium
        }
        if (holidays.contains(date)) {
            multiplier = new BigDecimal("2.00"); // 100% holiday premium
        }

        return base.multiply(multiplier).setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.FRIDAY || day == DayOfWeek.SATURDAY;
    }
}
```

### Core Services

```java
// ============================================================================
// InventoryService — Manages room availability. Heart of the system.
// SOLID (S): Only manages inventory counts. No pricing, no booking logic.
//
// The critical operation is reserveRooms() which uses OPTIMISTIC LOCKING
// to prevent overbooking in concurrent scenarios.
// ============================================================================
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    public InventoryService(InventoryRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * Check if a room type has enough rooms for ALL dates in the range.
     * A room type might have 5 rooms on Monday but 0 on Tuesday —
     * the booking fails because Tuesday has no availability.
     */
    public boolean isAvailable(String roomTypeId, DateRange dateRange, int roomsNeeded) {
        for (LocalDate date : dateRange.dates()) {
            int available = inventoryRepository.getAvailableRooms(roomTypeId, date);
            if (available < roomsNeeded) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reserve rooms across all dates in the range.
     * Uses optimistic locking: read version → decrement → write with version check.
     * If version changed, someone else booked in between → retry or fail.
     *
     * WHY optimistic over pessimistic? Hotel bookings have LOW contention
     * (unlike concert tickets). Most of the time, no two people book the
     * same room type on the same date simultaneously.
     */
    @Transactional
    public void reserveRooms(String roomTypeId, DateRange dateRange, int roomsToReserve) {
        for (LocalDate date : dateRange.dates()) {
            RoomInventory inventory = inventoryRepository
                .findByRoomTypeAndDate(roomTypeId, date)
                .orElseThrow(() -> new InventoryNotFoundException(
                    "No inventory for " + roomTypeId + " on " + date));

            if (inventory.getAvailableRooms() < roomsToReserve) {
                throw new InsufficientInventoryException(
                    "Only " + inventory.getAvailableRooms() + " rooms on " + date);
            }

            // Optimistic lock: update only if version matches
            int updated = inventoryRepository.decrementAvailable(
                inventory.getInventoryId(),
                roomsToReserve,
                inventory.getVersion()  // WHERE version = ?
            );

            if (updated == 0) {
                throw new ConcurrentBookingException(
                    "Room was booked by someone else. Please try again.");
            }
        }
    }

    /** Release rooms back to inventory (on cancellation). */
    @Transactional
    public void releaseRooms(String roomTypeId, DateRange dateRange, int roomsToRelease) {
        for (LocalDate date : dateRange.dates()) {
            inventoryRepository.incrementAvailable(roomTypeId, date, roomsToRelease);
        }
    }
}

// ============================================================================
// BookingService — Orchestrates the booking flow.
// SOLID (S): Orchestrates. Delegates inventory to InventoryService,
//   pricing to PricingStrategy.
// SOLID (D): Depends on interfaces for inventory and pricing.
// ============================================================================
public class BookingService {

    private final InventoryService inventoryService;
    private final PricingStrategy pricingStrategy;
    private final BookingRepository bookingRepository;
    private final List<BookingEventObserver> observers;

    public BookingService(InventoryService inventoryService,
                          PricingStrategy pricingStrategy,
                          BookingRepository bookingRepository) {
        this.inventoryService = inventoryService;
        this.pricingStrategy = pricingStrategy;
        this.bookingRepository = bookingRepository;
        this.observers = new ArrayList<>();
    }

    public void addObserver(BookingEventObserver observer) {
        observers.add(observer);
    }

    /**
     * Book rooms for a date range.
     * FLOW: Validate → Check availability → Calculate price → Reserve → Save
     */
    public Booking createBooking(String userId, String hotelId, String roomTypeId,
                                  DateRange dateRange, int rooms, String guestName) {
        // 1. Check availability for ALL dates
        if (!inventoryService.isAvailable(roomTypeId, dateRange, rooms)) {
            throw new RoomNotAvailableException("Insufficient rooms for selected dates");
        }

        // 2. Calculate total price using pricing strategy
        RoomType roomType = roomTypeRepository.findById(roomTypeId).orElseThrow();
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (LocalDate date : dateRange.dates()) {
            BigDecimal nightRate = pricingStrategy.calculateNightlyRate(
                roomType, date, 2);
            totalAmount = totalAmount.add(nightRate.multiply(BigDecimal.valueOf(rooms)));
        }

        // 3. Reserve rooms in inventory (optimistic locking inside)
        inventoryService.reserveRooms(roomTypeId, dateRange, rooms);

        // 4. Create booking
        Booking booking = Booking.builder()
            .bookingId(UUID.randomUUID().toString())
            .userId(userId)
            .hotelId(hotelId)
            .roomTypeId(roomTypeId)
            .bookingReference(generateReference())
            .dateRange(dateRange)
            .roomsBooked(rooms)
            .ratePerNight(roomType.getBasePricePerNight())
            .totalAmount(totalAmount)
            .guestName(guestName)
            .build();

        bookingRepository.save(booking);
        notifyObservers(booking, "BOOKING_CONFIRMED");

        return booking;
    }

    public Booking cancelBooking(String bookingId, String userId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        booking.cancel();
        
        // Release inventory
        inventoryService.releaseRooms(
            booking.getRoomTypeId(), booking.getDateRange(), booking.getRoomsBooked());
        
        bookingRepository.save(booking);
        notifyObservers(booking, "BOOKING_CANCELLED");
        return booking;
    }

    private String generateReference() {
        return "BK-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private void notifyObservers(Booking booking, String event) {
        for (BookingEventObserver obs : observers) {
            obs.onBookingEvent(booking, event);
        }
    }
}
```

### Observer Pattern

```java
public interface BookingEventObserver {
    void onBookingEvent(Booking booking, String event);
}

public class EmailNotificationObserver implements BookingEventObserver {
    @Override
    public void onBookingEvent(Booking booking, String event) {
        switch (event) {
            case "BOOKING_CONFIRMED" -> System.out.println(
                "📧 Confirmation email sent to guest for booking " + booking.getBookingId());
            case "BOOKING_CANCELLED" -> System.out.println(
                "📧 Cancellation email sent with refund details");
        }
    }
}

public class AnalyticsObserver implements BookingEventObserver {
    @Override
    public void onBookingEvent(Booking booking, String event) {
        System.out.println("📊 Analytics tracked: " + event + 
            " | amount: " + booking.getTotalAmount());
    }
}
```

---

## Edge Cases & Tests

```java
// 1. Booking a room type that's fully booked on one of the dates
@Test
void booking_partiallyAvailable_fails() {
    // Available on Jan 15 and 16, but NOT on Jan 17
    assertThrows(RoomNotAvailableException.class, () ->
        bookingService.createBooking("user-1", "hotel-1", "deluxe-king",
            new DateRange(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 18)),
            1, "John"));
}

// 2. Two users try to book the last room simultaneously
@Test
void concurrentBooking_lastRoom_oneSucceeds() throws Exception {
    // Only 1 Deluxe King room left on Jan 15
    ExecutorService executor = Executors.newFixedThreadPool(2);
    
    Future<?> user1 = executor.submit(() ->
        bookingService.createBooking("u1", "h1", "dk", range, 1, "User1"));
    Future<?> user2 = executor.submit(() ->
        bookingService.createBooking("u2", "h1", "dk", range, 1, "User2"));
    
    int success = 0, failure = 0;
    try { user1.get(); success++; } catch (Exception e) { failure++; }
    try { user2.get(); success++; } catch (Exception e) { failure++; }
    
    assertEquals(1, success);
    assertEquals(1, failure);
}

// 3. Cancellation releases inventory
@Test
void cancelBooking_releasesInventory() {
    Booking booking = bookingService.createBooking("u1", "h1", "dk", range, 1, "John");
    int availableBefore = inventoryService.getAvailable("dk", range.checkIn());
    
    bookingService.cancelBooking(booking.getBookingId(), "u1");
    
    int availableAfter = inventoryService.getAvailable("dk", range.checkIn());
    assertEquals(availableBefore + 1, availableAfter);
}

// 4. Weekend pricing applies correctly
@Test
void pricing_weekend_appliesMultiplier() {
    LocalDate saturday = LocalDate.of(2026, 2, 7); // Saturday
    BigDecimal weekendRate = pricingStrategy.calculateNightlyRate(roomType, saturday, 2);
    BigDecimal weekdayRate = pricingStrategy.calculateNightlyRate(roomType, saturday.minusDays(1), 2);
    assertTrue(weekendRate.compareTo(weekdayRate) > 0);
}

// 5. Check-out must be after check-in
@Test
void dateRange_invalidDates_throwsException() {
    assertThrows(IllegalArgumentException.class, () ->
        new DateRange(LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 15)));
}
```

---

## Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  KEY DESIGN DECISIONS                                           │
│                                                                 │
│  1. room_inventory has PER-DATE rows (not per-room)            │
│     → Enables date-specific pricing and availability           │
│                                                                 │
│  2. Optimistic locking on inventory prevents overbooking       │
│     → Low contention makes this optimal vs pessimistic         │
│                                                                 │
│  3. Price snapshotting in bookings                             │
│     → User pays agreed price even if hotel changes rates       │
│                                                                 │
│  PATTERNS: Strategy (pricing), Builder (booking), Observer,    │
│            State Machine (booking status)                       │
│  SOLID: S (services split), O (new pricing=new class),         │
│         D (depend on interfaces)                               │
└─────────────────────────────────────────────────────────────────┘
```
