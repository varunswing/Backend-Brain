# Coworking Space System - System Design Interview

## Problem Statement
*"Design a coworking space platform like WeWork that handles space booking, member management, access control, and billing for multiple locations."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What spaces do we manage?" → Hot desks, private offices, meeting rooms
- "Do we need real-time availability?" → Yes, live status, booking conflicts prevention
- "Should we support memberships?" → Yes, different plans, credits, subscriptions
- "What about access control?" → Yes, keycard integration, mobile app entry
- "Do we need billing?" → Yes, usage tracking, automated invoicing

**Non-Functional Requirements:**
- "How many locations?" → 1000+ locations globally
- "Expected members?" → 100K+ members across all locations  
- "Daily bookings?" → 50K+ bookings/day during peak
- "Availability?" → 99.9% uptime (24/7 access needed)

### Requirements Summary:
- **Scale**: 1000+ locations, 100K+ members, 50K+ daily bookings
- **Features**: Space booking, membership, access control, billing
- **Real-time**: Live availability, instant booking confirmation

---

## Phase 2: Capacity Estimation (5 minutes)

### Usage Patterns:
```
Total members: 100K
Daily active: 30K
Peak concurrent bookings: 5K
Locations: 1000 spaces globally
Spaces per location: 100 avg
```

### Storage Requirements:
```
Member profiles: 100K × 3KB = 300MB
Bookings: 50K/day × 2KB × 365 = 36GB/year
Access logs: 100K entries/day × 500B × 30 days = 1.5GB
Total: ~50GB/year
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐
│Mobile App   │  │Web Portal   │
│- Book Space │  │- Management │
└─────────────┘  └─────────────┘
       │                │
       └────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│         API Gateway             │
│- Auth - Location routing       │
└─────────────────────────────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Booking   │ │Member    │ │Space     │
│Service   │ │Service   │ │Service   │
│          │ │          │ │          │
│- Reserve │ │- Profile │ │- Inventory│
│- Schedule│ │- Credits │ │- Status  │
└──────────┘ └──────────┘ └──────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Access    │ │Billing   │ │Analytics │
│Control   │ │Service   │ │Service   │
│          │ │          │ │          │
│- Entry   │ │- Usage   │ │- Reports │
│- Security│ │- Invoice │ │- Metrics │
└──────────┘ └──────────┘ └──────────┘
                │
                ▼
┌─────────────────────────────────┐
│          Data Layer             │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │PostgreSQL│ │Redis    │ │InfluxDB│ │
│ │- Members │ │- Cache  │ │- IoT   │ │
│ │- Bookings│ │- Session│ │- Usage │ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
```

### Key Components:
- **Booking Service**: Space reservation, availability, scheduling
- **Access Control**: Entry management, security integration
- **Billing Service**: Usage tracking, automated invoicing

---

## Phase 4: Database Design (8 minutes)

### PostgreSQL Schema:
```sql
-- Locations
CREATE TABLE locations (
    location_id UUID PRIMARY KEY,
    location_name VARCHAR(200),
    address JSONB NOT NULL,
    timezone VARCHAR(50),
    operating_hours JSONB NOT NULL,
    pricing_config JSONB DEFAULT '{}',
    
    location_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW()
);

-- Spaces
CREATE TABLE spaces (
    space_id UUID PRIMARY KEY,
    location_id UUID REFERENCES locations(location_id),
    space_number VARCHAR(50),
    space_type VARCHAR(50) NOT NULL,  -- hot_desk, meeting_room, office
    capacity INTEGER DEFAULT 1,
    hourly_rate DECIMAL(8,2),
    space_status VARCHAR(20) DEFAULT 'available',
    
    UNIQUE(location_id, space_number),
    INDEX idx_spaces_location_type (location_id, space_type)
);

-- Members
CREATE TABLE members (
    member_id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE,
    membership_type VARCHAR(50) DEFAULT 'basic',
    credit_balance INTEGER DEFAULT 0,
    access_card_id VARCHAR(100),
    membership_status VARCHAR(20) DEFAULT 'active',
    
    INDEX idx_members_card (access_card_id)
);

-- Bookings
CREATE TABLE bookings (
    booking_id UUID PRIMARY KEY,
    member_id UUID REFERENCES members(member_id),
    space_id UUID REFERENCES spaces(space_id),
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    booking_status VARCHAR(30) DEFAULT 'confirmed',
    total_amount DECIMAL(10,2),
    checked_in_at TIMESTAMP,
    
    INDEX idx_bookings_space_time (space_id, start_time),
    -- Prevent double booking
    EXCLUDE USING GIST (space_id WITH =, tsrange(start_time, end_time) WITH &&)
);
```

### Redis Caching:
```javascript
// Real-time availability
"space_availability:{location_id}": {
  "hot_desks": {"available": 15, "total": 50},
  "meeting_rooms": {"available": 3, "total": 8},
  "ttl": 300
}

// Booking locks
"booking_lock:{space_id}:{start_time}": {
  "member_id": "uuid",
  "ttl": 300
}
```

---

## Phase 5: Critical Flow - Space Booking (8 minutes)

### Step-by-Step Flow:
```
1. Availability check:
   GET /api/spaces/availability?location=SF&date=2024-06-15
   - Check real-time availability from cache
   - Return available time slots with pricing

2. Booking creation:
   POST /api/bookings
   {"space_id": "uuid", "start_time": "...", "end_time": "..."}

3. Validation:
   - Verify member credits/payment
   - Check space availability with locking
   - Prevent overlapping bookings

4. Confirmation:
   - Create booking record
   - Update availability cache
   - Provision access permissions
   - Send confirmation

5. Access control:
   - Update keycard permissions
   - Send mobile access codes
   - Schedule automatic revocation
```

### Technical Challenges:
**Real-time Availability**: "Distributed caching with conflict resolution"
**Access Integration**: "IoT device communication and security"
**Billing Complexity**: "Usage-based pricing with multiple rates"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Real-time availability** - High-frequency updates
2. **Booking conflicts** - Race conditions during peak
3. **Access control latency** - Physical device integration
4. **Multi-location coordination** - Global member access

### Scaling Solutions:
**Availability**: Redis cluster, eventual consistency
**Conflicts**: Distributed locking, optimistic concurrency  
**Access**: Queue-based IoT updates, local fallbacks
**Global**: Location-based sharding, cached member data

### Trade-offs:
- **Real-time vs Consistency**: Instant updates vs accuracy
- **Security vs Convenience**: Access control vs user experience
- **Flexibility vs Performance**: Custom pricing vs simple rates

---

## Success Metrics:
- **Booking Success**: >98% completion rate
- **Space Utilization**: >80% occupancy during business hours
- **Access Response**: <3 seconds for mobile entry
- **Billing Accuracy**: >99.9% correct usage tracking

**🎯 Demonstrates IoT integration, real-time systems, and physical space management platforms.**