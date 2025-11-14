# System Design Interview Prompt: Smart Parking Lot Management System

## Interview Duration: 45 Minutes
**Role:** Senior Software Engineer  
**Focus:** Distributed Systems, Database Design, Real-time Systems

---

## 🎯 **Problem Statement** (2 minutes)

*"We're building a smart parking lot management system similar to ParkWhiz or SpotHero. The system should help users find, reserve, and pay for parking spots in real-time across multiple cities. Think of it as the 'Uber for parking spots.'"*

---

## 📋 **Guided Questions for Candidate**

### **Phase 1: Requirements Gathering** (8 minutes)

**Interviewer:** *"Let's start by understanding what we're building. What questions would you ask to clarify the requirements?"*

**Expected areas to cover:**
- **Scale**: How many cities, parking lots, spots, concurrent users?
- **Core features**: Search, reservation, payment, real-time updates
- **User types**: Regular users, lot owners, administrators
- **Platform**: Mobile app, web, kiosks
- **Payment**: Multiple methods, refunds, dynamic pricing
- **Non-functional**: Latency, availability, consistency requirements

**Prompt follow-ups:**
- *"Assume 1000 parking lots, 500K spots, 50K concurrent users during peak hours"*
- *"Users need real-time spot availability and 5-minute reservation holds"*
- *"Strong consistency for bookings, eventual consistency for analytics is acceptable"*

### **Phase 2: Capacity Estimation** (5 minutes)

**Interviewer:** *"Can you estimate the scale and resources we'll need?"*

**Expected calculations:**
- **Traffic**: Daily active users, peak RPS, read/write ratio
- **Storage**: User data, bookings history, spot information
- **Memory**: Active sessions, real-time cache requirements
- **Bandwidth**: API responses, real-time updates

**Look for:**
- Back-of-envelope calculations
- Realistic assumptions about user behavior
- Understanding of peak vs. average traffic patterns

### **Phase 3: High-Level Design** (12 minutes)

**Interviewer:** *"Now design the overall architecture. Draw the main components and how they interact."*

**Expected components:**
- **Client Layer**: Mobile apps, web clients, admin dashboards
- **Load Balancer + CDN**: Geographic distribution, SSL termination
- **API Gateway**: Authentication, rate limiting, routing
- **Core Services**: User Service, Spot Management, Booking Service, Payment Service
- **Supporting Services**: Notification Service, Analytics Service
- **Data Layer**: PostgreSQL (primary), Redis (cache), WebSockets (real-time)

**Key design decisions to discuss:**
- Microservices vs. monolithic architecture
- Synchronous vs. asynchronous communication
- Database choices and caching strategy
- Real-time updates mechanism

### **Phase 4: Database Design** (8 minutes)

**Interviewer:** *"Let's dive deeper into the database design. What tables would you need and how would you structure them?"*

**Expected tables:**
- **users**: Account management, preferences, loyalty program
- **parking_lots**: Location, capacity, pricing, features
- **parking_spots**: Individual spot status, type, availability
- **bookings**: Reservations, timing, payment status
- **vehicles**: User vehicle information
- **payments**: Transaction history and processing

**Key discussion points:**
- Primary keys (UUID vs. auto-increment)
- Indexes for performance (location-based, status queries)
- JSONB fields for flexible data
- Foreign key relationships
- Handling concurrent bookings (double-booking prevention)

### **Phase 5: Critical Workflows** (8 minutes)

**Interviewer:** *"Walk me through the most critical user flow: finding and booking a parking spot."*

**Expected flow:**
1. **Search**: Location-based query with filters
2. **Availability Check**: Real-time spot status from cache
3. **Spot Selection**: Dynamic pricing, spot details
4. **Reservation**: Distributed locking, temporary hold
5. **Payment**: PCI-compliant processing
6. **Confirmation**: QR code generation, real-time updates

**Focus areas:**
- Race condition handling (multiple users booking same spot)
- Payment processing reliability
- Real-time cache invalidation
- Error handling and rollback scenarios

### **Phase 6: Scaling & Trade-offs** (2 minutes)

**Interviewer:** *"What are the main bottlenecks and how would you scale this system?"*

**Expected discussion:**
- **Database scaling**: Read replicas, sharding strategies
- **Cache optimization**: Redis clustering, cache warming
- **Geographic distribution**: Regional deployments
- **Peak traffic handling**: Auto-scaling, queue-based processing

---

## 🔍 **Evaluation Criteria**

### **Excellent Candidate (Senior Level):**
- ✅ Asks clarifying questions before jumping to solutions
- ✅ Provides realistic capacity estimations with calculations
- ✅ Designs scalable architecture with proper component separation
- ✅ Shows deep understanding of database design and indexing
- ✅ Handles race conditions and consistency challenges
- ✅ Discusses trade-offs and alternative approaches
- ✅ Considers real-world constraints (cost, time, team size)

### **Good Candidate (Mid-Level):**
- ✅ Covers basic requirements and functional design
- ✅ Creates reasonable high-level architecture
- ✅ Designs adequate database schema
- ✅ Explains main user flows
- ❌ May miss some edge cases or advanced scaling topics

### **Needs Improvement:**
- ❌ Jumps to solution without clarifying requirements
- ❌ Unrealistic estimations or skips capacity planning
- ❌ Monolithic thinking or inappropriate technology choices
- ❌ Poor database design (no indexes, wrong relationships)
- ❌ Doesn't consider failure scenarios or race conditions

---

## 🎨 **Bonus Discussion Points** (If time permits)

- **Machine Learning**: Dynamic pricing, demand prediction
- **IoT Integration**: Smart sensors for automated spot detection
- **Mobile Features**: Offline mode, AR navigation, contactless payment
- **Enterprise Features**: Corporate accounts, bulk reservations
- **Global Expansion**: Multi-currency, regional compliance
- **Security**: PCI compliance, data encryption, fraud prevention

---

## 📝 **Sample Follow-up Questions**

1. *"How would you handle a situation where 1000 users try to book the last available spot simultaneously?"*

2. *"What if the payment gateway goes down during peak hours?"*

3. *"How would you implement dynamic pricing that increases rates during high demand?"*

4. *"Design a notification system to alert users when spots become available in their preferred locations."*

5. *"How would you prevent users from gaming the system by making fake reservations?"*

---

## ⏱️ **Time Management Tips for Interviewer**

- **0-2 min**: Problem introduction and context setting
- **2-10 min**: Requirements clarification and capacity estimation  
- **10-22 min**: High-level design and architecture discussion
- **22-30 min**: Database design and data modeling
- **30-38 min**: Critical workflows and system interactions
- **38-40 min**: Scaling, bottlenecks, and trade-offs
- **40-45 min**: Wrap-up, bonus topics, and questions

**🎯 Goal:** Assess the candidate's ability to design a real-world, production-ready system while demonstrating strong technical depth and practical engineering judgment.
