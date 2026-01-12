# Distributed Job Scheduler - System Design Interview

## Problem Statement
*"Design a distributed job scheduler like Apache Airflow or Quartz that can execute millions of jobs daily across multiple nodes with dependencies, retry logic, and monitoring."*

---

## Phase 1: Requirements Clarification (8 minutes)

### Questions I Would Ask:
**Functional Requirements:**
- "What types of jobs do we need to support?" → Cron jobs, one-time tasks, HTTP endpoints, data processing
- "Do we need job dependencies?" → Yes, DAG execution with upstream/downstream dependencies
- "Should we support retries?" → Yes, exponential backoff, dead letter queue
- "What about job priority?" → Yes, priority queues with different execution priorities
- "Do we need monitoring?" → Yes, job status, logs, performance metrics, alerting
- "Should we support dynamic scaling?" → Yes, auto-scale workers based on queue depth

**Non-Functional Requirements:**
- "What's our job volume?" → 1M+ jobs/day, 10K+ concurrent executions
- "Expected latency?" → <5 seconds job scheduling, <1 minute startup time
- "Availability needs?" → 99.99% scheduler uptime, graceful failure handling
- "How many worker nodes?" → 100+ worker nodes across multiple data centers
- "Resource constraints?" → CPU/memory limits per job, timeout handling

### Requirements Summary:
- **Scale**: 1M+ jobs/day, 10K+ concurrent, 100+ worker nodes
- **Features**: Cron scheduling, dependencies, retries, priority, monitoring
- **Types**: HTTP calls, shell scripts, data processing, custom executors
- **Performance**: <5s scheduling, fault-tolerant execution
- **Multi-tenancy**: Team/project isolation with resource quotas

---

## Phase 2: Capacity Estimation (5 minutes)

### Job Volume:
```
Daily jobs: 1M jobs/day
Peak hourly: 100K jobs/hour = ~28 jobs/second
Concurrent executions: 10K simultaneous jobs
Average job duration: 5 minutes
Job failure rate: 5% (requiring retries)
```

### Storage Requirements:
```
Job definitions: 100K unique jobs × 5KB = 500MB
Execution history: 1M/day × 2KB × 365 × 2 years = 1.5TB
Job logs: 1M/day × 50KB avg × 30 days = 1.5TB
Metrics data: 100GB/month for monitoring
Total: ~3TB with retention policies
```

### Infrastructure:
```
Scheduler nodes: 3-5 for high availability
Worker nodes: 100+ distributed across regions
Queue capacity: 100K pending jobs in memory
Database connections: 1K+ concurrent connections
Network throughput: 100MB/s for log aggregation
```

---

## Phase 3: High-Level Architecture (12 minutes)

```
┌─────────────┐  ┌─────────────┐  ┌─────────────┐
│Developers   │  │Data Teams   │  │SRE/Ops      │
│- Job Deploy │  │- Pipelines  │  │- Monitoring │
│- Trigger    │  │- ETL        │  │- Alerts     │
└─────────────┘  └─────────────┘  └─────────────┘
       │                │                │
       └────────────────┼────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            Web Interface                │
│- Job Management - Monitoring - Logs    │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│             API Gateway                 │
│- Authentication - Rate limiting         │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│           Scheduler Cluster             │
│                                         │
│ ┌─────────────┐  ┌─────────────┐ ┌────┐ │
│ │ Scheduler-1 │  │ Scheduler-2 │ │... │ │
│ │ (Leader)    │  │ (Follower)  │ │    │ │
│ │             │  │             │ │    │ │
│ │- Cron Parse │  │- Standby    │ │    │ │
│ │- Job Queue  │  │- Health     │ │    │ │
│ │- DAG Exec   │  │- Sync       │ │    │ │
│ └─────────────┘  └─────────────┘ └────┘ │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│           Message Queue                 │
│                                         │
│ ┌───────────────────────────────────────┐ │
│ │             Redis Cluster             │ │
│ │                                       │ │
│ │ Priority Queues:                      │ │
│ │ ┌─────────┐ ┌─────────┐ ┌─────────┐   │ │
│ │ │Critical │ │ High    │ │ Normal  │   │ │
│ │ │Queue    │ │ Queue   │ │ Queue   │   │ │
│ │ └─────────┘ └─────────┘ └─────────┘   │ │
│ └───────────────────────────────────────┘ │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│            Worker Cluster               │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │Worker-1 │  │Worker-2 │  │Worker-N │   │
│ │         │  │         │  │         │   │
│ │- Executor│ │- Executor│ │- Executor│  │
│ │- Monitor │ │- Monitor │ │- Monitor │  │
│ │- Report │  │- Report │  │- Report │  │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│         Supporting Services             │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │Log      │  │Metrics  │  │Alert    │   │
│ │Collector│  │Collector│  │Manager  │   │
│ │         │  │         │  │         │   │
│ │- Stream │  │- Stats  │  │- Rules  │   │
│ │- Store  │  │- Graphs │  │- Notify │   │
│ │- Search │  │- Alerts │  │- Escalate│  │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────┐
│               Data Layer                │
│                                         │
│ ┌─────────┐  ┌─────────┐  ┌─────────┐   │
│ │PostgreSQL│  │Elasticsearch│ │ InfluxDB│  │
│ │         │  │            │ │        │  │
│ │- Jobs   │  │- Logs      │ │- Metrics│  │
│ │- History│  │- Search    │ │- Time   │  │
│ │- Config │  │- Analysis  │ │- Series │  │
│ └─────────┘  └─────────┘  └─────────┘   │
└─────────────────────────────────────────┘
```

### Key Components:
- **Scheduler Cluster**: Cron parsing, job queuing, DAG execution
- **Message Queue**: Priority-based job distribution
- **Worker Cluster**: Distributed job execution with resource limits
- **Log/Metrics Collection**: Centralized observability

---

## Phase 4: Database Design (8 minutes)

### PostgreSQL Schema:
```sql
-- Job definitions
CREATE TABLE jobs (
    job_id UUID PRIMARY KEY,
    job_name VARCHAR(300) NOT NULL,
    project VARCHAR(100) NOT NULL,
    
    -- Scheduling
    cron_expression VARCHAR(100),         -- "0 */6 * * *" for every 6 hours
    timezone VARCHAR(50) DEFAULT 'UTC',
    is_active BOOLEAN DEFAULT true,
    
    -- Execution details
    executor_type VARCHAR(50) NOT NULL,   -- http, shell, python, docker
    command TEXT NOT NULL,               -- URL, script, or container image
    environment JSONB DEFAULT '{}',      -- env vars, secrets, config
    
    -- Resource limits
    timeout_seconds INTEGER DEFAULT 3600,
    memory_limit_mb INTEGER DEFAULT 512,
    cpu_limit DECIMAL(4,2) DEFAULT 1.0,  -- CPU cores
    
    -- Dependencies
    depends_on UUID[] DEFAULT '{}',       -- Array of upstream job_ids
    
    -- Retry policy
    max_retries INTEGER DEFAULT 3,
    retry_delay_seconds INTEGER DEFAULT 60,
    retry_backoff_multiplier DECIMAL(3,2) DEFAULT 2.0,
    
    -- Priority and concurrency
    priority INTEGER DEFAULT 5,          -- 1=highest, 10=lowest
    max_concurrent_runs INTEGER DEFAULT 1,
    
    -- Metadata
    description TEXT,
    tags TEXT[] DEFAULT '{}',
    owner_email VARCHAR(255),
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_jobs_active_cron (is_active, cron_expression),
    INDEX idx_jobs_project (project, is_active),
    INDEX idx_jobs_priority (priority, is_active)
);

-- Job execution history
CREATE TABLE job_executions (
    execution_id UUID PRIMARY KEY,
    job_id UUID REFERENCES jobs(job_id),
    
    -- Execution context
    scheduled_time TIMESTAMP NOT NULL,   -- When it was supposed to run
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    
    -- Execution details
    worker_node VARCHAR(100),            -- Which worker executed it
    execution_status VARCHAR(30) DEFAULT 'queued',
    exit_code INTEGER,
    
    -- Resource usage
    cpu_time_seconds INTEGER,
    memory_used_mb INTEGER,
    
    -- Retry information
    attempt_number INTEGER DEFAULT 1,
    retry_of UUID REFERENCES job_executions(execution_id),
    
    -- Failure handling
    error_message TEXT,
    stack_trace TEXT,
    
    -- Logs reference
    log_file_path TEXT,
    log_size_bytes BIGINT DEFAULT 0,
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_executions_job_scheduled (job_id, scheduled_time DESC),
    INDEX idx_executions_status_time (execution_status, scheduled_time),
    INDEX idx_executions_worker (worker_node, started_at DESC)
);

-- Job dependencies tracking
CREATE TABLE job_dependencies (
    dependency_id UUID PRIMARY KEY,
    downstream_job_id UUID REFERENCES jobs(job_id),
    upstream_job_id UUID REFERENCES jobs(job_id),
    
    -- Dependency rules
    dependency_type VARCHAR(30) DEFAULT 'success', -- success, failure, complete
    time_offset_minutes INTEGER DEFAULT 0,        -- Run X minutes after upstream
    
    created_at TIMESTAMP DEFAULT NOW(),
    
    UNIQUE(downstream_job_id, upstream_job_id),
    INDEX idx_dependencies_downstream (downstream_job_id),
    INDEX idx_dependencies_upstream (upstream_job_id)
);

-- Worker nodes registry
CREATE TABLE worker_nodes (
    worker_id VARCHAR(100) PRIMARY KEY,
    
    -- Node information
    hostname VARCHAR(255) NOT NULL,
    ip_address INET,
    
    -- Capacity
    max_concurrent_jobs INTEGER DEFAULT 10,
    current_job_count INTEGER DEFAULT 0,
    
    -- Resources
    total_cpu_cores INTEGER,
    total_memory_mb INTEGER,
    available_cpu_cores DECIMAL(4,2),
    available_memory_mb INTEGER,
    
    -- Status
    worker_status VARCHAR(20) DEFAULT 'active', -- active, draining, offline
    last_heartbeat TIMESTAMP DEFAULT NOW(),
    
    -- Capabilities
    supported_executors TEXT[] DEFAULT '{"shell", "http"}',
    
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    INDEX idx_workers_status_heartbeat (worker_status, last_heartbeat DESC),
    INDEX idx_workers_capacity (current_job_count, max_concurrent_jobs)
);
```

### Redis Queue Structure:
```javascript
// Priority queues for job execution
"job_queue:critical": [
  {
    execution_id: "uuid",
    job_id: "uuid", 
    scheduled_time: 1704110400,
    priority: 1,
    timeout: 3600,
    resource_requirements: {cpu: 2.0, memory: 1024}
  }
]

"job_queue:high": [...],
"job_queue:normal": [...],
"job_queue:low": [...]

// Worker heartbeat and status
"worker_heartbeat:{worker_id}": {
  status: "active",
  current_jobs: 3,
  max_jobs: 10,
  last_seen: 1704110400,
  available_resources: {cpu: 6.0, memory: 4096}
}

// Job execution locks (prevent duplicate runs)
"job_lock:{job_id}:{scheduled_time}": {
  execution_id: "uuid",
  worker_id: "worker-123",
  locked_at: 1704110400,
  ttl: 7200  // 2 hours max job duration
}

// DAG execution state
"dag_execution:{dag_run_id}": {
  jobs: [
    {job_id: "uuid1", status: "completed", execution_id: "uuid"},
    {job_id: "uuid2", status: "running", execution_id: "uuid"},
    {job_id: "uuid3", status: "waiting", execution_id: null}
  ],
  started_at: 1704110400,
  status: "running"
}
```

---

## Phase 5: Critical Flow - Job Scheduling & Execution (8 minutes)

### Step-by-Step Flow:
```
1. Cron evaluation (every minute):
   - Scheduler scans active jobs with cron expressions
   - Parse cron expressions for current minute
   - Check dependencies and previous execution status
   - Create execution records for jobs due to run

2. Job queuing:
   - Evaluate job dependencies (DAG analysis)
   - Check resource requirements and constraints
   - Add job to appropriate priority queue in Redis
   - Update execution status to 'queued'

3. Worker job pickup:
   - Workers poll priority queues (critical → high → normal → low)
   - Check resource availability (CPU, memory limits)
   - Acquire distributed lock to prevent duplicate execution
   - Update execution status to 'running'

4. Job execution:
   - Worker starts job based on executor type:
     * HTTP: Make REST API calls with retry logic
     * Shell: Execute command with environment variables  
     * Docker: Run containerized workload
   - Monitor resource usage (CPU, memory)
   - Stream logs to centralized logging system
   - Handle timeout and cancellation

5. Completion handling:
   - Update execution status (success/failure)
   - Record resource usage and performance metrics
   - Trigger downstream dependent jobs if successful
   - Handle retry logic for failed jobs
   - Release worker resources and capacity
```

### Technical Challenges:
**Distributed Locking**: "Prevent duplicate job execution across multiple schedulers"
**DAG Management**: "Resolve complex dependencies and handle circular references"
**Resource Management**: "Fair resource allocation and prevent resource starvation"
**Failure Recovery**: "Handle worker failures and job orphaning gracefully"

---

## Phase 6: Scaling & Bottlenecks (2 minutes)

### Main Bottlenecks:
1. **Scheduler throughput** - Cron evaluation and job queuing
2. **Queue latency** - Job distribution to available workers
3. **Database contention** - High-frequency status updates
4. **Log storage** - Large volume log aggregation

### Scaling Solutions:
**Scheduler Scaling**:
- Multi-master with leader election
- Horizontal partitioning by job hash
- Caching of job definitions and schedules

**Queue Optimization**:
- Redis cluster for queue distribution  
- Priority-based queuing with multiple tiers
- Batch job operations for efficiency

**Database Performance**:
- Read replicas for job history queries
- Partitioning by time for execution history
- Background cleanup of old execution records

**Worker Scaling**:
- Auto-scaling based on queue depth
- Resource-aware job placement
- Graceful node draining for maintenance

### Trade-offs:
- **Consistency vs Performance**: Strong scheduling guarantees vs high throughput
- **Resource Isolation vs Efficiency**: Container overhead vs native execution
- **Monitoring Detail vs Storage**: Detailed metrics vs storage costs

---

## Success Metrics:
- **Scheduling Accuracy**: >99.9% jobs start within 1 minute of schedule
- **Job Success Rate**: >95% jobs complete successfully on first attempt
- **Worker Utilization**: >80% average resource utilization across cluster
- **Recovery Time**: <5 minutes for scheduler failover
- **Queue Latency**: <30 seconds average time from queue to execution

**🎯 Demonstrates distributed systems expertise, job scheduling algorithms, resource management, and building mission-critical infrastructure for data processing and automation.**




