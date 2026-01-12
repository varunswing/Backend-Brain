# Ticket Booking System - System Design Interview

## Problem Statement
*"Design a ticket booking system like BookMyShow or Ticketmaster that handles high-concurrency booking for movies, concerts, sports events with no double bookings."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What types of events do we support?" → Movies, concerts, sports, theater, flights
- "Do we need seat selection?" → Yes, interactive seat maps with real-time availability
- "Should we support different pricing tiers?" → Yes, premium, regular, balcony seats
- "Do we need booking holds?" → Yes, hold seats for 10 minutes during checkout
- "What about cancellations?" → Yes, with refund policies based on timing
- "Should we handle waitlists?" → Yes, for sold-out events

**Non-Functional Requirements:**
- "What's our peak concurrency?" → 100K+ users booking simultaneously (concert releases)
- "How many events/venues?" → 10K+ venues, 100K+ events annually
- "Expected latency?" → <200ms for seat selection, <500ms for booking confirmation
- "Consistency requirements?" → Strong consistency - zero double bookings allowed
- "Availability needs?" → 99.99% uptime (revenue critical)

### Requirements Summary:
- **Scale**: 100K concurrent users, 10K venues, 1M bookings/day
- **Events**: Movies, concerts, sports with seat selection
- **Concurrency**: Prevent double booking with high traffic
- **Features**: Hold/release, dynamic pricing, waitlists, QR tickets
- **Performance**: <200ms seat selection, <500ms booking

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Peak concurrent users: 100K (major concert release)
Daily bookings: 1M bookings
Average booking session: 5 minutes
Seat hold duration: 10 minutes
Peak booking requests: 100K users × 10 seats = 1M seat operations
Peak RPS: 1M operations ÷ 300 seconds = ~3K RPS
```

### Storage Estimation:
```
Venues: 10K venues × 10KB = 100MB
Events: 100K events × 5KB = 500MB
Seats: 10K venues × 1K seats × 200B = 2GB
Bookings: 1M bookings/day × 2KB × 365 days = 730GB/year
User data: 50M users × 1KB = 50GB
Total: ~800GB/year with indexes
```

### Memory Requirements:
```
Active seat locks: 100K concurrent × 10 seats × 100B = 100MB
Session data: 100K sessions × 1KB = 100MB
Event cache: Hot events × 1MB = 1GB
Seat availability cache: Popular venues × 100KB = 1GB
Total memory: ~3GB active cache
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Mobile Apps  │  │Web Client   │  │Admin Portal │
│- Booking    │  │- Seat Map   │  │- Event Mgmt │
│- Payments   │  │- Checkout   │  │- Analytics  │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            Load Balancer                │
│- Session affinity  - Auto-scaling      │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│             API Gateway                 │
│- Authentication  - Rate limiting        │
│- Circuit breaker - Request routing     │
└─────────────────────────────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Event       │ │Booking     │ │User        │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Catalog   │ │- Seat Lock │ │- Auth      │
│- Search    │ │- Reserve   │ │- Profile   │
│- Pricing   │ │- Confirm   │ │- History   │
└────────────┘ └────────────┘ └────────────┘
    │                   │                   │
    └───────────────────┼───────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Inventory   │ │Payment     │ │Notification│
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Seat Mgmt │ │- Process   │ │- Confirm   │
│- Availability│- Refund    │ │- Remind    │
│- Locking   │ │- Billing   │ │- Queue     │
└────────────┘ └────────────┘ └────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│              Data Layer                 │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │PostgreSQL│  │  Redis  │  │  Kafka  │   │
│ │         │  │         │  │         │   │
│ │- Events │  │- Locks  │  │- Events │   │
│ │- Bookings│  │- Cache  │  │- Logs   │   │
│ │- Users  │  │- Session│  │- Metrics│   │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
```

### Key Components:
- **Inventory Service**: Seat management, real-time availability, locking
- **Booking Service**: Reservation logic, holds, confirmations
- **Event Service**: Event catalog, pricing, search functionality
- **Payment Service**: Secure payment processing, refunds
- **Queue Service**: Fair queuing for high-demand events

---

## Phase 4: Database Design (8 minutes)

### Core Entities:
- Venues, Events, Seats, Bookings, Users, Payments

### PostgreSQL Schema:
```sql
-- Venues and theaters
CREATE TABLE venues (
    venue_id UUID PRIMARY KEY,
    venue_name VARCHAR(200) NOT NULL,
    address JSONB NOT NULL,
    capacity INTEGER NOT NULL,
    venue_type VARCHAR(50),           -- theater, stadium, cinema
    seating_layout JSONB,             -- seat map configuration
    
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_venues_location ((address->>'city'))
);

-- Events (movies, concerts, sports)
CREATE TABLE events (
    event_id UUID PRIMARY KEY,
    venue_id UUID REFERENCES venues(venue_id),
    event_name VARCHAR(300) NOT NULL,
    event_type VARCHAR(50) NOT NULL,  -- movie, concert, sports
    event_date TIMESTAMP NOT NULL,
    
    -- Pricing tiers
    pricing_config JSONB NOT NULL,    -- {"premium": 100, "regular": 50, "balcony": 25}
    
    -- Event details
    description TEXT,
    duration_minutes INTEGER,
    genre VARCHAR(100),
    rating VARCHAR(10),
    
    -- Booking configuration
    booking_opens_at TIMESTAMP,
    booking_closes_at TIMESTAMP,
    cancellation_allowed BOOLEAN DEFAULT true,
    max_tickets_per_user INTEGER DEFAULT 10,
    
    -- Status
    event_status VARCHAR(20) DEFAULT 'upcoming',
    total_seats INTEGER,
    available_seats INTEGER,
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_events_venue_date (venue_id, event_date),
    INDEX idx_events_type_date (event_type, event_date),
    INDEX idx_events_booking_window (booking_opens_at, booking_closes_at)
);

-- Individual seats for each event
CREATE TABLE seats (
    seat_id UUID PRIMARY KEY,
    event_id UUID REFERENCES events(event_id),
    venue_id UUID REFERENCES venues(venue_id),
    
    -- Seat identification
    section VARCHAR(10),              -- A, B, C, or Left, Right, Center
    row_number VARCHAR(10),           -- 1, 2, 3... or A, B, C...
    seat_number VARCHAR(10),          -- 1, 2, 3...
    
    -- Seat properties
    seat_type VARCHAR(20) DEFAULT 'regular', -- premium, regular, handicapped
    base_price DECIMAL(8,2) NOT NULL,
    
    -- Booking status
    seat_status VARCHAR(20) DEFAULT 'available', -- available, locked, booked, blocked
    locked_by UUID,                   -- user_id who locked the seat
    locked_until TIMESTAMP,           -- lock expiration time
    
    -- Current booking
    booking_id UUID,
    booked_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    -- Unique constraint to prevent double booking
    UNIQUE(event_id, section, row_number, seat_number),
    
    INDEX idx_seats_event_status (event_id, seat_status),
    INDEX idx_seats_locked_until (locked_until),
    INDEX idx_seats_booking (booking_id)
);

-- Bookings and reservations
CREATE TABLE bookings (
    booking_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    event_id UUID REFERENCES events(event_id),
    
    -- Booking details
    booking_status VARCHAR(20) DEFAULT 'pending', -- pending, confirmed, cancelled, expired
    total_seats INTEGER NOT NULL,
    total_amount DECIMAL(10,2) NOT NULL,
    
    -- Payment information
    payment_method VARCHAR(50),
    payment_status VARCHAR(20) DEFAULT 'pending',
    payment_reference VARCHAR(100),
    
    -- Timing
    booking_expires_at TIMESTAMP,     -- When the booking hold expires
    confirmed_at TIMESTAMP,
    cancelled_at TIMESTAMP,
    
    -- Additional info
    special_requests TEXT,
    promo_code VARCHAR(50),
    discount_amount DECIMAL(8,2) DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_bookings_user_date (user_id, created_at DESC),
    INDEX idx_bookings_event (event_id),
    INDEX idx_bookings_status_expires (booking_status, booking_expires_at)
);

-- Booking line items (individual seats)
CREATE TABLE booking_seats (
    booking_seat_id UUID PRIMARY KEY,
    booking_id UUID REFERENCES bookings(booking_id) ON DELETE CASCADE,
    seat_id UUID REFERENCES seats(seat_id),
    
    seat_price DECIMAL(8,2) NOT NULL,
    
    INDEX idx_booking_seats_booking (booking_id),
    INDEX idx_booking_seats_seat (seat_id)
);
```

### Redis Cache Strategy:
```javascript
// Seat locks (critical for preventing double booking)
"seat_lock:{seat_id}": {
  "user_id": "uuid",
  "locked_at": 1704110400,
  "expires_at": 1704111000,      // 10 minutes from lock
  "booking_session": "session_123",
  "ttl": 600                     // 10 minutes
}

// Event availability cache
"event_availability:{event_id}": {
  "total_seats": 1000,
  "available_seats": 247,
  "pricing": {"premium": 100, "regular": 50},
  "booking_opens": 1704110400,
  "last_updated": 1704110450,
  "ttl": 300                     // 5 minutes
}

// User booking session
"booking_session:{session_id}": {
  "user_id": "uuid",
  "event_id": "uuid", 
  "locked_seats": ["seat_uuid_1", "seat_uuid_2"],
  "session_expires": 1704111000,
  "ttl": 1800                    // 30 minutes
}

// Queue for high-demand events
"event_queue:{event_id}": {
  "queue_length": 5000,
  "estimated_wait": 900,         // 15 minutes
  "active_users": 200
}
```

### Design Decisions:
- **Optimistic locking**: Prevent race conditions in seat booking
- **Time-based locks**: Automatic seat release after timeout
- **Denormalized pricing**: Cache pricing in events for performance
- **Queue management**: Handle traffic spikes fairly

---

## Phase 5: Critical Flow - Seat Booking (8 minutes)

### Most Critical Flow: User Books Tickets

**1. Seat Selection**
```
User selects seats on seat map:
POST /api/events/{event_id}/seats/lock
{
  "seat_ids": ["seat_1", "seat_2", "seat_3"],
  "session_id": "session_123"
}
```

**2. Seat Locking (Critical Section)**
```
Inventory Service (atomic operation):
1. Check seat availability in database
2. Acquire distributed lock using Redis
3. For each seat:
   - Verify seat is still available
   - Set seat status to 'locked'
   - Set locked_by = user_id
   - Set locked_until = now() + 10 minutes
4. Create Redis lock entries for each seat
5. Return success with lock expiration time
```

**3. Price Calculation & Hold Creation**
```
Booking Service:
1. Calculate total price with current pricing
2. Apply any discounts/promo codes
3. Create booking record with status 'pending'
4. Link seats to booking in booking_seats table
5. Set booking expiration to 10 minutes
6. Return booking details to user
```

**4. Payment Processing**
```
User proceeds to payment:
1. Validate booking is still valid (not expired)
2. Process payment through Payment Service
3. If payment successful:
   - Update booking status to 'confirmed'
   - Update seat status to 'booked'
   - Clear Redis locks
   - Generate QR codes for tickets
4. If payment fails:
   - Keep booking in 'pending' state
   - Allow retry within time limit
```

**5. Booking Confirmation/Expiration**
```
Successful booking:
1. Send confirmation email/SMS with tickets
2. Update event available_seats count
3. Log successful booking event
4. Clear user session data

Expired booking (background job):
1. Find bookings with booking_expires_at < now()
2. Release locked seats (set status back to 'available')
3. Update event availability count
4. Cancel booking and notify user
```

### Technical Challenges:

**Race Condition Prevention:**
- "Use database transactions with optimistic locking"
- "Redis distributed locks for seat reservation"
- "Atomic compare-and-swap operations"

**High Concurrency Handling:**
- "Connection pooling for database efficiency"  
- "Queue system for traffic spikes (virtual waiting room)"
- "Rate limiting per user to prevent abuse"

**Consistency Guarantees:**
- "ACID transactions for seat booking"
- "Compensation logic for payment failures"
- "Background jobs for timeout cleanup"

**Performance Optimization:**
- "Cache hot event data in Redis"
- "Database read replicas for seat availability"
- "CDN for seat maps and static assets"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Database contention**: Multiple users booking same seats
2. **Payment processing**: External payment gateway latency
3. **Session management**: High memory usage during peak
4. **Lock cleanup**: Expired locks and timeout handling

### Scaling Solutions:

**Database Scaling:**
```
- Read replicas: Separate read/write for availability queries
- Database sharding: Partition by event_id or venue_id
- Connection pooling: Manage database connections efficiently
- Optimistic locking: Reduce lock contention
```

**Cache Optimization:**
```
- Multi-level caching: L1 application, L2 Redis, L3 database
- Seat map caching: Cache seat layouts for faster rendering
- Event data caching: Hot events cached with short TTL
- Lock distribution: Separate Redis clusters for locks vs cache
```

**Traffic Management:**
```
- Virtual queue: Queue users during high-demand events
- Rate limiting: Prevent users from spamming booking API
- Auto-scaling: Scale booking service instances during peak
- Circuit breakers: Fail gracefully when dependencies down
```

**Performance Improvements:**
```
- Async processing: Background jobs for non-critical operations
- CDN integration: Cache static assets and seat maps
- Database indexing: Optimize for booking query patterns
- Batch operations: Bulk seat availability updates
```

### Trade-offs:
- **Consistency vs Availability**: Strong consistency for bookings vs high availability
- **Lock Duration vs UX**: Longer locks = better UX but less availability
- **Caching vs Real-time**: Cache improves performance but may show stale data

---

## Advanced Features:

**Queue Management:**
- Virtual waiting room for high-demand events
- Fair queuing algorithms (FIFO with randomization)
- Real-time queue position updates

**Dynamic Pricing:**
- Demand-based pricing adjustments
- Early bird and last-minute discounts
- Machine learning for price optimization

**Fraud Prevention:**
- Bot detection and CAPTCHA
- Payment fraud detection
- Account verification for high-value events

---

## Success Metrics:
- **Booking Success Rate**: >95% successful bookings
- **Zero Double Bookings**: 100% consistency in seat allocation
- **Response Time**: <200ms for seat selection, <500ms booking
- **Payment Success**: >98% payment completion rate
- **System Uptime**: 99.99% availability during peak events

**🎯 This design demonstrates high-concurrency systems, distributed locking, transaction management, and building systems that handle flash sales and traffic spikes.**




