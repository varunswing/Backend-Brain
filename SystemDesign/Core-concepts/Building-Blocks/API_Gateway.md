# API Gateway: Complete Guide for System Design

## Overview
An API Gateway is a server that acts as a single entry point for all client requests in a microservices architecture. It handles request routing, composition, and protocol translation.

## Why API Gateway?

```
Without API Gateway:           With API Gateway:
┌────────┐                     ┌────────┐
│ Client │                     │ Client │
└───┬────┘                     └───┬────┘
    │                              │
┌───┴────┐                    ┌───▼────┐
│ Service│                    │  API   │
│   A    │                    │Gateway │
└───┬────┘                    └───┬────┘
┌───┴────┐                        │
│ Service│              ┌─────────┼─────────┐
│   B    │              ▼         ▼         ▼
└───┬────┘          Service A  Service B  Service C
    ...
(Multiple connections)    (Single entry point)
```

## Core Responsibilities

### 1. Request Routing
```
/api/users/* → User Service
/api/orders/* → Order Service
/api/products/* → Product Service
```

### 2. Authentication & Authorization
```
Client Request → API Gateway → Validate JWT → Route to Service
                     ↓
              Reject if invalid
```

### 3. Rate Limiting
```
User: user123
Limit: 100 requests/minute
Current: 95 requests
Action: Allow (5 remaining)
```

### 4. Request/Response Transformation
```
Client Request (JSON) → Gateway → Backend (XML)
Client Response (JSON) ← Gateway ← Backend (XML)
```

### 5. Load Balancing
Distribute requests across multiple instances of a service.

### 6. Circuit Breaking
Prevent cascading failures when downstream services fail.

### 7. Caching
Cache responses for frequently requested data.

### 8. Logging & Monitoring
Centralized logging and metrics collection.

## API Gateway Patterns

### 1. Single Gateway Pattern
```
All Clients → Single API Gateway → All Services
```
**Pros**: Simple, centralized management
**Cons**: Single point of failure, potential bottleneck

### 2. BFF Pattern (Backend for Frontend)
```
Web Client → Web Gateway → Services
Mobile App → Mobile Gateway → Services
IoT Device → IoT Gateway → Services
```
**Pros**: Optimized for each client type
**Cons**: More complexity, code duplication

### 3. Gateway Per Domain
```
/users/* → User Gateway → User Services
/orders/* → Order Gateway → Order Services
/payments/* → Payment Gateway → Payment Services
```
**Pros**: Separation of concerns, team autonomy
**Cons**: Distributed management

## Key Features Deep Dive

### Authentication Methods

```yaml
# JWT Validation
authentication:
  type: JWT
  issuer: auth.company.com
  audience: api.company.com
  public_key: /keys/public.pem

# API Key
authentication:
  type: API_KEY
  header: X-API-Key
  storage: redis

# OAuth 2.0
authentication:
  type: OAuth2
  authorization_server: https://auth.company.com
  token_endpoint: /oauth/token
```

### Rate Limiting Strategies

```yaml
# Fixed Window
rate_limit:
  type: fixed_window
  limit: 100
  window: 60s

# Sliding Window
rate_limit:
  type: sliding_window
  limit: 100
  window: 60s

# Token Bucket
rate_limit:
  type: token_bucket
  capacity: 100
  refill_rate: 10/s
```

### Request Transformation

```yaml
# Add Headers
transform:
  request:
    headers:
      add:
        X-Request-ID: ${uuid()}
        X-Forwarded-For: ${client.ip}

# Modify Body
transform:
  request:
    body:
      json:
        - operation: add
          path: /metadata/gateway
          value: "api-gateway-v1"
```

### Circuit Breaker Configuration

```yaml
circuit_breaker:
  threshold: 5          # failures before opening
  timeout: 30s          # time in open state
  half_open_requests: 3 # requests to test
  
  states:
    CLOSED: Normal operation
    OPEN: All requests fail fast
    HALF_OPEN: Testing recovery
```

## Popular API Gateway Solutions

### Kong
```yaml
# Kong Configuration
services:
  - name: user-service
    url: http://user-service:8080
    routes:
      - name: user-route
        paths: ["/api/users"]
    plugins:
      - name: rate-limiting
        config:
          minute: 100
      - name: jwt
```

### AWS API Gateway
```yaml
# SAM Template
Resources:
  Api:
    Type: AWS::Serverless::Api
    Properties:
      StageName: prod
      Auth:
        DefaultAuthorizer: MyCognitoAuthorizer
      
  UserFunction:
    Type: AWS::Serverless::Function
    Properties:
      Events:
        GetUsers:
          Type: Api
          Properties:
            Path: /users
            Method: GET
```

### Spring Cloud Gateway
```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service
          uri: lb://USER-SERVICE
          predicates:
            - Path=/api/users/**
          filters:
            - StripPrefix=1
            - name: CircuitBreaker
              args:
                name: userServiceCB
                fallbackUri: forward:/fallback
```

### NGINX as API Gateway
```nginx
upstream user_service {
    server user-service-1:8080;
    server user-service-2:8080;
}

server {
    location /api/users {
        # Rate limiting
        limit_req zone=api_limit burst=20;
        
        # Auth validation
        auth_request /auth;
        
        # Proxy to service
        proxy_pass http://user_service;
    }
    
    location /auth {
        internal;
        proxy_pass http://auth-service/validate;
    }
}
```

## API Versioning Strategies

### URL Path Versioning
```
/api/v1/users
/api/v2/users
```

### Header Versioning
```
GET /api/users
X-API-Version: 2
```

### Query Parameter Versioning
```
/api/users?version=2
```

### Content Negotiation
```
Accept: application/vnd.company.v2+json
```

## Security Best Practices

### 1. Always Use HTTPS
### 2. Implement Rate Limiting
### 3. Validate All Input
### 4. Use Strong Authentication
### 5. Implement Request Signing
### 6. Enable CORS Properly
### 7. Hide Internal Errors

```yaml
# Security Headers
security:
  headers:
    X-Content-Type-Options: nosniff
    X-Frame-Options: DENY
    X-XSS-Protection: "1; mode=block"
    Strict-Transport-Security: "max-age=31536000"
    Content-Security-Policy: "default-src 'self'"
```

## Monitoring & Observability

### Key Metrics
- Request rate (RPS)
- Latency (p50, p95, p99)
- Error rate (4xx, 5xx)
- Circuit breaker state
- Rate limit hits

### Distributed Tracing
```
Client → Gateway → Service A → Service B
  ↓         ↓          ↓          ↓
  └─────────┴──────────┴──────────┘
         Trace ID: abc123
```

## Interview Questions

**Q: Why use an API Gateway instead of direct service calls?**
> Centralized cross-cutting concerns (auth, rate limiting, logging), simplified client, service abstraction, protocol translation.

**Q: How to handle API Gateway becoming a bottleneck?**
> Horizontal scaling, caching, multiple gateway instances, efficient routing algorithms, async processing.

**Q: API Gateway vs Service Mesh?**
> API Gateway handles north-south traffic (client to services). Service Mesh handles east-west traffic (service to service).

## Quick Reference

| Feature | Purpose |
|---------|---------|
| **Routing** | Direct requests to appropriate services |
| **Auth** | Validate identity and permissions |
| **Rate Limit** | Protect services from overload |
| **Transform** | Convert request/response formats |
| **Circuit Break** | Handle failing services gracefully |
| **Cache** | Reduce backend load |
| **Monitor** | Track API usage and performance |
