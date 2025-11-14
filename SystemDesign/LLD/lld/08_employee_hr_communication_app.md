# Employee-HR Communication App - System Design Interview

## Problem Statement
*"Design an internal communication platform like Workday that facilitates employee-HR interactions, handles requests, document management, and workflow automation for large organizations."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What types of HR requests?" → Leave requests, benefits enrollment, policy queries, document requests
- "Do we need workflow automation?" → Yes, approval workflows, escalation, notifications
- "Should we handle document management?" → Yes, e-signatures, policy distribution
- "Do we need employee directory?" → Yes, org chart, contact info, reporting structure
- "What about mobile support?" → Yes, mobile apps for on-the-go access

**Non-Functional Requirements:**
- "How many employees?" → 50K+ employees across multiple locations
- "Expected usage?" → Peak during enrollment periods, daily for requests
- "Response time?" → <2 seconds for most operations
- "Availability?" → 99.9% uptime during business hours

### Requirements Summary:
- **Scale**: 50K+ employees, 5K concurrent users during peak
- **Features**: Request management, workflows, documents, directory
- **Mobile**: iOS/Android apps with offline capabilities  
- **Integrations**: HRIS, payroll, email, calendar systems

---

## Phase 2: Capacity Estimation (5 minutes)

### User Activity:
```
Total employees: 50K
Daily active users: 15K (30% usage)
Peak concurrent: 5K (open enrollment)
Daily requests: 2K HR requests
Daily notifications: 50 announcements
```

### Storage Requirements:
```
Employee profiles: 50K × 5KB = 250MB
Documents: 1M × 2MB avg = 2TB
Request history: 2K/day × 10KB × 5 years = 36GB
Total: ~3TB with attachments
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐
│Employee     │  │HR Staff     │
│Portal       │  │Dashboard    │
│- Self-serve │  │- Requests   │
└─────────────┘  └─────────────┘
       │                │
       └────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│         API Gateway             │
│- Auth - Authorization - Routing│
└─────────────────────────────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Employee  │ │Request   │ │Workflow  │
│Service   │ │Service   │ │Engine    │
│          │ │          │ │          │
│- Profile │ │- Create  │ │- Approval│
│- Directory│ │- Track   │ │- Route   │
└──────────┘ └──────────┘ └──────────┘
    │           │           │
    └───────────┼───────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Document  │ │Notification│ │Analytics │
│Service   │ │Service   │ │Service   │
│          │ │          │ │          │
│- Upload  │ │- Email   │ │- Reports │
│- E-sign  │ │- Push    │ │- Metrics │
└──────────┘ └──────────┘ └──────────┘
                │
                ▼
┌─────────────────────────────────┐
│          Data Layer             │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │PostgreSQL│ │MongoDB  │ │Redis│ │
│ │- Employee│ │- Docs   │ │-Cache│ │
│ │- Requests│ │- Files  │ │-Queue│ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
```

### Key Components:
- **Employee Service**: Profile management, directory, authentication
- **Request Service**: HR request lifecycle management
- **Workflow Engine**: Approval routing, escalation, SLA tracking
- **Document Service**: File management, e-signatures

---

## Phase 4: Database Design (8 minutes)

### PostgreSQL Schema:
```sql
-- Employees
CREATE TABLE employees (
    employee_id UUID PRIMARY KEY,
    employee_number VARCHAR(20) UNIQUE,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    department VARCHAR(100),
    manager_id UUID REFERENCES employees(employee_id),
    employment_status VARCHAR(20) DEFAULT 'active',
    
    INDEX idx_employees_manager (manager_id),
    INDEX idx_employees_department (department)
);

-- HR requests  
CREATE TABLE hr_requests (
    request_id UUID PRIMARY KEY,
    request_number VARCHAR(30) UNIQUE,
    employee_id UUID REFERENCES employees(employee_id),
    request_type VARCHAR(50) NOT NULL,
    subject VARCHAR(500),
    request_status VARCHAR(30) DEFAULT 'submitted',
    current_approver_id UUID REFERENCES employees(employee_id),
    request_data JSONB DEFAULT '{}',
    
    submitted_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    
    INDEX idx_requests_employee (employee_id, submitted_at DESC),
    INDEX idx_requests_approver (current_approver_id, request_status)
);

-- Workflow stages
CREATE TABLE request_actions (
    action_id UUID PRIMARY KEY,
    request_id UUID REFERENCES hr_requests(request_id),
    actor_id UUID REFERENCES employees(employee_id),
    action_type VARCHAR(30) NOT NULL,
    comment TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);
```

### Redis Caching:
```javascript
// Employee session
"employee_session:{id}": {
  "name": "John Doe",
  "department": "Engineering", 
  "permissions": [...],
  "ttl": 3600
}

// Request status
"request_status:{id}": {
  "status": "pending_approval",
  "current_approver": "manager_uuid",
  "ttl": 1800
}
```

---

## Phase 5: Critical Flow - HR Request Processing (8 minutes)

### Step-by-Step Flow:
```
1. Employee submits leave request:
   POST /api/requests
   {"type": "leave", "start_date": "2024-06-15", "end_date": "2024-06-17"}

2. System processing:
   - Generate request number (HR-2024-001234)
   - Load workflow template for leave requests  
   - Determine first approver (direct manager)
   - Calculate SLA deadline
   
3. Approval routing:
   - Send notification to manager
   - Load employee context (leave balance, history)
   - Provide approve/reject options
   
4. Manager decision:
   - Reviews with context and approves/rejects
   - System updates status and notifies employee
```

### Technical Challenges:
**Workflow Management**: "Dynamic routing based on business rules"
**Integration**: "Real-time sync with HRIS for employee data"
**Security**: "Access control and audit trails for documents"
**Mobile**: "Offline capabilities and optimized workflows"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **HRIS integration** - External system dependencies
2. **Document storage** - Large file uploads/downloads
3. **Notification delivery** - Mass announcements to 50K employees  
4. **Peak usage** - Open enrollment traffic spikes

### Scaling Solutions:
**Database**: Read replicas, caching for employee directory
**Documents**: CDN for file delivery, async processing
**Notifications**: Queue-based delivery, batch processing
**Integration**: Circuit breakers, retry logic, caching

### Trade-offs:
- **Real-time vs Consistency**: Immediate updates vs data sync
- **Security vs Accessibility**: Strong controls vs user experience
- **Feature Richness vs Simplicity**: Complex workflows vs ease of use

---

## Success Metrics:
- **Request Processing**: <24 hours average for standard requests
- **System Adoption**: >90% employee engagement rate
- **Mobile Usage**: >60% requests via mobile
- **Integration Accuracy**: >99.5% data synchronization
- **User Satisfaction**: >4.5/5 rating

**🎯 Demonstrates enterprise software expertise, workflow automation, and building internal platforms that improve employee experience.**