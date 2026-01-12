# DNS and Networking Fundamentals

## DNS (Domain Name System)

### What is DNS?

DNS translates human-readable domain names to IP addresses - the "phonebook of the internet."

```
Browser: "What's the IP for www.google.com?"
DNS: "142.250.190.68"
Browser: Connects to 142.250.190.68
```

---

## DNS Resolution Process

### Step-by-Step Lookup

```
┌─────────┐    ┌───────────┐    ┌─────────────┐    ┌────────────┐    ┌─────────────┐
│ Browser │───→│   Local   │───→│    Root     │───→│    TLD     │───→│Authoritative│
│         │    │   DNS     │    │   Server    │    │   Server   │    │   Server    │
└─────────┘    └───────────┘    └─────────────┘    └────────────┘    └─────────────┘
     │              │                  │                  │                 │
     │ 1. Query     │                  │                  │                 │
     │─────────────→│                  │                  │                 │
     │              │ 2. Check cache   │                  │                 │
     │              │ (miss)           │                  │                 │
     │              │──────────────────→ 3. "Ask .com"    │                 │
     │              │                  │                  │                 │
     │              │──────────────────────────────────────→ 4. "Ask       │
     │              │                  │                  │  ns.google.com"│
     │              │                  │                  │                 │
     │              │───────────────────────────────────────────────────────→
     │              │                  │                  │   5. IP Address │
     │              │←──────────────────────────────────────────────────────
     │  6. Return   │                  │                  │                 │
     │←─────────────│                  │                  │                 │
```

### DNS Hierarchy

```
Root DNS Servers (.)
├── .com (TLD)
│   ├── google.com → Authoritative server
│   ├── amazon.com → Authoritative server
│   └── ...
├── .org (TLD)
├── .io (TLD)
└── ...
```

---

## DNS Record Types

| Type | Purpose | Example |
|------|---------|---------|
| **A** | IPv4 address | `example.com → 93.184.216.34` |
| **AAAA** | IPv6 address | `example.com → 2606:2800:220:1::` |
| **CNAME** | Alias to another domain | `www.example.com → example.com` |
| **MX** | Mail server | `example.com → mail.example.com` |
| **TXT** | Text data (verification) | `example.com → "v=spf1..."` |
| **NS** | Name server | `example.com → ns1.example.com` |
| **SOA** | Zone authority info | Start of Authority record |

### Record Examples

```
; A Record - Map domain to IP
example.com.     IN  A      93.184.216.34

; CNAME - Alias
www.example.com. IN  CNAME  example.com.

; MX - Mail servers (priority, then server)
example.com.     IN  MX     10 mail1.example.com.
example.com.     IN  MX     20 mail2.example.com.

; TXT - SPF record for email
example.com.     IN  TXT    "v=spf1 include:_spf.google.com ~all"
```

---

## DNS Caching

### Cache Locations
1. **Browser cache**: Shortest TTL
2. **OS cache**: Resolver cache
3. **ISP DNS**: Recursive resolver
4. **Authoritative DNS**: Source of truth

### TTL (Time To Live)
```
example.com.  300  IN  A  93.184.216.34
              ↑
              TTL in seconds (5 minutes)

High TTL (86400 = 1 day):
  ✓ Less DNS queries, faster
  ✗ Slow to propagate changes

Low TTL (60 = 1 minute):
  ✓ Fast changes
  ✗ More queries, slower
```

---

## DNS in System Design

### Load Balancing via DNS

```
example.com → Round Robin
├── Request 1 → 10.0.0.1
├── Request 2 → 10.0.0.2
├── Request 3 → 10.0.0.3
└── Request 4 → 10.0.0.1 (cycles)
```

### GeoDNS
```
User in US → example.com → US server (10.0.0.1)
User in EU → example.com → EU server (10.0.1.1)
User in Asia → example.com → Asia server (10.0.2.1)
```

### DNS Failover
```
Primary (healthy):   example.com → 10.0.0.1
Primary (unhealthy): example.com → 10.0.0.2 (backup)

Health checks determine which IPs to return
```

---

## TCP/IP Fundamentals

### OSI Model (Simplified)

```
Layer 7: Application  - HTTP, DNS, FTP
Layer 4: Transport    - TCP, UDP
Layer 3: Network      - IP, ICMP
Layer 2: Data Link    - Ethernet, MAC
Layer 1: Physical     - Cables, WiFi
```

### TCP vs UDP

| Feature | TCP | UDP |
|---------|-----|-----|
| Connection | Connection-oriented | Connectionless |
| Reliability | Guaranteed delivery | Best effort |
| Ordering | Ordered packets | No ordering |
| Speed | Slower | Faster |
| Use Case | HTTP, Database | DNS, Video, Gaming |

### TCP Connection (3-Way Handshake)

```
Client                Server
  │                     │
  │──── SYN ──────────→│  1. "I want to connect"
  │                     │
  │←─── SYN-ACK ───────│  2. "OK, I acknowledge"
  │                     │
  │──── ACK ──────────→│  3. "Great, let's talk"
  │                     │
  │←──── Data ────────→│  4. Connection established
```

---

## HTTP/HTTPS

### HTTP Request/Response

```
GET /api/users HTTP/1.1
Host: api.example.com
Authorization: Bearer token123
Accept: application/json

HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 123

{"users": [...]}
```

### HTTP Methods Recap

| Method | Idempotent | Safe | Use |
|--------|------------|------|-----|
| GET | ✅ | ✅ | Read |
| POST | ❌ | ❌ | Create |
| PUT | ✅ | ❌ | Replace |
| PATCH | ❌ | ❌ | Partial update |
| DELETE | ✅ | ❌ | Delete |

### HTTPS (TLS Handshake)

```
Client                           Server
  │                                │
  │── Client Hello ───────────────→│  (Supported ciphers)
  │                                │
  │←── Server Hello ───────────────│  (Chosen cipher + cert)
  │                                │
  │── Verify Certificate ──────────│
  │── Key Exchange ───────────────→│
  │                                │
  │←─────── Encrypted Data ───────→│  (Symmetric encryption)
```

---

## Proxies

### Forward Proxy

Client → **Forward Proxy** → Internet

```
┌─────────┐    ┌──────────┐    ┌──────────┐
│ Clients │───→│  Proxy   │───→│ Internet │
│ (hide)  │    │          │    │          │
└─────────┘    └──────────┘    └──────────┘

Use cases:
- Hide client IP
- Content filtering
- Caching
- Access control
```

### Reverse Proxy

Internet → **Reverse Proxy** → Servers

```
┌──────────┐    ┌──────────┐    ┌──────────┐
│ Internet │───→│  Nginx   │───→│ Servers  │
│          │    │ (Proxy)  │    │ (hidden) │
└──────────┘    └──────────┘    └──────────┘

Use cases:
- Load balancing
- SSL termination
- Caching
- Security
- Compression
```

### Sidecar Proxy (Service Mesh)

```
┌─────────────────────────────────────────┐
│              Pod                        │
│  ┌────────────┐    ┌────────────────┐   │
│  │  Service   │───→│ Sidecar Proxy  │───│───→ Network
│  │            │    │ (Envoy)        │   │
│  └────────────┘    └────────────────┘   │
└─────────────────────────────────────────┘

Use cases:
- mTLS between services
- Observability
- Traffic management
- Service mesh (Istio)
```

---

## IP Addressing

### IPv4 vs IPv6

```
IPv4: 192.168.1.1        (32 bits, 4.3 billion addresses)
IPv6: 2001:0db8::1       (128 bits, 340 undecillion addresses)
```

### Private IP Ranges (IPv4)

| Range | CIDR | Use |
|-------|------|-----|
| 10.0.0.0 - 10.255.255.255 | 10.0.0.0/8 | Large networks |
| 172.16.0.0 - 172.31.255.255 | 172.16.0.0/12 | Medium networks |
| 192.168.0.0 - 192.168.255.255 | 192.168.0.0/16 | Home/small |

### NAT (Network Address Translation)

```
Private Network              Public Internet
┌────────────────┐
│ 192.168.1.10   │────┐
│ 192.168.1.11   │────┼───→ [NAT Gateway] ───→ 203.0.113.1
│ 192.168.1.12   │────┘     (Public IP)
└────────────────┘
```

---

## Ports

### Well-Known Ports

| Port | Service |
|------|---------|
| 20, 21 | FTP |
| 22 | SSH |
| 25 | SMTP |
| 53 | DNS |
| 80 | HTTP |
| 443 | HTTPS |
| 3306 | MySQL |
| 5432 | PostgreSQL |
| 6379 | Redis |
| 27017 | MongoDB |

---

## Latency Numbers

| Operation | Time |
|-----------|------|
| L1 cache reference | 1 ns |
| L2 cache reference | 4 ns |
| RAM reference | 100 ns |
| SSD read | 150 μs |
| HDD seek | 10 ms |
| Same datacenter round trip | 500 μs |
| US East to West | 40 ms |
| US to Europe | 80 ms |
| US to Asia | 150 ms |

---

## Interview Questions

**Q: What happens when you type a URL in the browser?**
> 1. DNS lookup (cache → recursive resolver → root → TLD → authoritative)
> 2. TCP connection (3-way handshake)
> 3. TLS handshake (if HTTPS)
> 4. HTTP request sent
> 5. Server processes, returns response
> 6. Browser renders HTML/CSS/JS

**Q: How does DNS work?**
> Hierarchical system. Query goes to local resolver, then root servers, TLD servers, and authoritative servers. Responses cached with TTL. Uses UDP for queries (fast), TCP for zone transfers.

**Q: Forward vs Reverse proxy?**
> Forward: sits in front of clients, hides client identity, used for access control/caching. Reverse: sits in front of servers, hides server identity, used for load balancing/SSL termination/security.

**Q: Why use UDP for DNS?**
> DNS queries are small, fit in single packet. UDP is faster (no handshake). Retries handle packet loss. TCP used for zone transfers and large responses.

---

## Quick Reference

| Concept | Purpose | Example |
|---------|---------|---------|
| DNS | Name → IP | google.com → 142.250.x.x |
| TCP | Reliable transport | HTTP, Database |
| UDP | Fast transport | DNS, Video |
| HTTPS | Encrypted HTTP | TLS/SSL |
| Forward Proxy | Hide client | VPN, Corporate proxy |
| Reverse Proxy | Hide server | Nginx, Load balancer |
| CDN | Edge caching | Cloudflare, Akamai |
