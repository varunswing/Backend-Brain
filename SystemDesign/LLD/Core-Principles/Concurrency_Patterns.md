# Concurrency Patterns for System Design

## Overview
Concurrency patterns help manage shared resources, prevent race conditions, and ensure thread safety in multi-threaded applications. Essential knowledge for senior developer interviews.

---

## 1. Synchronization Patterns

### Mutex (Mutual Exclusion)
Ensures only one thread can access a resource at a time.

```java
public class Counter {
    private int count = 0;
    private final Object lock = new Object();
    
    public void increment() {
        synchronized (lock) {
            count++;
        }
    }
    
    public int getCount() {
        synchronized (lock) {
            return count;
        }
    }
}
```

### ReentrantLock
More flexible than synchronized, with tryLock and fairness.

```java
public class BankAccount {
    private double balance;
    private final ReentrantLock lock = new ReentrantLock();
    
    public boolean withdraw(double amount) {
        lock.lock();
        try {
            if (balance >= amount) {
                balance -= amount;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }
    
    // Try lock with timeout
    public boolean withdrawWithTimeout(double amount, long timeout) 
            throws InterruptedException {
        if (lock.tryLock(timeout, TimeUnit.MILLISECONDS)) {
            try {
                if (balance >= amount) {
                    balance -= amount;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }
        return false;
    }
}
```

### ReadWriteLock
Allows multiple readers OR single writer.

```java
public class Cache<K, V> {
    private final Map<K, V> cache = new HashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();
    
    public V get(K key) {
        readLock.lock();
        try {
            return cache.get(key);
        } finally {
            readLock.unlock();
        }
    }
    
    public void put(K key, V value) {
        writeLock.lock();
        try {
            cache.put(key, value);
        } finally {
            writeLock.unlock();
        }
    }
}
```

---

## 2. Producer-Consumer Pattern

### Using BlockingQueue
```java
public class ProducerConsumer {
    private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>(100);
    
    // Producer
    public void produce(Task task) throws InterruptedException {
        queue.put(task);  // Blocks if queue is full
    }
    
    // Consumer
    public void consume() throws InterruptedException {
        while (true) {
            Task task = queue.take();  // Blocks if queue is empty
            process(task);
        }
    }
    
    // Multiple consumers
    public void startConsumers(int count) {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        for (int i = 0; i < count; i++) {
            executor.submit(() -> {
                try {
                    consume();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}
```

### Using wait/notify
```java
public class BoundedBuffer<T> {
    private final Queue<T> buffer;
    private final int capacity;
    
    public BoundedBuffer(int capacity) {
        this.buffer = new LinkedList<>();
        this.capacity = capacity;
    }
    
    public synchronized void produce(T item) throws InterruptedException {
        while (buffer.size() == capacity) {
            wait();  // Wait until space available
        }
        buffer.add(item);
        notifyAll();  // Notify consumers
    }
    
    public synchronized T consume() throws InterruptedException {
        while (buffer.isEmpty()) {
            wait();  // Wait until item available
        }
        T item = buffer.poll();
        notifyAll();  // Notify producers
        return item;
    }
}
```

---

## 3. Thread Pool Pattern

### Custom Thread Pool
```java
public class CustomThreadPool {
    private final BlockingQueue<Runnable> taskQueue;
    private final List<WorkerThread> workers;
    private volatile boolean isShutdown = false;
    
    public CustomThreadPool(int poolSize, int queueSize) {
        taskQueue = new LinkedBlockingQueue<>(queueSize);
        workers = new ArrayList<>();
        
        for (int i = 0; i < poolSize; i++) {
            WorkerThread worker = new WorkerThread();
            workers.add(worker);
            worker.start();
        }
    }
    
    public void submit(Runnable task) {
        if (!isShutdown) {
            taskQueue.offer(task);
        }
    }
    
    public void shutdown() {
        isShutdown = true;
        workers.forEach(Thread::interrupt);
    }
    
    private class WorkerThread extends Thread {
        @Override
        public void run() {
            while (!isShutdown) {
                try {
                    Runnable task = taskQueue.take();
                    task.run();
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }
}
```

### Using ExecutorService
```java
// Fixed thread pool
ExecutorService fixedPool = Executors.newFixedThreadPool(10);

// Cached thread pool (scales automatically)
ExecutorService cachedPool = Executors.newCachedThreadPool();

// Scheduled thread pool
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

// Custom thread pool
ThreadPoolExecutor customPool = new ThreadPoolExecutor(
    5,                      // core pool size
    10,                     // max pool size
    60L, TimeUnit.SECONDS,  // keep alive time
    new LinkedBlockingQueue<>(100),  // work queue
    new ThreadPoolExecutor.CallerRunsPolicy()  // rejection policy
);
```

---

## 4. Semaphore Pattern

### Rate Limiting / Connection Pool
```java
public class ConnectionPool {
    private final Semaphore semaphore;
    private final Queue<Connection> pool;
    
    public ConnectionPool(int poolSize) {
        this.semaphore = new Semaphore(poolSize);
        this.pool = new ConcurrentLinkedQueue<>();
        
        for (int i = 0; i < poolSize; i++) {
            pool.add(createConnection());
        }
    }
    
    public Connection acquire() throws InterruptedException {
        semaphore.acquire();  // Block if no connections available
        return pool.poll();
    }
    
    public void release(Connection connection) {
        pool.offer(connection);
        semaphore.release();
    }
    
    // With timeout
    public Connection acquireWithTimeout(long timeout) 
            throws InterruptedException {
        if (semaphore.tryAcquire(timeout, TimeUnit.MILLISECONDS)) {
            return pool.poll();
        }
        throw new RuntimeException("Connection timeout");
    }
}
```

---

## 5. CountDownLatch Pattern

### Waiting for Multiple Tasks
```java
public class ParallelInitializer {
    
    public void initializeSystem() throws InterruptedException {
        int componentCount = 3;
        CountDownLatch latch = new CountDownLatch(componentCount);
        
        // Start parallel initialization
        new Thread(() -> {
            initializeDatabase();
            latch.countDown();
        }).start();
        
        new Thread(() -> {
            initializeCache();
            latch.countDown();
        }).start();
        
        new Thread(() -> {
            initializeMessageQueue();
            latch.countDown();
        }).start();
        
        // Wait for all components
        latch.await();
        System.out.println("System initialized!");
    }
}
```

---

## 6. CyclicBarrier Pattern

### Synchronizing Threads at a Point
```java
public class ParallelDataProcessor {
    
    public void processData(List<List<Integer>> partitions) {
        int threadCount = partitions.size();
        CyclicBarrier barrier = new CyclicBarrier(threadCount, () -> {
            // This runs when all threads reach the barrier
            System.out.println("Phase complete, merging results...");
        });
        
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        
        for (List<Integer> partition : partitions) {
            executor.submit(() -> {
                try {
                    // Phase 1: Process data
                    processPartition(partition);
                    barrier.await();  // Wait for all threads
                    
                    // Phase 2: Post-processing
                    postProcess(partition);
                    barrier.await();  // Wait again (barrier is reusable)
                    
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }
}
```

---

## 7. Atomic Operations

### Lock-Free Counter
```java
public class AtomicCounter {
    private final AtomicLong counter = new AtomicLong(0);
    
    public long increment() {
        return counter.incrementAndGet();
    }
    
    public long get() {
        return counter.get();
    }
    
    // Compare and swap
    public boolean compareAndSet(long expected, long newValue) {
        return counter.compareAndSet(expected, newValue);
    }
}
```

### AtomicReference for Lock-Free Updates
```java
public class LockFreeStack<T> {
    private final AtomicReference<Node<T>> top = new AtomicReference<>();
    
    public void push(T value) {
        Node<T> newNode = new Node<>(value);
        while (true) {
            Node<T> currentTop = top.get();
            newNode.next = currentTop;
            if (top.compareAndSet(currentTop, newNode)) {
                return;
            }
        }
    }
    
    public T pop() {
        while (true) {
            Node<T> currentTop = top.get();
            if (currentTop == null) {
                return null;
            }
            if (top.compareAndSet(currentTop, currentTop.next)) {
                return currentTop.value;
            }
        }
    }
    
    private static class Node<T> {
        final T value;
        Node<T> next;
        
        Node(T value) {
            this.value = value;
        }
    }
}
```

---

## 8. Future/Promise Pattern

### CompletableFuture
```java
public class AsyncService {
    
    public CompletableFuture<User> getUserAsync(Long userId) {
        return CompletableFuture.supplyAsync(() -> {
            return userRepository.findById(userId);
        });
    }
    
    // Chaining
    public CompletableFuture<OrderSummary> getOrderSummary(Long userId) {
        return getUserAsync(userId)
            .thenCompose(user -> getOrdersAsync(user.getId()))
            .thenApply(orders -> calculateSummary(orders))
            .exceptionally(ex -> {
                log.error("Error getting order summary", ex);
                return OrderSummary.empty();
            });
    }
    
    // Parallel execution
    public CompletableFuture<Dashboard> getDashboard(Long userId) {
        CompletableFuture<User> userFuture = getUserAsync(userId);
        CompletableFuture<List<Order>> ordersFuture = getOrdersAsync(userId);
        CompletableFuture<List<Notification>> notifFuture = getNotificationsAsync(userId);
        
        return CompletableFuture.allOf(userFuture, ordersFuture, notifFuture)
            .thenApply(v -> new Dashboard(
                userFuture.join(),
                ordersFuture.join(),
                notifFuture.join()
            ));
    }
}
```

---

## 9. Double-Checked Locking (Singleton)

```java
public class Singleton {
    private static volatile Singleton instance;
    
    private Singleton() {}
    
    public static Singleton getInstance() {
        if (instance == null) {                    // First check (no locking)
            synchronized (Singleton.class) {
                if (instance == null) {            // Second check (with lock)
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }
}

// Better: Enum singleton
public enum SingletonEnum {
    INSTANCE;
    
    public void doSomething() {
        // ...
    }
}
```

---

## 10. Common Concurrency Issues

### Race Condition
```java
// BAD: Race condition
public class UnsafeCounter {
    private int count = 0;
    
    public void increment() {
        count++;  // Not atomic: read-modify-write
    }
}

// GOOD: Thread-safe
public class SafeCounter {
    private final AtomicInteger count = new AtomicInteger(0);
    
    public void increment() {
        count.incrementAndGet();
    }
}
```

### Deadlock Prevention
```java
// BAD: Potential deadlock
public void transfer(Account from, Account to, double amount) {
    synchronized (from) {
        synchronized (to) {
            from.withdraw(amount);
            to.deposit(amount);
        }
    }
}

// GOOD: Lock ordering prevents deadlock
public void transfer(Account from, Account to, double amount) {
    Account first = from.getId() < to.getId() ? from : to;
    Account second = from.getId() < to.getId() ? to : from;
    
    synchronized (first) {
        synchronized (second) {
            from.withdraw(amount);
            to.deposit(amount);
        }
    }
}
```

---

## Quick Reference

| Pattern | Use Case | Java Class |
|---------|----------|------------|
| **Mutex** | Exclusive access | `synchronized`, `ReentrantLock` |
| **ReadWriteLock** | Many readers, few writers | `ReentrantReadWriteLock` |
| **Semaphore** | Limited concurrent access | `Semaphore` |
| **CountDownLatch** | Wait for N tasks | `CountDownLatch` |
| **CyclicBarrier** | Sync at points | `CyclicBarrier` |
| **BlockingQueue** | Producer-Consumer | `LinkedBlockingQueue` |
| **Atomic** | Lock-free operations | `AtomicInteger`, `AtomicReference` |
| **Future** | Async results | `CompletableFuture` |

---

## Interview Tips

**Q: How to prevent deadlock?**
> 1) Lock ordering, 2) Lock timeout, 3) Single lock, 4) Deadlock detection

**Q: synchronized vs ReentrantLock?**
> ReentrantLock: tryLock, fairness, multiple conditions. synchronized: simpler syntax, automatic release.

**Q: volatile vs AtomicInteger?**
> volatile: visibility only. AtomicInteger: visibility + atomicity for compound operations.
