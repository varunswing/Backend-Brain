# Rate Limiter for batch processing

## **1. Amazon Leadership Principle Connection**

This solution demonstrates **Customer Obsession** and **Ownership**:

- **Customer Obsession**: By implementing rate limiting, you're ensuring the downstream tier-0 service remains stable for end customers, prioritizing their experience over batch processing speed.
- **Ownership**: Taking responsibility for the impact of your batch system on downstream services and proactively building safeguards to prevent service degradation.

## **2. STAR Format Response (Theory-Heavy)**

### **Situation**
Your Apache Spark batch program makes API calls to downstream tier-0 services, causing service degradation due to burst traffic patterns. The downstream team requires request rate limiting to maintain service stability for customer-facing operations.

### **Task**
Design and implement a distributed rate-limiting client that:
- Respects configurable rate limits across Spark executors
- Handles backoff and retry logic
- Provides monitoring and observability
- Maintains high throughput while preventing service overload
- Works seamlessly with existing Spark batch jobs

### **Action**

**System Architecture Design:**

```
┌─────────────────────────────────────────────────────────────┐
│                   Spark Driver                              │
│  ┌───────────────────────────────────────────────────────┐  │
│  │         Rate Limit Configuration Manager             │  │
│  │   - Global rate limits                               │  │
│  │   - Partition-level allocation                       │  │
│  │   - Dynamic adjustment                               │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                 Spark Executors                            │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  │   Executor 1    │  │   Executor 2    │  │   Executor N    │
│  │                 │  │                 │  │                 │
│  │ RateLimitClient │  │ RateLimitClient │  │ RateLimitClient │
│  │ TokenBucket     │  │ TokenBucket     │  │ TokenBucket     │
│  │ CircuitBreaker  │  │ CircuitBreaker  │  │ CircuitBreaker  │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘
└─────────────────────────────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│              Downstream Tier-0 Service                     │
│                    (Protected)                             │
└─────────────────────────────────────────────────────────────┘
```

**Core Implementation:**

### **1. Rate Limiting Client Interface**

```java
public interface RateLimitedClient<T> extends Serializable {
    CompletableFuture<ApiResponse<T>> executeWithRateLimit(ApiRequest request);
    void updateRateLimit(RateLimitConfig config);
    RateLimitMetrics getMetrics();
}
```

### **2. Distributed Rate Limiter Implementation**

```java
@Component
public class DistributedRateLimitedClient<T> implements RateLimitedClient<T> {
    
    private final TokenBucket tokenBucket;
    private final CircuitBreaker circuitBreaker;
    private final RetryPolicy retryPolicy;
    private final MetricsCollector metricsCollector;
    private final HttpClient httpClient;
    
    public DistributedRateLimitedClient(RateLimitConfig config) {
        this.tokenBucket = createTokenBucket(config);
        this.circuitBreaker = createCircuitBreaker(config);
        this.retryPolicy = createRetryPolicy(config);
        this.metricsCollector = new MetricsCollector();
        this.httpClient = createHttpClient(config);
    }
    
    @Override
    public CompletableFuture<ApiResponse<T>> executeWithRateLimit(ApiRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                // 1. Acquire token from bucket
                if (!tokenBucket.tryAcquire(1, request.getTimeout())) {
                    metricsCollector.recordRateLimitHit();
                    throw new RateLimitExceededException("Rate limit exceeded");
                }
                
                // 2. Check circuit breaker
                if (circuitBreaker.isOpen()) {
                    metricsCollector.recordCircuitBreakerOpen();
                    throw new ServiceUnavailableException("Circuit breaker open");
                }
                
                // 3. Execute request with retry
                return executeWithRetry(request);
                
            } catch (Exception e) {
                metricsCollector.recordError(e);
                throw new RuntimeException(e);
            }
        });
    }
    
    private ApiResponse<T> executeWithRetry(ApiRequest request) {
        return retryPolicy.executeWithRetry(() -> {
            long startTime = System.currentTimeMillis();
            
            try {
                ApiResponse<T> response = httpClient.execute(request);
                
                // Update circuit breaker on success
                circuitBreaker.recordSuccess();
                
                // Record metrics
                metricsCollector.recordSuccess(System.currentTimeMillis() - startTime);
                
                return response;
                
            } catch (Exception e) {
                // Update circuit breaker on failure
                circuitBreaker.recordFailure();
                
                // Record failure metrics
                metricsCollector.recordFailure(System.currentTimeMillis() - startTime, e);
                
                throw e;
            }
        });
    }
}
```

### **3. Token Bucket Implementation**

```java
public class DistributedTokenBucket implements Serializable {
    
    private final double tokensPerSecond;
    private final long bucketCapacity;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTime;
    
    public DistributedTokenBucket(double tokensPerSecond, long bucketCapacity) {
        this.tokensPerSecond = tokensPerSecond;
        this.bucketCapacity = bucketCapacity;
        this.tokens = new AtomicLong(bucketCapacity);
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
    }
    
    public boolean tryAcquire(int requestedTokens, Duration timeout) {
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        
        while (System.currentTimeMillis() < endTime) {
            refillTokens();
            
            long currentTokens = tokens.get();
            if (currentTokens >= requestedTokens) {
                if (tokens.compareAndSet(currentTokens, currentTokens - requestedTokens)) {
                    return true;
                }
            } else {
                // Calculate wait time for next token
                long waitTime = calculateWaitTime(requestedTokens - currentTokens);
                if (waitTime > 0 && waitTime < timeout.toMillis()) {
                    try {
                        Thread.sleep(Math.min(waitTime, 100)); // Max 100ms sleep
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        
        return false;
    }
    
    private void refillTokens() {
        long now = System.currentTimeMillis();
        long lastRefill = lastRefillTime.get();
        
        if (now > lastRefill) {
            long timeDiff = now - lastRefill;
            long tokensToAdd = (long) (timeDiff * tokensPerSecond / 1000.0);
            
            if (tokensToAdd > 0) {
                long currentTokens = tokens.get();
                long newTokens = Math.min(bucketCapacity, currentTokens + tokensToAdd);
                
                if (tokens.compareAndSet(currentTokens, newTokens)) {
                    lastRefillTime.set(now);
                }
            }
        }
    }
    
    private long calculateWaitTime(long tokensNeeded) {
        return (long) (tokensNeeded * 1000.0 / tokensPerSecond);
    }
}
```

### **4. Spark Integration Layer**

```java
@Component
public class SparkRateLimitManager implements Serializable {
    
    private final RateLimitConfig globalConfig;
    private final ConcurrentHashMap<String, RateLimitedClient<?>> clients;
    
    public SparkRateLimitManager(RateLimitConfig config) {
        this.globalConfig = config;
        this.clients = new ConcurrentHashMap<>();
    }
    
    public <T> RateLimitedClient<T> getClient(String serviceName, Class<T> responseType) {
        return clients.computeIfAbsent(serviceName, 
            k -> createRateLimitedClient(serviceName, responseType));
    }
    
    private <T> RateLimitedClient<T> createRateLimitedClient(String serviceName, Class<T> responseType) {
        // Calculate per-executor rate limit
        int totalExecutors = SparkContext.getOrCreate().getExecutorInfos().size();
        double perExecutorLimit = globalConfig.getMaxRequestsPerSecond() / (double) totalExecutors;
        
        RateLimitConfig executorConfig = RateLimitConfig.builder()
            .maxRequestsPerSecond(perExecutorLimit)
            .bucketCapacity(globalConfig.getBucketCapacity() / totalExecutors)
            .timeout(globalConfig.getTimeout())
            .retryConfig(globalConfig.getRetryConfig())
            .circuitBreakerConfig(globalConfig.getCircuitBreakerConfig())
            .build();
        
        return new DistributedRateLimitedClient<>(executorConfig);
    }
    
    // Broadcast configuration updates to all executors
    public void updateGlobalRateLimit(RateLimitConfig newConfig) {
        SparkContext sc = SparkContext.getOrCreate();
        Broadcast<RateLimitConfig> broadcastConfig = sc.broadcast(newConfig);
        
        // Update all existing clients
        clients.values().forEach(client -> 
            client.updateRateLimit(broadcastConfig.value()));
    }
}
```

### **5. Spark Application Integration**

```java
public class RateLimitedSparkProcessor {
    
    private final SparkRateLimitManager rateLimitManager;
    private final SparkSession spark;
    
    public void processDataWithRateLimit() {
        // Initialize rate limit manager
        RateLimitConfig config = RateLimitConfig.builder()
            .maxRequestsPerSecond(100.0) // Global limit
            .bucketCapacity(200)
            .timeout(Duration.ofSeconds(30))
            .retryConfig(RetryConfig.builder()
                .maxAttempts(3)
                .backoffStrategy(BackoffStrategy.EXPONENTIAL)
                .build())
            .circuitBreakerConfig(CircuitBreakerConfig.builder()
                .failureThreshold(5)
                .recoveryTimeout(Duration.ofMinutes(1))
                .build())
            .build();
        
        this.rateLimitManager = new SparkRateLimitManager(config);
        
        // Process data with rate limiting
        Dataset<Row> inputData = spark.read()
            .option("header", true)
            .csv("path/to/input/data");
        
        Dataset<Row> processedData = inputData.mapPartitions(
            (MapPartitionsFunction<Row, Row>) this::processPartitionWithRateLimit,
            RowEncoder.apply(inputData.schema())
        );
        
        processedData.write()
            .mode(SaveMode.Overwrite)
            .csv("path/to/output/data");
    }
    
    private Iterator<Row> processPartitionWithRateLimit(Iterator<Row> partition) {
        RateLimitedClient<ApiResponse> client = rateLimitManager.getClient("downstream-service", ApiResponse.class);
        List<Row> results = new ArrayList<>();
        
        while (partition.hasNext()) {
            Row row = partition.next();
            
            try {
                ApiRequest request = createApiRequest(row);
                ApiResponse response = client.executeWithRateLimit(request).get();
                
                Row enrichedRow = enrichRowWithResponse(row, response);
                results.add(enrichedRow);
                
            } catch (Exception e) {
                // Handle error - log, add to DLQ, or create error row
                Row errorRow = createErrorRow(row, e);
                results.add(errorRow);
            }
        }
        
        return results.iterator();
    }
}
```

### **6. Configuration Management**

```java
@ConfigurationProperties(prefix = "rate-limit")
@Data
public class RateLimitConfig implements Serializable {
    
    private double maxRequestsPerSecond = 100.0;
    private long bucketCapacity = 200;
    private Duration timeout = Duration.ofSeconds(30);
    private RetryConfig retryConfig = RetryConfig.defaultConfig();
    private CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig.defaultConfig();
    
    // Service-specific configurations
    private Map<String, ServiceRateLimitConfig> serviceConfigs = new HashMap<>();
    
    @Data
    public static class ServiceRateLimitConfig {
        private double maxRequestsPerSecond;
        private long bucketCapacity;
        private Duration timeout;
        private int priority = 1; // Higher priority services get more tokens
    }
}
```

### **7. Monitoring and Metrics**

```java
@Component
public class RateLimitMetricsCollector implements Serializable {
    
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    private final AtomicLong rateLimitHits = new AtomicLong(0);
    private final AtomicLong circuitBreakerOpens = new AtomicLong(0);
    
    private final Timer responseTime = Timer.builder("api.response.time")
        .description("API response time")
        .register(Metrics.globalRegistry);
    
    public void recordSuccess(long responseTimeMs) {
        successCount.incrementAndGet();
        responseTime.record(responseTimeMs, TimeUnit.MILLISECONDS);
    }
    
    public void recordFailure(long responseTimeMs, Exception error) {
        failureCount.incrementAndGet();
        responseTime.record(responseTimeMs, TimeUnit.MILLISECONDS);
        
        // Record error types
        Metrics.counter("api.errors", "type", error.getClass().getSimpleName())
            .increment();
    }
    
    public void recordRateLimitHit() {
        rateLimitHits.incrementAndGet();
        Metrics.counter("rate.limit.hits").increment();
    }
    
    public void recordCircuitBreakerOpen() {
        circuitBreakerOpens.incrementAndGet();
        Metrics.counter("circuit.breaker.opens").increment();
    }
    
    public RateLimitMetrics getMetrics() {
        return RateLimitMetrics.builder()
            .successCount(successCount.get())
            .failureCount(failureCount.get())
            .rateLimitHits(rateLimitHits.get())
            .circuitBreakerOpens(circuitBreakerOpens.get())
            .successRate(calculateSuccessRate())
            .averageResponseTime(responseTime.mean(TimeUnit.MILLISECONDS))
            .build();
    }
    
    private double calculateSuccessRate() {
        long total = successCount.get() + failureCount.get();
        return total > 0 ? (double) successCount.get() / total : 0.0;
    }
}
```

### **Result**
- **Rate Limiting**: Successfully prevents overwhelming downstream services while maintaining high throughput
- **Fault Tolerance**: Circuit breaker and retry mechanisms ensure system resilience
- **Observability**: Comprehensive metrics and monitoring for performance optimization
- **Scalability**: Distributed design scales with Spark executor count
- **Configurability**: Dynamic configuration updates without job restart

## **3. Comprehensive Interview Questions & Theoretical Answers**

### **Q1: How would you handle dynamic rate limit adjustments during job execution?**
**Answer**: Implement a configuration broadcast mechanism using Spark's broadcast variables. Create a background thread that periodically checks for configuration updates and broadcasts changes to all executors. Use atomic operations to safely update token bucket parameters without disrupting ongoing requests.

### **Q2: How would you ensure fair distribution of rate limits across Spark partitions?**
**Answer**: Implement a weighted distribution algorithm based on partition size and processing characteristics. Use consistent hashing to assign token quotas, and implement a token sharing mechanism where underutilized partitions can lend tokens to busy partitions through a coordination service.

### **Q3: How would you handle the scenario where some executors fail during processing?**
**Answer**: Implement executor failure detection using Spark's built-in mechanisms. Redistribute failed executor's token allocation among remaining executors. Use checkpoint mechanisms to track processed records and implement idempotent request handling to safely retry failed operations.

### **Q4: How would you optimize performance when rate limits are very restrictive?**
**Answer**: Implement request batching where possible, use connection pooling and keep-alive connections, implement request prioritization based on business importance, and use asynchronous processing with CompletableFuture to maximize throughput within limits.

### **Q5: How would you test this rate limiting system comprehensively?**
**Answer**: Create unit tests for token bucket algorithms, integration tests with mock downstream services, load tests to verify rate limiting accuracy under high concurrency, chaos engineering tests for failure scenarios, and performance benchmarks comparing with and without rate limiting.

### **Q6: How would you handle different rate limits for different API endpoints?**
**Answer**: Implement a multi-bucket approach where each endpoint has its own token bucket. Use a factory pattern to create endpoint-specific clients. Implement priority-based token allocation where critical endpoints get guaranteed minimum tokens.

### **Q7: How would you ensure the rate limiter doesn't become a bottleneck itself?**
**Answer**: Use lock-free atomic operations for token bucket operations, implement local caching of frequently accessed configuration, use lazy initialization for expensive operations, and profile the rate limiter performance to identify optimization opportunities.

### **Q8: How would you implement graceful degradation when rate limits are exceeded?**
**Answer**: Implement multiple fallback strategies: queue requests for delayed processing, implement request sampling to reduce load, use cached responses when available, and provide circuit breaker patterns to fail fast and recover gracefully.

### **Q9: How would you coordinate rate limiting across multiple Spark applications?**
**Answer**: Implement a centralized rate limiting service using Redis or similar distributed cache. Use distributed locks for coordination. Implement application-level quotas and dynamic allocation based on application priority and current load.

### **Q10: How would you monitor and alert on rate limiting effectiveness?**
**Answer**: Implement comprehensive metrics including token utilization rates, request success/failure ratios, downstream service health indicators, and custom business metrics. Create dashboards showing rate limiting effectiveness and alerts for threshold breaches.

This solution provides a robust, scalable, and production-ready rate limiting system that protects downstream services while maintaining high batch processing throughput.

I'll provide a detailed line-by-line explanation of the core rate limiter components, focusing on the algorithms and implementation details.

# Code Explaination RateLimiter

## **1. Token Bucket Algorithm - Detailed Breakdown**

```java
public class DistributedTokenBucket implements Serializable {
    
    // Rate at which tokens are added to the bucket (tokens per second)
    private final double tokensPerSecond;
    
    // Maximum number of tokens the bucket can hold (burst capacity)
    private final long bucketCapacity;
    
    // Current number of available tokens (thread-safe atomic operation)
    private final AtomicLong tokens;
    
    // Timestamp of last token refill operation (thread-safe atomic operation)
    private final AtomicLong lastRefillTime;
```

**Constructor Analysis:**
```java
public DistributedTokenBucket(double tokensPerSecond, long bucketCapacity) {
    this.tokensPerSecond = tokensPerSecond;        // Store refill rate
    this.bucketCapacity = bucketCapacity;          // Store maximum capacity
    this.tokens = new AtomicLong(bucketCapacity);  // Start with full bucket
    this.lastRefillTime = new AtomicLong(System.currentTimeMillis()); // Initialize refill time
}
```

**Line-by-Line Explanation:**
- **Line 1**: Set the rate at which tokens are added (e.g., 100 tokens/second)
- **Line 2**: Set maximum tokens the bucket can hold (e.g., 200 tokens for burst)
- **Line 3**: Initialize with full bucket - allows immediate burst traffic
- **Line 4**: Record current time as baseline for future refill calculations

## **2. Token Acquisition Logic - Core Algorithm**

```java
public boolean tryAcquire(int requestedTokens, Duration timeout) {
    // Calculate absolute timeout deadline
    long endTime = System.currentTimeMillis() + timeout.toMillis();
    
    // Keep trying until timeout expires
    while (System.currentTimeMillis() < endTime) {
        
        // STEP 1: Refill tokens based on elapsed time
        refillTokens();
        
        // STEP 2: Read current token count atomically
        long currentTokens = tokens.get();
        
        // STEP 3: Check if we have enough tokens
        if (currentTokens >= requestedTokens) {
            
            // STEP 4: Try to atomically subtract tokens
            // Compare-And-Set ensures thread safety without locks
            if (tokens.compareAndSet(currentTokens, currentTokens - requestedTokens)) {
                return true;  // Successfully acquired tokens
            }
            // If CAS failed, another thread modified tokens, retry the loop
        } else {
            // STEP 5: Not enough tokens, calculate optimal wait time
            long waitTime = calculateWaitTime(requestedTokens - currentTokens);
            
            // STEP 6: Only wait if it's within timeout and reasonable
            if (waitTime > 0 && waitTime < timeout.toMillis()) {
                try {
                    // Sleep for calculated time, but max 100ms to avoid long blocks
                    Thread.sleep(Math.min(waitTime, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }
    
    // Timeout exceeded, request denied
    return false;
}
```

**Detailed Flow Analysis:**

**Line 2**: `long endTime = System.currentTimeMillis() + timeout.toMillis();`
- Calculates absolute deadline to prevent infinite waiting
- Uses millisecond precision for accurate timeout handling

**Line 5**: `while (System.currentTimeMillis() < endTime) {`
- Continues attempting until timeout expires
- Each iteration is a complete attempt cycle

**Line 8**: `refillTokens();`
- Always refill first - ensures we have latest token count
- Called every iteration to handle continuous token addition

**Line 11**: `long currentTokens = tokens.get();`
- Atomically reads current token count
- `AtomicLong.get()` is thread-safe and lock-free

**Line 14**: `if (currentTokens >= requestedTokens) {`
- Simple comparison to check token availability
- Usually `requestedTokens = 1` for single API calls

**Line 17**: `if (tokens.compareAndSet(currentTokens, currentTokens - requestedTokens)) {`
- **Critical Section**: Atomically updates token count
- CAS operation: "If tokens still equals currentTokens, set it to currentTokens - requestedTokens"
- Returns `true` if successful, `false` if another thread modified tokens

**Line 18**: `return true;`
- Success! Tokens were successfully acquired
- Request can proceed to API call

**Line 23**: `long waitTime = calculateWaitTime(requestedTokens - currentTokens);`
- Calculates how long to wait for required tokens
- Smart waiting instead of busy polling

**Line 28**: `Thread.sleep(Math.min(waitTime, 100));`
- Sleeps for calculated time, but maximum 100ms
- Prevents long blocking that could affect system responsiveness

## **3. Token Refill Mechanism - Time-Based Calculation**

```java
private void refillTokens() {
    // Get current timestamp
    long now = System.currentTimeMillis();
    
    // Get last refill timestamp atomically
    long lastRefill = lastRefillTime.get();
    
    // Only refill if time has actually passed
    if (now > lastRefill) {
        
        // Calculate elapsed time since last refill
        long timeDiff = now - lastRefill;
        
        // Calculate tokens to add based on elapsed time
        // Formula: (elapsed_ms * tokens_per_second) / 1000ms
        long tokensToAdd = (long) (timeDiff * tokensPerSecond / 1000.0);
        
        // Only proceed if we actually have tokens to add
        if (tokensToAdd > 0) {
            
            // Read current token count
            long currentTokens = tokens.get();
            
            // Calculate new token count, respecting bucket capacity
            long newTokens = Math.min(bucketCapacity, currentTokens + tokensToAdd);
            
            // Atomically update tokens and refill time
            if (tokens.compareAndSet(currentTokens, newTokens)) {
                // Update refill time only if token update succeeded
                lastRefillTime.set(now);
            }
            // If CAS failed, another thread refilled, which is fine
        }
    }
}
```

**Mathematical Formula Explanation:**

**Line 12**: `long tokensToAdd = (long) (timeDiff * tokensPerSecond / 1000.0);`

**Example Calculation:**
- `tokensPerSecond = 100.0`
- `timeDiff = 500ms` (half second elapsed)
- `tokensToAdd = (500 * 100.0) / 1000.0 = 50 tokens`

**Line 19**: `long newTokens = Math.min(bucketCapacity, currentTokens + tokensToAdd);`
- Ensures we never exceed bucket capacity
- Example: If capacity=200, current=180, tokensToAdd=50, then newTokens=200 (not 230)

**Line 22**: `if (tokens.compareAndSet(currentTokens, newTokens)) {`
- Atomically updates token count
- If another thread modified tokens between read and update, CAS fails
- Failure is acceptable - other thread likely did the refill

## **4. Wait Time Calculation**

```java
private long calculateWaitTime(long tokensNeeded) {
    // Calculate milliseconds needed to generate required tokens
    // Formula: (tokens_needed * 1000ms) / tokens_per_second
    return (long) (tokensNeeded * 1000.0 / tokensPerSecond);
}
```

**Example Calculation:**
- `tokensNeeded = 5` (need 5 more tokens)
- `tokensPerSecond = 100.0`
- `waitTime = (5 * 1000.0) / 100.0 = 50ms`

## **5. Main Rate Limited Client - Request Execution**

```java
@Override
public CompletableFuture<ApiResponse<T>> executeWithRateLimit(ApiRequest request) {
    
    // Execute asynchronously to avoid blocking calling thread
    return CompletableFuture.supplyAsync(() -> {
        try {
            // PHASE 1: RATE LIMITING CHECK
            // Try to acquire token with request timeout
            if (!tokenBucket.tryAcquire(1, request.getTimeout())) {
                // Record metric for monitoring
                metricsCollector.recordRateLimitHit();
                // Throw exception - request is rate limited
                throw new RateLimitExceededException("Rate limit exceeded");
            }
            
            // PHASE 2: CIRCUIT BREAKER CHECK
            // Check if downstream service is healthy
            if (circuitBreaker.isOpen()) {
                // Record metric for monitoring
                metricsCollector.recordCircuitBreakerOpen();
                // Fail fast - don't waste time on known failing service
                throw new ServiceUnavailableException("Circuit breaker open");
            }
            
            // PHASE 3: EXECUTE REQUEST WITH RETRY
            // Both rate limit and circuit breaker passed, execute request
            return executeWithRetry(request);
            
        } catch (Exception e) {
            // Record all errors for monitoring and debugging
            metricsCollector.recordError(e);
            // Re-throw as RuntimeException for CompletableFuture
            throw new RuntimeException(e);
        }
    });
}
```

**Execution Flow:**

**Line 4**: `return CompletableFuture.supplyAsync(() -> {`
- Executes request asynchronously
- Doesn't block the calling thread (important for Spark executors)
- Returns immediately with a Future

**Line 8**: `if (!tokenBucket.tryAcquire(1, request.getTimeout())) {`
- Attempts to acquire 1 token for the request
- Uses request timeout to avoid indefinite waiting
- Returns `false` if token couldn't be acquired within timeout

**Line 16**: `if (circuitBreaker.isOpen()) {`
- Checks if circuit breaker is blocking requests
- Prevents wasting resources on known failing services
- Fails fast to improve overall system performance

**Line 23**: `return executeWithRetry(request);`
- Executes the actual HTTP request with retry logic
- Only reached if both rate limiting and circuit breaker passed

## **6. Circuit Breaker Implementation**

```java
public class CircuitBreaker implements Serializable {
    
    // Number of failures before opening circuit
    private final int failureThreshold;
    
    // How long to wait before testing service recovery
    private final Duration recoveryTimeout;
    
    // Current count of consecutive failures
    private final AtomicInteger failureCount;
    
    // Current state of circuit breaker
    private final AtomicReference<CircuitState> state;
    
    // Timestamp of last failure
    private final AtomicLong lastFailureTime;
    
    public boolean isOpen() {
        // Check current state
        if (state.get() == CircuitState.OPEN) {
            
            // Calculate time since last failure
            long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
            
            // If recovery timeout has elapsed, try testing service
            if (timeSinceLastFailure > recoveryTimeout.toMillis()) {
                // Atomically transition to HALF_OPEN state
                state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);
                return false;  // Allow one test request
            }
            
            return true;  // Still in OPEN state, block requests
        }
        
        return false;  // CLOSED or HALF_OPEN, allow requests
    }
    
    public void recordSuccess() {
        // Reset failure count on any success
        failureCount.set(0);
        
        // Transition to CLOSED state (normal operation)
        state.set(CircuitState.CLOSED);
    }
    
    public void recordFailure() {
        // Increment failure count atomically
        int failures = failureCount.incrementAndGet();
        
        // Record when this failure occurred
        lastFailureTime.set(System.currentTimeMillis());
        
        // Check if we've exceeded failure threshold
        if (failures >= failureThreshold) {
            // Open circuit breaker
            state.set(CircuitState.OPEN);
        }
    }
}
```

**State Machine Analysis:**

**Line 15**: `if (state.get() == CircuitState.OPEN) {`
- Circuit is currently blocking all requests
- Need to check if it's time to test service recovery

**Line 18**: `long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();`
- Calculates elapsed time since circuit opened
- Used to determine if service might have recovered

**Line 21**: `if (timeSinceLastFailure > recoveryTimeout.toMillis()) {`
- If enough time has passed, test if service recovered
- Typical recovery timeout: 30 seconds to 5 minutes

**Line 23**: `state.compareAndSet(CircuitState.OPEN, CircuitState.HALF_OPEN);`
- Atomically transition to testing state
- Only one thread will successfully make this transition

## **7. Request Execution with Retry Logic**

```java
private ApiResponse<T> executeWithRetry(ApiRequest request) {
    
    // Execute request with configured retry policy
    return retryPolicy.executeWithRetry(() -> {
        
        // Record start time for latency metrics
        long startTime = System.currentTimeMillis();
        
        try {
            // Execute actual HTTP request
            ApiResponse<T> response = httpClient.execute(request);
            
            // SUCCESS PATH
            // Tell circuit breaker request succeeded
            circuitBreaker.recordSuccess();
            
            // Record success metrics
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordSuccess(responseTime);
            
            return response;
            
        } catch (Exception e) {
            // FAILURE PATH
            // Tell circuit breaker request failed
            circuitBreaker.recordFailure();
            
            // Record failure metrics
            long responseTime = System.currentTimeMillis() - startTime;
            metricsCollector.recordFailure(responseTime, e);
            
            // Re-throw for retry logic to handle
            throw e;
        }
    });
}
```

**Success/Failure Handling:**

**Line 11**: `ApiResponse<T> response = httpClient.execute(request);`
- Executes actual HTTP request using configured client
- This is where the real API call happens

**Line 15**: `circuitBreaker.recordSuccess();`
- Informs circuit breaker that request succeeded
- Resets failure count and keeps circuit closed

**Line 24**: `circuitBreaker.recordFailure();`
- Informs circuit breaker that request failed
- Increments failure count, might open circuit

## **8. Retry Policy Implementation**

```java
public <T> T executeWithRetry(Supplier<T> operation) {
    Exception lastException = null;
    
    // Try up to maxAttempts times
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
        try {
            // Execute the operation
            return operation.get();
            
        } catch (Exception e) {
            // Store exception for potential final throw
            lastException = e;
            
            // Check if this exception type is retryable
            if (!isRetryableException(e)) {
                throw new RuntimeException(e);
            }
            
            // If this was the last attempt, don't retry
            if (attempt == maxAttempts) {
                throw new RuntimeException(e);
            }
            
            // Calculate delay before next attempt
            long delayMs = backoffStrategy.calculateDelay(attempt);
            
            try {
                // Wait before retrying
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(ie);
            }
        }
    }
    
    // Should never reach here, but just in case
    throw new RuntimeException(lastException);
}
```

**Retry Logic Flow:**

**Line 5**: `for (int attempt = 1; attempt <= maxAttempts; attempt++) {`
- Attempts the operation up to configured maximum
- Typical maxAttempts: 3-5 times

**Line 14**: `if (!isRetryableException(e)) {`
- Checks if exception type should be retried
- Examples: Retry on IOException, don't retry on IllegalArgumentException

**Line 22**: `long delayMs = backoffStrategy.calculateDelay(attempt);`
- Calculates delay before next attempt
- Exponential backoff prevents overwhelming failing service

## **9. Exponential Backoff Strategy**

```java
public enum BackoffStrategy {
    EXPONENTIAL {
        @Override
        public long calculateDelay(int attempt) {
            // Calculate exponential delay: 2^(attempt-1) * 1000ms
            long delay = 1000 * (long) Math.pow(2, attempt - 1);
            
            // Cap at maximum delay to prevent extremely long waits
            return Math.min(delay, 30000); // Max 30 seconds
        }
    };
}
```

**Backoff Calculation Examples:**
- Attempt 1: `1000 * 2^0 = 1000ms` (1 second)
- Attempt 2: `1000 * 2^1 = 2000ms` (2 seconds)  
- Attempt 3: `1000 * 2^2 = 4000ms` (4 seconds)
- Attempt 4: `1000 * 2^3 = 8000ms` (8 seconds)

This detailed breakdown shows how each component works together to provide robust rate limiting with fault tolerance and monitoring capabilities.