# Load Balancing: Complete Guide for System Design

## Overview
Load balancing distributes incoming network traffic across multiple servers to ensure no single server bears too much load, improving responsiveness and availability.

## Why Load Balancing?

| Problem | Solution |
|---------|----------|
| Single point of failure | Distribute across multiple servers |
| Server overload | Even distribution of requests |
| Slow response times | Route to fastest available server |
| Maintenance downtime | Take servers offline without affecting users |

## Types of Load Balancers

### 1. Hardware Load Balancers
- Dedicated physical devices
- High performance, high cost
- Examples: F5 Networks, Citrix ADC

### 2. Software Load Balancers
- Run on commodity hardware
- Flexible, cost-effective
- Examples: NGINX, HAProxy, Traefik

### 3. Cloud Load Balancers
- Managed services
- Auto-scaling capability
- Examples: AWS ALB/NLB, GCP Load Balancer, Azure Load Balancer

## Load Balancing Algorithms

### 1. Round Robin
```
Request 1 → Server A
Request 2 → Server B
Request 3 → Server C
Request 4 → Server A (cycle repeats)
```
**Best for**: Servers with equal capacity, stateless applications

### 2. Weighted Round Robin
```
Server A (weight: 5) → 5 requests
Server B (weight: 3) → 3 requests
Server C (weight: 2) → 2 requests
```
**Best for**: Servers with different capacities

### 3. Least Connections
Routes to server with fewest active connections.

**Best for**: Long-lived connections, varying request complexity

### 4. Least Response Time
Routes to server with lowest latency + fewest connections.

**Best for**: Performance-critical applications

### 5. IP Hash
```python
server_index = hash(client_ip) % number_of_servers
```
**Best for**: Session persistence without cookies

### 6. Consistent Hashing
Minimizes redistribution when servers are added/removed.

**Best for**: Distributed caching, database sharding

## Load Balancer Layers

### Layer 4 (Transport Layer)
- Works at TCP/UDP level
- Faster, less CPU intensive
- Cannot inspect HTTP content
- Makes decisions based on IP and port

### Layer 7 (Application Layer)
- Works at HTTP level
- Can route based on URL, headers, cookies
- SSL termination
- Content-based routing

```
Layer 7 Load Balancer Routing:
├── /api/* → API Server Pool
├── /static/* → CDN
├── /admin/* → Admin Server Pool
└── /* → Web Server Pool
```

## Health Checks

### Types
```yaml
# HTTP Health Check
healthcheck:
  type: HTTP
  path: /health
  interval: 30s
  timeout: 10s
  healthy_threshold: 2
  unhealthy_threshold: 3

# TCP Health Check
healthcheck:
  type: TCP
  port: 8080
  interval: 10s
```

### Health Check Patterns
- **Active**: LB sends periodic probes
- **Passive**: LB monitors actual traffic responses
- **Combined**: Both active and passive

## Session Persistence (Sticky Sessions)

### Methods
1. **Cookie-based**: Insert session cookie
2. **IP-based**: Route by client IP
3. **Application-controlled**: Session ID in URL

### When to Use
- ✅ Shopping carts (if not using distributed session)
- ✅ Multi-step forms
- ❌ Stateless REST APIs
- ❌ Microservices (use distributed session instead)

## SSL/TLS Termination

### SSL Termination at Load Balancer
```
Client → HTTPS → Load Balancer → HTTP → Backend Servers
```
**Pros**: Offloads CPU from backend, centralized certificate management
**Cons**: Traffic unencrypted internally

### SSL Passthrough
```
Client → HTTPS → Load Balancer → HTTPS → Backend Servers
```
**Pros**: End-to-end encryption
**Cons**: More complex, higher backend CPU

### SSL Re-encryption
```
Client → HTTPS → Load Balancer → HTTPS → Backend Servers
                 (decrypt/re-encrypt)
```
**Pros**: Inspection capability + encryption

## High Availability Setup

### Active-Passive
```
Primary LB (Active) ←→ Secondary LB (Standby)
         ↓                    ↓
   Heartbeat/VRRP      Takes over on failure
```

### Active-Active
```
┌─────────────┐     ┌─────────────┐
│     LB 1    │     │     LB 2    │
└──────┬──────┘     └──────┬──────┘
       │                   │
       └───────┬───────────┘
               ↓
       DNS Round Robin / Anycast
```

## Global Load Balancing (GSLB)

### DNS-Based
```
User in US → DNS → US Data Center
User in EU → DNS → EU Data Center
```

### Anycast
- Same IP advertised from multiple locations
- Network routes to nearest location

## Configuration Examples

### NGINX
```nginx
upstream backend {
    least_conn;
    server backend1.example.com weight=5;
    server backend2.example.com weight=3;
    server backend3.example.com backup;
    
    keepalive 32;
}

server {
    location / {
        proxy_pass http://backend;
        proxy_next_upstream error timeout;
        proxy_connect_timeout 1s;
    }
    
    location /health {
        access_log off;
        return 200 "healthy";
    }
}
```

### HAProxy
```haproxy
frontend http_front
    bind *:80
    default_backend http_back

backend http_back
    balance roundrobin
    option httpchk GET /health
    server server1 192.168.1.1:80 check weight 5
    server server2 192.168.1.2:80 check weight 3
    server server3 192.168.1.3:80 check backup
```

## Best Practices

### Design
1. Use health checks aggressively
2. Set appropriate timeouts
3. Plan for failure (N+1 redundancy)
4. Use connection pooling
5. Enable keep-alive connections

### Security
1. Implement rate limiting
2. Use SSL/TLS
3. Enable DDoS protection
4. Hide backend server details

### Monitoring
1. Track request latency (p50, p95, p99)
2. Monitor connection counts
3. Alert on health check failures
4. Track error rates per backend

## Interview Questions

**Q: How would you handle a failing server?**
> Health checks detect failure → LB removes from pool → Traffic redistributes → Alert triggers → Auto-scaling adds new instance

**Q: Sticky sessions vs distributed sessions?**
> Sticky sessions are simpler but limit scaling. Distributed sessions (Redis) allow any server to handle any request.

**Q: Layer 4 vs Layer 7 load balancing?**
> L4 is faster (TCP level), L7 is smarter (HTTP level, content-based routing). Use L7 for microservices, L4 for raw performance.

## Quick Reference

| Algorithm | Use Case |
|-----------|----------|
| Round Robin | Equal servers, stateless |
| Weighted | Different server capacities |
| Least Connections | Long-lived connections |
| IP Hash | Session affinity without cookies |
| Consistent Hash | Caching, sharding |
