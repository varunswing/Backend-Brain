# Ride Sharing System (Uber/Ola) - Low Level Design (Machine Coding)

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

Design a ride-sharing system (Uber/Ola) that matches riders with nearby drivers, handles real-time tracking, calculates dynamic fares with surge pricing, and manages ride state transitions.

---

## Requirements

### Functional Requirements
1. Rider can request a ride (pickup, dropoff, vehicle type)
2. System matches rider with nearest available driver
3. Driver can accept/reject ride requests
4. Real-time tracking during ride
5. Fare estimation before ride, final calculation after
6. Surge pricing based on demand/supply
7. Rating system (rider rates driver and vice versa)
8. Support multiple vehicle types (economy, premium, bike)

### Non-Functional Requirements
- Match rider with driver in < 5 seconds
- Handle 700K location updates/second
- Zero double-assignment (one driver, one ride at a time)
- 99.99% uptime (safety critical)

---

## Database Design with Explanations

```sql
-- ============================================================================
-- TABLE: users
-- WHY: Both riders and drivers are users. We use a shared table because:
--      1. Both have common data (name, phone, email, rating)
--      2. A person could be BOTH a rider and driver (Uber allows this)
--      3. Auth/login is the same for both — one account
--      4. Simplifies the user lookup: one query regardless of role
--
-- WHY NOT separate rider/driver tables?
--   Because they share 80% of their fields. Separate tables would mean
--   duplicating phone, name, email, etc. Use role field + extension tables.
-- ============================================================================
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    phone_number VARCHAR(15) UNIQUE NOT NULL,  -- Primary identifier for Uber-like apps
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100),
    email VARCHAR(255),
    role VARCHAR(20) NOT NULL,              -- RIDER, DRIVER, BOTH
    average_rating DECIMAL(3,2) DEFAULT 5.0,
    total_rides INTEGER DEFAULT 0,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? Phone-based login is the primary auth method.
-- Without this index, every login attempt is a full table scan.
CREATE INDEX idx_users_phone ON users(phone_number);

-- ============================================================================
-- TABLE: driver_profiles
-- WHY: A separate table for driver-specific data because:
--      1. Only drivers have license, vehicle, online status, location
--      2. Riders don't need these fields — putting them in users would
--         waste space and violate normalization
--      3. Driver profile has its own lifecycle (onboarding, verification)
--      4. Location updates are HIGH frequency (every 5 sec). Keeping it
--         in a separate table avoids write contention on the users table.
--
-- RELATIONSHIP: driver_profiles → users (One-to-One)
--   WHY FK + PK? The driver_id IS the user_id. This ensures:
--   - Every driver MUST be a valid user first
--   - Can't have a driver profile without a user account
--   - Easy JOIN: users JOIN driver_profiles ON user_id = driver_id
--
-- WHY One-to-One instead of putting it in users?
--   Interface Segregation in database form. Riders shouldn't carry
--   driver_license, vehicle_info columns. Also, the location field
--   updates 12 times/minute — you don't want that write load on users.
-- ============================================================================
CREATE TABLE driver_profiles (
    driver_id UUID PRIMARY KEY REFERENCES users(user_id),
    license_number VARCHAR(50) UNIQUE NOT NULL,
    vehicle_type VARCHAR(20) NOT NULL,      -- ECONOMY, PREMIUM, BIKE
    vehicle_make VARCHAR(50),
    vehicle_model VARCHAR(50),
    vehicle_plate VARCHAR(20) NOT NULL,
    vehicle_color VARCHAR(30),
    
    -- Real-time state
    is_online BOOLEAN DEFAULT false,        -- Driver toggled "Go Online"
    is_available BOOLEAN DEFAULT false,     -- Online AND not on a ride
    current_ride_id UUID,                   -- Non-null = currently on a ride
    
    -- Location (POINT type for PostgreSQL geospatial queries)
    current_location POINT,                 -- (longitude, latitude)
    location_updated_at TIMESTAMP,
    
    -- Verification
    is_verified BOOLEAN DEFAULT false,
    verified_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY GIST index on location? GIST (Generalized Search Tree) is designed for
-- geospatial data. It enables "find drivers within 5km" queries in O(log n)
-- instead of O(n). Without it, finding nearby drivers scans every row.
CREATE INDEX idx_drivers_location ON driver_profiles USING GIST (current_location);

-- WHY this composite index? The driver matching query filters by:
-- is_online = true AND is_available = true AND vehicle_type = 'ECONOMY'
-- This index satisfies all three conditions directly.
CREATE INDEX idx_drivers_available ON driver_profiles(is_online, is_available, vehicle_type)
    WHERE is_online = true AND is_available = true;

-- ============================================================================
-- TABLE: rides
-- WHY: The core transaction table. Every ride request creates a ride record.
--      Even cancelled/failed rides are recorded for analytics and disputes.
--      This table captures the complete lifecycle of a ride.
--
-- RELATIONSHIP: rides → users (rider_id, Many-to-One)
--   WHY? A rider can take many rides. Each ride belongs to one rider.
--
-- RELATIONSHIP: rides → driver_profiles (driver_id, Many-to-One)
--   WHY? A driver can complete many rides. Each ride is done by one driver.
--   Note: driver_id is NULLABLE because when a ride is first requested,
--   no driver has been assigned yet.
--
-- WHY store both estimated_fare and actual_fare?
--   Estimated fare is shown BEFORE the ride (user decides whether to book).
--   Actual fare is calculated AFTER the ride (based on real distance/time).
--   They can differ (detours, traffic). Both are needed for billing and disputes.
-- ============================================================================
CREATE TABLE rides (
    ride_id UUID PRIMARY KEY,
    rider_id UUID NOT NULL REFERENCES users(user_id),
    driver_id UUID REFERENCES driver_profiles(driver_id),  -- Nullable until matched
    
    -- Ride type
    vehicle_type VARCHAR(20) NOT NULL,

    -- Locations
    pickup_location POINT NOT NULL,
    dropoff_location POINT NOT NULL,
    pickup_address TEXT,
    dropoff_address TEXT,

    -- State machine (see RideStatus enum)
    ride_status VARCHAR(20) DEFAULT 'REQUESTED',
    -- REQUESTED → MATCHED → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED
    --                     → CANCELLED (at any point before IN_PROGRESS)

    -- Fare
    estimated_distance_km DECIMAL(8,2),
    estimated_duration_min INTEGER,
    estimated_fare DECIMAL(8,2),
    actual_distance_km DECIMAL(8,2),
    actual_duration_min INTEGER,
    actual_fare DECIMAL(8,2),
    surge_multiplier DECIMAL(3,2) DEFAULT 1.0,

    -- Timing
    requested_at TIMESTAMP DEFAULT NOW(),
    matched_at TIMESTAMP,                   -- When driver was assigned
    driver_arrived_at TIMESTAMP,            -- When driver reached pickup
    started_at TIMESTAMP,                   -- When ride began (pickup)
    completed_at TIMESTAMP,                 -- When ride ended (dropoff)
    cancelled_at TIMESTAMP,
    cancelled_by VARCHAR(20),               -- RIDER or DRIVER

    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_rides_rider ON rides(rider_id, requested_at DESC);
CREATE INDEX idx_rides_driver ON rides(driver_id, requested_at DESC);

-- WHY this partial index? Background jobs and dashboards query active rides.
-- Most rides are completed — filtering to only active ones keeps the index small.
CREATE INDEX idx_rides_active ON rides(ride_status)
    WHERE ride_status NOT IN ('COMPLETED', 'CANCELLED');

-- ============================================================================
-- TABLE: ride_route
-- WHY: Stores the GPS breadcrumb trail during a ride.
--      Separated from rides because:
--      1. One ride has HUNDREDS of location points (every 5 seconds)
--      2. It's append-only during the ride (write-heavy)
--      3. It's queried separately (show route on map, calculate actual distance)
--      4. If embedded in rides table, the row would grow huge
--
-- RELATIONSHIP: ride_route → rides (Many-to-One)
--   WHY? Each location point belongs to exactly one ride.
--   A ride has many route points (1 per 5 seconds × 20 min = 240 points).
-- ============================================================================
CREATE TABLE ride_route (
    id BIGSERIAL PRIMARY KEY,
    ride_id UUID NOT NULL REFERENCES rides(ride_id),
    location POINT NOT NULL,
    recorded_at TIMESTAMP DEFAULT NOW()
);

-- WHY this index? To retrieve route for a ride, ordered by time.
CREATE INDEX idx_route_ride ON ride_route(ride_id, recorded_at);

-- ============================================================================
-- TABLE: ratings
-- WHY: Separate from rides because:
--      1. Ratings can be given AFTER ride completes (async)
--      2. Both rider and driver rate each other — two ratings per ride
--      3. Rating has its own data (score, comment, timestamp)
--      4. Need to query "all ratings for a driver" without touching rides table
--
-- RELATIONSHIP: ratings → rides (Many-to-One, but effectively 2 per ride)
--   Each ride generates up to 2 ratings: rider→driver and driver→rider.
--
-- RELATIONSHIP: ratings → users (rated_by → rater, rated_user → ratee)
-- ============================================================================
CREATE TABLE ratings (
    rating_id UUID PRIMARY KEY,
    ride_id UUID NOT NULL REFERENCES rides(ride_id),
    rated_by UUID NOT NULL REFERENCES users(user_id),
    rated_user UUID NOT NULL REFERENCES users(user_id),
    score INTEGER NOT NULL CHECK (score BETWEEN 1 AND 5),
    comment TEXT,
    created_at TIMESTAMP DEFAULT NOW(),

    -- One rating per person per ride
    UNIQUE(ride_id, rated_by)
);

CREATE INDEX idx_ratings_user ON ratings(rated_user, created_at DESC);

-- ============================================================================
-- TABLE: payments
-- WHY: Same reasoning as other systems — payments are separate because:
--      1. Multiple payment attempts per ride
--      2. Refunds are separate records
--      3. Payment has external references (gateway txn ids)
--      4. Compliance/auditing requirements
-- ============================================================================
CREATE TABLE payments (
    payment_id UUID PRIMARY KEY,
    ride_id UUID NOT NULL REFERENCES rides(ride_id),
    amount DECIMAL(8,2) NOT NULL,
    payment_type VARCHAR(20) NOT NULL,      -- CHARGE, REFUND, CANCELLATION_FEE
    payment_method VARCHAR(20) NOT NULL,    -- CARD, UPI, WALLET, CASH
    payment_status VARCHAR(20) DEFAULT 'PENDING',
    transaction_reference VARCHAR(100),
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_payments_ride ON payments(ride_id);
```

### Entity Relationship Summary

```
users (1) ─────── (0..1) driver_profiles
  │                          │
  │ (1)                      │ (1)
  │                          │
  rides (N) ─────────────────┘ (driver_id, nullable)
  │  ↑
  │  │ (N)
  │  └─── ride_route (N)  ←── GPS breadcrumbs
  │
  │  (1)
  │
  ratings (N) ←── 2 per ride (rider rates driver, driver rates rider)
  │
  payments (N) ←── charge + optional refund

KEY DESIGN DECISIONS:
- users + driver_profiles (One-to-One extension): Riders and drivers share auth.
  Driver-specific data lives in extension table to avoid polluting users table
  with 15+ driver-only columns.
  
- rides.driver_id is NULLABLE: At ride creation, no driver exists yet.
  It's assigned during matching. This avoids dummy/placeholder driver records.

- ride_route is separate from rides: Write pattern is append-only, 240+ rows
  per ride. Embedding in rides would create massive row bloat.

- ratings has UNIQUE(ride_id, rated_by): Prevents a user from rating the
  same ride twice (bug protection + idempotency).
```

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | DriverMatchingStrategy, FareStrategy, SurgePricingStrategy | Different matching algorithms and pricing rules. Swap without changing services. |
| **State** | RideStateMachine | Ride has strict state transitions. State pattern prevents invalid transitions. |
| **Observer** | RideEventObserver | Notify tracking, analytics, notifications when ride status changes. |
| **Factory** | FareCalculatorFactory | Create correct fare calculator based on vehicle type. |
| **Command** | RideCommand | Encapsulate ride operations (request, cancel) for undo and auditing. |

## SOLID Principles Applied

| Principle | How Applied |
|-----------|------------|
| **S** | `DriverMatchingService` only matches. `FareService` only calculates fares. `RideService` orchestrates. Each has ONE job. |
| **O** | New matching algorithm (ML-based)? Just implement `DriverMatchingStrategy`. Zero changes to `RideService`. |
| **L** | `NearestDriverStrategy` and `RatingWeightedStrategy` are fully interchangeable. `RideService` doesn't know which one runs. |
| **I** | `DriverMatchingStrategy` has only `findDriver()`. `FareStrategy` has only `calculateFare()`. No bloated interfaces. |
| **D** | `RideService` depends on `DriverMatchingStrategy` interface, not `NearestDriverStrategy` class. Injected via constructor. |

---

## Code Implementation

### Enums

```java
public enum VehicleType {
    BIKE,
    ECONOMY,
    PREMIUM,
    SUV;

    public BigDecimal getBaseRate() {
        return switch (this) {
            case BIKE -> new BigDecimal("5.00");
            case ECONOMY -> new BigDecimal("10.00");
            case PREMIUM -> new BigDecimal("18.00");
            case SUV -> new BigDecimal("22.00");
        };
    }

    public BigDecimal getPerKmRate() {
        return switch (this) {
            case BIKE -> new BigDecimal("6.00");
            case ECONOMY -> new BigDecimal("12.00");
            case PREMIUM -> new BigDecimal("20.00");
            case SUV -> new BigDecimal("25.00");
        };
    }
}

// ============================================================================
// RideStatus follows a strict state machine:
//   REQUESTED → MATCHED → DRIVER_ARRIVED → IN_PROGRESS → COMPLETED
//   Any state before IN_PROGRESS can transition to CANCELLED.
// ============================================================================
public enum RideStatus {
    REQUESTED,        // Rider requested, looking for driver
    MATCHED,          // Driver assigned, heading to pickup
    DRIVER_ARRIVED,   // Driver reached pickup point
    IN_PROGRESS,      // Ride started (rider picked up)
    COMPLETED,        // Ride finished (rider dropped off)
    CANCELLED;        // Cancelled by rider or driver

    /**
     * Validates if the given transition is allowed.
     * WHY here? Encapsulates valid transitions inside the enum itself.
     * No external code can force invalid transitions.
     */
    public boolean canTransitionTo(RideStatus next) {
        return switch (this) {
            case REQUESTED -> next == MATCHED || next == CANCELLED;
            case MATCHED -> next == DRIVER_ARRIVED || next == CANCELLED;
            case DRIVER_ARRIVED -> next == IN_PROGRESS || next == CANCELLED;
            case IN_PROGRESS -> next == COMPLETED;
            case COMPLETED, CANCELLED -> false;  // Terminal states
        };
    }
}
```

### Model Classes

```java
// ============================================================================
// MODEL: Location (Value Object)
// WHY a separate class? Latitude/longitude always travel together.
// A Value Object is immutable and compared by value, not identity.
// Two Location objects with the same lat/lng are EQUAL.
// ============================================================================
public record Location(double latitude, double longitude) {

    /**
     * Calculate distance between two locations using Haversine formula.
     * WHY here? Distance calculation is intrinsic to Location.
     * It's not a service concern — it's a geometric operation on coordinates.
     */
    public double distanceTo(Location other) {
        final double R = 6371; // Earth radius in km
        double dLat = Math.toRadians(other.latitude - this.latitude);
        double dLon = Math.toRadians(other.longitude - this.longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(this.latitude))
                 * Math.cos(Math.toRadians(other.latitude))
                 * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }
}

// ============================================================================
// MODEL: Driver
// Represents a driver's current state. Mutable because driver status
// changes frequently (online/offline, available/on-ride, location).
// Thread-safe state transitions via synchronized methods.
// ============================================================================
public class Driver {
    private final String driverId;
    private final String name;
    private final VehicleType vehicleType;
    private final String vehiclePlate;
    private double rating;

    private boolean online;
    private boolean available;
    private Location currentLocation;
    private String currentRideId;

    public Driver(String driverId, String name, VehicleType vehicleType,
                  String vehiclePlate, double rating) {
        this.driverId = driverId;
        this.name = name;
        this.vehicleType = vehicleType;
        this.vehiclePlate = vehiclePlate;
        this.rating = rating;
        this.online = false;
        this.available = false;
    }

    public synchronized void goOnline(Location location) {
        this.online = true;
        this.available = true;
        this.currentLocation = location;
    }

    public synchronized void goOffline() {
        if (currentRideId != null) {
            throw new IllegalStateException("Cannot go offline during an active ride");
        }
        this.online = false;
        this.available = false;
    }

    public synchronized void assignRide(String rideId) {
        if (!available) {
            throw new DriverNotAvailableException("Driver " + driverId + " is not available");
        }
        this.available = false;
        this.currentRideId = rideId;
    }

    public synchronized void completeRide() {
        this.available = true;
        this.currentRideId = null;
    }

    public void updateLocation(Location location) {
        this.currentLocation = location;
    }

    public boolean isAvailableFor(VehicleType requestedType) {
        return online && available && vehicleType == requestedType;
    }

    // Getters
    public String getDriverId() { return driverId; }
    public String getName() { return name; }
    public VehicleType getVehicleType() { return vehicleType; }
    public double getRating() { return rating; }
    public Location getCurrentLocation() { return currentLocation; }
    public boolean isAvailable() { return online && available; }
}

// ============================================================================
// MODEL: Ride
// PATTERN: State Machine — Ride status transitions are validated.
// OOP: Encapsulation — External code can't set ride_status directly.
// Must call transition methods that validate the state change.
// ============================================================================
public class Ride {
    private final String rideId;
    private final String riderId;
    private final Location pickup;
    private final Location dropoff;
    private final VehicleType vehicleType;

    private String driverId;
    private RideStatus status;
    private BigDecimal estimatedFare;
    private BigDecimal actualFare;
    private BigDecimal surgeMultiplier;

    private LocalDateTime requestedAt;
    private LocalDateTime matchedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    public Ride(String rideId, String riderId, Location pickup, Location dropoff,
                VehicleType vehicleType) {
        this.rideId = rideId;
        this.riderId = riderId;
        this.pickup = pickup;
        this.dropoff = dropoff;
        this.vehicleType = vehicleType;
        this.status = RideStatus.REQUESTED;
        this.requestedAt = LocalDateTime.now();
        this.surgeMultiplier = BigDecimal.ONE;
    }

    /**
     * PATTERN: State Machine — validates transition before applying.
     * WHY? Prevents bugs like "completing a cancelled ride" or
     * "matching a ride that's already in progress".
     */
    public void transitionTo(RideStatus newStatus) {
        if (!status.canTransitionTo(newStatus)) {
            throw new InvalidRideStateException(
                String.format("Cannot transition from %s to %s", status, newStatus));
        }
        this.status = newStatus;
    }

    public void assignDriver(String driverId) {
        transitionTo(RideStatus.MATCHED);
        this.driverId = driverId;
        this.matchedAt = LocalDateTime.now();
    }

    public void startRide() {
        transitionTo(RideStatus.IN_PROGRESS);
        this.startedAt = LocalDateTime.now();
    }

    public void completeRide(BigDecimal actualFare) {
        transitionTo(RideStatus.COMPLETED);
        this.actualFare = actualFare;
        this.completedAt = LocalDateTime.now();
    }

    public void cancel(String cancelledBy) {
        transitionTo(RideStatus.CANCELLED);
    }

    // Getters
    public String getRideId() { return rideId; }
    public String getRiderId() { return riderId; }
    public String getDriverId() { return driverId; }
    public Location getPickup() { return pickup; }
    public Location getDropoff() { return dropoff; }
    public VehicleType getVehicleType() { return vehicleType; }
    public RideStatus getStatus() { return status; }
    public BigDecimal getEstimatedFare() { return estimatedFare; }
    public void setEstimatedFare(BigDecimal fare) { this.estimatedFare = fare; }
    public BigDecimal getSurgeMultiplier() { return surgeMultiplier; }
    public void setSurgeMultiplier(BigDecimal multiplier) { this.surgeMultiplier = multiplier; }
}
```

### Strategy Implementations

```java
// ============================================================================
// INTERFACE: DriverMatchingStrategy
// PATTERN: Strategy — Different algorithms for matching riders with drivers.
// WHY? Matching is the HEART of ride-sharing. We need different strategies:
//   - Simple nearest driver (startup, low traffic)
//   - Rating-weighted (balance quality and distance)
//   - ML-based (production Uber uses this)
// ============================================================================
public interface DriverMatchingStrategy {
    Optional<Driver> findBestDriver(Location pickup, VehicleType vehicleType,
                                     List<Driver> availableDrivers);
}

// ============================================================================
// Nearest Driver Strategy: Simply picks the closest available driver.
// WHEN: Low complexity, startup phase, low traffic areas.
// TRADE-OFF: Fast but ignores driver quality and acceptance rate.
// ============================================================================
public class NearestDriverStrategy implements DriverMatchingStrategy {

    private static final double MAX_RADIUS_KM = 5.0;

    @Override
    public Optional<Driver> findBestDriver(Location pickup, VehicleType vehicleType,
                                            List<Driver> availableDrivers) {
        return availableDrivers.stream()
            .filter(d -> d.isAvailableFor(vehicleType))
            .filter(d -> d.getCurrentLocation().distanceTo(pickup) <= MAX_RADIUS_KM)
            .min(Comparator.comparingDouble(
                d -> d.getCurrentLocation().distanceTo(pickup)));
    }
}

// ============================================================================
// Rating-Weighted Strategy: Balances distance AND driver rating.
// Score = (1 / distance) * 0.4 + rating * 0.6
// WHY? Users want GOOD drivers, not just the nearest one. A 4.9★ driver
// 3km away is better than a 3.5★ driver 1km away.
// ============================================================================
public class RatingWeightedStrategy implements DriverMatchingStrategy {

    private static final double MAX_RADIUS_KM = 5.0;
    private static final double DISTANCE_WEIGHT = 0.4;
    private static final double RATING_WEIGHT = 0.6;

    @Override
    public Optional<Driver> findBestDriver(Location pickup, VehicleType vehicleType,
                                            List<Driver> availableDrivers) {
        return availableDrivers.stream()
            .filter(d -> d.isAvailableFor(vehicleType))
            .filter(d -> d.getCurrentLocation().distanceTo(pickup) <= MAX_RADIUS_KM)
            .max(Comparator.comparingDouble(d -> calculateScore(d, pickup)));
    }

    private double calculateScore(Driver driver, Location pickup) {
        double distance = driver.getCurrentLocation().distanceTo(pickup);
        double distanceScore = 1.0 / (1.0 + distance); // Closer = higher score
        double ratingScore = driver.getRating() / 5.0;   // Normalize to 0-1
        return (distanceScore * DISTANCE_WEIGHT) + (ratingScore * RATING_WEIGHT);
    }
}

// ============================================================================
// INTERFACE: FareStrategy
// PATTERN: Strategy — Different fare calculation methods.
// WHY? Fare rules differ by city, vehicle type, and time of day.
// ============================================================================
public interface FareStrategy {
    BigDecimal calculateFare(double distanceKm, int durationMin, VehicleType vehicleType,
                             BigDecimal surgeMultiplier);
}

// ============================================================================
// Standard Fare: base_fare + (per_km × distance) + (per_min × time)
// This is the most common model used by ride-sharing apps.
// ============================================================================
public class StandardFareStrategy implements FareStrategy {

    private static final BigDecimal PER_MINUTE_RATE = new BigDecimal("2.00");
    private static final BigDecimal MINIMUM_FARE = new BigDecimal("30.00");

    @Override
    public BigDecimal calculateFare(double distanceKm, int durationMin,
                                     VehicleType vehicleType, BigDecimal surgeMultiplier) {
        BigDecimal baseFare = vehicleType.getBaseRate();
        BigDecimal distanceFare = vehicleType.getPerKmRate()
            .multiply(BigDecimal.valueOf(distanceKm));
        BigDecimal timeFare = PER_MINUTE_RATE
            .multiply(BigDecimal.valueOf(durationMin));

        BigDecimal subtotal = baseFare.add(distanceFare).add(timeFare);

        // Apply surge multiplier
        BigDecimal total = subtotal.multiply(surgeMultiplier)
            .setScale(2, RoundingMode.HALF_UP);

        // Enforce minimum fare
        return total.max(MINIMUM_FARE);
    }
}

// ============================================================================
// INTERFACE: SurgePricingStrategy
// WHY separate from FareStrategy? Single Responsibility.
// Surge calculation depends on demand/supply ratio, which is a different
// concern from fare calculation.
// ============================================================================
public interface SurgePricingStrategy {
    BigDecimal calculateSurgeMultiplier(Location location, VehicleType vehicleType);
}

// ============================================================================
// Demand-Supply Surge: surge = demand / supply.
// If 100 riders want ECONOMY in an area but only 20 drivers are available,
// surge = 100/20 = 5.0, capped at 3.0.
// ============================================================================
public class DemandSupplySurgeStrategy implements SurgePricingStrategy {

    private final RideRepository rideRepository;
    private final DriverRepository driverRepository;
    private static final BigDecimal MAX_SURGE = new BigDecimal("3.0");

    public DemandSupplySurgeStrategy(RideRepository rideRepository,
                                      DriverRepository driverRepository) {
        this.rideRepository = rideRepository;
        this.driverRepository = driverRepository;
    }

    @Override
    public BigDecimal calculateSurgeMultiplier(Location location, VehicleType vehicleType) {
        double radiusKm = 3.0;

        // Count active ride requests in the area (demand)
        long demand = rideRepository.countActiveRequestsNear(location, radiusKm, vehicleType);

        // Count available drivers in the area (supply)
        long supply = driverRepository.countAvailableDriversNear(location, radiusKm, vehicleType);

        if (supply == 0) return MAX_SURGE;
        if (demand <= supply) return BigDecimal.ONE; // No surge

        BigDecimal surge = BigDecimal.valueOf((double) demand / supply)
            .setScale(1, RoundingMode.HALF_UP);

        return surge.min(MAX_SURGE);
    }
}
```

### Core Services

```java
// ============================================================================
// RideService — The main orchestrator. Handles ride lifecycle.
//
// SOLID (S): Orchestrates ride flow. Delegates matching, pricing, payment.
// SOLID (D): Depends on strategy INTERFACES, injected via constructor.
//
// PATTERN: Uses Strategy for matching, pricing, surge.
// PATTERN: Uses Observer to notify on ride status changes.
// ============================================================================
public class RideService {

    private final DriverMatchingStrategy matchingStrategy;
    private final FareStrategy fareStrategy;
    private final SurgePricingStrategy surgeStrategy;
    private final DriverRepository driverRepository;
    private final RideRepository rideRepository;
    private final List<RideEventObserver> observers;

    public RideService(DriverMatchingStrategy matchingStrategy,
                       FareStrategy fareStrategy,
                       SurgePricingStrategy surgeStrategy,
                       DriverRepository driverRepository,
                       RideRepository rideRepository) {
        this.matchingStrategy = matchingStrategy;
        this.fareStrategy = fareStrategy;
        this.surgeStrategy = surgeStrategy;
        this.driverRepository = driverRepository;
        this.rideRepository = rideRepository;
        this.observers = new ArrayList<>();
    }

    public void addObserver(RideEventObserver observer) {
        observers.add(observer);
    }

    /**
     * STEP 1: Rider requests a ride.
     * Creates ride, calculates estimate, finds driver.
     */
    public Ride requestRide(String riderId, Location pickup, Location dropoff,
                            VehicleType vehicleType) {
        // Create ride
        Ride ride = new Ride(UUID.randomUUID().toString(), riderId,
                             pickup, dropoff, vehicleType);

        // Calculate surge multiplier
        BigDecimal surge = surgeStrategy.calculateSurgeMultiplier(pickup, vehicleType);
        ride.setSurgeMultiplier(surge);

        // Estimate fare
        double distance = pickup.distanceTo(dropoff);
        int estimatedDuration = estimateDuration(distance);
        BigDecimal estimatedFare = fareStrategy.calculateFare(
            distance, estimatedDuration, vehicleType, surge);
        ride.setEstimatedFare(estimatedFare);

        rideRepository.save(ride);

        // Find and assign driver
        matchDriver(ride);

        return ride;
    }

    /**
     * Find best driver using the injected strategy.
     * If no driver found, ride stays in REQUESTED status.
     * A background job retries matching every few seconds.
     */
    private void matchDriver(Ride ride) {
        List<Driver> availableDrivers = driverRepository.findAvailableDrivers(
            ride.getPickup(), 5.0, ride.getVehicleType());

        Optional<Driver> bestDriver = matchingStrategy.findBestDriver(
            ride.getPickup(), ride.getVehicleType(), availableDrivers);

        bestDriver.ifPresent(driver -> {
            // Assign driver (synchronized inside Driver to prevent double-assignment)
            driver.assignRide(ride.getRideId());
            ride.assignDriver(driver.getDriverId());

            driverRepository.save(driver);
            rideRepository.save(ride);

            notifyObservers(ride, "DRIVER_MATCHED");
        });
    }

    /**
     * Driver arrives at pickup location.
     */
    public void driverArrived(String rideId) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RideNotFoundException(rideId));

        ride.transitionTo(RideStatus.DRIVER_ARRIVED);
        rideRepository.save(ride);
        notifyObservers(ride, "DRIVER_ARRIVED");
    }

    /**
     * Start ride (rider picked up).
     */
    public void startRide(String rideId) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RideNotFoundException(rideId));

        ride.startRide();
        rideRepository.save(ride);
        notifyObservers(ride, "RIDE_STARTED");
    }

    /**
     * Complete ride. Calculate actual fare based on real distance/time.
     */
    public Ride completeRide(String rideId, double actualDistanceKm, int actualDurationMin) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RideNotFoundException(rideId));

        BigDecimal actualFare = fareStrategy.calculateFare(
            actualDistanceKm, actualDurationMin,
            ride.getVehicleType(), ride.getSurgeMultiplier());

        ride.completeRide(actualFare);

        // Free up the driver
        Driver driver = driverRepository.findById(ride.getDriverId()).orElseThrow();
        driver.completeRide();
        driverRepository.save(driver);

        rideRepository.save(ride);
        notifyObservers(ride, "RIDE_COMPLETED");

        return ride;
    }

    /**
     * Cancel ride.
     */
    public void cancelRide(String rideId, String cancelledBy) {
        Ride ride = rideRepository.findById(rideId)
            .orElseThrow(() -> new RideNotFoundException(rideId));

        ride.cancel(cancelledBy);

        // Free driver if assigned
        if (ride.getDriverId() != null) {
            Driver driver = driverRepository.findById(ride.getDriverId()).orElseThrow();
            driver.completeRide();
            driverRepository.save(driver);
        }

        rideRepository.save(ride);
        notifyObservers(ride, "RIDE_CANCELLED");
    }

    private int estimateDuration(double distanceKm) {
        // Rough estimate: 2 minutes per km in city traffic
        return (int) Math.ceil(distanceKm * 2);
    }

    private void notifyObservers(Ride ride, String event) {
        for (RideEventObserver obs : observers) {
            obs.onRideEvent(ride, event);
        }
    }
}
```

### Observer Pattern

```java
// ============================================================================
// PATTERN: Observer — Decouples ride events from reactions.
// When a ride is matched/started/completed, multiple systems need to know:
//   1. Push notification to rider/driver
//   2. Update real-time tracking map
//   3. Log event for analytics
//   4. Trigger payment (on completion)
// ============================================================================
public interface RideEventObserver {
    void onRideEvent(Ride ride, String event);
}

public class NotificationObserver implements RideEventObserver {
    @Override
    public void onRideEvent(Ride ride, String event) {
        switch (event) {
            case "DRIVER_MATCHED" -> System.out.println(
                "📱 Notify rider: Driver is on the way! Ride: " + ride.getRideId());
            case "DRIVER_ARRIVED" -> System.out.println(
                "📱 Notify rider: Driver has arrived at pickup!");
            case "RIDE_COMPLETED" -> System.out.println(
                "📱 Notify rider: Ride completed. Fare: ₹" + ride.getActualFare());
        }
    }
}

public class AnalyticsObserver implements RideEventObserver {
    @Override
    public void onRideEvent(Ride ride, String event) {
        System.out.println("📊 Analytics: " + event + " for ride " + ride.getRideId());
    }
}
```

### Demo

```java
public class RideSharingDemo {
    public static void main(String[] args) {
        // ── Setup ────────────────────────────────────────────────────────
        DriverRepository driverRepo = new InMemoryDriverRepository();
        RideRepository rideRepo = new InMemoryRideRepository();

        // Register drivers
        Driver d1 = new Driver("drv-1", "Ramesh", VehicleType.ECONOMY, "MH12AB1234", 4.8);
        d1.goOnline(new Location(19.0760, 72.8777)); // Mumbai
        Driver d2 = new Driver("drv-2", "Suresh", VehicleType.ECONOMY, "MH12CD5678", 4.5);
        d2.goOnline(new Location(19.0800, 72.8800));
        driverRepo.save(d1);
        driverRepo.save(d2);

        // Choose strategies (Dependency Injection)
        DriverMatchingStrategy matching = new RatingWeightedStrategy();
        FareStrategy fare = new StandardFareStrategy();
        SurgePricingStrategy surge = new DemandSupplySurgeStrategy(rideRepo, driverRepo);

        RideService rideService = new RideService(matching, fare, surge, driverRepo, rideRepo);
        rideService.addObserver(new NotificationObserver());
        rideService.addObserver(new AnalyticsObserver());

        // ── Ride Flow ────────────────────────────────────────────────────
        Location pickup = new Location(19.0750, 72.8770);
        Location dropoff = new Location(19.1000, 72.9000);

        // 1. Request ride
        Ride ride = rideService.requestRide("rider-1", pickup, dropoff, VehicleType.ECONOMY);
        System.out.println("Ride requested: " + ride.getRideId());
        System.out.println("Status: " + ride.getStatus());           // MATCHED
        System.out.println("Estimated fare: ₹" + ride.getEstimatedFare());
        System.out.println("Surge: " + ride.getSurgeMultiplier() + "x");

        // 2. Driver arrives
        rideService.driverArrived(ride.getRideId());

        // 3. Start ride
        rideService.startRide(ride.getRideId());

        // 4. Complete ride
        Ride completed = rideService.completeRide(ride.getRideId(), 5.2, 18);
        System.out.println("Actual fare: ₹" + completed.getActualFare());
    }
}
```

---

## Edge Cases & Tests

```java
// 1. No drivers available — ride stays in REQUESTED status
@Test
void requestRide_noDrivers_staysRequested() {
    Ride ride = rideService.requestRide("rider-1", pickup, dropoff, VehicleType.PREMIUM);
    assertEquals(RideStatus.REQUESTED, ride.getStatus());
    assertNull(ride.getDriverId());
}

// 2. Cannot assign same driver to two rides
@Test
void assignDriver_alreadyOnRide_throwsException() {
    driver1.assignRide("ride-1");
    assertThrows(DriverNotAvailableException.class, () -> driver1.assignRide("ride-2"));
}

// 3. Cannot complete a cancelled ride (invalid state transition)
@Test
void completeRide_afterCancel_throwsException() {
    Ride ride = rideService.requestRide("rider-1", pickup, dropoff, VehicleType.ECONOMY);
    rideService.cancelRide(ride.getRideId(), "RIDER");
    assertThrows(InvalidRideStateException.class, () ->
        rideService.completeRide(ride.getRideId(), 5.0, 15));
}

// 4. Surge pricing applies during high demand
@Test
void surge_highDemand_multiplierAboveOne() {
    // Create 50 ride requests, only 10 drivers available
    for (int i = 0; i < 50; i++) {
        rideService.requestRide("rider-" + i, pickup, dropoff, VehicleType.ECONOMY);
    }
    BigDecimal surge = surgeStrategy.calculateSurgeMultiplier(pickup, VehicleType.ECONOMY);
    assertTrue(surge.compareTo(BigDecimal.ONE) > 0);
}

// 5. Minimum fare is enforced for very short rides
@Test
void fare_veryShortRide_minimumFareApplied() {
    BigDecimal fare = fareStrategy.calculateFare(0.5, 2, VehicleType.ECONOMY, BigDecimal.ONE);
    assertTrue(fare.compareTo(new BigDecimal("30.00")) >= 0);
}

// 6. Driver goes offline while on ride — should throw
@Test
void goOffline_duringRide_throwsException() {
    driver1.assignRide("ride-1");
    assertThrows(IllegalStateException.class, () -> driver1.goOffline());
}

// 7. Cancel frees up the driver
@Test
void cancelRide_freesDriver() {
    Ride ride = rideService.requestRide("rider-1", pickup, dropoff, VehicleType.ECONOMY);
    assertFalse(driver1.isAvailable()); // On a ride
    rideService.cancelRide(ride.getRideId(), "RIDER");
    assertTrue(driver1.isAvailable());   // Free again
}
```

---

## Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  DESIGN PATTERNS                                                │
│                                                                 │
│  Strategy  → DriverMatchingStrategy (Nearest vs RatingWeighted)│
│           → FareStrategy (Standard, Flat, etc.)                │
│           → SurgePricingStrategy (Demand-Supply, Time-based)   │
│  State    → RideStatus state machine (validated transitions)   │
│  Observer → RideEventObserver (notifications, analytics)       │
│                                                                 │
│  SOLID PRINCIPLES                                               │
│                                                                 │
│  S → RideService orchestrates, MatchingService matches,        │
│      FareService prices. Each does ONE thing.                  │
│  O → New matching algorithm = new class, zero changes          │
│  L → NearestDriver and RatingWeighted are interchangeable      │
│  I → Focused interfaces: 1 method each                         │
│  D → All services depend on interfaces, not implementations    │
│                                                                 │
│  OOP CONCEPTS                                                   │
│                                                                 │
│  Encapsulation  → Driver.assignRide() validates before setting │
│  Value Object   → Location (immutable, equality by value)      │
│  State Machine  → RideStatus.canTransitionTo() enforces rules  │
│  Polymorphism   → Multiple strategies behind same interface    │
└─────────────────────────────────────────────────────────────────┘
```
