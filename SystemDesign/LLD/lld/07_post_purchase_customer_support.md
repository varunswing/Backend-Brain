# Post-Purchase Customer Support System - System Design Interview

## Problem Statement
*"Design a comprehensive customer support platform like Zendesk that handles multi-channel interactions, automated responses, agent management, and integrates with e-commerce systems."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:

**Functional Requirements:**
- "What channels do we need to support?" → Email, live chat, phone, social media, SMS
- "Do we need automated responses?" → Yes, chatbots, auto-replies, smart routing
- "Should we track customer journey?" → Yes, order history, past interactions, context awareness
- "Do we need agent management?" → Yes, workload distribution, skill-based routing, performance tracking
- "What about self-service?" → Yes, knowledge base, FAQ, order tracking portal
- "Should we support escalations?" → Yes, SLA tracking, supervisor escalation, priority queues

**Non-Functional Requirements:**
- "What's our interaction volume?" → 100K+ concurrent customers, 1M+ tickets/day
- "Expected response times?" → <2 seconds acknowledgment, <5 minutes first response
- "Agent capacity?" → 10K+ agents handling 50+ tickets/day each
- "Availability needs?" → 99.99% uptime (customer service is business critical)
- "Global support?" → 24/7 coverage, multi-language, regional compliance

### Requirements Summary:
- **Scale**: 100K concurrent users, 1M tickets/day, 10K agents
- **Channels**: Email, chat, phone, social media, SMS support
- **Features**: Auto-responses, routing, escalation, knowledge base
- **Performance**: <2s acknowledgment, <5min response, 99.99% uptime
- **Intelligence**: Context awareness, sentiment analysis, auto-resolution

---

## Phase 2: Capacity Estimation (5 minutes)

### Interaction Volume:
```
Daily tickets: 1M tickets/day
Peak concurrent: 100K simultaneous interactions
Average interaction duration: 10 minutes
Agent capacity: 10K agents × 50 tickets/day = 500K handled
Automation resolution: 50% tickets auto-resolved
```

### Storage Requirements:
```
Customer profiles: 50M customers × 2KB = 100GB
Ticket data: 1M/day × 5KB × 365 × 3 years = 5.5TB
Conversation history: 1M/day × 10KB × 365 × 3 years = 11TB
Knowledge base: 100K articles × 50KB = 5GB
Attachments: 200K/day × 1MB × 365 × 3 years = 200TB
Total: ~220TB with indexes and replicas
```

### Real-time Requirements:
```
Active chat sessions: 50K concurrent
WebSocket connections: 150K (customers + agents + supervisors)
Message throughput: 10K messages/second during peak
Knowledge base searches: 50K queries/second
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Customer     │  │Agent        │  │Supervisor   │
│Portal       │  │Dashboard    │  │Analytics    │
│- Chat       │  │- Tickets    │  │- Reports    │
│- Self-serve │  │- Knowledge  │  │- Performance│
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            Load Balancer                │
│- Channel routing - Sticky sessions     │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            API Gateway                  │
│- Authentication - Rate limiting         │
│- Channel orchestration - Routing       │
└─────────────────────────────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Ticket      │ │Chat        │ │Customer    │
│Service     │ │Service     │ │Service     │
│            │ │            │ │            │
│- Create    │ │- WebSocket │ │- Profile   │
│- Route     │ │- Real-time │ │- History   │
│- Track     │ │- Bot       │ │- Context   │
└────────────┘ └────────────┘ └────────────┘
    │                   │                   │
    └───────────────────┼───────────────────┘
                        │
    ┌───────────────────┼───────────────────┐
    │                   │                   │
    ▼                   ▼                   ▼
┌────────────┐ ┌────────────┐ ┌────────────┐
│Agent       │ │Knowledge   │ │Notification│
│Management  │ │Base        │ │Service     │
│            │ │            │ │            │
│- Assignment│ │- Search    │ │- Email     │
│- Workload  │ │- AI Assist │ │- SMS       │
│- Skills    │ │- Auto-Resp │ │- Push      │
└────────────┘ └────────────┘ └────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│          Supporting Services            │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │AI/ML    │  │Analytics│  │Integration│  │
│ │Engine   │  │Service  │  │Hub       │  │
│ │         │  │         │  │         │  │
│ │- NLP    │  │- Reports│  │- CRM     │  │
│ │- Sentiment│ │- KPI   │  │- Orders  │  │
│ │- Auto   │  │- Trends │  │- Billing │  │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│               Data Layer                │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │PostgreSQL│  │Redis    │  │Elasticsearch││
│ │         │  │         │  │            │ │
│ │- Tickets│  │- Sessions│ │- Search    │ │
│ │- Agents │  │- Cache   │ │- Analytics │ │
│ │- Customers│ │- Queues │ │- Knowledge │ │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
```

### Key Components:
- **Ticket Service**: Lifecycle management, routing, SLA tracking
- **Chat Service**: Real-time messaging, WebSocket management
- **Agent Management**: Workload distribution, skill-based routing
- **Knowledge Base**: Self-service, agent assistance, AI integration
- **AI/ML Engine**: Automated responses, sentiment analysis, classification

---

## Phase 4: Database Design (8 minutes)

### PostgreSQL Schema:
```sql
-- Customers
CREATE TABLE customers (
    customer_id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    phone VARCHAR(20),
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    
    -- Customer segmentation
    tier VARCHAR(20) DEFAULT 'standard',  -- vip, standard, basic
    lifetime_value DECIMAL(12,2) DEFAULT 0,
    
    -- Support context
    preferred_language VARCHAR(5) DEFAULT 'en',
    preferred_channel VARCHAR(20) DEFAULT 'email',
    timezone VARCHAR(50),
    
    -- History metrics
    total_tickets INTEGER DEFAULT 0,
    satisfaction_score DECIMAL(3,2) DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT NOW(),
    INDEX idx_customers_email (email),
    INDEX idx_customers_tier (tier)
);

-- Support agents
CREATE TABLE agents (
    agent_id UUID PRIMARY KEY,
    employee_id VARCHAR(50) UNIQUE,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    
    -- Agent capabilities
    skills TEXT[] DEFAULT '{}',           -- technical, billing, returns
    languages TEXT[] DEFAULT '{"en"}',
    max_concurrent_chats INTEGER DEFAULT 5,
    
    -- Work schedule
    shift_start TIME,
    shift_end TIME,
    timezone VARCHAR(50),
    
    -- Performance metrics
    average_resolution_time INTEGER,      -- in minutes
    customer_satisfaction DECIMAL(3,2) DEFAULT 0,
    tickets_handled_today INTEGER DEFAULT 0,
    
    -- Status
    agent_status VARCHAR(20) DEFAULT 'offline', -- online, offline, busy, away
    current_workload INTEGER DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_agents_status_skills (agent_status, skills),
    INDEX idx_agents_workload (current_workload, agent_status)
);

-- Support tickets
CREATE TABLE tickets (
    ticket_id UUID PRIMARY KEY,
    ticket_number VARCHAR(20) UNIQUE NOT NULL, -- T-2024-001234
    
    -- Participants
    customer_id UUID REFERENCES customers(customer_id),
    assigned_agent_id UUID REFERENCES agents(agent_id),
    created_by UUID,                      -- Can be customer, agent, or system
    
    -- Ticket details
    channel VARCHAR(20) NOT NULL,         -- email, chat, phone, social
    category VARCHAR(50),                 -- billing, technical, returns, general
    subcategory VARCHAR(100),
    priority VARCHAR(20) DEFAULT 'medium', -- low, medium, high, urgent, critical
    
    -- Content
    subject VARCHAR(500) NOT NULL,
    description TEXT,
    
    -- Status and tracking
    ticket_status VARCHAR(20) DEFAULT 'open', -- open, pending, resolved, closed
    resolution_summary TEXT,
    
    -- SLA tracking
    sla_deadline TIMESTAMP,
    first_response_time INTEGER,          -- minutes to first response
    resolution_time INTEGER,              -- minutes to resolution
    
    -- Customer feedback
    satisfaction_rating INTEGER CHECK (satisfaction_rating >= 1 AND satisfaction_rating <= 5),
    feedback_comment TEXT,
    
    -- Timestamps
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    first_responded_at TIMESTAMP,
    resolved_at TIMESTAMP,
    closed_at TIMESTAMP,
    
    -- Indexes for efficient queries
    INDEX idx_tickets_customer (customer_id, created_at DESC),
    INDEX idx_tickets_agent (assigned_agent_id, ticket_status),
    INDEX idx_tickets_status_priority (ticket_status, priority, created_at),
    INDEX idx_tickets_sla (sla_deadline, ticket_status),
    INDEX idx_tickets_channel_category (channel, category, created_at)
);

-- Conversation messages
CREATE TABLE messages (
    message_id UUID PRIMARY KEY,
    ticket_id UUID REFERENCES tickets(ticket_id),
    
    -- Message details
    sender_type VARCHAR(20) NOT NULL,     -- customer, agent, system, bot
    sender_id UUID,                       -- customer_id or agent_id
    
    -- Content
    message_text TEXT NOT NULL,
    message_type VARCHAR(20) DEFAULT 'text', -- text, image, file, system_note
    attachments JSONB DEFAULT '[]',
    
    -- Message metadata
    is_internal BOOLEAN DEFAULT false,    -- Internal agent notes
    is_auto_generated BOOLEAN DEFAULT false,
    
    -- Timestamps
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_messages_ticket_time (ticket_id, created_at),
    INDEX idx_messages_sender (sender_type, sender_id, created_at)
);
```

### Redis Cache Strategy:
```javascript
// Agent availability and workload
"agent_status:{agent_id}": {
  "status": "online",
  "current_workload": 3,
  "max_concurrent": 5,
  "skills": ["technical", "billing"],
  "languages": ["en", "es"],
  "last_activity": 1704110400,
  "ttl": 300
}

// Active chat sessions
"chat_session:{session_id}": {
  "customer_id": "uuid",
  "agent_id": "uuid",
  "ticket_id": "uuid",
  "channel": "web_chat",
  "started_at": 1704110400,
  "last_message_at": 1704110450,
  "ttl": 3600
}

// Customer context cache
"customer_context:{customer_id}": {
  "recent_orders": [...],
  "open_tickets": [...],
  "satisfaction_history": [...],
  "preferences": {...},
  "ttl": 1800
}

// Knowledge base suggestions
"kb_suggestions:{query_hash}": {
  "articles": [...],
  "confidence_scores": [...],
  "last_updated": 1704110400,
  "ttl": 900
}

// Real-time queue status
"queue_status:{channel}:{category}": {
  "waiting_count": 25,
  "average_wait_time": 180,
  "available_agents": 8,
  "last_updated": 1704110400,
  "ttl": 60
}
```

### Design Decisions:
- **Unified messaging**: Single table for all channel communications
- **Agent skills**: Flexible skill-based routing system
- **SLA tracking**: Built-in performance monitoring
- **Customer context**: Rich history for personalized support

---

## Phase 5: Critical Flow - Ticket Creation & Assignment (8 minutes)

### Most Critical Flow: Customer Creates Support Ticket

**1. Ticket Creation**
```
Customer creates ticket via chat/email:
POST /api/tickets
{
  "channel": "web_chat",
  "subject": "Order refund request",
  "description": "I need to return my order #12345",
  "category": "returns"
}
```

**2. Intelligent Routing & Classification**
```
Ticket Service processing:
1. Extract customer information and load context
2. AI classification of category/priority:
   - NLP analysis of description
   - Customer tier consideration (VIP gets higher priority)
   - Historical pattern analysis
3. Generate unique ticket number (T-2024-001234)
4. Calculate SLA deadline based on priority and customer tier
5. Check for potential auto-resolution via knowledge base
```

**3. Agent Assignment Algorithm**
```
Agent Management Service:
1. Query available agents with required skills
2. Consider current workload and availability
3. Language matching for international customers
4. Apply assignment logic:
   - Skill match (40% weight)
   - Current workload (30% weight) 
   - Customer satisfaction history (20% weight)
   - Response time performance (10% weight)
5. Assign to best-matched agent or add to queue
```

**4. Real-time Notification & Context Loading**
```
Once assigned:
1. Send real-time notification to agent via WebSocket
2. Load customer context:
   - Order history from e-commerce system
   - Previous ticket history
   - Customer preferences and notes
3. Provide AI-suggested responses based on similar tickets
4. Start SLA timer for first response
```

**5. Conversation Management**
```
During interaction:
1. Real-time message exchange via WebSocket
2. Sentiment analysis on customer messages
3. Auto-escalation if negative sentiment detected
4. Knowledge base suggestions for agent
5. Track response times and update SLA status
6. Auto-save conversation for future reference
```

### Technical Challenges:

**Real-time Communication:**
- "WebSocket management for scalable chat connections"
- "Message ordering and delivery guarantees"
- "Connection handling during network issues"

**Intelligent Routing:**
- "Machine learning for category classification"
- "Dynamic agent scoring based on performance"
- "Load balancing across agent availability"

**Context Awareness:**
- "Integration with multiple backend systems"
- "Real-time customer data aggregation"
- "Performance optimization for data fetching"

**Scalability:**
- "Queue management during traffic spikes"
- "Auto-scaling agent capacity"
- "Database optimization for high-volume operations"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **WebSocket connections**: 100K+ concurrent chat sessions
2. **Agent assignment**: Complex routing logic during peak hours
3. **Database queries**: Customer context loading with multiple joins
4. **Knowledge base search**: Real-time article recommendations

### Scaling Solutions:

**Connection Management:**
```
- WebSocket clustering: Distribute connections across multiple servers
- Sticky sessions: Route customers to same server for consistency
- Connection pooling: Efficient resource utilization
- Graceful failover: Seamless connection migration during outages
```

**Database Optimization:**
```
- Read replicas: Separate read/write operations
- Caching strategy: Multi-layer caching for customer context
- Query optimization: Indexes on frequently accessed fields
- Connection pooling: Manage database connections efficiently
```

**Search & AI:**
```
- Elasticsearch: Fast full-text search for knowledge base
- ML model caching: Cache classification results
- Async processing: Background jobs for non-critical AI tasks
- CDN integration: Cache knowledge base articles globally
```

**Agent Management:**
```
- Auto-scaling: Dynamic agent capacity based on queue length
- Predictive staffing: ML-based demand forecasting
- Skill optimization: Dynamic skill assignment based on demand
- Performance monitoring: Real-time agent productivity tracking
```

### Trade-offs:
- **Response Speed vs Accuracy**: Quick assignment vs perfect skill matching
- **Automation vs Human Touch**: Bot resolution vs agent expertise
- **Real-time vs Cost**: Immediate updates vs resource efficiency

---

## Advanced Features:

**AI/ML Integration:**
- Sentiment analysis for proactive escalation
- Automated response suggestions
- Customer churn prediction
- Dynamic priority adjustment based on customer value

**Omnichannel Support:**
- Unified conversation history across all channels
- Context preservation during channel switches
- Social media monitoring and engagement
- Voice-to-text integration for phone support

---

## Success Metrics:
- **First Response Time**: <5 minutes average across all channels
- **Resolution Rate**: >85% tickets resolved within SLA
- **Customer Satisfaction**: >90% CSAT score
- **Agent Efficiency**: 50+ tickets handled per agent per day
- **System Uptime**: 99.99% availability during business hours

**🎯 This design demonstrates customer experience expertise, real-time communication systems, AI integration, and building scalable support platforms that handle millions of customer interactions.**




