# MakeMyTrip - UML Diagrams

## 1. Class Diagram - Core Domain Model

```mermaid
classDiagram
    class User {
        +String userId
        +String email
        +String phoneNumber
        +String name
        +Date dateOfBirth
        +Address address
        +List~Booking~ bookingHistory
        +WishList wishList
        +LoyaltyAccount loyaltyAccount
        +register()
        +login()
        +updateProfile()
        +viewBookings()
    }

    class Booking {
        +String bookingId
        +String userId
        +BookingType type
        +BookingStatus status
        +Date bookingDate
        +BigDecimal totalAmount
        +PaymentInfo paymentInfo
        +List~Traveler~ travelers
        +Date createdAt
        +Date updatedAt
        +createBooking()
        +confirmBooking()
        +cancelBooking()
        +modifyBooking()
    }

    class HotelBooking {
        +String hotelId
        +Date checkInDate
        +Date checkOutDate
        +Integer numberOfRooms
        +List~Room~ rooms
        +List~Guest~ guests
        +MealPlan mealPlan
    }

    class FlightBooking {
        +String flightId
        +Date departureDate
        +Date returnDate
        +String origin
        +String destination
        +TripType tripType
        +List~Passenger~ passengers
        +List~Seat~ seats
        +BaggageInfo baggage
    }

    class Hotel {
        +String hotelId
        +String name
        +String description
        +Address address
        +GeoLocation location
        +Float rating
        +List~String~ amenities
        +List~Room~ rooms
        +List~Image~ images
        +ContactInfo contactInfo
        +PriceRange priceRange
        +checkAvailability()
        +getRoomDetails()
        +getReviews()
    }

    class Room {
        +String roomId
        +String roomType
        +Integer capacity
        +BigDecimal basePrice
        +BigDecimal currentPrice
        +Integer availableCount
        +List~String~ amenities
        +List~Image~ images
        +Boolean isAvailable
        +reserve()
        +release()
    }

    class Flight {
        +String flightId
        +String flightNumber
        +Airline airline
        +String origin
        +String destination
        +DateTime departureTime
        +DateTime arrivalTime
        +Duration duration
        +Integer stops
        +Aircraft aircraft
        +List~Seat~ seats
        +checkAvailability()
        +getSeatMap()
    }

    class Seat {
        +String seatId
        +String seatNumber
        +SeatClass seatClass
        +SeatType seatType
        +BigDecimal price
        +Boolean isAvailable
        +reserve()
        +release()
    }

    class Payment {
        +String paymentId
        +String bookingId
        +BigDecimal amount
        +PaymentMethod method
        +PaymentStatus status
        +String transactionId
        +Date paymentDate
        +String gatewayResponse
        +processPayment()
        +refund()
        +verify()
    }

    class Review {
        +String reviewId
        +String userId
        +String itemId
        +ItemType itemType
        +Float rating
        +String title
        +String comment
        +List~Image~ images
        +Date reviewDate
        +Integer helpfulCount
        +submitReview()
        +updateReview()
        +deleteReview()
    }

    class SearchQuery {
        +String queryId
        +String userId
        +SearchType type
        +Map~String,Object~ filters
        +Date searchDate
        +execute()
        +getResults()
    }

    class HotelSearchQuery {
        +String city
        +Date checkInDate
        +Date checkOutDate
        +Integer rooms
        +Integer guests
        +PriceRange priceRange
        +List~String~ amenities
        +Float minRating
    }

    class FlightSearchQuery {
        +String origin
        +String destination
        +Date departureDate
        +Date returnDate
        +Integer passengers
        +TripType tripType
        +CabinClass cabinClass
        +List~String~ airlines
    }

    class Inventory {
        +String itemId
        +ItemType type
        +Integer totalCapacity
        +Integer availableCapacity
        +Date date
        +updateAvailability()
        +lock()
        +release()
    }

    class PriceEngine {
        +calculatePrice()
        +applyDynamicPricing()
        +applyDiscounts()
        +validatePrice()
    }

    class Notification {
        +String notificationId
        +String userId
        +NotificationType type
        +String channel
        +String content
        +NotificationStatus status
        +Date sentAt
        +send()
        +retry()
    }

    class Coupon {
        +String couponCode
        +String description
        +DiscountType discountType
        +BigDecimal discountValue
        +Date validFrom
        +Date validUntil
        +Integer usageLimit
        +Integer usedCount
        +validate()
        +apply()
    }

    %% Relationships
    User "1" --> "*" Booking : makes
    User "1" --> "*" Review : writes
    User "1" --> "*" SearchQuery : performs
    
    Booking <|-- HotelBooking : extends
    Booking <|-- FlightBooking : extends
    Booking "1" --> "1" Payment : has
    
    SearchQuery <|-- HotelSearchQuery : extends
    SearchQuery <|-- FlightSearchQuery : extends
    
    HotelBooking "*" --> "1" Hotel : books
    HotelBooking "*" --> "*" Room : reserves
    
    FlightBooking "*" --> "1" Flight : books
    FlightBooking "*" --> "*" Seat : reserves
    
    Hotel "1" --> "*" Room : contains
    Hotel "1" --> "*" Review : has
    
    Flight "1" --> "*" Seat : contains
    Flight "1" --> "*" Review : has
    
    Hotel "1" --> "1" Inventory : has
    Flight "1" --> "1" Inventory : has
    
    PriceEngine --> Room : calculates price for
    PriceEngine --> Seat : calculates price for
    
    Booking "*" --> "*" Coupon : uses
    Booking "1" --> "*" Notification : triggers
```

## 2. Sequence Diagram - Hotel Search Flow

```mermaid
sequenceDiagram
    actor User
    participant MobileApp
    participant APIGateway
    participant AuthService
    participant SearchService
    participant Cache as Redis Cache
    participant ES as Elasticsearch
    participant InventoryDB
    participant PriceEngine

    User->>MobileApp: Enter search criteria<br/>(Mumbai, Check-in: Feb 1, Check-out: Feb 3)
    
    MobileApp->>APIGateway: POST /api/v1/search/hotels<br/>{city: "Mumbai", checkIn: "2026-02-01", ...}
    
    APIGateway->>AuthService: Validate JWT Token
    AuthService-->>APIGateway: Token Valid ✓
    
    APIGateway->>APIGateway: Apply Rate Limiting<br/>(100 req/min per user)
    
    APIGateway->>SearchService: Forward Search Request
    
    SearchService->>SearchService: Generate Cache Key<br/>hash(query_params)
    
    SearchService->>Cache: GET search_results:{cache_key}
    
    alt Cache Hit
        Cache-->>SearchService: Cached Results (JSON)
        SearchService-->>APIGateway: Return Results (50ms)
    else Cache Miss
        Cache-->>SearchService: Cache Miss
        
        SearchService->>ES: Search Query<br/>{city: "Mumbai", available: true, ...}
        Note over ES: Query matches on:<br/>- City keyword<br/>- Date range<br/>- Availability<br/>- Filters (price, rating)
        
        ES->>ES: Execute Search<br/>- Query inverted index<br/>- Apply filters<br/>- Calculate relevance score<br/>- Sort results
        
        ES-->>SearchService: Search Results<br/>(hotel IDs + basic data)
        
        par Parallel Enrichment
            SearchService->>InventoryDB: Get real-time availability<br/>for top 20 hotels
            InventoryDB-->>SearchService: Availability data
        and
            SearchService->>PriceEngine: Get current prices<br/>for top 20 hotels
            PriceEngine->>PriceEngine: Calculate dynamic pricing
            PriceEngine-->>SearchService: Price data
        end
        
        SearchService->>SearchService: Merge & Enrich Results<br/>- Add prices<br/>- Add availability<br/>- Apply personalization
        
        SearchService->>Cache: SET search_results:{cache_key}<br/>TTL: 300 seconds
        Cache-->>SearchService: OK
        
        SearchService-->>APIGateway: Return Enriched Results (180ms)
    end
    
    APIGateway-->>MobileApp: HTTP 200 OK<br/>JSON Response
    
    MobileApp->>MobileApp: Render Search Results
    
    MobileApp-->>User: Display Hotels with<br/>- Photos<br/>- Prices<br/>- Ratings<br/>- Amenities
```

## 3. Sequence Diagram - Booking Flow with Payment

```mermaid
sequenceDiagram
    actor User
    participant MobileApp
    participant APIGateway
    participant BookingService
    participant LockService as Distributed Lock<br/>(Redis)
    participant InventoryDB
    participant PaymentService
    participant PaymentGateway as Payment Gateway<br/>(Razorpay/Stripe)
    participant NotificationService
    participant MessageQueue as Kafka

    User->>MobileApp: Select Hotel Room<br/>Click "Book Now"
    
    MobileApp->>MobileApp: Collect traveler details<br/>& payment method
    
    User->>MobileApp: Confirm Booking
    
    MobileApp->>APIGateway: POST /api/v1/bookings<br/>Idempotency-Key: {uuid}<br/>{hotelId, roomId, dates, travelers, ...}
    
    APIGateway->>BookingService: Create Booking Request
    
    BookingService->>BookingService: Validate Request<br/>- Check dates<br/>- Validate travelers<br/>- Calculate total amount
    
    BookingService->>LockService: ACQUIRE LOCK<br/>key: booking:hotel:123:room:456
    
    alt Lock Acquired
        LockService-->>BookingService: Lock Acquired ✓<br/>(TTL: 30 seconds)
        
        BookingService->>InventoryDB: BEGIN TRANSACTION<br/>(Isolation: SERIALIZABLE)
        
        BookingService->>InventoryDB: SELECT * FROM rooms<br/>WHERE room_id = 456<br/>AND check_in_date = '2026-02-01'<br/>FOR UPDATE
        
        InventoryDB-->>BookingService: Room Details<br/>{available_count: 5}
        
        alt Room Available
            BookingService->>InventoryDB: UPDATE rooms<br/>SET available_count = available_count - 1<br/>WHERE room_id = 456
            
            BookingService->>InventoryDB: INSERT INTO bookings<br/>(booking_id, user_id, room_id, status='PENDING', ...)
            
            InventoryDB-->>BookingService: Booking Created<br/>{booking_id: "BK123456"}
            
            BookingService->>InventoryDB: COMMIT TRANSACTION
            
            BookingService->>PaymentService: Process Payment<br/>Idempotency-Key: {uuid}<br/>{amount, booking_id, method}
            
            PaymentService->>PaymentGateway: Initiate Payment<br/>{amount: 10000, currency: INR, ...}
            
            PaymentGateway->>PaymentGateway: Process Payment<br/>- Validate card<br/>- Check funds<br/>- Perform 3D Secure
            
            alt Payment Success
                PaymentGateway-->>PaymentService: Payment Success<br/>{transaction_id: "TXN789"}
                
                PaymentService->>PaymentService: Record Payment<br/>in payment_transactions table
                
                PaymentService-->>BookingService: Payment Confirmed ✓
                
                BookingService->>InventoryDB: UPDATE bookings<br/>SET status = 'CONFIRMED',<br/>payment_id = 'PAY789'<br/>WHERE booking_id = 'BK123456'
                
                BookingService->>LockService: RELEASE LOCK
                
                BookingService->>MessageQueue: Publish Event<br/>topic: booking-confirmed<br/>{booking_id, user_id, ...}
                
                MessageQueue->>NotificationService: Consume Event
                
                par Send Notifications
                    NotificationService->>User: Send Email<br/>"Booking Confirmed"
                and
                    NotificationService->>User: Send SMS<br/>"Your booking BK123456 confirmed"
                and
                    NotificationService->>User: Push Notification
                end
                
                BookingService-->>APIGateway: HTTP 201 Created<br/>{booking_id, status: "CONFIRMED", ...}
                
            else Payment Failed
                PaymentGateway-->>PaymentService: Payment Failed<br/>{error: "Insufficient funds"}
                
                PaymentService-->>BookingService: Payment Failed ✗
                
                BookingService->>InventoryDB: BEGIN TRANSACTION
                BookingService->>InventoryDB: UPDATE bookings<br/>SET status = 'FAILED'<br/>WHERE booking_id = 'BK123456'
                BookingService->>InventoryDB: UPDATE rooms<br/>SET available_count = available_count + 1<br/>WHERE room_id = 456
                BookingService->>InventoryDB: COMMIT (Rollback)
                
                BookingService->>LockService: RELEASE LOCK
                
                BookingService-->>APIGateway: HTTP 402 Payment Required<br/>{error: "Payment failed"}
            end
            
        else Room Not Available
            InventoryDB-->>BookingService: available_count = 0
            
            BookingService->>InventoryDB: ROLLBACK TRANSACTION
            
            BookingService->>LockService: RELEASE LOCK
            
            BookingService-->>APIGateway: HTTP 409 Conflict<br/>{error: "Room no longer available"}
        end
        
    else Lock Failed
        LockService-->>BookingService: Lock Failed ✗<br/>(Another booking in progress)
        
        BookingService-->>APIGateway: HTTP 429 Too Many Requests<br/>{error: "Please try again"}
    end
    
    APIGateway-->>MobileApp: Response
    
    MobileApp-->>User: Show Booking Status<br/>- Success: Display e-ticket<br/>- Failure: Show error message
```

## 4. Component Diagram - Search Service Architecture

```mermaid
graph TB
    subgraph "Client Layer"
        WebApp[Web Application]
        MobileApp[Mobile App]
        PartnerAPI[Partner APIs]
    end

    subgraph "API Gateway"
        Kong[Kong API Gateway]
        RateLimit[Rate Limiter]
        Auth[Auth Middleware]
        LoadBalancer[Load Balancer]
    end

    subgraph "Search Service Cluster"
        SearchOrchestrator[Search Orchestrator]
        QueryParser[Query Parser]
        ResultAggregator[Result Aggregator]
        PersonalizationEngine[Personalization Engine]
    end

    subgraph "Cache Layer"
        RedisCluster[Redis Cluster]
        CacheNode1[Cache Node 1]
        CacheNode2[Cache Node 2]
        CacheNode3[Cache Node 3]
    end

    subgraph "Search Engine"
        ESCluster[Elasticsearch Cluster]
        ESMaster[Master Node]
        ESData1[Data Node 1]
        ESData2[Data Node 2]
        ESData3[Data Node 3]
    end

    subgraph "Supporting Services"
        PriceEngine[Price Engine]
        InventoryService[Inventory Service]
        RecommendationService[Recommendation Service]
    end

    subgraph "Data Layer"
        MongoDB[(MongoDB<br/>Inventory DB)]
        PostgreSQL[(PostgreSQL<br/>Bookings DB)]
        S3[(S3<br/>Images)]
    end

    subgraph "Message Queue"
        Kafka[Kafka Cluster]
    end

    WebApp --> Kong
    MobileApp --> Kong
    PartnerAPI --> Kong

    Kong --> RateLimit
    RateLimit --> Auth
    Auth --> LoadBalancer

    LoadBalancer --> SearchOrchestrator

    SearchOrchestrator --> QueryParser
    QueryParser --> RedisCluster
    RedisCluster --> CacheNode1
    RedisCluster --> CacheNode2
    RedisCluster --> CacheNode3

    SearchOrchestrator --> ESCluster
    ESCluster --> ESMaster
    ESMaster --> ESData1
    ESMaster --> ESData2
    ESMaster --> ESData3

    SearchOrchestrator --> PriceEngine
    SearchOrchestrator --> InventoryService
    SearchOrchestrator --> RecommendationEngine

    PriceEngine --> MongoDB
    InventoryService --> MongoDB
    RecommendationEngine --> PostgreSQL

    ResultAggregator --> PersonalizationEngine
    PersonalizationEngine --> RedisCluster

    MongoDB --> Kafka
    PostgreSQL --> Kafka
    Kafka --> ESCluster

    S3 -.->|CDN| WebApp
    S3 -.->|CDN| MobileApp

    style SearchOrchestrator fill:#4CAF50
    style RedisCluster fill:#FF6B6B
    style ESCluster fill:#FFA726
    style Kafka fill:#42A5F5
```

## 5. Deployment Diagram

```mermaid
graph TB
    subgraph "AWS Region: ap-south-1 (Mumbai)"
        subgraph "VPC"
            subgraph "Public Subnet - AZ1"
                ALB1[Application Load<br/>Balancer]
                NAT1[NAT Gateway]
            end

            subgraph "Public Subnet - AZ2"
                ALB2[Application Load<br/>Balancer]
                NAT2[NAT Gateway]
            end

            subgraph "Private Subnet - AZ1"
                subgraph "EKS Cluster - AZ1"
                    APIGateway1[API Gateway Pods]
                    SearchService1[Search Service Pods]
                    BookingService1[Booking Service Pods]
                end
                RDS1[(RDS PostgreSQL<br/>Primary)]
            end

            subgraph "Private Subnet - AZ2"
                subgraph "EKS Cluster - AZ2"
                    APIGateway2[API Gateway Pods]
                    SearchService2[Search Service Pods]
                    BookingService2[Booking Service Pods]
                end
                RDS2[(RDS PostgreSQL<br/>Read Replica)]
            end

            subgraph "Private Subnet - AZ3"
                ElasticSearch[Elasticsearch<br/>Cluster]
                Redis[ElastiCache<br/>Redis Cluster]
                MSK[Amazon MSK<br/>Kafka Cluster]
            end
        end
    end

    subgraph "Global Services"
        Route53[Route 53<br/>DNS]
        CloudFront[CloudFront CDN]
        S3[S3 Buckets<br/>Images & Assets]
        WAF[AWS WAF]
    end

    subgraph "Monitoring & Operations"
        CloudWatch[CloudWatch]
        Prometheus[Prometheus]
        Grafana[Grafana]
        ELK[ELK Stack]
    end

    Users[End Users] --> Route53
    Route53 --> CloudFront
    CloudFront --> WAF
    WAF --> ALB1
    WAF --> ALB2

    ALB1 --> APIGateway1
    ALB2 --> APIGateway2

    APIGateway1 --> SearchService1
    APIGateway1 --> BookingService1
    APIGateway2 --> SearchService2
    APIGateway2 --> BookingService2

    SearchService1 --> ElasticSearch
    SearchService2 --> ElasticSearch
    SearchService1 --> Redis
    SearchService2 --> Redis

    BookingService1 --> RDS1
    BookingService2 --> RDS2
    RDS1 -.->|Replication| RDS2

    BookingService1 --> MSK
    BookingService2 --> MSK

    CloudFront --> S3

    APIGateway1 --> CloudWatch
    APIGateway2 --> CloudWatch
    SearchService1 --> Prometheus
    SearchService2 --> Prometheus
    Prometheus --> Grafana
    EKS --> ELK

    style ALB1 fill:#FF9800
    style ALB2 fill:#FF9800
    style RDS1 fill:#4CAF50
    style RDS2 fill:#4CAF50
    style ElasticSearch fill:#FFA726
    style Redis fill:#FF6B6B
    style CloudFront fill:#2196F3
```

## 6. State Diagram - Booking Lifecycle

```mermaid
stateDiagram-v2
    [*] --> Draft: User starts booking
    
    Draft --> Validating: Submit booking
    
    Validating --> InventoryLocked: Validation passed
    Validating --> Failed: Validation failed
    
    InventoryLocked --> PaymentPending: Lock acquired
    InventoryLocked --> Failed: Lock timeout
    
    PaymentPending --> PaymentProcessing: Initiate payment
    PaymentPending --> Cancelled: User cancelled
    
    PaymentProcessing --> Confirmed: Payment success
    PaymentProcessing --> Failed: Payment failed
    PaymentProcessing --> PaymentRetrying: Payment timeout
    
    PaymentRetrying --> PaymentProcessing: Retry (max 3)
    PaymentRetrying --> Failed: Max retries exceeded
    
    Confirmed --> Modified: User modifies booking
    Confirmed --> Cancelling: User requests cancel
    Confirmed --> Completed: After check-out date
    
    Modified --> Confirmed: Modification confirmed
    Modified --> Failed: Modification failed
    
    Cancelling --> RefundPending: Cancellation approved
    Cancelling --> Confirmed: Cancellation rejected
    
    RefundPending --> Cancelled: Refund processed
    RefundPending --> Failed: Refund failed
    
    Failed --> [*]
    Cancelled --> [*]
    Completed --> [*]
    
    note right of InventoryLocked
        TTL: 30 seconds
        Auto-release if timeout
    end note
    
    note right of PaymentProcessing
        Idempotent operation
        3D Secure support
    end note
    
    note right of Confirmed
        Send confirmation email
        Update inventory
        Trigger notifications
    end note
```

## 7. Activity Diagram - Dynamic Pricing Calculation

```mermaid
stateDiagram-v2
    [*] --> FetchBasePrice
    
    FetchBasePrice --> CalculateDemandMultiplier
    
    CalculateDemandMultiplier --> CheckInventoryLevel
    note right of CalculateDemandMultiplier
        Use ML model to predict demand
        Historical data + current trends
    end note
    
    CheckInventoryLevel --> CalculateInventoryMultiplier
    
    CalculateInventoryMultiplier --> CheckSeasonality
    note right of CalculateInventoryMultiplier
        If occupancy > 90%: 1.4x
        If occupancy > 75%: 1.25x
        If occupancy > 50%: 1.1x
        Else: 1.0x
    end note
    
    CheckSeasonality --> CalculateSeasonalityMultiplier
    
    CalculateSeasonalityMultiplier --> CheckDayOfWeek
    note right of CalculateSeasonalityMultiplier
        Peak season: 1.5x
        High season: 1.2x
        Regular season: 1.0x
    end note
    
    CheckDayOfWeek --> CalculateDOWMultiplier
    
    CalculateDOWMultiplier --> CheckAdvanceBooking
    note right of CalculateDOWMultiplier
        Weekend: 1.2x
        Weekday: 1.0x
    end note
    
    CheckAdvanceBooking --> CalculateAdvanceMultiplier
    note right of CalculateAdvanceMultiplier
        > 30 days: 0.8x (Early bird)
        > 14 days: 0.9x
        > 7 days: 1.0x
        < 3 days: 1.3x (Last minute)
    end note
    
    CalculateAdvanceMultiplier --> CheckSpecialEvents
    
    CheckSpecialEvents --> ApplyEventMultiplier
    
    ApplyEventMultiplier --> CalculateFinalPrice
    note right of CalculateFinalPrice
        final_price = base_price × 
        demand_mult × inventory_mult × 
        seasonal_mult × dow_mult × 
        advance_mult × event_mult
    end note
    
    CalculateFinalPrice --> ApplyBusinessConstraints
    
    ApplyBusinessConstraints --> ValidateMinMargin
    note right of ApplyBusinessConstraints
        Max discount: 40%
        Max premium: 100%
        Min profit margin: 15%
    end note
    
    ValidateMinMargin --> decision1{Margin OK?}
    
    decision1 --> RoundPrice: Yes
    decision1 --> AdjustPrice: No
    
    AdjustPrice --> RoundPrice
    
    RoundPrice --> CachePrice
    note right of RoundPrice
        Round to nearest 50
        For better UX
    end note
    
    CachePrice --> [*]
```

## 8. ER Diagram - Database Schema (Core Tables)

```mermaid
erDiagram
    USERS ||--o{ BOOKINGS : makes
    USERS ||--o{ REVIEWS : writes
    USERS ||--o{ WISHLISTS : has
    USERS ||--|| LOYALTY_ACCOUNTS : has
    
    BOOKINGS ||--|| PAYMENTS : has
    BOOKINGS ||--o{ BOOKING_TRAVELERS : includes
    BOOKINGS }o--|| HOTELS : for
    BOOKINGS }o--|| FLIGHTS : for
    
    HOTELS ||--o{ ROOMS : contains
    HOTELS ||--o{ REVIEWS : has
    HOTELS ||--o{ HOTEL_AMENITIES : has
    HOTELS }o--|| LOCATIONS : located_at
    
    ROOMS ||--o{ ROOM_INVENTORY : has
    ROOMS ||--o{ BOOKING_ROOMS : booked_in
    
    FLIGHTS ||--o{ FLIGHT_SEGMENTS : contains
    FLIGHTS ||--o{ SEATS : has
    FLIGHTS }o--|| AIRLINES : operated_by
    
    SEATS ||--o{ SEAT_INVENTORY : has
    SEATS ||--o{ BOOKING_SEATS : booked_in
    
    PAYMENTS ||--o{ PAYMENT_TRANSACTIONS : has
    PAYMENTS }o--|| PAYMENT_METHODS : uses
    
    COUPONS ||--o{ BOOKING_COUPONS : applied_to
    
    USERS {
        uuid user_id PK
        string email UK
        string phone UK
        string password_hash
        string first_name
        string last_name
        date date_of_birth
        timestamp created_at
        timestamp updated_at
    }
    
    BOOKINGS {
        uuid booking_id PK
        uuid user_id FK
        string booking_type
        string status
        decimal total_amount
        date booking_date
        timestamp created_at
        timestamp updated_at
        int version
    }
    
    HOTELS {
        uuid hotel_id PK
        string name
        text description
        uuid location_id FK
        decimal latitude
        decimal longitude
        decimal rating
        int total_rooms
        timestamp created_at
        timestamp updated_at
    }
    
    ROOMS {
        uuid room_id PK
        uuid hotel_id FK
        string room_type
        int capacity
        decimal base_price
        int total_count
        timestamp created_at
        timestamp updated_at
    }
    
    ROOM_INVENTORY {
        uuid inventory_id PK
        uuid room_id FK
        date date
        int available_count
        int booked_count
        decimal current_price
        timestamp updated_at
    }
    
    FLIGHTS {
        uuid flight_id PK
        string flight_number
        uuid airline_id FK
        string origin
        string destination
        timestamp departure_time
        timestamp arrival_time
        int duration_minutes
        int stops
        timestamp created_at
    }
    
    SEATS {
        uuid seat_id PK
        uuid flight_id FK
        string seat_number
        string seat_class
        string seat_type
        decimal base_price
    }
    
    SEAT_INVENTORY {
        uuid inventory_id PK
        uuid seat_id FK
        date date
        boolean is_available
        decimal current_price
        timestamp updated_at
    }
    
    PAYMENTS {
        uuid payment_id PK
        uuid booking_id FK
        decimal amount
        string currency
        string method
        string status
        string transaction_id
        timestamp payment_date
        text gateway_response
    }
    
    REVIEWS {
        uuid review_id PK
        uuid user_id FK
        uuid item_id FK
        string item_type
        decimal rating
        string title
        text comment
        timestamp created_at
        timestamp updated_at
    }
    
    COUPONS {
        uuid coupon_id PK
        string coupon_code UK
        string discount_type
        decimal discount_value
        decimal min_order_value
        date valid_from
        date valid_until
        int usage_limit
        int used_count
    }
```

---

## Summary

These UML diagrams provide a comprehensive view of the MakeMyTrip system:

1. **Class Diagram**: Shows the core domain model with entities and relationships
2. **Sequence Diagrams**: Illustrate the request flows for search and booking
3. **Component Diagram**: Depicts the search service architecture and interactions
4. **Deployment Diagram**: Shows the AWS infrastructure and deployment topology
5. **State Diagram**: Represents the booking lifecycle and state transitions
6. **Activity Diagram**: Details the dynamic pricing calculation workflow
7. **ER Diagram**: Presents the database schema and relationships

These diagrams complement the detailed system design document and provide visual representations of the architecture, flows, and data models.
