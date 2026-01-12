# Webhooks

## What are Webhooks?

Webhooks are HTTP callbacks that notify your application when an event occurs in another system. Instead of polling for changes, you receive a push notification.

```
Polling (Pull):
Your App ──→ "Any updates?" ──→ Service
Your App ←── "No" ←─────────── Service
Your App ──→ "Any updates?" ──→ Service
Your App ←── "Yes! Here's data" ← Service
(Wasteful - many empty requests)

Webhook (Push):
Your App                        Service
    │                             │
    │     (waiting...)            │
    │                             │ Event occurs!
    │←── POST /webhook ───────────│
    │    {event data}             │
    │                             │
(Efficient - only when needed)
```

---

## Webhooks vs Polling vs WebSocket

| Aspect | Webhooks | Polling | WebSocket |
|--------|----------|---------|-----------|
| Direction | Server → You | You → Server | Bidirectional |
| Connection | Per-event | Per-request | Persistent |
| Real-time | Near real-time | Delayed | Real-time |
| Complexity | Medium | Low | High |
| Best For | Event notifications | Simple updates | Chat, gaming |

---

## How Webhooks Work

```
1. Register webhook URL with service
2. Service stores your endpoint
3. Event occurs in service
4. Service POSTs to your endpoint
5. You process and respond 200 OK

┌─────────────┐    Register    ┌─────────────┐
│  Your App   │───────────────→│   Service   │
│             │  POST /hooks   │  (Stripe,   │
│  Endpoint:  │  {url: "..."}  │   GitHub)   │
│  /webhook   │                │             │
└──────┬──────┘                └──────┬──────┘
       │                              │
       │        Event Occurs          │
       │                              │
       │←──── POST /webhook ──────────│
       │      {event: "payment.success"}
       │                              │
       │──── 200 OK ─────────────────→│
```

---

## Implementing a Webhook Receiver

### Basic Implementation

```python
from flask import Flask, request, jsonify
import hmac
import hashlib

app = Flask(__name__)
WEBHOOK_SECRET = "your_webhook_secret"

@app.route('/webhook', methods=['POST'])
def webhook_handler():
    # 1. Verify signature
    signature = request.headers.get('X-Signature')
    if not verify_signature(request.data, signature):
        return jsonify({'error': 'Invalid signature'}), 401
    
    # 2. Parse event
    event = request.json
    event_type = event.get('type')
    
    # 3. Process event (quickly!)
    try:
        if event_type == 'payment.success':
            handle_payment_success(event['data'])
        elif event_type == 'payment.failed':
            handle_payment_failed(event['data'])
        else:
            print(f"Unhandled event: {event_type}")
    except Exception as e:
        # Log but don't fail - process async if needed
        print(f"Error processing webhook: {e}")
    
    # 4. Return 200 quickly (< 30 seconds)
    return jsonify({'received': True}), 200

def verify_signature(payload, signature):
    expected = hmac.new(
        WEBHOOK_SECRET.encode(),
        payload,
        hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(f"sha256={expected}", signature)
```

### Async Processing (Recommended)

```python
from celery import Celery
import redis

celery = Celery('webhooks', broker='redis://localhost:6379')

@app.route('/webhook', methods=['POST'])
def webhook_handler():
    # Verify signature
    if not verify_signature(request.data, request.headers.get('X-Signature')):
        return jsonify({'error': 'Invalid signature'}), 401
    
    event = request.json
    
    # Queue for async processing
    process_webhook.delay(event)
    
    # Return immediately
    return jsonify({'received': True}), 200

@celery.task(bind=True, max_retries=3)
def process_webhook(self, event):
    try:
        event_type = event['type']
        if event_type == 'payment.success':
            handle_payment_success(event['data'])
        # ... handle other events
    except Exception as e:
        # Retry with exponential backoff
        self.retry(exc=e, countdown=2 ** self.request.retries)
```

---

## Sending Webhooks

### Basic Sender

```python
import requests
import hmac
import hashlib
import json
from datetime import datetime

class WebhookSender:
    def __init__(self, secret):
        self.secret = secret
    
    def send(self, url, event_type, data, max_retries=3):
        payload = {
            'id': str(uuid.uuid4()),
            'type': event_type,
            'data': data,
            'timestamp': datetime.utcnow().isoformat()
        }
        
        body = json.dumps(payload)
        signature = self._sign(body)
        
        headers = {
            'Content-Type': 'application/json',
            'X-Signature': f"sha256={signature}",
            'X-Webhook-ID': payload['id']
        }
        
        for attempt in range(max_retries):
            try:
                response = requests.post(
                    url,
                    data=body,
                    headers=headers,
                    timeout=30
                )
                
                if response.status_code == 200:
                    return True
                    
                if response.status_code >= 500:
                    # Retry on server errors
                    continue
                    
                # Don't retry on 4xx
                return False
                
            except requests.RequestException:
                if attempt < max_retries - 1:
                    time.sleep(2 ** attempt)  # Exponential backoff
                    
        return False
    
    def _sign(self, payload):
        return hmac.new(
            self.secret.encode(),
            payload.encode(),
            hashlib.sha256
        ).hexdigest()
```

### Webhook with Delivery Tracking

```python
class WebhookDelivery:
    def __init__(self, db):
        self.db = db
    
    def queue_webhook(self, subscription_id, event):
        delivery = {
            'id': str(uuid.uuid4()),
            'subscription_id': subscription_id,
            'event': event,
            'status': 'pending',
            'attempts': 0,
            'created_at': datetime.utcnow()
        }
        self.db.deliveries.insert(delivery)
        return delivery['id']
    
    def process_delivery(self, delivery_id):
        delivery = self.db.deliveries.find_one({'id': delivery_id})
        subscription = self.db.subscriptions.find_one({'id': delivery['subscription_id']})
        
        delivery['attempts'] += 1
        
        try:
            success = self.sender.send(
                subscription['url'],
                delivery['event']['type'],
                delivery['event']['data']
            )
            
            delivery['status'] = 'delivered' if success else 'failed'
            delivery['delivered_at'] = datetime.utcnow() if success else None
            
        except Exception as e:
            delivery['status'] = 'failed'
            delivery['error'] = str(e)
            
            # Schedule retry
            if delivery['attempts'] < 5:
                delivery['next_retry'] = datetime.utcnow() + timedelta(
                    minutes=2 ** delivery['attempts']
                )
                delivery['status'] = 'pending'
        
        self.db.deliveries.update({'id': delivery_id}, delivery)
```

---

## Security Best Practices

### 1. Signature Verification

```python
# Always verify signatures!
def verify_stripe_signature(payload, sig_header, secret):
    import stripe
    try:
        event = stripe.Webhook.construct_event(
            payload, sig_header, secret
        )
        return event
    except stripe.error.SignatureVerificationError:
        return None
```

### 2. Idempotency

```python
# Store processed webhook IDs to prevent duplicates
processed_webhooks = set()  # Use Redis in production

@app.route('/webhook', methods=['POST'])
def webhook_handler():
    webhook_id = request.headers.get('X-Webhook-ID')
    
    # Check if already processed
    if webhook_id in processed_webhooks:
        return jsonify({'status': 'already_processed'}), 200
    
    # Process webhook
    process_event(request.json)
    
    # Mark as processed
    processed_webhooks.add(webhook_id)
    
    return jsonify({'status': 'processed'}), 200
```

### 3. IP Allowlisting

```python
ALLOWED_IPS = ['192.0.2.1', '192.0.2.2']

@app.before_request
def check_ip():
    if request.path == '/webhook':
        if request.remote_addr not in ALLOWED_IPS:
            return jsonify({'error': 'Forbidden'}), 403
```

---

## Common Webhook Providers

| Provider | Events | Use Case |
|----------|--------|----------|
| **Stripe** | payment.success, refund.created | Payments |
| **GitHub** | push, pull_request, issues | CI/CD |
| **Twilio** | message.received, call.completed | Communications |
| **Slack** | message, reaction_added | Team chat |
| **Shopify** | order.created, product.updated | E-commerce |

---

## Interview Questions

**Q: What are webhooks and how do they differ from APIs?**
> Webhooks are push-based HTTP callbacks. APIs require you to request data (pull). Webhooks notify you when events occur. More efficient for event-driven integrations, no polling needed.

**Q: How do you secure webhooks?**
> 1) Verify signatures (HMAC), 2) Use HTTPS only, 3) IP allowlisting, 4) Validate payload structure, 5) Implement idempotency to handle duplicates.

**Q: How do you handle webhook failures?**
> Return 200 quickly, process async. If processing fails: retry with exponential backoff, dead letter queue for persistent failures, alerting on high failure rates.

---

## Quick Reference

| Aspect | Best Practice |
|--------|---------------|
| **Response Time** | < 30 seconds, async process |
| **Signature** | HMAC-SHA256 |
| **Idempotency** | Store processed IDs |
| **Retries** | Exponential backoff |
| **Security** | HTTPS + signature verify |
