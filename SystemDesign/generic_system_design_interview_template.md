# System Design Interview Candidate Guide

## Interview Duration: 45 Minutes
**Your Goal:** Demonstrate system design skills, technical depth, and clear communication

---

## 🎯 **How to Approach Any System Design Question**

When given a problem like:
- *"Design Twitter/Instagram/Facebook"*
- *"Design Netflix/YouTube video streaming"*
- *"Design Uber/Lyft ride sharing"*
- *"Design WhatsApp/Slack messaging system"*
- *"Design Amazon/eBay e-commerce platform"*

**Your mindset:** Don't jump into solutions immediately. Follow this structured approach!

---

## 📋 **Your Step-by-Step Approach**

### **Phase 1: Clarify Requirements** (8 minutes)

**What you should say:** *"Before I start designing, let me clarify some requirements to make sure I understand the problem correctly."*

#### **Questions YOU Should Ask:**

**Functional Requirements:**
- "What are the core features we need to support?" (e.g., post, like, comment, share for Twitter)
- "Who are the primary users?" (consumers, businesses, admins)
- "What platforms should we support?" (mobile, web, APIs)
- "Are there any specific user flows that are most critical?"
- "What type of content/data will users be creating/consuming?"

**Non-Functional Requirements:**
- "What scale are we targeting?" (users, requests/day, data volume)
- "What are our performance requirements?" (latency, throughput)
- "What's our availability target?" (99.9%, 99.99%?)
- "Do we need strong consistency or is eventual consistency acceptable?"
- "Are there any specific security or compliance requirements?"
- "What's our budget/timeline constraints?"

#### **Sample Questions for Any System:**
- "How many daily active users are we expecting?"
- "What's the read to write ratio?"
- "Do users expect real-time updates?"
- "What's the average size of content/data per user?"
- "Are we building this globally or for specific regions first?"

**💡 Pro Tip:** Don't ask ALL these questions - pick 5-7 most relevant ones for the system you're designing.

### **Phase 2: Back-of-Envelope Calculations** (5 minutes)

**What you should say:** *"Let me do some quick calculations to understand the scale we're dealing with."*

#### **Your Calculation Framework:**

**1. Traffic Estimation:**
```
Daily Active Users (DAU): [X] million
Peak usage ratio: 2-3x average
Concurrent users: DAU / 10 (rough estimate)
Read/Write ratio: [depends on system - usually 100:1 to 1000:1]
Peak RPS = (DAU × actions per day × peak ratio) / (24 × 60 × 60)

Example for Twitter-like system:
- 100M DAU, 20 tweets read per day per user
- Read RPS = (100M × 20 × 2) / 86,400 = ~46,000 RPS
```

**2. Storage Estimation:**
```
User data: [X] users × [Y] KB per user
Content data: [X] posts/day × [Y] KB per post × retention period
Media storage: [X]% of content has media × average media size
Total = User data + Content data + Media + Indexes (1.2x multiplier)

Example: 
- 100M users × 2KB = 200GB user data
- 100M tweets/day × 1KB × 365 days = 36.5TB content data
```

**3. Memory (Cache) Requirements:**
```
Active sessions: Concurrent users × session data size
Hot data cache: [X]% of content × content size
Total memory: Sessions + Cache + Application memory

Example:
- 10M concurrent × 10KB = 100GB sessions
- 20% hot content × 100GB = 20GB cache
```

**💡 Pro Tips:** 
- Show your math on the whiteboard
- Use round numbers (100M not 97.3M)
- State your assumptions clearly
- It's okay to revise estimates later

### **Phase 3: High-Level Architecture** (12 minutes)

**What you should say:** *"Now let me design the high-level architecture. I'll start simple and add complexity as needed."*

#### **Your Architecture Approach:**

**Step 1: Start Simple**
```
[Client] → [Load Balancer] → [Web Server] → [Database]
```

**Step 2: Add Essential Components**
```
[Clients] → [CDN] → [Load Balancer] → [API Gateway] → [Services] → [Database + Cache]
```

**Step 3: Break Down Each Layer**

**Client Layer (What you should mention):**
- "We'll have mobile apps for iOS/Android and a web application"
- "Admin dashboard for content moderation and analytics"
- "Third-party API access for partners"

**Edge & Load Balancing:**
- "CDN for static assets and frequently accessed content"
- "Load balancer with health checks and auto-scaling"
- "Geographic distribution for global users"

**API Gateway:**
- "Single entry point for all client requests"
- "Handles authentication, rate limiting, and request routing"
- "Circuit breaker pattern for fault tolerance"

**Core Services (Customize based on system):**
- User Service: "Authentication, profile management, preferences"
- [Main Business Logic Service]: "Core functionality like posting, messaging, etc."
- [Content Service]: "Content creation, storage, retrieval"
- Search Service: "Fast search across content"
- Recommendation Service: "Personalized content recommendations"

**Data Layer:**
- "PostgreSQL for transactional data requiring ACID properties"
- "Redis for caching and session management" 
- "Elasticsearch for search functionality"
- "S3/Cloud Storage for media files"
- "Message queue (Kafka) for async processing"

#### **How to Present Your Design:**

1. **Draw as you speak** - Don't draw everything then explain
2. **Start simple, add complexity** - Show evolution of thought
3. **Justify your choices** - "I'm using PostgreSQL because we need ACID properties for user transactions"
4. **Ask if they want details** - "Should I dive deeper into any specific component?"

### **Phase 4: Database Design** (8 minutes)

**What you should say:** *"Let me design the data model based on the core entities and their relationships."*

#### **Your Database Design Process:**

**Step 1: Identify Core Entities**
```
Think out loud: "The main entities I need are..."
- Users (authentication, profile, preferences)
- [Core Entity] (posts, videos, rides, etc.)  
- [Relationship Entity] (likes, follows, ratings, etc.)
- [Supporting Entities] (notifications, comments, etc.)
```

**Step 2: Design Key Tables**
```sql
-- Always start with users table
CREATE TABLE users (
    user_id UUID PRIMARY KEY,           -- "I'm using UUID for better sharding"
    email VARCHAR(255) UNIQUE NOT NULL,
    username VARCHAR(50) UNIQUE,
    profile_data JSONB,                 -- "JSONB for flexible profile fields"
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    -- Add indexes
    INDEX idx_users_email (email),
    INDEX idx_users_username (username)
);

-- Core business entity (customize based on system)
CREATE TABLE [posts/videos/rides] (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(user_id),
    content TEXT,                       -- or specific fields based on system
    metadata JSONB,                     -- flexible schema for future features
    status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    
    -- Important indexes based on access patterns
    INDEX idx_entity_user (user_id, created_at),
    INDEX idx_entity_status (status, created_at)
);
```

**Step 3: Explain Your Design Decisions**

**Primary Key Strategy:**
- "I'm using UUIDs instead of auto-increment integers because they work better in distributed systems and make sharding easier"

**Indexing Strategy:**
- "I'm adding indexes on columns we'll frequently query - user lookups, time-based queries, and status filtering"
- "The composite index on (user_id, created_at) will help with user timeline queries"

**Data Types:**
- "Using JSONB for flexible fields that might evolve over time"
- "VARCHAR with appropriate lengths based on expected data size"

**Relationships:**
- "Foreign key constraints to maintain data integrity"
- "For high-scale systems, we might relax some constraints for performance"

**Step 4: Caching Strategy**
```
"For caching, I'd use Redis with these patterns:"

User data: "user:{user_id}" → profile info, preferences
Session data: "session:{token}" → authentication state
Hot content: "trending:{time_bucket}" → popular content
Timeline cache: "timeline:{user_id}" → personalized feed
```

**💡 What to emphasize:**
- Show understanding of trade-offs (consistency vs. performance)
- Mention specific query patterns you're optimizing for
- Discuss how design supports future scaling needs

### **Phase 5: Deep Dive - Critical User Flow** (8 minutes)

**What you should say:** *"Let me walk through the most critical user workflow step by step, highlighting the technical challenges."*

#### **Your Workflow Analysis Approach:**

**Pick the MOST important flow for your system:**
- Twitter: "User posts a tweet"
- Netflix: "User streams a video" 
- Uber: "User requests a ride"
- WhatsApp: "User sends a message"

**Step-by-Step Breakdown Template:**

**1. Request Initiation**
```
"When a user [performs action], here's what happens:

1. Client sends request to Load Balancer
2. Load Balancer routes to API Gateway  
3. API Gateway validates JWT token and checks rate limits
4. Request routed to appropriate microservice"
```

**2. Business Logic & Data Processing**
```
"The [Core Service] then:

1. Validates input data and user permissions
2. Checks business rules (e.g., user can perform this action)
3. Retrieves any necessary data from cache/database
4. Processes the core business logic
5. Prepares data for persistence"
```

**3. Data Persistence**
```
"For data consistency, I need to:

1. Start a database transaction
2. Update primary entities with ACID properties
3. Update related entities (likes, followers, etc.)
4. Commit transaction or rollback on failure
5. Invalidate relevant cache entries"
```

**4. Response & Side Effects**
```
"After successful persistence:

1. Return success response to client
2. Publish event to message queue for async processing
3. Update real-time systems (WebSocket notifications)
4. Trigger background jobs (analytics, recommendations, etc.)"
```

#### **Key Technical Challenges to Address:**

**Race Conditions:**
- "Multiple users might [perform conflicting action] - I'd use optimistic locking with version numbers"

**Performance:**
- "For hot data, I'd implement cache-aside pattern with Redis"
- "Database queries optimized with proper indexing"

**Scalability:**
- "If this service becomes a bottleneck, we can scale horizontally"
- "Database sharding strategy based on [user_id/geographic region]"

**Failure Handling:**
- "If database is down, graceful degradation - show cached data with warning"
- "Retry logic with exponential backoff for transient failures"

**💡 Pro Tip:** Pick specific numbers and be concrete - "Cache TTL of 5 minutes", "Retry up to 3 times", etc.

### **Phase 6: Scaling & Bottlenecks** (2 minutes)

**What you should say:** *"Let me identify potential bottlenecks and how I'd address them as the system grows."*

#### **Your Scaling Analysis:**

**Identify Bottlenecks First:**
```
"The main bottlenecks I anticipate are:

1. Database - as we reach [X] users, database becomes read/write bottleneck
2. Cache - hot data might not fit in single Redis instance  
3. Application servers - CPU-intensive operations during peak hours
4. Network - media files and real-time updates consume bandwidth"
```

**Present Scaling Solutions:**
```
"Here's how I'd address each:

Database Scaling:
- Read replicas for read-heavy workloads (90% of traffic)
- Sharding by user_id or geographic region for write scaling
- Consider NoSQL for specific use cases (analytics, logs)

Application Scaling:
- Horizontal scaling with stateless services
- Load balancing with health checks
- Auto-scaling based on CPU/memory metrics

Caching:  
- Redis cluster for high availability
- Multi-layer caching (L1: application, L2: Redis, L3: CDN)
- Cache warming strategies for predictable traffic

Geographic Distribution:
- Multi-region deployment for global users
- Regional data centers with data locality
- CDN for static content delivery"
```

**💡 Key phrases to use:**
- "As we scale from X to Y users..."
- "This approach works until we hit Z scale, then we'd need to..."
- "The trade-off here is complexity vs. performance..."

---

## 🎯 **What You Need to Demonstrate**

### **To Ace the Interview (Senior Level):**

**Requirements & Planning:**
- ✅ Ask 5-7 targeted clarifying questions before designing
- ✅ Show realistic capacity estimation with back-of-envelope math
- ✅ Start simple and evolve design complexity gradually

**Technical Excellence:**
- ✅ Design scalable architecture with proper separation of concerns
- ✅ Show deep database knowledge (indexing, sharding, consistency)
- ✅ Understand caching strategies and when to use them
- ✅ Handle race conditions and failure scenarios

**Communication Skills:**
- ✅ Think out loud - explain your reasoning
- ✅ Draw diagrams while explaining (don't draw then explain)
- ✅ Use specific numbers and technologies 
- ✅ Ask "Should I go deeper into this component?"

**Advanced Concepts:**
- ✅ Discuss trade-offs explicitly (consistency vs. availability)
- ✅ Consider real-world constraints (cost, time, team size)
- ✅ Show knowledge of distributed systems patterns
- ✅ Propose monitoring and observability strategies

### **Good Performance Indicators:**
- ✅ Covers all functional requirements systematically
- ✅ Creates reasonable high-level architecture 
- ✅ Designs adequate database schema with basic indexing
- ✅ Explains main user flows clearly
- ⚠️ May need prompting for advanced scaling topics

### **Red Flags to Avoid:**
- ❌ Jumping to solution without asking questions
- ❌ Unrealistic estimates or skipping capacity planning entirely
- ❌ Technology choices without justification
- ❌ Poor database design (no indexes, wrong relationships)
- ❌ Not considering failure scenarios or edge cases
- ❌ Being unable to handle follow-up questions or pushback

---

## 🚀 **Advanced Topics to Impress** (If Time Allows)

**Choose relevant topics based on the system you're designing:**

**For Social Media Systems (Twitter, Instagram):**
- "For viral content, I'd implement a push/pull hybrid model for timeline generation"
- "Anti-abuse systems with ML-based content moderation"
- "Celebrity user handling with separate infrastructure"

**For Video Streaming (Netflix, YouTube):**
- "Video transcoding pipeline with multiple quality levels"
- "CDN optimization with geographic content placement"
- "Adaptive bitrate streaming based on network conditions"

**For Real-time Systems (WhatsApp, Uber):**
- "WebSocket connection management and failover strategies"
- "Message ordering guarantees in distributed systems"
- "Location update optimization to reduce battery drain"

**Universal Advanced Topics:**
- **Monitoring**: "I'd implement distributed tracing, metrics dashboards, and automated alerting"
- **Security**: "Rate limiting, DDoS protection, data encryption at rest and in transit"
- **Compliance**: "GDPR compliance with data retention and deletion policies"
- **A/B Testing**: "Feature flag system for gradual rollouts and experimentation"

---

## 💭 **How to Handle Common Follow-up Questions**

### **Scalability Challenges:**

**Q: "What if your system needs to handle 10x traffic overnight?"**
**Your approach:**
1. "First, I'd identify which components would break first"
2. "Short-term: horizontal scaling of stateless services"
3. "Medium-term: database read replicas and caching optimization"
4. "Long-term: sharding and microservice decomposition"

**Q: "How do you ensure consistency across services?"**
**Your answer:**
- "For critical operations, I'd use distributed transactions or saga patterns"
- "For eventual consistency, event-driven architecture with compensation"
- "Example: User signup must be consistent, but analytics can be eventually consistent"

### **Failure Scenarios:**

**Q: "What happens when your database goes down?"**
**Your approach:**
1. "Immediate: Graceful degradation - serve cached data with warnings"
2. "Auto-failover to read replica promoted to master"
3. "Circuit breaker pattern to prevent cascade failures"
4. "Queue write operations to replay when database recovers"

**Q: "How do you prevent the system from gaming/abuse?"**
**Your answer:**
- "Rate limiting at multiple levels (user, IP, API key)"
- "Anomaly detection for unusual usage patterns"
- "Content validation and spam detection"
- "User reputation system for trust scoring"

### **Design Changes:**

**Q: "How would you add [new feature] to this system?"**
**Your approach:**
1. "Assess impact on existing architecture"
2. "Identify which services need modification"
3. "Plan backward-compatible API changes"
4. "Rollout strategy with feature flags and gradual deployment"

---

## ⏰ **Time Management Strategy**

**Your 45-Minute Breakdown:**
- **0-2 min**: Listen carefully, ask for clarification if needed
- **2-10 min**: Ask questions and capacity estimation (don't rush this!)
- **10-22 min**: High-level design - this is your main showcase
- **22-30 min**: Database design and data modeling
- **30-38 min**: Deep dive into 1-2 critical workflows
- **38-45 min**: Scaling discussion and follow-up questions

**⚠️ Time Management Tips:**
- If you're running behind, say "Should I continue with [X] or move to [Y]?"
- Don't spend too long perfecting diagrams - functionality over beauty
- If asked about edge cases, acknowledge them but focus on main flow
- Save detailed monitoring/security discussion for bonus time

---

## 🎯 **Final Success Tips**

### **Before the Interview:**
- [ ] Practice with a timer - 45 minutes goes fast!
- [ ] Know common system architectures by heart
- [ ] Practice capacity estimation mental math
- [ ] Review CAP theorem, ACID properties, consistency patterns

### **During the Interview:**
- [ ] Stay calm if you don't know something - think out loud
- [ ] It's okay to ask "Can I take a moment to think about this?"
- [ ] Use the whiteboard/screen share effectively
- [ ] Engage with the interviewer - it's a discussion, not a presentation

### **Mindset:**
- **Focus on trade-offs** - there's rarely one right answer
- **Show your thought process** - interviewers care about how you think
- **Be pragmatic** - consider real-world constraints like cost and time
- **Ask for feedback** - "Does this approach make sense so far?"

### **Common Mistakes to Avoid:**
- ❌ Not asking clarifying questions upfront
- ❌ Overengineering from the start
- ❌ Not justifying your technology choices
- ❌ Ignoring the interviewer's hints or follow-up questions
- ❌ Spending too much time on non-critical details

**🎯 Remember:** The goal is to show you can design production systems that real engineers would build and maintain. Think like a senior engineer who has to ship this system with a team!
