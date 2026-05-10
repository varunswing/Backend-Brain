# Case Management System - LLD / Machine Coding Interview Study

---

## 1. Problem Statement

Design a case management system for customer support or internal ticketing that tracks cases through their lifecycle, assigns cases to agents, supports notes and document attachments, and maintains a full audit trail. The system must enforce valid status transitions, support pluggable assignment strategies, and notify stakeholders on case events.

---

## 2. Requirements

### Functional Requirements
- Create, read, update cases with title, description, priority, type, category
- Assign cases to agents using configurable strategies (round-robin, load-balanced, expertise-based)
- Add notes and attach documents to cases
- Transition case status with validation (e.g., cannot reopen a closed case)
- Track case assignments and reassignments
- Maintain audit log for all case operations
- Notify stakeholders on case events (assignment, status change, escalation)

### Non-Functional Requirements
- Extensible assignment strategies without changing core logic
- Immutable audit trail for compliance
- Clear separation of concerns and testability
- Support for SLA tracking and reporting

---

## 3. Database Design with Explanations

```sql
-- =============================================================================
-- users
-- WHY exists: Core entity for agents, reporters, and assignees.
-- WHY FKs: None - root entity.
-- WHY indexes: Lookup by email (login), by role (filtering agents).
-- WHY structure: Separate role for RBAC; active flag for soft-disable.
-- =============================================================================
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE NOT NULL,
    name            VARCHAR(255) NOT NULL,
    role            VARCHAR(50) NOT NULL,  -- AGENT, REPORTER, ADMIN
    is_active       BOOLEAN DEFAULT true,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role ON users(role) WHERE is_active = true;

-- =============================================================================
-- case_categories
-- WHY exists: Categorize cases for routing, reporting, and expertise matching.
-- WHY FKs: None - lookup/reference table.
-- WHY indexes: Lookup by slug for API; by name for UI.
-- WHY structure: Slug for URLs; description for tooltips.
-- =============================================================================
CREATE TABLE case_categories (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    slug            VARCHAR(100) UNIQUE NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_case_categories_slug ON case_categories(slug);

-- =============================================================================
-- cases
-- WHY exists: Central entity for support tickets/cases.
-- WHY FKs: reporter_id (who opened), assignee_id (who owns), category_id (routing).
-- WHY indexes: project_id+status (board views), assignee (agent workload), created_at (recent).
-- WHY structure: status/resolution for workflow; priority/type for triage.
-- =============================================================================
CREATE TABLE cases (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_number     VARCHAR(50) UNIQUE NOT NULL,  -- e.g. CASE-001234
    title           VARCHAR(500) NOT NULL,
    description     TEXT,
    status          VARCHAR(50) NOT NULL,
    priority        VARCHAR(50) NOT NULL,
    case_type       VARCHAR(50) NOT NULL,
    category_id     UUID REFERENCES case_categories(id),
    reporter_id     UUID NOT NULL REFERENCES users(id),
    assignee_id     UUID REFERENCES users(id),
    resolution      VARCHAR(100),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    resolved_at     TIMESTAMP,
    closed_at       TIMESTAMP
);
CREATE INDEX idx_cases_status ON cases(status);
CREATE INDEX idx_cases_assignee ON cases(assignee_id) WHERE assignee_id IS NOT NULL;
CREATE INDEX idx_cases_created ON cases(created_at DESC);
CREATE INDEX idx_cases_number ON cases(case_number);
CREATE INDEX idx_cases_category ON cases(category_id);

-- =============================================================================
-- case_notes
-- WHY exists: Internal/external comments and activity log on a case.
-- WHY FKs: case_id (which case), author_id (who wrote).
-- WHY indexes: case_id + created_at for chronological display.
-- WHY structure: is_internal for agent-only notes; supports rich text.
-- =============================================================================
CREATE TABLE case_notes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id         UUID NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    author_id       UUID NOT NULL REFERENCES users(id),
    content         TEXT NOT NULL,
    is_internal     BOOLEAN DEFAULT false,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_case_notes_case_time ON case_notes(case_id, created_at DESC);

-- =============================================================================
-- case_documents
-- WHY exists: Attach files (logs, screenshots, contracts) to cases.
-- WHY FKs: case_id (which case), uploaded_by (audit).
-- WHY indexes: case_id for listing attachments per case.
-- WHY structure: file_path for storage; mime_type for preview/validation.
-- =============================================================================
CREATE TABLE case_documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id         UUID NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    file_name       VARCHAR(500) NOT NULL,
    file_path       VARCHAR(1000) NOT NULL,
    mime_type       VARCHAR(100),
    file_size_bytes BIGINT,
    uploaded_by     UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_case_documents_case ON case_documents(case_id);

-- =============================================================================
-- case_assignments
-- WHY exists: History of who was assigned when; supports workload analytics.
-- WHY FKs: case_id, assignee_id, assigned_by (audit trail).
-- WHY indexes: case_id for case history; assignee_id for agent workload.
-- WHY structure: assigned_at/ended_at for time-in-role; reason for context.
-- =============================================================================
CREATE TABLE case_assignments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id         UUID NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    assignee_id     UUID NOT NULL REFERENCES users(id),
    assigned_by     UUID NOT NULL REFERENCES users(id),
    assigned_at     TIMESTAMP DEFAULT NOW(),
    ended_at        TIMESTAMP,
    reason          VARCHAR(255)
);
CREATE INDEX idx_case_assignments_case ON case_assignments(case_id);
CREATE INDEX idx_case_assignments_assignee ON case_assignments(assignee_id, ended_at);

-- =============================================================================
-- case_audit_log
-- WHY exists: Immutable compliance trail; who did what, when.
-- WHY FKs: case_id, user_id (actor).
-- WHY indexes: case_id for case history; created_at for time-range queries.
-- WHY structure: action + old_value/new_value JSON for diff; entity_type for future extensibility.
-- =============================================================================
CREATE TABLE case_audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id         UUID NOT NULL REFERENCES cases(id) ON DELETE CASCADE,
    user_id         UUID REFERENCES users(id),
    action          VARCHAR(100) NOT NULL,
    entity_type     VARCHAR(50) NOT NULL,
    old_value       JSONB,
    new_value       JSONB,
    created_at      TIMESTAMP DEFAULT NOW()
);
CREATE INDEX idx_case_audit_log_case ON case_audit_log(case_id);
CREATE INDEX idx_case_audit_log_created ON case_audit_log(created_at DESC);
```

---

## 4. Design Patterns

| Pattern   | Where                                      | Why                                                                 |
|-----------|---------------------------------------------|---------------------------------------------------------------------|
| **State** | `CaseStatus`, `CaseStateMachine`            | Enforces valid status transitions; prevents invalid moves (e.g. CLOSED→OPEN). |
| **Strategy** | `CaseAssignmentStrategy`, implementations | Pluggable assignment logic (RoundRobin, LoadBalanced, ExpertiseBased) without changing CaseService. |
| **Observer** | `CaseEventObserver`, concrete observers | Decouple notifications, SLA tracking, audit logging from core case logic. |
| **Factory** | `CaseFactory` or enum factories           | Centralizes case creation with defaults; encapsulates validation.   |
| **Command** | `CaseCommand`, `CreateCaseCommand`, etc.  | Encapsulates operations for undo/redo, audit trail, and async execution. |

---

## 5. SOLID Principles

| Principle | How |
|-----------|-----|
| **S**ingle Responsibility | Each class has one job: `CaseService` orchestrates; `CaseStateMachine` validates transitions; observers handle side effects. |
| **O**pen/Closed | New assignment strategies added by implementing `CaseAssignmentStrategy`; new observers by implementing `CaseEventObserver`—no core changes. |
| **L**iskov Substitution | All `CaseAssignmentStrategy` implementations are interchangeable; all observers can be swapped. |
| **I**nterface Segregation | Small interfaces: `CaseAssignmentStrategy`, `CaseEventObserver`, `CaseCommand`—clients depend only on what they need. |
| **D**ependency Inversion | `CaseService` depends on `CaseAssignmentStrategy` and `CaseEventObserver` abstractions, not concrete implementations. |

---

## 6. Code Implementation in Java

*Imports used: `java.util.*`, `java.time.Instant`, `java.util.UUID`, `org.junit.jupiter.api.*`*

### Enums

```java
/**
 * Case lifecycle status. State pattern: transitions validated by CaseStateMachine.
 * WHY: Centralizes allowed states; prevents invalid transitions (e.g. CLOSED -> OPEN).
 */
public enum CaseStatus {
    NEW,        // Just created
    OPEN,       // Assigned, in progress
    IN_REVIEW,  // Pending review/approval
    ESCALATED,  // Escalated to senior/specialist
    RESOLVED,   // Resolution applied
    CLOSED      // Final state, no further changes
}

/**
 * Priority for triage and SLA. WHY: Drives assignment order and SLA targets.
 */
public enum CasePriority {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * Type of case for routing and reporting. WHY: Different handling for bug vs feature vs inquiry.
 */
public enum CaseType {
    BUG, FEATURE_REQUEST, INQUIRY, COMPLAINT, INCIDENT
}
```

### Value Objects (Immutable)

```java
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable value object for case identity.
 * WHY: Encapsulates ID + case number; prevents primitive obsession; supports validation.
 */
public record CaseId(UUID id, String caseNumber) {
    public CaseId {
        if (id == null || caseNumber == null || caseNumber.isBlank())
            throw new IllegalArgumentException("Invalid CaseId");
    }
}

/**
 * Immutable snapshot of case state at a point in time.
 * WHY: Value object for audit, events; avoids mutable shared state.
 */
public record CaseSnapshot(
    CaseId id,
    String title,
    String description,
    CaseStatus status,
    CasePriority priority,
    CaseType caseType,
    UUID categoryId,
    UUID reporterId,
    UUID assigneeId,
    Instant createdAt,
    Instant updatedAt
) {}
```

### State Machine (State Pattern)

```java
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * State machine for case lifecycle. State pattern: defines valid transitions.
 * WHY: Centralizes transition rules; CaseService delegates validation here.
 * SOLID: Single responsibility - only transition validation.
 */
public class CaseStateMachine {

    private static final Map<CaseStatus, Set<CaseStatus>> VALID_TRANSITIONS = Map.of(
        CaseStatus.NEW,      EnumSet.of(CaseStatus.OPEN, CaseStatus.CLOSED),
        CaseStatus.OPEN,     EnumSet.of(CaseStatus.IN_REVIEW, CaseStatus.ESCALATED, CaseStatus.RESOLVED, CaseStatus.CLOSED),
        CaseStatus.IN_REVIEW, EnumSet.of(CaseStatus.OPEN, CaseStatus.RESOLVED, CaseStatus.ESCALATED),
        CaseStatus.ESCALATED, EnumSet.of(CaseStatus.OPEN, CaseStatus.IN_REVIEW, CaseStatus.RESOLVED, CaseStatus.CLOSED),
        CaseStatus.RESOLVED, EnumSet.of(CaseStatus.CLOSED, CaseStatus.OPEN),
        CaseStatus.CLOSED,   EnumSet.noneOf(CaseStatus.class)  // Terminal state
    );

    /**
     * Validates transition. WHY: Prevents invalid moves (e.g. CLOSED -> OPEN).
     */
    public boolean canTransition(CaseStatus from, CaseStatus to) {
        if (from == null || to == null) return false;
        return VALID_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public void validateTransition(CaseStatus from, CaseStatus to) {
        if (!canTransition(from, to))
            throw new IllegalStateException("Invalid transition: " + from + " -> " + to);
    }
}
```

### Strategy: Case Assignment

```java
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Strategy pattern: pluggable case assignment logic.
 * WHY: Open/Closed - add new strategies without modifying CaseService.
 * SOLID: Dependency Inversion - CaseService depends on abstraction.
 */
public interface CaseAssignmentStrategy {
    Optional<UUID> selectAssignee(UUID caseId, UUID categoryId, CasePriority priority, List<UUID> agentIds);
}

/**
 * Round-robin: cycles through agents. WHY: Simple fair distribution.
 */
public class RoundRobinAssignmentStrategy implements CaseAssignmentStrategy {
    private int index = 0;

    @Override
    public Optional<UUID> selectAssignee(UUID caseId, UUID categoryId, CasePriority priority, List<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return Optional.empty();
        UUID selected = agentIds.get(index % agentIds.size());
        index++;
        return Optional.of(selected);
    }
}

/**
 * Load-balanced: assigns to agent with fewest open cases. WHY: Distributes workload.
 */
public class LoadBalancedAssignmentStrategy implements CaseAssignmentStrategy {

    private final CaseRepository caseRepository;

    public LoadBalancedAssignmentStrategy(CaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Override
    public Optional<UUID> selectAssignee(UUID caseId, UUID categoryId, CasePriority priority, List<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty()) return Optional.empty();
        return caseRepository.countOpenCasesByAssignee(agentIds).entrySet().stream()
            .min(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }
}

/**
 * Expertise-based: assigns to agent with most cases in same category. WHY: Specialization.
 */
public class ExpertiseBasedAssignmentStrategy implements CaseAssignmentStrategy {

    private final CaseRepository caseRepository;

    public ExpertiseBasedAssignmentStrategy(CaseRepository caseRepository) {
        this.caseRepository = caseRepository;
    }

    @Override
    public Optional<UUID> selectAssignee(UUID caseId, UUID categoryId, CasePriority priority, List<UUID> agentIds) {
        if (agentIds == null || agentIds.isEmpty() || categoryId == null) return Optional.empty();
        return caseRepository.countCasesByCategoryAndAssignee(categoryId, agentIds).entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }
}
```

### Observer: Case Events

```java
import java.util.UUID;

/**
 * Observer pattern: decouples side effects (notifications, audit, SLA) from core logic.
 * WHY: Open/Closed - add new observers without changing CaseService.
 * SOLID: Single responsibility - each observer does one thing.
 */
public interface CaseEventObserver {
    void onCaseCreated(CaseSnapshot snapshot);
    void onCaseAssigned(CaseSnapshot snapshot, UUID previousAssigneeId, UUID newAssigneeId);
    void onCaseStatusChanged(CaseSnapshot snapshot, CaseStatus previousStatus, CaseStatus newStatus);
}

/**
 * Sends notifications (email, in-app). WHY: Keeps notification logic out of CaseService.
 */
public class NotificationObserver implements CaseEventObserver {
    @Override
    public void onCaseCreated(CaseSnapshot snapshot) {
        // Send "Case created" notification to assignee/reporter
    }

    @Override
    public void onCaseAssigned(CaseSnapshot snapshot, UUID previousAssigneeId, UUID newAssigneeId) {
        // Notify new assignee
    }

    @Override
    public void onCaseStatusChanged(CaseSnapshot snapshot, CaseStatus previousStatus, CaseStatus newStatus) {
        // Notify stakeholders on status change
    }
}

/**
 * Tracks SLA deadlines. WHY: Single responsibility for SLA logic.
 */
public class SlaTrackingObserver implements CaseEventObserver {
    @Override
    public void onCaseCreated(CaseSnapshot snapshot) {
        // Schedule SLA check based on priority
    }

    @Override
    public void onCaseAssigned(CaseSnapshot snapshot, UUID previousAssigneeId, UUID newAssigneeId) {
        // Reset or adjust SLA timer
    }

    @Override
    public void onCaseStatusChanged(CaseSnapshot snapshot, CaseStatus previousStatus, CaseStatus newStatus) {
        // Update SLA state on resolution/closure
    }
}

/**
 * Writes to audit log. WHY: Immutable audit trail; compliance.
 */
public class AuditLoggingObserver implements CaseEventObserver {
    private final CaseAuditRepository auditRepository;

    public AuditLoggingObserver(CaseAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Override
    public void onCaseCreated(CaseSnapshot snapshot) {
        auditRepository.log(snapshot.id().id(), snapshot.reporterId(), "CREATE", "case", null, toJson(snapshot));
    }

    @Override
    public void onCaseAssigned(CaseSnapshot snapshot, UUID previousAssigneeId, UUID newAssigneeId) {
        auditRepository.log(snapshot.id().id(), null, "ASSIGN", "case",
            Map.of("assignee", previousAssigneeId), Map.of("assignee", newAssigneeId));
    }

    @Override
    public void onCaseStatusChanged(CaseSnapshot snapshot, CaseStatus previousStatus, CaseStatus newStatus) {
        auditRepository.log(snapshot.id().id(), snapshot.reporterId(), "STATUS_CHANGE", "case",
            Map.of("status", previousStatus.name()), Map.of("status", newStatus.name()));
    }

    private Map<String, Object> toJson(CaseSnapshot s) {
        return Map.of("title", s.title(), "status", s.status().name(), "priority", s.priority().name());
    }
}
```

### Command Pattern (for Audit Trail)

```java
import java.time.Instant;
import java.util.UUID;

/**
 * Command pattern: encapsulates case operations for audit, undo, async execution.
 * WHY: Each command is a first-class object; can be logged, replayed, queued.
 * SOLID: Single responsibility - one command per operation.
 */
public interface CaseCommand {
    CaseSnapshot execute(CaseServiceContext ctx);
    String getAction();
}

/**
 * Create case command. WHY: Audit trail shows "CREATE" with full payload.
 */
public class CreateCaseCommand implements CaseCommand {
    private final String title;
    private final String description;
    private final CasePriority priority;
    private final CaseType caseType;
    private final UUID categoryId;
    private final UUID reporterId;

    public CreateCaseCommand(String title, String description, CasePriority priority,
                             CaseType caseType, UUID categoryId, UUID reporterId) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.caseType = caseType;
        this.categoryId = categoryId;
        this.reporterId = reporterId;
    }

    @Override
    public CaseSnapshot execute(CaseServiceContext ctx) {
        return ctx.getCaseService().createCase(title, description, priority, caseType, categoryId, reporterId);
    }

    @Override
    public String getAction() { return "CREATE"; }
}

/**
 * Change status command. WHY: Audit trail shows old -> new status.
 */
public class ChangeStatusCommand implements CaseCommand {
    private final UUID caseId;
    private final CaseStatus newStatus;
    private final UUID userId;

    public ChangeStatusCommand(UUID caseId, CaseStatus newStatus, UUID userId) {
        this.caseId = caseId;
        this.newStatus = newStatus;
        this.userId = userId;
    }

    @Override
    public CaseSnapshot execute(CaseServiceContext ctx) {
        CaseSnapshot snapshot = ctx.getCaseService().transitionStatus(caseId, newStatus, userId);
        return snapshot;
    }

    @Override
    public String getAction() { return "STATUS_CHANGE"; }
}

/**
 * Context passed to commands. WHY: Provides dependencies without command knowing constructors.
 */
public interface CaseServiceContext {
    CaseService getCaseService();
    CaseAuditRepository getAuditRepository();
}
```

### CaseService (Orchestrator)

```java
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrator for case operations. Constructor injection for testability.
 * WHY: Single entry point; delegates to state machine, strategy, observers.
 * SOLID: Dependency Inversion - depends on abstractions; Single Responsibility - orchestration only.
 */
public class CaseService {

    private final CaseRepository caseRepository;
    private final CaseStateMachine stateMachine;
    private final CaseAssignmentStrategy assignmentStrategy;
    private final List<CaseEventObserver> observers;
    private final CaseNumberGenerator numberGenerator;

    public CaseService(CaseRepository caseRepository,
                       CaseStateMachine stateMachine,
                       CaseAssignmentStrategy assignmentStrategy,
                       List<CaseEventObserver> observers,
                       CaseNumberGenerator numberGenerator) {
        this.caseRepository = caseRepository;
        this.stateMachine = stateMachine;
        this.assignmentStrategy = assignmentStrategy;
        this.observers = new ArrayList<>(observers);
        this.numberGenerator = numberGenerator;
    }

    /**
     * Creates case, assigns via strategy, notifies observers.
     */
    public CaseSnapshot createCase(String title, String description, CasePriority priority,
                                   CaseType caseType, UUID categoryId, UUID reporterId) {
        String caseNumber = numberGenerator.next();
        CaseId id = new CaseId(UUID.randomUUID(), caseNumber);
        CaseStatus status = CaseStatus.NEW;

        List<UUID> agentIds = caseRepository.findActiveAgentIds();
        Optional<UUID> assigneeOpt = assignmentStrategy.selectAssignee(id.id(), categoryId, priority, agentIds);
        UUID assigneeId = assigneeOpt.orElse(null);

        CaseSnapshot snapshot = new CaseSnapshot(id, title, description, status, priority, caseType,
            categoryId, reporterId, assigneeId, java.time.Instant.now(), java.time.Instant.now());

        caseRepository.save(snapshot);
        caseRepository.recordAssignment(id.id(), assigneeId, reporterId, "Initial assignment");

        for (CaseEventObserver o : observers) {
            o.onCaseCreated(snapshot);
        }
        return snapshot;
    }

    /**
     * Validates transition via state machine, updates, notifies observers.
     */
    public CaseSnapshot transitionStatus(UUID caseId, CaseStatus newStatus, UUID userId) {
        CaseSnapshot current = caseRepository.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        stateMachine.validateTransition(current.status(), newStatus);

        CaseSnapshot updated = new CaseSnapshot(
            current.id(), current.title(), current.description(), newStatus, current.priority(),
            current.caseType(), current.categoryId(), current.reporterId(), current.assigneeId(),
            current.createdAt(), java.time.Instant.now());

        caseRepository.save(updated);

        for (CaseEventObserver o : observers) {
            o.onCaseStatusChanged(updated, current.status(), newStatus);
        }
        return updated;
    }

    /**
     * Reassigns case, records assignment, notifies observers.
     */
    public CaseSnapshot reassignCase(UUID caseId, UUID newAssigneeId, UUID assignedBy) {
        CaseSnapshot current = caseRepository.findById(caseId)
            .orElseThrow(() -> new IllegalArgumentException("Case not found: " + caseId));

        UUID previousAssigneeId = current.assigneeId();

        CaseSnapshot updated = new CaseSnapshot(
            current.id(), current.title(), current.description(), current.status(), current.priority(),
            current.caseType(), current.categoryId(), current.reporterId(), newAssigneeId,
            current.createdAt(), java.time.Instant.now());

        caseRepository.save(updated);
        caseRepository.recordAssignment(caseId, newAssigneeId, assignedBy, "Reassignment");

        for (CaseEventObserver o : observers) {
            o.onCaseAssigned(updated, previousAssigneeId, newAssigneeId);
        }
        return updated;
    }
}
```

### Repository Interfaces (for DI)

```java
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * WHY: Abstraction for persistence; enables testing with in-memory impl.
 */
public interface CaseRepository {
    void save(CaseSnapshot snapshot);
    Optional<CaseSnapshot> findById(UUID caseId);
    List<UUID> findActiveAgentIds();
    Map<UUID, Long> countOpenCasesByAssignee(List<UUID> agentIds);
    Map<UUID, Long> countCasesByCategoryAndAssignee(UUID categoryId, List<UUID> agentIds);
    void recordAssignment(UUID caseId, UUID assigneeId, UUID assignedBy, String reason);
}

public interface CaseAuditRepository {
    void log(UUID caseId, UUID userId, String action, String entityType, Object oldValue, Object newValue);
}

public interface CaseNumberGenerator {
    String next();
}
```

---

## 7. Edge Cases & Tests

```java
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge cases and unit tests for Case Management System.
 */
class CaseManagementSystemTest {

    private CaseStateMachine stateMachine;
    private InMemoryCaseRepository caseRepository;
    private List<CaseEventObserver> observers;
    private CaseService caseService;

    @BeforeEach
    void setUp() {
        stateMachine = new CaseStateMachine();
        caseRepository = new InMemoryCaseRepository();
        observers = new ArrayList<>();
        CaseAssignmentStrategy strategy = new RoundRobinAssignmentStrategy();
        CaseNumberGenerator numberGen = () -> "CASE-" + System.currentTimeMillis();
        caseService = new CaseService(caseRepository, stateMachine, strategy, observers, numberGen);
    }

    @Test
    @DisplayName("State machine rejects CLOSED -> OPEN transition")
    void stateMachine_rejectsClosedToOpen() {
        assertFalse(stateMachine.canTransition(CaseStatus.CLOSED, CaseStatus.OPEN));
        assertThrows(IllegalStateException.class,
            () -> stateMachine.validateTransition(CaseStatus.CLOSED, CaseStatus.OPEN));
    }

    @Test
    @DisplayName("State machine allows NEW -> OPEN and NEW -> CLOSED")
    void stateMachine_allowsValidNewTransitions() {
        assertTrue(stateMachine.canTransition(CaseStatus.NEW, CaseStatus.OPEN));
        assertTrue(stateMachine.canTransition(CaseStatus.NEW, CaseStatus.CLOSED));
    }

    @Test
    @DisplayName("RoundRobin assigns to agents in rotation")
    void roundRobin_rotatesThroughAgents() {
        List<UUID> agents = List.of(UUID.randomUUID(), UUID.randomUUID());
        RoundRobinAssignmentStrategy strategy = new RoundRobinAssignmentStrategy();
        var a1 = strategy.selectAssignee(UUID.randomUUID(), null, CasePriority.MEDIUM, agents);
        var a2 = strategy.selectAssignee(UUID.randomUUID(), null, CasePriority.MEDIUM, agents);
        var a3 = strategy.selectAssignee(UUID.randomUUID(), null, CasePriority.MEDIUM, agents);
        assertTrue(a1.isPresent());
        assertTrue(a2.isPresent());
        assertTrue(a3.isPresent());
        assertEquals(a1.get(), a3.get());  // Wraps around
    }

    @Test
    @DisplayName("Strategy returns empty when no agents available")
    void strategy_returnsEmptyWhenNoAgents() {
        RoundRobinAssignmentStrategy strategy = new RoundRobinAssignmentStrategy();
        assertTrue(strategy.selectAssignee(UUID.randomUUID(), null, CasePriority.MEDIUM, List.of()).isEmpty());
        assertTrue(strategy.selectAssignee(UUID.randomUUID(), null, CasePriority.MEDIUM, null).isEmpty());
    }

    @Test
    @DisplayName("CaseId rejects null or blank case number")
    void caseId_rejectsInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> new CaseId(UUID.randomUUID(), null));
        assertThrows(IllegalArgumentException.class, () -> new CaseId(UUID.randomUUID(), ""));
        assertThrows(IllegalArgumentException.class, () -> new CaseId(null, "CASE-001"));
    }

    @Test
    @DisplayName("Observers are notified on case creation")
    void observers_notifiedOnCreate() {
        AtomicInteger createCount = new AtomicInteger(0);
        observers.add(new CaseEventObserver() {
            @Override
            public void onCaseCreated(CaseSnapshot s) { createCount.incrementAndGet(); }
            @Override
            public void onCaseAssigned(CaseSnapshot s, UUID prev, UUID next) {}
            @Override
            public void onCaseStatusChanged(CaseSnapshot s, CaseStatus prev, CaseStatus next) {}
        });
        caseRepository.setAgentIds(List.of(UUID.randomUUID()));
        caseService.createCase("Test", "Desc", CasePriority.MEDIUM, CaseType.BUG, null, UUID.randomUUID());
        assertEquals(1, createCount.get());
    }

    @Test
    @DisplayName("Transition status throws when case not found")
    void transitionStatus_throwsWhenCaseNotFound() {
        assertThrows(IllegalArgumentException.class,
            () -> caseService.transitionStatus(UUID.randomUUID(), CaseStatus.OPEN, UUID.randomUUID()));
    }

    @Test
    @DisplayName("LoadBalanced assigns to agent with fewest open cases")
    void loadBalanced_assignsToLeastLoaded() {
        UUID agent1 = UUID.randomUUID();
        UUID agent2 = UUID.randomUUID();
        caseRepository.setAgentIds(List.of(agent1, agent2));
        caseRepository.setOpenCounts(Map.of(agent1, 5L, agent2, 2L));
        LoadBalancedAssignmentStrategy strategy = new LoadBalancedAssignmentStrategy(caseRepository);
        var result = strategy.selectAssignee(UUID.randomUUID(), null, CasePriority.HIGH, List.of(agent1, agent2));
        assertTrue(result.isPresent());
        assertEquals(agent2, result.get());
    }
}

/** In-memory implementations for testing */
class InMemoryCaseRepository implements CaseRepository {
    private final Map<UUID, CaseSnapshot> store = new HashMap<>();
    private List<UUID> agentIds = List.of();
    private Map<UUID, Long> openCounts = new HashMap<>();

    void setAgentIds(List<UUID> ids) { agentIds = ids; }
    void setOpenCounts(Map<UUID, Long> counts) { openCounts = counts; }

    @Override
    public void save(CaseSnapshot s) { store.put(s.id().id(), s); }

    @Override
    public Optional<CaseSnapshot> findById(UUID id) { return Optional.ofNullable(store.get(id)); }

    @Override
    public List<UUID> findActiveAgentIds() { return agentIds; }

    @Override
    public Map<UUID, Long> countOpenCasesByAssignee(List<UUID> ids) {
        return ids.stream().collect(HashMap::new, (m, id) -> m.put(id, openCounts.getOrDefault(id, 0L)), HashMap::putAll);
    }

    @Override
    public Map<UUID, Long> countCasesByCategoryAndAssignee(UUID catId, List<UUID> ids) {
        return ids.stream().collect(HashMap::new, (m, id) -> m.put(id, 0L), HashMap::putAll);
    }

    @Override
    public void recordAssignment(UUID caseId, UUID assigneeId, UUID assignedBy, String reason) {}
}
```

---

## 8. Summary

| Aspect | Summary |
|--------|---------|
| **Problem** | Case management with lifecycle, assignment, notes, documents, and audit. |
| **Patterns** | State (transitions), Strategy (assignment), Observer (notifications/SLA/audit), Command (audit trail), Factory (creation). |
| **SOLID** | SRP per class; OCP via strategies/observers; LSP for implementations; ISP with small interfaces; DIP via constructor injection. |
| **DB** | `users`, `cases`, `case_notes`, `case_documents`, `case_assignments`, `case_audit_log`, `case_categories` with FKs and indexes for queries. |
| **Edge Cases** | Invalid transitions (CLOSED→OPEN), empty agent list, null/blank CaseId, observer notification, not-found case, load-balanced selection. |

---
