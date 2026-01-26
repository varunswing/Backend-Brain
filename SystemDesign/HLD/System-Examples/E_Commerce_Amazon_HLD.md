# E-Commerce (Amazon) - High Level Design

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

1. **Product Catalog**
   - Browse products by category
   - Search products
   - Product details with images
   - Reviews and ratings
   - Inventory tracking

2. **Shopping Cart**
   - Add/remove items
   - Update quantities
   - Persistent cart (logged in users)
   - Guest cart (session-based)

3. **Order Management**
   - Place orders
   - Order tracking
   - Order history
   - Returns and refunds

4. **Payment Processing**
   - Multiple payment methods
   - Secure transactions
   - Split payments

5. **User Management**
   - Registration/login
   - Address book
   - Wishlists
   - Recommendations

6. **Seller Platform**
   - Product listing
   - Inventory management
   - Sales analytics

### Non-Functional Requirements

1. **Scale**: 300M+ active customers, 12M+ products
2. **Availability**: 99.99% uptime (especially during sales)
3. **Latency**: < 200ms for search, < 500ms for checkout
4. **Consistency**: Strong consistency for inventory/orders
5. **Peak handling**: 10-100x normal traffic (Prime Day, Black Friday)

---

## Capacity Estimation

### Traffic Estimates

```
Users:
- Monthly Active Users: 300 Million
- Daily Active Users: 150 Million
- Concurrent users (peak): 50 Million

Operations:
- Product views/day: 5 Billion
- Searches/day: 1 Billion
- Add to cart/day: 500 Million
- Orders/day: 50 Million (normal), 500 Million (peak)

Requests per second:
- Product views: 60,000/sec
- Searches: 12,000/sec
- Checkout: 600/sec (normal), 6,000/sec (peak)
```

### Storage Estimates

```
Products:
- Total products: 350 Million
- Per product: 100 KB (metadata + descriptions)
- Total: 35 TB

Product Images:
- 10 images per product average
- 500 KB average per image
- Total: 350M * 10 * 500KB = 1.75 PB

Orders:
- 50M orders/day * 365 * 5 years = 90 Billion orders
- 5 KB per order
- Total: 450 TB

User Data:
- 300M users * 10 KB = 3 TB
```

### Bandwidth Estimates

```
Product pages:
- 60,000 req/sec * 500 KB = 30 GB/sec (mostly images from CDN)

Search:
- 12,000 req/sec * 50 KB = 600 MB/sec

API:
- 100,000 req/sec * 5 KB = 500 MB/sec
```

---

## High-Level Design

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                   CLIENTS                                            │
│                      (Web, iOS, Android, Alexa, Third-party)                        │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              CDN (CloudFront)                                        │
│                        (Static assets, product images)                              │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              API GATEWAY                                             │
│                  (Rate limiting, Auth, Request routing)                             │
└─────────────────────────────────────────────────────────────────────────────────────┘
                                         │
              ┌──────────────────────────┼──────────────────────────┐
              ▼                          ▼                          ▼
┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────────────┐
│   PRODUCT SERVICE    │   │    USER SERVICE      │   │     SEARCH SERVICE           │
│                      │   │                      │   │                              │
│  - Catalog mgmt      │   │  - Authentication    │   │  - Elasticsearch             │
│  - Categories        │   │  - User profiles     │   │  - Autocomplete              │
│  - Reviews           │   │  - Addresses         │   │  - Filters/facets            │
└──────────────────────┘   └──────────────────────┘   └──────────────────────────────┘

┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────────────┐
│    CART SERVICE      │   │   ORDER SERVICE      │   │   INVENTORY SERVICE          │
│                      │   │                      │   │                              │
│  - Cart CRUD         │   │  - Order creation    │   │  - Stock levels              │
│  - Price calculation │   │  - Order tracking    │   │  - Reservations              │
│  - Promotions        │   │  - Fulfillment       │   │  - Warehouse allocation      │
└──────────────────────┘   └──────────────────────┘   └──────────────────────────────┘

┌──────────────────────┐   ┌──────────────────────┐   ┌──────────────────────────────┐
│   PAYMENT SERVICE    │   │  NOTIFICATION SVC    │   │  RECOMMENDATION SERVICE      │
│                      │   │                      │   │                              │
│  - Payment gateway   │   │  - Email             │   │  - Personalization           │
│  - Fraud detection   │   │  - SMS               │   │  - "Customers also bought"   │
│  - Refunds           │   │  - Push              │   │  - Recently viewed           │
└──────────────────────┘   └──────────────────────┘   └──────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              MESSAGE QUEUE (Kafka/SQS)                               │
│                                                                                     │
│  Topics: orders, inventory-updates, payments, notifications, analytics              │
└─────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                  DATA LAYER                                          │
├──────────────────┬──────────────────┬──────────────────┬────────────────────────────┤
│   PRODUCTS DB    │    ORDERS DB     │  INVENTORY DB    │        CACHE               │
│  (DynamoDB/      │  (Aurora/        │  (DynamoDB)      │     (ElastiCache)          │
│   MongoDB)       │   PostgreSQL)    │                  │                            │
├──────────────────┼──────────────────┼──────────────────┼────────────────────────────┤
│  USERS DB        │   SEARCH INDEX   │   ANALYTICS      │   SESSION STORE            │
│  (Aurora)        │  (Elasticsearch) │  (Redshift)      │     (Redis)                │
└──────────────────┴──────────────────┴──────────────────┴────────────────────────────┘
```

### Core Microservices

1. **Product Service**: Catalog, categories, reviews
2. **Search Service**: Full-text search, filtering
3. **Cart Service**: Shopping cart management
4. **Order Service**: Order lifecycle
5. **Inventory Service**: Stock management
6. **Payment Service**: Transaction processing
7. **User Service**: Authentication, profiles
8. **Recommendation Service**: Personalization

---

## Request Flows

### Product Search Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│ Client │  │   API   │  │ Search  │  │ Elastic   │  │ Product │  │  Cache  │
│        │  │ Gateway │  │ Service │  │ search    │  │ Service │  │         │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ GET /search?q=laptop    │             │             │            │
    │───────────>│            │             │             │            │
    │            │───────────>│             │             │            │
    │            │            │ Query       │             │            │
    │            │            │────────────>│             │            │
    │            │            │             │             │            │
    │            │            │<────────────│             │            │
    │            │            │ Product IDs │             │            │
    │            │            │             │             │            │
    │            │            │ Get product details       │            │
    │            │            │───────────────────────────────────────>│
    │            │            │             │             │ Cache miss │
    │            │            │             │             │◄───────────│
    │            │            │◄────────────────────────────────────────
    │            │            │             │             │            │
    │            │            │ Apply ranking, filters    │            │
    │            │            │             │             │            │
    │<───────────│<───────────│             │             │            │
    │ Search results + facets │             │             │            │
```

### Checkout Flow

```
┌────────┐  ┌─────────┐  ┌─────────┐  ┌───────────┐  ┌─────────┐  ┌─────────┐
│ Client │  │  Order  │  │Inventory│  │  Payment  │  │Notifica │  │ Fulfil  │
│        │  │ Service │  │ Service │  │  Service  │  │  tion   │  │  ment   │
└───┬────┘  └────┬────┘  └────┬────┘  └─────┬─────┘  └────┬────┘  └────┬────┘
    │            │            │             │             │            │
    │ POST /checkout          │             │             │            │
    │───────────>│            │             │             │            │
    │            │ Reserve inventory        │             │            │
    │            │───────────>│             │             │            │
    │            │            │ Check stock │             │            │
    │            │            │ & reserve   │             │            │
    │            │<───────────│ Reserved    │             │            │
    │            │            │             │             │            │
    │            │ Process payment          │             │            │
    │            │──────────────────────────>│             │            │
    │            │            │             │ Charge      │            │
    │            │            │             │ customer    │            │
    │            │<──────────────────────────│ Success    │            │
    │            │            │             │             │            │
    │            │ Confirm reservation       │             │            │
    │            │───────────>│             │             │            │
    │            │            │             │             │            │
    │            │ Create order              │             │            │
    │            │ (write to DB)             │             │            │
    │            │            │             │             │            │
    │            │ Send confirmation         │             │            │
    │            │─────────────────────────────────────>│            │
    │            │            │             │             │            │
    │            │ Queue for fulfillment    │             │            │
    │            │──────────────────────────────────────────────────>│
    │            │            │             │             │            │
    │<───────────│            │             │             │            │
    │ Order confirmation       │             │             │            │
```

### Inventory Reservation Pattern

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          TWO-PHASE INVENTORY RESERVATION                             │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Phase 1: RESERVE (Soft Lock)                                                       │
│                                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                                │ │
│  │  Product: iPhone 15                                                           │ │
│  │  Available: 100                                                               │ │
│  │  Reserved: 0                                                                  │ │
│  │                                                                                │ │
│  │  User adds to cart → Reserve 1                                                │ │
│  │                                                                                │ │
│  │  Available: 100 (unchanged)                                                   │ │
│  │  Reserved: 1                                                                  │ │
│  │  Sellable: Available - Reserved = 99                                          │ │
│  │                                                                                │ │
│  │  Reservation expires in 15 minutes                                            │ │
│  │                                                                                │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                     │
│  Phase 2: CONFIRM or RELEASE                                                        │
│                                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────────────┐ │
│  │                                                                                │ │
│  │  On successful payment:                                                       │ │
│  │  CONFIRM → Available: 99, Reserved: 0, Sold: +1                              │ │
│  │                                                                                │ │
│  │  On timeout or cancellation:                                                  │ │
│  │  RELEASE → Available: 100, Reserved: 0                                        │ │
│  │                                                                                │ │
│  └───────────────────────────────────────────────────────────────────────────────┘ │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Detailed Component Design

### 1. Inventory Management System

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          INVENTORY ARCHITECTURE                                      │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Multi-warehouse inventory:                                                         │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Inventory Record:                                                           │   │
│  │  {                                                                           │   │
│  │    "product_id": "B09V3KXJPB",                                             │   │
│  │    "warehouse_id": "FTW1",                                                  │   │
│  │    "available_qty": 500,                                                    │   │
│  │    "reserved_qty": 23,                                                      │   │
│  │    "incoming_qty": 1000,  // Expected from supplier                        │   │
│  │    "incoming_date": "2024-01-20"                                           │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  │  Aggregated view:                                                            │   │
│  │  Product B09V3KXJPB:                                                        │   │
│  │  ├── FTW1 (Texas): 500 available                                           │   │
│  │  ├── ONT6 (California): 200 available                                      │   │
│  │  ├── BOS7 (Boston): 150 available                                          │   │
│  │  └── Total sellable: 850                                                   │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Inventory operations (DynamoDB transactions):                                      │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  // Reserve inventory (atomic)                                               │   │
│  │  TransactWriteItems:                                                         │   │
│  │    - ConditionCheck: available_qty >= requested_qty                         │   │
│  │    - Update: reserved_qty += requested_qty                                  │   │
│  │    - Put: reservation record with TTL                                       │   │
│  │                                                                              │   │
│  │  // Confirm reservation (atomic)                                             │   │
│  │  TransactWriteItems:                                                         │   │
│  │    - Update: available_qty -= qty, reserved_qty -= qty                      │   │
│  │    - Delete: reservation record                                             │   │
│  │    - Put: inventory movement record                                         │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Warehouse selection algorithm:                                                     │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  def select_warehouse(product_id, ship_to_address, qty):                    │   │
│  │      warehouses = get_warehouses_with_stock(product_id, qty)                │   │
│  │                                                                              │   │
│  │      scored = []                                                             │   │
│  │      for wh in warehouses:                                                   │   │
│  │          score = 0                                                           │   │
│  │          # Distance (shipping cost)                                         │   │
│  │          score -= distance(wh.location, ship_to_address) * 0.5             │   │
│  │          # Delivery speed                                                   │   │
│  │          score -= wh.estimated_days * 10                                    │   │
│  │          # Current load (balance utilization)                               │   │
│  │          score -= wh.current_utilization * 0.3                             │   │
│  │          # Stock level (prioritize high stock)                              │   │
│  │          score += log(wh.stock_level) * 0.2                                │   │
│  │          scored.append((wh, score))                                         │   │
│  │                                                                              │   │
│  │      return max(scored, key=lambda x: x[1])[0]                             │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 2. Product Search System

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                          SEARCH ARCHITECTURE                                         │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Elasticsearch Index Design:                                                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  {                                                                           │   │
│  │    "mappings": {                                                             │   │
│  │      "properties": {                                                         │   │
│  │        "product_id": {"type": "keyword"},                                   │   │
│  │        "title": {                                                           │   │
│  │          "type": "text",                                                    │   │
│  │          "analyzer": "product_analyzer",                                    │   │
│  │          "fields": {"keyword": {"type": "keyword"}}                        │   │
│  │        },                                                                   │   │
│  │        "description": {"type": "text"},                                     │   │
│  │        "brand": {"type": "keyword"},                                        │   │
│  │        "category": {"type": "keyword"},                                     │   │
│  │        "category_path": {"type": "keyword"},  // Electronics > Phones      │   │
│  │        "price": {"type": "float"},                                          │   │
│  │        "rating": {"type": "float"},                                         │   │
│  │        "review_count": {"type": "integer"},                                 │   │
│  │        "sales_rank": {"type": "integer"},                                   │   │
│  │        "attributes": {                                                       │   │
│  │          "type": "nested",                                                  │   │
│  │          "properties": {                                                    │   │
│  │            "name": {"type": "keyword"},                                     │   │
│  │            "value": {"type": "keyword"}                                     │   │
│  │          }                                                                  │   │
│  │        },                                                                   │   │
│  │        "in_stock": {"type": "boolean"},                                     │   │
│  │        "prime_eligible": {"type": "boolean"}                                │   │
│  │      }                                                                       │   │
│  │    }                                                                         │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Search query with boosting:                                                        │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  {                                                                           │   │
│  │    "query": {                                                               │   │
│  │      "function_score": {                                                    │   │
│  │        "query": {                                                           │   │
│  │          "bool": {                                                          │   │
│  │            "must": [                                                        │   │
│  │              {"multi_match": {                                              │   │
│  │                "query": "wireless headphones",                              │   │
│  │                "fields": ["title^3", "brand^2", "description"]             │   │
│  │              }}                                                             │   │
│  │            ],                                                               │   │
│  │            "filter": [                                                      │   │
│  │              {"term": {"in_stock": true}},                                 │   │
│  │              {"range": {"price": {"lte": 200}}}                            │   │
│  │            ]                                                                │   │
│  │          }                                                                  │   │
│  │        },                                                                   │   │
│  │        "functions": [                                                       │   │
│  │          {"field_value_factor": {                                          │   │
│  │            "field": "sales_rank",                                          │   │
│  │            "modifier": "log1p",                                            │   │
│  │            "factor": 0.1                                                   │   │
│  │          }},                                                               │   │
│  │          {"filter": {"term": {"prime_eligible": true}},                   │   │
│  │           "weight": 1.5}                                                   │   │
│  │        ]                                                                    │   │
│  │      }                                                                      │   │
│  │    },                                                                       │   │
│  │    "aggs": {                                                                │   │
│  │      "brands": {"terms": {"field": "brand"}},                              │   │
│  │      "price_ranges": {"range": {"field": "price", "ranges": [...]}}       │   │
│  │    }                                                                        │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Shopping Cart Design

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              CART ARCHITECTURE                                       │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│  Cart Storage (Redis):                                                              │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  Key: cart:{user_id}                                                        │   │
│  │  Type: Hash                                                                  │   │
│  │                                                                              │   │
│  │  {                                                                           │   │
│  │    "product_123": {"qty": 2, "added_at": 1705312800, "price": 29.99},      │   │
│  │    "product_456": {"qty": 1, "added_at": 1705312900, "price": 149.99}      │   │
│  │  }                                                                           │   │
│  │                                                                              │   │
│  │  TTL: 30 days for logged-in users                                           │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Cart merge (guest → logged in):                                                   │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  def merge_carts(guest_cart_id, user_id):                                   │   │
│  │      guest_cart = redis.hgetall(f"cart:{guest_cart_id}")                   │   │
│  │      user_cart = redis.hgetall(f"cart:{user_id}")                          │   │
│  │                                                                              │   │
│  │      for product_id, item in guest_cart.items():                            │   │
│  │          if product_id in user_cart:                                        │   │
│  │              # Keep higher quantity                                         │   │
│  │              user_cart[product_id].qty = max(                               │   │
│  │                  user_cart[product_id].qty,                                 │   │
│  │                  item.qty                                                   │   │
│  │              )                                                               │   │
│  │          else:                                                               │   │
│  │              user_cart[product_id] = item                                   │   │
│  │                                                                              │   │
│  │      redis.hmset(f"cart:{user_id}", user_cart)                             │   │
│  │      redis.delete(f"cart:{guest_cart_id}")                                 │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
│  Price calculation:                                                                 │
│                                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                                                                              │   │
│  │  def calculate_cart_total(cart):                                             │   │
│  │      subtotal = 0                                                            │   │
│  │      for item in cart.items:                                                │   │
│  │          # Get current price (may have changed)                             │   │
│  │          current_price = product_service.get_price(item.product_id)        │   │
│  │          subtotal += current_price * item.qty                               │   │
│  │                                                                              │   │
│  │      # Apply promotions                                                      │   │
│  │      discount = promotion_service.calculate_discount(cart)                  │   │
│  │                                                                              │   │
│  │      # Calculate shipping                                                   │   │
│  │      shipping = shipping_service.calculate(cart, address)                   │   │
│  │                                                                              │   │
│  │      # Calculate tax                                                         │   │
│  │      tax = tax_service.calculate(subtotal - discount, address)             │   │
│  │                                                                              │   │
│  │      return {                                                                │   │
│  │          "subtotal": subtotal,                                              │   │
│  │          "discount": discount,                                              │   │
│  │          "shipping": shipping,                                              │   │
│  │          "tax": tax,                                                        │   │
│  │          "total": subtotal - discount + shipping + tax                      │   │
│  │      }                                                                       │   │
│  │                                                                              │   │
│  └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## Trade-offs and Tech Choices

### 1. Database Selection

| Component | Database | Reason |
|-----------|----------|--------|
| Products | DynamoDB | Scale, flexible schema |
| Orders | Aurora PostgreSQL | ACID, complex queries |
| Inventory | DynamoDB | High throughput, transactions |
| Users | Aurora PostgreSQL | Relations, compliance |
| Cart | Redis | Speed, TTL |
| Search | Elasticsearch | Full-text, facets |

### 2. Consistency Models

| Operation | Consistency | Reason |
|-----------|-------------|--------|
| Inventory | Strong | Prevent oversell |
| Orders | Strong | Financial accuracy |
| Product catalog | Eventual | Scale, tolerance for staleness |
| Search index | Eventual | Async update is OK |
| Reviews | Eventual | Not critical |

### 3. Caching Strategy

```
┌─────────────────────────────────────────────────────────────────┐
│                 CACHING LAYERS                                   │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Layer 1: CDN (CloudFront)                                      │
│  - Static assets (images, CSS, JS)                              │
│  - Product images                                               │
│  - TTL: Hours to days                                           │
│                                                                 │
│  Layer 2: Application Cache (ElastiCache/Redis)                 │
│  - Product details: TTL 5 min                                   │
│  - Category listings: TTL 1 min                                 │
│  - Price: TTL 1 min (or invalidate on change)                  │
│  - User sessions                                                │
│  - Shopping carts                                               │
│                                                                 │
│  Layer 3: Database Query Cache                                  │
│  - Complex aggregations                                         │
│  - Recommendation results                                       │
│                                                                 │
│  Cache Invalidation:                                            │
│  - Product update → Invalidate product cache                   │
│  - Price change → Invalidate price, broadcast to carts         │
│  - Inventory change → Update search index async                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Failure Scenarios and Bottlenecks

### 1. Flash Sale / Prime Day

```
┌─────────────────────────────────────────────────────────────────┐
│              HIGH TRAFFIC HANDLING                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Problem: 100x traffic spike in minutes                         │
│                                                                 │
│  Preparation:                                                    │
│  1. Pre-warm caches with sale items                             │
│  2. Pre-scale all services (known event)                        │
│  3. Enable queue-based checkout (virtual waiting room)          │
│  4. Degrade non-essential features                              │
│                                                                 │
│  Queue-based checkout:                                           │
│                                                                 │
│  User → Queue → Wait page → Checkout when slot available       │
│                                                                 │
│  Implementation:                                                 │
│  - Rate limit checkout: 1000/sec                                │
│  - Excess requests get queue position                           │
│  - WebSocket updates position                                   │
│  - FIFO with priority for Prime members                        │
│                                                                 │
│  Degradation levels:                                            │
│  L1: Disable recommendations                                    │
│  L2: Cache product pages longer                                 │
│  L3: Disable reviews on listings                                │
│  L4: Static product pages                                       │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 2. Inventory Oversell

```
Problem: Sell more than available stock

Prevention:
1. Optimistic locking on inventory updates
2. DynamoDB transactions for reserve → confirm
3. Buffer stock (show 95% of actual)
4. Async inventory reconciliation

If oversold:
1. Detect within minutes (reconciliation job)
2. Priority: Fulfill older orders first
3. Notify affected customers immediately
4. Offer alternatives or compensation
```

### 3. Payment Failures

```
┌─────────────────────────────────────────────────────────────────┐
│               PAYMENT FAILURE HANDLING                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Saga pattern for checkout:                                      │
│                                                                 │
│  1. Reserve inventory    ───► Success                           │
│  2. Process payment      ───► FAILURE                           │
│  3. Compensate: Release inventory                               │
│                                                                 │
│  State machine:                                                  │
│  CREATED → INVENTORY_RESERVED → PAYMENT_PENDING                 │
│         ↓                    ↓                                   │
│     CANCELLED           PAYMENT_FAILED                          │
│                              ↓                                   │
│                      INVENTORY_RELEASED                         │
│                                                                 │
│  If payment gateway timeout:                                    │
│  - Mark payment as UNKNOWN                                      │
│  - Query gateway for status                                     │
│  - Reconciliation job handles edge cases                        │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Future Improvements

### 1. ML-Powered Features

- Dynamic pricing based on demand
- Fraud detection improvement
- Delivery time prediction
- Visual search (image → products)

### 2. Voice Commerce

- Alexa integration
- Conversational shopping
- Voice-based reordering

### 3. Sustainability

- Carbon-neutral delivery options
- Packaging optimization
- Consolidated shipments

---

## Interviewer Questions & Answers

### Q1: How do you handle inventory for a flash sale with limited stock?

**Answer:**

**Problem:** 1000 items, 1 million users trying to buy at 12:00:00

**Solution: Distributed counting with queuing**

```python
class FlashSaleInventory:
    def __init__(self, product_id, total_stock):
        self.product_id = product_id
        # Pre-allocate stock to multiple shards
        self.shards = 10
        self.stock_per_shard = total_stock // self.shards
        
        for i in range(self.shards):
            redis.set(f"flash:{product_id}:shard:{i}", self.stock_per_shard)
    
    def try_reserve(self, user_id):
        # Hash user to shard (consistent)
        shard = hash(user_id) % self.shards
        
        # Atomic decrement
        remaining = redis.decr(f"flash:{product_id}:shard:{shard}")
        
        if remaining >= 0:
            return ReservationSuccess(user_id)
        else:
            # Try other shards
            for other_shard in range(self.shards):
                if other_shard != shard:
                    remaining = redis.decr(f"flash:{product_id}:shard:{other_shard}")
                    if remaining >= 0:
                        return ReservationSuccess(user_id)
            
            return SoldOut()
```

**Additional protections:**
- Rate limit per user (1 request/sec)
- CAPTCHA to prevent bots
- Queue with random delay (fairness)
- Oversell buffer (reserve 950, show sold out at 1000)

---

### Q2: Design the order fulfillment system.

**Answer:**

**Order states:**
```
CREATED → PAYMENT_CONFIRMED → PICKING → PACKING → SHIPPED → DELIVERED
                ↓
            CANCELLED
```

**Warehouse assignment:**
```python
async def assign_fulfillment(order):
    for item in order.items:
        # Find best warehouse
        warehouses = await get_warehouses_with_stock(
            item.product_id,
            item.quantity
        )
        
        best = select_optimal_warehouse(
            warehouses,
            order.shipping_address,
            order.delivery_preference  # Speed vs cost
        )
        
        # Create pick task
        await create_pick_task(
            warehouse=best,
            product_id=item.product_id,
            quantity=item.quantity,
            order_id=order.id
        )

def select_optimal_warehouse(warehouses, address, preference):
    scored = []
    for wh in warehouses:
        score = 0
        
        distance = calculate_distance(wh.location, address)
        delivery_days = estimate_delivery(wh, address)
        
        if preference == "SPEED":
            score = -delivery_days * 100 - distance * 0.1
        else:  # COST
            score = -shipping_cost(wh, address) - distance * 0.5
        
        scored.append((wh, score))
    
    return max(scored, key=lambda x: x[1])[0]
```

**Split shipment handling:**
- If items in different warehouses, split order
- Each shipment tracked independently
- Consolidate at hub if cost-effective

---

### Q3: How do you design the product recommendation system?

**Answer:**

**Multiple recommendation types:**

1. **Collaborative filtering** ("Customers who bought X also bought Y")
```python
# Item-based collaborative filtering
def get_similar_products(product_id, limit=10):
    # Co-purchase matrix (precomputed)
    co_purchases = item_similarity_matrix[product_id]
    
    # Sort by similarity score
    similar = sorted(co_purchases.items(), key=lambda x: x[1], reverse=True)
    
    return [p for p, score in similar[:limit]]
```

2. **Content-based** (Similar attributes)
```python
def get_similar_by_content(product_id, limit=10):
    product = get_product(product_id)
    
    # Search for similar attributes
    query = {
        "query": {
            "bool": {
                "should": [
                    {"term": {"category": product.category}},
                    {"term": {"brand": product.brand}},
                    {"terms": {"attributes": product.attributes}}
                ],
                "must_not": [
                    {"term": {"product_id": product_id}}
                ]
            }
        }
    }
    
    return search(query)[:limit]
```

3. **Personalized** (User history)
```python
def personalized_recommendations(user_id, limit=20):
    # Get user embeddings (from view/purchase history)
    user_embedding = get_user_embedding(user_id)
    
    # Find similar products using ANN
    candidates = vector_search(user_embedding, k=100)
    
    # Re-rank with business rules
    ranked = rerank(candidates, user_id)
    
    return ranked[:limit]
```

---

### Q4: How do you handle pricing and promotions?

**Answer:**

**Price hierarchy:**
```
Base Price → Sale Price → Coupon → Loyalty Discount → Final Price
```

**Promotion engine:**
```python
class PromotionEngine:
    def apply_promotions(self, cart, user):
        applicable = []
        
        # Find all applicable promotions
        for promo in self.get_active_promotions():
            if promo.is_applicable(cart, user):
                applicable.append(promo)
        
        # Sort by priority (some stack, some exclusive)
        applicable.sort(key=lambda p: p.priority)
        
        # Apply in order
        discount = 0
        for promo in applicable:
            if promo.exclusive and discount > 0:
                continue  # Skip if exclusive and others applied
            
            promo_discount = promo.calculate_discount(cart)
            discount += promo_discount
            
            if promo.exclusive:
                break  # Don't apply others
        
        return discount

class Promotion:
    types = ["PERCENTAGE", "FIXED_AMOUNT", "BOGO", "BUNDLE"]
    
    def is_applicable(self, cart, user):
        # Check conditions
        if self.min_purchase and cart.subtotal < self.min_purchase:
            return False
        if self.user_segment and user.segment not in self.user_segment:
            return False
        if self.product_ids and not any(p in cart for p in self.product_ids):
            return False
        return True
```

**Real-time price updates:**
- WebSocket for cart price changes
- Event-driven: price change → notify all carts containing product

---

### Q5: Design the returns and refunds system.

**Answer:**

**Return states:**
```
REQUESTED → APPROVED → SHIPPED_BACK → RECEIVED → INSPECTED → REFUNDED
              ↓                                       ↓
           REJECTED                              PARTIAL_REFUND
```

**Implementation:**
```python
class ReturnsService:
    async def initiate_return(self, order_id, items, reason):
        order = await get_order(order_id)
        
        # Validate return window
        if days_since(order.delivered_at) > 30:
            raise ReturnWindowExpired()
        
        # Check return eligibility
        for item in items:
            if not item.product.returnable:
                raise ItemNotReturnable(item.product_id)
        
        # Create return
        return_order = Return(
            order_id=order_id,
            items=items,
            reason=reason,
            status="REQUESTED"
        )
        
        # Auto-approve if meets criteria
        if self.can_auto_approve(return_order):
            return_order.status = "APPROVED"
            await generate_return_label(return_order)
            await send_notification(return_order.user, "return_approved")
        else:
            await queue_for_review(return_order)
        
        return return_order
    
    async def process_refund(self, return_order):
        # Determine refund amount
        if return_order.inspection_result == "PASS":
            refund_amount = return_order.original_amount
        else:
            # Partial refund for damaged items
            refund_amount = calculate_partial_refund(return_order)
        
        # Process refund to original payment method
        await payment_service.refund(
            order_id=return_order.order_id,
            amount=refund_amount,
            payment_method=return_order.original_payment
        )
        
        # Update inventory
        await inventory_service.add_stock(
            return_order.items,
            warehouse="returns_center"
        )
```

---

### Q6: How do you ensure data consistency across services?

**Answer:**

**Pattern: Saga with event sourcing**

```python
class CheckoutSaga:
    def __init__(self, order_id):
        self.order_id = order_id
        self.state = "STARTED"
        self.events = []
    
    async def execute(self, cart, payment_info):
        try:
            # Step 1: Validate cart
            await self.validate_cart(cart)
            
            # Step 2: Reserve inventory
            reservation = await self.reserve_inventory(cart)
            self.events.append(InventoryReserved(reservation))
            
            # Step 3: Process payment
            payment = await self.process_payment(payment_info, cart.total)
            self.events.append(PaymentProcessed(payment))
            
            # Step 4: Create order
            order = await self.create_order(cart, payment)
            self.events.append(OrderCreated(order))
            
            # Step 5: Confirm inventory
            await self.confirm_inventory(reservation)
            self.events.append(InventoryConfirmed(reservation))
            
            self.state = "COMPLETED"
            return order
            
        except Exception as e:
            await self.compensate()
            raise
    
    async def compensate(self):
        # Reverse in opposite order
        for event in reversed(self.events):
            if isinstance(event, PaymentProcessed):
                await payment_service.refund(event.payment_id)
            elif isinstance(event, InventoryReserved):
                await inventory_service.release(event.reservation_id)
        
        self.state = "COMPENSATED"
```

**Eventual consistency handling:**
- Idempotency keys for all operations
- Outbox pattern for reliable event publishing
- Reconciliation jobs for detecting inconsistencies

---

### Q7: How do you handle search ranking?

**Answer:**

**Multi-factor ranking:**

```python
def calculate_search_score(product, query, user):
    score = 0
    
    # Text relevance (Elasticsearch BM25)
    score += product.text_score * 100
    
    # Sales performance
    score += log(product.sales_last_30_days + 1) * 10
    
    # Rating quality
    if product.review_count > 10:
        score += product.rating * 5
    
    # Availability
    if product.in_stock:
        score += 20
    
    # Prime eligibility (for Prime members)
    if user.is_prime and product.prime_eligible:
        score += 15
    
    # Sponsored boost (ads)
    if product.is_sponsored:
        score += product.bid_amount * 0.5
    
    # Personalization
    if product.category in user.preferred_categories:
        score += 10
    
    # Freshness (for fashion, tech)
    if is_freshness_category(product.category):
        days_old = (now() - product.created_at).days
        score -= min(days_old * 0.1, 20)
    
    return score
```

**A/B testing ranking:**
- 10% traffic gets experimental ranking
- Measure conversion rate, GMV
- Graduate winners to production

---

### Q8: Design the seller platform and product listing.

**Answer:**

**Listing workflow:**
```
Draft → Submitted → Under Review → Published/Rejected
           ↓
    Auto-approved (trusted sellers)
```

**Implementation:**
```python
class ProductListingService:
    async def create_listing(self, seller_id, product_data):
        # Validate product data
        validation = await self.validate(product_data)
        if not validation.is_valid:
            raise InvalidProductData(validation.errors)
        
        # Check for duplicates
        duplicate = await self.find_duplicate(product_data)
        if duplicate:
            return SuggestMatchExisting(duplicate)
        
        # Create listing
        listing = ProductListing(
            seller_id=seller_id,
            status="DRAFT",
            **product_data
        )
        
        # Run automated checks
        auto_checks = await self.run_auto_checks(listing)
        
        if auto_checks.all_passed and seller.is_trusted:
            listing.status = "PUBLISHED"
            await self.index_for_search(listing)
        else:
            listing.status = "UNDER_REVIEW"
            await self.queue_for_moderation(listing)
        
        return listing
    
    async def run_auto_checks(self, listing):
        checks = []
        
        # Image quality
        checks.append(await image_service.check_quality(listing.images))
        
        # Prohibited content
        checks.append(await content_filter.check(listing.title, listing.description))
        
        # Price reasonableness
        similar = await self.find_similar_products(listing)
        checks.append(self.check_price_range(listing.price, similar))
        
        # Brand authorization (if branded)
        if listing.brand:
            checks.append(await brand_registry.check_authorization(
                seller_id, listing.brand
            ))
        
        return CheckResults(checks)
```

---

### Q9: How do you handle geographic expansion?

**Answer:**

**Multi-region architecture:**

```
┌─────────────────────────────────────────────────────────────────┐
│                   GLOBAL ARCHITECTURE                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Region-specific:                                                │
│  - Inventory (must be local)                                    │
│  - Orders (compliance, latency)                                 │
│  - Payment processing (local regulations)                       │
│  - Search (low latency)                                         │
│                                                                 │
│  Global (replicated):                                           │
│  - Product catalog (eventual consistency)                       │
│  - User authentication                                          │
│  - Seller data                                                  │
│                                                                 │
│  Data residency:                                                 │
│  - EU users → EU data centers                                   │
│  - Order data stays in region                                   │
│  - Cross-region shipping handled at edge                        │
│                                                                 │
│  Currency/localization:                                         │
│  - Prices stored in base currency (USD)                         │
│  - Converted at display time                                    │
│  - Tax calculated per region                                    │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Expansion checklist:**
1. Local payment methods
2. Language/currency support
3. Tax compliance
4. Warehouse partnerships
5. Last-mile delivery
6. Customer support in timezone

---

### Q10: How do you prevent fraud?

**Answer:**

**Multi-layer fraud detection:**

```python
class FraudDetectionService:
    async def evaluate_order(self, order, user, payment):
        risk_score = 0
        signals = []
        
        # Device fingerprint
        if device_service.is_suspicious(order.device_fingerprint):
            risk_score += 30
            signals.append("suspicious_device")
        
        # Velocity checks
        recent_orders = await get_orders_last_hour(user.id)
        if len(recent_orders) > 5:
            risk_score += 20
            signals.append("high_velocity")
        
        # Address mismatch
        if payment.billing_address != order.shipping_address:
            risk_score += 10
            signals.append("address_mismatch")
        
        # New account + high value
        if user.account_age_days < 7 and order.total > 500:
            risk_score += 25
            signals.append("new_account_high_value")
        
        # ML model prediction
        ml_score = await fraud_ml_model.predict(
            user_features=extract_user_features(user),
            order_features=extract_order_features(order),
            payment_features=extract_payment_features(payment)
        )
        risk_score += ml_score * 50
        
        # Decision
        if risk_score > 70:
            return FraudDecision.REJECT
        elif risk_score > 40:
            return FraudDecision.MANUAL_REVIEW
        else:
            return FraudDecision.APPROVE

# Real-time signals
- IP geolocation vs shipping address
- Card BIN country vs user country  
- Known fraud patterns (graph analysis)
- Behavioral biometrics
```

---

### Bonus: Quick Architecture Summary

```
┌─────────────────────────────────────────────────────────────────┐
│               E-COMMERCE - FINAL ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  Scale: 300M users, 350M products, 50M orders/day              │
│                                                                 │
│  Core Services:                                                 │
│  ├── Product Service - Catalog, reviews                        │
│  ├── Search Service - Elasticsearch, ML ranking                │
│  ├── Cart Service - Redis, session management                  │
│  ├── Order Service - Order lifecycle, saga pattern             │
│  ├── Inventory Service - Multi-warehouse, reservations         │
│  ├── Payment Service - Multi-provider, fraud detection         │
│  └── Recommendation - Collaborative + content filtering        │
│                                                                 │
│  Data Stores:                                                   │
│  ├── DynamoDB - Products, inventory (scale + transactions)     │
│  ├── Aurora - Orders, users (ACID, complex queries)            │
│  ├── Redis - Carts, sessions, cache                            │
│  ├── Elasticsearch - Product search                            │
│  └── Redshift - Analytics                                      │
│                                                                 │
│  Key Decisions:                                                 │
│  ├── Two-phase inventory reservation                           │
│  ├── Saga pattern for distributed transactions                 │
│  ├── Event-driven for loose coupling                           │
│  ├── Queue-based checkout for flash sales                      │
│  └── Multi-warehouse optimization                              │
│                                                                 │
│  SLA: 99.99%, <200ms search, <500ms checkout                   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```
