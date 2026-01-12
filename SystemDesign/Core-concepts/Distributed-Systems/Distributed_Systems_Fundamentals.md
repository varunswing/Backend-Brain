# Distributed Systems Fundamentals

## Overview
Distributed systems are collections of independent computers that appear to users as a single coherent system. This document covers the fundamental concepts, challenges, patterns, and principles essential for designing and building distributed systems.

## What are Distributed Systems?

### Definition
A distributed system is a collection of autonomous computing elements that appears to its users as a single coherent system, where components communicate through message passing over a network.

### Key Characteristics
- **Multiple Independent Nodes**: Separate computers or processes
- **Network Communication**: Components communicate via messages
- **Shared State**: Coordinated state across multiple nodes
- **Concurrent Execution**: Multiple operations happening simultaneously
- **Partial Failures**: Some components can fail while others continue

### Examples of Distributed Systems
- **Web Applications**: Frontend, backend services, databases
- **Cloud Platforms**: AWS, Google Cloud, Azure
- **Social Networks**: Facebook, Twitter, Instagram
- **E-commerce**: Amazon, eBay, Shopify
- **Streaming Services**: Netflix, YouTube, Spotify
- **Financial Systems**: Banking, payment processing
- **IoT Systems**: Smart homes, industrial monitoring

## Why Distributed Systems?

### Motivations

#### 1. Scalability
**Horizontal Scaling**: Add more machines to handle increased load
```
Single Server (Vertical Scaling):
CPU: 4 cores → 8 cores → 16 cores (limited)

Distributed System (Horizontal Scaling):
1 server → 2 servers → 4 servers → 100 servers
```

#### 2. Reliability and Availability
**Fault Tolerance**: System continues operating despite component failures
```
Single Point of Failure:
Server Down → Entire System Down

Distributed System:
Node 1 Down → Nodes 2, 3, 4 Continue Operating
```

#### 3. Geographic Distribution
**Global Reach**: Serve users from multiple locations
```
Global CDN:
├── US East: Serves North American users
├── EU West: Serves European users
├── Asia Pacific: Serves Asian users
└── Data replication across regions
```

#### 4. Resource Sharing
**Efficient Utilization**: Share computing resources across applications

#### 5. Performance
**Parallel Processing**: Distribute workload across multiple machines

## Fundamental Challenges

### 1. Network Partitions
**Problem**: Network failures can split the system into isolated groups.

**Mitigation Strategies**:
- **Quorum Systems**: Majority consensus for decisions
- **Circuit Breakers**: Fail fast when network issues detected
- **Graceful Degradation**: Reduced functionality during partitions
- **Partition Detection**: Monitor network connectivity

### 2. Partial Failures
**Problem**: Some components fail while others continue operating.

**Failure Types**:
- **Crash Failures**: Node stops responding
- **Omission Failures**: Messages are lost
- **Timing Failures**: Operations take too long
- **Byzantine Failures**: Arbitrary or malicious behavior

**Handling Strategies**:
- **Timeouts**: Don't wait indefinitely
- **Retries**: Attempt operations multiple times
- **Idempotency**: Safe to retry operations
- **Compensation**: Undo operations when failures occur

### 3. Concurrency and Coordination
**Problem**: Multiple nodes performing operations simultaneously.

**Coordination Mechanisms**:
- **Distributed Locks**: Mutual exclusion across nodes
- **Consensus Algorithms**: Agreement on shared state
- **Atomic Transactions**: All-or-nothing operations
- **Event Ordering**: Establish order of operations

### 4. Consistency
**Problem**: Maintaining consistent state across multiple nodes.

**Consistency Models**:

#### Strong Consistency
All nodes see the same data simultaneously

#### Eventual Consistency
All nodes will eventually show the same data

#### Causal Consistency
Causally related operations are seen in the same order

## Time and Ordering

### The Problem of Time
**Challenge**: No global clock in distributed systems.

#### Logical Clocks (Lamport Timestamps)
```python
class LamportClock:
    def __init__(self):
        self.time = 0
    
    def tick(self):
        self.time += 1
        return self.time
    
    def update(self, received_time):
        self.time = max(self.time, received_time) + 1
        return self.time
```

#### Vector Clocks
```python
class VectorClock:
    def __init__(self, process_id, num_processes):
        self.process_id = process_id
        self.clock = [0] * num_processes
    
    def tick(self):
        self.clock[self.process_id] += 1
        return self.clock.copy()
    
    def update(self, received_clock):
        for i in range(len(self.clock)):
            self.clock[i] = max(self.clock[i], received_clock[i])
        self.clock[self.process_id] += 1
        return self.clock.copy()
```

## Consensus and Agreement

### The Consensus Problem
**Goal**: Multiple nodes agree on a single value despite failures.

**Requirements**:
- **Agreement**: All correct nodes decide the same value
- **Validity**: Decided value was proposed by some node
- **Termination**: All correct nodes eventually decide

### Consensus Algorithms

#### Raft Consensus
**Roles**:
- **Leader**: Handles client requests, replicates log
- **Follower**: Passive, responds to leader requests
- **Candidate**: Seeks to become leader

#### Paxos Algorithm
**Phases**:
1. **Prepare**: Proposer asks acceptors to promise
2. **Promise**: Acceptors respond with highest proposal seen
3. **Accept**: Proposer sends value to acceptors
4. **Accepted**: Acceptors accept if promise not broken

#### PBFT (Practical Byzantine Fault Tolerance)
**Assumptions**: Up to f Byzantine failures in 3f+1 nodes

## Replication

### Why Replication?
- **Fault Tolerance**: Survive node failures
- **Performance**: Serve requests from multiple locations
- **Availability**: Continue operating during maintenance

### Replication Strategies

#### 1. Primary-Backup Replication
```
Client → Primary Node → Backup Node 1
                     → Backup Node 2
                     → Backup Node 3
```

#### 2. Multi-Master Replication
```
Client A → Master Node 1 ←→ Master Node 2 ← Client B
                         ←→ Master Node 3
```

#### 3. Quorum-Based Replication
```
Replication Factor: R = 3
Write Quorum: W = 2
Read Quorum: R = 2

Quorum Condition: W + R > N ensures consistency
```

## Distributed Transactions

### Two-Phase Commit (2PC)
**Phase 1 - Prepare**: Coordinator asks participants to prepare
**Phase 2 - Commit**: Coordinator sends commit or abort

**Problems with 2PC**:
- **Blocking**: Participants wait for coordinator
- **Single Point of Failure**: Coordinator failure
- **Performance**: Multiple round trips

### Saga Pattern
**Concept**: Long-running transactions as sequence of local transactions with compensating actions.

```python
class OrderSaga:
    def __init__(self):
        self.steps = [
            (self.reserve_inventory, self.release_inventory),
            (self.process_payment, self.refund_payment),
            (self.ship_order, self.cancel_shipment),
            (self.send_confirmation, self.send_cancellation)
        ]
    
    def execute(self, order):
        completed_steps = []
        
        try:
            for step, compensation in self.steps:
                step(order)
                completed_steps.append(compensation)
        except Exception as e:
            # Execute compensating actions in reverse order
            for compensation in reversed(completed_steps):
                compensation(order)
            raise e
```

## Communication Patterns

### 1. Request-Response
Synchronous communication - direct coupling between services

### 2. Publish-Subscribe
Asynchronous communication - decoupled services

### 3. Message Queues
Reliable message delivery with persistence

### 4. Stream Processing
Continuous data processing

## Fault Tolerance Patterns

### 1. Circuit Breaker
```python
class CircuitBreaker:
    def __init__(self, failure_threshold=5, timeout=60):
        self.failure_threshold = failure_threshold
        self.timeout = timeout
        self.failure_count = 0
        self.state = "CLOSED"  # CLOSED, OPEN, HALF_OPEN
    
    def call(self, func, *args, **kwargs):
        if self.state == "OPEN":
            if time.time() - self.last_failure_time > self.timeout:
                self.state = "HALF_OPEN"
            else:
                raise Exception("Circuit breaker is OPEN")
        
        try:
            result = func(*args, **kwargs)
            self.on_success()
            return result
        except Exception as e:
            self.on_failure()
            raise e
```

### 2. Bulkhead Pattern
Isolate resources to prevent cascade failures

### 3. Retry with Exponential Backoff
```python
def retry_with_backoff(func, max_retries=3, base_delay=1):
    for attempt in range(max_retries):
        try:
            return func()
        except Exception as e:
            if attempt == max_retries - 1:
                raise e
            
            # Exponential backoff with jitter
            delay = base_delay * (2 ** attempt) + random.uniform(0, 1)
            time.sleep(delay)
```

### 4. Timeout Pattern
Don't wait indefinitely for responses

## Load Balancing

### Load Balancing Algorithms

#### 1. Round Robin
Distribute requests sequentially

#### 2. Weighted Round Robin
Distribute based on server capacity

#### 3. Least Connections
Send to server with fewest active connections

#### 4. Consistent Hashing
Minimize data movement when nodes change

## Best Practices

### 1. Design Principles

#### Embrace Failure
Design for failure from the beginning

#### Idempotency
Make operations safe to retry

#### Loose Coupling
Use events for decoupled communication

### 2. Operational Excellence

#### Health Checks
Monitor component health continuously

#### Graceful Shutdown
Handle termination signals properly

### 3. Security Considerations

#### Service-to-Service Authentication
Use tokens/certificates for inter-service communication

## Conclusion

Distributed systems are complex but essential for building scalable, reliable, and performant applications. Key takeaways:

**Fundamental Challenges**:
- Network partitions and partial failures are inevitable
- Consistency, availability, and partition tolerance trade-offs
- Time and ordering are complex in distributed environments
- Coordination and consensus are difficult but necessary

**Design Principles**:
- Embrace failure and design for resilience
- Use appropriate consistency models for your use case
- Implement proper monitoring and observability
- Choose the right communication patterns
- Plan for scalability from the beginning

**Remember**: Start simple and add complexity only when necessary. Distributed systems introduce significant complexity, so ensure the benefits justify the costs.
