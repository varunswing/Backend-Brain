# Case Management System (JIRA-like) - System Design Interview

## Problem Statement
*"Design a case management system like JIRA or ServiceNow that handles project management, issue tracking, workflow automation, and team collaboration for large organizations."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What types of work items?" → Issues, tasks, bugs, stories, epics
- "Do we need custom workflows?" → Yes, configurable states, transitions, rules
- "Should we support agile boards?" → Yes, Scrum/Kanban boards, sprint planning
- "What about reporting?" → Yes, dashboards, burn-down charts, velocity tracking
- "Do we need notifications?" → Yes, email, in-app notifications for updates
- "Should we support file attachments?" → Yes, documents, images, logs

**Non-Functional Requirements:**
- "How many users?" → 100K+ users, 10K+ concurrent
- "Expected issue volume?" → 10M+ issues, 100K+ projects
- "Response time?" → <200ms for issue views, <2s for complex queries
- "Availability?" → 99.9% uptime (business critical for teams)

### Requirements Summary:
- **Scale**: 100K users, 10M issues, 10K concurrent users
- **Features**: Issue tracking, workflows, boards, reporting, notifications
- **Customization**: Custom fields, workflows, permissions
- **Performance**: <200ms views, real-time updates

---

## Phase 2: Capacity Estimation (5 minutes)

### Usage Patterns:
```
Total users: 100K
Daily active users: 30K
Concurrent users: 10K peak
Daily issue updates: 500K
Daily comments/attachments: 200K
```

### Storage Requirements:
```
Users: 100K × 2KB = 200MB
Projects: 100K × 10KB = 1GB
Issues: 10M × 5KB = 50GB
Comments: 50M × 1KB = 50GB
Attachments: 5M × 2MB avg = 10TB
Total: ~11TB with indexes
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐
│Web Client   │  │Mobile App   │
│- Issues     │  │- Updates    │
│- Boards     │  │- Comments   │
└─────────────┘  └─────────────┘
       │                │
       └────────────────┘
                │
                ▼
┌─────────────────────────────────┐
│         API Gateway             │
│- Auth - Rate limits - Routing  │
└─────────────────────────────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Issue     │ │Project   │ │User      │
│Service   │ │Service   │ │Service   │
│          │ │          │ │          │
│- CRUD    │ │- Config  │ │- Auth    │
│- Search  │ │- Workflow│ │- Perms   │
│- History │ │- Reports │ │- Profile │
└──────────┘ └──────────┘ └──────────┘
                │
    ┌───────────┼───────────┐
    │           │           │
    ▼           ▼           ▼
┌──────────┐ ┌──────────┐ ┌──────────┐
│Workflow  │ │Notification│ │File      │
│Engine    │ │Service   │ │Service   │
│          │ │          │ │          │
│- States  │ │- Email   │ │- Upload  │
│- Rules   │ │- Push    │ │- Storage │
│- Auto    │ │- Digest  │ │- Preview │
└──────────┘ └──────────┘ └──────────┘
                │
                ▼
┌─────────────────────────────────┐
│          Data Layer             │
│ ┌─────────┐ ┌─────────┐ ┌─────┐ │
│ │PostgreSQL│ │Redis    │ │S3   │ │
│ │- Issues │ │- Cache  │ │-Files│ │
│ │- Projects│ │- Session│ │-Media│ │
│ │- Users  │ │- Search │ │     │ │
│ └─────────┘ └─────────┘ └─────┘ │
└─────────────────────────────────┘
```

### Key Components:
- **Issue Service**: CRUD operations, search, history tracking
- **Workflow Engine**: State management, transitions, automation
- **Project Service**: Configuration, permissions, reporting
- **Notification Service**: Real-time updates, email digests

---

## Phase 4: Database Design (8 minutes)

### PostgreSQL Schema:
```sql
-- Projects
CREATE TABLE projects (
    project_id UUID PRIMARY KEY,
    project_key VARCHAR(10) UNIQUE NOT NULL,
    project_name VARCHAR(300) NOT NULL,
    description TEXT,
    project_type VARCHAR(50) DEFAULT 'software',
    lead_user_id UUID,
    
    -- Project configuration
    workflow_scheme_id UUID,
    permission_scheme_id UUID,
    
    project_status VARCHAR(20) DEFAULT 'active',
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_projects_key (project_key),
    INDEX idx_projects_status (project_status)
);

-- Issues/tickets
CREATE TABLE issues (
    issue_id UUID PRIMARY KEY,
    issue_key VARCHAR(20) UNIQUE NOT NULL,  -- ABC-1234
    project_id UUID REFERENCES projects(project_id),
    
    -- Issue details
    issue_type VARCHAR(50) NOT NULL,         -- bug, story, task, epic
    summary VARCHAR(500) NOT NULL,
    description TEXT,
    priority VARCHAR(20) DEFAULT 'medium',
    
    -- People
    reporter_id UUID NOT NULL,
    assignee_id UUID,
    
    -- Status and workflow
    issue_status VARCHAR(50) NOT NULL,
    resolution VARCHAR(50),
    
    -- Hierarchy
    parent_issue_id UUID REFERENCES issues(issue_id),
    epic_id UUID REFERENCES issues(issue_id),
    
    -- Estimates and time
    original_estimate INTEGER,               -- in minutes
    remaining_estimate INTEGER,
    time_spent INTEGER DEFAULT 0,
    
    -- Custom fields
    custom_fields JSONB DEFAULT '{}',
    
    -- Timestamps
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP,
    
    INDEX idx_issues_project_status (project_id, issue_status),
    INDEX idx_issues_assignee (assignee_id),
    INDEX idx_issues_created (created_at DESC),
    INDEX idx_issues_key (issue_key)
);

-- Comments
CREATE TABLE issue_comments (
    comment_id UUID PRIMARY KEY,
    issue_id UUID REFERENCES issues(issue_id),
    author_id UUID NOT NULL,
    
    comment_text TEXT NOT NULL,
    is_internal BOOLEAN DEFAULT false,
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_comments_issue_time (issue_id, created_at DESC)
);

-- Workflow definitions
CREATE TABLE workflows (
    workflow_id UUID PRIMARY KEY,
    workflow_name VARCHAR(200) NOT NULL,
    project_id UUID REFERENCES projects(project_id),
    
    -- Workflow configuration
    states JSONB NOT NULL,                   -- [{name: "Open", type: "initial"}, ...]
    transitions JSONB NOT NULL,              -- [{from: "Open", to: "In Progress", ...}]
    
    is_active BOOLEAN DEFAULT true,
    
    INDEX idx_workflows_project (project_id, is_active)
);
```

### Redis Caching:
```javascript
// User session and permissions
"user_session:{user_id}": {
  "projects": ["ABC", "XYZ"],
  "permissions": ["issue.create", "issue.edit"],
  "ttl": 3600
}

// Issue cache for quick access
"issue:{issue_key}": {
  "summary": "Fix login bug",
  "status": "In Progress", 
  "assignee": "john.doe",
  "updated_at": 1704110400,
  "ttl": 1800
}

// Project board cache
"board:{project_id}": {
  "columns": [...],
  "issues_by_status": {...},
  "last_updated": 1704110400,
  "ttl": 300
}
```

---

## Phase 5: Critical Flow - Issue Creation & Updates (8 minutes)

### Step-by-Step Flow:
```
1. Issue creation:
   POST /api/issues
   {
     "project": "ABC",
     "type": "bug",
     "summary": "Login not working",
     "assignee": "john.doe"
   }

2. Validation and processing:
   - Check user permissions for project
   - Validate required fields and constraints
   - Generate unique issue key (ABC-1234)
   - Apply default workflow state

3. Workflow processing:
   - Load workflow scheme for project
   - Set initial state based on issue type
   - Apply any automation rules
   - Check field requirements for current state

4. Persistence and indexing:
   - Save issue to database
   - Update search indexes
   - Cache issue data for quick access
   - Log audit trail entry

5. Notifications and updates:
   - Send notifications to assignee and watchers
   - Update project statistics
   - Trigger any webhook integrations
   - Real-time update to connected clients
```

### Technical Challenges:
**Custom Workflows**: "Dynamic state machines with validation rules"
**Real-time Updates**: "WebSocket updates for collaborative editing"
**Search Performance**: "Complex queries across millions of issues"
**Permission System**: "Fine-grained access control at multiple levels"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Search queries** - Complex filters across large datasets
2. **Real-time updates** - WebSocket connections for collaboration
3. **File uploads** - Large attachments and document storage
4. **Reporting queries** - Aggregation across millions of records

### Scaling Solutions:
**Search**: Elasticsearch for fast full-text and faceted search
**Database**: Read replicas, query optimization, indexing strategy
**Real-time**: WebSocket clustering, Redis pub/sub for updates
**Files**: CDN for attachments, async thumbnail generation

### Trade-offs:
- **Flexibility vs Performance**: Custom fields vs query performance
- **Real-time vs Consistency**: Live updates vs data consistency
- **Features vs Simplicity**: Extensive customization vs ease of use

---

## Success Metrics:
- **Page Load Time**: <200ms for issue views
- **Search Performance**: <1 second for complex queries
- **System Uptime**: >99.9% availability
- **User Adoption**: >80% daily active users
- **Notification Delivery**: <5 seconds for real-time updates

**🎯 Demonstrates enterprise workflow management, complex data modeling, real-time collaboration, and building productivity tools used by engineering teams.**




