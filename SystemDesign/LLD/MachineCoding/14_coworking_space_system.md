# Coworking Space System - LLD / Machine Coding Interview

---

## 1. Problem Statement

Design a coworking space booking system (similar to WeWork) that allows users to discover, book, and manage workspace reservations across multiple locations. The system must support different space types (desks, private offices, meeting rooms, event spaces), membership tiers with varying pricing, and real-time availability with conflict-free bookings.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement | Priority |
|----|-------------|----------|
| FR1 | Users can register, login, and manage profile | Must |
| FR2 | Users can browse locations and spaces by type, amenities, availability | Must |
| FR3 | Users can book spaces for specific time slots (hourly/daily) | Must |
| FR4 | System prevents double-booking; enforces availability constraints | Must |
| FR5 | Support multiple space types: desk, private office, meeting room, event space | Must |
| FR6 | Membership tiers (Basic, Premium, Enterprise) with different pricing | Must |
| FR7 | Pricing varies by duration (hourly vs daily) and membership tier | Must |
| FR8 | Space allocation strategies: first available, preferred floor, near team | Should |
| FR9 | Post-booking notifications (email, calendar sync, analytics) | Should |
| FR10 | Users can view booking history and cancel/modify bookings | Must |

### Non-Functional Requirements

| ID | Requirement | Target |
|----|-------------|--------|
| NFR1 | **Scalability** | Support 1000+ locations, 100K+ users, 50K+ daily bookings |
| NFR2 | **Availability** | 99.9% uptime (24/7 access-critical) |
| NFR3 | **Latency** | Booking confirmation < 500ms p99 |
| NFR4 | **Consistency** | Strong consistency for booking conflicts; eventual consistency for analytics |
| NFR5 | **Extensibility** | Easy to add new space types, pricing models, allocation strategies |

---

## 3. Database Design with Explanations

```sql
-- =============================================================================
-- LOCATIONS
-- WHY: Physical buildings/sites where coworking spaces exist. Central entity
--      for geographic organization, operating hours, and location-level config.
-- =============================================================================
CREATE TABLE locations (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    address         TEXT NOT NULL,
    city            VARCHAR(100) NOT NULL,
    country         VARCHAR(100) NOT NULL,
    timezone        VARCHAR(50) NOT NULL DEFAULT 'UTC',
    operating_hours JSONB NOT NULL DEFAULT '{}',  -- {"mon":"09:00-18:00", ...}
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- WHY: Filter active locations by city/country for discovery
CREATE INDEX idx_locations_city_country ON locations(city, country);
CREATE INDEX idx_locations_status ON locations(status);


-- =============================================================================
-- SPACE_TYPES
-- WHY: Normalize space categories. Avoids magic strings, enables type-specific
--      config (capacity, default pricing). Extensible for new types.
-- =============================================================================
CREATE TABLE space_types (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(50) UNIQUE NOT NULL,  -- DESK, PRIVATE_OFFICE, MEETING_ROOM, EVENT_SPACE
    name            VARCHAR(100) NOT NULL,
    default_capacity INTEGER NOT NULL DEFAULT 1,
    description     TEXT,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


-- =============================================================================
-- SPACES
-- WHY: Individual bookable units within a location. FK to location + space_type
--      for referential integrity. floor/zone support allocation strategies.
-- =============================================================================
CREATE TABLE spaces (
    id              BIGSERIAL PRIMARY KEY,
    location_id     BIGINT NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    space_type_id   BIGINT NOT NULL REFERENCES space_types(id),
    name            VARCHAR(100) NOT NULL,
    floor           INTEGER,
    zone            VARCHAR(50),                 -- For "NearTeam" / "PreferredFloor"
    capacity        INTEGER NOT NULL DEFAULT 1,
    hourly_rate     DECIMAL(10,2) NOT NULL,
    daily_rate      DECIMAL(10,2),
    status          VARCHAR(20) NOT NULL DEFAULT 'available',
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(location_id, name)
);

-- WHY: Most queries filter by location + type; composite index optimizes discovery
CREATE INDEX idx_spaces_location_type ON spaces(location_id, space_type_id);
CREATE INDEX idx_spaces_location_floor ON spaces(location_id, floor);
CREATE INDEX idx_spaces_status ON spaces(status);


-- =============================================================================
-- USERS
-- WHY: Core identity for authentication and profile. Separate from membership
--      to allow one user to have multiple memberships (e.g., different locations).
-- =============================================================================
CREATE TABLE users (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) UNIQUE NOT NULL,
    name            VARCHAR(200) NOT NULL,
    phone           VARCHAR(50),
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_users_email ON users(email);


-- =============================================================================
-- MEMBERSHIPS
-- WHY: Links user to membership tier and location. Tier drives pricing strategy.
--      credit_balance for pay-as-you-go; subscription_id for recurring.
-- =============================================================================
CREATE TABLE memberships (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    location_id     BIGINT NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    tier            VARCHAR(50) NOT NULL,         -- BASIC, PREMIUM, ENTERPRISE
    credit_balance  INTEGER DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    valid_from      DATE NOT NULL,
    valid_until     DATE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, location_id)
);

-- WHY: Lookup membership by user+location for pricing and access
CREATE INDEX idx_memberships_user_location ON memberships(user_id, location_id);
CREATE INDEX idx_memberships_status ON memberships(status);


-- =============================================================================
-- AMENITIES
-- WHY: Reusable catalog of amenities (WiFi, projector, whiteboard, etc.).
--      Normalized to avoid duplication and enable flexible filtering.
-- =============================================================================
CREATE TABLE amenities (
    id              BIGSERIAL PRIMARY KEY,
    code            VARCHAR(50) UNIQUE NOT NULL,
    name            VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


-- =============================================================================
-- SPACE_AMENITIES (Junction Table)
-- WHY: Many-to-many: spaces can have multiple amenities; amenities apply to
--      many spaces. Enables "filter spaces by amenity" queries.
-- =============================================================================
CREATE TABLE space_amenities (
    space_id        BIGINT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    amenity_id      BIGINT NOT NULL REFERENCES amenities(id) ON DELETE CASCADE,
    PRIMARY KEY (space_id, amenity_id)
);

CREATE INDEX idx_space_amenities_amenity ON space_amenities(amenity_id);


-- =============================================================================
-- BOOKINGS
-- WHY: Core transactional entity. FKs to user, space. Status for lifecycle.
--      EXCLUDE constraint prevents overlapping bookings for same space.
-- =============================================================================
CREATE TABLE bookings (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    space_id        BIGINT NOT NULL REFERENCES spaces(id) ON DELETE CASCADE,
    membership_id   BIGINT REFERENCES memberships(id),
    start_time      TIMESTAMP NOT NULL,
    end_time        TIMESTAMP NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'confirmed',
    total_amount    DECIMAL(10,2),
    pricing_type    VARCHAR(50),                 -- HOURLY, DAILY, MEMBERSHIP
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_end_after_start CHECK (end_time > start_time)
);

-- WHY: Availability queries filter by space + time range
CREATE INDEX idx_bookings_space_time ON bookings(space_id, start_time, end_time);
CREATE INDEX idx_bookings_user ON bookings(user_id);
CREATE INDEX idx_bookings_status ON bookings(status);

-- WHY: Prevent double-booking at DB level (PostgreSQL exclusion)
-- CREATE EXTENSION IF NOT EXISTS btree_gist;
-- ALTER TABLE bookings ADD EXCLUDE USING GIST (
--     space_id WITH =,
--     tsrange(start_time, end_time) WITH &&
-- );
```

---

## 4. Design Patterns

| Pattern | Where Used | Why |
|---------|------------|-----|
| **Strategy** | `PricingStrategy` (HourlyPricing, DailyPricing, MembershipPricing) | Pricing logic varies by duration and membership; Strategy allows swapping algorithms without changing client code. |
| **Strategy** | `SpaceAllocationStrategy` (FirstAvailable, PreferredFloor, NearTeam) | Different allocation preferences; Strategy encapsulates each algorithm and makes adding new ones trivial. |
| **Observer** | `BookingEventObserver` (EmailNotifier, CalendarSyncObserver, AnalyticsObserver) | Post-booking side effects (email, calendar, analytics) are decoupled; new observers can be added without modifying BookingService. |
| **Factory** | `BookingFactory` | Centralizes creation of `Booking` objects with validation and default values; hides construction complexity. |
| **Builder** | `BookingRequest.Builder` | Booking requests have many optional fields; Builder provides fluent API and enforces required fields at build time. |

---

## 5. SOLID Principles

| Principle | Application |
|-----------|-------------|
| **S - Single Responsibility** | `BookingService` orchestrates; `PricingStrategy` only calculates price; `SpaceAllocationStrategy` only selects space; each observer handles one concern. |
| **O - Open/Closed** | New pricing strategies, allocation strategies, and observers can be added without modifying existing code. |
| **L - Liskov Substitution** | Any `PricingStrategy` implementation can replace another; any `SpaceAllocationStrategy` can replace another; observers are interchangeable. |
| **I - Interface Segregation** | `PricingStrategy` has only `calculatePrice()`; `SpaceAllocationStrategy` has only `allocate()`; observers have only `onBookingCreated()`. |
| **D - Dependency Inversion** | `BookingService` depends on `PricingStrategy`, `SpaceAllocationStrategy`, and `BookingEventObserver` abstractions, not concrete implementations. |

---

## 6. Code Implementation in Java

### Enums

```java
package com.coworking.enums;

public enum SpaceType {
    DESK,           // Hot desk, single seat
    PRIVATE_OFFICE, // Enclosed office
    MEETING_ROOM,   // Conference room
    EVENT_SPACE     // Large venue for events
}

public enum BookingStatus {
    PENDING,        // Awaiting payment/confirmation
    CONFIRMED,      // Booked and confirmed
    CANCELLED,      // User cancelled
    COMPLETED,      // Booking ended
    NO_SHOW         // User did not show up
}

public enum MembershipTier {
    BASIC,          // Pay-as-you-go, standard rates
    PREMIUM,        // Discounted rates, priority booking
    ENTERPRISE      // Best rates, dedicated support
}
```

### Models (with encapsulation and state management)

```java
package com.coworking.model;

import com.coworking.enums.BookingStatus;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Immutable value object representing a booking.
 * State changes (e.g., cancel) produce new status via dedicated methods.
 */
public class Booking {
    private final Long id;
    private final Long userId;
    private final Long spaceId;
    private final Long membershipId;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final BookingStatus status;
    private final Double totalAmount;
    private final String pricingType;

    private Booking(Builder b) {
        this.id = b.id;
        this.userId = b.userId;
        this.spaceId = b.spaceId;
        this.membershipId = b.membershipId;
        this.startTime = b.startTime;
        this.endTime = b.endTime;
        this.status = b.status;
        this.totalAmount = b.totalAmount;
        this.pricingType = b.pricingType;
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public Long getSpaceId() { return spaceId; }
    public Long getMembershipId() { return membershipId; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public BookingStatus getStatus() { return status; }
    public Double getTotalAmount() { return totalAmount; }
    public String getPricingType() { return pricingType; }

    /** State transition: cancel produces new status. */
    public Booking withCancelled() {
        return new Builder(this).status(BookingStatus.CANCELLED).build();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Long userId;
        private Long spaceId;
        private Long membershipId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private BookingStatus status = BookingStatus.CONFIRMED;
        private Double totalAmount;
        private String pricingType;

        Builder() {}
        Builder(Booking b) {
            this.id = b.id;
            this.userId = b.userId;
            this.spaceId = b.spaceId;
            this.membershipId = b.membershipId;
            this.startTime = b.startTime;
            this.endTime = b.endTime;
            this.status = b.status;
            this.totalAmount = b.totalAmount;
            this.pricingType = b.pricingType;
        }

        public Builder id(Long id) { this.id = id; return this; }
        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder spaceId(Long spaceId) { this.spaceId = spaceId; return this; }
        public Builder membershipId(Long membershipId) { this.membershipId = membershipId; return this; }
        public Builder startTime(LocalDateTime t) { this.startTime = t; return this; }
        public Builder endTime(LocalDateTime t) { this.endTime = t; return this; }
        public Builder status(BookingStatus s) { this.status = s; return this; }
        public Builder totalAmount(Double a) { this.totalAmount = a; return this; }
        public Builder pricingType(String p) { this.pricingType = p; return this; }
        public Booking build() { return new Booking(this); }
    }
}
```

```java
package com.coworking.model;

import com.coworking.enums.SpaceType;
import java.util.Set;

public class Space {
    private final Long id;
    private final Long locationId;
    private final SpaceType type;
    private final String name;
    private final int floor;
    private final String zone;
    private final int capacity;
    private final double hourlyRate;
    private final Double dailyRate;
    private final Set<Long> amenityIds;

    // Constructor, getters, builder omitted for brevity - same pattern as Booking
}
```

### Strategy: PricingStrategy

```java
package com.coworking.strategy;

import com.coworking.model.BookingRequest;
import com.coworking.model.Space;

/**
 * STRATEGY PATTERN: Encapsulates pricing algorithms.
 * Different strategies (hourly, daily, membership) can be swapped without changing client.
 * SOLID: O - Open/Closed (add new strategies without modifying existing).
 */
public interface PricingStrategy {
    double calculatePrice(BookingRequest request, Space space);
    boolean supports(BookingRequest request);
}
```

```java
package com.coworking.strategy.impl;

import com.coworking.model.BookingRequest;
import com.coworking.model.PricingPreference;
import com.coworking.model.Space;
import com.coworking.strategy.PricingStrategy;
import java.time.Duration;

public class HourlyPricing implements PricingStrategy {
    @Override
    public double calculatePrice(BookingRequest request, Space space) {
        long hours = Duration.between(request.getStartTime(), request.getEndTime()).toHours();
        return Math.max(1, hours) * space.getHourlyRate();
    }

    @Override
    public boolean supports(BookingRequest request) {
        return request.getPricingPreference() == PricingPreference.HOURLY;
    }
}
```

```java
package com.coworking.strategy.impl;

import com.coworking.model.BookingRequest;
import com.coworking.model.PricingPreference;
import com.coworking.model.Space;
import com.coworking.strategy.PricingStrategy;
import java.time.Duration;

public class DailyPricing implements PricingStrategy {
    @Override
    public double calculatePrice(BookingRequest request, Space space) {
        long days = Duration.between(request.getStartTime(), request.getEndTime()).toDays();
        Double dailyRate = space.getDailyRate();
        return Math.max(1, days) * (dailyRate != null ? dailyRate : space.getHourlyRate() * 8);
    }

    @Override
    public boolean supports(BookingRequest request) {
        return request.getPricingPreference() == PricingPreference.DAILY;
    }
}
```

```java
package com.coworking.strategy.impl;

import com.coworking.enums.MembershipTier;
import com.coworking.model.BookingRequest;
import com.coworking.model.Space;
import com.coworking.strategy.PricingStrategy;

public class MembershipPricing implements PricingStrategy {
    private static final double PREMIUM_DISCOUNT = 0.85;
    private static final double ENTERPRISE_DISCOUNT = 0.70;

    private final MembershipTier tier;

    public MembershipPricing(MembershipTier tier) {
        this.tier = tier;
    }

    @Override
    public double calculatePrice(BookingRequest request, Space space) {
        double base = new HourlyPricing().calculatePrice(request, space);
        return switch (tier) {
            case PREMIUM -> base * PREMIUM_DISCOUNT;
            case ENTERPRISE -> base * ENTERPRISE_DISCOUNT;
            default -> base;
        };
    }

    @Override
    public boolean supports(BookingRequest request) {
        return request.getMembershipId() != null;
    }
}
```

### Strategy: SpaceAllocationStrategy

```java
package com.coworking.strategy;

import com.coworking.model.BookingRequest;
import com.coworking.model.Space;
import java.util.List;
import java.util.Optional;

/**
 * STRATEGY PATTERN: Encapsulates space selection algorithms.
 * FirstAvailable, PreferredFloor, NearTeam - each implements different logic.
 * SOLID: D - Dependency Inversion (BookingService depends on abstraction).
 */
public interface SpaceAllocationStrategy {
    Optional<Space> allocate(BookingRequest request, List<Space> availableSpaces);
}
```

```java
package com.coworking.strategy.impl;

import com.coworking.model.BookingRequest;
import com.coworking.model.Space;
import com.coworking.strategy.SpaceAllocationStrategy;
import java.util.List;
import java.util.Optional;

public class FirstAvailableStrategy implements SpaceAllocationStrategy {
    @Override
    public Optional<Space> allocate(BookingRequest request, List<Space> availableSpaces) {
        return availableSpaces.isEmpty() ? Optional.empty() : Optional.of(availableSpaces.get(0));
    }
}
```

```java
package com.coworking.strategy.impl;

import com.coworking.model.BookingRequest;
import com.coworking.model.Space;
import com.coworking.strategy.SpaceAllocationStrategy;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public class PreferredFloorStrategy implements SpaceAllocationStrategy {
    private final int preferredFloor;

    public PreferredFloorStrategy(int preferredFloor) {
        this.preferredFloor = preferredFloor;
    }

    @Override
    public Optional<Space> allocate(BookingRequest request, List<Space> availableSpaces) {
        return availableSpaces.stream()
            .min(Comparator.comparingInt(s -> Math.abs(s.getFloor() - preferredFloor)))
            .map(Optional::of)
            .orElse(Optional.empty());
    }
}
```

```java
package com.coworking.strategy.impl;

import com.coworking.model.BookingRequest;
import com.coworking.model.Space;
import com.coworking.strategy.SpaceAllocationStrategy;
import java.util.List;
import java.util.Optional;

public class NearTeamStrategy implements SpaceAllocationStrategy {
    private final String teamZone;

    public NearTeamStrategy(String teamZone) {
        this.teamZone = teamZone;
    }

    @Override
    public Optional<Space> allocate(BookingRequest request, List<Space> availableSpaces) {
        return availableSpaces.stream()
            .filter(s -> teamZone.equals(s.getZone()))
            .findFirst()
            .or(() -> availableSpaces.stream().findFirst());
    }
}
```

### Observer: BookingEventObserver

```java
package com.coworking.observer;

import com.coworking.model.Booking;

/**
 * OBSERVER PATTERN: Decouples post-booking side effects from core logic.
 * EmailNotifier, CalendarSyncObserver, AnalyticsObserver implement this.
 * SOLID: O - Open/Closed (add observers without modifying BookingService).
 */
public interface BookingEventObserver {
    void onBookingCreated(Booking booking);
    void onBookingCancelled(Booking booking);
}
```

```java
package com.coworking.observer.impl;

import com.coworking.model.Booking;
import com.coworking.observer.BookingEventObserver;

public class EmailNotifier implements BookingEventObserver {
    @Override
    public void onBookingCreated(Booking booking) {
        // Send confirmation email
        System.out.println("[Email] Booking confirmed: " + booking.getId());
    }

    @Override
    public void onBookingCancelled(Booking booking) {
        System.out.println("[Email] Booking cancelled: " + booking.getId());
    }
}
```

```java
package com.coworking.observer.impl;

import com.coworking.model.Booking;
import com.coworking.observer.BookingEventObserver;

public class CalendarSyncObserver implements BookingEventObserver {
    @Override
    public void onBookingCreated(Booking booking) {
        // Sync to Google Calendar / Outlook
        System.out.println("[Calendar] Event added: " + booking.getStartTime());
    }

    @Override
    public void onBookingCancelled(Booking booking) {
        System.out.println("[Calendar] Event removed");
    }
}
```

```java
package com.coworking.observer.impl;

import com.coworking.model.Booking;
import com.coworking.observer.BookingEventObserver;

public class AnalyticsObserver implements BookingEventObserver {
    @Override
    public void onBookingCreated(Booking booking) {
        // Track metrics: utilization, revenue, etc.
        System.out.println("[Analytics] Booking recorded: space=" + booking.getSpaceId());
    }

    @Override
    public void onBookingCancelled(Booking booking) {
        System.out.println("[Analytics] Cancellation recorded");
    }
}
```

### Factory: BookingFactory

```java
package com.coworking.factory;

import com.coworking.enums.BookingStatus;
import com.coworking.model.Booking;
import com.coworking.model.BookingRequest;
import com.coworking.strategy.PricingStrategy;

/**
 * FACTORY PATTERN: Centralizes Booking creation with validation and defaults.
 * Hides construction complexity; ensures consistent object creation.
 * SOLID: S - Single Responsibility (only creates valid Bookings).
 */
public class BookingFactory {
    private final PricingStrategy pricingStrategy;

    public BookingFactory(PricingStrategy pricingStrategy) {
        this.pricingStrategy = pricingStrategy;
    }

    public Booking create(BookingRequest request, Long spaceId, double totalAmount) {
        return Booking.builder()
            .userId(request.getUserId())
            .spaceId(spaceId)
            .membershipId(request.getMembershipId())
            .startTime(request.getStartTime())
            .endTime(request.getEndTime())
            .status(BookingStatus.CONFIRMED)
            .totalAmount(totalAmount)
            .pricingType(request.getPricingPreference().name())
            .build();
    }
}
```

### Builder: BookingRequest.Builder

```java
package com.coworking.model;

import com.coworking.enums.SpaceType;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * BUILDER PATTERN: Fluent API for constructing BookingRequest with many optional fields.
 * Enforces required fields (userId, startTime, endTime) at build() time.
 * Reduces constructor overloads and improves readability.
 */
public class BookingRequest {
    private final Long userId;
    private final Long locationId;
    private final Long membershipId;
    private final LocalDateTime startTime;
    private final LocalDateTime endTime;
    private final SpaceType spaceType;
    private final PricingPreference pricingPreference;
    private final Integer preferredFloor;
    private final String preferredZone;

    private BookingRequest(Builder b) {
        this.userId = Objects.requireNonNull(b.userId);
        this.startTime = Objects.requireNonNull(b.startTime);
        this.endTime = Objects.requireNonNull(b.endTime);
        if (!b.endTime.isAfter(b.startTime)) {
            throw new IllegalArgumentException("endTime must be after startTime");
        }
        this.locationId = b.locationId;
        this.membershipId = b.membershipId;
        this.spaceType = b.spaceType;
        this.pricingPreference = b.pricingPreference != null ? b.pricingPreference : PricingPreference.HOURLY;
        this.preferredFloor = b.preferredFloor;
        this.preferredZone = b.preferredZone;
    }

    public Long getUserId() { return userId; }
    public Long getLocationId() { return locationId; }
    public Long getMembershipId() { return membershipId; }
    public LocalDateTime getStartTime() { return startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public SpaceType getSpaceType() { return spaceType; }
    public PricingPreference getPricingPreference() { return pricingPreference; }
    public Integer getPreferredFloor() { return preferredFloor; }
    public String getPreferredZone() { return preferredZone; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long userId;
        private Long locationId;
        private Long membershipId;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private SpaceType spaceType;
        private PricingPreference pricingPreference;
        private Integer preferredFloor;
        private String preferredZone;

        public Builder userId(Long userId) { this.userId = userId; return this; }
        public Builder locationId(Long locationId) { this.locationId = locationId; return this; }
        public Builder membershipId(Long membershipId) { this.membershipId = membershipId; return this; }
        public Builder startTime(LocalDateTime t) { this.startTime = t; return this; }
        public Builder endTime(LocalDateTime t) { this.endTime = t; return this; }
        public Builder spaceType(SpaceType t) { this.spaceType = t; return this; }
        public Builder pricingPreference(PricingPreference p) { this.pricingPreference = p; return this; }
        public Builder preferredFloor(Integer f) { this.preferredFloor = f; return this; }
        public Builder preferredZone(String z) { this.preferredZone = z; return this; }

        public BookingRequest build() {
            return new BookingRequest(this);
        }
    }
}
```

```java
package com.coworking.model;

public enum PricingPreference {
    HOURLY,
    DAILY
}
```

### BookingService (Orchestrator with DI)

```java
package com.coworking.service;

import com.coworking.enums.SpaceType;
import com.coworking.model.Booking;
import com.coworking.model.BookingRequest;
import com.coworking.model.Space;
import com.coworking.observer.BookingEventObserver;
import com.coworking.factory.BookingFactory;
import com.coworking.strategy.PricingStrategy;
import com.coworking.strategy.SpaceAllocationStrategy;

import java.util.List;

/**
 * ORCHESTRATOR: Coordinates booking flow.
 * SOLID: D - Depends on abstractions (PricingStrategy, SpaceAllocationStrategy, observers).
 * SOLID: S - Single Responsibility (orchestration only; delegates to strategies/factory).
 */
public class BookingService {
    private final SpaceRepository spaceRepository;
    private final BookingRepository bookingRepository;
    private final PricingStrategy pricingStrategy;  // Or PricingStrategySelector for multiple strategies
    private final SpaceAllocationStrategy allocationStrategy;
    private final BookingFactory bookingFactory;
    private final List<BookingEventObserver> observers;

    public BookingService(
            SpaceRepository spaceRepository,
            BookingRepository bookingRepository,
            PricingStrategy pricingStrategy,
            SpaceAllocationStrategy allocationStrategy,
            BookingFactory bookingFactory,
            List<BookingEventObserver> observers) {
        this.spaceRepository = spaceRepository;
        this.bookingRepository = bookingRepository;
        this.pricingStrategy = pricingStrategy;
        this.allocationStrategy = allocationStrategy;
        this.bookingFactory = bookingFactory;
        this.observers = observers;
    }

    public Booking createBooking(BookingRequest request) {
        // 1. Find available spaces
        List<Space> available = spaceRepository.findAvailable(
            request.getLocationId(),
            request.getSpaceType(),
            request.getStartTime(),
            request.getEndTime()
        );

        // 2. Allocate using strategy
        Space space = allocationStrategy.allocate(request, available)
            .orElseThrow(() -> new IllegalStateException("No available space"));

        // 3. Calculate price using strategy
        double amount = pricingStrategy.calculatePrice(request, space);

        // 4. Create booking via factory
        Booking booking = bookingFactory.create(request, space.getId(), amount);
        booking = bookingRepository.save(booking);

        // 5. Notify observers (Observer pattern)
        observers.forEach(o -> o.onBookingCreated(booking));

        return booking;
    }

    public void cancelBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
            .orElseThrow(() -> new IllegalArgumentException("Booking not found"));
        Booking cancelled = booking.withCancelled();
        bookingRepository.save(cancelled);
        observers.forEach(o -> o.onBookingCancelled(cancelled));
    }
}

// Placeholder interfaces for repository layer
interface SpaceRepository {
    List<Space> findAvailable(Long locationId, SpaceType type, java.time.LocalDateTime start, java.time.LocalDateTime end);
}
interface BookingRepository {
    Booking save(Booking b);
    java.util.Optional<Booking> findById(Long id);
}
```

---

## 7. Edge Cases & Tests

| # | Edge Case | Test Scenario | Expected Behavior |
|---|-----------|---------------|-------------------|
| 1 | **Overlapping bookings** | User A books space 1 for 10:00–12:00; User B attempts 11:00–13:00 for same space | Reject User B's booking; return "Space not available" |
| 2 | **Zero availability** | Request booking when no spaces match (type + time + location) | Throw/return "No available space" |
| 3 | **Invalid time range** | `endTime` before or equal to `startTime` | Builder throws `IllegalArgumentException` |
| 4 | **Membership pricing without membership** | Request `MembershipPricing` but `membershipId` is null | Fall back to `HourlyPricing` or reject |
| 5 | **Preferred floor/zone with no match** | `PreferredFloorStrategy` with floor 5, but only floors 1–3 have availability | Return nearest available (e.g., floor 3) or first available |
| 6 | **Concurrent booking race** | Two users book same space for same slot simultaneously | One succeeds; other gets conflict (use DB constraint or optimistic lock) |
| 7 | **Observer failure** | Email observer throws during `onBookingCreated` | Booking still persisted; log error; consider async observers |
| 8 | **Past booking** | `startTime` in the past | Reject with "Cannot book in the past" |

### Sample Test (JUnit)

```java
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BookingServiceTest {
    private BookingService bookingService;  // Injected in @BeforeEach

    @Test
    void shouldRejectOverlappingBookings() {
        BookingRequest req1 = BookingRequest.builder()
            .userId(1L).locationId(1L).startTime(LocalDateTime.now().plusHours(1))
            .endTime(LocalDateTime.now().plusHours(3)).build();
        bookingService.createBooking(req1);

        BookingRequest req2 = BookingRequest.builder()
            .userId(2L).locationId(1L).startTime(LocalDateTime.now().plusHours(2))
            .endTime(LocalDateTime.now().plusHours(4)).build();

        assertThrows(IllegalStateException.class, () -> bookingService.createBooking(req2));
    }

    @Test
    void shouldRejectInvalidTimeRange() {
        assertThrows(IllegalArgumentException.class, () ->
            BookingRequest.builder()
                .userId(1L).startTime(LocalDateTime.now())
                .endTime(LocalDateTime.now().minusHours(1))
                .build()
        );
    }
}
```

---

## 8. Summary

| Aspect | Summary |
|--------|---------|
| **Domain** | Coworking space booking with locations, spaces, users, memberships, amenities |
| **Core Flow** | Request → Allocate space (Strategy) → Calculate price (Strategy) → Create booking (Factory) → Notify observers |
| **Patterns** | Strategy (pricing, allocation), Observer (notifications), Factory (booking creation), Builder (request construction) |
| **SOLID** | SRP per class; OCP via strategies/observers; LSP for implementations; ISP for small interfaces; DIP for DI |
| **DB** | Normalized schema with locations, space_types, spaces, users, memberships, amenities, space_amenities, bookings |
| **Edge Cases** | Overlaps, no availability, invalid times, concurrency, observer failures |

---

*Use this document for LLD/Machine Coding interviews focused on coworking/booking systems. Emphasize Strategy + Observer + Factory + Builder and SOLID when discussing design.*
