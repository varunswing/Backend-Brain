# Parking Lot System - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database Design with Explanations](#database-design-with-explanations)
4. [Class Diagram](#class-diagram)
5. [Design Patterns Used](#design-patterns-used)
6. [SOLID Principles Applied](#solid-principles-applied)
7. [Code Implementation](#code-implementation)
8. [Request Flows](#request-flows)
9. [Edge Cases & Tests](#edge-cases--tests)

---

## Problem Statement

Design a parking lot system that manages vehicle entry/exit, spot assignment, payment processing, and real-time availability tracking. Support multiple vehicle types, dynamic pricing, and high concurrency.

---

## Requirements

### Functional Requirements
1. Vehicle entry with automatic spot assignment
2. Vehicle exit with fee calculation
3. Support multiple vehicle types (car, motorcycle, truck, handicapped)
4. Multiple parking spot sizes (small, medium, large)
5. Multiple floors in the parking lot
6. Payment processing (cash, card, UPI)
7. Real-time spot availability tracking
8. Reservation system (book a spot in advance)

### Non-Functional Requirements
- Handle concurrent entry/exit (no double-booking)
- Calculate fees based on duration + vehicle type
- Support multiple parking lots
- Extensible for new vehicle types and pricing strategies

---

## Database Design with Explanations

### Why Each Table Exists

```sql
-- ============================================================================
-- TABLE: parking_lots
-- WHY: We need a top-level entity to represent each physical parking facility.
--      A company may operate multiple lots across a city. This table is the 
--      root of our entity hierarchy. All floors, spots, and bookings eventually 
--      trace back to a specific parking lot.
-- ============================================================================
CREATE TABLE parking_lots (
    lot_id UUID PRIMARY KEY,
    lot_name VARCHAR(200) NOT NULL,
    address TEXT NOT NULL,
    latitude DECIMAL(10, 8),
    longitude DECIMAL(11, 8),
    total_floors INTEGER NOT NULL,          -- How many floors this lot has
    total_capacity INTEGER NOT NULL,        -- Total spots across all floors
    operating_hours JSONB NOT NULL,         -- {"open": "06:00", "close": "23:00"}
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- TABLE: floors
-- WHY: A parking lot has multiple floors. We need this table to:
--      1. Group spots by floor (user needs to know which floor to go to)
--      2. Track per-floor capacity separately (Floor 1 might be full, Floor 3 open)
--      3. Enable floor-level operations (close a floor for maintenance)
--
-- RELATIONSHIP: floors → parking_lots (Many-to-One)
--   WHY FK? Each floor MUST belong to exactly one parking lot. Without this FK,
--   we'd have orphan floors that don't belong anywhere. The FK also lets us
--   cascade-delete all floors if a lot is removed.
-- ============================================================================
CREATE TABLE floors (
    floor_id UUID PRIMARY KEY,
    lot_id UUID NOT NULL REFERENCES parking_lots(lot_id) ON DELETE CASCADE,
    floor_number INTEGER NOT NULL,
    total_spots INTEGER NOT NULL,
    available_spots INTEGER NOT NULL,        -- Denormalized for fast availability check
    is_operational BOOLEAN DEFAULT true,     -- Can close floor for maintenance
    created_at TIMESTAMP DEFAULT NOW(),

    -- UNIQUE: No two floors in the same lot can have the same number
    UNIQUE(lot_id, floor_number)
);

-- WHY this index? When users search for parking, we query by lot_id and
-- need available floors fast. Without this index, it's a full table scan.
CREATE INDEX idx_floors_lot_available ON floors(lot_id, available_spots) 
    WHERE is_operational = true;

-- ============================================================================
-- TABLE: parking_spots
-- WHY: This is the core entity. Each row represents ONE physical parking space.
--      We need individual rows (not just counts) because:
--      1. Each spot has a unique location (floor, row, number)
--      2. Each spot has a type (compact, regular, large) determining which vehicles fit
--      3. Each spot tracks its own status independently (available, occupied, reserved)
--      4. We need to assign SPECIFIC spots to vehicles (not just "any spot")
--
-- RELATIONSHIP: parking_spots → floors (Many-to-One)
--   WHY FK? Each spot belongs to exactly one floor. This lets us:
--   - Query all spots on a floor: "Show me available spots on Floor 2"
--   - Cascade operations: Close a floor → all spots become unavailable
--
-- WHY NOT just store spots directly under parking_lots?
--   Because we need the floor abstraction. Users need to know "Go to Floor 3, 
--   Spot B-12". Without floors, we lose physical navigation context.
-- ============================================================================
CREATE TABLE parking_spots (
    spot_id UUID PRIMARY KEY,
    floor_id UUID NOT NULL REFERENCES floors(floor_id) ON DELETE CASCADE,
    spot_number VARCHAR(20) NOT NULL,       -- "A-12", "B-05"
    spot_size VARCHAR(20) NOT NULL,         -- SMALL, MEDIUM, LARGE
    spot_status VARCHAR(20) DEFAULT 'AVAILABLE',  -- AVAILABLE, OCCUPIED, RESERVED, MAINTENANCE
    has_ev_charging BOOLEAN DEFAULT false,
    is_handicapped BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT NOW(),

    -- UNIQUE: No two spots on the same floor can have the same number
    UNIQUE(floor_id, spot_number)
);

-- WHY this index? The #1 query is "find available spots of size X on floor Y".
-- This composite index makes that query O(log n) instead of O(n).
CREATE INDEX idx_spots_status_size ON parking_spots(floor_id, spot_status, spot_size);

-- ============================================================================
-- TABLE: vehicles
-- WHY: We need to track vehicles separately from users because:
--      1. One user can own multiple vehicles (car + motorcycle)
--      2. Vehicle type determines which spot size is needed
--      3. License plate is needed for entry/exit verification
--      4. Vehicle history helps with analytics and fraud detection
--
-- RELATIONSHIP: vehicles → users (Many-to-One)
--   WHY FK? Each vehicle belongs to a registered user. This enables:
--   - "Show me all vehicles for this user"
--   - Prevent orphan vehicles when a user is deleted
--
-- WHY a separate table instead of embedding in users?
--   Because it's a 1-to-Many relationship. Embedding would mean arrays or
--   JSON in the users table, losing referential integrity and query efficiency.
-- ============================================================================
CREATE TABLE vehicles (
    vehicle_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    license_plate VARCHAR(20) NOT NULL UNIQUE,
    vehicle_type VARCHAR(20) NOT NULL,      -- CAR, MOTORCYCLE, TRUCK, VAN
    vehicle_size VARCHAR(20) NOT NULL,      -- SMALL, MEDIUM, LARGE
    color VARCHAR(30),
    make VARCHAR(50),
    model VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? Vehicles enter by license plate scan. We need O(1) lookup.
CREATE INDEX idx_vehicles_plate ON vehicles(license_plate);

-- ============================================================================
-- TABLE: parking_tickets
-- WHY: This is the TRANSACTION table. Every time a vehicle enters the lot,
--      a ticket is created. It tracks:
--      1. Which vehicle is in which spot (current state)
--      2. Entry/exit times (for billing)
--      3. Payment status (pending, paid, failed)
--      This table is the bridge between vehicles and spots. Without it,
--      we'd have no record of who parked where and when.
--
-- RELATIONSHIP: parking_tickets → parking_spots (Many-to-One)
--   WHY FK? A ticket MUST reference a valid spot. Over time, many tickets 
--   reference the same spot (different vehicles on different days).
--   This is Many-to-One because a spot has many tickets (over time) but 
--   each ticket is for exactly one spot.
--
-- RELATIONSHIP: parking_tickets → vehicles (Many-to-One)
--   WHY FK? A ticket MUST reference a valid vehicle. One vehicle can have
--   many tickets over time (parks multiple times).
--
-- WHY NOT just add entry_time/exit_time to parking_spots?
--   Because a spot has HISTORY. We need to keep records of all past parkings
--   for billing, analytics, and dispute resolution. If we only stored current
--   state on the spot, we'd lose all history on exit.
-- ============================================================================
CREATE TABLE parking_tickets (
    ticket_id UUID PRIMARY KEY,
    spot_id UUID NOT NULL REFERENCES parking_spots(spot_id),
    vehicle_id UUID NOT NULL REFERENCES vehicles(vehicle_id),
    lot_id UUID NOT NULL REFERENCES parking_lots(lot_id),

    entry_time TIMESTAMP NOT NULL DEFAULT NOW(),
    exit_time TIMESTAMP,                    -- NULL means vehicle is still parked
    
    -- Pricing snapshot at time of entry (prices may change, but we bill at entry rate)
    hourly_rate DECIMAL(10,2) NOT NULL,
    
    -- Calculated on exit
    duration_minutes INTEGER,
    total_amount DECIMAL(10,2),
    
    ticket_status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, COMPLETED, CANCELLED
    
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? To find all currently active tickets (vehicles still parked).
-- The WHERE clause filter makes this a partial index - very efficient.
CREATE INDEX idx_tickets_active ON parking_tickets(lot_id, ticket_status) 
    WHERE ticket_status = 'ACTIVE';

-- WHY this index? To look up a ticket by vehicle (for exit processing).
CREATE INDEX idx_tickets_vehicle ON parking_tickets(vehicle_id, entry_time DESC);

-- ============================================================================
-- TABLE: payments
-- WHY: Separated from tickets because:
--      1. Single Responsibility: Ticket tracks parking, payment tracks money
--      2. One ticket might have multiple payment attempts (retry on failure)
--      3. Payment has its own lifecycle (pending → processing → success/failed)
--      4. Different payment methods need different data (card last4, UPI ref, etc.)
--      5. PCI compliance: Isolate payment data for security auditing
--
-- RELATIONSHIP: payments → parking_tickets (Many-to-One)
--   WHY Many-to-One instead of One-to-One?
--   Because a single ticket might have multiple payment attempts.
--   First attempt fails (card declined), second attempt succeeds (different card).
--   If One-to-One, we'd lose the failed attempt record.
-- ============================================================================
CREATE TABLE payments (
    payment_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES parking_tickets(ticket_id),
    amount DECIMAL(10,2) NOT NULL,
    payment_method VARCHAR(20) NOT NULL,    -- CASH, CREDIT_CARD, DEBIT_CARD, UPI
    payment_status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, SUCCESS, FAILED, REFUNDED
    transaction_reference VARCHAR(100),     -- External payment gateway reference
    paid_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_payments_ticket ON payments(ticket_id);

-- ============================================================================
-- TABLE: pricing_rules
-- WHY: Separated from parking_lots because:
--      1. Pricing varies by vehicle type (car vs truck)
--      2. Pricing varies by time (peak hours vs off-peak)
--      3. Rules change frequently without changing lot data
--      4. Enables Strategy Pattern: Different pricing algorithms per rule
--
-- RELATIONSHIP: pricing_rules → parking_lots (Many-to-One)
--   WHY? Each lot can have its own pricing. Airport lot charges $10/hr,
--   mall lot charges $2/hr. Multiple rules per lot (one per vehicle type).
-- ============================================================================
CREATE TABLE pricing_rules (
    rule_id UUID PRIMARY KEY,
    lot_id UUID NOT NULL REFERENCES parking_lots(lot_id),
    vehicle_type VARCHAR(20) NOT NULL,      -- CAR, MOTORCYCLE, TRUCK
    hourly_rate DECIMAL(10,2) NOT NULL,
    daily_max_rate DECIMAL(10,2),           -- Cap: Don't charge more than this per day
    peak_multiplier DECIMAL(3,2) DEFAULT 1.0,  -- 1.5 = 50% more during peak
    peak_start_hour INTEGER,                -- e.g., 8 (8 AM)
    peak_end_hour INTEGER,                  -- e.g., 18 (6 PM)
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),

    -- One pricing rule per vehicle type per lot
    UNIQUE(lot_id, vehicle_type)
);
```

### Entity Relationship Summary

```
parking_lots (1) ──── (N) floors (1) ──── (N) parking_spots
                                                    │
                                                    │ (1)
                                                    │
parking_tickets (N) ────────────────────────────────┘
       │
       │ (N)                    vehicles (N) ──── (1) users
       │                             │
       └─────────────────────────────┘
       │
       │ (1)
       │
payments (N) ───────────────────────┘

pricing_rules (N) ──── (1) parking_lots

KEY RELATIONSHIPS EXPLAINED:
- parking_lots → floors: A lot HAS many floors (composition)
- floors → parking_spots: A floor HAS many spots (composition)
- parking_spots → parking_tickets: A spot HAS many tickets over time (association)
- vehicles → parking_tickets: A vehicle HAS many parking sessions (association)
- parking_tickets → payments: A ticket HAS multiple payment attempts (composition)
- parking_lots → pricing_rules: A lot HAS pricing rules per vehicle type (composition)
```

---

## Class Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           <<interface>>                                  │
│                         ParkingStrategy                                  │
│  ──────────────────────────────────────────────                         │
│  + assignSpot(lot, vehicle): ParkingSpot                                │
├──────────────────────────────────────────────────────────────────────────┤
│           ▲                    ▲                     ▲                   │
│           │                    │                     │                   │
│  NearestFirstStrategy  LowestFloorStrategy  SpecificSpotStrategy        │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                           <<interface>>                                  │
│                         PricingStrategy                                  │
│  ──────────────────────────────────────────────                         │
│  + calculateFee(ticket): Money                                          │
├──────────────────────────────────────────────────────────────────────────┤
│           ▲                    ▲                     ▲                   │
│           │                    │                     │                   │
│    HourlyPricing       FlatRatePricing       PeakHourPricing            │
└──────────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────────┐
│                           <<interface>>                                  │
│                          PaymentProcessor                                │
│  ──────────────────────────────────────────────                         │
│  + processPayment(amount, method): PaymentResult                        │
├──────────────────────────────────────────────────────────────────────────┤
│           ▲                    ▲                     ▲                   │
│           │                    │                     │                   │
│   CashPaymentProcessor  CardPaymentProcessor  UPIPaymentProcessor       │
└──────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐  uses   ┌─────────────────┐  uses   ┌─────────────────┐
│  ParkingLot     │────────>│ ParkingService  │────────>│ PaymentService  │
│                 │         │                 │         │                 │
│ - id            │         │ - parkingStrategy│        │ - processors{}  │
│ - name          │         │ - pricingStrategy│        │ + pay()         │
│ - floors[]      │         │ + vehicleEntry()│         │                 │
│ + getAvailable()│         │ + vehicleExit() │         │                 │
└─────────────────┘         └─────────────────┘         └─────────────────┘
         │ has                     uses
         ▼                          │
┌─────────────────┐                 ▼
│  Floor          │         ┌─────────────────┐
│                 │         │  ParkingTicket  │
│ - floorNumber   │         │                 │
│ - spots[]       │         │ - vehicle       │
│ + getAvailable()│         │ - spot          │
└─────────────────┘         │ - entryTime     │
         │ has              │ - exitTime      │
         ▼                  │ - amount        │
┌─────────────────┐         └─────────────────┘
│  ParkingSpot    │
│                 │
│ - spotNumber    │
│ - size          │
│ - status        │
│ + canFit(vehicle)│
└─────────────────┘
```

---

## Design Patterns Used

| Pattern | Where Used | Why |
|---------|-----------|-----|
| **Strategy** | ParkingStrategy, PricingStrategy, PaymentProcessor | Different algorithms for spot assignment, pricing, and payment. Allows swapping without changing client code. |
| **Factory** | VehicleFactory, PaymentProcessorFactory | Create objects without exposing creation logic. Centralizes object creation. |
| **Observer** | SpotAvailabilityObserver | Notify dashboard/display boards when spot status changes. Decouples notification from core logic. |
| **Singleton** | ParkingLotManager | Only one manager instance per JVM. Controls access to the parking system. |
| **Builder** | ParkingTicket.Builder | Complex object with many optional fields. Avoids telescoping constructors. |

## SOLID Principles Applied

| Principle | How Applied |
|-----------|------------|
| **S - Single Responsibility** | Each class does ONE thing: `ParkingSpot` manages spot state, `PricingStrategy` calculates fees, `PaymentProcessor` handles payments. They don't mix concerns. |
| **O - Open/Closed** | Adding a new vehicle type (e.g., `BUS`) doesn't change existing code. Just add a new enum value and a new `PricingStrategy` implementation. System is open for extension, closed for modification. |
| **L - Liskov Substitution** | Any `PricingStrategy` implementation (`HourlyPricing`, `FlatRate`) can be swapped without breaking the `ParkingService`. The caller doesn't know or care which strategy is used. |
| **I - Interface Segregation** | `ParkingStrategy`, `PricingStrategy`, and `PaymentProcessor` are separate interfaces. A class implementing payment doesn't need to know about spot assignment. |
| **D - Dependency Inversion** | `ParkingService` depends on the `PricingStrategy` INTERFACE, not on `HourlyPricing` concrete class. High-level modules depend on abstractions, not details. |

---

## Code Implementation

### Enums

```java
// ============================================================================
// WHY ENUMS? Type safety. Instead of string "CAR" that could be misspelled as 
// "car" or "Car", we use enums. Compiler catches errors at compile time.
// ============================================================================

public enum VehicleType {
    MOTORCYCLE,   // Needs SMALL spot
    CAR,          // Needs MEDIUM spot  
    TRUCK,        // Needs LARGE spot
    VAN;          // Needs LARGE spot
    
    /**
     * Maps vehicle type to minimum spot size required.
     * WHY here? This is vehicle's own knowledge about its size requirement.
     * Keeps the mapping close to the enum (cohesion).
     */
    public SpotSize getRequiredSpotSize() {
        return switch (this) {
            case MOTORCYCLE -> SpotSize.SMALL;
            case CAR -> SpotSize.MEDIUM;
            case TRUCK, VAN -> SpotSize.LARGE;
        };
    }
}

public enum SpotSize {
    SMALL(1),     // Fits motorcycle only
    MEDIUM(2),    // Fits motorcycle + car
    LARGE(3);     // Fits all vehicles

    private final int capacity;

    SpotSize(int capacity) {
        this.capacity = capacity;
    }

    /**
     * Can this spot fit a vehicle that needs the given size?
     * A LARGE spot can fit a SMALL vehicle, but not vice versa.
     * WHY this method? Encapsulates the "can fit" logic inside SpotSize
     * rather than spreading if-else checks across the codebase.
     */
    public boolean canFit(SpotSize required) {
        return this.capacity >= required.capacity;
    }
}

public enum SpotStatus {
    AVAILABLE,
    OCCUPIED,
    RESERVED,
    MAINTENANCE
}

public enum TicketStatus {
    ACTIVE,       // Vehicle is currently parked
    COMPLETED,    // Vehicle has exited and paid
    CANCELLED     // Ticket was voided
}

public enum PaymentMethod {
    CASH,
    CREDIT_CARD,
    DEBIT_CARD,
    UPI
}

public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED
}
```

### Model Classes

```java
// ============================================================================
// MODEL: Vehicle
// OOP Concept: Encapsulation — all vehicle data is bundled together.
// Fields are private, accessed through getters. Immutable after creation
// because a vehicle's license plate and type don't change.
// ============================================================================
public class Vehicle {
    private final String vehicleId;
    private final String licensePlate;
    private final VehicleType type;
    private final String ownerName;

    public Vehicle(String vehicleId, String licensePlate, VehicleType type, String ownerName) {
        // Validate invariants at construction time
        if (licensePlate == null || licensePlate.isBlank()) {
            throw new IllegalArgumentException("License plate cannot be empty");
        }
        this.vehicleId = vehicleId;
        this.licensePlate = licensePlate;
        this.type = type;
        this.ownerName = ownerName;
    }

    public String getVehicleId() { return vehicleId; }
    public String getLicensePlate() { return licensePlate; }
    public VehicleType getType() { return type; }
    public SpotSize getRequiredSpotSize() { return type.getRequiredSpotSize(); }
}

// ============================================================================
// MODEL: ParkingSpot
// OOP Concept: Encapsulation — spot manages its own state transitions.
// External code can't directly set status to invalid states.
// The occupy/release methods enforce valid transitions.
// ============================================================================
public class ParkingSpot {
    private final String spotId;
    private final String spotNumber;
    private final SpotSize size;
    private final int floorNumber;
    private final boolean isHandicapped;
    private final boolean hasEVCharging;
    
    private SpotStatus status;
    private Vehicle currentVehicle;    // NULL when spot is empty

    public ParkingSpot(String spotId, String spotNumber, SpotSize size, 
                       int floorNumber, boolean isHandicapped, boolean hasEVCharging) {
        this.spotId = spotId;
        this.spotNumber = spotNumber;
        this.size = size;
        this.floorNumber = floorNumber;
        this.isHandicapped = isHandicapped;
        this.hasEVCharging = hasEVCharging;
        this.status = SpotStatus.AVAILABLE;
    }

    /**
     * Check if this spot can accommodate the given vehicle.
     * WHY a method instead of external check? Encapsulation — the spot
     * knows its own constraints. Caller doesn't need to know the size rules.
     */
    public boolean canFitVehicle(Vehicle vehicle) {
        return status == SpotStatus.AVAILABLE 
            && size.canFit(vehicle.getRequiredSpotSize());
    }

    /**
     * Occupy this spot with a vehicle.
     * WHY synchronized? Prevents race condition where two threads try to 
     * occupy the same spot simultaneously (Thread Safety).
     * WHY throw exception? Fail-fast — don't silently ignore invalid operations.
     */
    public synchronized void occupy(Vehicle vehicle) {
        if (status != SpotStatus.AVAILABLE) {
            throw new SpotNotAvailableException(
                "Spot " + spotNumber + " is not available (current: " + status + ")");
        }
        if (!canFitVehicle(vehicle)) {
            throw new VehicleTooLargeException(
                "Vehicle " + vehicle.getType() + " doesn't fit in " + size + " spot");
        }
        this.currentVehicle = vehicle;
        this.status = SpotStatus.OCCUPIED;
    }

    /**
     * Release this spot (vehicle exits).
     * Returns the vehicle that was parked for ticket processing.
     */
    public synchronized Vehicle release() {
        if (status != SpotStatus.OCCUPIED) {
            throw new IllegalStateException("Cannot release a spot that isn't occupied");
        }
        Vehicle parkedVehicle = this.currentVehicle;
        this.currentVehicle = null;
        this.status = SpotStatus.AVAILABLE;
        return parkedVehicle;
    }

    public boolean isAvailable() { return status == SpotStatus.AVAILABLE; }
    public String getSpotId() { return spotId; }
    public String getSpotNumber() { return spotNumber; }
    public SpotSize getSize() { return size; }
    public int getFloorNumber() { return floorNumber; }
    public SpotStatus getStatus() { return status; }
    public Vehicle getCurrentVehicle() { return currentVehicle; }
}

// ============================================================================
// MODEL: Floor
// OOP Concept: Composition — A Floor HAS-A collection of ParkingSpots.
// The floor is responsible for managing its spots and providing floor-level
// operations (find available spot on this floor).
// ============================================================================
public class Floor {
    private final String floorId;
    private final int floorNumber;
    private final List<ParkingSpot> spots;

    public Floor(String floorId, int floorNumber) {
        this.floorId = floorId;
        this.floorNumber = floorNumber;
        this.spots = new ArrayList<>();
    }

    public void addSpot(ParkingSpot spot) {
        spots.add(spot);
    }

    /**
     * Find first available spot that can fit the given vehicle.
     * Returns Optional to force caller to handle "no spot found" case.
     * WHY Optional? Avoids null checks and NullPointerException. Caller is
     * forced to handle the empty case explicitly.
     */
    public Optional<ParkingSpot> findAvailableSpot(Vehicle vehicle) {
        return spots.stream()
            .filter(spot -> spot.canFitVehicle(vehicle))
            .findFirst();
    }

    public long getAvailableCount() {
        return spots.stream().filter(ParkingSpot::isAvailable).count();
    }

    public long getAvailableCountBySize(SpotSize size) {
        return spots.stream()
            .filter(spot -> spot.isAvailable() && spot.getSize() == size)
            .count();
    }

    public int getFloorNumber() { return floorNumber; }
    public List<ParkingSpot> getSpots() { return Collections.unmodifiableList(spots); }
}

// ============================================================================
// MODEL: ParkingTicket
// PATTERN: Builder — ParkingTicket has many fields, some set at entry, 
// some at exit. Builder lets us construct it step by step.
// OOP Concept: Immutability for core fields (id, vehicle, spot, entryTime)
// while allowing mutation for exit-related fields.
// ============================================================================
public class ParkingTicket {
    private final String ticketId;
    private final Vehicle vehicle;
    private final ParkingSpot spot;
    private final LocalDateTime entryTime;
    private final BigDecimal hourlyRate;
    
    // Mutable: Set when vehicle exits
    private LocalDateTime exitTime;
    private long durationMinutes;
    private BigDecimal totalAmount;
    private TicketStatus status;

    // Private constructor: Force use of Builder
    private ParkingTicket(Builder builder) {
        this.ticketId = builder.ticketId;
        this.vehicle = builder.vehicle;
        this.spot = builder.spot;
        this.entryTime = builder.entryTime;
        this.hourlyRate = builder.hourlyRate;
        this.status = TicketStatus.ACTIVE;
    }

    public void completeParking(LocalDateTime exitTime, BigDecimal totalAmount) {
        this.exitTime = exitTime;
        this.durationMinutes = Duration.between(entryTime, exitTime).toMinutes();
        this.totalAmount = totalAmount;
        this.status = TicketStatus.COMPLETED;
    }

    // Getters...
    public String getTicketId() { return ticketId; }
    public Vehicle getVehicle() { return vehicle; }
    public ParkingSpot getSpot() { return spot; }
    public LocalDateTime getEntryTime() { return entryTime; }
    public BigDecimal getHourlyRate() { return hourlyRate; }
    public LocalDateTime getExitTime() { return exitTime; }
    public long getDurationMinutes() { return durationMinutes; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public TicketStatus getStatus() { return status; }

    // ========================================================================
    // PATTERN: Builder
    // WHY? ParkingTicket has 5+ constructor params. A Builder:
    //   1. Makes construction readable: Ticket.builder().vehicle(v).spot(s).build()
    //   2. Allows optional fields without telescoping constructors
    //   3. Validates all required fields in build() before creating object
    // ========================================================================
    public static class Builder {
        private String ticketId;
        private Vehicle vehicle;
        private ParkingSpot spot;
        private LocalDateTime entryTime;
        private BigDecimal hourlyRate;

        public Builder ticketId(String ticketId) { this.ticketId = ticketId; return this; }
        public Builder vehicle(Vehicle vehicle) { this.vehicle = vehicle; return this; }
        public Builder spot(ParkingSpot spot) { this.spot = spot; return this; }
        public Builder entryTime(LocalDateTime time) { this.entryTime = time; return this; }
        public Builder hourlyRate(BigDecimal rate) { this.hourlyRate = rate; return this; }

        public ParkingTicket build() {
            // Validate required fields
            Objects.requireNonNull(ticketId, "Ticket ID is required");
            Objects.requireNonNull(vehicle, "Vehicle is required");
            Objects.requireNonNull(spot, "Spot is required");
            if (entryTime == null) entryTime = LocalDateTime.now();
            Objects.requireNonNull(hourlyRate, "Hourly rate is required");
            return new ParkingTicket(this);
        }
    }

    public static Builder builder() { return new Builder(); }
}
```

### Strategy Pattern Implementations

```java
// ============================================================================
// INTERFACE: ParkingStrategy
// PATTERN: Strategy — Defines a family of algorithms for spot assignment.
// SOLID (O): Open for extension. New strategies (VIP-first, EV-first) can
//   be added without modifying existing code.
// SOLID (D): ParkingService depends on this interface, not concrete strategies.
// SOLID (I): Interface has only ONE method — minimal, focused contract.
// ============================================================================
public interface ParkingStrategy {
    /**
     * Find and assign an appropriate parking spot for the vehicle.
     * @return Optional.empty() if no spot is available
     */
    Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle);
}

// ============================================================================
// Nearest-to-entrance strategy: Fill lowest floors first.
// WHY? Most parking lots want to minimize walking distance for customers.
// This assigns spots starting from Floor 1 and moving up.
// ============================================================================
public class NearestEntranceStrategy implements ParkingStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle) {
        // Iterate floors from lowest to highest (nearest entrance first)
        return lot.getFloors().stream()
            .sorted(Comparator.comparingInt(Floor::getFloorNumber))
            .map(floor -> floor.findAvailableSpot(vehicle))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }
}

// ============================================================================
// Most-available strategy: Fill the floor with most open spots.
// WHY? Distributes vehicles evenly across floors. Prevents one floor from
// being completely full while others are empty (better traffic flow).
// ============================================================================
public class MostAvailableFloorStrategy implements ParkingStrategy {

    @Override
    public Optional<ParkingSpot> findSpot(ParkingLot lot, Vehicle vehicle) {
        // Sort floors by available count (most available first)
        return lot.getFloors().stream()
            .sorted(Comparator.comparingLong(Floor::getAvailableCount).reversed())
            .map(floor -> floor.findAvailableSpot(vehicle))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .findFirst();
    }
}

// ============================================================================
// INTERFACE: PricingStrategy
// PATTERN: Strategy — Different pricing algorithms.
// WHY separate from ParkingStrategy? Single Responsibility (S in SOLID).
//   Spot assignment and pricing are independent concerns.
// WHY interface? Liskov Substitution (L) — any pricing can be swapped in.
// ============================================================================
public interface PricingStrategy {
    BigDecimal calculateFee(ParkingTicket ticket);
}

// ============================================================================
// Hourly pricing: Charge per hour, round up partial hours.
// ============================================================================
public class HourlyPricingStrategy implements PricingStrategy {

    @Override
    public BigDecimal calculateFee(ParkingTicket ticket) {
        long minutes = Duration.between(ticket.getEntryTime(), 
                                        ticket.getExitTime()).toMinutes();
        // Round up to next hour (45 minutes = 1 hour, 61 minutes = 2 hours)
        long hours = (minutes + 59) / 60;
        
        return ticket.getHourlyRate().multiply(BigDecimal.valueOf(hours));
    }
}

// ============================================================================
// Peak-hour pricing: Higher rates during peak hours.
// PATTERN: Decorator-like — wraps base pricing with peak-hour multiplier.
// WHY? Airport lots charge more during morning/evening rush. Mall lots
// charge more on weekends. This strategy applies time-based multipliers.
// ============================================================================
public class PeakHourPricingStrategy implements PricingStrategy {
    private final PricingStrategy baseStrategy;    // Composition over inheritance
    private final BigDecimal peakMultiplier;        // e.g., 1.5 = 50% surcharge
    private final int peakStartHour;               // e.g., 8 (8 AM)
    private final int peakEndHour;                 // e.g., 18 (6 PM)

    public PeakHourPricingStrategy(PricingStrategy baseStrategy, 
                                    BigDecimal peakMultiplier,
                                    int peakStartHour, int peakEndHour) {
        this.baseStrategy = baseStrategy;
        this.peakMultiplier = peakMultiplier;
        this.peakStartHour = peakStartHour;
        this.peakEndHour = peakEndHour;
    }

    @Override
    public BigDecimal calculateFee(ParkingTicket ticket) {
        BigDecimal baseFee = baseStrategy.calculateFee(ticket);

        int entryHour = ticket.getEntryTime().getHour();
        if (entryHour >= peakStartHour && entryHour < peakEndHour) {
            return baseFee.multiply(peakMultiplier).setScale(2, RoundingMode.HALF_UP);
        }

        return baseFee;
    }
}

// ============================================================================
// INTERFACE: PaymentProcessor
// PATTERN: Strategy — Different payment processing implementations.
// SOLID (I): Only ONE method. CashPaymentProcessor doesn't need to implement
//   card-specific methods, and vice versa.
// ============================================================================
public interface PaymentProcessor {
    PaymentResult processPayment(BigDecimal amount);
}

public class CashPaymentProcessor implements PaymentProcessor {
    @Override
    public PaymentResult processPayment(BigDecimal amount) {
        // Cash is always successful (collected at exit booth)
        return new PaymentResult(true, "CASH-" + UUID.randomUUID(), "Cash payment collected");
    }
}

public class CardPaymentProcessor implements PaymentProcessor {
    private final PaymentGateway gateway;   // External dependency injected

    public CardPaymentProcessor(PaymentGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public PaymentResult processPayment(BigDecimal amount) {
        try {
            String transactionId = gateway.charge(amount);
            return new PaymentResult(true, transactionId, "Card payment successful");
        } catch (PaymentGatewayException e) {
            return new PaymentResult(false, null, "Card payment failed: " + e.getMessage());
        }
    }
}

public class UPIPaymentProcessor implements PaymentProcessor {
    @Override
    public PaymentResult processPayment(BigDecimal amount) {
        // UPI payment processing logic
        String upiRef = "UPI-" + UUID.randomUUID();
        return new PaymentResult(true, upiRef, "UPI payment successful");
    }
}

// Simple result DTO
public record PaymentResult(boolean success, String transactionId, String message) {}
```

### Factory Pattern

```java
// ============================================================================
// PATTERN: Factory — Centralizes creation of PaymentProcessor objects.
// WHY? The caller (PaymentService) shouldn't know HOW to create each processor.
//   If we add a new payment method (Apple Pay), we only change this factory,
//   not every place that creates processors.
// SOLID (O): Open for extension — add new case, don't modify existing ones.
// SOLID (S): Factory's only job is creating objects.
// ============================================================================
public class PaymentProcessorFactory {
    
    private final PaymentGateway cardGateway;

    public PaymentProcessorFactory(PaymentGateway cardGateway) {
        this.cardGateway = cardGateway;
    }

    public PaymentProcessor create(PaymentMethod method) {
        return switch (method) {
            case CASH -> new CashPaymentProcessor();
            case CREDIT_CARD, DEBIT_CARD -> new CardPaymentProcessor(cardGateway);
            case UPI -> new UPIPaymentProcessor();
        };
    }
}
```

### Observer Pattern

```java
// ============================================================================
// PATTERN: Observer — Decouples spot status changes from notification logic.
// WHY? When a spot becomes available/occupied, multiple things need to happen:
//   1. Update display boards
//   2. Notify users waiting for spots
//   3. Update analytics dashboards
//   4. Trigger auto-scaling logic
// Without Observer, ParkingSpot would need to know about ALL these systems.
// With Observer, it just says "I changed" and listeners react independently.
// SOLID (S): ParkingSpot doesn't handle notifications.
// SOLID (O): Add new listeners without changing ParkingSpot.
// ============================================================================
public interface SpotAvailabilityObserver {
    void onSpotStatusChanged(ParkingSpot spot, SpotStatus oldStatus, SpotStatus newStatus);
}

// Concrete observer: Updates the display board
public class DisplayBoardObserver implements SpotAvailabilityObserver {
    @Override
    public void onSpotStatusChanged(ParkingSpot spot, SpotStatus oldStatus, SpotStatus newStatus) {
        System.out.printf("Display Board Updated: Floor %d, Spot %s: %s → %s%n",
            spot.getFloorNumber(), spot.getSpotNumber(), oldStatus, newStatus);
    }
}

// Concrete observer: Sends push notification to waiting users
public class UserNotificationObserver implements SpotAvailabilityObserver {
    private final NotificationService notificationService;

    public UserNotificationObserver(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onSpotStatusChanged(ParkingSpot spot, SpotStatus oldStatus, SpotStatus newStatus) {
        if (newStatus == SpotStatus.AVAILABLE) {
            notificationService.notifyWaitingUsers(spot);
        }
    }
}
```

### Core Service Classes

```java
// ============================================================================
// ParkingLot — The aggregate root. Represents one physical parking facility.
// OOP Concept: Composition — ParkingLot OWNS Floors which OWN Spots.
// PATTERN: Singleton-like per lot. In a real system, loaded from DB.
// ============================================================================
public class ParkingLot {
    private final String lotId;
    private final String name;
    private final List<Floor> floors;
    private final List<SpotAvailabilityObserver> observers;

    public ParkingLot(String lotId, String name) {
        this.lotId = lotId;
        this.name = name;
        this.floors = new ArrayList<>();
        this.observers = new ArrayList<>();
    }

    public void addFloor(Floor floor) {
        floors.add(floor);
    }

    public void addObserver(SpotAvailabilityObserver observer) {
        observers.add(observer);
    }

    public void notifyObservers(ParkingSpot spot, SpotStatus oldStatus, SpotStatus newStatus) {
        for (SpotAvailabilityObserver observer : observers) {
            observer.onSpotStatusChanged(spot, oldStatus, newStatus);
        }
    }

    public long getTotalAvailableSpots() {
        return floors.stream().mapToLong(Floor::getAvailableCount).sum();
    }

    public Map<SpotSize, Long> getAvailabilityBySize() {
        Map<SpotSize, Long> availability = new EnumMap<>(SpotSize.class);
        for (SpotSize size : SpotSize.values()) {
            long count = floors.stream()
                .mapToLong(f -> f.getAvailableCountBySize(size))
                .sum();
            availability.put(size, count);
        }
        return availability;
    }

    public String getLotId() { return lotId; }
    public String getName() { return name; }
    public List<Floor> getFloors() { return Collections.unmodifiableList(floors); }
}

// ============================================================================
// ParkingService — The main orchestrator. Handles vehicle entry and exit.
//
// SOLID (S): Only handles parking operations. Doesn't handle payment or notifications.
// SOLID (D): Depends on INTERFACES (ParkingStrategy, PricingStrategy),
//   not concrete implementations. These are injected via constructor.
// SOLID (O): New parking/pricing strategies don't require changing this class.
//
// PATTERN: This class demonstrates Dependency Injection (DI).
//   Strategies are INJECTED, not created internally.
// ============================================================================
public class ParkingService {

    private final ParkingStrategy parkingStrategy;
    private final PricingStrategy pricingStrategy;
    private final Map<String, ParkingTicket> activeTickets;   // ticketId → ticket

    /**
     * Constructor Injection — all dependencies are provided externally.
     * WHY? Makes the class testable (inject mocks in tests) and flexible
     * (swap strategies without changing this code).
     */
    public ParkingService(ParkingStrategy parkingStrategy, PricingStrategy pricingStrategy) {
        this.parkingStrategy = parkingStrategy;
        this.pricingStrategy = pricingStrategy;
        this.activeTickets = new ConcurrentHashMap<>();
    }

    /**
     * Process vehicle entry: Find spot → Assign → Create ticket.
     * 
     * @throws ParkingFullException if no suitable spot is available
     */
    public ParkingTicket vehicleEntry(ParkingLot lot, Vehicle vehicle) {
        // STEP 1: Use strategy to find appropriate spot
        // WHY strategy? Different lots may have different assignment rules
        ParkingSpot spot = parkingStrategy.findSpot(lot, vehicle)
            .orElseThrow(() -> new ParkingFullException(
                "No available spot for " + vehicle.getType() + " in " + lot.getName()));

        // STEP 2: Occupy the spot (synchronized inside ParkingSpot)
        SpotStatus oldStatus = spot.getStatus();
        spot.occupy(vehicle);

        // STEP 3: Create parking ticket using Builder pattern
        ParkingTicket ticket = ParkingTicket.builder()
            .ticketId(UUID.randomUUID().toString())
            .vehicle(vehicle)
            .spot(spot)
            .entryTime(LocalDateTime.now())
            .hourlyRate(getHourlyRate(lot, vehicle))
                    .build();

        // STEP 4: Store active ticket for later retrieval on exit
        activeTickets.put(ticket.getTicketId(), ticket);

        // STEP 5: Notify observers (display boards, waiting users)
        lot.notifyObservers(spot, oldStatus, SpotStatus.OCCUPIED);

        return ticket;
    }

    /**
     * Process vehicle exit: Calculate fee → Return fee amount.
     * Payment is handled separately by PaymentService (Single Responsibility).
     */
    public BigDecimal vehicleExit(ParkingLot lot, String ticketId) {
        // STEP 1: Find active ticket
        ParkingTicket ticket = activeTickets.get(ticketId);
        if (ticket == null) {
            throw new TicketNotFoundException("Ticket not found: " + ticketId);
        }

        // STEP 2: Calculate fee using pricing strategy
        // WHY strategy? Airport lot charges differently than mall lot.
        // Strategy is injected — this code doesn't know or care which one.
        LocalDateTime exitTime = LocalDateTime.now();
        
        // Temporarily set exit time for calculation
        ticket.completeParking(exitTime, BigDecimal.ZERO);
        BigDecimal fee = pricingStrategy.calculateFee(ticket);
        ticket.completeParking(exitTime, fee);

        // STEP 3: Release the spot
        ParkingSpot spot = ticket.getSpot();
        spot.release();

        // STEP 4: Remove from active tickets
        activeTickets.remove(ticketId);

        // STEP 5: Notify observers
        lot.notifyObservers(spot, SpotStatus.OCCUPIED, SpotStatus.AVAILABLE);

        return fee;
    }

    private BigDecimal getHourlyRate(ParkingLot lot, Vehicle vehicle) {
        // In real system, this would query pricing_rules table
        return switch (vehicle.getType()) {
            case MOTORCYCLE -> new BigDecimal("20.00");
            case CAR -> new BigDecimal("40.00");
            case TRUCK, VAN -> new BigDecimal("60.00");
        };
    }

    public ParkingTicket getTicket(String ticketId) {
        return activeTickets.get(ticketId);
    }

    public int getActiveTicketCount() {
        return activeTickets.size();
    }
}

// ============================================================================
// PaymentService — Handles payment processing.
//
// SOLID (S): Only handles payments. Doesn't know about parking logic.
// SOLID (D): Depends on PaymentProcessorFactory (abstraction), not concrete processors.
// PATTERN: Factory — Uses PaymentProcessorFactory to create the right processor.
// ============================================================================
public class PaymentService {
    
    private final PaymentProcessorFactory processorFactory;

    public PaymentService(PaymentProcessorFactory processorFactory) {
        this.processorFactory = processorFactory;
    }

    /**
     * Process payment for a parking ticket.
     * Uses Factory pattern to get the right payment processor.
     */
    public Payment processPayment(ParkingTicket ticket, PaymentMethod method) {
        if (ticket.getStatus() != TicketStatus.COMPLETED) {
            throw new IllegalStateException("Ticket must be completed before payment");
        }

        // Factory creates the appropriate processor based on payment method
        PaymentProcessor processor = processorFactory.create(method);

        // Process payment through the selected processor
        PaymentResult result = processor.processPayment(ticket.getTotalAmount());

        // Create payment record
        Payment payment = new Payment(
            UUID.randomUUID().toString(),
            ticket.getTicketId(),
            ticket.getTotalAmount(),
            method,
            result.success() ? PaymentStatus.SUCCESS : PaymentStatus.FAILED,
            result.transactionId()
        );

        return payment;
    }
}
```

### Putting It All Together — Main / Demo

```java
// ============================================================================
// DEMO: Shows how all components wire together.
// In a real Spring Boot app, these would be @Bean configurations.
// ============================================================================
public class ParkingLotDemo {

    public static void main(String[] args) {
        // ── SETUP: Create parking lot with floors and spots ──────────────
        ParkingLot lot = new ParkingLot("lot-1", "Airport Parking");

        Floor floor1 = new Floor("floor-1", 1);
        floor1.addSpot(new ParkingSpot("s1", "A-01", SpotSize.SMALL, 1, false, false));
        floor1.addSpot(new ParkingSpot("s2", "A-02", SpotSize.MEDIUM, 1, false, false));
        floor1.addSpot(new ParkingSpot("s3", "A-03", SpotSize.MEDIUM, 1, false, true));
        floor1.addSpot(new ParkingSpot("s4", "A-04", SpotSize.LARGE, 1, false, false));

        Floor floor2 = new Floor("floor-2", 2);
        floor2.addSpot(new ParkingSpot("s5", "B-01", SpotSize.MEDIUM, 2, false, false));
        floor2.addSpot(new ParkingSpot("s6", "B-02", SpotSize.LARGE, 2, true, false));

        lot.addFloor(floor1);
        lot.addFloor(floor2);

        // ── SETUP: Register observers ────────────────────────────────────
        lot.addObserver(new DisplayBoardObserver());

        // ── SETUP: Choose strategies (Dependency Injection) ──────────────
        // Strategy Pattern: We can swap these without changing ParkingService
        ParkingStrategy parkingStrategy = new NearestEntranceStrategy();
        PricingStrategy pricingStrategy = new PeakHourPricingStrategy(
            new HourlyPricingStrategy(),
            new BigDecimal("1.5"),    // 50% peak surcharge
            8, 18                     // Peak: 8 AM to 6 PM
        );

        ParkingService parkingService = new ParkingService(parkingStrategy, pricingStrategy);

        // Factory Pattern: Creates appropriate payment processor
        PaymentProcessorFactory paymentFactory = new PaymentProcessorFactory(null);
        PaymentService paymentService = new PaymentService(paymentFactory);

        // ── FLOW: Vehicle Entry ──────────────────────────────────────────
        Vehicle car = new Vehicle("v1", "MH-12-AB-1234", VehicleType.CAR, "John");
        Vehicle motorcycle = new Vehicle("v2", "MH-12-CD-5678", VehicleType.MOTORCYCLE, "Jane");

        System.out.println("Available spots: " + lot.getTotalAvailableSpots());

        ParkingTicket ticket1 = parkingService.vehicleEntry(lot, car);
        System.out.println("Car parked at spot: " + ticket1.getSpot().getSpotNumber());
        System.out.println("Available spots: " + lot.getTotalAvailableSpots());

        ParkingTicket ticket2 = parkingService.vehicleEntry(lot, motorcycle);
        System.out.println("Motorcycle parked at spot: " + ticket2.getSpot().getSpotNumber());

        // ── FLOW: Vehicle Exit ───────────────────────────────────────────
        BigDecimal fee = parkingService.vehicleExit(lot, ticket1.getTicketId());
        System.out.println("Fee for car: ₹" + fee);

        // ── FLOW: Payment ────────────────────────────────────────────────
        Payment payment = paymentService.processPayment(ticket1, PaymentMethod.UPI);
        System.out.println("Payment status: " + payment.getStatus());
        System.out.println("Available spots after exit: " + lot.getTotalAvailableSpots());
    }
}
```

---

## Request Flows

### Vehicle Entry Flow

```
User arrives → Gate scanner reads license plate
    │
    ▼
ParkingService.vehicleEntry(lot, vehicle)
    │
    ├── ParkingStrategy.findSpot(lot, vehicle)        [Strategy Pattern]
    │       │
    │       ├── Iterates floors (sorted by strategy)
    │       ├── For each floor: floor.findAvailableSpot(vehicle)
    │       └── Returns first matching spot (or empty)
    │
    ├── ParkingSpot.occupy(vehicle)                    [Encapsulation + Thread Safety]
    │       │
    │       ├── Validates spot is AVAILABLE
    │       ├── Validates vehicle fits (canFitVehicle)
    │       └── Sets status = OCCUPIED, currentVehicle = vehicle
    │
    ├── ParkingTicket.builder()...build()              [Builder Pattern]
    │       │
    │       └── Creates immutable ticket with entry details
    │
    ├── Store ticket in activeTickets map
    │
    └── lot.notifyObservers(...)                       [Observer Pattern]
            │
            ├── DisplayBoardObserver → Update display
            └── UserNotificationObserver → Notify waiting users
```

### Vehicle Exit Flow

```
User arrives at exit → Scans ticket
    │
    ▼
ParkingService.vehicleExit(lot, ticketId)
    │
    ├── Look up active ticket
    │
    ├── PricingStrategy.calculateFee(ticket)           [Strategy Pattern]
    │       │
    │       ├── HourlyPricingStrategy: hours × rate
    │       └── PeakHourPricingStrategy: base × multiplier (if peak)
    │
    ├── ParkingSpot.release()                          [Encapsulation]
    │
    └── lot.notifyObservers(...)                       [Observer Pattern]

    ▼
PaymentService.processPayment(ticket, method)
    │
    ├── PaymentProcessorFactory.create(method)         [Factory Pattern]
    │       │
    │       ├── CASH → CashPaymentProcessor
    │       ├── CARD → CardPaymentProcessor
    │       └── UPI  → UPIPaymentProcessor
    │
    └── processor.processPayment(amount)               [Strategy Pattern]
            │
            └── Returns PaymentResult(success, txnId)
```

---

## Edge Cases & Tests

```java
// ============================================================================
// TESTS: Cover the critical paths and edge cases.
// Each test name describes the scenario and expected behavior.
// ============================================================================

// 1. Parking a car when lot is full should throw ParkingFullException
@Test
void vehicleEntry_whenLotFull_throwsParkingFullException() {
    // Fill all spots
    parkingService.vehicleEntry(lot, new Vehicle("v1", "P1", VehicleType.CAR, "A"));
    parkingService.vehicleEntry(lot, new Vehicle("v2", "P2", VehicleType.CAR, "B"));
    // All medium/large spots occupied
    assertThrows(ParkingFullException.class, () ->
        parkingService.vehicleEntry(lot, new Vehicle("v3", "P3", VehicleType.CAR, "C")));
}

// 2. Truck should not fit in a SMALL spot
@Test
void vehicleEntry_truckInSmallLot_assignsLargeSpot() {
    Vehicle truck = new Vehicle("v1", "P1", VehicleType.TRUCK, "A");
    ParkingTicket ticket = parkingService.vehicleEntry(lot, truck);
    assertEquals(SpotSize.LARGE, ticket.getSpot().getSize());
}

// 3. Motorcycle CAN park in MEDIUM or LARGE spot (larger spot fits smaller vehicle)
@Test
void vehicleEntry_motorcycleCanUseAnySpot() {
    Vehicle bike = new Vehicle("v1", "P1", VehicleType.MOTORCYCLE, "A");
    ParkingTicket ticket = parkingService.vehicleEntry(lot, bike);
    assertNotNull(ticket);
}

// 4. Double-occupy same spot should throw exception (thread safety)
@Test
void occupy_sameSpotTwice_throwsException() {
    ParkingSpot spot = new ParkingSpot("s1", "A-01", SpotSize.MEDIUM, 1, false, false);
    Vehicle v1 = new Vehicle("v1", "P1", VehicleType.CAR, "A");
    Vehicle v2 = new Vehicle("v2", "P2", VehicleType.CAR, "B");
    spot.occupy(v1);
    assertThrows(SpotNotAvailableException.class, () -> spot.occupy(v2));
}

// 5. Exit with invalid ticket ID
@Test
void vehicleExit_invalidTicket_throwsException() {
    assertThrows(TicketNotFoundException.class, () ->
        parkingService.vehicleExit(lot, "non-existent-ticket"));
}

// 6. Payment with failed card should return FAILED status
@Test
void payment_cardDeclined_returnsFailedStatus() {
    // Mock card gateway to throw exception
    PaymentGateway failingGateway = mock(PaymentGateway.class);
    when(failingGateway.charge(any())).thenThrow(new PaymentGatewayException("Declined"));
    
    PaymentProcessorFactory factory = new PaymentProcessorFactory(failingGateway);
    PaymentService service = new PaymentService(factory);
    
    Payment payment = service.processPayment(completedTicket, PaymentMethod.CREDIT_CARD);
    assertEquals(PaymentStatus.FAILED, payment.getStatus());
}

// 7. Fee calculation: 2.5 hours should charge for 3 hours (round up)
@Test
void pricing_partialHour_roundsUp() {
    PricingStrategy strategy = new HourlyPricingStrategy();
    // Create ticket with 2.5 hours duration
    ParkingTicket ticket = createTicketWithDuration(150); // 150 minutes
    BigDecimal fee = strategy.calculateFee(ticket);
    assertEquals(new BigDecimal("120.00"), fee); // 3 hours × $40/hr
}

// 8. Peak hour pricing applies multiplier correctly
@Test
void peakPricing_duringPeakHours_appliesMultiplier() {
    PricingStrategy strategy = new PeakHourPricingStrategy(
        new HourlyPricingStrategy(), new BigDecimal("1.5"), 8, 18);
    ParkingTicket ticket = createTicketWithEntryAt(10); // 10 AM = peak
    BigDecimal fee = strategy.calculateFee(ticket);
    // base $40 × 1.5 = $60 per hour
    assertTrue(fee.compareTo(new BigDecimal("40.00")) > 0);
}
```

---

## Summary of Patterns & Principles

```
┌─────────────────────────────────────────────────────────────────┐
│  DESIGN PATTERNS                                                │
│                                                                 │
│  Strategy  → ParkingStrategy, PricingStrategy, PaymentProcessor │
│  Factory   → PaymentProcessorFactory                            │
│  Builder   → ParkingTicket.Builder                              │
│  Observer  → SpotAvailabilityObserver                           │
│  Singleton → ParkingLotManager (in real Spring app, @Service)   │
│                                                                 │
│  SOLID PRINCIPLES                                               │
│                                                                 │
│  S → Each class has ONE responsibility                          │
│  O → New vehicle types/strategies don't modify existing code    │
│  L → Any Strategy implementation is interchangeable             │
│  I → Small, focused interfaces (1-2 methods each)              │
│  D → Services depend on interfaces, not concrete classes        │
│                                                                 │
│  OOP CONCEPTS                                                   │
│                                                                 │
│  Encapsulation  → ParkingSpot manages its own state            │
│  Composition    → ParkingLot → Floors → Spots                  │
│  Abstraction    → Interfaces hide implementation details        │
│  Polymorphism   → Different strategies via same interface       │
│  Immutability   → Vehicle, core ticket fields                   │
└─────────────────────────────────────────────────────────────────┘
```
