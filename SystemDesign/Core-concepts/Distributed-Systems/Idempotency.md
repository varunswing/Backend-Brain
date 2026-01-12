# Idempotency in Distributed Systems

## What is Idempotency?

An operation is **idempotent** if performing it multiple times produces the same result as performing it once.

```
Idempotent:
  DELETE /users/123  (first call: deletes user)
  DELETE /users/123  (second call: no change, user already gone)
  
NOT Idempotent:
  POST /orders (first call: creates order #1001)
  POST /orders (second call: creates order #1002) ← Different result!
```

---

## Why Idempotency Matters

### The Problem: Network Uncertainty

```
Client          Server
  │                │
  │───Request─────→│
  │                │ (Processing...)
  │    (Timeout)   │
  │                │───Response (lost)
  │                │
  │───Retry───────→│   ← Is this safe?
```

You can never know if:
- Request never reached server
- Server processed but response was lost
- Server is still processing

**Without idempotency**: Retry might duplicate the operation.

### Real-World Scenarios

| Scenario | Without Idempotency | With Idempotency |
|----------|---------------------|------------------|
| Payment | User charged twice | Single charge |
| Order | Duplicate orders | Single order |
| Email | Multiple emails sent | Single email |
| Inventory | Over-decremented | Correct count |

---

## HTTP Methods and Idempotency

| Method | Idempotent? | Safe? | Notes |
|--------|-------------|-------|-------|
| GET | ✅ Yes | ✅ Yes | Read-only |
| HEAD | ✅ Yes | ✅ Yes | Read-only |
| PUT | ✅ Yes | ❌ No | Replace resource |
| DELETE | ✅ Yes | ❌ No | Delete resource |
| POST | ❌ No | ❌ No | Create new |
| PATCH | ❌ No | ❌ No | Partial update |

### Making POST Idempotent

Use an **Idempotency Key**:

```
POST /payments
Idempotency-Key: abc-123-xyz
{
  "amount": 100,
  "currency": "USD"
}

First call:  Creates payment, stores key → returns 200
Second call: Finds key exists → returns cached 200 (no new payment)
```

---

## Implementation Patterns

### 1. Idempotency Key Pattern

```python
import hashlib
from datetime import datetime, timedelta

class IdempotencyService:
    def __init__(self, redis_client, ttl_hours=24):
        self.redis = redis_client
        self.ttl = timedelta(hours=ttl_hours)
    
    def process_request(self, idempotency_key, request_hash, process_func):
        key = f"idempotency:{idempotency_key}"
        
        # Check if request was already processed
        cached = self.redis.get(key)
        if cached:
            cached_data = json.loads(cached)
            
            # Verify request is identical
            if cached_data['request_hash'] != request_hash:
                raise ValueError("Idempotency key reused with different request")
            
            # Return cached response
            return cached_data['response'], False  # (response, is_new)
        
        # Process new request
        try:
            response = process_func()
            
            # Cache the result
            self.redis.setex(
                key,
                self.ttl,
                json.dumps({
                    'request_hash': request_hash,
                    'response': response,
                    'processed_at': datetime.utcnow().isoformat()
                })
            )
            
            return response, True  # (response, is_new)
            
        except Exception as e:
            # Don't cache errors (allow retry)
            raise

# Usage in API
@app.post("/payments")
def create_payment(request):
    idempotency_key = request.headers.get("Idempotency-Key")
    if not idempotency_key:
        return {"error": "Idempotency-Key required"}, 400
    
    request_hash = hashlib.md5(request.body).hexdigest()
    
    response, is_new = idempotency_service.process_request(
        idempotency_key,
        request_hash,
        lambda: payment_processor.charge(request.json)
    )
    
    status = 201 if is_new else 200
    return response, status
```

### 2. Natural Idempotency Keys

Use business identifiers instead of client-generated keys:

```python
# Payment with order reference
def process_payment(order_id, amount):
    # Check if payment already exists for this order
    existing = db.query(
        "SELECT * FROM payments WHERE order_id = ? AND status = 'completed'",
        order_id
    )
    
    if existing:
        return existing  # Already processed
    
    # Process new payment
    payment = create_payment(order_id, amount)
    return payment
```

### 3. Database Constraints

Use unique constraints to prevent duplicates:

```sql
-- Prevent duplicate payments for same order
CREATE TABLE payments (
    id SERIAL PRIMARY KEY,
    order_id INTEGER NOT NULL,
    amount DECIMAL NOT NULL,
    created_at TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(order_id)  -- Prevents duplicates
);

-- Insert with ON CONFLICT
INSERT INTO payments (order_id, amount)
VALUES (123, 100.00)
ON CONFLICT (order_id) DO NOTHING
RETURNING *;
```

### 4. Conditional Updates

```sql
-- Only update if conditions match (version-based)
UPDATE inventory
SET quantity = quantity - 1, version = version + 1
WHERE product_id = 123 AND version = 5;

-- Only insert if not exists
INSERT INTO orders (id, user_id, amount)
SELECT 'order-123', 456, 100.00
WHERE NOT EXISTS (SELECT 1 FROM orders WHERE id = 'order-123');
```

---

## Idempotent Operations Examples

### 1. Set Value (Idempotent)
```python
# Always idempotent - same result regardless of previous state
user.email = "new@example.com"
user.save()
```

### 2. Add to Set (Idempotent)
```python
# Set membership - adding same element twice doesn't change set
user.roles.add("admin")
```

### 3. Increment (NOT Idempotent → Make Idempotent)
```python
# NOT idempotent - each call increases
balance += 100

# Made idempotent with transaction ID
def credit_account(account_id, amount, transaction_id):
    # Check if already processed
    if transaction_exists(transaction_id):
        return get_transaction(transaction_id)
    
    # Process and record
    with db.transaction():
        account.balance += amount
        record_transaction(transaction_id, account_id, amount)
    
    return get_transaction(transaction_id)
```

### 4. State Machine Transitions

```python
# State machine ensures idempotent transitions
class Order:
    STATES = ['pending', 'paid', 'shipped', 'delivered', 'cancelled']
    
    def mark_paid(self, payment_id):
        if self.state == 'paid':
            return  # Already paid - idempotent
        
        if self.state != 'pending':
            raise InvalidTransition(f"Cannot pay order in {self.state} state")
        
        self.state = 'paid'
        self.payment_id = payment_id
        self.save()
```

---

## Handling Retries

### Client-Side Retry with Idempotency

```python
import time
import random

def retry_with_idempotency(func, idempotency_key, max_retries=3):
    for attempt in range(max_retries):
        try:
            return func(idempotency_key)
        except NetworkError:
            if attempt < max_retries - 1:
                # Exponential backoff with jitter
                delay = (2 ** attempt) + random.uniform(0, 1)
                time.sleep(delay)
            else:
                raise
        except DuplicateError:
            # Already processed - fetch result
            return get_existing_result(idempotency_key)

# Usage
result = retry_with_idempotency(
    lambda key: api.create_payment(amount=100, idempotency_key=key),
    idempotency_key="payment-abc-123",
    max_retries=3
)
```

### Server-Side: At-Least-Once to Exactly-Once

```python
class ExactlyOnceProcessor:
    def __init__(self, redis, db):
        self.redis = redis
        self.db = db
    
    def process_message(self, message_id, payload, handler):
        lock_key = f"processing:{message_id}"
        result_key = f"result:{message_id}"
        
        # Check if already processed
        existing_result = self.redis.get(result_key)
        if existing_result:
            return json.loads(existing_result)
        
        # Try to acquire processing lock
        if not self.redis.set(lock_key, "1", nx=True, ex=300):
            # Another worker is processing
            raise RetryLater()
        
        try:
            # Process the message
            result = handler(payload)
            
            # Store result atomically
            self.redis.setex(result_key, 86400, json.dumps(result))
            
            return result
        finally:
            self.redis.delete(lock_key)
```

---

## Best Practices

### Do's ✅
1. Always use idempotency keys for non-idempotent operations
2. Hash request body to detect key reuse with different data
3. Set appropriate TTL for idempotency records
4. Return cached response on duplicate requests
5. Use database constraints as backup

### Don'ts ❌
1. Don't generate idempotency keys server-side for client requests
2. Don't cache error responses (allow retry)
3. Don't rely on timing (network delays vary)
4. Don't forget to handle partial failures

### Idempotency Key Guidelines
```
Good keys:
- UUID generated by client: "550e8400-e29b-41d4-a716-446655440000"
- Business reference: "order-123-payment"
- User + timestamp: "user456-2024-01-15-payment1"

Bad keys:
- Sequential integers (guessable)
- Timestamp only (collisions)
- Empty/null (no protection)
```

---

## Interview Questions

**Q: What is idempotency and why is it important?**
> An operation is idempotent if repeating it produces the same result. Important because in distributed systems, network failures require retries, and without idempotency, retries can cause duplicates (double charges, duplicate orders).

**Q: How do you make a POST request idempotent?**
> Use an idempotency key in the header. Server stores key + request hash + response. On duplicate key, verify request matches and return cached response. Set TTL for cleanup.

**Q: What's the difference between idempotent and safe methods?**
> Safe methods don't modify state (GET, HEAD). Idempotent methods can modify state but repeating gives same result (PUT, DELETE). POST is neither safe nor idempotent by default.

**Q: How do you handle idempotency in event-driven systems?**
> Include unique event ID, consumer stores processed IDs (deduplication), use database constraints, design event handlers to be idempotent (set value vs increment).

---

## Quick Reference

| Pattern | Use When | Storage |
|---------|----------|---------|
| Idempotency Key | API endpoints | Redis/DB |
| Natural Key | Business identifier exists | DB constraint |
| Version/ETag | Concurrent updates | Resource field |
| State Machine | Status transitions | State field |
| Dedup Table | Event processing | Dedicated table |
