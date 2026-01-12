# Hotel Booking System - System Design Interview

## Problem Statement
*"Design a hotel booking platform like Booking.com or Expedia that handles millions of users searching and booking hotel rooms globally with real-time inventory management."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What core features do we need?" → Hotel search, room booking, payment processing, inventory management
- "Should we support real-time pricing?" → Yes, dynamic pricing based on demand, seasons, events
- "Do we need user reviews and ratings?" → Yes, bidirectional reviews between guests and hotels
- "What about cancellation policies?" → Yes, flexible cancellation with different policies per hotel
- "Should we support corporate bookings?" → Yes, bulk bookings and corporate discounts
- "Do we need loyalty programs?" → Yes, points system and tier-based benefits

**Non-Functional Requirements:**
- "What's our scale?" → 100M users, 1M hotels, 50M bookings/year
- "Expected search latency?" → <200ms for hotel search, <100ms booking confirmation
- "Availability needs?" → 99.99% uptime (travel bookings are time-sensitive)
- "Geographic coverage?" → Global platform with multi-currency, multi-language
- "Peak load patterns?" → Holiday seasons, events can cause 10x traffic spikes

### Requirements Summary:
- **Scale**: 100M users, 1M hotels, 50M bookings annually, 10K+ searches/second
- **Features**: Search, booking, payments, reviews, inventory management, pricing
- **Performance**: <200ms search, <100ms booking, real-time availability
- **Global**: Multi-region, multi-currency, dynamic pricing
- **Reliability**: Zero double bookings, accurate inventory

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Daily active users: 10M users
Daily searches: 10M users × 5 searches = 50M searches
Daily bookings: 50M searches × 2% conversion = 1M bookings
Peak search QPS: 50M ÷ 86,400 × 3 (peak) = ~1,700 QPS
Peak booking QPS: 1M ÷ 86,400 × 3 = ~35 QPS
```

### Storage Estimation:
```
Hotels: 1M hotels × 10KB = 10GB
Rooms: 50M rooms × 2KB = 100GB
Users: 100M users × 2KB = 200GB
Bookings: 1M/day × 2KB × 365 × 5 years = 3.6TB
Reviews: 500K/day × 1KB × 365 × 5 years = 900GB
Images: 1M hotels × 20 images × 500KB = 10TB
Total: ~15TB with indexes and replicas
```

### Memory Requirements:
```
Search cache: Hot hotels and room data = 5GB
User sessions: 1M concurrent × 2KB = 2GB
Inventory cache: Real-time availability = 2GB
Pricing cache: Dynamic rates = 1GB
Total memory: ~10GB per region
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Mobile Apps  │  │Web Client   │  │Hotel Portal │
│- Search     │  │- Browse     │  │- Inventory  │
│- Book       │  │- Book       │  │- Analytics  │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│             CDN + Load Balancer         │
│- Global edge locations - Cache         │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│              API Gateway                │
│- Authentication - Rate limiting         │
│- Request routing - Circuit breaker     │
└─────────────────────────────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Search      │ │Booking     │ │User        │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Hotels    │ │- Reserve   │ │- Profile   │
│- Filter    │ │- Confirm   │ │- Auth      │
│- Ranking   │ │- Cancel    │ │- Loyalty   │
└────────────┘ └────────────┘ └────────────┘
    │                   │                   │
    └───────────────────┼───────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Inventory   │ │Pricing     │ │Payment     │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Rooms     │ │- Dynamic   │ │- Process   │
│- Avail     │ │- Seasonal  │ │- Refund    │
│- Block     │ │- Demand    │ │- Multi-pay │
└────────────┘ └────────────┘ └────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│           Supporting Services           │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │Review   │  │Notification│ │Analytics│   │
│ │Service  │  │Service   │ │Service  │   │
│ │         │  │          │ │         │   │
│ │- Rating │  │- Email   │ │- Reports│   │
│ │- Feedback│ │- SMS     │ │- ML     │   │
│ │- Moderation││- Push   │ │- Insights│  │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│               Data Layer                │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │PostgreSQL│  │Elasticsearch│ │Redis  │   │
│ │         │  │            │  │       │   │
│ │- Hotels │  │- Search    │  │- Cache│   │
│ │- Bookings│  │- Analytics │  │- Session│  │
│ │- Users  │  │- Logs      │  │- Inventory│ │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
```

### Key Components:
- **Search Service**: Hotel discovery, filtering, ranking algorithms
- **Booking Service**: Reservation management, booking lifecycle
- **Inventory Service**: Room availability, real-time updates, blocking
- **Pricing Service**: Dynamic pricing, demand-based rates
- **Payment Service**: Multi-currency payments, refunds

---

## Phase 4: Database Design (8 minutes)

### Core Entities:
- Hotels, Rooms, Bookings, Users, Reviews, Pricing

### PostgreSQL Schema:
```sql
-- Hotels master data
CREATE TABLE hotels (
    hotel_id UUID PRIMARY KEY,
    hotel_name VARCHAR(300) NOT NULL,
    chain_id UUID,                    -- Marriott, Hilton, etc.
    
    -- Location information
    address JSONB NOT NULL,
    city VARCHAR(100) NOT NULL,
    country VARCHAR(50) NOT NULL,
    latitude DECIMAL(10, 8),
    longitude DECIMAL(11, 8),
    
    -- Hotel details
    star_rating INTEGER CHECK (star_rating >= 1 AND star_rating <= 5),
    hotel_type VARCHAR(50),           -- business, resort, boutique
    amenities TEXT[],                 -- pool, gym, spa, wifi
    description TEXT,
    
    -- Business information
    check_in_time TIME DEFAULT '15:00',
    check_out_time TIME DEFAULT '11:00',
    cancellation_policy JSONB,
    
    -- Performance metrics
    average_rating DECIMAL(3,2) DEFAULT 0,
    total_reviews INTEGER DEFAULT 0,
    
    -- Status
    hotel_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_hotels_location (city, country),
    INDEX idx_hotels_rating (average_rating DESC),
    INDEX idx_hotels_geolocation USING GIST (POINT(longitude, latitude))
);

-- Room types and inventory
CREATE TABLE room_types (
    room_type_id UUID PRIMARY KEY,
    hotel_id UUID REFERENCES hotels(hotel_id),
    
    -- Room details
    room_name VARCHAR(200) NOT NULL,   -- Deluxe King, Standard Twin
    room_size INTEGER,                 -- in sq ft
    bed_type VARCHAR(50),             -- king, queen, twin, sofa
    max_occupancy INTEGER DEFAULT 2,
    
    -- Amenities
    amenities TEXT[],                 -- mini-bar, balcony, sea-view
    
    -- Pricing
    base_price_per_night DECIMAL(10,2) NOT NULL,
    currency VARCHAR(3) DEFAULT 'USD',
    
    -- Inventory
    total_rooms INTEGER NOT NULL,
    
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_room_types_hotel (hotel_id)
);

-- Room inventory and availability
CREATE TABLE room_inventory (
    inventory_id UUID PRIMARY KEY,
    room_type_id UUID REFERENCES room_types(room_type_id),
    
    -- Date-specific availability
    availability_date DATE NOT NULL,
    available_rooms INTEGER NOT NULL,
    base_price DECIMAL(10,2) NOT NULL,
    
    -- Dynamic pricing
    demand_multiplier DECIMAL(4,2) DEFAULT 1.00,
    final_price DECIMAL(10,2) GENERATED ALWAYS AS (base_price * demand_multiplier) STORED,
    
    -- Booking restrictions
    min_stay_nights INTEGER DEFAULT 1,
    max_stay_nights INTEGER DEFAULT 30,
    
    last_updated TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(room_type_id, availability_date),
    INDEX idx_inventory_date_hotel (room_type_id, availability_date),
    INDEX idx_inventory_availability (availability_date, available_rooms)
);

-- Bookings
CREATE TABLE bookings (
    booking_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    hotel_id UUID REFERENCES hotels(hotel_id),
    room_type_id UUID REFERENCES room_types(room_type_id),
    
    -- Booking details
    check_in_date DATE NOT NULL,
    check_out_date DATE NOT NULL,
    nights INTEGER GENERATED ALWAYS AS (check_out_date - check_in_date) STORED,
    rooms_booked INTEGER DEFAULT 1,
    guests INTEGER DEFAULT 2,
    
    -- Pricing
    room_rate_per_night DECIMAL(10,2) NOT NULL,
    total_room_cost DECIMAL(12,2),
    taxes_and_fees DECIMAL(10,2) DEFAULT 0,
    total_amount DECIMAL(12,2),
    currency VARCHAR(3) DEFAULT 'USD',
    
    -- Booking status
    booking_status VARCHAR(20) DEFAULT 'confirmed', -- confirmed, cancelled, completed, no_show
    booking_reference VARCHAR(20) UNIQUE NOT NULL,
    
    -- Guest information
    primary_guest_name VARCHAR(200) NOT NULL,
    primary_guest_email VARCHAR(255),
    primary_guest_phone VARCHAR(20),
    special_requests TEXT,
    
    -- Payment
    payment_method VARCHAR(50),
    payment_status VARCHAR(20) DEFAULT 'paid',
    
    -- Timing
    booked_at TIMESTAMP DEFAULT NOW(),
    cancelled_at TIMESTAMP,
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_bookings_user (user_id, booked_at DESC),
    INDEX idx_bookings_hotel_dates (hotel_id, check_in_date, check_out_date),
    INDEX idx_bookings_reference (booking_reference),
    INDEX idx_bookings_status (booking_status, booked_at)
);

-- User reviews and ratings
CREATE TABLE reviews (
    review_id UUID PRIMARY KEY,
    booking_id UUID REFERENCES bookings(booking_id),
    user_id UUID NOT NULL,
    hotel_id UUID REFERENCES hotels(hotel_id),
    
    -- Rating breakdown
    overall_rating INTEGER CHECK (overall_rating >= 1 AND overall_rating <= 5),
    cleanliness_rating INTEGER CHECK (cleanliness_rating >= 1 AND cleanliness_rating <= 5),
    service_rating INTEGER CHECK (service_rating >= 1 AND service_rating <= 5),
    location_rating INTEGER CHECK (location_rating >= 1 AND location_rating <= 5),
    value_rating INTEGER CHECK (value_rating >= 1 AND value_rating <= 5),
    
    -- Review content
    review_title VARCHAR(500),
    review_text TEXT,
    
    -- Review metadata
    is_verified_stay BOOLEAN DEFAULT true,
    review_status VARCHAR(20) DEFAULT 'published', -- published, pending, rejected
    helpful_votes INTEGER DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_reviews_hotel (hotel_id, created_at DESC),
    INDEX idx_reviews_user (user_id, created_at DESC),
    INDEX idx_reviews_rating (overall_rating, created_at DESC)
);
```

### Redis Cache Strategy:
```javascript
// Hotel search cache
"search_results:{city}:{checkin}:{checkout}:{filters_hash}": {
  "hotels": [
    {"hotel_id": "uuid", "name": "Grand Hotel", "price": 150, "rating": 4.5},
    {"hotel_id": "uuid", "name": "City Inn", "price": 80, "rating": 4.2}
  ],
  "total_results": 245,
  "cached_at": 1704110400,
  "ttl": 900  // 15 minutes
}

// Room availability cache
"room_availability:{hotel_id}:{date_range}": {
  "room_types": [
    {"room_type_id": "uuid", "available": 5, "price": 150},
    {"room_type_id": "uuid", "available": 2, "price": 200}
  ],
  "last_updated": 1704110400,
  "ttl": 300  // 5 minutes
}

// Hotel details cache
"hotel_details:{hotel_id}": {
  "name": "Grand Hotel",
  "location": {"city": "NYC", "country": "USA"},
  "amenities": ["pool", "gym", "spa"],
  "average_rating": 4.5,
  "total_reviews": 1250,
  "ttl": 3600  // 1 hour
}

// User session and booking state
"booking_session:{session_id}": {
  "user_id": "uuid",
  "hotel_id": "uuid",
  "room_type_id": "uuid",
  "check_in": "2024-06-15",
  "check_out": "2024-06-18",
  "total_price": 450.00,
  "expires_at": 1704111900,
  "ttl": 1800  // 30 minutes
}
```

### Design Decisions:
- **Date-based inventory**: Flexible room availability and pricing
- **Normalized reviews**: Detailed rating breakdown for better insights
- **Booking reference**: Human-readable booking codes
- **Multi-currency support**: Global platform requirements

---

## Phase 5: Critical Flow - Hotel Search & Booking (8 minutes)

### Most Critical Flow: User Searches and Books Hotel

**1. Hotel Search Request**
```
User searches for hotels:
GET /api/hotels/search?city=NYC&checkin=2024-06-15&checkout=2024-06-18&guests=2

Parameters processed:
- Location: City, coordinates, landmarks
- Dates: Check-in/out with validation
- Occupancy: Guests, rooms needed
- Filters: Price range, rating, amenities
```

**2. Search Processing**
```
Search Service processing:
1. Validate search parameters and dates
2. Generate cache key from search parameters
3. Check Redis cache for existing results
4. If cache miss:
   - Query Elasticsearch for hotels in location
   - Filter by availability for date range
   - Apply user filters (price, rating, amenities)
   - Sort by relevance, price, or rating
   - Cache results with 15-minute TTL
5. Return paginated hotel list with pricing
```

**3. Room Availability Check**
```
When user clicks on hotel:
1. Query room_inventory for specific dates
2. Check available_rooms > 0 for each night
3. Calculate dynamic pricing based on:
   - Base price from room_types
   - Demand multiplier from inventory
   - Seasonal adjustments
   - Special offers/discounts
4. Return available room types with final pricing
```

**4. Booking Reservation**
```
User proceeds with booking:
1. Create booking session in Redis (30-minute hold)
2. Validate inventory still available
3. Calculate total pricing with taxes and fees
4. Process payment through Payment Service
5. If payment successful:
   - Create booking record in database
   - Update room_inventory (decrement available_rooms)
   - Generate unique booking reference
   - Send confirmation email
6. If payment fails:
   - Release inventory hold
   - Return error with retry option
```

**5. Inventory Management**
```
Real-time inventory updates:
1. Background jobs sync with hotel PMS systems
2. Handle overbooking scenarios with waitlists
3. Automatic inventory blocks for maintenance
4. Dynamic pricing updates based on demand
5. Cancellation processing and inventory release
```

### Technical Challenges:

**Search Performance:**
- "Elasticsearch with geospatial queries for location-based search"
- "Multi-layered caching (CDN, Redis, database) for hot data"
- "Precomputed availability aggregates for popular routes"

**Inventory Consistency:**
- "Optimistic locking to prevent double bookings"
- "Real-time inventory updates from hotel systems"
- "Compensation logic for oversold situations"

**Global Scale:**
- "Multi-region deployment with data locality"
- "CDN for static content (images, descriptions)"
- "Database sharding by geographic region"

**Dynamic Pricing:**
- "Real-time price calculation based on demand"
- "Machine learning for demand forecasting"
- "A/B testing for pricing strategies"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Search latency**: Complex queries with filters across large datasets
2. **Inventory contention**: Multiple users booking same rooms
3. **Database load**: High read/write volume during peak seasons
4. **Image delivery**: Large media files affecting page load times

### Scaling Solutions:

**Search Optimization:**
```
- Elasticsearch clusters: Distributed search across regions
- Search result caching: Redis with intelligent cache warming
- Index optimization: Separate indexes for availability vs details
- CDN integration: Cache popular search results at edge
```

**Database Scaling:**
```
- Read replicas: Separate search queries from transactional operations
- Horizontal sharding: Partition by geographic region or hotel chain
- Connection pooling: Efficient database connection management
- Materialized views: Precomputed aggregates for reporting
```

**Inventory Management:**
```
- Event-driven updates: Real-time inventory sync with hotel systems
- Batch processing: Nightly inventory reconciliation jobs
- Overbooking algorithms: Intelligent overselling with waitlists
- Circuit breakers: Graceful degradation when hotel APIs fail
```

**Global Performance:**
```
- Multi-region deployment: Data centers in major markets
- Content delivery: Images and static assets via CDN
- Currency/language: Localized content and pricing
- Edge caching: Hotel data cached at geographic edges
```

### Trade-offs:
- **Search Accuracy vs Speed**: Real-time availability vs cached results
- **Consistency vs Availability**: Strong inventory consistency vs system uptime
- **Personalization vs Privacy**: User tracking for recommendations vs data protection

---

## Advanced Features:

**Machine Learning:**
- Demand forecasting for dynamic pricing
- Personalized hotel recommendations
- Fraud detection for bookings and reviews
- Revenue optimization algorithms

**Business Intelligence:**
- Real-time analytics dashboard
- Booking pattern analysis
- Competitive pricing intelligence
- Customer lifetime value tracking

---

## Success Metrics:
- **Search Performance**: <200ms hotel search response time
- **Booking Conversion**: >3% search-to-booking conversion rate
- **Inventory Accuracy**: >99.9% availability accuracy
- **System Uptime**: 99.99% availability during peak travel periods
- **Customer Satisfaction**: >4.5 average booking rating

**🎯 This design demonstrates travel industry expertise, inventory management, search optimization, and building global platforms handling complex business logic with high availability requirements.**




