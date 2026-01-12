# API Security

## Overview

Securing APIs is critical for protecting data and services. This covers authentication, authorization, common attacks, and best practices.

---

## API Authentication Methods

### 1. API Keys

Simple token for identifying the calling application.

```python
# Request
GET /api/data HTTP/1.1
X-API-Key: sk_live_abc123xyz

# Validation
@app.before_request
def validate_api_key():
    api_key = request.headers.get('X-API-Key')
    if not api_key or not is_valid_key(api_key):
        return jsonify({'error': 'Invalid API key'}), 401
```

**Pros:** Simple, easy to implement
**Cons:** No user context, hard to rotate, can leak

### 2. Bearer Tokens (JWT)

```python
# Request
GET /api/data HTTP/1.1
Authorization: Bearer eyJhbGciOiJSUzI1NiIs...

# Validation
import jwt

def validate_token(token):
    try:
        payload = jwt.decode(
            token,
            public_key,
            algorithms=['RS256'],
            audience='my-api'
        )
        return payload
    except jwt.ExpiredSignatureError:
        raise AuthError('Token expired')
    except jwt.InvalidTokenError:
        raise AuthError('Invalid token')
```

### 3. OAuth 2.0 (Client Credentials)

For service-to-service authentication.

```python
# Get access token
response = requests.post('https://auth.example.com/token', data={
    'grant_type': 'client_credentials',
    'client_id': 'service-a',
    'client_secret': 'secret',
    'scope': 'read:users write:orders'
})
access_token = response.json()['access_token']

# Use token
response = requests.get('https://api.example.com/users', 
    headers={'Authorization': f'Bearer {access_token}'})
```

### 4. Mutual TLS (mTLS)

Both client and server present certificates.

```python
# Server validates client certificate
import ssl

context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('server.crt', 'server.key')
context.load_verify_locations('ca.crt')
context.verify_mode = ssl.CERT_REQUIRED  # Require client cert
```

---

## Rate Limiting

Protect against abuse and DDoS.

### Token Bucket Algorithm

```python
import time
from dataclasses import dataclass

@dataclass
class TokenBucket:
    capacity: int
    refill_rate: float  # tokens per second
    tokens: float
    last_refill: float

class RateLimiter:
    def __init__(self, redis):
        self.redis = redis
    
    def is_allowed(self, key: str, capacity: int = 100, refill_rate: float = 10) -> bool:
        now = time.time()
        bucket_key = f"rate_limit:{key}"
        
        # Get or create bucket
        data = self.redis.hgetall(bucket_key)
        
        if not data:
            # New bucket
            tokens = capacity - 1
            self.redis.hset(bucket_key, mapping={
                'tokens': tokens,
                'last_refill': now
            })
            self.redis.expire(bucket_key, 3600)
            return True
        
        # Refill tokens
        tokens = float(data['tokens'])
        last_refill = float(data['last_refill'])
        elapsed = now - last_refill
        tokens = min(capacity, tokens + elapsed * refill_rate)
        
        if tokens >= 1:
            tokens -= 1
            self.redis.hset(bucket_key, mapping={
                'tokens': tokens,
                'last_refill': now
            })
            return True
        
        return False

# Usage
@app.before_request
def rate_limit():
    if not rate_limiter.is_allowed(request.remote_addr):
        return jsonify({'error': 'Rate limit exceeded'}), 429
```

### Response Headers

```python
@app.after_request
def add_rate_limit_headers(response):
    response.headers['X-RateLimit-Limit'] = '100'
    response.headers['X-RateLimit-Remaining'] = str(remaining)
    response.headers['X-RateLimit-Reset'] = str(reset_time)
    return response
```

---

## Input Validation

### Schema Validation

```python
from pydantic import BaseModel, validator, Field
from typing import Optional

class CreateUserRequest(BaseModel):
    email: str = Field(..., regex=r'^[\w\.-]+@[\w\.-]+\.\w+$')
    name: str = Field(..., min_length=1, max_length=100)
    age: Optional[int] = Field(None, ge=0, le=150)
    
    @validator('email')
    def email_lowercase(cls, v):
        return v.lower()
    
    @validator('name')
    def sanitize_name(cls, v):
        # Remove potential XSS
        return bleach.clean(v)

@app.post('/users')
def create_user(request: CreateUserRequest):
    # Request is already validated
    pass
```

### SQL Injection Prevention

```python
# ❌ BAD - SQL Injection vulnerable
def get_user(user_id):
    query = f"SELECT * FROM users WHERE id = {user_id}"
    return db.execute(query)

# ✅ GOOD - Parameterized query
def get_user(user_id):
    query = "SELECT * FROM users WHERE id = ?"
    return db.execute(query, [user_id])

# ✅ GOOD - ORM
def get_user(user_id):
    return User.query.filter_by(id=user_id).first()
```

---

## Common API Attacks

### 1. Broken Object Level Authorization (BOLA)

```python
# ❌ Vulnerable - No ownership check
@app.get('/api/orders/{order_id}')
def get_order(order_id):
    return Order.query.get(order_id)  # Any user can access any order

# ✅ Fixed - Check ownership
@app.get('/api/orders/{order_id}')
def get_order(order_id, current_user):
    order = Order.query.get(order_id)
    if order.user_id != current_user.id:
        raise ForbiddenError()
    return order
```

### 2. Mass Assignment

```python
# ❌ Vulnerable - Accepts all fields
@app.post('/api/users')
def create_user():
    user = User(**request.json)  # Could set is_admin=True!
    db.save(user)

# ✅ Fixed - Whitelist fields
@app.post('/api/users')
def create_user():
    allowed_fields = ['email', 'name', 'password']
    data = {k: v for k, v in request.json.items() if k in allowed_fields}
    user = User(**data)
    db.save(user)
```

### 3. Excessive Data Exposure

```python
# ❌ Vulnerable - Returns all user data
@app.get('/api/users/{user_id}')
def get_user(user_id):
    return User.query.get(user_id).to_dict()  # Includes password_hash, SSN, etc.

# ✅ Fixed - Return only needed fields
@app.get('/api/users/{user_id}')
def get_user(user_id):
    user = User.query.get(user_id)
    return {
        'id': user.id,
        'name': user.name,
        'email': user.email
    }
```

---

## Security Headers

```python
@app.after_request
def add_security_headers(response):
    # Prevent clickjacking
    response.headers['X-Frame-Options'] = 'DENY'
    
    # Prevent MIME sniffing
    response.headers['X-Content-Type-Options'] = 'nosniff'
    
    # XSS Protection
    response.headers['X-XSS-Protection'] = '1; mode=block'
    
    # HTTPS only
    response.headers['Strict-Transport-Security'] = 'max-age=31536000; includeSubDomains'
    
    # Content Security Policy
    response.headers['Content-Security-Policy'] = "default-src 'self'"
    
    return response
```

---

## API Versioning Security

```python
# Maintain security for all versions
# Deprecate insecure versions

@app.route('/api/v1/data')  # Deprecated - remove after migration
def get_data_v1():
    logger.warning('Deprecated API v1 called')
    return get_data_v2()

@app.route('/api/v2/data')  # Current
def get_data_v2():
    # New security measures
    validate_request()
    return data
```

---

## Logging & Monitoring

```python
import logging
from functools import wraps

def audit_log(func):
    @wraps(func)
    def wrapper(*args, **kwargs):
        # Log request
        logging.info({
            'event': 'api_request',
            'endpoint': request.endpoint,
            'method': request.method,
            'user_id': get_current_user_id(),
            'ip': request.remote_addr,
            'user_agent': request.user_agent.string,
            'timestamp': datetime.utcnow().isoformat()
        })
        
        try:
            result = func(*args, **kwargs)
            
            # Log success
            logging.info({
                'event': 'api_response',
                'status': 'success',
                'endpoint': request.endpoint
            })
            
            return result
            
        except Exception as e:
            # Log failure
            logging.error({
                'event': 'api_error',
                'endpoint': request.endpoint,
                'error': str(e),
                'user_id': get_current_user_id()
            })
            raise
    
    return wrapper
```

### Security Alerts

```python
# Alert on suspicious activity
ALERT_THRESHOLDS = {
    'failed_auth_per_ip': 10,      # per minute
    'failed_auth_per_user': 5,     # per minute
    'requests_per_ip': 1000,       # per minute
}

def check_for_attacks(request):
    ip = request.remote_addr
    
    # Check for brute force
    failed_auths = get_failed_auth_count(ip, minutes=1)
    if failed_auths > ALERT_THRESHOLDS['failed_auth_per_ip']:
        alert_security_team(f'Possible brute force from {ip}')
        block_ip_temporarily(ip)
```

---

## CORS Configuration

```python
from flask_cors import CORS

# ❌ BAD - Allow all origins
CORS(app, origins='*')

# ✅ GOOD - Specific origins
CORS(app, 
    origins=['https://myapp.com', 'https://admin.myapp.com'],
    methods=['GET', 'POST', 'PUT', 'DELETE'],
    allow_headers=['Authorization', 'Content-Type'],
    expose_headers=['X-Request-Id'],
    supports_credentials=True,
    max_age=3600
)
```

---

## Best Practices Checklist

### Authentication
- [ ] Use HTTPS everywhere
- [ ] Implement proper authentication (OAuth 2.0, JWT)
- [ ] Use strong, unique API keys
- [ ] Rotate credentials regularly
- [ ] Implement MFA for sensitive operations

### Authorization
- [ ] Validate user permissions on every request
- [ ] Check object ownership (BOLA prevention)
- [ ] Use principle of least privilege
- [ ] Implement role-based access control

### Input/Output
- [ ] Validate all input (type, length, format)
- [ ] Use parameterized queries
- [ ] Sanitize output to prevent XSS
- [ ] Don't expose sensitive data in responses

### Rate Limiting & Monitoring
- [ ] Implement rate limiting
- [ ] Log all requests
- [ ] Monitor for anomalies
- [ ] Set up alerts for attacks

---

## Interview Questions

**Q: How do you secure an API?**
> Layers: 1) HTTPS only, 2) Authentication (OAuth/JWT), 3) Authorization (check permissions per request), 4) Input validation, 5) Rate limiting, 6) Logging & monitoring. Defense in depth.

**Q: JWT vs API Keys?**
> JWT: Contains user info, can be verified without DB, expires. Better for user auth. API Keys: Simple string, requires DB lookup, long-lived. Better for service identification.

**Q: What is BOLA and how to prevent it?**
> Broken Object Level Authorization - accessing others' data via ID manipulation. Prevent: always verify the requesting user owns/can access the resource. Don't rely on obscurity.

---

## Quick Reference

| Attack | Prevention |
|--------|------------|
| SQL Injection | Parameterized queries |
| XSS | Output encoding, CSP |
| CSRF | CSRF tokens, SameSite cookies |
| BOLA | Object ownership checks |
| Brute Force | Rate limiting, account lockout |
| Man-in-Middle | HTTPS, certificate pinning |
