# MakeMyTrip - Tech Stack Reference Guide

> Quick reference for all technologies, frameworks, and tools used across MakeMyTrip platform

---

## 📋 Quick Summary

| Category | Technologies |
|----------|-------------|
| **Languages** | Go, Java, Python |
| **Communication** | gRPC, GraphQL, REST |
| **Databases** | MySQL, MongoDB, Cassandra |
| **Caching** | Redis, Aerospike |
| **Messaging** | Apache Kafka |
| **Infrastructure** | Docker, Kubernetes, AWS |
| **Monitoring** | Prometheus, Grafana, Jaeger |

---

## 🔤 Programming Languages & Frameworks

### Go (Golang) - 70% of Services

**Version**: Go 1.23

**Services Using Go**:
- INGO-Enigma (GraphQL Gateway)
- INGO-Goku (Search & Availability)
- INGO-Phoenix (Inventory Management)
- INGO-ReservationEngine (Booking)
- INGO-PriceEngine (Pricing Library)
- INGO-Promo-Genie (Promotions)
- INGO-Nexus-Partner (Partner Hub)
- INGO-Dynamic-Hub (Configuration Hub)
- INGO-Hotel-Supply-Content
- INGO-Hotel-Supply-Inclusions
- INGO-Hotel-Supply-Interlink
- INGO-Hotel-Supply-Invoice

**Key Go Libraries**:

```go
// HTTP & Web Framework
"github.com/gin-gonic/gin"              // v1.9.1 - HTTP web framework

// gRPC
"google.golang.org/grpc"                // v1.65.0 - RPC framework
"google.golang.org/protobuf"            // v1.36.7 - Protocol buffers
"github.com/golang/protobuf"            // v1.5.4

// GraphQL
"github.com/99designs/gqlgen"           // v0.17.70 - GraphQL server
"github.com/vektah/gqlparser/v2"        // v2.5.25 - GraphQL parser

// Database Drivers
"github.com/go-sql-driver/mysql"       // v1.8.1 - MySQL driver
"go.mongodb.org/mongo-driver"          // MongoDB driver

// Caching
"github.com/go-redis/redis/v8"         // v8.11.5 - Redis client
"github.com/aerospike/aerospike-client-go/v8" // v8.5.0 - Aerospike

// Kafka
"github.com/Shopify/sarama"            // v1.38.1 - Kafka client
"gopkg.in/Shopify/sarama.v1"          // v1.20.1

// Configuration
"github.com/spf13/viper"               // v1.18.2 - Config management

// Logging
"go.uber.org/zap"                      // v1.27.0 - Structured logging

// AWS SDK
"github.com/aws/aws-sdk-go"            // v1.53.10

// Utilities
"github.com/google/uuid"               // v1.6.0 - UUID generation
"github.com/mailru/easyjson"          // v0.7.7 - Fast JSON
"github.com/jinzhu/copier"            // v0.3.5 - Struct copying

// Testing
"github.com/stretchr/testify"         // v1.10.0
"github.com/golang/mock"              // v1.6.0
```

**Why Go?**
- ✅ High performance (compiled, statically typed)
- ✅ Built-in concurrency (goroutines, channels)
- ✅ Fast compilation
- ✅ Small binary size
- ✅ Excellent standard library
- ✅ Native gRPC support

---

### Java (Spring Boot) - 20% of Services

**Version**: Java 8

**Framework**: Spring Boot 2.0.0

**Services Using Java**:
- Hotels-content-services (Content aggregation)

**Key Java Dependencies**:

```xml
<!-- Spring Boot -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
    <version>2.0.0.RELEASE</version>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>

<!-- Data Access -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-mongodb-reactive</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>

<!-- MySQL -->
<dependency>
    <groupId>mysql</groupId>
    <artifactId>mysql-connector-java</artifactId>
</dependency>

<!-- Connection Pool -->
<dependency>
    <groupId>com.jolbox</groupId>
    <artifactId>bonecp</artifactId>
    <version>0.8.0.RELEASE</version>
</dependency>

<!-- Kafka -->
<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-clients</artifactId>
    <version>0.11.0.0</version>
</dependency>

<dependency>
    <groupId>org.apache.kafka</groupId>
    <artifactId>kafka-streams</artifactId>
    <version>0.11.0.0</version>
</dependency>

<!-- Solr -->
<dependency>
    <groupId>org.apache.solr</groupId>
    <artifactId>solr-solrj</artifactId>
</dependency>

<!-- Circuit Breaker -->
<dependency>
    <groupId>com.netflix.hystrix</groupId>
    <artifactId>hystrix-core</artifactId>
    <version>1.5.12</version>
</dependency>

<!-- Utilities -->
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
    <version>19.0</version>
</dependency>

<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <version>1.16.20</version>
</dependency>

<!-- Error Tracking -->
<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry-logback</artifactId>
    <version>1.6.4</version>
</dependency>
```

**Why Java/Spring Boot?**
- ✅ Rich ecosystem for enterprise features
- ✅ Excellent for content processing
- ✅ Spring Data simplifies data access
- ✅ Mature reactive programming support
- ✅ Strong integration libraries

---

### Python (Django) - 10% of Services

**Version**: Python 2.7 (legacy), Python 3.x (newer services)

**Framework**: Django 4.2 (for Python 3.x services)

**Services Using Python**:
- INGO-Inventory (Legacy inventory management)
- INGO-Configkeeper (Configuration management)
- INGO-Sandesh (Notifications)
- INGO-hotel-supply-analytics-workflow

**Key Python Libraries**:

```python
# Web Framework
Django==4.2.23
djangorestframework==3.15.2
drf-spectacular==0.27.2          # OpenAPI/Swagger

# Database
mysqlclient==2.2.7
mongoengine==0.19.1
django-rest-framework-mongoengine==3.4.0
cassandra-driver==3.23.0
psycopg2==2.7.1                  # PostgreSQL

# Caching
django-redis==5.4.0
redis==2.10.3

# Aerospike
aerospike==4.0.0

# Kafka
confluent-kafka==0.11.0
kafka-python==1.2.5

# Search
elasticsearch==6.3.1
elasticsearch-dsl==6.4.0

# Celery (Async Tasks)
celery==3.1.17
celery-with-redis==3.0

# Authentication
python3-saml==1.16.0
django-auth-ldap==4.8.0
django-guardian==2.4.0           # Object permissions

# API Tools
requests==2.32.3
django-request-id==1.0.0

# AWS
boto3==1.14.26
botocore==1.17.26

# Utilities
six==1.16.0
pytz==2024.2
croniter==3.0.3
pandas==0.20.3
numpy==1.10.4

# Testing
pytest==4.3.0
mock==2.0.0
coverage==7.6.9

# Server
uwsgi
supervisor
```

**Why Python/Django?**
- ✅ Rapid development for admin tools
- ✅ Excellent Django admin interface
- ✅ Rich data processing libraries
- ✅ Good for analytics and batch processing
- ✅ Easy integration with legacy systems

---

## 🔌 Communication Protocols

### 1. gRPC - Internal Communication

**Usage**: Service-to-service communication

**Proto Files**: 77+ .proto definitions

**Protocol Buffer Version**: protobuf 3

**Key Features**:
```
✓ Binary protocol (efficient)
✓ HTTP/2 based
✓ Bi-directional streaming
✓ Strong typing
✓ Code generation
✓ 2-5ms latency
```

**Tools**:
```bash
protoc-gen-go              # Go code generation
protoc-gen-go-grpc         # Go gRPC code generation
grpcurl                    # gRPC curl-like tool
grpc-gateway              # gRPC to REST proxy
```

### 2. GraphQL - Client APIs

**Usage**: Mobile and web client communication

**Implementation**: gqlgen (Go)

**Schema Files**: 363+ .graphql files

**Key Features**:
```
✓ Single endpoint
✓ Client-defined queries
✓ Strong typing
✓ Real-time subscriptions
✓ Schema introspection
✓ 50-200ms latency
```

**Tools**:
```bash
gqlgen                    # GraphQL server generator
GraphQL Playground        # API explorer
Apollo Client            # Client library
```

### 3. REST - Partner APIs

**Usage**: Third-party and partner integration

**Framework**: Gin (Go), Spring Boot (Java)

**Key Features**:
```
✓ HTTP/1.1 based
✓ Universal compatibility
✓ Easy debugging
✓ Well-understood
✓ 100-500ms latency
```

---

## 💾 Data Storage

### 1. MySQL - Relational Database

**Version**: MySQL 8.0

**Usage**: 
- Booking records
- User data
- Inventory state
- Rate plans
- Transactions

**Configuration**:
```
Sharding: 16 shards (by hotel_id)
Replication: Master-slave (3 replicas per shard)
Connection Pool: 100 max connections
Engine: InnoDB
Charset: utf8mb4
```

**Drivers**:
```
Go: github.com/go-sql-driver/mysql v1.8.1
Java: mysql-connector-java
Python: mysqlclient==2.2.7
```

### 2. MongoDB - Document Database

**Version**: MongoDB 5.0

**Usage**:
- Hotel content
- Reviews and ratings
- Metadata
- Flexible schemas

**Configuration**:
```
Replica Set: 3 nodes
Storage Engine: WiredTiger
Index: Compound indexes for common queries
Size: 10TB+
```

**Drivers**:
```
Java: spring-boot-starter-data-mongodb
Python: mongoengine==0.19.1
```

### 3. Cassandra - Wide-Column Store

**Version**: Cassandra 4.0

**Usage**:
- Time-series data (availability)
- Search events
- Audit logs
- High write throughput data

**Configuration**:
```
Replication Factor: 3
Consistency: QUORUM
Partitioning: By hotel_id + date
Compaction: Size-tiered
```

**Driver**:
```
Python: cassandra-driver==3.23.0
```

### 4. Aerospike - In-Memory Database

**Version**: Latest

**Usage**:
- Search results cache
- Pricing cache
- Inventory cache
- Session data

**Configuration**:
```
Cluster: 10 nodes
Replication: 2x
Storage: Hybrid (memory + SSD)
Latency: <1ms
```

**Drivers**:
```
Go: github.com/aerospike/aerospike-client-go/v8 v8.5.0
Python: aerospike==4.0.0
```

### 5. Redis - Key-Value Store

**Version**: Redis 7

**Usage**:
- Session management
- Pub/Sub messaging
- Distributed locks
- Rate limiting

**Configuration**:
```
Mode: Cluster
Eviction: LRU
Persistence: AOF + RDB
Max Memory: 16GB per node
```

**Drivers**:
```
Go: github.com/go-redis/redis/v8 v8.11.5
Python: redis==2.10.3
Java: Jedis/Lettuce
```

---

## 📨 Messaging & Streaming

### Apache Kafka

**Version**: Confluent Kafka / Apache Kafka 2.x

**Usage**:
- Event streaming
- Async communication
- Service decoupling
- Event sourcing

**Topics**: 50+ topics

**Configuration**:
```
Brokers: 10 nodes
Partitions: 100+ per topic (based on volume)
Replication Factor: 3
Retention: 7 days (configurable)
```

**Clients**:
```
Go: github.com/Shopify/sarama v1.38.1
Java: kafka-clients 0.11.0.0
Python: confluent-kafka==0.11.0
```

**Throughput**: 100,000+ events/second

---

## ☁️ Infrastructure & DevOps

### Container Orchestration

**Docker**:
```
Version: Latest stable
Images: Alpine-based (minimal)
Registry: Private registry
```

**Kubernetes**:
```
Version: 1.25+
Ingress: NGINX Ingress Controller
Service Mesh: Envoy/Istio
Auto-scaling: HPA (Horizontal Pod Autoscaler)
```

### Cloud Provider (AWS)

**Services Used**:
```
EC2: Compute instances
EKS: Managed Kubernetes
RDS: Managed MySQL
S3: Object storage
Secrets Manager: Secret storage
CloudWatch: Logging & monitoring
Route53: DNS
ELB: Load balancing
VPC: Network isolation
```

### CI/CD

**Tools**:
```
Version Control: Git
CI/CD: Jenkins / GitHub Actions
Build: Docker
Deploy: Kubernetes (kubectl, Helm)
```

### Configuration Management

**Tools**:
```
Viper: Go configuration
Spring Cloud Config: Java
Django Settings: Python
Consul: Service discovery
```

---

## 📊 Observability & Monitoring

### Metrics

**Prometheus**:
```
Version: 2.x
Scrape Interval: 15s
Retention: 30 days
Exporters: Node exporter, Custom app metrics
```

**Grafana**:
```
Version: Latest
Dashboards: 50+ custom dashboards
Data Source: Prometheus, CloudWatch
Alerting: Integrated
```

### Distributed Tracing

**OpenTelemetry**:
```
Instrumentation: Auto + manual
Exporters: Jaeger, Zipkin
Sampling: Probabilistic (10%)
```

**Jaeger**:
```
Version: Latest
Storage: Cassandra
Retention: 7 days
```

### Logging

**Structured Logging**:
```
Go: go.uber.org/zap
Java: Logback + Sentry
Python: Python logging + ELK stack
```

**Log Aggregation**:
```
FluentBit: Log collection
Elasticsearch: Log storage
Kibana: Log visualization
```

### APM

**Tools**:
```
New Relic: Application monitoring
Sentry: Error tracking
DataDog: Infrastructure monitoring
```

---

## 🔐 Security

### Authentication & Authorization

**Protocols**:
```
JWT: API authentication
OAuth 2.0: Third-party integration
SAML: SSO for internal tools
LDAP: User directory
```

**Libraries**:
```
Go: JWT-go
Java: Spring Security
Python: python3-saml, django-auth-ldap
```

### Encryption

**In Transit**:
```
TLS 1.3
Certificate Management: Let's Encrypt / AWS ACM
```

**At Rest**:
```
AES-256
Database encryption: MySQL TDE, MongoDB encryption
```

### Secrets Management

**Tools**:
```
AWS Secrets Manager
Kubernetes Secrets
Hashicorp Vault (optional)
```

---

## 🧪 Testing

### Frameworks

**Go**:
```
Testing: testify v1.10.0
Mocking: golang/mock v1.6.0
HTTP Testing: httptest (standard library)
```

**Java**:
```
Unit: JUnit 4.12
Mocking: Mockito 1.10.19
Integration: Spring Boot Test
```

**Python**:
```
Unit: pytest 4.3.0
Mocking: mock 2.0.0
Coverage: coverage 7.6.9
```

### Test Coverage

**Target**:
```
Unit Test Coverage: 70%+
Integration Test Coverage: 50%+
E2E Test Coverage: Critical paths
```

---

## 🛠️ Development Tools

### IDEs
```
Go: GoLand, VSCode with Go extension
Java: IntelliJ IDEA
Python: PyCharm, VSCode
```

### API Testing
```
Postman: REST API testing
grpcurl: gRPC testing
GraphQL Playground: GraphQL testing
```

### Database Tools
```
MySQL Workbench
MongoDB Compass
Cassandra cqlsh
Redis CLI
DBeaver (universal)
```

### Code Quality
```
SonarQube: Code quality & security
golangci-lint: Go linting
ESLint: JavaScript linting
```

---

## 📦 Package Management

**Go**:
```
go mod (built-in)
go.mod, go.sum files
Private modules: GOPRIVATE env var
```

**Java**:
```
Maven: pom.xml
Repository: Nexus / Artifactory
```

**Python**:
```
pip: requirements.txt
Virtual env: virtualenv, venv
Repository: PyPI / Nexus
```

---

## 🚀 Performance Tools

### Load Testing
```
Apache JMeter
Gatling
k6
```

### Profiling
```
Go: pprof (built-in)
Java: JProfiler, VisualVM
Python: cProfile
```

### Benchmarking
```
Go: testing.B (built-in)
wrk: HTTP benchmarking
redis-benchmark: Redis benchmarking
```

---

## 📈 Key Performance Numbers

### Service Latency
```
gRPC (internal):        2-5ms
GraphQL (client):      50-200ms
REST (partner):       100-500ms
```

### Database Performance
```
MySQL query:           5-20ms
MongoDB query:        10-30ms
Cassandra write:       1-5ms
Aerospike read:        <1ms
Redis operation:       <1ms
```

### Throughput
```
Search requests:      10,000+ req/sec
Booking creation:      1,000+ req/sec
Inventory updates:    50,000+ updates/sec
Kafka throughput:    100,000+ events/sec
```

### Cache Performance
```
Cache hit rate:        85%+
Cache warm-up time:    30 seconds
Cache invalidation:    <100ms
```

---

## 📚 Learning Resources

### Official Documentation
- [Go Documentation](https://go.dev/doc/)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Django Documentation](https://docs.djangoproject.com/)
- [gRPC Documentation](https://grpc.io/docs/)
- [GraphQL Documentation](https://graphql.org/learn/)

### Internal Resources
- **MakeMyTrip_System_Design.md**: Detailed architecture
- **grpc-graphql-rest-blog-article.md**: Protocol comparison
- **Service READMEs**: Service-specific documentation

---

## 🔄 Version Matrix

| Service | Language | Framework | Version |
|---------|----------|-----------|---------|
| INGO-Enigma | Go | gqlgen | v0.17.70 |
| INGO-Goku | Go | gRPC | v1.65.0 |
| INGO-Phoenix | Go | gRPC | v1.62.1 |
| INGO-ReservationEngine | Go | gRPC | v1.65.0 |
| Hotels-Content | Java 8 | Spring Boot | 2.0.0 |
| INGO-Inventory | Python 3 | Django | 4.2.23 |
| INGO-Configkeeper | Python 3 | Django | 4.2.23 |

---

## 📞 Contact & Support

**Platform Team**: platform-team@makemytrip.com  
**DevOps Team**: devops@makemytrip.com  
**Architecture Review**: architecture-review@makemytrip.com

---

**Last Updated**: January 7, 2026  
**Maintained by**: Platform Engineering Team  
**Version**: 1.0

