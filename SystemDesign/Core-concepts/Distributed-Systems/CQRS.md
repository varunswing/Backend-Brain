# CQRS (Command Query Responsibility Segregation)

## What is CQRS?

CQRS separates read and write operations into different models, allowing each to be optimized independently.

```
Traditional (CRUD):
┌──────────────────────────────────┐
│           Single Model           │
│   Read ←──→ Database ←──→ Write  │
└──────────────────────────────────┘

CQRS:
┌────────────────┐     ┌────────────────┐
│   Write Model  │     │   Read Model   │
│   (Commands)   │     │   (Queries)    │
└───────┬────────┘     └───────┬────────┘
        │                      │
        ▼                      ▼
┌────────────────┐     ┌────────────────┐
│  Write Database│────→│  Read Database │
│  (Normalized)  │ sync│ (Denormalized) │
└────────────────┘     └────────────────┘
```

---

## Why CQRS?

### The Problem

In traditional CRUD:
- Same model for reads and writes
- Read optimization conflicts with write optimization
- Complex queries slow down writes
- Difficult to scale independently

### Benefits of CQRS

| Benefit | Description |
|---------|-------------|
| **Independent Scaling** | Scale reads and writes separately |
| **Optimized Models** | Each model optimized for its purpose |
| **Performance** | Read model can be denormalized |
| **Flexibility** | Different storage for different needs |
| **Simplicity** | Simpler models, single responsibility |

---

## Core Concepts

### Commands (Write)
Actions that change state. Should be task-based, not data-centric.

```python
# Good: Task-based command
class PlaceOrderCommand:
    customer_id: str
    items: List[OrderItem]
    shipping_address: Address

# Bad: Data-centric (CRUD-style)
class UpdateOrderCommand:
    order_id: str
    data: dict  # Generic update
```

### Queries (Read)
Retrieve data without modifying state. Can be highly optimized.

```python
class GetOrderDetailsQuery:
    order_id: str

class GetCustomerOrderHistoryQuery:
    customer_id: str
    page: int
    page_size: int
```

### Command Handler
```python
class PlaceOrderCommandHandler:
    def __init__(self, order_repo, event_bus):
        self.order_repo = order_repo
        self.event_bus = event_bus
    
    def handle(self, command: PlaceOrderCommand):
        # Validate
        self._validate_items(command.items)
        
        # Create aggregate
        order = Order.create(
            customer_id=command.customer_id,
            items=command.items,
            shipping_address=command.shipping_address
        )
        
        # Persist
        self.order_repo.save(order)
        
        # Publish event for read model sync
        self.event_bus.publish(OrderPlacedEvent(
            order_id=order.id,
            customer_id=order.customer_id,
            total=order.total
        ))
        
        return order.id
```

### Query Handler
```python
class GetOrderDetailsQueryHandler:
    def __init__(self, read_db):
        self.read_db = read_db
    
    def handle(self, query: GetOrderDetailsQuery):
        # Direct read from denormalized view
        return self.read_db.query("""
            SELECT * FROM order_details_view 
            WHERE order_id = %s
        """, query.order_id)
```

---

## Architecture Patterns

### 1. Same Database, Different Tables

```
┌─────────────────────────────────────────────┐
│              Single Database                 │
│  ┌─────────────────┐  ┌──────────────────┐ │
│  │  Write Tables   │  │   Read Views     │ │
│  │  (Normalized)   │  │  (Denormalized)  │ │
│  │  - orders       │  │  - order_summary │ │
│  │  - order_items  │──│  - customer_dash │ │
│  │  - customers    │  │  - reports       │ │
│  └─────────────────┘  └──────────────────┘ │
└─────────────────────────────────────────────┘
```

### 2. Separate Databases

```
┌──────────────┐          ┌──────────────┐
│ Write Model  │          │  Read Model  │
│  (PostgreSQL)│          │ (Elasticsearch│
│              │          │   or Redis)  │
└──────┬───────┘          └──────┬───────┘
       │                         ▲
       │    Event Bus            │
       └────────────────────────┘
           (Kafka/RabbitMQ)
```

### 3. CQRS with Event Sourcing

```
Commands                              Queries
    │                                    ▲
    ▼                                    │
┌──────────┐    ┌──────────┐    ┌───────────────┐
│ Command  │───→│  Event   │───→│   Projector   │
│ Handler  │    │  Store   │    │(builds views) │
└──────────┘    └──────────┘    └───────────────┘
                                        │
                                        ▼
                                ┌───────────────┐
                                │  Read Store   │
                                │  (Optimized)  │
                                └───────────────┘
```

---

## Implementation Example

### Write Side (Command)

```python
# Domain Model (Write)
class Order:
    def __init__(self, id, customer_id, items, status):
        self.id = id
        self.customer_id = customer_id
        self.items = items
        self.status = status
        self._events = []
    
    @staticmethod
    def create(customer_id, items, shipping_address):
        order = Order(
            id=generate_id(),
            customer_id=customer_id,
            items=items,
            status='pending'
        )
        order._events.append(OrderCreatedEvent(order))
        return order
    
    def ship(self, tracking_number):
        if self.status != 'paid':
            raise InvalidOperationError("Order must be paid before shipping")
        
        self.status = 'shipped'
        self.tracking_number = tracking_number
        self._events.append(OrderShippedEvent(self.id, tracking_number))

# Write Repository
class OrderWriteRepository:
    def save(self, order):
        # Save to normalized tables
        self.db.execute("""
            INSERT INTO orders (id, customer_id, status, created_at)
            VALUES (%s, %s, %s, %s)
        """, order.id, order.customer_id, order.status, datetime.now())
        
        for item in order.items:
            self.db.execute("""
                INSERT INTO order_items (order_id, product_id, quantity, price)
                VALUES (%s, %s, %s, %s)
            """, order.id, item.product_id, item.quantity, item.price)
```

### Read Side (Query)

```python
# Read Model (Denormalized View)
class OrderReadModel:
    order_id: str
    customer_name: str
    customer_email: str
    items: List[dict]  # Pre-joined product info
    total: Decimal
    status: str
    created_at: datetime

# Projector (Sync read model from events)
class OrderProjector:
    def __init__(self, read_db):
        self.read_db = read_db
    
    def handle_order_created(self, event: OrderCreatedEvent):
        # Build denormalized view
        customer = self.get_customer(event.customer_id)
        items_with_products = self.enrich_items(event.items)
        
        self.read_db.upsert('order_views', {
            'order_id': event.order_id,
            'customer_name': customer.name,
            'customer_email': customer.email,
            'items': items_with_products,
            'total': sum(i.price * i.quantity for i in event.items),
            'status': 'pending',
            'created_at': event.timestamp
        })
    
    def handle_order_shipped(self, event: OrderShippedEvent):
        self.read_db.update('order_views', 
            {'order_id': event.order_id},
            {'status': 'shipped', 'tracking_number': event.tracking_number}
        )

# Query Handler
class OrderQueryHandler:
    def get_order_details(self, order_id):
        # Fast read from denormalized view
        return self.read_db.find_one('order_views', {'order_id': order_id})
    
    def get_customer_orders(self, customer_id, page, page_size):
        return self.read_db.find(
            'order_views',
            {'customer_id': customer_id},
            sort=[('created_at', -1)],
            skip=page * page_size,
            limit=page_size
        )
```

---

## Synchronization Strategies

### 1. Synchronous (Same Transaction)
```python
def place_order(command):
    with db.transaction():
        order = Order.create(...)
        write_repo.save(order)
        read_repo.update_view(order)  # Same transaction
```

### 2. Asynchronous (Event-Based)
```python
def place_order(command):
    order = Order.create(...)
    write_repo.save(order)
    event_bus.publish(OrderCreatedEvent(order))  # Async sync

# Separate consumer updates read model
def on_order_created(event):
    projector.handle_order_created(event)
```

### Eventual Consistency
```
Write completes → Event published → Read model updated (later)
                                    ↑
                         Lag (milliseconds to seconds)
```

**Handling:**
- UI shows "Processing..." state
- Polling for updated data
- Optimistic UI updates

---

## When to Use CQRS

### ✅ Good Fit
- Read/write ratio highly skewed (90%+ reads)
- Complex read queries
- Different scaling needs for reads vs writes
- Event-driven architecture
- Multiple read representations needed

### ❌ Not Recommended
- Simple CRUD applications
- Same model works for both operations
- Low traffic, no scaling needs
- Team unfamiliar with pattern
- Tight consistency requirements

---

## Best Practices

### Do's ✅
1. Keep commands task-based, not CRUD-based
2. Validate commands before processing
3. Make projectors idempotent
4. Monitor read model lag
5. Design for eventual consistency

### Don'ts ❌
1. Don't use CQRS everywhere (overkill for simple cases)
2. Don't put business logic in query handlers
3. Don't skip event versioning
4. Don't ignore consistency requirements

---

## Interview Questions

**Q: What is CQRS and why would you use it?**
> CQRS separates read and write models. Use when: different scaling needs, complex read queries, event-driven systems. Benefits: optimized models, independent scaling, simpler code.

**Q: How do you handle consistency between read and write models?**
> Typically eventual consistency via events. Write publishes event, projector updates read model. Handle UI with optimistic updates or "processing" states. For strong consistency, use same transaction.

**Q: CQRS vs Event Sourcing - what's the difference?**
> CQRS is about separating read/write models. Event Sourcing stores events as source of truth. Often used together but independent. CQRS without Event Sourcing: separate models, traditional storage.

---

## Quick Reference

| Component | Purpose | Example |
|-----------|---------|---------|
| Command | Change state | PlaceOrderCommand |
| Query | Read data | GetOrderQuery |
| Command Handler | Process commands | Validate, persist |
| Query Handler | Execute queries | Return data |
| Projector | Build read models | Event → View |
