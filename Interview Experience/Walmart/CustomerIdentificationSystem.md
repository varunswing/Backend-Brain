# Customer Identification System

## **1. Amazon Leadership Principle Connection**

This project demonstrates **Customer Obsession** and **Ownership** principles:

- **Customer Obsession**: By integrating customer data from multiple sources, you're creating a unified view that enables better customer experience, personalized services, and faster resolution of customer issues.
- **Ownership**: Taking responsibility for data quality, consistency, and building a robust system that other teams can depend on for customer insights.

## **2. STAR Format Response (Theory-Heavy)**

### **Situation**
Your organization receives customer data from two high-volume Kafka topics:
- **Topic 1**: .NET details (100K-1M records) with name, phone, email, address
- **Topic 2**: Store details (10M records) with name, phone, credit card, date
- **Challenge**: Create a unified customer table with unique customer IDs for customers present in BOTH data sources

### **Task**
Design and implement a scalable, fault-tolerant system that can:
- Process 10M+ records efficiently
- Perform real-time data matching and deduplication
- Handle high throughput with minimal latency
- Ensure data consistency and integrity
- Provide monitoring and alerting capabilities

### **Action**
**System Architecture Design:**

```
┌─────────────────┐    ┌─────────────────┐
│   Topic 1       │    │   Topic 2       │
│  (.NET Details) │    │ (Store Details) │
│   100K-1M       │    │     10M         │
└─────────────────┘    └─────────────────┘
         │                       │
         ▼                       ▼
┌─────────────────┐    ┌─────────────────┐
│   Consumer 1    │    │   Consumer 2    │
│  (Batch: 50)    │    │  (Batch: 100)   │
└─────────────────┘    └─────────────────┘
         │                       │
         ▼                       ▼
┌─────────────────────────────────────────┐
│        Redis Stream Buffer              │
│      (Temporary Storage)                │
└─────────────────────────────────────────┘
         │                       │
         ▼                       ▼
┌─────────────────────────────────────────┐
│     Customer Matching Engine            │
│    (Spring Boot Service)                │
└─────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────┐
│          MySQL Cluster                  │
│      (Customer Database)                │
└─────────────────────────────────────────┘
```

**Technology Stack Selection:**

1. **Database Choice: MySQL 8.0 with InnoDB**
   - **Why**: Aligns with your existing infrastructure
   - **Benefits**: ACID compliance, excellent performance for structured data, JSON support for flexible schemas
   - **Indexing Strategy**: Composite indexes on (phone, name), individual indexes on customer_id

2. **Caching Layer: Redis**
   - **Why**: Better suited for high-volume data matching than Aerospike
   - **Benefits**: Advanced data structures (Sets, Sorted Sets), pub/sub capabilities, cluster mode
   - **Usage**: Store phone+name combinations for fast lookups

3. **Message Processing: Spring Kafka**
   - **Why**: Consistency with your existing patterns
   - **Configuration**: Batch processing with manual acknowledgment, rate limiting

**Implementation Strategy:**

**Phase 1: Data Ingestion Layer**
```java
@Service
public class CustomerDataIngestionService {
    
    @KafkaListener(topics = "dotnet-details", 
                   containerFactory = "batchListenerFactory")
    public void consumeDotNetDetails(List<ConsumerRecord<String, DotNetCustomer>> records) {
        // Process in batches of 50
        List<CustomerProfile> profiles = records.stream()
            .map(this::mapToCustomerProfile)
            .collect(Collectors.toList());
        
        customerMatchingService.processProfiles(profiles, DataSource.DOTNET);
    }
}
```

**Phase 2: Customer Matching Engine**
```java
@Component
public class CustomerMatchingEngine {
    
    public void matchAndStore(List<CustomerProfile> profiles, DataSource source) {
        // Use Redis for fast lookups
        for (CustomerProfile profile : profiles) {
            String matchKey = generateMatchKey(profile.getPhone(), profile.getName());
            
            // Check if customer exists in both sources
            if (redisService.existsInBothSources(matchKey)) {
                Customer customer = createOrUpdateCustomer(profile);
                customerRepository.save(customer);
            } else {
                // Store in temporary buffer
                redisService.storeProfile(matchKey, profile, source);
            }
        }
    }
}
```

**Phase 3: Database Schema Design**
```sql
CREATE TABLE customers (
    customer_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    phone VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(100),
    address JSON,
    credit_card_info JSON,
    first_seen_date TIMESTAMP,
    last_updated TIMESTAMP,
    data_sources SET('DOTNET', 'STORE'),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    UNIQUE KEY unique_phone_name (phone, name),
    INDEX idx_phone (phone),
    INDEX idx_name (name),
    INDEX idx_customer_id (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**Phase 4: Scalability Optimizations**
- **Connection Pooling**: HikariCP with 100+ connections
- **Batch Processing**: Process 1000 records per database transaction
- **Async Processing**: CompletableFuture for non-blocking operations
- **Circuit Breaker**: Resilience4j for fault tolerance

### **Result**
- **Performance**: System processes 10M+ records/hour with <500ms average latency
- **Scalability**: Horizontal scaling through Kafka partitioning and consumer groups
- **Reliability**: 99.9% uptime with automatic failover and retry mechanisms
- **Data Quality**: 99.5% accuracy in customer matching with comprehensive auditing

## **3. Comprehensive Interview Questions & Theoretical Answers**

### **Q1: How would you handle the scenario where the same customer has different names across the two data sources?**

**Theoretical Answer**: Implement a fuzzy matching algorithm using Levenshtein distance or Jaro-Winkler similarity. Create a configurable threshold (e.g., 85% similarity) and use machine learning models trained on historical data to improve accuracy. Store all name variations in a JSON field for audit purposes.

### **Q2: How would you ensure data consistency across distributed consumers?**

**Theoretical Answer**: Implement distributed locking using Redis or database-level locks. Use eventual consistency patterns with compensation actions. Implement idempotent operations and maintain audit logs for all data changes. Use database transactions with proper isolation levels.

### **Q3: How would you monitor and alert on data quality issues?**

**Theoretical Answer**: Implement comprehensive metrics using Micrometer and DataDog:
- Match rate percentages by time window
- Processing latency per consumer
- Error rates and retry counts
- Data volume anomalies
- Custom business metrics for duplicate detection rates

### **Q4: How would you handle schema evolution in the Kafka topics?**

**Theoretical Answer**: Use Confluent Schema Registry with Avro schemas for backward/forward compatibility. Implement schema versioning strategies. Use consumer lag monitoring to detect processing issues. Implement graceful degradation for unknown fields.

### **Q5: How would you scale this system to handle 100M records per day?**

**Theoretical Answer**: 
- **Horizontal Scaling**: Increase Kafka partitions and consumer instances
- **Database Sharding**: Partition customer data by phone number ranges
- **Caching Strategy**: Implement multi-level caching with Redis Cluster
- **Batch Optimization**: Increase batch sizes and use bulk operations
- **Asynchronous Processing**: Implement event-driven architecture

### **Q6: How would you handle data privacy and compliance requirements?**

**Theoretical Answer**: Implement data masking for sensitive fields, use encryption at rest and in transit, implement audit logging for all access patterns, and create data retention policies. Use role-based access control and implement GDPR-compliant data deletion capabilities.

### **Q7: How would you test this system comprehensively?**

**Theoretical Answer**: Multi-layered testing approach:
- **Unit Tests**: Test individual components with mocked dependencies
- **Integration Tests**: Test Kafka consumers with embedded Kafka
- **Performance Tests**: Load testing with 10M+ records using JMeter
- **Chaos Engineering**: Test failure scenarios and recovery mechanisms
- **Data Quality Tests**: Validate matching accuracy and deduplication logic

### **Q8: How would you implement disaster recovery for this system?**

**Theoretical Answer**: Multi-region deployment with database replication, Kafka mirroring for cross-region data sync, automated backup and restore procedures, and comprehensive monitoring with automatic failover capabilities. Implement RTO/RPO targets and regular disaster recovery testing.

### **Q9: How would you optimize database performance for high-volume operations?**

**Theoretical Answer**: 
- **Indexing Strategy**: Composite indexes on frequently queried columns
- **Query Optimization**: Use EXPLAIN plans and query hints
- **Connection Management**: Optimize connection pooling and statement caching
- **Batch Processing**: Use batch inserts and updates
- **Read Replicas**: Separate read and write workloads

### **Q10: How would you ensure system observability and debugging capabilities?**

**Theoretical Answer**: Implement distributed tracing using OpenTelemetry, structured logging with correlation IDs, custom metrics and dashboards, and comprehensive error tracking. Use ELK stack for log aggregation and analysis. Implement health checks and synthetic monitoring.

This design leverages your existing UPI system patterns while addressing the specific challenges of high-volume customer data integration, ensuring scalability, reliability, and maintainability.
