# Distributed Job Scheduler - LLD / Machine Coding Interview

---

## 1. Problem Statement

Design a distributed job scheduler that executes jobs across multiple worker nodes with support for cron/interval/one-time scheduling, retry logic, priority queuing, and observability. The system must handle job lifecycle, worker registration, failure recovery, and dead-letter handling for permanently failed jobs.

---

## 2. Requirements

### Functional Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| FR1 | Schedule jobs by CRON, INTERVAL, or ONE_TIME | Support multiple schedule types |
| FR2 | Execute jobs on available worker nodes | Workers pull jobs from queue |
| FR3 | Support job priority (HIGH, NORMAL, LOW) | Higher priority jobs run first |
| FR4 | Retry failed jobs with configurable strategy | FixedDelay, ExponentialBackoff, or NoRetry |
| FR5 | Track job execution history | Status, timestamps, worker, error details |
| FR6 | Move exhausted retries to dead-letter queue | Jobs that fail after max retries |
| FR7 | Worker heartbeat and registration | Detect dead workers, reassign jobs |
| FR8 | Notify on job completion/failure | Observer for notifications, metrics, audit |

### Non-Functional Requirements

| ID | Requirement | Notes |
|----|-------------|-------|
| NFR1 | Scalability | Support 100+ workers, 10K+ jobs/day |
| NFR2 | Fault tolerance | No duplicate execution, graceful worker failure |
| NFR3 | Extensibility | Pluggable scheduling and retry strategies |
| NFR4 | Testability | Clear separation of concerns, dependency injection |
| NFR5 | Observability | Metrics, audit trail, notifications |

---

## 3. Database Design with Explanations

```sql
-- =============================================================================
-- TABLE: jobs
-- WHY: Core entity storing job definitions. Separates "what to run" from
--      "when to run" (job_schedules) and "run history" (job_executions).
-- =============================================================================
CREATE TABLE jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    command         TEXT NOT NULL,                    -- Shell command, HTTP URL, or script path
    timeout_seconds INTEGER DEFAULT 3600,
    max_retries     INTEGER DEFAULT 3,
    retry_strategy  VARCHAR(50) DEFAULT 'EXPONENTIAL_BACKOFF',  -- FK to strategy type
    priority        VARCHAR(20) DEFAULT 'NORMAL',     -- HIGH, NORMAL, LOW
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- INDEX: Fast lookup of jobs by name (admin/API queries)
CREATE INDEX idx_jobs_name ON jobs(name);

-- INDEX: Filter by priority for scheduling decisions
CREATE INDEX idx_jobs_priority ON jobs(priority);


-- =============================================================================
-- TABLE: job_schedules
-- WHY: Separate table for schedules allows one job to have multiple schedules
--      (e.g., daily + weekly) and keeps scheduling logic decoupled from job def.
-- =============================================================================
CREATE TABLE job_schedules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    schedule_type   VARCHAR(20) NOT NULL,             -- CRON, INTERVAL, ONE_TIME
    cron_expression VARCHAR(100),                    -- "0 */6 * * *" for CRON type
    interval_seconds INTEGER,                        -- For INTERVAL type (e.g., 3600 = hourly)
    run_at          TIMESTAMP,                       -- For ONE_TIME: exact run time
    is_active       BOOLEAN DEFAULT true,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW(),
    CONSTRAINT chk_schedule_type CHECK (
        (schedule_type = 'CRON' AND cron_expression IS NOT NULL) OR
        (schedule_type = 'INTERVAL' AND interval_seconds IS NOT NULL) OR
        (schedule_type = 'ONE_TIME' AND run_at IS NOT NULL)
    )
);

-- FK: job_id ensures referential integrity; CASCADE deletes schedules when job is deleted
-- INDEX: Scheduler scans active schedules to find jobs due to run
CREATE INDEX idx_job_schedules_active_type ON job_schedules(is_active, schedule_type);
CREATE INDEX idx_job_schedules_job_id ON job_schedules(job_id);
CREATE INDEX idx_job_schedules_run_at ON job_schedules(run_at) WHERE schedule_type = 'ONE_TIME';


-- =============================================================================
-- TABLE: job_executions
-- WHY: Immutable audit trail of every execution attempt. Enables retry tracking
--      (attempt_number, retry_of), worker assignment, and failure analysis.
-- =============================================================================
CREATE TABLE job_executions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    schedule_id     UUID REFERENCES job_schedules(id) ON DELETE SET NULL,
    worker_node_id  VARCHAR(100),                    -- FK to worker_nodes.worker_id
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING, RUNNING, COMPLETED, FAILED, RETRYING
    scheduled_at    TIMESTAMP NOT NULL,
    started_at      TIMESTAMP,
    completed_at    TIMESTAMP,
    attempt_number  INTEGER DEFAULT 1,
    retry_of        UUID REFERENCES job_executions(id),     -- Links retry to original execution
    error_message   TEXT,
    exit_code       INTEGER,
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- FK: job_id, schedule_id, retry_of maintain referential integrity
-- INDEX: Query execution history for a job (dashboard, debugging)
CREATE INDEX idx_job_executions_job_id ON job_executions(job_id);
CREATE INDEX idx_job_executions_status ON job_executions(status);
CREATE INDEX idx_job_executions_scheduled_at ON job_executions(scheduled_at DESC);
CREATE INDEX idx_job_executions_worker ON job_executions(worker_node_id) WHERE worker_node_id IS NOT NULL;
CREATE INDEX idx_job_executions_retry ON job_executions(retry_of) WHERE retry_of IS NOT NULL;


-- =============================================================================
-- TABLE: worker_nodes
-- WHY: Registry of available workers. Heartbeat-based liveness; scheduler
--      assigns jobs only to active workers. Supports capacity and draining.
-- =============================================================================
CREATE TABLE worker_nodes (
    worker_id       VARCHAR(100) PRIMARY KEY,
    hostname        VARCHAR(255) NOT NULL,
    status          VARCHAR(20) DEFAULT 'ACTIVE',    -- ACTIVE, DRAINING, OFFLINE
    max_concurrent  INTEGER DEFAULT 10,
    current_jobs    INTEGER DEFAULT 0,
    last_heartbeat  TIMESTAMP DEFAULT NOW(),
    created_at      TIMESTAMP DEFAULT NOW(),
    updated_at      TIMESTAMP DEFAULT NOW()
);

-- INDEX: Scheduler finds available workers (ACTIVE, capacity check)
CREATE INDEX idx_worker_nodes_status ON worker_nodes(status);
CREATE INDEX idx_worker_nodes_heartbeat ON worker_nodes(last_heartbeat DESC);
CREATE INDEX idx_worker_nodes_capacity ON worker_nodes(current_jobs, max_concurrent) WHERE status = 'ACTIVE';


-- =============================================================================
-- TABLE: dead_letter_jobs
-- WHY: Jobs that exhausted all retries. Isolated for manual inspection, alerting,
--      and potential replay. Prevents polluting main job/execution tables.
-- =============================================================================
CREATE TABLE dead_letter_jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    job_id          UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    execution_id    UUID NOT NULL REFERENCES job_executions(id) ON DELETE CASCADE,
    failure_count   INTEGER NOT NULL,
    last_error      TEXT,
    last_attempt_at TIMESTAMP NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW()
);

-- FK: job_id, execution_id link to source job and final failed execution
-- INDEX: Admin queries for DLQ dashboard, replay by job
CREATE INDEX idx_dead_letter_jobs_job_id ON dead_letter_jobs(job_id);
CREATE INDEX idx_dead_letter_jobs_created ON dead_letter_jobs(created_at DESC);
```

---

## 4. Design Patterns

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | `JobSchedulingStrategy` (FIFO, Priority, FairShare) | Interchangeable scheduling algorithms without modifying scheduler; supports different SLA needs |
| **Strategy** | `RetryStrategy` (FixedDelay, ExponentialBackoff, NoRetry) | Pluggable retry behavior per job; easy to add new strategies (e.g., LinearBackoff) |
| **Observer** | `JobEventObserver` (NotificationObserver, MetricsObserver, AuditObserver) | Decouple job lifecycle events from side effects; add/remove observers without touching core logic |
| **Command** | `JobCommand` | Encapsulate job execution as object; enables retry, undo (cancel), and queuing; supports transactional semantics |
| **State** | Job lifecycle (PENDING → RUNNING → COMPLETED/FAILED/RETRYING) | Centralized state transitions; prevents invalid transitions; single place for side effects |
| **Dependency Injection** | `JobSchedulerService` constructor | Testability; swap real DB/queue for mocks; follow IoC principle |
| **Factory** | `RetryStrategyFactory`, `JobSchedulingStrategyFactory` | Create strategy instances from config/enum; hide construction details |

---

## 5. SOLID Principles

| Principle | Application |
|-----------|-------------|
| **S**ingle Responsibility | `JobSchedulerService` orchestrates; `JobRepository` handles persistence; `JobQueue` handles queuing; each class has one reason to change |
| **O**pen/Closed | New scheduling strategies (e.g., `DeadlineScheduler`) extend `JobSchedulingStrategy` without modifying scheduler; new observers extend `JobEventObserver` |
| **L**iskov Substitution | Any `JobSchedulingStrategy` implementation can replace another; any `RetryStrategy` can replace another; clients depend on abstractions |
| **I**nterface Segregation | `JobEventObserver` has minimal `onJobEvent(JobEvent)`; no fat interface; notification/metrics/audit implement only what they need |
| **D**ependency Inversion | `JobSchedulerService` depends on `JobRepository`, `JobQueue`, `JobSchedulingStrategy` interfaces, not concrete implementations; high-level module doesn't depend on low-level |

---

## 6. Code Implementation in Java

### 6.1 Enums

```java
/**
 * Job lifecycle status. State machine: PENDING -> RUNNING -> {COMPLETED | FAILED | RETRYING}
 * RETRYING transitions back to PENDING when retry is scheduled.
 */
public enum JobStatus {
    PENDING,    // Queued, waiting for worker
    RUNNING,    // Executing on worker
    COMPLETED,  // Success
    FAILED,     // Failed, may retry or go to DLQ
    RETRYING    // Failed, will retry (transitions to PENDING)
}

/**
 * Job priority for scheduling. Higher priority jobs are picked first.
 * Used by PriorityScheduler strategy.
 */
public enum JobPriority {
    HIGH(1),
    NORMAL(5),
    LOW(10);

    private final int value;
    JobPriority(int value) { this.value = value; }
    public int getValue() { return value; }
}

/**
 * Schedule type determines how next run time is computed.
 * CRON: cron expression (e.g., "0 */6 * * *")
 * INTERVAL: fixed interval in seconds
 * ONE_TIME: single execution at specified time
 */
public enum ScheduleType {
    CRON,
    INTERVAL,
    ONE_TIME
}
```

### 6.2 Models with State Machine

```java
import java.time.Instant;
import java.util.UUID;

/** Job definition - what to execute. Immutable after creation for audit. */
public class Job {
    private final UUID id;
    private final String name;
    private final String command;
    private final int timeoutSeconds;
    private final int maxRetries;
    private final String retryStrategyType;
    private final JobPriority priority;

    public Job(UUID id, String name, String command, int timeoutSeconds,
               int maxRetries, String retryStrategyType, JobPriority priority) {
        this.id = id;
        this.name = name;
        this.command = command;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
        this.retryStrategyType = retryStrategyType;
        this.priority = priority;
    }
    // Getters...
}

/**
 * JobExecution - single run instance. State machine enforces valid transitions.
 * WHY: Centralizes transition logic; prevents invalid states (e.g., COMPLETED -> RUNNING).
 */
public class JobExecution {
    private final UUID id;
    private final UUID jobId;
    private final UUID scheduleId;
    private volatile JobStatus status;
    private String workerNodeId;
    private final Instant scheduledAt;
    private Instant startedAt;
    private Instant completedAt;
    private final int attemptNumber;
    private final UUID retryOf;
    private String errorMessage;
    private Integer exitCode;

    public JobExecution(UUID id, UUID jobId, UUID scheduleId, Instant scheduledAt,
                        int attemptNumber, UUID retryOf) {
        this.id = id;
        this.jobId = jobId;
        this.scheduleId = scheduleId;
        this.status = JobStatus.PENDING;
        this.scheduledAt = scheduledAt;
        this.attemptNumber = attemptNumber;
        this.retryOf = retryOf;
    }

    /** State machine: only valid transitions allowed */
    public void transitionTo(JobStatus newStatus) {
        switch (status) {
            case PENDING:
                if (newStatus != JobStatus.RUNNING) throw new IllegalStateException("PENDING -> " + newStatus);
                break;
            case RUNNING:
                if (newStatus != JobStatus.COMPLETED && newStatus != JobStatus.FAILED)
                    throw new IllegalStateException("RUNNING -> " + newStatus);
                break;
            case FAILED:
            case RETRYING:
                if (newStatus != JobStatus.PENDING) throw new IllegalStateException(status + " -> " + newStatus);
                break;
            case COMPLETED:
                throw new IllegalStateException("COMPLETED is terminal");
            default:
                throw new IllegalStateException("Unknown status: " + status);
        }
        this.status = newStatus;
    }

    public void markRunning(String workerNodeId) {
        transitionTo(JobStatus.RUNNING);
        this.workerNodeId = workerNodeId;
        this.startedAt = Instant.now();
    }

    public void markCompleted(int exitCode) {
        transitionTo(JobStatus.COMPLETED);
        this.exitCode = exitCode;
        this.completedAt = Instant.now();
    }

    public void markFailed(String errorMessage) {
        transitionTo(JobStatus.FAILED);
        this.errorMessage = errorMessage;
        this.completedAt = Instant.now();
    }

    public void markRetrying() {
        transitionTo(JobStatus.RETRYING);
    }

    // Getters...
}

/**
 * WorkerNode - represents a worker in the cluster.
 * Heartbeat-based liveness; capacity tracked for fair scheduling.
 */
public class WorkerNode {
    private final String workerId;
    private final String hostname;
    private volatile String status;  // ACTIVE, DRAINING, OFFLINE
    private final int maxConcurrent;
    private volatile int currentJobs;
    private volatile Instant lastHeartbeat;

    public WorkerNode(String workerId, String hostname, int maxConcurrent) {
        this.workerId = workerId;
        this.hostname = hostname;
        this.status = "ACTIVE";
        this.maxConcurrent = maxConcurrent;
        this.currentJobs = 0;
        this.lastHeartbeat = Instant.now();
    }

    public boolean canAcceptJob() {
        return "ACTIVE".equals(status) && currentJobs < maxConcurrent;
    }

    public void incrementJobs() { currentJobs++; }
    public void decrementJobs() { currentJobs = Math.max(0, currentJobs - 1); }
    public void heartbeat() { lastHeartbeat = Instant.now(); }
    public String getWorkerId() { return workerId; }
    public int getCurrentJobs() { return currentJobs; }
    // Other getters...
}
```

### 6.3 Strategy: JobSchedulingStrategy

```java
import java.util.List;

/**
 * Strategy pattern: interchangeable scheduling algorithms.
 * WHY: Open/Closed - add new schedulers without modifying JobSchedulerService.
 */
public interface JobSchedulingStrategy {
    /**
     * Select next job(s) to run from pending executions.
     * @param pending Executions waiting for workers
     * @param workers Available workers
     * @return Assignments of execution -> worker
     */
    List<JobAssignment> schedule(List<JobExecution> pending, List<WorkerNode> workers);
}

public record JobAssignment(JobExecution execution, WorkerNode worker) {}

/** FIFO: First-in-first-out. Simplest; fair for equal-priority jobs. */
public class FIFOScheduler implements JobSchedulingStrategy {
    @Override
    public List<JobAssignment> schedule(List<JobExecution> pending, List<WorkerNode> workers) {
        var assignments = new ArrayList<JobAssignment>();
        var availableWorkers = workers.stream().filter(WorkerNode::canAcceptJob).toList();
        for (int i = 0; i < Math.min(pending.size(), availableWorkers.size()); i++) {
            assignments.add(new JobAssignment(pending.get(i), availableWorkers.get(i)));
        }
        return assignments;
    }
}

/** Priority: Higher priority jobs run first. Uses Job.priority. */
public class PriorityScheduler implements JobSchedulingStrategy {
    private final JobRepository jobRepository;

    public PriorityScheduler(JobRepository jobRepository) { this.jobRepository = jobRepository; }

    @Override
    public List<JobAssignment> schedule(List<JobExecution> pending, List<WorkerNode> workers) {
        var jobs = jobRepository.findByIds(pending.stream().map(JobExecution::getJobId).toList());
        var byPriority = pending.stream()
            .sorted(Comparator.comparingInt(e -> {
                var j = jobs.get(e.getJobId());
                return j != null ? j.getPriority().getValue() : Integer.MAX_VALUE;
            }))
            .toList();
        var availableWorkers = workers.stream().filter(WorkerNode::canAcceptJob).toList();
        var assignments = new ArrayList<JobAssignment>();
        for (int i = 0; i < Math.min(byPriority.size(), availableWorkers.size()); i++) {
            assignments.add(new JobAssignment(byPriority.get(i), availableWorkers.get(i)));
        }
        return assignments;
    }
}

/** FairShare: Distribute jobs across workers to balance load. */
public class FairShareScheduler implements JobSchedulingStrategy {
    @Override
    public List<JobAssignment> schedule(List<JobExecution> pending, List<WorkerNode> workers) {
        var available = workers.stream().filter(WorkerNode::canAcceptJob)
            .sorted(Comparator.comparingInt(WorkerNode::getCurrentJobs))
            .toList();
        var assignments = new ArrayList<JobAssignment>();
        for (int i = 0; i < Math.min(pending.size(), available.size()); i++) {
            assignments.add(new JobAssignment(pending.get(i), available.get(i)));
        }
        return assignments;
    }
}
```

### 6.4 Strategy: RetryStrategy

```java
import java.time.Instant;

/**
 * Strategy pattern: pluggable retry behavior.
 * WHY: Different jobs need different retry semantics (e.g., idempotent vs non-idempotent).
 */
public interface RetryStrategy {
    /**
     * @param attemptNumber 1-based attempt count
     * @param lastFailureTime When the last attempt failed
     * @return Next run time, or null if no retry
     */
    Instant getNextRetryTime(int attemptNumber, Instant lastFailureTime);
}

/** Fixed delay between retries (e.g., 60s, 60s, 60s). */
public class FixedDelayRetryStrategy implements RetryStrategy {
    private final int delaySeconds;

    public FixedDelayRetryStrategy(int delaySeconds) { this.delaySeconds = delaySeconds; }

    @Override
    public Instant getNextRetryTime(int attemptNumber, Instant lastFailureTime) {
        return lastFailureTime.plusSeconds(delaySeconds);
    }
}

/** Exponential backoff: delay * 2^(attempt-1). */
public class ExponentialBackoffRetryStrategy implements RetryStrategy {
    private final int initialDelaySeconds;
    private final double multiplier;

    public ExponentialBackoffRetryStrategy(int initialDelaySeconds, double multiplier) {
        this.initialDelaySeconds = initialDelaySeconds;
        this.multiplier = multiplier;
    }

    @Override
    public Instant getNextRetryTime(int attemptNumber, Instant lastFailureTime) {
        long delay = (long) (initialDelaySeconds * Math.pow(multiplier, attemptNumber - 1));
        return lastFailureTime.plusSeconds(delay);
    }
}

/** No retries - fail immediately to DLQ. */
public class NoRetryStrategy implements RetryStrategy {
    @Override
    public Instant getNextRetryTime(int attemptNumber, Instant lastFailureTime) {
        return null;
    }
}
```

### 6.5 Observer: JobEventObserver

```java
/**
 * Observer pattern: decouple job lifecycle events from side effects.
 * WHY: Add notifications, metrics, audit without modifying scheduler; Single Responsibility.
 */
public interface JobEventObserver {
    void onJobEvent(JobEvent event);
}

public record JobEvent(JobExecution execution, JobEventType type) {}
public enum JobEventType { SCHEDULED, STARTED, COMPLETED, FAILED, RETRYING, DEAD_LETTER }

/** Sends notifications (email, Slack) on failure/dead-letter. */
public class NotificationObserver implements JobEventObserver {
    private final NotificationService notificationService;

    public NotificationObserver(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Override
    public void onJobEvent(JobEvent event) {
        if (event.type() == JobEventType.FAILED || event.type() == JobEventType.DEAD_LETTER) {
            notificationService.send("Job " + event.execution().getJobId() + " failed: " + event.execution().getErrorMessage());
        }
    }
}

/** Records metrics (success rate, latency) for dashboards. */
public class MetricsObserver implements JobEventObserver {
    private final MetricsCollector metrics;

    public MetricsObserver(MetricsCollector metrics) { this.metrics = metrics; }

    @Override
    public void onJobEvent(JobEvent event) {
        metrics.recordJobEvent(event.type(), event.execution().getJobId());
        if (event.type() == JobEventType.COMPLETED && event.execution().getCompletedAt() != null) {
            long duration = event.execution().getCompletedAt().toEpochMilli() - event.execution().getStartedAt().toEpochMilli();
            metrics.recordJobDuration(event.execution().getJobId(), duration);
        }
    }
}

/** Writes to audit log for compliance. */
public class AuditObserver implements JobEventObserver {
    private final AuditLog auditLog;

    public AuditObserver(AuditLog auditLog) { this.auditLog = auditLog; }

    @Override
    public void onJobEvent(JobEvent event) {
        auditLog.append("JOB_EVENT", event.type().name(), event.execution().getId().toString());
    }
}
```

### 6.6 Command Pattern: JobCommand

```java
/**
 * Command pattern: encapsulate job execution as object.
 * WHY: Enables retry (re-execute same command), undo (cancel), and queuing.
 *      Supports transactional semantics and logging.
 */
public interface JobCommand {
    JobExecutionResult execute();
    void cancel();
}

public record JobExecutionResult(boolean success, int exitCode, String errorMessage) {}

/**
 * Concrete command: runs a job on a worker.
 * Can be re-executed for retry; cancel() stops the process.
 */
public class ExecuteJobCommand implements JobCommand {
    private final Job job;
    private final JobExecution execution;
    private final WorkerNode worker;
    private volatile Process process;

    public ExecuteJobCommand(Job job, JobExecution execution, WorkerNode worker) {
        this.job = job;
        this.execution = execution;
        this.worker = worker;
    }

    @Override
    public JobExecutionResult execute() {
        try {
            process = Runtime.getRuntime().exec(job.getCommand());
            int exitCode = process.waitFor(job.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (exitCode == 0) {
                return new JobExecutionResult(true, 0, null);
            } else {
                return new JobExecutionResult(false, exitCode, "Exit code: " + exitCode);
            }
        } catch (Exception e) {
            return new JobExecutionResult(false, -1, e.getMessage());
        } finally {
            worker.decrementJobs();
        }
    }

    @Override
    public void cancel() {
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
```

### 6.7 JobSchedulerService (Orchestrator with DI)

```java
import java.util.*;

/**
 * Orchestrator: coordinates scheduling, execution, retry, and observers.
 * WHY: Dependency Injection - all dependencies injected; testable with mocks.
 *      Single Responsibility - orchestrates, doesn't implement scheduling/retry logic.
 */
public class JobSchedulerService {
    private final JobRepository jobRepository;
    private final JobExecutionRepository executionRepository;
    private final JobQueue jobQueue;
    private final JobSchedulingStrategy schedulingStrategy;
    private final Map<String, RetryStrategy> retryStrategies;
    private final List<JobEventObserver> observers;
    private final WorkerRegistry workerRegistry;
    private final DeadLetterQueue deadLetterQueue;

    public JobSchedulerService(JobRepository jobRepository,
                               JobExecutionRepository executionRepository,
                               JobQueue jobQueue,
                               JobSchedulingStrategy schedulingStrategy,
                               Map<String, RetryStrategy> retryStrategies,
                               List<JobEventObserver> observers,
                               WorkerRegistry workerRegistry,
                               DeadLetterQueue deadLetterQueue) {
        this.jobRepository = jobRepository;
        this.executionRepository = executionRepository;
        this.jobQueue = jobQueue;
        this.schedulingStrategy = schedulingStrategy;
        this.retryStrategies = retryStrategies;
        this.observers = observers;
        this.workerRegistry = workerRegistry;
        this.deadLetterQueue = deadLetterQueue;
    }

    /** Main scheduling loop: get pending, assign to workers, dispatch. */
    public void runSchedulingCycle() {
        List<JobExecution> pending = jobQueue.drainPending();
        List<WorkerNode> workers = workerRegistry.getActiveWorkers();
        List<JobAssignment> assignments = schedulingStrategy.schedule(pending, workers);

        for (JobAssignment a : assignments) {
            a.execution().markRunning(a.worker().getWorkerId());
            a.worker().incrementJobs();
            executionRepository.save(a.execution());
            notifyObservers(new JobEvent(a.execution(), JobEventType.STARTED));

            Job job = jobRepository.findById(a.execution().getJobId()).orElseThrow();
            JobCommand command = new ExecuteJobCommand(job, a.execution(), a.worker());
            // In production: submit to executor service
            JobExecutionResult result = command.execute();

            if (result.success()) {
                a.execution().markCompleted(result.exitCode());
                notifyObservers(new JobEvent(a.execution(), JobEventType.COMPLETED));
            } else {
                handleFailure(a.execution(), job, result);
            }
            executionRepository.save(a.execution());
        }

        // Re-queue unassigned pending
        for (JobExecution e : pending) {
            if (e.getStatus() == JobStatus.PENDING) {
                jobQueue.enqueue(e);
            }
        }
    }

    private void handleFailure(JobExecution execution, Job job, JobExecutionResult result) {
        execution.markFailed(result.errorMessage());
        notifyObservers(new JobEvent(execution, JobEventType.FAILED));

        RetryStrategy retryStrategy = retryStrategies.getOrDefault(job.getRetryStrategyType(),
            retryStrategies.get("EXPONENTIAL_BACKOFF"));
        Instant nextRetry = retryStrategy.getNextRetryTime(execution.getAttemptNumber(), execution.getCompletedAt());

        if (nextRetry != null && execution.getAttemptNumber() < job.getMaxRetries()) {
            execution.markRetrying();
            notifyObservers(new JobEvent(execution, JobEventType.RETRYING));
            JobExecution retryExecution = new JobExecution(UUID.randomUUID(), job.getId(), execution.getScheduleId(),
                nextRetry, execution.getAttemptNumber() + 1, execution.getId());
            jobQueue.enqueue(retryExecution);
        } else {
            deadLetterQueue.add(execution, job.getMaxRetries(), result.errorMessage());
            notifyObservers(new JobEvent(execution, JobEventType.DEAD_LETTER));
        }
    }

    private void notifyObservers(JobEvent event) {
        observers.forEach(o -> o.onJobEvent(event));
    }
}
```

### 6.8 Interfaces (for DI)

```java
public interface JobRepository {
    Optional<Job> findById(UUID id);
    Map<UUID, Job> findByIds(List<UUID> ids);
}

public interface JobExecutionRepository {
    void save(JobExecution execution);
}

public interface JobQueue {
    void enqueue(JobExecution execution);
    List<JobExecution> drainPending();
}

public interface WorkerRegistry {
    List<WorkerNode> getActiveWorkers();
}

public interface DeadLetterQueue {
    void add(JobExecution execution, int failureCount, String lastError);
}
```

---

## 7. Edge Cases & Tests

| # | Edge Case | Test Scenario | Expected Behavior |
|---|-----------|---------------|-------------------|
| 1 | **Worker dies mid-execution** | Worker heartbeat stops; job was RUNNING | Scheduler marks execution FAILED after timeout; job retried or sent to DLQ |
| 2 | **Duplicate execution** | Two schedulers pick same job | Use distributed lock (e.g., Redis) on (job_id, scheduled_at); only one acquires |
| 3 | **All workers at capacity** | Pending jobs > available worker slots | Jobs remain in queue; next cycle assigns when workers free up |
| 4 | **Max retries exceeded** | Job fails 4 times with max_retries=3 | Move to dead_letter_jobs; notify observers; do not retry |
| 5 | **Invalid state transition** | Call `markCompleted()` on PENDING execution | Throw IllegalStateException; state machine rejects |
| 6 | **ONE_TIME schedule after run** | Job with ONE_TIME runs successfully | Deactivate schedule or delete; do not reschedule |
| 7 | **CRON parse failure** | Invalid cron expression "invalid" | Fail at schedule creation; log error; do not create execution |
| 8 | **Empty observer list** | No observers registered | No NPE; notifyObservers iterates empty list safely |

### Sample Test (JUnit)

```java
@Test
void stateMachine_rejectsInvalidTransition() {
    var execution = new JobExecution(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        Instant.now(), 1, null);
    execution.markRunning("worker-1");
    execution.markCompleted(0);
    assertThrows(IllegalStateException.class, () -> execution.markRunning("worker-2"));
}

@Test
void maxRetriesExceeded_movesToDeadLetter() {
    var job = new Job(UUID.randomUUID(), "test", "exit 1", 60, 2, "FIXED_DELAY", JobPriority.NORMAL);
    var execution = new JobExecution(UUID.randomUUID(), job.getId(), null, Instant.now(), 2, null);
    execution.markFailed("error");
    // Simulate handleFailure: attempt 2 >= maxRetries 2 -> DLQ
    assertTrue(deadLetterQueue.contains(execution.getId()));
}
```

---

## 8. Summary

| Aspect | Key Takeaway |
|--------|--------------|
| **Problem** | Distributed job scheduler with scheduling, retry, priority, and observability |
| **DB** | jobs, job_schedules, job_executions, worker_nodes, dead_letter_jobs with clear FK/index rationale |
| **Patterns** | Strategy (scheduling, retry), Observer (notify/metrics/audit), Command (execute/retry), State (job lifecycle) |
| **SOLID** | SRP per component, OCP via strategies, DIP via constructor injection |
| **Code** | Enums, models with state machine, strategies, observers, command, orchestrator with DI |
| **Edge Cases** | Worker death, duplicate execution, capacity, retry exhaustion, invalid transitions, ONE_TIME, cron parse |

**Interview focus:** Demonstrate design patterns, SOLID, database design rationale, and edge-case handling in a 45–60 minute machine coding session.
