## Refund Transaction Processing System Implementation**

### **🎯 SITUATION**
- **Challenge**: Handle 3-4 Lakh refund transactions daily for 80L merchant transactions with high success rate requirements
- **Initial Success Rate**: Below optimal levels due to network timeouts, downstream service failures, and processing bottlenecks
- **Technical Constraints**: 
  - Distributed microservices architecture with multiple failure points
  - High-volume transaction processing requiring fault tolerance
  - Need for real-time status tracking and reconciliation

### **📋 TASK**
**Primary Objectives:**
- Increase refund transaction success rate to 99.5%
- Implement comprehensive retry mechanisms for failed transactions
- Build fault-tolerant message delivery system using Kafka
- Create reconciliation and status check mechanisms
- Establish monitoring and alerting for transaction processing

### **⚡ ACTION**

#### **1. Cron Job Implementation (upi-jobs)**

**A. External Transaction Status Check Jobs:**
```java
// Primary status check job
@DisallowConcurrentExecution
@Component
@ConditionalOnExpression("${ext.txns.check.status.job:false}")
public class ExtTransactionsCheckStatusJob implements BatchWiseExecutableJob
```

**Implemented Multiple Job Types:**
- **`ExtTransactionsCheckStatusJob`**: Main status verification job
- **`ExtTransactionsCheckStatusRetry1Job`**: First retry layer
- **`ExtTransactionsCheckStatusFinalRetryJob`**: Final retry with advanced offset management
- **`ExtTransactionsCheckStatusDeemedJob`**: Handles deemed transactions

**B. Retry Mechanism Architecture:**
```java
// Configurable batch processing with offset management
private static final int EXT_TRANSACTIONS_CHECK_STATUS_BATCH_SIZE = 100;
private static final String EXT_TRANSACTIONS_CHECK_STATUS_LAST_PROCESSED_OFFSET = "2019-05-01";

// Advanced retry attempt tracking
@Query(value = "select * from retry_attempt where status = :status and execute_after <= now() 
               and execute_after > :offset order by execute_after asc limit :limit")
List<ExtTransactionRetryAttemptInfo> getProcessingAttempts(@Param("status") String status);
```

**C. Intelligent Offset Management:**
```java
// Final retry job with sophisticated offset updating
public void saveAfterAllBatchExecutionUpdateOffset() {
    Date updatedOn = getFirstTxnForUpdatingLastProcessedOffset();
    Calendar calender = Calendar.getInstance();
    int getMaxSecondForUpdatingLastProcessedOffset = 
        (int) (getMaxMillisForUpdatingLastProcessedOffset() / 1000);
    calender.add(Calendar.SECOND, -getMaxSecondForUpdatingLastProcessedOffset);
    // Update offset logic ensures no transaction loss
}
```

#### **2. Kafka-Based Fault-Tolerant Message Delivery (upi-consumers)**

**A. Pre-Approved Refund Transaction Receivers:**
```java
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions}",
              groupId = "preapproved_refund_transactions_group")
public void recievePreApprovedRefundTransactionRequest(ConsumerRecord<?, ?> record, Acknowledgment ack) {
    // Rate limiting and processing logic
    rateLimitAccessOfMessage(topicName, record.partition());
    preApprovedExtTxnsConsumerService.processPreApprovedTransactionRequest(
        record.value().toString(), METRIC_TAG_VALUES.PREAPPROVED_REFUND_TRANSACTIONS_REQUEST_TOPIC.getValue());
}
```

**B. Multi-Level Retry Implementation:**
```java
// Dedicated retry topic consumer
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions.retry.job}",
              groupId = "preapproved_refund_transactions_retry_group")
public void receivePreApprovedRefundTransactionRequestRetry(ConsumerRecord<?, ?> record, Acknowledgment ack)
```

**C. Rate Limiting and Circuit Breaker:**
```java
public abstract class RateLimitableReciever {
    protected void rateLimitAccessOfMessage(String topicName, int partition) {
        // Business property-based rate limiting
        // Prevents system overload during peak traffic
    }
}
```

#### **3. Refund Service Architecture (upi-refund-service)**

**A. Comprehensive Refund Processing:**
```java
@Service
public class DefaultRefundService {
    
    // Async processing with executor service
    private final ExecutorService executorService = new ThreadPoolExecutor(
        corePoolSize, maximumPoolSize, keepAliveTime, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), threadFactory);
    
    @Transactional(transactionManager = "refundMasterTransactionManager")
    public ONLINE_REFUND_RESPONSE checkRefundAmountAndUpdateStatusPlusCreateAttemptV2(
        ExtTxnRequestDTO extTxnRequestDTO, RefundOrgTxnInfo refundOrgTxnInfo, RefundInfo refundInfo) {
        // Business validation logic
        // Concurrent processing safeguards
        // Retry attempt creation
    }
}
```

**B. Status Reconciliation System:**
```java
// Real-time status synchronization
public ProcessOnlineRefundResponse checkExternalTxnStatus(ExtTxnCheckStatusRequest extTxnCheckStatusRequest) {
    RefundInfo refundInfo = onlineRefundDataService.findRefundInfoByIdAndMode(
        extTxnCheckStatusRequest.getExternalTxnId(), Constants.ONLINE_REFUND_TYPE);
    
    // Status mapping and response preparation
    if (ONLINE_REFUND_TXN_STATUS.SUCCESS.name().equalsIgnoreCase(refundInfo.getStatus())) {
        return prepareRefundResponseWithRefundStatus(ONLINE_REFUND_RESPONSE.SUCCESS_RESPONSE, refundInfo);
    }
}
```

#### **4. Monitoring and Metrics Implementation**

**A. DataDog Integration:**
```java
@MonitoringEntities({
    @MonitoringEntity(operand = MonitoringEntity.Datatype.REFUND_RESP_CODE,
                     operations = {MonitoringEntity.Operations.LOG, MonitoringEntity.Operations.DATA_DOG_INCR}),
    @MonitoringEntity(operand = MonitoringEntity.Datatype.TIME_DATA,
                     operations = {MonitoringEntity.Operations.LOG, MonitoringEntity.Operations.DATA_DOG_TIME})
})
```

**B. Performance Metrics:**
```java
// Journey time tracking
private void pushJourneyTime(RefundInfo refundInfo) {
    long pollTimeInMillis = System.currentTimeMillis() - refundInfo.getCreateDate().getTime();
    datadogUtility.recordExecutionTime(Enums.METRIC_NAME.REFUND_POLL_TIME.name(), pollTimeInMillis);
}

// Success rate monitoring
datadogUtility.recordHistogram(Enums.METRIC_KEY.EXTERNAL_TXN_CHECK_STATUS_SUCCESS.getValue(),
                              response.getNoOfRecordsProcessed());
```

#### **5. Data Pipeline Architecture**

**A. Event-Driven Processing:**
```java
// Kafka producer for retry jobs
public BatchWiseExecutableJobResponseDto initiateExtTransactions(
    List<ExtTransactionRetryAttemptInfo> extTxnsAttemptInfoList) {
    
    for (ExtTransactionRetryAttemptInfo extTransactionRetryAttemptInfo : extTxnsAttemptInfoList) {
        String topicName = extTransactionRetryAttemptInfo.getExtendedInfo()
            .get(Constants.TOPIC_NAME).textValue() + "_retry_job";
        sender.send(topicName, extTransactionRetryAttemptInfo.getOrderId(), payload);
    }
}
```

**B. Database Sharding Strategy:**
```java
// Efficient data retrieval with time-based partitioning
@Query(value = "select * from retry_attempt where status = :status 
               and execute_after <= (now() - interval :hours hour) 
               and execute_after > :offset order by execute_after asc limit :limit")
List<ExtTransactionRetryAttemptInfo> getInitiatedAttemptsForFinalJob();
```

### **📊 RESULT**

#### **Quantitative Achievements:**
- **Success Rate**: Achieved **99.5%** refund transaction success rate
- **Transaction Volume**: Successfully processed **3-4 Lakh refund transactions daily**
- **Merchant Coverage**: Supported **80L merchant transactions daily**
- **System Availability**: Maintained **99.9%** uptime for refund processing

#### **Technical Improvements:**
- **Retry Mechanism**: Implemented 4-tier retry system (Initial → Retry1 → Final → Deemed)
- **Fault Tolerance**: Zero message loss with Kafka acknowledgment patterns
- **Processing Efficiency**: Batch processing with configurable batch sizes (100 records/batch)
- **Monitoring Coverage**: 360-degree observability with DataDog metrics

#### **Operational Benefits:**
- **Reduced Manual Intervention**: 95% reduction in manual refund processing
- **SLA Compliance**: Achieved <5 second response time for status checks
- **Error Reduction**: 80% decrease in failed transactions requiring manual resolution
- **Cost Optimization**: 40% reduction in operational overhead

#### **Architecture Insights Gained:**
- **Event-Driven Design**: Deep understanding of Kafka's role in building resilient data pipelines
- **Distributed Systems**: Expertise in handling eventual consistency and transaction boundaries
- **Monitoring Strategy**: Comprehensive metrics collection for proactive issue detection
- **Scalability Patterns**: Horizontal scaling through job distribution and rate limiting

This implementation demonstrates proficiency in building enterprise-grade, fault-tolerant systems that handle high-volume financial transactions with stringent reliability requirements.

# **🎯 Comprehensive Cross-Questions & Detailed Answers**

## **1. Technical Deep Dive Questions**

### **Q1: How did you handle the complexity of managing 4-tier retry mechanisms without creating infinite loops?**

**Detailed Answer:**
```java
// Implemented sophisticated retry attempt tracking
@Entity(name = "retry_attempt")
public class ExtTransactionRetryAttemptInfo {
    @Column(name = "attempt_seq")
    private int attemptSeq;
    
    @Column(name = "status")
    private String status; // INITIATED, PROCESSING, SUCCESS, FAILURE, DEEMED
    
    @Column(name = "execute_after")
    private Date executeAfter;
}
```

**Technical Implementation:**
- **Sequence-Based Control**: Each retry attempt has an `attemptSeq` field that increments with each retry
- **Time-Based Execution**: Used `execute_after` timestamp to prevent immediate retries
- **Status Transitions**: Implemented state machine pattern with clear status transitions
- **Database Constraints**: Added unique constraints to prevent duplicate processing

**Infinite Loop Prevention:**
```java
// Maximum retry attempts configured per job type
@Query(value = "select * from retry_attempt where status = :status 
               and execute_after <= now() and execute_after > :offset 
               and attempt_seq > 1 order by execute_after asc limit :limit")
List<ExtTransactionRetryAttemptInfo> getInitiatedAttemptsForInitialJob();
```

**Business Logic:**
- **Retry Limits**: Each job type has maximum retry attempts (Initial → Retry1 → Final → Deemed)
- **Exponential Backoff**: Increasing delays between retries (5 seconds → 30 seconds → 1 minute → 15 minutes)
- **Circuit Breaker**: After final retry, transactions move to DEEMED status for manual review

---

### **Q2: How did you ensure exactly-once processing in your Kafka implementation?**

**Detailed Answer:**

**Kafka Configuration:**
```java
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions}",
              groupId = "preapproved_refund_transactions_group",
              containerFactory = "kafkaListenerContainerFactoryForPreApprovedRefundTxnReciever")
public void recievePreApprovedRefundTransactionRequest(ConsumerRecord<?, ?> record, Acknowledgment ack) {
    try {
        // Process message
        preApprovedExtTxnsConsumerService.processPreApprovedTransactionRequest(record.value().toString());
        ack.acknowledge(); // Manual acknowledgment only after successful processing
    } catch (Exception e) {
        // Exception handling without acknowledgment triggers retry
        throw e;
    }
}
```

**Idempotency Implementation:**
```java
// Database-level idempotency check
public boolean checkIfNeedToProcessTransaction(String payload) {
    RefundInfo existingRefund = refundInfoRepository.findByPgRefundId(refundId);
    
    // Skip processing if already processed
    if (existingRefund != null && 
        (ONLINE_REFUND_TXN_STATUS.SUCCESS.name().equals(existingRefund.getStatus()) ||
         ONLINE_REFUND_TXN_STATUS.PROCESSING.name().equals(existingRefund.getStatus()))) {
        return false;
    }
    return true;
}
```

**Three-Layer Approach:**
1. **Kafka Level**: Manual acknowledgment with proper error handling
2. **Database Level**: Unique constraints on `pg_refund_id` and `order_id`
3. **Application Level**: Idempotency checks before processing

---

### **Q3: How did you handle database connection pooling and transaction management across multiple microservices?**

**Detailed Answer:**

**Connection Pool Configuration:**
```java
@Configuration
public class DataSourceConfiguration {
    
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.refund-master")
    public DataSource refundMasterDataSource() {
        return DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .build();
    }
    
    @Bean
    @ConfigurationProperties("spring.datasource.refund-slave")
    public DataSource refundSlaveDataSource() {
        return DataSourceBuilder.create()
            .type(HikariDataSource.class)
            .build();
    }
}
```

**Transaction Management:**
```java
// Separate transaction managers for different data sources
@Transactional(transactionManager = "refundMasterTransactionManager", rollbackFor = Exception.class)
public ONLINE_REFUND_RESPONSE checkRefundAmountAndUpdateStatusPlusCreateAttemptV2(
    ExtTxnRequestDTO extTxnRequestDTO, RefundOrgTxnInfo refundOrgTxnInfo, RefundInfo refundInfo) {
    
    // Business logic with proper transaction boundaries
    refundOrgTxnInfoRepository.findById(refundOrgTxnInfo.getId()); // SELECT FOR UPDATE
    
    // Atomic operations within transaction
    updateOnlineRefundStatus(refundInfo.getPgRefundId(), ONLINE_REFUND_TXN_STATUS.PROCESSING.name());
    retryDataService.saveRetryAttempt(initialRetryAttempt);
    
    return ONLINE_REFUND_RESPONSE.SUCCESS_RESPONSE;
}
```

**Key Strategies:**
- **Master-Slave Pattern**: Read operations on slave, write operations on master
- **Connection Pooling**: HikariCP for optimal connection management
- **Transaction Boundaries**: Clear transaction scopes with proper rollback mechanisms
- **Deadlock Prevention**: Consistent ordering of database operations

---

## **2. Scale and Performance Questions**

### **Q4: How did you optimize the system to handle 3-4 Lakh transactions daily? What were the bottlenecks?**

**Detailed Answer:**

**Identified Bottlenecks:**
1. **Database Lock Contention**: Multiple jobs accessing same records
2. **Kafka Consumer Lag**: Single-threaded message processing
3. **Memory Usage**: Large batch sizes causing OOM issues
4. **Network Timeouts**: Downstream service call failures

**Optimization Strategies:**

**1. Database Optimization:**
```java
// Batch processing with configurable batch sizes
private static final int EXT_TRANSACTIONS_CHECK_STATUS_BATCH_SIZE = 100;

@Query(value = "select * from retry_attempt where status = :status 
               and execute_after <= now() and execute_after > :offset 
               order by execute_after asc limit :limit", nativeQuery = true)
List<ExtTransactionRetryAttemptInfo> getProcessingAttempts(@Param("status") String status,
                                                          @Param("limit") long limit,
                                                          @Param("offset") String offset);
```

**2. Kafka Optimization:**
```java
// Parallel processing with multiple consumer groups
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions}",
              groupId = "preapproved_refund_transactions_group",
              containerFactory = "kafkaListenerContainerFactoryForPreApprovedRefundTxnReciever",
              concurrency = "3") // Multiple consumer threads
```

**3. Rate Limiting:**
```java
public abstract class RateLimitableReciever {
    protected void rateLimitAccessOfMessage(String topicName, int partition) {
        // Business property-based rate limiting
        Double consumptionRate = getConsumptionRate(topicName);
        if (shouldRateLimit(consumptionRate)) {
            Thread.sleep(calculateDelay(consumptionRate));
        }
    }
}
```

**Performance Metrics Achieved:**
- **Throughput**: 3-4L transactions/day = ~46 transactions/second peak
- **Latency**: <5 seconds for status checks
- **Resource Usage**: 40% reduction in CPU/memory consumption
- **Database Connections**: Optimized pool size (min: 5, max: 20)

---

### **Q5: How did you ensure the system could scale horizontally?**

**Detailed Answer:**

**Horizontal Scaling Strategy:**

**1. Stateless Design:**
```java
// No in-memory state, all data persisted in database
@Component
public class ExtTransactionsCheckStatusJob implements BatchWiseExecutableJob {
    
    @Override
    public BatchWiseExecutableJobResponseDto runBatch() {
        // Fetch data from database
        List<ExtTransactionRetryAttemptInfo> retryAttempts = 
            getExtTxnsRetryAttemptsForCheckStatus(getLastProcessedOffset(), getBatchSize());
        
        // Process and update offset
        return externalTransactionsCheckStatusService.checkStatusExtTransactions(retryAttempts);
    }
}
```

**2. Partition-Based Processing:**
```java
// Kafka partitioning for parallel processing
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions}",
              groupId = "preapproved_refund_transactions_group")
public void recievePreApprovedRefundTransactionRequest(ConsumerRecord<?, ?> record, Acknowledgment ack) {
    // Each partition processed by different consumer instance
    rateLimitAccessOfMessage(topicName, record.partition());
}
```

**3. Job Distribution:**
```java
// Multiple job instances with different configurations
ext.txns.check.status.job=true          // Instance 1
ext.txns.check.status.retry1.job=true   // Instance 2
ext.txns.check.status.final.retry.job=true // Instance 3
```

**Scaling Capabilities:**
- **Vertical Scaling**: Increased batch sizes and thread pools
- **Horizontal Scaling**: Added more job instances and Kafka partitions
- **Auto-scaling**: Kubernetes-based scaling based on queue depth
- **Database Scaling**: Read replicas for query distribution

---

## **3. Failure Scenarios and Handling**

### **Q6: What happens when the database goes down? How did you handle various failure scenarios?**

**Detailed Answer:**

**Database Failure Scenarios:**

**1. Master Database Failure:**
```java
@Retryable(value = Exception.class, maxAttempts = 3, backoff = @Backoff(delay = 1000))
public RefundInfo updateStatus(RefundInfo refundInfo, ONLINE_REFUND_RESPONSE refundResponse) {
    try {
        refundInfo.setStatus(refundResponse.getStatus().name());
        return refundInfoRepository.save(refundInfo);
    } catch (DataAccessException e) {
        // Fallback to slave for read operations
        log.error("Master database unavailable, retrying...", e);
        throw e;
    }
}

@Recover
public RefundInfo updateStatusFallback(Exception exp) throws Exception {
    // Circuit breaker pattern
    log.error("Database operations failed after retries, marking for manual review");
    throw exp;
}
```

**2. Kafka Failure Scenarios:**
```java
// Dead letter queue for failed messages
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions.deadletter}",
              groupId = "preapproved_refund_transactions_dlq_group")
public void handleDeadLetterQueue(ConsumerRecord<?, ?> record, Acknowledgment ack) {
    // Manual intervention required
    log.error("Message failed after all retries: {}", record.value());
    // Store in database for manual processing
    failedMessageService.storeForManualProcessing(record.value().toString());
    ack.acknowledge();
}
```

**3. Downstream Service Failures:**
```java
// Circuit breaker with fallback
@CircuitBreaker(name = "switchv2-service", fallbackMethod = "fallbackCheckStatus")
public PreApprovedCheckStatusResponse checkTransactionStatus(PreApprovedCheckStatusRequest request) {
    return switchV2Client.checkStatus(request);
}

public PreApprovedCheckStatusResponse fallbackCheckStatus(PreApprovedCheckStatusRequest request, Exception ex) {
    // Fallback logic
    return PreApprovedCheckStatusResponse.builder()
        .status("RETRY_LATER")
        .build();
}
```

**Comprehensive Failure Handling:**
- **Database**: Connection pooling, read replicas, retry mechanisms
- **Kafka**: Dead letter queues, manual acknowledgment, partition rebalancing
- **Network**: Circuit breakers, timeout configurations, fallback mechanisms
- **Application**: Health checks, graceful shutdown, error monitoring

---

### **Q7: How did you handle data consistency across multiple microservices?**

**Detailed Answer:**

**Eventual Consistency Strategy:**

**1. Saga Pattern Implementation:**
```java
// Compensating transaction pattern
@Service
public class RefundProcessingSaga {
    
    @Transactional
    public void processRefund(ExtTxnRequestDTO request) {
        try {
            // Step 1: Create refund record
            RefundInfo refundInfo = createRefundInitialRecord(request);
            
            // Step 2: Create retry attempt
            RetryAttempt retryAttempt = createRetryAttempt(request);
            
            // Step 3: Publish to Kafka
            publishToKafka(request);
            
        } catch (Exception e) {
            // Compensating actions
            compensateRefundCreation(request.getExternalTxnId());
            throw e;
        }
    }
}
```

**2. Outbox Pattern:**
```java
// Transactional outbox for guaranteed message delivery
@Entity
public class OutboxEvent {
    private String eventId;
    private String eventType;
    private String payload;
    private Date createdAt;
    private boolean processed;
}

@Transactional
public void processRefundWithOutbox(RefundInfo refundInfo) {
    // Business logic
    refundInfoRepository.save(refundInfo);
    
    // Create outbox event in same transaction
    OutboxEvent event = new OutboxEvent(
        UUID.randomUUID().toString(),
        "REFUND_PROCESSING",
        JsonUtils.toJson(refundInfo),
        new Date(),
        false
    );
    outboxEventRepository.save(event);
}
```

**3. Idempotency Keys:**
```java
// Ensure idempotent operations
@Service
public class IdempotencyService {
    
    public boolean isOperationProcessed(String idempotencyKey) {
        return processedOperationsCache.containsKey(idempotencyKey);
    }
    
    public void markOperationProcessed(String idempotencyKey, Object result) {
        processedOperationsCache.put(idempotencyKey, result);
    }
}
```

**Consistency Mechanisms:**
- **Transactional Boundaries**: Clear transaction scopes within services
- **Event Sourcing**: Audit trail of all state changes
- **Reconciliation Jobs**: Periodic consistency checks
- **Monitoring**: Real-time inconsistency detection

---

## **4. Architecture and Design Questions**

### **Q8: Why did you choose this specific architecture? What were the trade-offs?**

**Detailed Answer:**

**Architecture Decision Matrix:**

**1. Microservices vs Monolith:**
```
Chosen: Microservices
Reasons:
- Independent deployments (upi-jobs, upi-consumers, upi-refund-service)
- Technology diversity (different optimization needs)
- Team ownership and scalability
- Fault isolation

Trade-offs:
- Increased complexity in distributed debugging
- Network latency between services
- Data consistency challenges
- Operational overhead
```

**2. Event-Driven Architecture:**
```java
// Asynchronous processing with Kafka
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions}")
public void processRefundRequest(ConsumerRecord<?, ?> record, Acknowledgment ack) {
    // Decoupled processing
    preApprovedExtTxnsConsumerService.processPreApprovedTransactionRequest(record.value().toString());
    ack.acknowledge();
}
```

**Benefits:**
- **Loose Coupling**: Services communicate through events
- **Scalability**: Independent scaling of consumers
- **Resilience**: Failures don't cascade immediately
- **Audit Trail**: All events are logged

**Trade-offs:**
- **Eventual Consistency**: Not immediate consistency
- **Complexity**: Message ordering and deduplication
- **Debugging**: Distributed tracing required
- **Monitoring**: More complex observability needs

**3. Database Strategy:**
```java
// Master-slave configuration
@Transactional(transactionManager = "refundMasterTransactionManager") // Writes
public RefundInfo saveRefundInfo(RefundInfo refundInfo) {
    return refundInfoRepository.save(refundInfo);
}

@Transactional(transactionManager = "refundSlaveTransactionManager", readOnly = true) // Reads
public List<RefundInfo> getRefundInfo(String txnId) {
    return refundInfoRepository.findByTxnId(txnId);
}
```

**Alternative Considered:**
- **Single Database**: Simpler but scaling bottleneck
- **Database per Service**: More isolation but complex joins
- **NoSQL**: Better scaling but ACID compliance issues

---

### **Q9: How did you design the retry mechanism? What patterns did you use?**

**Detailed Answer:**

**Retry Strategy Design:**

**1. Exponential Backoff with Jitter:**
```java
public class RetryDelayCalculator {
    
    private static final long[] RETRY_DELAYS = {
        5 * 1000,      // 5 seconds
        30 * 1000,     // 30 seconds
        1 * 60 * 1000, // 1 minute
        15 * 60 * 1000, // 15 minutes
        60 * 60 * 1000  // 1 hour
    };
    
    public long calculateDelay(int attemptNumber) {
        long baseDelay = RETRY_DELAYS[Math.min(attemptNumber - 1, RETRY_DELAYS.length - 1)];
        // Add jitter to prevent thundering herd
        return baseDelay + (long)(Math.random() * 1000);
    }
}
```

**2. Circuit Breaker Pattern:**
```java
@Component
public class RefundCircuitBreaker {
    
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final int threshold = 5;
    private final long timeout = 60000; // 1 minute
    
    public boolean isCircuitOpen() {
        if (failureCount.get() >= threshold) {
            return System.currentTimeMillis() - lastFailureTime.get() < timeout;
        }
        return false;
    }
}
```

**3. Retry State Machine:**
```java
public enum RetryState {
    INITIATED,
    PROCESSING,
    SUCCESS,
    FAILURE,
    DEEMED;
    
    public static RetryState getNextState(RetryState current, boolean isSuccess) {
        switch (current) {
            case INITIATED:
                return isSuccess ? SUCCESS : PROCESSING;
            case PROCESSING:
                return isSuccess ? SUCCESS : FAILURE;
            case FAILURE:
                return DEEMED; // Final state
            default:
                return current;
        }
    }
}
```

**Design Patterns Used:**
- **Command Pattern**: Encapsulated retry logic
- **Strategy Pattern**: Different retry strategies per job type
- **State Machine**: Clear state transitions
- **Observer Pattern**: Event notification on state changes

---

## **5. Monitoring and Observability Questions**

### **Q10: How did you implement monitoring? What metrics did you track?**

**Detailed Answer:**

**Monitoring Architecture:**

**1. Application Metrics:**
```java
// Custom metrics with DataDog
@MonitoringEntities({
    @MonitoringEntity(operand = MonitoringEntity.Datatype.REFUND_RESP_CODE,
                     operations = {MonitoringEntity.Operations.LOG, MonitoringEntity.Operations.DATA_DOG_INCR}),
    @MonitoringEntity(operand = MonitoringEntity.Datatype.TIME_DATA,
                     operations = {MonitoringEntity.Operations.LOG, MonitoringEntity.Operations.DATA_DOG_TIME})
})
public class RefundController {
    
    @PostMapping("/refund/process")
    public ProcessOnlineRefundResponse processRefund(@RequestBody ExtTxnRequestDTO request) {
        // Automatic metrics collection through AOP
        return refundService.processExternalTxn(request);
    }
}
```

**2. Business Metrics:**
```java
// Journey time tracking
private void pushJourneyTime(RefundInfo refundInfo) {
    long pollTimeInMillis = System.currentTimeMillis() - refundInfo.getCreateDate().getTime();
    datadogUtility.recordExecutionTime(
        Enums.METRIC_NAME.REFUND_POLL_TIME.name(), 
        pollTimeInMillis,
        Enums.METRIC_TAG.BUSINESS_TYPE.name() + ":" + Constants.REFUND_BUSINESS_TYPE_AT_EXT_SERVICE,
        Enums.METRIC_TAG.CHANNEL_CODE.name() + ":" + refundInfo.getChannel()
    );
}

// Success rate tracking
datadogUtility.recordHistogram(
    Enums.METRIC_KEY.EXTERNAL_TXN_CHECK_STATUS_SUCCESS.getValue(),
    response.getNoOfRecordsProcessed(),
    Enums.METRIC_TAG.TYPE.getValue() + ":" + getType().name()
);
```

**3. Infrastructure Metrics:**
```java
// Kafka consumer lag monitoring
@KafkaListener(topics = "${kafka.topic.preapproved.refund.transactions}")
public void processMessage(ConsumerRecord<?, ?> record, Acknowledgment ack) {
    long lag = System.currentTimeMillis() - record.timestamp();
    datadogUtility.recordExecutionTime(
        Enums.METRIC_KEY.CONSUMER_LAG.getValue(),
        lag,
        "topic:" + record.topic(),
        "partition:" + record.partition()
    );
}
```

**Key Metrics Tracked:**
- **Business Metrics**: Success rate, processing time, transaction volume
- **Technical Metrics**: Error rates, response times, queue depths
- **Infrastructure Metrics**: CPU, memory, database connections
- **Custom Metrics**: Retry attempts, circuit breaker states

**Alerting Strategy:**
- **Success Rate < 99%**: Immediate alert to on-call team
- **Processing Time > 10s**: Warning alert
- **Error Rate > 1%**: Critical alert
- **Queue Depth > 1000**: Scaling alert

---

### **Q11: How did you handle debugging in a distributed system?**

**Detailed Answer:**

**Distributed Tracing Strategy:**

**1. Correlation IDs:**
```java
// Request correlation across services
public class RequestCorrelationFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        String correlationId = UUID.randomUUID().toString();
        MDC.put("correlationId", correlationId);
        
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlationId");
        }
    }
}
```

**2. Structured Logging:**
```java
// Consistent log format across services
@Component
public class RefundService {
    
    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    
    public void processRefund(ExtTxnRequestDTO request) {
        log.info("Processing refund request", 
                kv("refundId", request.getExternalTxnId()),
                kv("orgTxnId", request.getOrgTxnId()),
                kv("amount", request.getTxnAmount()),
                kv("channel", request.getChannelCode()));
    }
}
```

**3. Event Sourcing for Audit:**
```java
// Complete audit trail
@Entity
public class RefundEvent {
    private String eventId;
    private String refundId;
    private String eventType;
    private String previousState;
    private String newState;
    private Date timestamp;
    private String userId;
    private JsonNode eventData;
}
```

**Debugging Tools:**
- **Distributed Tracing**: OpenTracing/Jaeger for request flows
- **Log Aggregation**: ELK stack for centralized logging
- **Metrics Dashboard**: Grafana for real-time monitoring
- **Error Tracking**: Sentry for exception tracking

---



# ** Bulk Uploader **

## **1. Amazon Leadership Principle Connection**

**Customer Obsession** is the most relevant Leadership Principle for your work. This principle emphasizes starting with the customer and working backwards, earning and maintaining customer trust through delivering results. Your initiative directly addresses a critical customer pain point - the lack of cancellation options after booking - which impacts customer trust and booking confidence.

## **2. STAR Format Response (Theory-Heavy)**

**Situation**: 
As a senior engineer in the hotel booking platform, I identified a critical gap in our system where many rateplans lacked cancellation policies, leaving customers without cancellation options after booking. This created poor customer experience and reduced booking confidence, as users couldn't understand their cancellation rights upfront. Our platform had thousands of hoteliers with existing rateplans that needed to be brought into compliance with our new cancellation policy requirements.

**Task**: 
My responsibility was to design and implement a comprehensive solution that would: (1) Make cancellation policies mandatory for all rateplans going forward, (2) Provide a seamless migration path for existing hoteliers to add cancellation policies to their rateplans, and (3) Create efficient bulk management tools to handle large-scale policy attachments across thousands of properties.

**Action**: 
I architected a multi-layered solution spanning both inventory management and orchestration services, with a particular focus on building a robust CSV-based bulk uploader system:

*CSV-Based Bulk Uploader Architecture*:
- Designed a configurable bulk uploader framework with multiple specialized uploaders: "Cancellation Policy Creation Uploader" (ID: 15), "RatePlan Creation/Updation Uploader" (ID: 14), and "New Cancellation Policy Bulk Uploader" (ID: 109)
- Implemented a plugin-based architecture where each uploader was defined by configuration including auth groups, field mappings, validation rules, and processing functions
- Created a sophisticated CSV manipulation system with `convert_cancellation_policy_csv()` function that could merge multiple policy rules into consolidated entries based on hotel/rateplan combinations

*Technical CSV Processing Implementation*:
- Built a two-phase processing system: CSV parsing/validation phase followed by business logic execution
- Implemented field validation with configurable required fields: `['ingo_hotel_code', 'vendor_source_code', 'vendor_room_code', 'related_to', 'vendor_rateplan_code', 'policy_start_date', 'policy_end_date', 'no_show_charges_in_percent', 'offset_day', 'charge_type', 'charge_value']`
- Created intelligent data aggregation logic that combined multiple CSV rows representing different policy rules into single policy objects using semicolon-separated values
- Designed batch processing with configurable limits (max 1000-3000 lines) and batch counts for memory optimization

*Advanced Bulk Operations*:
- Implemented "attach_all_rateplans" functionality that could automatically fetch and attach policies to all qualifying rateplans in a hotel using `fetch_all_rateplans()` with policy type filtering
- Created template-based policy creation supporting predefined templates (FC, FC_24H, FC_48H, NR, CUSTOM) with automatic policy rule generation
- Built sophisticated validation layers including hotel existence checks, rateplan validation, policy template validation, and business rule enforcement
- Designed error handling with detailed error categorization and line-by-line processing results

*System Integration & API Design*:
- Developed gRPC services for both GetCancellationPolicy and UpsertCancellationPolicy operations with proper request/response handling
- Implemented correlation key tracking for request tracing and debugging across the bulk upload pipeline
- Created reservation service integration with proper metadata handling for user context, request IDs, and source tracking
- Built retry mechanisms and circuit breaker patterns for external service calls during bulk operations

*Data Consistency & Transaction Management*:
- Implemented atomic operations where policy creation and rateplan attachment happened as coordinated transactions
- Created rollback mechanisms for partial failures during bulk operations
- Designed idempotent operations using correlation keys to prevent duplicate processing during retries
- Built comprehensive logging with operation tracking, error categorization, and audit trails

*User Experience & Interface Design*:
- Created intuitive CSV templates with clear field descriptions and sample data
- Implemented progressive validation that provided immediate feedback on CSV format issues before processing
- Built comprehensive error reporting with line-by-line validation results and suggested corrections
- Designed batch status tracking allowing users to monitor long-running bulk operations

**Result**: 
The CSV-based bulk uploader system processed over 10,000+ policy attachments across thousands of properties with 99.5% success rate. The intelligent CSV manipulation reduced hotelier effort by 80% through automatic rule aggregation and template-based creation. The system's flexibility supported complex scenarios including blackout dates, custom policy rules, and multi-vendor configurations. Most importantly, customers now had clear visibility into cancellation terms before booking, increasing booking confidence by 25% and reducing support tickets related to cancellation confusion by 60%.

## **3. Comprehensive Interview Questions & Theoretical Answers**

**Q1: How did you design the CSV parsing and validation system to handle complex policy rules?**

**A1**: I implemented a multi-stage CSV processing pipeline. First, I created a schema validation layer that checked field presence and data types. Then I built an intelligent aggregation system using `convert_cancellation_policy_csv()` that combined multiple CSV rows into consolidated policy objects. The system used composite keys (vendor_code + hotel_code + room_code + rateplan_code + date_range) to group related rules, then concatenated values using semicolons for offset_day, charge_type, and charge_value fields. This allowed hoteliers to define complex policies with multiple time-based rules in a simple tabular format.

**Q2: What strategies did you use to ensure CSV bulk operations could scale to thousands of hotels?**

**A2**: I implemented several scaling strategies: (1) Configurable batch processing with limits of 1000-3000 lines per upload to manage memory usage, (2) Parallel processing for independent hotel operations while maintaining data consistency, (3) Lazy loading of hotel and rateplan data with caching to reduce database calls, (4) Streaming CSV processing to handle large files without loading everything into memory, and (5) Database connection pooling with transaction batching to optimize database performance.

**Q3: How did you handle CSV format validation and provide meaningful error feedback?**

**A3**: I created a comprehensive validation framework with multiple layers: (1) Schema validation for required fields and data types, (2) Business logic validation for date formats, percentage ranges, and policy rule consistency, (3) Cross-reference validation checking hotel codes, rateplan codes, and vendor configurations against our database. The system provided line-by-line error reporting with specific error codes and suggested corrections, making it easy for users to fix issues and resubmit.

**Q4: What was your approach to handling different CSV formats for different use cases?**

**A4**: I designed a configurable uploader framework where each bulk operation was defined by a configuration object specifying required fields, validation rules, and processing functions. This allowed me to support multiple CSV formats: basic cancellation policy creation, rateplan creation with policy attachment, and comprehensive policy upsert operations. Each format had its own field mapping, validation logic, and sample templates, while sharing common infrastructure for CSV parsing and error handling.

**Q5: How did you implement the "attach_all_rateplans" functionality in the CSV system?**

**A5**: I created an intelligent policy attachment system using the `get_applicable_rateplans()` function. When the CSV specified "attach_all_rateplans=true", the system would query all active rateplans for the hotel, filtering by policy type (Normal vs Hourly) and excluding group contract types. The system used `fetch_all_rateplans()` with room-based filtering to ensure only compatible rateplans were included. This reduced CSV complexity from requiring individual rateplan specification to a simple boolean flag.

**Q6: What error recovery and retry mechanisms did you build into the CSV processing?**

**A6**: I implemented a multi-level error recovery system: (1) Line-level validation that allowed processing to continue for valid rows even when some rows failed, (2) Transaction-level retry for temporary service failures using exponential backoff, (3) Correlation key tracking to enable safe operation retries without duplicating data, (4) Partial success reporting that clearly indicated which operations succeeded and which required attention, and (5) Resume functionality allowing users to reprocess only failed entries.

**Q7: How did you optimize the CSV processing performance for large bulk uploads?**

**A7**: I implemented several performance optimizations: (1) Streaming CSV parsing to avoid loading entire files into memory, (2) Database query optimization using bulk lookups for hotel and rateplan validation, (3) Connection pooling and prepared statements for database operations, (4) Parallel processing for independent operations while maintaining data consistency, (5) Caching of frequently accessed data like policy templates and vendor configurations, and (6) Batch API calls to external services to reduce network overhead.

**Q8: What validation logic did you implement for complex policy rules in CSV format?**

**A8**: I built comprehensive validation including: (1) Date format validation ensuring policy_start_date and policy_end_date followed YYYY-MM-DD format, (2) Policy rule consistency checking that offset_day, charge_type, and charge_value arrays had equal lengths, (3) Business rule validation ensuring percentage values were 0-100 and offset days were 0-365, (4) Template compatibility checking that custom policies had required fields while standard templates used predefined rules, and (5) Cross-validation ensuring vendor codes, hotel codes, and rateplan codes existed and were properly associated.

**Q9: How did you handle CSV data transformation for different policy templates?**

**A9**: I created a template-based transformation system where the CSV input was mapped to different policy structures based on the template_id field. For standard templates (FC, NR, etc.), the system automatically generated policy rules without requiring detailed CSV specification. For custom templates, I implemented a policy rule parser that converted semicolon-separated CSV values into structured policy rule objects with chargeValue, chargeType, day, and time fields. The system also handled special cases like automatic addition of 365-day rules for policy completeness.

**Q10: What monitoring and logging did you implement for CSV bulk operations?**

**A10**: I implemented comprehensive monitoring including: (1) Operation-level tracking with correlation keys for end-to-end traceability, (2) Performance metrics capturing processing times, success rates, and error categorization, (3) Business metrics tracking policy attachment rates and template usage patterns, (4) Real-time alerts for bulk operation failures or performance degradation, (5) Audit logs capturing all policy changes with user attribution and timestamps, and (6) Dashboard visualization showing bulk upload trends and system health metrics.

**Q11: How did you ensure data consistency during CSV bulk operations across multiple services?**

**A11**: I implemented a distributed transaction pattern using: (1) Two-phase commit for critical operations ensuring policy creation and rateplan attachment happened atomically, (2) Compensating transactions for rollback scenarios when partial operations failed, (3) Idempotent operation design using correlation keys to prevent duplicate processing, (4) Event-driven consistency where policy updates triggered downstream synchronization, and (5) Eventual consistency monitoring to detect and resolve any data mismatches between services.

**Q12: What user experience considerations did you incorporate into the CSV bulk uploader design?**

**A12**: I focused on minimizing user friction through: (1) Intuitive CSV templates with clear field descriptions and sample data, (2) Progressive validation providing immediate feedback on format issues before processing, (3) Comprehensive error reporting with line-by-line results and suggested corrections, (4) Batch status tracking allowing users to monitor long-running operations, (5) Resume functionality for failed operations, and (6) Multiple CSV formats supporting different user workflows from simple policy creation to complex multi-hotel operations.