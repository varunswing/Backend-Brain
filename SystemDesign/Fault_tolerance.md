# Fault Tolerance in Distributed Systems

## Overview
Fault tolerance is the ability of a system to continue operating correctly even when some of its components fail. In distributed systems, failures are not exceptional cases but normal occurrences that must be designed for from the beginning.

## Understanding Failures

### Types of Failures

#### 1. Hardware Failures
**Examples**:
- Server crashes
- Disk failures
- Network interface failures
- Power outages
- Memory corruption

**Characteristics**:
- Often permanent until repaired
- Can be detected relatively easily
- May cause data loss if not handled properly

#### 2. Software Failures
**Examples**:
- Application bugs
- Memory leaks
- Deadlocks
- Configuration errors
- Resource exhaustion

**Characteristics**:
- Can be transient or permanent
- May be harder to detect
- Often correlated across similar systems

#### 3. Network Failures
**Examples**:
- Network partitions
- High latency
- Packet loss
- DNS failures
- Load balancer failures

**Characteristics**:
- Often transient
- Can cause split-brain scenarios
- May appear as other types of failures

#### 4. Human Errors
**Examples**:
- Misconfigurations
- Accidental deletions
- Deployment errors
- Security breaches

**Characteristics**:
- Often the most common cause of outages
- Can have widespread impact
- Prevention through automation and processes

### Failure Models

#### 1. Fail-Stop Model
```python
class FailStopNode:
    def __init__(self):
        self.is_running = True
    
    def process_request(self, request):
        if not self.is_running:
            # Node has stopped, no response
            return None
        
        try:
            return self.handle_request(request)
        except CriticalError:
            # Node detects critical error and stops
            self.is_running = False
            self.shutdown()
            return None
```

**Characteristics**:
- Node either works correctly or stops completely
- Failures are detectable (no response)
- Simplest model to reason about

#### 2. Fail-Recovery Model
```python
class FailRecoveryNode:
    def __init__(self):
        self.state = "RUNNING"
        self.persistent_state = {}
    
    def process_request(self, request):
        if self.state == "FAILED":
            return None
        
        try:
            result = self.handle_request(request)
            self.save_state()
            return result
        except Exception as e:
            self.state = "FAILED"
            self.initiate_recovery()
            return None
    
    def initiate_recovery(self):
        # Attempt to recover from persistent state
        self.restore_state()
        self.state = "RUNNING"
```

**Characteristics**:
- Node can fail and later recover
- May lose some in-memory state
- Requires persistent state management

#### 3. Byzantine Failure Model
```python
class ByzantineNode:
    def __init__(self, is_malicious=False):
        self.is_malicious = is_malicious
    
    def process_request(self, request):
        if self.is_malicious:
            # May return incorrect results
            return self.generate_malicious_response(request)
        
        try:
            return self.handle_request(request)
        except Exception:
            # May return arbitrary responses
            return self.generate_arbitrary_response()
```

**Characteristics**:
- Node may behave arbitrarily
- Can send conflicting information to different nodes
- Most complex model, requires sophisticated protocols

## Fault Tolerance Strategies

### 1. Redundancy

#### Hardware Redundancy
```python
class RedundantStorage:
    def __init__(self, replicas=3):
        self.replicas = replicas
        self.storage_nodes = [StorageNode(i) for i in range(replicas)]
    
    def write_data(self, key, value):
        successful_writes = 0
        
        for node in self.storage_nodes:
            try:
                node.write(key, value)
                successful_writes += 1
            except Exception as e:
                logger.warning(f"Write failed on node {node.id}: {e}")
        
        # Require majority of writes to succeed
        if successful_writes >= (self.replicas // 2) + 1:
            return True
        else:
            raise WriteFailureError("Insufficient replicas wrote data")
    
    def read_data(self, key):
        responses = []
        
        for node in self.storage_nodes:
            try:
                value = node.read(key)
                responses.append(value)
            except Exception as e:
                logger.warning(f"Read failed on node {node.id}: {e}")
        
        if not responses:
            raise ReadFailureError("No replicas available")
        
        # Return most common value (simple majority)
        return max(set(responses), key=responses.count)
```

#### Software Redundancy
```python
class RedundantService:
    def __init__(self, service_instances):
        self.instances = service_instances
        self.current_primary = 0
    
    def call_service(self, request):
        # Try primary instance first
        try:
            return self.instances[self.current_primary].process(request)
        except Exception as e:
            logger.warning(f"Primary instance failed: {e}")
            
            # Try backup instances
            for i, instance in enumerate(self.instances):
                if i == self.current_primary:
                    continue
                
                try:
                    result = instance.process(request)
                    # Switch primary to working instance
                    self.current_primary = i
                    return result
                except Exception as backup_error:
                    logger.warning(f"Backup instance {i} failed: {backup_error}")
            
            raise ServiceUnavailableError("All service instances failed")
```

### 2. Replication

#### Active Replication
```python
class ActiveReplication:
    def __init__(self, replicas):
        self.replicas = replicas
    
    def execute_operation(self, operation):
        results = []
        
        # Send operation to all replicas
        for replica in self.replicas:
            try:
                result = replica.execute(operation)
                results.append(result)
            except Exception as e:
                logger.error(f"Replica {replica.id} failed: {e}")
        
        if not results:
            raise OperationFailedError("All replicas failed")
        
        # Use majority voting for result
        if len(results) >= (len(self.replicas) // 2) + 1:
            return self.majority_vote(results)
        else:
            raise InsufficientReplicasError("Not enough replicas responded")
    
    def majority_vote(self, results):
        # Return most common result
        return max(set(results), key=results.count)
```

#### Passive Replication (Primary-Backup)
```python
class PrimaryBackupReplication:
    def __init__(self, primary, backups):
        self.primary = primary
        self.backups = backups
        self.primary_failed = False
    
    def execute_operation(self, operation):
        if not self.primary_failed:
            try:
                # Execute on primary
                result = self.primary.execute(operation)
                
                # Replicate to backups asynchronously
                self.replicate_to_backups(operation, result)
                
                return result
            except Exception as e:
                logger.error(f"Primary failed: {e}")
                self.primary_failed = True
                self.promote_backup()
        
        # If primary failed, try backups
        for backup in self.backups:
            try:
                return backup.execute(operation)
            except Exception as e:
                logger.error(f"Backup {backup.id} failed: {e}")
        
        raise AllReplicasFailedError("Primary and all backups failed")
    
    def replicate_to_backups(self, operation, result):
        for backup in self.backups:
            try:
                backup.replicate(operation, result)
            except Exception as e:
                logger.warning(f"Replication to backup {backup.id} failed: {e}")
```

### 3. Checkpointing and Recovery

#### State Checkpointing
```python
class CheckpointManager:
    def __init__(self, checkpoint_interval=60):
        self.checkpoint_interval = checkpoint_interval
        self.last_checkpoint = time.time()
        self.checkpoint_storage = CheckpointStorage()
    
    def should_checkpoint(self):
        return time.time() - self.last_checkpoint > self.checkpoint_interval
    
    def create_checkpoint(self, service_state):
        if self.should_checkpoint():
            checkpoint_id = self.generate_checkpoint_id()
            
            try:
                self.checkpoint_storage.save_checkpoint(checkpoint_id, service_state)
                self.last_checkpoint = time.time()
                logger.info(f"Checkpoint {checkpoint_id} created successfully")
                return checkpoint_id
            except Exception as e:
                logger.error(f"Failed to create checkpoint: {e}")
                raise CheckpointError(f"Checkpoint creation failed: {e}")
    
    def restore_from_checkpoint(self, checkpoint_id=None):
        if checkpoint_id is None:
            checkpoint_id = self.checkpoint_storage.get_latest_checkpoint_id()
        
        try:
            service_state = self.checkpoint_storage.load_checkpoint(checkpoint_id)
            logger.info(f"Restored from checkpoint {checkpoint_id}")
            return service_state
        except Exception as e:
            logger.error(f"Failed to restore from checkpoint {checkpoint_id}: {e}")
            raise RecoveryError(f"Checkpoint restoration failed: {e}")

class StatefulService:
    def __init__(self):
        self.state = {}
        self.checkpoint_manager = CheckpointManager()
    
    def process_request(self, request):
        try:
            # Process the request
            result = self.handle_request(request)
            
            # Update state
            self.update_state(request, result)
            
            # Create checkpoint if needed
            self.checkpoint_manager.create_checkpoint(self.state)
            
            return result
        except Exception as e:
            logger.error(f"Request processing failed: {e}")
            # Attempt recovery
            self.recover_from_failure()
            raise e
    
    def recover_from_failure(self):
        try:
            # Restore from latest checkpoint
            self.state = self.checkpoint_manager.restore_from_checkpoint()
            logger.info("Service recovered from checkpoint")
        except RecoveryError:
            # If checkpoint recovery fails, start with clean state
            self.state = {}
            logger.warning("Started with clean state due to recovery failure")
```

### 4. Circuit Breaker Pattern

```python
import time
import threading
from enum import Enum

class CircuitState(Enum):
    CLOSED = "CLOSED"
    OPEN = "OPEN"
    HALF_OPEN = "HALF_OPEN"

class CircuitBreaker:
    def __init__(self, failure_threshold=5, recovery_timeout=60, expected_exception=Exception):
        self.failure_threshold = failure_threshold
        self.recovery_timeout = recovery_timeout
        self.expected_exception = expected_exception
        
        self.failure_count = 0
        self.last_failure_time = None
        self.state = CircuitState.CLOSED
        self.lock = threading.Lock()
    
    def call(self, func, *args, **kwargs):
        with self.lock:
            if self.state == CircuitState.OPEN:
                if self._should_attempt_reset():
                    self.state = CircuitState.HALF_OPEN
                else:
                    raise CircuitBreakerOpenError("Circuit breaker is OPEN")
        
        try:
            result = func(*args, **kwargs)
            self._on_success()
            return result
        except self.expected_exception as e:
            self._on_failure()
            raise e
    
    def _should_attempt_reset(self):
        return (time.time() - self.last_failure_time) >= self.recovery_timeout
    
    def _on_success(self):
        with self.lock:
            self.failure_count = 0
            self.state = CircuitState.CLOSED
    
    def _on_failure(self):
        with self.lock:
            self.failure_count += 1
            self.last_failure_time = time.time()
            
            if self.failure_count >= self.failure_threshold:
                self.state = CircuitState.OPEN

# Usage example
class ExternalServiceClient:
    def __init__(self):
        self.circuit_breaker = CircuitBreaker(failure_threshold=3, recovery_timeout=30)
    
    def call_external_service(self, data):
        return self.circuit_breaker.call(self._make_api_call, data)
    
    def _make_api_call(self, data):
        # Simulate external API call
        response = requests.post("https://external-api.com/endpoint", json=data)
        response.raise_for_status()
        return response.json()
```

### 5. Bulkhead Pattern

```python
class ResourcePool:
    def __init__(self, pool_size, resource_factory):
        self.pool_size = pool_size
        self.resource_factory = resource_factory
        self.available_resources = queue.Queue(maxsize=pool_size)
        self.in_use_resources = set()
        
        # Initialize pool
        for _ in range(pool_size):
            resource = resource_factory()
            self.available_resources.put(resource)
    
    def acquire_resource(self, timeout=5):
        try:
            resource = self.available_resources.get(timeout=timeout)
            self.in_use_resources.add(resource)
            return resource
        except queue.Empty:
            raise ResourceExhaustionError("No resources available")
    
    def release_resource(self, resource):
        if resource in self.in_use_resources:
            self.in_use_resources.remove(resource)
            self.available_resources.put(resource)

class BulkheadService:
    def __init__(self):
        # Separate resource pools for different operations
        self.critical_pool = ResourcePool(10, self.create_database_connection)
        self.non_critical_pool = ResourcePool(5, self.create_database_connection)
        self.background_pool = ResourcePool(3, self.create_database_connection)
    
    def handle_critical_request(self, request):
        resource = self.critical_pool.acquire_resource()
        try:
            return self.process_critical_request(request, resource)
        finally:
            self.critical_pool.release_resource(resource)
    
    def handle_non_critical_request(self, request):
        resource = self.non_critical_pool.acquire_resource()
        try:
            return self.process_non_critical_request(request, resource)
        finally:
            self.non_critical_pool.release_resource(resource)
    
    def handle_background_task(self, task):
        resource = self.background_pool.acquire_resource()
        try:
            return self.process_background_task(task, resource)
        finally:
            self.background_pool.release_resource(resource)
```

### 6. Retry Patterns

#### Exponential Backoff with Jitter
```python
import random
import time

class RetryPolicy:
    def __init__(self, max_retries=3, base_delay=1, max_delay=60, jitter=True):
        self.max_retries = max_retries
        self.base_delay = base_delay
        self.max_delay = max_delay
        self.jitter = jitter
    
    def execute(self, func, *args, **kwargs):
        last_exception = None
        
        for attempt in range(self.max_retries + 1):
            try:
                return func(*args, **kwargs)
            except Exception as e:
                last_exception = e
                
                if attempt == self.max_retries:
                    break
                
                delay = self._calculate_delay(attempt)
                logger.warning(f"Attempt {attempt + 1} failed: {e}. Retrying in {delay}s")
                time.sleep(delay)
        
        raise RetryExhaustedException(f"All {self.max_retries + 1} attempts failed") from last_exception
    
    def _calculate_delay(self, attempt):
        # Exponential backoff: base_delay * 2^attempt
        delay = self.base_delay * (2 ** attempt)
        delay = min(delay, self.max_delay)
        
        if self.jitter:
            # Add random jitter to avoid thundering herd
            jitter_range = delay * 0.1
            delay += random.uniform(-jitter_range, jitter_range)
        
        return max(0, delay)

# Usage with different retry policies
class ResilientService:
    def __init__(self):
        self.fast_retry = RetryPolicy(max_retries=2, base_delay=0.1, max_delay=1)
        self.standard_retry = RetryPolicy(max_retries=3, base_delay=1, max_delay=30)
        self.slow_retry = RetryPolicy(max_retries=5, base_delay=5, max_delay=300)
    
    def call_fast_service(self, request):
        return self.fast_retry.execute(self._call_fast_external_service, request)
    
    def call_standard_service(self, request):
        return self.standard_retry.execute(self._call_standard_external_service, request)
    
    def call_slow_service(self, request):
        return self.slow_retry.execute(self._call_slow_external_service, request)
```

#### Retry with Circuit Breaker
```python
class RetryWithCircuitBreaker:
    def __init__(self, retry_policy, circuit_breaker):
        self.retry_policy = retry_policy
        self.circuit_breaker = circuit_breaker
    
    def execute(self, func, *args, **kwargs):
        def wrapped_func():
            return self.circuit_breaker.call(func, *args, **kwargs)
        
        return self.retry_policy.execute(wrapped_func)

# Usage
service_client = ExternalServiceClient()
retry_policy = RetryPolicy(max_retries=3, base_delay=1)
circuit_breaker = CircuitBreaker(failure_threshold=5, recovery_timeout=60)

resilient_caller = RetryWithCircuitBreaker(retry_policy, circuit_breaker)

def call_external_service_safely(data):
    return resilient_caller.execute(service_client.call_external_service, data)
```

## Failure Detection

### 1. Heartbeat Mechanism
```python
class HeartbeatMonitor:
    def __init__(self, heartbeat_interval=30, failure_threshold=3):
        self.heartbeat_interval = heartbeat_interval
        self.failure_threshold = failure_threshold
        self.nodes = {}
        self.running = True
    
    def register_node(self, node_id, node_endpoint):
        self.nodes[node_id] = {
            'endpoint': node_endpoint,
            'last_heartbeat': time.time(),
            'missed_heartbeats': 0,
            'status': 'HEALTHY'
        }
    
    def receive_heartbeat(self, node_id):
        if node_id in self.nodes:
            self.nodes[node_id]['last_heartbeat'] = time.time()
            self.nodes[node_id]['missed_heartbeats'] = 0
            if self.nodes[node_id]['status'] == 'FAILED':
                self.nodes[node_id]['status'] = 'HEALTHY'
                self.on_node_recovery(node_id)
    
    def start_monitoring(self):
        while self.running:
            self.check_node_health()
            time.sleep(self.heartbeat_interval)
    
    def check_node_health(self):
        current_time = time.time()
        
        for node_id, node_info in self.nodes.items():
            time_since_heartbeat = current_time - node_info['last_heartbeat']
            
            if time_since_heartbeat > self.heartbeat_interval:
                node_info['missed_heartbeats'] += 1
                
                if node_info['missed_heartbeats'] >= self.failure_threshold:
                    if node_info['status'] != 'FAILED':
                        node_info['status'] = 'FAILED'
                        self.on_node_failure(node_id)
    
    def on_node_failure(self, node_id):
        logger.error(f"Node {node_id} detected as failed")
        # Trigger failure handling logic
        self.handle_node_failure(node_id)
    
    def on_node_recovery(self, node_id):
        logger.info(f"Node {node_id} recovered")
        # Trigger recovery logic
        self.handle_node_recovery(node_id)
```

### 2. Health Checks
```python
class HealthChecker:
    def __init__(self):
        self.health_checks = {}
    
    def register_health_check(self, name, check_function, timeout=5):
        self.health_checks[name] = {
            'function': check_function,
            'timeout': timeout
        }
    
    def check_health(self):
        health_status = {
            'overall_status': 'HEALTHY',
            'checks': {},
            'timestamp': time.time()
        }
        
        for check_name, check_config in self.health_checks.items():
            try:
                # Run health check with timeout
                result = self.run_with_timeout(
                    check_config['function'],
                    check_config['timeout']
                )
                
                health_status['checks'][check_name] = {
                    'status': 'HEALTHY',
                    'details': result
                }
            except Exception as e:
                health_status['checks'][check_name] = {
                    'status': 'UNHEALTHY',
                    'error': str(e)
                }
                health_status['overall_status'] = 'UNHEALTHY'
        
        return health_status
    
    def run_with_timeout(self, func, timeout):
        import signal
        
        def timeout_handler(signum, frame):
            raise TimeoutError("Health check timed out")
        
        signal.signal(signal.SIGALRM, timeout_handler)
        signal.alarm(timeout)
        
        try:
            result = func()
            signal.alarm(0)  # Cancel the alarm
            return result
        except Exception as e:
            signal.alarm(0)  # Cancel the alarm
            raise e

# Usage
health_checker = HealthChecker()

def check_database_connection():
    # Check if database is accessible
    db.execute("SELECT 1")
    return "Database connection OK"

def check_external_service():
    # Check if external service is accessible
    response = requests.get("https://external-service.com/health", timeout=3)
    response.raise_for_status()
    return "External service OK"

health_checker.register_health_check("database", check_database_connection, timeout=5)
health_checker.register_health_check("external_service", check_external_service, timeout=10)
```

### 3. Timeout Mechanisms
```python
class TimeoutManager:
    def __init__(self):
        self.default_timeout = 30
        self.operation_timeouts = {}
    
    def set_timeout(self, operation_name, timeout):
        self.operation_timeouts[operation_name] = timeout
    
    def execute_with_timeout(self, operation_name, func, *args, **kwargs):
        timeout = self.operation_timeouts.get(operation_name, self.default_timeout)
        
        return self.run_with_timeout(func, timeout, *args, **kwargs)
    
    def run_with_timeout(self, func, timeout, *args, **kwargs):
        import concurrent.futures
        
        with concurrent.futures.ThreadPoolExecutor() as executor:
            future = executor.submit(func, *args, **kwargs)
            
            try:
                result = future.result(timeout=timeout)
                return result
            except concurrent.futures.TimeoutError:
                # Cancel the future (best effort)
                future.cancel()
                raise OperationTimeoutError(f"Operation timed out after {timeout} seconds")

# Usage
timeout_manager = TimeoutManager()
timeout_manager.set_timeout("database_query", 10)
timeout_manager.set_timeout("external_api_call", 30)

def execute_database_query(query):
    return timeout_manager.execute_with_timeout(
        "database_query",
        database.execute,
        query
    )
```

## Recovery Strategies

### 1. Graceful Degradation
```python
class GracefulDegradationService:
    def __init__(self):
        self.primary_service = PrimaryService()
        self.fallback_service = FallbackService()
        self.cache = Cache()
    
    def get_user_recommendations(self, user_id):
        try:
            # Try primary recommendation service
            return self.primary_service.get_recommendations(user_id)
        except Exception as e:
            logger.warning(f"Primary recommendation service failed: {e}")
            
            try:
                # Try fallback service with simpler algorithm
                return self.fallback_service.get_basic_recommendations(user_id)
            except Exception as e2:
                logger.warning(f"Fallback recommendation service failed: {e2}")
                
                # Return cached recommendations or default
                cached_recommendations = self.cache.get(f"recommendations:{user_id}")
                if cached_recommendations:
                    return cached_recommendations
                
                # Return default recommendations
                return self.get_default_recommendations()
    
    def get_default_recommendations(self):
        return [
            {"id": "popular_item_1", "title": "Popular Item 1"},
            {"id": "popular_item_2", "title": "Popular Item 2"},
            {"id": "popular_item_3", "title": "Popular Item 3"}
        ]
```

### 2. Automatic Failover
```python
class AutomaticFailover:
    def __init__(self, primary_service, backup_services):
        self.primary_service = primary_service
        self.backup_services = backup_services
        self.current_service = primary_service
        self.health_checker = HealthChecker()
        self.failover_in_progress = False
    
    def execute_request(self, request):
        if not self.failover_in_progress and not self.is_service_healthy(self.current_service):
            self.initiate_failover()
        
        try:
            return self.current_service.process_request(request)
        except Exception as e:
            logger.error(f"Request failed on current service: {e}")
            
            if not self.failover_in_progress:
                self.initiate_failover()
                return self.current_service.process_request(request)
            else:
                raise ServiceUnavailableError("All services are unavailable")
    
    def initiate_failover(self):
        self.failover_in_progress = True
        logger.info("Initiating failover...")
        
        # Try backup services in order
        for backup_service in self.backup_services:
            if self.is_service_healthy(backup_service):
                logger.info(f"Failing over to backup service: {backup_service}")
                self.current_service = backup_service
                self.failover_in_progress = False
                return
        
        # If no backup services are healthy, keep current service
        logger.error("No healthy backup services found")
        self.failover_in_progress = False
    
    def is_service_healthy(self, service):
        try:
            service.health_check()
            return True
        except Exception:
            return False
```

### 3. Data Recovery
```python
class DataRecoveryManager:
    def __init__(self, primary_storage, backup_storages):
        self.primary_storage = primary_storage
        self.backup_storages = backup_storages
    
    def recover_data(self, key):
        # Try primary storage first
        try:
            return self.primary_storage.get(key)
        except Exception as e:
            logger.warning(f"Primary storage failed for key {key}: {e}")
        
        # Try backup storages
        for i, backup_storage in enumerate(self.backup_storages):
            try:
                data = backup_storage.get(key)
                logger.info(f"Data recovered from backup storage {i} for key {key}")
                
                # Attempt to repair primary storage
                self.repair_primary_storage(key, data)
                
                return data
            except Exception as e:
                logger.warning(f"Backup storage {i} failed for key {key}: {e}")
        
        raise DataNotFoundError(f"Data not found in any storage for key {key}")
    
    def repair_primary_storage(self, key, data):
        try:
            self.primary_storage.set(key, data)
            logger.info(f"Primary storage repaired for key {key}")
        except Exception as e:
            logger.error(f"Failed to repair primary storage for key {key}: {e}")
    
    def verify_data_integrity(self, key):
        primary_data = None
        backup_data = []
        
        # Get data from all sources
        try:
            primary_data = self.primary_storage.get(key)
        except Exception:
            pass
        
        for backup_storage in self.backup_storages:
            try:
                data = backup_storage.get(key)
                backup_data.append(data)
            except Exception:
                pass
        
        # Check for inconsistencies
        all_data = [primary_data] + backup_data
        all_data = [data for data in all_data if data is not None]
        
        if len(set(all_data)) > 1:
            logger.warning(f"Data inconsistency detected for key {key}")
            return False
        
        return True
```

## Testing Fault Tolerance

### 1. Chaos Engineering
```python
class ChaosMonkey:
    def __init__(self, services):
        self.services = services
        self.chaos_scenarios = []
    
    def add_chaos_scenario(self, scenario):
        self.chaos_scenarios.append(scenario)
    
    def run_chaos_test(self, duration=300):  # 5 minutes
        start_time = time.time()
        
        while time.time() - start_time < duration:
            # Randomly select and execute chaos scenario
            scenario = random.choice(self.chaos_scenarios)
            
            try:
                logger.info(f"Executing chaos scenario: {scenario.name}")
                scenario.execute()
                
                # Wait before next chaos event
                time.sleep(random.randint(30, 120))
                
            except Exception as e:
                logger.error(f"Chaos scenario failed: {e}")

class NetworkPartitionScenario:
    def __init__(self, name, target_services):
        self.name = name
        self.target_services = target_services
    
    def execute(self):
        # Simulate network partition by blocking communication
        for service in self.target_services:
            service.block_network_communication()
        
        # Wait for some time
        time.sleep(60)
        
        # Restore communication
        for service in self.target_services:
            service.restore_network_communication()

class ServiceCrashScenario:
    def __init__(self, name, target_service):
        self.name = name
        self.target_service = target_service
    
    def execute(self):
        # Crash the service
        self.target_service.crash()
        
        # Wait for automatic recovery
        time.sleep(30)
        
        # If not recovered automatically, restart
        if not self.target_service.is_running():
            self.target_service.restart()
```

### 2. Fault Injection Testing
```python
class FaultInjector:
    def __init__(self):
        self.active_faults = {}
    
    def inject_latency(self, service_name, latency_ms, duration=60):
        fault_id = f"{service_name}_latency_{int(time.time())}"
        
        self.active_faults[fault_id] = {
            'type': 'latency',
            'service': service_name,
            'latency_ms': latency_ms,
            'end_time': time.time() + duration
        }
        
        return fault_id
    
    def inject_error_rate(self, service_name, error_rate, duration=60):
        fault_id = f"{service_name}_error_{int(time.time())}"
        
        self.active_faults[fault_id] = {
            'type': 'error_rate',
            'service': service_name,
            'error_rate': error_rate,
            'end_time': time.time() + duration
        }
        
        return fault_id
    
    def should_inject_fault(self, service_name, operation_type):
        current_time = time.time()
        
        # Clean up expired faults
        expired_faults = [
            fault_id for fault_id, fault in self.active_faults.items()
            if fault['end_time'] < current_time
        ]
        
        for fault_id in expired_faults:
            del self.active_faults[fault_id]
        
        # Check for active faults
        for fault in self.active_faults.values():
            if fault['service'] == service_name:
                if fault['type'] == 'latency':
                    time.sleep(fault['latency_ms'] / 1000.0)
                elif fault['type'] == 'error_rate':
                    if random.random() < fault['error_rate']:
                        raise InjectedFaultError("Fault injected for testing")
        
        return False

# Usage in service
class ResilientService:
    def __init__(self):
        self.fault_injector = FaultInjector()
    
    def process_request(self, request):
        # Check for fault injection
        self.fault_injector.should_inject_fault("user_service", "process_request")
        
        # Normal processing
        return self.handle_request(request)
```

## Monitoring and Alerting

### 1. Fault Detection Metrics
```python
class FaultToleranceMetrics:
    def __init__(self, metrics_client):
        self.metrics = metrics_client
    
    def record_service_failure(self, service_name, failure_type):
        self.metrics.increment(f"service.{service_name}.failures.{failure_type}")
        self.metrics.increment(f"service.{service_name}.failures.total")
    
    def record_recovery_time(self, service_name, recovery_time_seconds):
        self.metrics.histogram(f"service.{service_name}.recovery_time", recovery_time_seconds)
    
    def record_failover_event(self, from_service, to_service):
        self.metrics.increment(f"failover.{from_service}.to.{to_service}")
        self.metrics.increment("failover.total")
    
    def record_circuit_breaker_state(self, service_name, state):
        self.metrics.gauge(f"circuit_breaker.{service_name}.state", 
                          1 if state == "OPEN" else 0)
    
    def get_service_health_score(self, service_name, time_window=3600):
        # Calculate health score based on recent failures
        total_requests = self.metrics.get_count(
            f"service.{service_name}.requests", time_window
        )
        total_failures = self.metrics.get_count(
            f"service.{service_name}.failures.total", time_window
        )
        
        if total_requests == 0:
            return 1.0  # No requests, assume healthy
        
        success_rate = (total_requests - total_failures) / total_requests
        return max(0.0, success_rate)
```

### 2. Alerting Rules
```python
class FaultToleranceAlerting:
    def __init__(self, metrics_client, alert_manager):
        self.metrics = metrics_client
        self.alert_manager = alert_manager
        self.alert_rules = []
    
    def add_alert_rule(self, rule):
        self.alert_rules.append(rule)
    
    def check_alerts(self):
        for rule in self.alert_rules:
            try:
                if rule.should_alert(self.metrics):
                    self.alert_manager.send_alert(rule.create_alert())
            except Exception as e:
                logger.error(f"Error checking alert rule {rule.name}: {e}")

class HighFailureRateAlert:
    def __init__(self, service_name, threshold=0.05, time_window=300):
        self.name = f"high_failure_rate_{service_name}"
        self.service_name = service_name
        self.threshold = threshold
        self.time_window = time_window
    
    def should_alert(self, metrics):
        total_requests = metrics.get_count(
            f"service.{self.service_name}.requests", self.time_window
        )
        total_failures = metrics.get_count(
            f"service.{self.service_name}.failures.total", self.time_window
        )
        
        if total_requests < 10:  # Not enough data
            return False
        
        failure_rate = total_failures / total_requests
        return failure_rate > self.threshold
    
    def create_alert(self):
        return {
            "severity": "HIGH",
            "service": self.service_name,
            "message": f"High failure rate detected for {self.service_name}",
            "threshold": self.threshold,
            "time_window": self.time_window
        }
```

## Best Practices

### 1. Design Principles
- **Fail Fast**: Detect failures quickly and fail fast rather than hanging
- **Isolation**: Isolate failures to prevent cascade effects
- **Redundancy**: Build redundancy at multiple levels
- **Graceful Degradation**: Provide reduced functionality rather than complete failure
- **Idempotency**: Make operations idempotent to safely retry

### 2. Implementation Guidelines
- **Timeout Everything**: Set timeouts for all external calls
- **Circuit Breakers**: Use circuit breakers for external dependencies
- **Bulkheads**: Isolate resources to prevent resource exhaustion
- **Health Checks**: Implement comprehensive health checks
- **Monitoring**: Monitor all aspects of system health

### 3. Testing Strategies
- **Chaos Engineering**: Regularly inject failures to test resilience
- **Disaster Recovery Drills**: Practice recovery procedures
- **Load Testing**: Test system behavior under stress
- **Fault Injection**: Test specific failure scenarios

## Conclusion

Fault tolerance is essential for building reliable distributed systems:

**Key Strategies**:
- Redundancy and replication for availability
- Circuit breakers and bulkheads for isolation
- Retry mechanisms with exponential backoff
- Comprehensive monitoring and alerting
- Graceful degradation and automatic recovery

**Implementation Considerations**:
- Design for failure from the beginning
- Test failure scenarios regularly
- Monitor system health continuously
- Plan for different types of failures
- Balance consistency with availability

**Success Factors**:
- Clear understanding of failure modes
- Appropriate use of fault tolerance patterns
- Comprehensive testing and monitoring
- Team expertise in distributed systems
- Regular practice of recovery procedures

Remember: Fault tolerance is not about preventing failures, but about handling them gracefully when they occur. The goal is to build systems that continue to provide value even when components fail.
