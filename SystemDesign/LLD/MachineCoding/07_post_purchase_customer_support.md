# Post-Purchase Customer Support System - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database Design with Explanations](#database-design-with-explanations)
4. [Design Patterns Used](#design-patterns-used)
5. [SOLID Principles Applied](#solid-principles-applied)
6. [Code Implementation in Java](#code-implementation-in-java)
7. [Edge Cases & Tests](#edge-cases--tests)
8. [Summary](#summary)

---

## Problem Statement

Design a post-purchase customer support system (like Zendesk/Intercom) that handles support ticket creation, intelligent routing to agents, ticket lifecycle management, and multi-channel messaging. The system must integrate with e-commerce orders and support canned responses for efficient resolution.

---

## Requirements

### Functional Requirements
- Create support tickets linked to users and orders
- Route tickets to agents (round-robin, skill-based, priority-based)
- Manage ticket lifecycle: OPEN → IN_PROGRESS → AWAITING_CUSTOMER → RESOLVED → CLOSED
- Add messages to tickets (customer, agent, system)
- Assign and reassign tickets to agents
- Use canned responses for common scenarios
- Notify stakeholders on ticket events (email, SLA monitoring, analytics)

### Non-Functional Requirements
- Extensible routing strategies without modifying core logic
- State machine enforcement for valid ticket transitions
- Loose coupling between ticket events and notification handlers
- Support multiple ticket creation sources (order-linked, standalone)

---

## Database Design with Explanations

```sql
-- ============================================================================
-- TABLE: users
-- WHY: Central identity table for both customers (who create tickets) and 
--      agents (who resolve them). A single table with role discrimination 
--      avoids schema duplication and simplifies auth. We need users because 
--      every ticket has a creator and every assignment has an assignee.
--
-- WHY role column? Customers create tickets; agents resolve them. Different 
--      permissions, different UIs. One table = one auth flow, one user lookup.
--
-- WHY NOT separate customers and agents tables?
--      Would duplicate email, name, created_at. Would need union queries for 
--      "find user by email". Single table is simpler for LLD scope.
-- ============================================================================
CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    first_name VARCHAR(100),
    last_name VARCHAR(100),
    role VARCHAR(20) NOT NULL,              -- CUSTOMER, AGENT
    skills TEXT[] DEFAULT '{}',             -- For agents: ['billing', 'technical']
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- WHY: Lookup by email for login; filter agents by role for assignment.
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_role_active ON users(role, is_active) WHERE role = 'AGENT';


-- ============================================================================
-- TABLE: orders
-- WHY: Post-purchase support is ORDER-CENTRIC. Customers say "I have an 
--      issue with order #12345". We need orders to: (1) link tickets to 
--      specific purchases, (2) show order context to agents, (3) validate 
--      that a ticket references a real order. Without orders, we lose 
--      critical context for refunds, shipping, product defects.
--
-- RELATIONSHIP: orders → users (Many-to-One)
--   WHY FK? Every order belongs to a customer (user_id). Enables "show all 
--   orders for this user" and "show all tickets for this order". Prevents 
--   orphan orders.
-- ============================================================================
CREATE TABLE orders (
    order_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id),
    order_number VARCHAR(50) UNIQUE NOT NULL,
    total_amount DECIMAL(12, 2) NOT NULL,
    order_status VARCHAR(30) NOT NULL,      -- PENDING, SHIPPED, DELIVERED, REFUNDED
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY: Most common query is "tickets for order X" or "orders for user Y".
CREATE INDEX idx_orders_user ON orders(user_id);
CREATE INDEX idx_orders_number ON orders(order_number);


-- ============================================================================
-- TABLE: support_tickets
-- WHY: The core entity. A ticket represents ONE support request. It has 
--      status, priority, category, and lifecycle. We need a dedicated table 
--      because tickets are the primary unit of work for agents and the 
--      central object for routing, SLA, and analytics.
--
-- RELATIONSHIP: support_tickets → users (Many-to-One, creator)
--   WHY FK? Every ticket is created by a user (customer). Required for 
--   "my tickets" and audit trail.
--
-- RELATIONSHIP: support_tickets → orders (Many-to-One, optional)
--   WHY FK optional? Some tickets are general (e.g., "How do I track?"). 
--   Others are order-specific ("Refund for order #123"). Optional FK allows 
--   both. When present, agents get full order context.
--
-- WHY status, priority, category as columns? They drive routing, SLA, and 
--   filtering. Indexed for "show open high-priority tickets" queries.
-- ============================================================================
CREATE TABLE support_tickets (
    ticket_id UUID PRIMARY KEY,
    ticket_number VARCHAR(30) UNIQUE NOT NULL,
    created_by UUID NOT NULL REFERENCES users(user_id),
    order_id UUID REFERENCES orders(order_id),
    subject VARCHAR(500) NOT NULL,
    description TEXT,
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    priority VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    category VARCHAR(50),
    sla_deadline TIMESTAMP,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    resolved_at TIMESTAMP,
    closed_at TIMESTAMP
);

-- WHY: Agent dashboard: "my open tickets", "queue by priority", SLA breach alerts.
CREATE INDEX idx_tickets_status_priority ON support_tickets(status, priority);
CREATE INDEX idx_tickets_creator ON support_tickets(created_by);
CREATE INDEX idx_tickets_order ON support_tickets(order_id) WHERE order_id IS NOT NULL;
CREATE INDEX idx_tickets_sla ON support_tickets(sla_deadline) WHERE status NOT IN ('RESOLVED', 'CLOSED');


-- ============================================================================
-- TABLE: ticket_messages
-- WHY: A ticket is a CONVERSATION. Each message is one exchange. We need 
--      messages to: (1) show full thread to agents, (2) track who said what 
--      and when, (3) support internal notes vs customer-visible content. 
--      Without messages, a ticket is just metadata—no actual support flow.
--
-- RELATIONSHIP: ticket_messages → support_tickets (Many-to-One)
--   WHY FK? Every message belongs to exactly one ticket. Cascade delete: 
--   when ticket is removed, messages go too. Enables "all messages for 
--   ticket X" ordered by created_at.
--
-- RELATIONSHIP: ticket_messages → users (Many-to-One, sender - optional)
--   WHY FK optional? System-generated messages (e.g., "Ticket assigned") 
--   have no human sender. Customer/agent messages have sender_id.
--
-- WHY sender_type? Distinguishes CUSTOMER, AGENT, SYSTEM for filtering and UI.
-- ============================================================================
CREATE TABLE ticket_messages (
    message_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support_tickets(ticket_id) ON DELETE CASCADE,
    sender_id UUID REFERENCES users(user_id),
    sender_type VARCHAR(20) NOT NULL,       -- CUSTOMER, AGENT, SYSTEM
    message_text TEXT NOT NULL,
    is_internal_note BOOLEAN DEFAULT false,
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY: Primary query is "load all messages for ticket X in order".
CREATE INDEX idx_messages_ticket_time ON ticket_messages(ticket_id, created_at);


-- ============================================================================
-- TABLE: ticket_assignments
-- WHY: Tracks WHO is responsible for a ticket and WHEN. A ticket can be 
--      reassigned (Agent A → Agent B). We need history for: (1) workload 
--      fairness, (2) escalation audit, (3) "who worked on this?" reporting. 
--      A separate table (vs single assigned_agent_id on ticket) preserves 
--      full assignment history.
--
-- RELATIONSHIP: ticket_assignments → support_tickets (Many-to-One)
--   WHY FK? Each assignment row is for one ticket. Enables "assignment 
--   history for ticket X".
--
-- RELATIONSHIP: ticket_assignments → users (Many-to-One, assignee)
--   WHY FK? Assignee must be a valid agent. Enables "all tickets assigned 
--   to agent Y".
--
-- RELATIONSHIP: ticket_assignments → users (Many-to-One, assigned_by)
--   WHY FK? Audit: who made the assignment (supervisor, system, self).
--
-- WHY unassigned_at? Marks when assignment ended (reassignment or resolution).
--   Current assignment = row where unassigned_at IS NULL.
-- ============================================================================
CREATE TABLE ticket_assignments (
    assignment_id UUID PRIMARY KEY,
    ticket_id UUID NOT NULL REFERENCES support_tickets(ticket_id) ON DELETE CASCADE,
    assignee_id UUID NOT NULL REFERENCES users(user_id),
    assigned_by UUID REFERENCES users(user_id),
    assigned_at TIMESTAMP DEFAULT NOW(),
    unassigned_at TIMESTAMP
);

-- WHY: "Current assignments for agent X", "assignment history for ticket Y".
CREATE INDEX idx_assignments_ticket ON ticket_assignments(ticket_id);
CREATE INDEX idx_assignments_assignee_active ON ticket_assignments(assignee_id, unassigned_at) 
    WHERE unassigned_at IS NULL;


-- ============================================================================
-- TABLE: canned_responses
-- WHY: Agents reuse common replies ("Thank you for contacting...", "Your 
--      refund has been processed"). Storing these in DB allows: (1) 
--      centralized editing without code deploy, (2) category/tag-based 
--      lookup, (3) analytics on which responses are used. Without this, 
--      agents copy-paste from docs or type manually—slow and inconsistent.
--
-- RELATIONSHIP: None. Canned responses are standalone templates.
--   WHY no FK? Responses are global, not tied to a specific ticket or user. 
--   They're looked up by category/tag when agent needs a suggestion.
--
-- WHY category? Enables "show billing responses" when ticket category is 
--   billing. Speeds up agent workflow.
-- ============================================================================
CREATE TABLE canned_responses (
    response_id UUID PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    category VARCHAR(50),
    tags TEXT[] DEFAULT '{}',
    use_count INTEGER DEFAULT 0,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

-- WHY: Agent searches by category or tag when inserting response.
CREATE INDEX idx_canned_category ON canned_responses(category) WHERE is_active = true;
```

---

## Design Patterns Used

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `TicketRoutingStrategy` with `RoundRobinRouting`, `SkillBasedRouting`, `PriorityBasedRouting` | Routing logic varies (round-robin vs skill vs priority). Strategy lets us swap algorithms without changing `TicketService`. Open/Closed: add new routing without modifying existing code. |
| **State** | `TicketStatus` transitions: OPEN → IN_PROGRESS → AWAITING_CUSTOMER → RESOLVED → CLOSED | Ticket lifecycle has strict rules (can't go CLOSED from OPEN). State pattern encapsulates valid transitions and prevents invalid state changes. |
| **Observer** | `TicketEventObserver` with `EmailNotificationObserver`, `SlaMonitoringObserver`, `AnalyticsObserver` | When ticket status changes, multiple systems react (email, SLA check, analytics). Observer decouples ticket logic from notification logic. Add new observers without touching ticket code. |
| **Factory** | `TicketFactory` | Ticket creation involves generating ticket number, setting defaults, validating. Factory centralizes creation logic and hides complexity. Single place to change creation rules. |

---

## SOLID Principles Applied

| Principle | How Applied |
|-----------|-------------|
| **S**ingle Responsibility | Each class has one job: `TicketService` orchestrates; `TicketRoutingStrategy` routes; `TicketEventObserver` reacts; `TicketFactory` creates. |
| **O**pen/Closed | New routing: add `TicketRoutingStrategy` impl. New notification: add `TicketEventObserver` impl. No changes to existing classes. |
| **L**iskov Substitution | Any `TicketRoutingStrategy` can replace another. Any `TicketEventObserver` can be added/removed. Subtypes honor contracts. |
| **I**nterface Segregation | `TicketRoutingStrategy` has only `selectAgent()`. `TicketEventObserver` has only `onTicketEvent()`. No fat interfaces. |
| **D**ependency Inversion | `TicketService` depends on `TicketRoutingStrategy` and `TicketEventObserver` abstractions, not concrete classes. Injected via constructor. |

---

## Code Implementation in Java

```java
// ============================================================================
// ENUMS: TicketStatus, TicketPriority, TicketCategory
// WHY: Type-safe constants. Prevents invalid values (e.g., "openn" typo).
//      Encapsulates valid states and transitions for the State pattern.
// ============================================================================

/** Valid ticket lifecycle states. State pattern: transitions are validated. */
public enum TicketStatus {
    OPEN,              // Initial state
    IN_PROGRESS,       // Agent is working on it
    AWAITING_CUSTOMER, // Waiting for customer reply
    RESOLVED,          // Issue fixed, pending confirmation
    CLOSED             // Terminal state
}

/** Priority drives routing order and SLA. Higher = faster response. */
public enum TicketPriority {
    LOW, MEDIUM, HIGH, URGENT
}

/** Category drives skill-based routing and canned response lookup. */
public enum TicketCategory {
    BILLING, SHIPPING, RETURNS, TECHNICAL, GENERAL
}


// ============================================================================
// MODEL: SupportTicket
// OOP: Encapsulation (private fields, getters). State machine for lifecycle.
// WHY: Ticket is the core domain object. Encapsulation prevents invalid 
//      mutations. State transitions are validated in setStatus().
// ============================================================================

import java.time.Instant;
import java.util.UUID;

/** Core domain entity. Encapsulates ticket data and enforces state transitions. */
public class SupportTicket {
    private final UUID ticketId;
    private final String ticketNumber;
    private final UUID createdBy;
    private final UUID orderId;
    private final String subject;
    private final String description;
    private TicketStatus status;
    private final TicketPriority priority;
    private final TicketCategory category;
    private final Instant createdAt;

    public SupportTicket(UUID ticketId, String ticketNumber, UUID createdBy, UUID orderId,
                         String subject, String description, TicketStatus status,
                         TicketPriority priority, TicketCategory category, Instant createdAt) {
        this.ticketId = ticketId;
        this.ticketNumber = ticketNumber;
        this.createdBy = createdBy;
        this.orderId = orderId;
        this.subject = subject;
        this.description = description;
        this.status = status;
        this.priority = priority;
        this.category = category;
        this.createdAt = createdAt;
    }

    /**
     * State pattern: Valid transitions only.
     * OPEN -> IN_PROGRESS, AWAITING_CUSTOMER
     * IN_PROGRESS -> AWAITING_CUSTOMER, RESOLVED
     * AWAITING_CUSTOMER -> IN_PROGRESS, RESOLVED
     * RESOLVED -> CLOSED
     * CLOSED -> (terminal)
     */
    public void setStatus(TicketStatus newStatus) {
        if (!isValidTransition(this.status, newStatus)) {
            throw new IllegalStateException("Invalid transition: " + this.status + " -> " + newStatus);
        }
        this.status = newStatus;
    }

    private boolean isValidTransition(TicketStatus from, TicketStatus to) {
        return switch (from) {
            case OPEN -> to == TicketStatus.IN_PROGRESS || to == TicketStatus.AWAITING_CUSTOMER;
            case IN_PROGRESS -> to == TicketStatus.AWAITING_CUSTOMER || to == TicketStatus.RESOLVED;
            case AWAITING_CUSTOMER -> to == TicketStatus.IN_PROGRESS || to == TicketStatus.RESOLVED;
            case RESOLVED -> to == TicketStatus.CLOSED;
            case CLOSED -> false;
        };
    }

    public UUID getTicketId() { return ticketId; }
    public String getTicketNumber() { return ticketNumber; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getOrderId() { return orderId; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public TicketStatus getStatus() { return status; }
    public TicketPriority getPriority() { return priority; }
    public TicketCategory getCategory() { return category; }
    public Instant getCreatedAt() { return createdAt; }
}


// ============================================================================
// STRATEGY PATTERN: TicketRoutingStrategy
// WHY: Different routing algorithms (round-robin, skill-based, priority).
//      Strategy lets us swap at runtime. Open/Closed: add new strategies 
//      without modifying TicketService.
// ============================================================================

import java.util.List;

/** Strategy interface. DIP: TicketService depends on abstraction. */
public interface TicketRoutingStrategy {
    /** Select an agent for the given ticket from available agents. */
    User selectAgent(SupportTicket ticket, List<User> availableAgents);
}

/** Round-robin: Distribute evenly. Simple, fair for low-complexity queues. */
public class RoundRobinRouting implements TicketRoutingStrategy {
    private int counter = 0;

    @Override
    public User selectAgent(SupportTicket ticket, List<User> availableAgents) {
        if (availableAgents.isEmpty()) return null;
        return availableAgents.get(Math.abs(counter++) % availableAgents.size());
    }
}

/** Skill-based: Match ticket category to agent skills. Best for specialized support. */
public class SkillBasedRouting implements TicketRoutingStrategy {
    @Override
    public User selectAgent(SupportTicket ticket, List<User> availableAgents) {
        return availableAgents.stream()
                .filter(a -> a.getSkills().contains(ticket.getCategory().name()))
                .findFirst()
                .orElse(availableAgents.isEmpty() ? null : availableAgents.get(0));
    }
}

/** Priority-based: Prefer agents with fewer high-priority tickets. Balances workload. */
public class PriorityBasedRouting implements TicketRoutingStrategy {
    private final java.util.function.Function<User, Integer> workloadGetter;

    public PriorityBasedRouting(java.util.function.Function<User, Integer> workloadGetter) {
        this.workloadGetter = workloadGetter;
    }

    @Override
    public User selectAgent(SupportTicket ticket, List<User> availableAgents) {
        return availableAgents.stream()
                .min((a, b) -> workloadGetter.apply(a).compareTo(workloadGetter.apply(b)))
                .orElse(null);
    }
}


// ============================================================================
// OBSERVER PATTERN: TicketEventObserver
// WHY: When ticket changes, multiple systems react (email, SLA, analytics).
//      Observer decouples ticket logic from notification logic. Add new 
//      observers without touching TicketService.
// ============================================================================

/** Observer interface. Notified on ticket lifecycle events. */
public interface TicketEventObserver {
    void onTicketEvent(TicketEvent event);
}

/** Event payload. Immutable. */
public record TicketEvent(SupportTicket ticket, String eventType) {}

/** Sends email to customer on status change. */
public class EmailNotificationObserver implements TicketEventObserver {
    @Override
    public void onTicketEvent(TicketEvent event) {
        // In production: send email via email service
        System.out.println("[Email] Ticket " + event.ticket().getTicketNumber() + ": " + event.eventType());
    }
}

/** Monitors SLA deadlines. Escalates if breached. */
public class SlaMonitoringObserver implements TicketEventObserver {
    @Override
    public void onTicketEvent(TicketEvent event) {
        // In production: check sla_deadline, escalate if overdue
        System.out.println("[SLA] Monitoring ticket " + event.ticket().getTicketNumber());
    }
}

/** Tracks metrics for analytics. */
public class AnalyticsObserver implements TicketEventObserver {
    @Override
    public void onTicketEvent(TicketEvent event) {
        System.out.println("[Analytics] Event: " + event.eventType() + " for " + event.ticket().getTicketNumber());
    }
}


// ============================================================================
// FACTORY PATTERN: TicketFactory
// WHY: Ticket creation involves: generate ticket number, set defaults, 
//      validate. Factory centralizes this. Single place to change creation 
//      rules. SRP: creation logic separated from business logic.
// ============================================================================

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public class TicketFactory {
    private static final AtomicLong sequence = new AtomicLong(0);

    /** Factory method. Creates ticket with defaults and unique ticket number. */
    public SupportTicket create(UUID createdBy, UUID orderId, String subject, String description,
                                TicketPriority priority, TicketCategory category) {
        String ticketNumber = "TKT-" + System.currentTimeMillis() + "-" + sequence.incrementAndGet();
        return new SupportTicket(
                UUID.randomUUID(),
                ticketNumber,
                createdBy,
                orderId,
                subject,
                description,
                TicketStatus.OPEN,
                priority != null ? priority : TicketPriority.MEDIUM,
                category != null ? category : TicketCategory.GENERAL,
                Instant.now()
        );
    }
}


// ============================================================================
// MODEL: User (simplified for routing)
// ============================================================================

import java.util.List;

public class User {
    private final UUID userId;
    private final String email;
    private final String role;
    private final List<String> skills;

    public User(UUID userId, String email, String role, List<String> skills) {
        this.userId = userId;
        this.email = email;
        this.role = role;
        this.skills = skills;
    }

    public UUID getUserId() { return userId; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public List<String> getSkills() { return skills; }
}


// ============================================================================
// SERVICE: TicketService (Orchestrator)
// DIP: Constructor injection of Strategy, Factory, Observers.
// SRP: Orchestrates creation, routing, status updates. Delegates to collaborators.
// ============================================================================

import java.util.ArrayList;
import java.util.List;

public class TicketService {
    private final TicketFactory ticketFactory;
    private final TicketRoutingStrategy routingStrategy;
    private final List<TicketEventObserver> observers;

    /** Constructor injection. DIP: depend on abstractions. */
    public TicketService(TicketFactory ticketFactory,
                         TicketRoutingStrategy routingStrategy,
                         List<TicketEventObserver> observers) {
        this.ticketFactory = ticketFactory;
        this.routingStrategy = routingStrategy;
        this.observers = observers != null ? new ArrayList<>(observers) : new ArrayList<>();
    }

    /** Creates ticket, routes to agent, notifies observers. */
    public SupportTicket createTicket(UUID createdBy, UUID orderId, String subject, String description,
                                      TicketPriority priority, TicketCategory category,
                                      List<User> availableAgents) {
        SupportTicket ticket = ticketFactory.create(createdBy, orderId, subject, description, priority, category);
        User agent = routingStrategy.selectAgent(ticket, availableAgents);
        notifyObservers(new TicketEvent(ticket, "CREATED"));
        return ticket;
    }

    /** Updates status with state validation, notifies observers. */
    public void updateStatus(SupportTicket ticket, TicketStatus newStatus) {
        ticket.setStatus(newStatus);
        notifyObservers(new TicketEvent(ticket, "STATUS_CHANGED"));
    }

    private void notifyObservers(TicketEvent event) {
        for (TicketEventObserver o : observers) {
            o.onTicketEvent(event);
        }
    }
}


// ============================================================================
// CANNED RESPONSE (Model + usage)
// ============================================================================

public class CannedResponse {
    private final java.util.UUID responseId;
    private final String title;
    private final String content;
    private final String category;

    public CannedResponse(java.util.UUID responseId, String title, String content, String category) {
        this.responseId = responseId;
        this.title = title;
        this.content = content;
        this.category = category;
    }

    public String getContent() { return content; }
    public String getCategory() { return category; }
}
```

---

## Edge Cases & Tests

```java
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PostPurchaseSupportTest {

    private TicketFactory ticketFactory;
    private TicketService ticketService;
    private List<TicketEventObserver> observers;

    @BeforeEach
    void setUp() {
        ticketFactory = new TicketFactory();
        observers = List.of(
                new EmailNotificationObserver(),
                new SlaMonitoringObserver(),
                new AnalyticsObserver()
        );
    }

    /** Edge: Valid state transition OPEN -> IN_PROGRESS. */
    @Test
    void ticketStatusTransition_OpenToInProgress_Succeeds() {
        SupportTicket ticket = ticketFactory.create(
                UUID.randomUUID(), null, "Subj", "Desc", TicketPriority.MEDIUM, TicketCategory.GENERAL);
        assertEquals(TicketStatus.OPEN, ticket.getStatus());
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        assertEquals(TicketStatus.IN_PROGRESS, ticket.getStatus());
    }

    /** Edge: Invalid transition OPEN -> CLOSED should throw. */
    @Test
    void ticketStatusTransition_OpenToClosed_Throws() {
        SupportTicket ticket = ticketFactory.create(
                UUID.randomUUID(), null, "Subj", "Desc", TicketPriority.MEDIUM, TicketCategory.GENERAL);
        assertThrows(IllegalStateException.class, () -> ticket.setStatus(TicketStatus.CLOSED));
    }

    /** Edge: Round-robin with empty agents returns null. */
    @Test
    void roundRobinRouting_EmptyAgents_ReturnsNull() {
        TicketRoutingStrategy strategy = new RoundRobinRouting();
        User agent = strategy.selectAgent(createDummyTicket(), List.of());
        assertNull(agent);
    }

    /** Edge: Round-robin distributes across agents. */
    @Test
    void roundRobinRouting_DistributesEvenly() {
        TicketRoutingStrategy strategy = new RoundRobinRouting();
        List<User> agents = List.of(
                new User(UUID.randomUUID(), "a@x.com", "AGENT", List.of()),
                new User(UUID.randomUUID(), "b@x.com", "AGENT", List.of())
        );
        User first = strategy.selectAgent(createDummyTicket(), agents);
        User second = strategy.selectAgent(createDummyTicket(), agents);
        assertNotEquals(first.getUserId(), second.getUserId());
    }

    /** Edge: Skill-based routing selects agent with matching skill. */
    @Test
    void skillBasedRouting_SelectsMatchingAgent() {
        TicketRoutingStrategy strategy = new SkillBasedRouting();
        SupportTicket ticket = createDummyTicket(TicketCategory.BILLING);
        User billingAgent = new User(UUID.randomUUID(), "b@x.com", "AGENT", List.of("BILLING"));
        User techAgent = new User(UUID.randomUUID(), "t@x.com", "AGENT", List.of("TECHNICAL"));
        User selected = strategy.selectAgent(ticket, List.of(techAgent, billingAgent));
        assertEquals("BILLING", selected.getSkills().get(0));
    }

    /** Edge: TicketService notifies all observers on create. */
    @Test
    void ticketService_NotifyObserversOnCreate() {
        List<String> events = new ArrayList<>();
        observers = List.of(
                event -> events.add("O1:" + event.eventType()),
                event -> events.add("O2:" + event.eventType())
        );
        ticketService = new TicketService(ticketFactory, new RoundRobinRouting(), observers);
        ticketService.createTicket(UUID.randomUUID(), null, "S", "D", null, null, List.of());
        assertEquals(2, events.size());
        assertTrue(events.stream().allMatch(e -> e.contains("CREATED")));
    }

    /** Edge: Full lifecycle OPEN -> RESOLVED -> CLOSED. */
    @Test
    void ticketFullLifecycle_ValidTransitions_Succeeds() {
        SupportTicket ticket = ticketFactory.create(
                UUID.randomUUID(), null, "S", "D", TicketPriority.HIGH, TicketCategory.GENERAL);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setStatus(TicketStatus.AWAITING_CUSTOMER);
        ticket.setStatus(TicketStatus.IN_PROGRESS);
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setStatus(TicketStatus.CLOSED);
        assertEquals(TicketStatus.CLOSED, ticket.getStatus());
    }

    /** Edge: Factory generates unique ticket numbers. */
    @Test
    void ticketFactory_GeneratesUniqueTicketNumbers() {
        SupportTicket t1 = ticketFactory.create(UUID.randomUUID(), null, "S", "D", null, null);
        SupportTicket t2 = ticketFactory.create(UUID.randomUUID(), null, "S", "D", null, null);
        assertNotEquals(t1.getTicketNumber(), t2.getTicketNumber());
    }

    private SupportTicket createDummyTicket() {
        return createDummyTicket(TicketCategory.GENERAL);
    }

    private SupportTicket createDummyTicket(TicketCategory category) {
        return ticketFactory.create(
                UUID.randomUUID(), null, "Subject", "Desc", TicketPriority.MEDIUM, category);
    }
}
```

---

## Summary

| Aspect | Detail |
|--------|--------|
| **Problem** | Post-purchase support system with tickets, routing, lifecycle, and notifications |
| **Core Tables** | users, orders, support_tickets, ticket_messages, ticket_assignments, canned_responses |
| **Patterns** | Strategy (routing), State (ticket lifecycle), Observer (notifications), Factory (creation) |
| **SOLID** | SRP, OCP, LSP, ISP, DIP applied via interfaces and constructor injection |
| **Key Design** | State machine for valid transitions; Strategy for pluggable routing; Observer for decoupled notifications |
| **Tests** | State transitions, routing edge cases, observer notification, full lifecycle, factory uniqueness |
