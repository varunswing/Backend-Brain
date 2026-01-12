# Ride Sharing System (Uber/Ola) - System Design Interview

## Problem Statement
*"Design a ride-sharing platform like Uber that connects riders with drivers, handles real-time location tracking, and processes millions of rides daily."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What core features do we need?" → Ride booking, driver matching, real-time tracking, payments
- "Should we support different vehicle types?" → Yes, economy, premium, motorcycle
- "Do we need real-time location tracking?" → Yes, GPS updates every 5 seconds
- "What about dynamic pricing?" → Yes, surge pricing based on demand/supply
- "Should we support ride sharing/pooling?" → Yes, multiple passengers per ride

**Non-Functional Requirements:**
- "What's our scale?" → 100M users, 10M daily rides, 1M concurrent users
- "Expected latency?" → <100ms for ride matching, <1s location updates
- "Availability needs?" → 99.99% uptime (safety critical)
- "Geographic coverage?" → Global platform, multi-city deployment

### Requirements Summary:
- **Scale**: 100M users, 10M daily rides, 700K location updates/second
- **Features**: Booking, matching, tracking, payments, ratings
- **Performance**: <100ms matching, real-time location updates
- **Global**: Multi-region with local regulations

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Daily rides: 10M
Peak concurrent rides: 1.25M (3x normal load)
Active drivers: 1M concurrent
Location updates: 700K/second (drivers + active rides)
Peak API requests: 50K RPS
```

### Storage Estimation:
```
User profiles: 100M × 2KB = 200GB
Ride history: 10M/day × 2KB × 365 × 3 = 22TB
Location data: 700K updates/sec × 100B × 30 days = 1.8TB/month
Total: ~25TB with replicas and indexes
```

### Memory Requirements:
```
Driver location cache: 1M × 200B = 200MB
Active rides: 1.25M × 1KB = 1.25GB
User sessions: 5M × 1KB = 5GB
Total per region: ~10GB
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐
│Rider Apps   │  │Driver Apps  │
│- Booking    │  │- GPS Track  │
└─────────────┘  └─────────────┘
       │                │
       └────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│         API Gateway             │
│- Auth  - Rate Limit - Routing  │
└─────────────────────────────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Location  │ │Ride      │ │User      │
│Service   │ │Service   │ │Service   │
│          │ │          │ │          │
│- GPS     │ │- Booking │ │- Auth    │
│- Tracking│ │- Matching│ │- Profile │
│- GeoQuery│ │- State   │ │- Ratings │
└──────────┘ └──────────┘ └──────────┘
    │           │           │
    └───────────┼───────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Driver    │ │Pricing   │ │Payment   │
│Service   │ │Service   │ │Service   │
│          │ │          │ │          │
│- Dispatch│ │- Surge   │ │- Process │
│- Verify  │ │- Estimate│ │- Billing │
│- Manage  │ │- Dynamic │ │- Refunds │
└──────────┘ └──────────┘ └──────────┘
                │
                ▼
┌─────────────────────────────────┐
│          Data Layer             │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │PostgreSQL│ │ Redis   │ │Kafka│ │
│ │- Users  │ │- Location│ │-Events│ │
│ │- Rides  │ │- Cache  │ │-Logs│ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
```

### Key Components:
- **Location Service**: GPS tracking, geospatial queries
- **Ride Service**: Booking, matching, state management
- **Driver Service**: Dispatch, verification, availability
- **Pricing Service**: Dynamic pricing, surge calculation

---

## Phase 4: Database Design (8 minutes)

### PostgreSQL Schema:
```sql
-- Users (riders and drivers)
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    phone_number VARCHAR(15) UNIQUE NOT NULL,
    first_name VARCHAR(100),
    user_type VARCHAR(10) DEFAULT 'rider',
    average_rating DECIMAL(3,2) DEFAULT 5.0,
    total_rides INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_users_phone (phone_number)
);

-- Drivers with location
CREATE TABLE drivers (
    driver_id UUID PRIMARY KEY REFERENCES users(user_id),
    license_number VARCHAR(50) UNIQUE NOT NULL,
    vehicle_info JSONB NOT NULL,
    vehicle_type VARCHAR(20) NOT NULL,
    is_online BOOLEAN DEFAULT false,
    current_location POINT,
    current_ride_id UUID,
    
    INDEX idx_drivers_location USING GIST (current_location),
    INDEX idx_drivers_online_type (is_online, vehicle_type)
);

-- Rides
CREATE TABLE rides (
    ride_id UUID PRIMARY KEY,
    rider_id UUID REFERENCES users(user_id),
    driver_id UUID REFERENCES drivers(driver_id),
    ride_status VARCHAR(20) DEFAULT 'requested',
    pickup_location POINT NOT NULL,
    dropoff_location POINT,
    estimated_fare DECIMAL(8,2),
    actual_fare DECIMAL(8,2),
    surge_multiplier DECIMAL(3,2) DEFAULT 1.0,
    requested_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    
    INDEX idx_rides_rider (rider_id, requested_at DESC),
    INDEX idx_rides_driver (driver_id, requested_at DESC)
);
```

### Redis Cache Strategy:
```javascript
// Driver location (critical for matching)
"driver_location:{driver_id}": {
  "lat": 37.7749, "lng": -122.4194,
  "is_online": true, "vehicle_type": "economy",
  "current_ride_id": null, "ttl": 30
}

// Active rides
"ride_state:{ride_id}": {
  "rider_id": "uuid", "driver_id": "uuid",
  "status": "started", "estimated_fare": 15.50
}

// Geospatial index for driver discovery
"drivers_nearby:{city}:{type}": 
// GEORADIUS drivers_nearby:sf:economy -122.4194 37.7749 5 km
```

---

## Phase 5: Critical Flow - Ride Booking (8 minutes)

### Step-by-Step Flow:
```
1. User requests ride:
   POST /api/rides {pickup_location, dropoff_location, vehicle_type}

2. Fare estimation:
   - Calculate distance and time
   - Apply surge multiplier based on demand
   - Return estimated fare

3. Driver matching:
   - Query Redis geospatial: nearby drivers within 5km
   - Filter: online, available, correct vehicle type
   - Rank by: distance (30%), rating (20%), acceptance rate (50%)
   - Select top 3 candidates

4. Driver dispatch:
   - Send request to best driver (15 second timeout)
   - If declined/timeout, try next driver
   - If accepted: update ride status, notify rider

5. Real-time tracking:
   - Driver location updates every 5 seconds
   - Calculate and broadcast ETA updates
   - Handle pickup/dropoff events
```

### Technical Challenges:
**Geospatial Performance**: "Redis geospatial commands for <1s driver discovery"
**Real-time Updates**: "WebSocket for live location streaming"
**Race Conditions**: "Optimistic locking for driver assignment"
**Matching Algorithm**: "ML-based ETA prediction and demand forecasting"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Location updates** - 700K GPS updates/second
2. **Driver matching** - Geospatial queries during peak
3. **Database writes** - High volume ride data

### Scaling Solutions:
**Geographic Distribution:**
- Regional clusters per city/region
- Location-based request routing
- Local regulation compliance

**Performance Optimization:**
- Dedicated location service clusters
- Read replicas for ride history
- Multi-level caching for hot data
- Batch location updates

**Real-time Infrastructure:**
- Horizontal WebSocket scaling
- Kafka for event streaming
- Dedicated push notification service

### Trade-offs:
- **Accuracy vs Latency**: Perfect location vs real-time updates
- **Matching Quality vs Speed**: Best driver vs fast assignment
- **Consistency vs Availability**: Strong consistency vs partition tolerance

---

## Success Metrics:
- **Matching Success**: >95% requests matched with drivers
- **ETA Accuracy**: <3 minutes difference actual vs estimated  
- **API Latency**: <100ms ride booking response
- **Driver Utilization**: >70% time with passengers
- **Customer Satisfaction**: >4.5 average rating

**🎯 Demonstrates real-time systems, geospatial algorithms, and location-based platforms at massive scale.**