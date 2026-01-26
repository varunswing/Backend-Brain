# Uber - High Level Design

## Table of Contents
1. [System Requirements](#system-requirements)
2. [Capacity Estimation](#capacity-estimation)
3. [High-Level Design](#high-level-design)
4. [Request Flows](#request-flows)
5. [Detailed Component Design](#detailed-component-design)
6. [Trade-offs and Tech Choices](#trade-offs-and-tech-choices)
7. [Failure Scenarios and Bottlenecks](#failure-scenarios-and-bottlenecks)
8. [Future Improvements](#future-improvements)
9. [Interviewer Questions & Answers](#interviewer-questions--answers)

---

## System Requirements

### Functional Requirements

1. **Rider Features**
   - Request a ride (pickup and destination)
   - View nearby drivers
   - Track driver location in real-time
   - View fare estimate before booking
   - Rate driver after ride
   - Payment processing

2. **Driver Features**
   - Accept/reject ride requests
   - Navigate to pickup and destination
   - View ride history and earnings
   - Update availability status
   - Rate riders

3. **Trip Management**
   - Match riders with nearest available drivers
   - Real-time trip tracking
   - Dynamic pricing (surge)
   - ETA calculation
   - Trip history

4. **Support Features**
   - In-app messaging
   - Emergency assistance (SOS)
   - Lost item recovery
   - Dispute resolution

### Non-Functional Requirements

1. **Scale**: 20M+ rides/day, 5M drivers, 100M riders
2. **Availability**: 99.99% uptime
3. **Latency**: Ride matching < 30 seconds
4. **Real-time**: Location updates every 4 seconds
5. **Accuracy**: ETA within 20% accuracy
6. **Consistency**: Strong consistency for payments

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Active Riders: 100 Million
- Active Drivers: 5 Million
- Daily Active Riders: 20 Million
- Concurrent drivers: 2 Million

Rides:
- Rides per day: 20 Million
- Rides per second: 20M / 86400 ≈ 230 rides/sec
- Peak: 5x average = 1,150 rides/sec

Location Updates:
- 2M drivers sending location every 4 seconds
- Updates per second: 2M / 4 = 500,000 updates/sec
- During ride: Riders also send updates
- Total: ~1 Million location updates/sec
```

### Storage Estimates

```
Location Data:
- Each update: 50 bytes (driver_id, lat, lng, timestamp)
- Updates per day: 1M/sec * 86400 = 86 Billion
- Storage per day: 86B * 50 bytes = 4.3 TB/day
- Retention: 30 days = 129 TB

Trip Data:
- Each trip: 2 KB (all metadata)
- Trips per day: 20 Million
- Storage per day: 20M * 2 KB = 40 GB/day
- Retention: 7 years = 100 TB

User Data:
- Riders: 100M * 1 KB = 100 GB
- Drivers: 5M * 5 KB = 25 GB
- Total user data: ~125 GB
```

### Bandwidth Estimates

```
Location Updates:
- Incoming: 1M/sec * 50 bytes = 50 MB/sec
- Outgoing (to riders tracking): 500K/sec * 50 bytes = 25 MB/sec

API Requests:
- Ride requests: 230/sec * 1 KB = 230 KB/sec
- Browse/search: 1000/sec * 2 KB = 2 MB/sec

Total: ~100 MB/sec sustained, peaks to 500 MB/sec
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                            │
│                           (Rider App, Driver App)                                   │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         │ HTTPS / WebSocket
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              LOAD BALANCER (L7)                                      │
│                            (AWS ALB / Nginx)                                         │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                    ┌────────────────────┴────────────────────┐
                    │                                         │
                    ▼                                         ▼
┌───────────────────────────────────────┐    ┌───────────────────────────────────────┐
│           API GATEWAY                  │    │        WEBSOCKET GATEWAY              │
│   (Auth, Rate Limit, Routing)         │    │   (Real-time location streaming)      │
└───────────────────┬───────────────────┘    └───────────────────────────────────────┘
                    │
    ┌───────────────┼───────────────┬───────────────┬───────────────┐
    │               │               │               │               │
    ▼               ▼               ▼               ▼               ▼
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│  Trip   │   │  Match  │   │Location │   │ Pricing │   │ Payment │
│ Service │   │ Service │   │ Service │   │ Service │   │ Service │
└────┬────┘   └────┬────┘   └────┬────┘   └────┬────┘   └────┬────┘
     │             │             │             │             │
     │             │             │             │             │
┌────┴─────────────┴─────────────┴─────────────┴─────────────┴────┐
│                         MESSAGE QUEUE                            │
│                          (Kafka)                                 │
│   ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│   │  Location   │  │   Trip      │  │   Pricing   │             │
│   │   Events    │  │   Events    │  │   Events    │             │
│   └─────────────┘  └─────────────┘  └─────────────┘             │
└─────────────────────────────────────────────────────────────────┘
                              │
         ┌────────────────────┴────────────────────┐
         ▼                                         ▼
┌─────────────────────────────────┐  ┌─────────────────────────────────┐
│      SUPPLY SERVICE             │  │      DEMAND PREDICTION          │
│                                 │  │         SERVICE                 │
│  - Track driver availability    │  │  - Predict ride demand         │
│  - Manage driver status         │  │  - Surge pricing input         │
│  - Driver matching pool         │  │  - Resource allocation         │
└─────────────────────────────────┘  └─────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├───────────────┬───────────────┬───────────────┬───────────────┬─────────────────────┤
│   TRIP DB     │   USER DB     │   LOCATION    │   SEARCH      │   CACHE             │
│  (PostgreSQL) │  (PostgreSQL) │   INDEX       │(Elasticsearch)│   (Redis)           │
│               │               │ (Custom/Redis)│               │                     │
│ - Trip data   │ - Riders      │ - Geospatial  │ - Place search│ - Driver locations  │
│ - History     │ - Drivers     │   index       │ - Address     │ - Session data      │
│ - Payments    │ - Vehicles    │ - Real-time   │   autocomplete│ - Surge prices      │
└───────────────┴───────────────┴───────────────┴───────────────┴─────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                            MAPS & ROUTING SERVICE                                    │
│                                                                                     │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐                     │
│  │  ETA Service    │  │  Route Service  │  │  Geocoding      │                     │
│  │                 │  │                 │  │  Service        │                     │
│  │  ML-based ETA   │  │  Turn-by-turn   │  │  Address ↔ GPS  │                     │
│  │  prediction     │  │  navigation     │  │  conversion     │                     │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘                     │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Core Services

1. **Trip Service**: Manage trip lifecycle
2. **Match Service**: Match riders with drivers
3. **Location Service**: Track and index locations
4. **Pricing Service**: Calculate fares and surge pricing
5. **Payment Service**: Process payments
6. **ETA Service**: Estimate arrival times
7. **Supply Service**: Manage driver availability
8. **Notification Service**: Push notifications

---

## Request Flows

### Ride Request Flow

```
┌────────┐  ┌─────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐  ┌────────┐
│ Rider  │  │ LB  │  │ API GW  │  │   Trip    │  │  Match  │  │Location │  │ Driver │
│  App   │  │     │  │         │  │ Service   │  │ Service │  │ Index   │  │  App   │
└───┬────┘  └──┬──┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘  └───┬────┘
    │          │          │             │             │            │           │
    │ Request  │          │             │             │            │           │
    │ Ride     │          │             │             │            │           │
    │─────────>│          │             │             │            │           │
    │          │─────────>│             │             │            │           │
    │          │          │────────────>│             │            │           │
    │          │          │             │ Create Trip │            │           │
    │          │          │             │──────(DB)   │            │           │
    │          │          │             │             │            │           │
    │          │          │             │ Find Drivers│            │           │
    │          │          │             │────────────>│            │           │
    │          │          │             │             │ Query Nearby│           │
    │          │          │             │             │───────────>│           │
    │          │          │             │             │            │           │
    │          │          │             │             │<───────────│           │
    │          │          │             │             │ Drivers    │           │
    │          │          │             │             │            │           │
    │          │          │             │             │ Rank & Select           │
    │          │          │             │             │            │           │
    │          │          │             │<────────────│            │           │
    │          │          │             │ Best Driver │            │           │
    │          │          │             │             │            │           │
    │          │          │             │ Dispatch Request         │           │
    │          │          │             │─────────────────────────────────────>│
    │          │          │             │             │            │           │
    │          │          │             │             │            │ Accept?   │
    │          │          │             │<─────────────────────────────────────│
    │          │          │             │ Driver Accepted          │           │
    │          │          │             │             │            │           │
    │<─────────│<─────────│<────────────│             │            │           │
    │ Match    │          │             │             │            │           │
    │ Details  │          │             │             │            │           │
```

### Driver Location Update Flow

```
┌────────┐  ┌─────────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐  ┌────────┐
│ Driver │  │  WebSocket  │  │ Location  │  │  Redis  │  │Location │  │ Rider  │
│  App   │  │   Gateway   │  │ Service   │  │  Cache  │  │  Index  │  │  App   │
└───┬────┘  └──────┬──────┘  └─────┬─────┘  └────┬────┘  └────┬────┘  └───┬────┘
    │              │               │             │            │           │
    │ Location     │               │             │            │           │
    │ Update       │               │             │            │           │
    │ (every 4s)   │               │             │            │           │
    │─────────────>│               │             │            │           │
    │              │──────────────>│             │            │           │
    │              │               │ Update Cache│            │           │
    │              │               │────────────>│            │           │
    │              │               │             │            │           │
    │              │               │ Update Index│            │           │
    │              │               │───────────────────────>│           │
    │              │               │             │            │           │
    │              │               │ If in active trip:       │           │
    │              │               │ Broadcast to rider       │           │
    │              │               │──────────────────────────────────────>│
    │              │               │             │            │           │
    │              │<──────────────│             │            │           │
    │<─────────────│ ACK           │             │            │           │
```

### ETA Calculation Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│ Client │  │ API GW  │  │  ETA    │  │  Routing  │  │Traffic  │  │   ML    │
│        │  │         │  │ Service │  │  Service  │  │ Data    │  │  Model  │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ Get ETA    │            │             │             │            │
    │───────────>│            │             │            │            │
    │            │───────────>│             │             │            │
    │            │            │ Get Route   │             │            │
    │            │            │────────────>│             │            │
    │            │            │             │ Get Traffic │            │
    │            │            │             │────────────>│            │
    │            │            │             │             │            │
    │            │            │             │<────────────│            │
    │            │            │<────────────│             │            │
    │            │            │ Base ETA    │             │            │
    │            │            │             │             │            │
    │            │            │ ML Adjustment             │            │
    │            │            │───────────────────────────────────────>│
    │            │            │             │             │            │
    │            │            │<───────────────────────────────────────│
    │            │            │ Adjusted ETA│             │            │
    │            │            │             │             │            │
    │<───────────│<───────────│             │             │            │
    │ Final ETA  │            │             │             │            │
```

---

## Detailed Component Design

### 1. Geospatial Indexing (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          GEOSPATIAL INDEXING                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Problem: Find nearby drivers among 2 million active drivers                        │
│                                                                                     │
│  Solution Options:                                                                  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  OPTION 1: GEOHASH                                                          │   │
│  │                                                                              │   │
│  │  - Divide world into grid cells                                             │   │
│  │  - Each cell has unique hash (e.g., "9q8yy")                               │   │
│  │  - Longer hash = smaller area                                               │   │
│  │                                                                              │   │
│  │  Precision:                                                                  │   │
│  │  - 5 chars: ~5km × 5km                                                      │   │
│  │  - 6 chars: ~1.2km × 0.6km                                                  │   │
│  │  - 7 chars: ~150m × 150m                                                    │   │
│  │                                                                              │   │
│  │  Finding nearby:                                                             │   │
│  │  - Get driver's geohash                                                      │   │
│  │  - Query same cell + 8 neighbors                                            │   │
│  │  - Filter by exact distance                                                  │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │  OPTION 2: S2 GEOMETRY (Used by Uber)                                       │   │
│  │                                                                              │   │
│  │  - Projects Earth onto cube faces                                           │   │
│  │  - Each face divided into cells (Hilbert curve)                             │   │
│  │  - Hierarchical: levels 0-30                                                │   │
│  │                                                                              │   │
│  │  Advantages over Geohash:                                                    │   │
│  │  - More uniform cell sizes                                                   │   │
│  │  - Better at poles and edges                                                 │   │
│  │  - Efficient range queries                                                   │   │
│  │                                                                              │   │
│  │  Cell levels:                                                                │   │
│  │  - Level 12: ~6.4km × 6.4km                                                 │   │
│  │  - Level 14: ~1.6km × 1.6km (neighborhood)                                  │   │
│  │  - Level 16: ~400m × 400m (block)                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Implementation (Uber's approach):                                                  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Data Structure: Redis + S2                                                  │   │
│  │                                                                              │   │
│  │  Key: s2_cell_id (level 14)                                                 │   │
│  │  Value: Set of driver_ids                                                   │   │
│  │                                                                              │   │
│  │  "s2:level14:8584029" → {driver_1, driver_2, driver_3}                     │   │
│  │                                                                              │   │
│  │  On location update:                                                         │   │
│  │  1. Calculate new S2 cell                                                   │   │
│  │  2. If changed: remove from old, add to new                                 │   │
│  │  3. Update driver's current cell in metadata                                │   │
│  │                                                                              │   │
│  │  On nearby query:                                                            │   │
│  │  1. Get covering cells for radius (e.g., 5km)                               │   │
│  │  2. Query each cell for drivers                                             │   │
│  │  3. Filter by exact distance and availability                               │   │
│  │  4. Return sorted by distance/ETA                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class LocationService:
    def __init__(self):
        self.redis = Redis()
        self.s2 = S2Library()
        self.CELL_LEVEL = 14  # ~1.6km cells
        
    async def update_driver_location(self, driver_id: str, lat: float, lng: float):
        # Calculate S2 cell
        new_cell = self.s2.get_cell_id(lat, lng, self.CELL_LEVEL)
        
        # Get previous cell
        old_cell = await self.redis.hget(f"driver:{driver_id}", "cell")
        
        # Update if cell changed
        if old_cell != new_cell:
            # Remove from old cell
            if old_cell:
                await self.redis.srem(f"s2:{old_cell}", driver_id)
            
            # Add to new cell
            await self.redis.sadd(f"s2:{new_cell}", driver_id)
            
            # Update driver metadata
            await self.redis.hset(f"driver:{driver_id}", "cell", new_cell)
        
        # Update current location
        await self.redis.hset(f"driver:{driver_id}", mapping={
            "lat": lat,
            "lng": lng,
            "updated_at": time.time()
        })
    
    async def find_nearby_drivers(self, lat: float, lng: float, radius_km: float):
        # Get covering cells for the search radius
        covering_cells = self.s2.get_covering(lat, lng, radius_km, self.CELL_LEVEL)
        
        # Query all cells
        pipeline = self.redis.pipeline()
        for cell in covering_cells:
            pipeline.smembers(f"s2:{cell}")
        
        results = await pipeline.execute()
        
        # Flatten and deduplicate
        driver_ids = set()
        for cell_drivers in results:
            driver_ids.update(cell_drivers)
        
        # Get driver details and filter
        nearby_drivers = []
        for driver_id in driver_ids:
            driver = await self.get_driver_details(driver_id)
            
            # Check availability
            if not driver.is_available:
                continue
            
            # Calculate exact distance
            distance = haversine(lat, lng, driver.lat, driver.lng)
            if distance <= radius_km:
                nearby_drivers.append({
                    "driver_id": driver_id,
                    "distance": distance,
                    "eta_seconds": self.estimate_eta(distance)
                })
        
        # Sort by ETA
        return sorted(nearby_drivers, key=lambda d: d["eta_seconds"])
```

### 2. Matching Algorithm (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          DRIVER-RIDER MATCHING                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Goals:                                                                             │
│  - Minimize rider wait time                                                         │
│  - Maximize driver utilization                                                      │
│  - Fair distribution of rides                                                       │
│  - Account for ride type (UberX, XL, etc.)                                         │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         MATCHING APPROACHES                                  │   │
│  │                                                                              │   │
│  │  Simple Approach: Nearest Driver                                            │   │
│  │  - Find closest available driver                                            │   │
│  │  - Dispatch immediately                                                     │   │
│  │  - Pros: Simple, fast                                                        │   │
│  │  - Cons: Not optimal for overall system                                     │   │
│  │                                                                              │   │
│  │  Better Approach: Batch Matching (Used by Uber)                             │   │
│  │  - Collect requests for 1-2 seconds                                         │   │
│  │  - Match batch of riders to batch of drivers                                │   │
│  │  - Optimize for global efficiency                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Batch Matching Algorithm:                                                          │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  INPUT:                                                                      │   │
│  │  - Set of waiting riders R = {r1, r2, ..., rn}                              │   │
│  │  - Set of available drivers D = {d1, d2, ..., dm}                           │   │
│  │                                                                              │   │
│  │  COST FUNCTION:                                                              │   │
│  │  cost(r, d) = w1 * pickup_eta +                                             │   │
│  │               w2 * driver_wait_time +                                        │   │
│  │               w3 * (1 / driver_rating) +                                    │   │
│  │               w4 * detour_factor                                             │   │
│  │                                                                              │   │
│  │  ALGORITHM: Hungarian Algorithm (Optimal Assignment)                        │   │
│  │  - Build cost matrix C[n][m]                                                │   │
│  │  - Find minimum cost matching                                               │   │
│  │  - O(n³) complexity                                                         │   │
│  │                                                                              │   │
│  │  For large scale: Greedy approximation + local search                       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Dispatch Priority:                                                                 │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  1. Exact ride type match                                                   │   │
│  │  2. Pickup ETA < 10 minutes                                                 │   │
│  │  3. Driver acceptance rate > 80%                                            │   │
│  │  4. Driver rating > 4.5                                                     │   │
│  │  5. Time since last ride (fairness)                                         │   │
│  │                                                                              │   │
│  │  Timeout handling:                                                           │   │
│  │  - Driver has 15 seconds to accept                                          │   │
│  │  - If declined/timeout: dispatch to next best                               │   │
│  │  - After 3 attempts: expand search radius                                   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

**Implementation:**

```python
class MatchingService:
    def __init__(self):
        self.location_service = LocationService()
        self.eta_service = ETAService()
        
    async def match_rider(self, ride_request: RideRequest) -> MatchResult:
        # Find nearby drivers
        candidates = await self.location_service.find_nearby_drivers(
            lat=ride_request.pickup_lat,
            lng=ride_request.pickup_lng,
            radius_km=5.0
        )
        
        # Filter by ride type
        candidates = [c for c in candidates if c.vehicle_type == ride_request.ride_type]
        
        # Score each candidate
        scored_candidates = []
        for candidate in candidates:
            score = await self.score_candidate(candidate, ride_request)
            scored_candidates.append((candidate, score))
        
        # Sort by score (lower is better)
        scored_candidates.sort(key=lambda x: x[1])
        
        # Try to dispatch to best candidates
        for candidate, score in scored_candidates[:5]:
            result = await self.dispatch_to_driver(candidate, ride_request)
            if result.accepted:
                return MatchResult(
                    success=True,
                    driver_id=candidate.driver_id,
                    eta=result.eta
                )
        
        # No driver accepted - queue for retry or notify rider
        return MatchResult(success=False, reason="no_drivers_available")
    
    async def score_candidate(self, candidate, ride_request):
        # Calculate ETA to pickup
        eta = await self.eta_service.get_eta(
            from_lat=candidate.lat,
            from_lng=candidate.lng,
            to_lat=ride_request.pickup_lat,
            to_lng=ride_request.pickup_lng
        )
        
        score = 0
        
        # Primary factor: ETA (weight: 0.5)
        score += eta.minutes * 0.5
        
        # Driver wait time - prioritize drivers waiting longer (weight: 0.2)
        wait_minutes = (time.time() - candidate.last_ride_completed) / 60
        score -= min(wait_minutes, 30) * 0.2
        
        # Rating factor (weight: 0.15)
        score -= (candidate.rating - 4.0) * 0.15
        
        # Acceptance rate (weight: 0.15)
        score -= candidate.acceptance_rate * 0.15
        
        return score
    
    async def dispatch_to_driver(self, candidate, ride_request):
        # Send push notification to driver
        dispatch_id = generate_uuid()
        
        await self.notification_service.send_dispatch(
            driver_id=candidate.driver_id,
            dispatch_id=dispatch_id,
            pickup=ride_request.pickup_location,
            destination=ride_request.destination,
            fare_estimate=ride_request.fare_estimate
        )
        
        # Wait for response (15 second timeout)
        try:
            response = await asyncio.wait_for(
                self.wait_for_driver_response(dispatch_id),
                timeout=15.0
            )
            return response
        except asyncio.TimeoutError:
            return DispatchResponse(accepted=False, reason="timeout")
```

### 3. Surge Pricing (Deep Dive)

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          SURGE PRICING                                               │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Goal: Balance supply and demand dynamically                                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         SURGE CALCULATION                                    │   │
│  │                                                                              │   │
│  │  Inputs:                                                                     │   │
│  │  - Current demand (ride requests in area)                                   │   │
│  │  - Current supply (available drivers in area)                               │   │
│  │  - Historical patterns (same time/day)                                      │   │
│  │  - Events (concerts, sports, weather)                                       │   │
│  │                                                                              │   │
│  │  Formula:                                                                    │   │
│  │                                                                              │   │
│  │  demand_supply_ratio = active_requests / available_drivers                  │   │
│  │                                                                              │   │
│  │  if ratio < 1.0:                                                            │   │
│  │      surge = 1.0 (no surge)                                                 │   │
│  │  else:                                                                       │   │
│  │      surge = 1.0 + log(ratio) * surge_factor                                │   │
│  │      surge = min(surge, MAX_SURGE)  # Cap at 3x-5x                         │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Geographic Zones:                                                                  │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  City divided into hexagonal zones (~1km²)                                  │   │
│  │  Each zone has independent surge multiplier                                  │   │
│  │                                                                              │   │
│  │       ┌───┐   ┌───┐                                                         │   │
│  │      │1.0│   │2.0│  ← Different surge per zone                              │   │
│  │       └───┘   └───┘                                                         │   │
│  │         ┌───┐                                                               │   │
│  │        │1.5│                                                                │   │
│  │         └───┘                                                               │   │
│  │                                                                              │   │
│  │  Updates: Every 1-2 minutes                                                 │   │
│  │  Smoothing: Gradual changes to avoid oscillation                            │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Implementation:                                                                    │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  class SurgeService:                                                         │   │
│  │      def calculate_surge(zone_id):                                          │   │
│  │          # Get current metrics                                              │   │
│  │          demand = get_active_requests(zone_id, window=5min)                 │   │
│  │          supply = get_available_drivers(zone_id)                            │   │
│  │                                                                              │   │
│  │          # Calculate base surge                                             │   │
│  │          if supply == 0:                                                    │   │
│  │              return MAX_SURGE                                               │   │
│  │                                                                              │   │
│  │          ratio = demand / supply                                            │   │
│  │          base_surge = 1.0 + max(0, log(ratio) * 0.5)                        │   │
│  │                                                                              │   │
│  │          # Apply smoothing (20% change per update)                          │   │
│  │          current = get_current_surge(zone_id)                               │   │
│  │          new_surge = current + (base_surge - current) * 0.2                 │   │
│  │                                                                              │   │
│  │          return round(new_surge, 1)  # Round to 0.1x                       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### 1. Location Update Frequency

| Frequency | Pros | Cons |
|-----------|------|------|
| 1 second | Very accurate | High bandwidth, battery drain |
| 4 seconds | Good balance | Industry standard |
| 10 seconds | Low resource use | Stale location data |

**Decision:** 4 seconds (Uber's choice) - balances accuracy with resource usage

### 2. Database Selection

| Data | Database | Reason |
|------|----------|--------|
| Trip data | PostgreSQL | ACID for financial data |
| Location index | Redis + S2 | In-memory, fast updates |
| User profiles | PostgreSQL | Structured, consistent |
| Analytics | Cassandra/Druid | Time-series, high write |

### 3. Matching Strategy

| Approach | Latency | Optimality |
|----------|---------|------------|
| Nearest driver | <1s | Local optimum |
| Batch matching (2s) | 2-3s | Better global |
| Full optimization | 5-10s | Best but too slow |

**Decision:** Batch matching with 2-second window

### 4. ETA Calculation

```
┌─────────────────────────────────────────────────────────────────┐
│                    ETA APPROACHES                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Option 1: Simple distance-based                                │
│  ETA = distance / average_speed                                 │
│  Accuracy: ~50% (ignores traffic)                               │
│                                                                 │
│  Option 2: Route-based with traffic                             │
│  ETA = route_distance / speed_per_segment                       │
│  Accuracy: ~70%                                                 │
│                                                                 │
│  Option 3: ML-based (Uber's approach)                           │
│  Features:                                                       │
│  - Distance and route                                           │
│  - Historical travel times                                      │
│  - Current traffic                                              │
│  - Time of day, day of week                                     │
│  - Weather conditions                                           │
│  - Local events                                                 │
│  Accuracy: ~85-90%                                              │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Failure Scenarios and Bottlenecks

### 1. Location Service Failure

```
┌─────────────────────────────────────────────────────────────────┐
│                 LOCATION SERVICE FAILURE                         │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Impact: Cannot match riders to drivers                         │
│                                                                 │
│  Mitigation:                                                     │
│  1. Redis cluster with replication (6 nodes)                    │
│  2. Automatic failover (< 30 seconds)                           │
│  3. Write to multiple nodes (quorum)                            │
│  4. Client-side caching of last known location                  │
│                                                                 │
│  Fallback:                                                       │
│  - Use cached driver positions (up to 30 seconds old)           │
│  - Expand search radius                                         │
│  - Show "finding driver" message                                │
│                                                                 │
│  Recovery: Location updates resume, index rebuilds in <1 min    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Payment Processing Failure

```
Problem: Payment gateway unavailable

Solution:
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  1. Multiple payment providers (Stripe, Braintree, Adyen)       │
│  2. Automatic failover between providers                        │
│  3. Queue failed charges for retry                              │
│  4. Allow ride completion - charge later                        │
│                                                                 │
│  For riders:                                                     │
│  - Complete ride normally                                       │
│  - Charge when payment restored                                 │
│  - Email notification of pending charge                         │
│                                                                 │
│  For drivers:                                                    │
│  - Earnings credited normally                                   │
│  - Uber absorbs risk of failed collection                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 3. High Demand Spike (Concert ending)

```
Problem: 10x spike in ride requests in 1km² area

Mitigation:
1. Predictive scaling
   - Know event end time
   - Pre-position drivers
   - Notify drivers of surge opportunity

2. Gradual surge pricing
   - Increase price gradually
   - Incentivize more drivers to area
   - Balance demand with available supply

3. Queue management
   - Fair queuing for riders
   - Estimated wait times
   - Option to walk to lower-surge area

4. Communication
   - Push notification: "Event ending, expect delays"
   - Show surge map
   - Offer scheduled ride option
```

### 4. Driver App Crashes

```
Problem: Driver's app crashes during trip

Solution:
┌─────────────────────────────────────────────────────────────────┐
│                                                                 │
│  Detection:                                                      │
│  - No location updates for > 30 seconds                         │
│  - No heartbeat from app                                        │
│                                                                 │
│  Response:                                                       │
│  1. Send SMS to driver                                          │
│  2. Call driver's phone                                         │
│  3. Show last known location to rider                           │
│  4. Rider can call driver directly                              │
│                                                                 │
│  If unresponsive (5 minutes):                                   │
│  - Mark trip as "potential issue"                               │
│  - Alert safety team                                            │
│  - Offer rider option to cancel (no charge)                     │
│                                                                 │
│  Data preservation:                                              │
│  - Server maintains trip state                                  │
│  - App syncs on restart                                         │
│  - Trip can resume from last checkpoint                         │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Future Improvements

### 1. Autonomous Vehicles

- Self-driving car integration
- Different matching algorithms
- Remote monitoring center
- Hybrid fleet management

### 2. Multi-Modal Transportation

- Combine Uber with public transit
- Bike/scooter first/last mile
- Real-time transit integration
- Unified trip planning

### 3. Predictive Dispatch

```
┌─────────────────────────────────────────────────────────────────┐
│                   PREDICTIVE DISPATCH                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Predict where rides will be requested:                         │
│  - Historical patterns                                          │
│  - Events calendar                                              │
│  - Weather forecast                                             │
│  - Flight arrivals                                              │
│                                                                 │
│  Pre-position drivers:                                          │
│  - Send incentives to relocate                                  │
│  - Show heat map of predicted demand                            │
│  - Reduce wait times by 30%+                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Interviewer Questions & Answers

### Q1: How do you efficiently find nearby drivers?

**Answer:**

**Data Structure: S2 Geometry + Redis**

S2 is a hierarchical spatial index that divides Earth into cells:
- Uses Hilbert curve for locality preservation
- Uniform cell sizes (unlike geohash)
- Efficient range queries

**Implementation:**
```
1. Each driver's location mapped to S2 cell (level 14 ≈ 1.6km)
2. Redis stores: cell_id → Set{driver_ids}
3. On location update:
   - Calculate new cell
   - If changed: SREM from old, SADD to new
4. On nearby query:
   - Get covering cells for radius
   - SMEMBERS for each cell
   - Filter by exact distance
```

**Why not PostGIS?**
- Too slow for 500K updates/second
- Redis provides O(1) lookups
- PostGIS better for complex queries, not real-time

**Scaling:**
- Shard by geography (city-level)
- Each city cluster handles local traffic
- Cross-city trips handled by routing

---

### Q2: How does the driver-rider matching algorithm work?

**Answer:**

**Approach: Batch Matching**

Instead of immediately matching, we batch requests for 1-2 seconds:

```
Window: 2 seconds
├── Rider 1 requests at t=0.1s
├── Rider 2 requests at t=0.5s
├── Rider 3 requests at t=1.2s
└── At t=2.0s: Match all together
```

**Algorithm:**
1. Build cost matrix (riders × drivers)
2. Cost factors:
   - Pickup ETA (primary)
   - Driver idle time (fairness)
   - Driver rating
   - Vehicle type match
3. Find minimum cost assignment (Hungarian algorithm)
4. Dispatch to matched drivers

**Benefits:**
- Better global optimization
- Reduces driver repositioning
- Fairer distribution

**Timeout handling:**
- Driver has 15s to accept
- If timeout: rematch with remaining drivers
- After 3 attempts: notify rider, expand search

---

### Q3: How do you implement surge pricing?

**Answer:**

**Goal:** Balance supply and demand dynamically

**Calculation:**
```python
def calculate_surge(zone_id):
    # Current metrics (5-minute window)
    demand = count_ride_requests(zone_id, window=5min)
    supply = count_available_drivers(zone_id)
    
    if supply == 0:
        return MAX_SURGE  # Usually 3x-5x
    
    ratio = demand / supply
    
    if ratio <= 1.0:
        return 1.0  # No surge
    
    # Logarithmic scaling
    surge = 1.0 + log(ratio) * surge_factor
    
    # Smoothing (prevent oscillation)
    current = get_current_surge(zone_id)
    new_surge = current + (surge - current) * 0.2
    
    return min(new_surge, MAX_SURGE)
```

**Zone management:**
- City divided into ~1km² hexagonal zones
- Each zone has independent surge
- Updates every 1-2 minutes

**Effects:**
- High surge → More drivers come to area
- High surge → Some riders wait/walk
- System reaches equilibrium

---

### Q4: How do you calculate ETA accurately?

**Answer:**

**ML-based approach (3 stages):**

**Stage 1: Route calculation**
- Use road network graph
- Account for one-way streets, turn restrictions
- Multiple route options

**Stage 2: Segment-level prediction**
```
For each road segment:
- Historical travel times (by time of day)
- Current traffic speed
- Incidents/road closures
```

**Stage 3: ML adjustment**
```
Features:
- Base route ETA
- Time of day, day of week
- Weather conditions
- Special events nearby
- Driver's typical speed
- Vehicle type

Model: Gradient Boosted Trees
Output: Adjusted ETA with confidence interval
```

**Continuous improvement:**
- Actual trip times feed back into model
- A/B test different models
- Regional model variations

**Accuracy: 85-90%** (within 20% of actual)

---

### Q5: How do you handle the real-time location updates at scale?

**Answer:**

**Scale: 500K-1M updates per second**

**Architecture:**
```
Driver App → WebSocket Gateway → Kafka → Location Processors → Redis
```

**WebSocket Gateway:**
- Maintains persistent connections
- 10K connections per server
- 50-100 gateway servers per region

**Kafka:**
- Partitioned by driver_id
- Ordered processing per driver
- Buffer for spikes

**Processing:**
- Stateless workers
- Update Redis index
- Broadcast to tracking riders

**Optimizations:**
- Batch Redis writes (10ms batches)
- Skip updates if distance < 10m
- Different frequencies: moving (4s) vs idle (30s)

**Bandwidth:**
- Update size: ~50 bytes
- 500K/s × 50 = 25 MB/s
- Acceptable for datacenter network

---

### Q6: How do you ensure high availability?

**Answer:**

**Multi-level redundancy:**

**1. Application layer:**
- Kubernetes with 3+ replicas per service
- Auto-scaling based on CPU/RPS
- Rolling deployments

**2. Database layer:**
- PostgreSQL: Primary + 2 replicas
- Redis: 6-node cluster (3 master, 3 replica)
- Cross-AZ replication

**3. Geographic:**
- Multiple datacenters per region
- Active-active where possible
- Failover in < 30 seconds

**4. Graceful degradation:**
```
Tier 1 (normal): Full features
Tier 2 (degraded): Disable non-essential
  - No surge calculation (use cached)
  - Simple matching (nearest driver)
Tier 3 (emergency): Core function only
  - Accept requests
  - Basic matching
  - Complete trips
```

**SLA: 99.99%** (52 minutes downtime/year)

---

### Q7: How would you design the payment system?

**Answer:**

**Architecture:**
```
Trip End → Payment Service → Payment Gateway → Bank
                 │
                 ├── Stripe (primary)
                 ├── Braintree (backup)
                 └── Local providers
```

**Flow:**
1. Trip ends, fare calculated
2. Attempt charge on primary provider
3. If fails, retry with backoff
4. If all fail, queue for later
5. Success → credit driver earnings

**Handling failures:**
```python
async def process_payment(trip):
    for attempt in range(MAX_RETRIES):
        for provider in get_providers(trip.country):
            try:
                result = await provider.charge(trip.fare, trip.payment_method)
                if result.success:
                    return result
            except ProviderError:
                continue
        
        await asyncio.sleep(backoff(attempt))
    
    # All failed - queue for manual review
    await queue_failed_payment(trip)
    return PaymentResult(pending=True)
```

**Fraud detection:**
- ML model scores each transaction
- Flag unusual patterns
- Geographic anomalies
- Velocity checks

**Driver payouts:**
- Batch processing (weekly)
- Instant pay option (small fee)
- Multiple payout methods

---

### Q8: How do you handle trip tracking and history?

**Answer:**

**Real-time tracking:**
```
Driver Update → Kafka → Trip Service → WebSocket → Rider App
```

**Data stored per trip:**
```
- Pickup/dropoff locations
- Route taken (polyline)
- Key timestamps (request, accept, pickup, dropoff)
- Fare breakdown
- Surge multiplier
- Driver/rider info
- Ratings
```

**Route recording:**
```python
class TripTracker:
    def record_location(self, trip_id, location):
        # Store location point
        self.cassandra.insert(
            f"trip_locations:{trip_id}",
            {
                "timestamp": location.timestamp,
                "lat": location.lat,
                "lng": location.lng,
                "speed": location.speed
            }
        )
        
        # Broadcast to rider (if tracking)
        if self.is_rider_tracking(trip_id):
            self.websocket.send(trip_id, location)
```

**History queries:**
- PostgreSQL for metadata
- Cassandra for location time-series
- Elasticsearch for search

---

### Q9: How would you implement safety features like SOS?

**Answer:**

**SOS Flow:**
```
┌─────────────────────────────────────────────────────────────────┐
│                    SOS SYSTEM                                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Trigger:                                                        │
│  - User presses SOS button                                      │
│  - Shake detection (optional)                                   │
│  - Voice command "Hey Uber, call 911"                           │
│                                                                 │
│  Immediate Actions:                                              │
│  1. Freeze trip state (preserve evidence)                       │
│  2. Start audio recording                                       │
│  3. Share live location with emergency contacts                 │
│  4. Alert Uber safety team                                      │
│  5. Option to call 911 directly                                 │
│                                                                 │
│  Safety Team Response:                                           │
│  - 24/7 monitoring center                                       │
│  - Can contact driver/rider                                     │
│  - Coordinate with law enforcement                              │
│  - Track all parties in real-time                               │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Additional safety features:**
- Photo verification of driver
- Share trip with friends/family
- Unexpected stop detection
- Driver background checks
- In-app messaging (no phone number sharing)

---

### Q10: How do you handle international expansion and localization?

**Answer:**

**Geographic considerations:**

1. **Data residency:**
   - Each region has local database cluster
   - User data stays in region
   - Compliance with local laws (GDPR, etc.)

2. **Payment methods:**
   - Credit cards (global)
   - Local wallets (M-Pesa in Africa)
   - Cash (some markets)
   - UPI (India)

3. **Mapping:**
   - Google Maps (primary)
   - Local providers where better
   - OpenStreetMap as fallback

4. **Ride types:**
   - Auto-rickshaws (India)
   - Motorcycles (Southeast Asia)
   - Boats (some cities)

**Localization:**
```python
class LocalizationService:
    def get_config(self, city_id):
        return {
            "currency": "INR",
            "language": "hi-IN",
            "date_format": "DD/MM/YYYY",
            "ride_types": ["auto", "mini", "sedan"],
            "payment_methods": ["card", "upi", "cash"],
            "surge_max": 2.0,  # Lower cap in some markets
            "tipping": False,  # Not all markets
        }
```

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│                   UBER - FINAL ARCHITECTURE                      │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 20M rides/day, 5M drivers, 1M location updates/sec     │
│                                                                 │
│  Core Components:                                               │
│  ├── API Gateway - Authentication, routing, rate limiting      │
│  ├── WebSocket Gateway - Real-time location streaming          │
│  ├── Trip Service - Trip lifecycle management                  │
│  ├── Match Service - Driver-rider matching                     │
│  ├── Location Service - Geospatial indexing (S2 + Redis)       │
│  ├── Pricing Service - Fare calculation, surge                 │
│  ├── Payment Service - Multi-provider payments                 │
│  └── ETA Service - ML-based arrival predictions                │
│                                                                 │
│  Data Stores:                                                   │
│  ├── PostgreSQL - Trip data, user profiles                     │
│  ├── Redis - Location index, caching                           │
│  ├── Cassandra - Location history, analytics                   │
│  └── Elasticsearch - Search, place autocomplete                │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── S2 geometry for geospatial indexing                       │
│  ├── Batch matching (2s windows)                               │
│  ├── ML-based ETA predictions                                  │
│  ├── Zone-based surge pricing                                  │
│  └── WebSocket for real-time updates                           │
│                                                                 │
│  SLA: 99.99% availability, <30s matching time                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
