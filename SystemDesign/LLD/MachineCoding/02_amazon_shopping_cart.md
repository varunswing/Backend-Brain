# Amazon Shopping Cart - System Design Interview

## Problem Statement
*"Design a shopping cart system like Amazon's where users can add/remove items, save for later, apply coupons, and checkout. Handle millions of concurrent users with low latency."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What cart operations do we need?" → Add/remove items, update quantities, view cart, save for later
- "Should we support guest carts?" → Yes, anonymous users can add items before login
- "What about cart merging?" → Merge guest cart with user cart on login
- "Do we need coupons and pricing?" → Apply discounts, calculate taxes, shipping costs

**Non-Functional Requirements:**
- "What's our user scale?" → 100M+ users, 10M+ concurrent during peak
- "Expected latency?" → <100ms for cart operations
- "Availability requirement?" → 99.9% uptime
- "How long should carts persist?" → 90 days for users, 7 days for guests

### Requirements Summary:
- **Scale**: 100M users, 10M concurrent, 30K cart RPS
- **Features**: CRUD operations, guest carts, coupon application, tax calculation
- **Performance**: <100ms latency, 99.9% availability
- **Persistence**: 90 days users, 7 days guests

---

## Phase 2: Capacity Estimation (5 minutes)

### Traffic Estimation:
```
Daily Active Users: 50M
Cart operations per user: 10 per day
Peak multiplier: 5x during sales
Peak RPS = (50M × 10 × 5) / 86,400 = ~30K RPS
```

### Storage Estimation:
```
Active carts: 50M users × 5 items × 200 bytes = 50GB
Cart history: 50M × 5 × 200B × 90 days = 4.5TB
Total storage: ~5TB with indexes
```

### Memory Requirements:
```
Active cart cache: 10M concurrent × 1KB = 10GB
Product price cache: 1M products × 100B = 100MB
Total memory: ~15GB Redis cluster
```

---

## Phase 3: High-Level Architecture (12 minutes)

### Architecture Evolution:
```
Step 1: [Client] → [Load Balancer] → [Cart Service] → [Database]

Step 2: Complete Architecture
```

```
┌─────────────┐  ┌─────────────┐
│Mobile/Web   │  │Admin Panel  │
│Apps         │  │             │
└─────────────┘  └─────────────┘
       │                │
       └────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│        API Gateway              │
│- Auth  - Rate Limit - Routing  │
└─────────────────────────────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Cart      │ │Product   │ │Pricing   │
│Service   │ │Service   │ │Service   │
│          │ │          │ │          │
│- CRUD    │ │- Inventory│ │- Tax     │
│- Merge   │ │- Details │ │- Coupons │
│- Persist │ │- Validate│ │- Shipping│
└──────────┘ └──────────┘ └──────────┘
    │           │           │
    └───────────┼───────────┘
                │
                ▼
┌─────────────────────────────────┐
│          Data Layer             │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │PostgreSQL│ │  Redis  │ │Kafka│ │
│ │- Carts  │ │- Cache  │ │-Logs│ │
│ │- Items  │ │- Session│ │     │ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
```

### Key Components:
- **Cart Service**: Core CRUD, validation, guest cart handling
- **Product Service**: Product details, inventory checks
- **Pricing Service**: Tax calculation, discounts, shipping
- **Redis**: Active cart cache, session management
- **PostgreSQL**: Persistent cart storage

---

## Phase 4: Database Design (8 minutes)

### Core Entities:
- Users, Carts, Cart Items, Products, Coupons

### PostgreSQL Schema:
```sql
-- Carts table
CREATE TABLE carts (
    cart_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(user_id),    -- NULL for guest
    guest_session_id VARCHAR(100),
    cart_status VARCHAR(20) DEFAULT 'active',
    subtotal DECIMAL(10,2) DEFAULT 0,
    total_amount DECIMAL(10,2) DEFAULT 0,
    applied_coupons JSONB DEFAULT '[]',
    created_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP,
    
    INDEX idx_carts_user (user_id),
    INDEX idx_carts_guest_session (guest_session_id)
);

-- Cart items
CREATE TABLE cart_items (
    item_id UUID PRIMARY KEY,
    cart_id UUID REFERENCES carts(cart_id) ON DELETE CASCADE,
    product_id UUID NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    unit_price DECIMAL(10,2) NOT NULL,
    line_total DECIMAL(10,2) NOT NULL,
    added_at TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(cart_id, product_id),
    INDEX idx_cart_items_cart (cart_id)
);
```

### Redis Cache Strategy:
```javascript
// Active cart cache
"cart:{user_id}": {
  "cart_id": "uuid",
  "items": [{"product_id": "uuid", "quantity": 2, "price": 29.99}],
  "total": 65.98,
  "ttl": 1800
}

// Guest cart
"guest_cart:{session_id}": { /* same structure */ }

// Product pricing
"product_price:{product_id}": {
  "price": 29.99,
  "inventory": 150,
  "ttl": 300
}
```

---

## Phase 5: Critical Flow - Add Item to Cart (8 minutes)

### Step-by-Step Flow:
```
1. User clicks "Add to Cart" → POST /api/cart/items

2. Cart Service validation:
   - Authenticate user or get guest session
   - Validate product exists and available
   - Check inventory levels

3. Cart update transaction:
   - Add/update item in cart_items table
   - Recalculate cart totals
   - Apply existing coupons
   - Update cart summary

4. Cache update:
   - Update Redis with new cart state
   - Publish event to Kafka
   - Return updated cart to client
```

### Technical Challenges:
**Concurrency**: "Use optimistic locking for cart updates"
**Inventory**: "Real-time checks to prevent overselling"
**Performance**: "Cache-aside pattern with Redis, <100ms response"
**Guest Carts**: "Session-based identification, 7-day expiration"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Database writes** during peak traffic
2. **Pricing calculation** overhead
3. **Cache consistency** between Redis and PostgreSQL

### Scaling Solutions:
**Database Scaling:**
- Read replicas for cart reads
- Sharding by user_id for writes
- Connection pooling

**Service Scaling:**
- Horizontal scaling of stateless services
- Load balancing with cart affinity
- Circuit breaker patterns

**Cache Optimization:**
- Redis cluster for high availability
- Write-through caching
- Cache warming for popular products

### Trade-offs:
- **Consistency vs Performance**: Cache eventual consistency
- **Availability vs Accuracy**: Cached vs real-time inventory
- **Storage vs Compute**: Denormalized cart data

---

## Success Metrics:
- **Conversion Rate**: >15% cart-to-order conversion
- **Latency**: <100ms P95 for cart operations
- **Availability**: 99.9% cart service uptime
- **Cache Hit Rate**: >95% for cart operations

**🎯 This design handles high-volume e-commerce transactions while optimizing for conversion and performance.**