# Service Discovery

## What is Service Discovery?

Service Discovery is the automatic detection of services and their network locations in a distributed system. It solves the problem of "how do services find each other?"

```
Traditional (Static):
  Service A → config.yaml → "Service B is at 10.0.0.5:8080"
  
Service Discovery (Dynamic):
  Service A → Discovery Service → "Service B is at [10.0.0.5, 10.0.0.6, 10.0.0.7]"
```

---

## Why Service Discovery?

### The Problem
In dynamic environments (cloud, containers, Kubernetes):
- IP addresses change frequently
- Services scale up/down
- Instances fail and restart
- New versions deploy

### Without Service Discovery
```yaml
# Hardcoded configuration - breaks when IPs change
database:
  host: 10.0.0.5
  port: 5432
  
payment-service:
  host: 10.0.0.10
  port: 8080
```

### With Service Discovery
```yaml
# Service names - always works
database:
  service: database-service
  
payment-service:
  service: payment-service
```

---

## Service Discovery Patterns

### 1. Client-Side Discovery

Client queries service registry and load balances requests.

```
┌──────────────┐
│   Client     │
│  (Service A) │
└──────┬───────┘
       │ 1. Query "Service B"
       ▼
┌──────────────┐
│   Service    │
│   Registry   │──→ Returns: [B1:8080, B2:8080, B3:8080]
└──────────────┘
       │
       │ 2. Client picks one (load balance)
       ▼
┌──────────────┐
│  Service B   │
│  (B1:8080)   │
└──────────────┘
```

**Examples**: Netflix Eureka, Consul (client mode)

**Pros**:
- Client controls load balancing
- No single point of failure in routing
- Can implement custom logic

**Cons**:
- Client needs discovery library
- Language-specific implementations
- Client complexity

### 2. Server-Side Discovery

Client talks to load balancer which handles discovery.

```
┌──────────────┐
│   Client     │
│  (Service A) │
└──────┬───────┘
       │ 1. Request to "Service B"
       ▼
┌──────────────┐      ┌──────────────┐
│    Load      │◄────►│   Service    │
│   Balancer   │      │   Registry   │
└──────┬───────┘      └──────────────┘
       │ 2. Route to healthy instance
       ▼
┌──────────────┐
│  Service B   │
│  (B2:8080)   │
└──────────────┘
```

**Examples**: AWS ELB, Kubernetes Services, Nginx

**Pros**:
- Client is simple (just HTTP call)
- Language agnostic
- Centralized control

**Cons**:
- Load balancer is potential bottleneck
- Extra network hop
- More infrastructure

### 3. DNS-Based Discovery

Use DNS to resolve service names to IP addresses.

```
Client: "What's the IP for payment-service.internal?"
DNS: "10.0.0.5, 10.0.0.6, 10.0.0.7"
Client: Connects to one of them
```

**Examples**: Kubernetes DNS, Consul DNS, AWS Route 53

**Pros**:
- Universal support (DNS is everywhere)
- No special client libraries
- Simple to implement

**Cons**:
- DNS caching causes stale data
- Limited health checking
- No sophisticated load balancing

---

## Service Registry Components

### Registration

```
Service starts → Register with registry
{
  "service": "payment-service",
  "instance": "payment-1",
  "host": "10.0.0.5",
  "port": 8080,
  "health_check": "/health",
  "metadata": {
    "version": "2.1.0",
    "region": "us-east-1"
  }
}
```

### Health Checking

```
Registry periodically checks:
  GET http://10.0.0.5:8080/health

Response 200 → Instance healthy → Keep in registry
Response 5xx → Instance unhealthy → Remove from registry
Timeout      → Instance dead → Remove from registry
```

### Deregistration

```
Service stops gracefully → Deregister from registry
Service crashes → Health check fails → Auto-deregister
```

---

## Popular Service Discovery Tools

### 1. Consul (HashiCorp)

```hcl
# Service registration
service {
  name = "payment-service"
  port = 8080
  
  check {
    http = "http://localhost:8080/health"
    interval = "10s"
  }
}
```

```bash
# DNS query
dig @127.0.0.1 -p 8600 payment-service.service.consul

# HTTP API
curl http://localhost:8500/v1/catalog/service/payment-service
```

**Features**:
- Service mesh capabilities
- Key-value store
- Multi-datacenter
- DNS and HTTP interfaces

### 2. etcd

```bash
# Register service
etcdctl put /services/payment/instance1 '{"host":"10.0.0.5","port":8080}'

# Watch for changes
etcdctl watch /services/payment --prefix

# Get all instances
etcdctl get /services/payment --prefix
```

**Features**:
- Strong consistency (Raft)
- Watch for changes
- Used by Kubernetes

### 3. Kubernetes Service Discovery

```yaml
# Service definition
apiVersion: v1
kind: Service
metadata:
  name: payment-service
spec:
  selector:
    app: payment
  ports:
    - port: 80
      targetPort: 8080
```

```python
# Access service by DNS name
import requests
response = requests.get("http://payment-service/api/pay")
```

**Features**:
- Built into Kubernetes
- DNS-based
- Automatic load balancing

### 4. Netflix Eureka

```java
// Service registration (Spring Boot)
@EnableEurekaClient
@SpringBootApplication
public class PaymentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(PaymentServiceApplication.class, args);
    }
}

// Service discovery
@Autowired
private DiscoveryClient discoveryClient;

public List<ServiceInstance> getPaymentInstances() {
    return discoveryClient.getInstances("payment-service");
}
```

**Features**:
- Client-side discovery
- Self-preservation mode
- Spring Cloud integration

---

## Implementation Example

### Self-Registration Pattern

```python
import requests
import atexit
from threading import Thread
import time

class ServiceRegistry:
    def __init__(self, registry_url, service_name, host, port):
        self.registry_url = registry_url
        self.service_name = service_name
        self.instance_id = f"{service_name}-{host}-{port}"
        self.host = host
        self.port = port
        
    def register(self):
        """Register this service instance"""
        data = {
            "id": self.instance_id,
            "name": self.service_name,
            "host": self.host,
            "port": self.port,
            "health_check": f"http://{self.host}:{self.port}/health"
        }
        requests.post(f"{self.registry_url}/register", json=data)
        
        # Start heartbeat thread
        self._start_heartbeat()
        
        # Deregister on shutdown
        atexit.register(self.deregister)
    
    def _start_heartbeat(self):
        """Send periodic heartbeats"""
        def heartbeat():
            while True:
                requests.put(f"{self.registry_url}/heartbeat/{self.instance_id}")
                time.sleep(10)
        
        Thread(target=heartbeat, daemon=True).start()
    
    def deregister(self):
        """Remove this instance from registry"""
        requests.delete(f"{self.registry_url}/deregister/{self.instance_id}")
    
    def discover(self, service_name):
        """Find instances of a service"""
        response = requests.get(f"{self.registry_url}/services/{service_name}")
        return response.json()["instances"]
```

### Client-Side Load Balancing

```python
import random

class ServiceClient:
    def __init__(self, registry, service_name):
        self.registry = registry
        self.service_name = service_name
    
    def call(self, path, method="GET"):
        instances = self.registry.discover(self.service_name)
        
        if not instances:
            raise Exception(f"No instances of {self.service_name} available")
        
        # Round-robin or random selection
        instance = random.choice(instances)
        url = f"http://{instance['host']}:{instance['port']}{path}"
        
        return requests.request(method, url)

# Usage
registry = ServiceRegistry("http://consul:8500", "order-service", "10.0.0.1", 8080)
registry.register()

payment_client = ServiceClient(registry, "payment-service")
response = payment_client.call("/api/charge")
```

---

## Best Practices

### Health Checks
```python
# Implement meaningful health checks
@app.route('/health')
def health():
    checks = {
        "database": check_database(),
        "cache": check_cache(),
        "dependencies": check_dependencies()
    }
    
    all_healthy = all(checks.values())
    status = 200 if all_healthy else 503
    
    return jsonify(checks), status
```

### Graceful Shutdown
```python
import signal

def graceful_shutdown(signum, frame):
    # Stop accepting new requests
    server.stop_accepting()
    
    # Deregister from service discovery
    registry.deregister()
    
    # Wait for in-flight requests
    server.wait_for_pending()
    
    # Shutdown
    sys.exit(0)

signal.signal(signal.SIGTERM, graceful_shutdown)
```

### Caching and Fallback
```python
class CachingDiscovery:
    def __init__(self, registry, cache_ttl=30):
        self.registry = registry
        self.cache = {}
        self.cache_ttl = cache_ttl
    
    def discover(self, service_name):
        # Check cache first
        if service_name in self.cache:
            cached, timestamp = self.cache[service_name]
            if time.time() - timestamp < self.cache_ttl:
                return cached
        
        # Fetch from registry
        try:
            instances = self.registry.discover(service_name)
            self.cache[service_name] = (instances, time.time())
            return instances
        except Exception:
            # Return stale cache on failure
            if service_name in self.cache:
                return self.cache[service_name][0]
            raise
```

---

## Interview Questions

**Q: What is service discovery and why is it needed?**
> Service discovery automatically detects services and their network locations. Needed because in dynamic environments (cloud, containers), IPs change frequently, services scale, and instances fail. Hardcoded addresses don't work.

**Q: Explain client-side vs server-side discovery.**
> Client-side: client queries registry and does load balancing (more control, language-specific). Server-side: client calls load balancer which handles discovery (simpler client, potential bottleneck).

**Q: How do health checks work in service discovery?**
> Registry periodically pings service health endpoints. Healthy responses keep instance registered; failures/timeouts remove it. Prevents routing to dead instances.

**Q: What happens if the service registry goes down?**
> Client-side caching continues working with last known instances. New services can't register. Design for HA: run registry as cluster (Consul, etcd use Raft consensus).

---

## Quick Reference

| Tool | Pattern | Protocol | Use Case |
|------|---------|----------|----------|
| Consul | Both | DNS, HTTP | General purpose |
| etcd | Client | HTTP/gRPC | Kubernetes |
| Eureka | Client | HTTP | Spring Cloud |
| Kubernetes | Server | DNS | K8s native |
| ZooKeeper | Client | TCP | Legacy systems |
