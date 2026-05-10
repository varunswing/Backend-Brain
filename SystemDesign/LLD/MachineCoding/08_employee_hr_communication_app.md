# Employee-HR Communication App - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database Design with Explanations](#database-design-with-explanations)
4. [Design Patterns](#design-patterns)
5. [SOLID Principles](#solid-principles)
6. [Code Implementation](#code-implementation)
7. [Edge Cases & Tests](#edge-cases--tests)
8. [Summary](#summary)

---

## Problem Statement

Design an internal HR communication platform that facilitates employee-HR interactions, handles various request types (leave, expense, grievance), approval workflows, and company announcements. The system must support extensible request processing, multi-level approvals, and event-driven notifications.

---

## Requirements

### Functional Requirements
1. **Employee Management**: Register employees with department, manager, and role
2. **HR Request Submission**: Submit leave, expense claim, and grievance requests
3. **Approval Workflow**: Multi-level approval chain (Manager → HR → Director)
4. **Request Processing**: Different validation and processing logic per request type
5. **Announcements**: HR can publish announcements; track read status per employee
6. **Leave Requests**: Dedicated leave tracking with type (sick, vacation, unpaid)
7. **Event Notifications**: Email, compliance logging, and audit trail on key events

### Non-Functional Requirements
- Extensible for new request types without modifying core logic (Strategy)
- Support adding new approval levels or observers without changing existing code
- Request state transitions must be validated (State machine)
- Dependency injection for testability and loose coupling

---

## Database Design with Explanations

```sql
-- ============================================================================
-- TABLE: departments
-- WHY: Departments are organizational units. We need this table because:
--      1. Employees belong to departments (Engineering, HR, Sales)
--      2. Approval routing may depend on department (e.g., HR requests skip HR dept)
--      3. Announcements can be targeted by department
--      4. Reporting and analytics require department-level aggregation
--
-- RELATIONSHIP: Self-referencing for hierarchy (e.g., Engineering has sub-depts)
--   WHY? Large orgs have nested structures: Engineering → Backend → Platform
-- ============================================================================
CREATE TABLE departments (
    department_id UUID PRIMARY KEY,
    department_name VARCHAR(100) NOT NULL,
    parent_department_id UUID REFERENCES departments(department_id),
    head_employee_id UUID,                    -- Set via FK after employees exist
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_departments_parent ON departments(parent_department_id);

-- ============================================================================
-- TABLE: employees
-- WHY: Core entity representing each employee. Required for:
--      1. Authentication and authorization
--      2. Request submission (who submitted)
--      3. Approval chain (manager_id for routing)
--      4. Announcement read tracking
--
-- RELATIONSHIP: employees → departments (Many-to-One)
--   WHY FK? Each employee belongs to exactly one department. Enables queries
--   like "all employees in Engineering" and department-level announcements.
--
-- RELATIONSHIP: employees → employees (self-reference via manager_id)
--   WHY FK? Approval chain starts with direct manager. Hierarchical org structure.
--   manager_id NULL for top-level (CEO, etc.)
-- ============================================================================
CREATE TABLE employees (
    employee_id UUID PRIMARY KEY,
    employee_number VARCHAR(20) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    department_id UUID NOT NULL REFERENCES departments(department_id),
    manager_id UUID REFERENCES employees(employee_id),
    role VARCHAR(50) NOT NULL,               -- EMPLOYEE, MANAGER, HR, DIRECTOR
    employment_status VARCHAR(20) DEFAULT 'ACTIVE',  -- ACTIVE, INACTIVE, TERMINATED
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_employees_department ON employees(department_id);
CREATE INDEX idx_employees_manager ON employees(manager_id);
CREATE INDEX idx_employees_status ON employees(employment_status) WHERE employment_status = 'ACTIVE';

-- Add FK from departments to employees (head) - deferred to avoid circular dependency
ALTER TABLE departments ADD CONSTRAINT fk_departments_head 
    FOREIGN KEY (head_employee_id) REFERENCES employees(employee_id);

-- ============================================================================
-- TABLE: hr_requests
-- WHY: Central table for all HR request types (leave, expense, grievance).
--      Single table with polymorphic type because:
--      1. All requests share common lifecycle (submit → approve → complete)
--      2. Unified tracking and reporting across request types
--      3. request_data JSONB holds type-specific payload (Strategy processes it)
--
-- RELATIONSHIP: hr_requests → employees (Many-to-One, submitter)
--   WHY FK? Every request has an owner. Enables "my requests" and audit trail.
--
-- RELATIONSHIP: hr_requests → employees (Many-to-One, current_approver_id)
--   WHY FK? Tracks who must act next. Enables "pending my approval" dashboard.
-- ============================================================================
CREATE TABLE hr_requests (
    request_id UUID PRIMARY KEY,
    request_number VARCHAR(30) UNIQUE NOT NULL,   -- HR-2024-001234
    employee_id UUID NOT NULL REFERENCES employees(employee_id),
    request_type VARCHAR(50) NOT NULL,            -- LEAVE, EXPENSE_CLAIM, GRIEVANCE
    request_status VARCHAR(30) DEFAULT 'SUBMITTED', -- State machine: SUBMITTED→PENDING_APPROVAL→APPROVED/REJECTED
    current_approver_id UUID REFERENCES employees(employee_id),
    request_data JSONB NOT NULL DEFAULT '{}',    -- Type-specific: {"start_date":"...","leave_type":"SICK"}
    submitted_at TIMESTAMP DEFAULT NOW(),
    completed_at TIMESTAMP,
    
    CONSTRAINT chk_request_status CHECK (request_status IN (
        'SUBMITTED', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'CANCELLED'
    ))
);

CREATE INDEX idx_hr_requests_employee ON hr_requests(employee_id, submitted_at DESC);
CREATE INDEX idx_hr_requests_approver ON hr_requests(current_approver_id, request_status);
CREATE INDEX idx_hr_requests_type_status ON hr_requests(request_type, request_status);

-- ============================================================================
-- TABLE: request_approvals
-- WHY: Audit trail of each approval step. A request may pass through multiple
--      approvers (Manager → HR → Director). We need to record:
--      1. Who approved/rejected at each stage
--      2. Comments and timestamps
--      3. Order of approvals (approval_level) for Chain of Responsibility
--
-- RELATIONSHIP: request_approvals → hr_requests (Many-to-One)
--   WHY FK? Each approval record belongs to one request. Cascade delete when
--   request is deleted (or soft-delete approvals for audit compliance).
--
-- RELATIONSHIP: request_approvals → employees (Many-to-One, approver_id)
--   WHY FK? Links to the employee who performed the action.
-- ============================================================================
CREATE TABLE request_approvals (
    approval_id UUID PRIMARY KEY,
    request_id UUID NOT NULL REFERENCES hr_requests(request_id) ON DELETE CASCADE,
    approver_id UUID NOT NULL REFERENCES employees(employee_id),
    approval_level INTEGER NOT NULL,             -- 1=Manager, 2=HR, 3=Director
    approval_status VARCHAR(20) NOT NULL,        -- APPROVED, REJECTED
    comment TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    
    CONSTRAINT chk_approval_status CHECK (approval_status IN ('APPROVED', 'REJECTED'))
);

CREATE INDEX idx_request_approvals_request ON request_approvals(request_id);

-- ============================================================================
-- TABLE: leave_requests
-- WHY: Specialized table for leave-specific data. hr_requests holds the
--      generic workflow; leave_requests holds leave-domain attributes:
--      1. Leave type (sick, vacation, unpaid) affects policy
--      2. Date range and days count for balance deduction
--      3. May have different approval rules than expense/grievance
--
-- RELATIONSHIP: leave_requests → hr_requests (One-to-One)
--   WHY FK? Each leave request extends exactly one hr_request. The hr_request
--   stores request_data JSONB, but we normalize critical leave fields for
--   querying (e.g., "all leave in June", "leave balance by type").
-- ============================================================================
CREATE TABLE leave_requests (
    leave_request_id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE REFERENCES hr_requests(request_id) ON DELETE CASCADE,
    leave_type VARCHAR(30) NOT NULL,             -- SICK, VACATION, UNPAID, MATERNITY
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    total_days DECIMAL(5,2) NOT NULL,
    reason TEXT,
    
    CONSTRAINT chk_leave_dates CHECK (end_date >= start_date)
);

CREATE INDEX idx_leave_requests_dates ON leave_requests(start_date, end_date);
CREATE INDEX idx_leave_requests_type ON leave_requests(leave_type);

-- ============================================================================
-- TABLE: announcements
-- WHY: Company-wide or department-specific announcements from HR.
--      Examples: policy updates, holiday schedule, office closure.
--
-- RELATIONSHIP: announcements → employees (Many-to-One, created_by)
--   WHY FK? Track who published. Optional - some may be system-generated.
--
-- RELATIONSHIP: announcements → departments (Many-to-One, optional)
--   WHY FK? NULL = company-wide; non-NULL = targeted to that department.
-- ============================================================================
CREATE TABLE announcements (
    announcement_id UUID PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    body TEXT NOT NULL,
    created_by UUID REFERENCES employees(employee_id),
    department_id UUID REFERENCES departments(department_id),  -- NULL = all
    priority VARCHAR(20) DEFAULT 'NORMAL',       -- LOW, NORMAL, HIGH, URGENT
    published_at TIMESTAMP DEFAULT NOW(),
    expires_at TIMESTAMP
);

CREATE INDEX idx_announcements_published ON announcements(published_at DESC);
CREATE INDEX idx_announcements_department ON announcements(department_id) WHERE department_id IS NOT NULL;

-- ============================================================================
-- TABLE: announcement_reads
-- WHY: Track which employees have read which announcements. Enables:
--      1. "Mark as read" functionality
--      2. Compliance: prove employee was notified (e.g., policy acknowledgment)
--      3. Analytics: read rate per announcement
--
-- RELATIONSHIP: announcement_reads → announcements (Many-to-One)
--   WHY FK? Each read record links to one announcement.
--
-- RELATIONSHIP: announcement_reads → employees (Many-to-One)
--   WHY FK? Each read record links to one employee.
--
-- UNIQUE(announcement_id, employee_id): One read record per employee per announcement.
-- ============================================================================
CREATE TABLE announcement_reads (
    read_id UUID PRIMARY KEY,
    announcement_id UUID NOT NULL REFERENCES announcements(announcement_id) ON DELETE CASCADE,
    employee_id UUID NOT NULL REFERENCES employees(employee_id) ON DELETE CASCADE,
    read_at TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(announcement_id, employee_id)
);

CREATE INDEX idx_announcement_reads_employee ON announcement_reads(employee_id);
CREATE INDEX idx_announcement_reads_announcement ON announcement_reads(announcement_id);
```

---

## Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `RequestProcessingStrategy` with `LeaveRequestProcessor`, `ExpenseClaimProcessor`, `GrievanceProcessor` | Each request type has different validation and processing logic. Strategy allows adding new types (e.g., `TransferRequestProcessor`) without modifying the orchestrator. Open/Closed Principle. |
| **Observer** | `HREventObserver` with `EmailNotificationObserver`, `ComplianceLogObserver`, `AuditLogObserver` | When requests are submitted/approved, multiple side effects occur (email, compliance, audit). Observer decouples these; new observers can be added without changing event sources. |
| **State** | Request lifecycle: `SUBMITTED` → `PENDING_APPROVAL` → `APPROVED`/`REJECTED` | Valid transitions must be enforced. State pattern encapsulates transition logic and prevents invalid states (e.g., cannot approve an already rejected request). |
| **Factory** | `RequestProcessorFactory` | Returns the correct `RequestProcessingStrategy` based on `RequestType`. Encapsulates object creation; callers don't need to know concrete implementations. |
| **Chain of Responsibility** | `ApprovalChain`: `ManagerApprovalHandler` → `HRApprovalHandler` → `DirectorApprovalHandler` | Approval flows through levels. Each handler decides: approve, reject, or pass to next. Easy to add/remove/reorder levels. |

---

## SOLID Principles

| Principle | Application |
|-----------|-------------|
| **S**ingle Responsibility | `LeaveRequestProcessor` only handles leave logic; `EmailNotificationObserver` only sends emails; `Request` model only manages its own state. |
| **O**pen/Closed | New request types: add new Strategy impl, register in Factory. New observers: implement `HREventObserver`, register. No changes to existing code. |
| **L**iskov Substitution | Any `RequestProcessingStrategy` can replace another; any `ApprovalHandler` can replace another. Subtypes honor contracts. |
| **I**nterface Segregation | `RequestProcessingStrategy` has only `validate()` and `process()`. `HREventObserver` has only `onEvent()`. No fat interfaces. |
| **D**ependency Inversion | `HRService` depends on `RequestProcessingStrategy` (interface), not concrete processors. `HRService` receives observers via constructor (DI). |

---

## Code Implementation

### Enums

```java
package com.hrapp.model;

/** Request types - extensible via Strategy pattern */
public enum RequestType {
    LEAVE,
    EXPENSE_CLAIM,
    GRIEVANCE
}

/** Request lifecycle - State pattern */
public enum RequestStatus {
    SUBMITTED,      // Initial state
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    CANCELLED
}

/** Leave-specific types */
public enum LeaveType {
    SICK,
    VACATION,
    UNPAID,
    MATERNITY
}

/** Approval decision at each chain level */
public enum ApprovalStatus {
    APPROVED,
    REJECTED
}
```

### Models (with encapsulation and state machine)

```java
package com.hrapp.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Request model with encapsulated state machine.
 * SOLID: Single Responsibility - manages only request state and transitions.
 * State pattern: valid transitions enforced in setStatus().
 */
public class Request {
    private final UUID requestId;
    private final String requestNumber;
    private final UUID employeeId;
    private final RequestType requestType;
    private RequestStatus status;
    private UUID currentApproverId;
    private final String requestDataJson;
    private final Instant submittedAt;
    private Instant completedAt;

    public Request(UUID requestId, String requestNumber, UUID employeeId,
                   RequestType requestType, String requestDataJson) {
        this.requestId = requestId;
        this.requestNumber = requestNumber;
        this.employeeId = employeeId;
        this.requestType = requestType;
        this.requestDataJson = requestDataJson;
        this.status = RequestStatus.SUBMITTED;
        this.submittedAt = Instant.now();
    }

    /** State machine: only valid transitions allowed */
    public void setStatus(RequestStatus newStatus) {
        switch (this.status) {
            case SUBMITTED:
                if (newStatus != RequestStatus.PENDING_APPROVAL && newStatus != RequestStatus.CANCELLED)
                    throw new IllegalStateException("SUBMITTED can only go to PENDING_APPROVAL or CANCELLED");
                break;
            case PENDING_APPROVAL:
                if (newStatus != RequestStatus.APPROVED && newStatus != RequestStatus.REJECTED)
                    throw new IllegalStateException("PENDING_APPROVAL can only go to APPROVED or REJECTED");
                break;
            case APPROVED:
            case REJECTED:
            case CANCELLED:
                throw new IllegalStateException("Terminal state " + this.status + " cannot transition");
            default:
                throw new IllegalStateException("Unknown status: " + this.status);
        }
        this.status = newStatus;
        if (newStatus == RequestStatus.APPROVED || newStatus == RequestStatus.REJECTED) {
            this.completedAt = Instant.now();
        }
    }

    // Getters
    public UUID getRequestId() { return requestId; }
    public String getRequestNumber() { return requestNumber; }
    public UUID getEmployeeId() { return employeeId; }
    public RequestType getRequestType() { return requestType; }
    public RequestStatus getStatus() { return status; }
    public UUID getCurrentApproverId() { return currentApproverId; }
    public String getRequestDataJson() { return requestDataJson; }
    public Instant getSubmittedAt() { return submittedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setCurrentApproverId(UUID currentApproverId) {
        this.currentApproverId = currentApproverId;
    }
}
```

### Strategy: RequestProcessingStrategy

```java
package com.hrapp.strategy;

import com.hrapp.model.Request;

/**
 * Strategy pattern: Different request types have different validation and processing.
 * SOLID: Open/Closed - add new types by implementing this interface, no changes to HRService.
 */
public interface RequestProcessingStrategy {
    /** Validate request data before submission */
    void validate(Request request);
    /** Process (e.g., create leave record, deduct balance) after approval */
    void process(Request request);
}
```

```java
package com.hrapp.strategy.impl;

import com.hrapp.model.Request;
import com.hrapp.model.RequestType;
import com.hrapp.strategy.RequestProcessingStrategy;

/**
 * Strategy impl for leave requests.
 * Validates date range, leave type; processes by creating leave_requests record.
 */
public class LeaveRequestProcessor implements RequestProcessingStrategy {
    @Override
    public void validate(Request request) {
        if (request.getRequestType() != RequestType.LEAVE)
            throw new IllegalArgumentException("LeaveRequestProcessor handles only LEAVE");
        // Parse requestDataJson, validate start_date <= end_date, leave_type valid
        // In real impl: JsonNode data = objectMapper.readTree(request.getRequestDataJson());
    }

    @Override
    public void process(Request request) {
        // Create leave_requests row, deduct leave balance, etc.
    }
}
```

```java
package com.hrapp.strategy.impl;

import com.hrapp.model.Request;
import com.hrapp.model.RequestType;
import com.hrapp.strategy.RequestProcessingStrategy;

/** Strategy impl for expense claims */
public class ExpenseClaimProcessor implements RequestProcessingStrategy {
    @Override
    public void validate(Request request) {
        if (request.getRequestType() != RequestType.EXPENSE_CLAIM)
            throw new IllegalArgumentException("ExpenseClaimProcessor handles only EXPENSE_CLAIM");
        // Validate amount, receipts, policy limits
    }

    @Override
    public void process(Request request) {
        // Create expense record, route to finance, etc.
    }
}
```

```java
package com.hrapp.strategy.impl;

import com.hrapp.model.Request;
import com.hrapp.model.RequestType;
import com.hrapp.strategy.RequestProcessingStrategy;

/** Strategy impl for grievances */
public class GrievanceProcessor implements RequestProcessingStrategy {
    @Override
    public void validate(Request request) {
        if (request.getRequestType() != RequestType.GRIEVANCE)
            throw new IllegalArgumentException("GrievanceProcessor handles only GRIEVANCE");
        // Validate description, category
    }

    @Override
    public void process(Request request) {
        // Create grievance case, notify HR, etc.
    }
}
```

### Chain of Responsibility: ApprovalChain

```java
package com.hrapp.chain;

import com.hrapp.model.ApprovalStatus;
import com.hrapp.model.Request;

/**
 * Chain of Responsibility: Each handler can approve, reject, or pass to next.
 */
public abstract class ApprovalHandler {
    protected ApprovalHandler next;

    public void setNext(ApprovalHandler next) {
        this.next = next;
    }

    /** Process approval; return true if handled, false to pass to next */
    public abstract boolean handle(Request request, ApprovalStatus decision, String comment);
}
```

```java
package com.hrapp.chain.impl;

import com.hrapp.chain.ApprovalHandler;
import com.hrapp.model.ApprovalStatus;
import com.hrapp.model.Request;
import com.hrapp.model.RequestStatus;

/** First level: Manager approval */
public class ManagerApprovalHandler extends ApprovalHandler {
    @Override
    public boolean handle(Request request, ApprovalStatus decision, String comment) {
        // If this handler is responsible (e.g., request needs manager approval)
        if (shouldHandle(request)) {
            applyDecision(request, decision, comment);
            if (decision == ApprovalStatus.APPROVED && next != null)
                next.handle(request, decision, comment); // Pass to HR
            return true;
        }
        return next != null && next.handle(request, decision, comment);
    }

    private boolean shouldHandle(Request request) {
        return request.getStatus().name().equals("PENDING_APPROVAL") && request.getCurrentApproverId() != null;
    }

    private void applyDecision(Request request, ApprovalStatus decision, String comment) {
        request.setStatus(decision == ApprovalStatus.APPROVED ? RequestStatus.APPROVED : RequestStatus.REJECTED);
        // Persist approval record
    }
}
```

```java
package com.hrapp.chain.impl;

import com.hrapp.chain.ApprovalHandler;
import com.hrapp.model.ApprovalStatus;
import com.hrapp.model.Request;

/** Second level: HR approval (for certain request types or amounts) */
public class HRApprovalHandler extends ApprovalHandler {
    @Override
    public boolean handle(Request request, ApprovalStatus decision, String comment) {
        if (shouldHandle(request)) {
            applyDecision(request, decision, comment);
            if (decision == ApprovalStatus.APPROVED && next != null)
                next.handle(request, decision, comment);
            return true;
        }
        return next != null && next.handle(request, decision, comment);
    }
    // ... similar structure
}
```

```java
package com.hrapp.chain.impl;

import com.hrapp.chain.ApprovalHandler;
import com.hrapp.model.ApprovalStatus;
import com.hrapp.model.Request;

/** Third level: Director approval (high-value or sensitive) */
public class DirectorApprovalHandler extends ApprovalHandler {
    @Override
    public boolean handle(Request request, ApprovalStatus decision, String comment) {
        if (shouldHandle(request)) {
            applyDecision(request, decision, comment);
            return true;
        }
        return false;
    }
}
```

### Observer: HREventObserver

```java
package com.hrapp.observer;

import com.hrapp.model.Request;

/**
 * Observer pattern: Notify multiple listeners on HR events.
 * SOLID: Open/Closed - add new observers without changing event source.
 */
public interface HREventObserver {
    void onRequestSubmitted(Request request);
    void onRequestApproved(Request request);
    void onRequestRejected(Request request);
}
```

```java
package com.hrapp.observer.impl;

import com.hrapp.model.Request;
import com.hrapp.observer.HREventObserver;

/** Sends email notifications */
public class EmailNotificationObserver implements HREventObserver {
    @Override
    public void onRequestSubmitted(Request request) {
        // Email to manager: "You have a new request to approve"
    }

    @Override
    public void onRequestApproved(Request request) {
        // Email to employee: "Your request HR-2024-001234 has been approved"
    }

    @Override
    public void onRequestRejected(Request request) {
        // Email to employee: "Your request has been rejected"
    }
}
```

```java
package com.hrapp.observer.impl;

import com.hrapp.model.Request;
import com.hrapp.observer.HREventObserver;

/** Logs for compliance (retention, audit) */
public class ComplianceLogObserver implements HREventObserver {
    @Override
    public void onRequestSubmitted(Request request) {
        // Log to compliance store
    }

    @Override
    public void onRequestApproved(Request request) {
        // Log approval for regulatory compliance
    }

    @Override
    public void onRequestRejected(Request request) {
        // Log rejection
    }
}
```

```java
package com.hrapp.observer.impl;

import com.hrapp.model.Request;
import com.hrapp.observer.HREventObserver;

/** Audit trail for security */
public class AuditLogObserver implements HREventObserver {
    @Override
    public void onRequestSubmitted(Request request) {
        // Append to audit log: who, when, what
    }

    @Override
    public void onRequestApproved(Request request) {
        // Append approval to audit log
    }

    @Override
    public void onRequestRejected(Request request) {
        // Append rejection to audit log
    }
}
```

### Factory: RequestProcessorFactory

```java
package com.hrapp.factory;

import com.hrapp.model.RequestType;
import com.hrapp.strategy.RequestProcessingStrategy;
import com.hrapp.strategy.impl.ExpenseClaimProcessor;
import com.hrapp.strategy.impl.GrievanceProcessor;
import com.hrapp.strategy.impl.LeaveRequestProcessor;

/**
 * Factory pattern: Returns correct Strategy based on RequestType.
 * SOLID: Dependency Inversion - callers depend on interface, not concrete classes.
 */
public class RequestProcessorFactory {
    public RequestProcessingStrategy getProcessor(RequestType type) {
        return switch (type) {
            case LEAVE -> new LeaveRequestProcessor();
            case EXPENSE_CLAIM -> new ExpenseClaimProcessor();
            case GRIEVANCE -> new GrievanceProcessor();
            default -> throw new IllegalArgumentException("Unknown request type: " + type);
        };
    }
}
```

### HRService (Orchestrator with DI)

```java
package com.hrapp.service;

import com.hrapp.factory.RequestProcessorFactory;
import com.hrapp.model.Request;
import com.hrapp.model.RequestStatus;
import com.hrapp.observer.HREventObserver;
import com.hrapp.chain.ApprovalHandler;
import com.hrapp.strategy.RequestProcessingStrategy;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrator: Coordinates Strategy, Chain, Observer.
 * SOLID: Dependency Inversion - receives dependencies via constructor (DI).
 * Single Responsibility: Orchestrates workflow; does not implement business logic.
 */
public class HRService {
    private final RequestProcessorFactory processorFactory;
    private final ApprovalHandler approvalChain;
    private final List<HREventObserver> observers;

    public HRService(RequestProcessorFactory processorFactory,
                     ApprovalHandler approvalChain,
                     List<HREventObserver> observers) {
        this.processorFactory = processorFactory;
        this.approvalChain = approvalChain;
        this.observers = observers;
    }

    /** Submit request: validate via Strategy, set status, notify observers */
    public Request submitRequest(Request request) {
        RequestProcessingStrategy processor = processorFactory.getProcessor(request.getRequestType());
        processor.validate(request);
        request.setStatus(RequestStatus.PENDING_APPROVAL);
        // Determine first approver (manager), set currentApproverId
        observers.forEach(o -> o.onRequestSubmitted(request));
        return request;
    }

    /** Approve/reject: Chain processes, then Strategy.process if approved, notify observers */
    public void processApproval(Request request, com.hrapp.model.ApprovalStatus decision, String comment) {
        boolean handled = approvalChain.handle(request, decision, comment);
        if (!handled) throw new IllegalStateException("No handler could process request");
        if (decision == com.hrapp.model.ApprovalStatus.APPROVED) {
            RequestProcessingStrategy processor = processorFactory.getProcessor(request.getRequestType());
            processor.process(request);
            observers.forEach(o -> o.onRequestApproved(request));
        } else {
            observers.forEach(o -> o.onRequestRejected(request));
        }
    }
}
```

---

## Edge Cases & Tests

| # | Edge Case | Test / Handling |
|---|-----------|-----------------|
| 1 | **Invalid state transition** | Assert `setStatus(APPROVED)` on `SUBMITTED` request throws `IllegalStateException`. State machine enforces valid transitions only. |
| 2 | **Wrong processor for request type** | `LeaveRequestProcessor.validate()` with `EXPENSE_CLAIM` request throws `IllegalArgumentException`. Strategy is type-specific. |
| 3 | **Empty approval chain** | If `next == null` and handler doesn't process, chain returns false. HRService should throw or handle "no handler" case. |
| 4 | **Leave dates: end before start** | `LeaveRequestProcessor.validate()` rejects `end_date < start_date`. DB CHECK constraint also enforces. |
| 5 | **Duplicate announcement read** | `UNIQUE(announcement_id, employee_id)` prevents duplicate reads. Upsert or ignore on conflict. |
| 6 | **Approval by non-approver** | Verify `currentApproverId` matches approver before applying. Chain handler checks `request.getCurrentApproverId() == approverId`. |
| 7 | **Observer throws exception** | One observer failure should not block others. Use try-catch per observer or async dispatch. |
| 8 | **Manager is submitter** | Leave request by employee whose manager is self (e.g., CEO). Approval chain should escalate to next level or skip. |

---

## Summary

| Aspect | Detail |
|--------|--------|
| **Core Problem** | Employee-HR communication with extensible requests, approvals, and announcements |
| **Key Patterns** | Strategy (request types), Observer (notifications), State (lifecycle), Factory (processor creation), Chain of Responsibility (approvals) |
| **SOLID** | SRP per component, OCP for new types/observers, LSP for strategies/handlers, ISP for small interfaces, DIP via DI |
| **DB Tables** | employees, departments, hr_requests, request_approvals, announcements, announcement_reads, leave_requests |
| **Extensibility** | Add new request type → new Strategy + Factory entry. Add observer → implement interface + register. Add approval level → new Handler in chain. |
