# Razorpay Senior SDE Interview Experience — System Design: Event Throttling Framework

## Round Overview
- **Role:** Senior Software Development Engineer (Backend / Distributed Systems / Platform)
- **Topic:** Event Throttling Framework (Control events published/processed by users or services within a time window)
- **Primary Guide Location:** [Razorpay_Event_Throttling_Framework_Senior_SDE_Interview_Guide.md](file:///Users/mmt12368/Documents/GitHub/Interview/SystemDesign/HLD/System-Examples/Razorpay_Event_Throttling_Framework_Senior_SDE_Interview_Guide.md)

---

## Key Highlights & Framework

### 1. Requirements & Workload Assumptions
- **Workload:** 500,000 aggregate events/sec across services (Payments, Webhooks, Refunds, Telemetry).
- **Latency Overhead:** p95 < 2ms, p99 < 5ms.
- **Active Entity Keys:** ~1,000,000 active buckets (Merchants, Users, Gateways).
- **Redis Memory:** ~160 MB (Memory is never the bottleneck; Redis CPU & network bandwidth are the bottlenecks).
- **Cluster Sizing:** 8 to 10 Redis primary shards or Client-Side Token Leasing (Batching).

### 2. Core Architecture
- **Control Plane:** Dynamic rule configuration via etcd / Kafka config topic with sub-second propagation.
- **Data Plane (SDK):** Smart Client with local token lease buffer, policy cache (Caffeine), and circuit breaker (Resilience4j).
- **Distributed State:** Redis Cluster executing atomic Lua scripts for lazy token refill, capacity checks, and deduction.
- **Breach Actions:**
  - *Hard Reject:* HTTP 429 Retry-After (API callers).
  - *Bounded Buffer / Delay:* LMAX Disruptor / Kafka Retry Topic (Webhooks, Refunds).
  - *Backpressure:* Pause Kafka partition consumption / block producer thread.

### 3. Critical Production Challenges to Highlight
1. **Concurrency & Race Conditions:** Solved via atomic Redis Lua script (never multi-step GET/SET in Java).
2. **Clock Skew:** Rely on Redis server clock (`redis.call('TIME')`), never client timestamps.
3. **Hot Key Saturation (Top Merchants):** Solved via Client-Side Token Leasing (batching 50–100 tokens locally) or key sub-sharding (`key#1..k`).
4. **Redis Failure Resilience:** Fail-Open for critical payment transactions; Fail-Closed for unauthenticated APIs; Local fractional in-memory quota fallback.
5. **Thundering Herd / Retry Storms:** Enforce Exponential Backoff with Full Jitter in client SDKs.

---

> For the complete 45-minute interview script, the production Redis Lua script, Java Spring Boot integration, and detailed answers to all 12 follow-up questions, refer to:  
> 👉 **[Razorpay Event Throttling Framework Senior SDE Guide](file:///Users/mmt12368/Documents/GitHub/Interview/SystemDesign/HLD/System-Examples/Razorpay_Event_Throttling_Framework_Senior_SDE_Interview_Guide.md)**
