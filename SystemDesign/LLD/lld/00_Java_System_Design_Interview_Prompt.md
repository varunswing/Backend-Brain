# Java System Design Interview Prompt (45 Minutes)

## 🎯 **Interview Structure**

### **Phase 1: Requirements (8 minutes)**

#### **Functional Requirements**
```
❓ What are the core features we need to implement?
❓ Who are the primary users and their workflows?
❓ What are the key operations (CRUD, search, etc.)?
❓ Any integrations with external systems?

✅ Feature 1: [Brief description]
✅ Feature 2: [Brief description]  
✅ Feature 3: [Brief description]
✅ User Management: Registration, auth, profiles
✅ Admin Features: Dashboard, analytics
```

#### **Non-Functional Requirements**
```
�� Scale: [X] million users, [Y]K concurrent, [Z]K RPS
⚡ Performance: <100ms API response, <200ms page load
🔄 Availability: 99.9% uptime
🔒 Security: HTTPS, JWT, input validation
🌐 Consistency: Strong for critical data, eventual for others
```

### **Phase 2: Capacity Estimation (5 minutes)**
```
�� Traffic: [X]M DAU × [Y] requests/day = [Z]M requests/day
📊 Peak RPS: [Z]M × 3 ÷ 86,400 = [A]K RPS
💾 Storage: [B]M users × [C]KB = [D]GB + growth
�� Bandwidth: [A]K RPS × [E]KB avg response = [F] MB/s
```

### **Phase 3: High-Level Design (12 minutes)**

#### **Architecture Choice**
```
□ Monolithic: Simple, single deployment (small teams)
□ Microservices: Scalable, independent services (large teams)
```

#### **Core Components**
```
[Client] → [CDN] → [Load Balancer] → [API Gateway]
                                           │
                    ┌──────────────────────┼──────────────────────┐
                    │                      │                      │
            [User Service]          [Business Service]    [Data Service]
                    │                      │                      │
            ┌───────────────────────────────┼───────────────────────────────┐
            │                    Data Layer                                 │
            │  [PostgreSQL]  [Redis]  [Elasticsearch]  [File Storage]      │
            └───────────────────────────────────────────────────────────────┘
```

### **Phase 4: Request Flow (8 minutes)**
```
🎯 Primary Use Case: [e.g., "User Places Order"]

1. Client Request
   ├─ Input validation (client-side)
   ├─ Authentication (JWT token)
   └─ Send to API Gateway

2. API Gateway
   ├─ Rate limiting check
   ├─ Route to appropriate service
   └─ Request logging

3. Business Service (Java Spring Boot)
   ├─ Input validation & sanitization
   ├─ Business logic processing
   ├─ Database operations
   └─ Cache updates

4. Response
   ├─ Format response data
   ├─ Update metrics
   └─ Return to client

⚠️ Error Handling: Validation → 400, Auth → 401, Server → 500
```

### **Phase 5: Detailed Design (10 minutes)**

#### **Java Service Implementation**
```java
@RestController
@RequestMapping("/api/v1")
public class BusinessController {
    
    @Autowired
    private BusinessService businessService;
    
    @Autowired
    private CacheManager cacheManager;
    
    @PostMapping("/operation")
    public ResponseEntity<ApiResponse> handleOperation(
            @Valid @RequestBody OperationRequest request,
            HttpServletRequest httpRequest) {
        
        try {
            // 1. Input validation (handled by @Valid)
            
            // 2. Rate limiting check
            if (!rateLimitService.isAllowed(getUserId(httpRequest))) {
                return ResponseEntity.status(429)
                    .body(ApiResponse.error("Rate limit exceeded"));
            }
            
            // 3. Cache check
            String cacheKey = generateCacheKey(request);
            Optional<OperationResult> cached = cacheManager.get(cacheKey, OperationResult.class);
            if (cached.isPresent()) {
                return ResponseEntity.ok(ApiResponse.success(cached.get()));
            }
            
            // 4. Business logic
            OperationResult result = businessService.processOperation(request);
            
            // 5. Cache result
            cacheManager.put(cacheKey, result, Duration.ofMinutes(5));
            
            return ResponseEntity.ok(ApiResponse.success(result));
            
        } catch (ValidationException e) {
            return ResponseEntity.badRequest()
                .body(ApiResponse.error("Validation failed: " + e.getMessage()));
        } catch (BusinessException e) {
            return ResponseEntity.status(422)
                .body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error processing operation", e);
            return ResponseEntity.status(500)
                .body(ApiResponse.error("Internal server error"));
        }
    }
}

@Service
@Transactional
public class BusinessService {
    
    @Autowired
    private BusinessRepository repository;
    
    @Autowired
    private MessagePublisher messagePublisher;
    
    public OperationResult processOperation(OperationRequest request) {
        // 1. Business validation
        validateBusinessRules(request);
        
        // 2. Database operations
        Entity entity = repository.save(createEntity(request));
        
        // 3. Async processing
        messagePublisher.publish("operation.completed", 
            new OperationCompletedEvent(entity.getId(), request.getUserId()));
        
        return OperationResult.from(entity);
    }
}
```

#### **Database Design**
```sql
-- Core entity table
CREATE TABLE entities (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    data JSONB,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_entities_user (user_id),
    INDEX idx_entities_status (status),
    INDEX idx_entities_created (created_at)
);
```

### **Phase 6: Trade-offs & Tech Choices (2 minutes)**
```
✅ Spring Boot: Rapid development, mature ecosystem, enterprise-ready
✅ PostgreSQL: ACID compliance, complex queries, JSON support
✅ Redis: Sub-ms caching, session management, rate limiting
✅ Docker + Kubernetes: Containerization, orchestration, scaling
✅ RabbitMQ: Async messaging, reliable delivery, dead letter queues

Trade-offs:
- Microservices: Complexity vs Scalability
- Strong Consistency: Performance vs Data Accuracy  
- Caching: Memory Usage vs Response Time
```

## 🚀 **Quick Reference**

### **Java Stack Recommendations**
- **Framework**: Spring Boot 3.x with Spring Security
- **Database**: PostgreSQL with JPA/Hibernate
- **Caching**: Redis with Spring Data Redis
- **Messaging**: RabbitMQ or Apache Kafka
- **Monitoring**: Micrometer + Prometheus + Grafana
- **Testing**: JUnit 5 + Testcontainers for integration tests

### **Scaling Patterns**
- **Database**: Read replicas, connection pooling, query optimization
- **Caching**: Multi-level (L1: Caffeine, L2: Redis)
- **Async Processing**: Message queues for background tasks
- **Load Balancing**: Nginx/HAProxy with health checks

### **Common Failure Scenarios**
- Database connection exhaustion → Connection pooling
- Memory leaks → Heap analysis, proper resource cleanup
- Service timeouts → Circuit breaker pattern
- Cache failures → Graceful degradation to database

**Time Management**: 8min requirements → 5min capacity → 12min design → 8min flows → 10min details → 2min trade-offs

**Success Tips**: Ask clarifying questions, start simple then scale, explain trade-offs, handle errors gracefully, show Java expertise with concrete code examples.
