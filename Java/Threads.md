There are several ways to create and manage multiple threads. Here's a detailed explanation of each approach:

---

### ✅ 1. **Extending the `Thread` class**

This is the most basic way.

#### ✅ How it works:

You create a class that extends `Thread` and override the `run()` method.

```java
class MyThread extends Thread {
    public void run() {
        System.out.println("Thread running: " + Thread.currentThread().getName());
    }
}

public class Main {
    public static void main(String[] args) {
        MyThread t1 = new MyThread();
        t1.start();  // starts the thread
    }
}
```

#### 🧠 Notes:

* You **cannot extend another class** if you extend `Thread`.
* You should call `start()`, not `run()` directly.

---

### ✅ 2. **Implementing the `Runnable` interface**

A more flexible approach.

#### ✅ How it works:

Create a class that implements `Runnable`, then pass it to a `Thread`.

```java
class MyRunnable implements Runnable {
    public void run() {
        System.out.println("Thread running: " + Thread.currentThread().getName());
    }
}

public class Main {
    public static void main(String[] args) {
        Thread t1 = new Thread(new MyRunnable());
        t1.start();
    }
}
```

#### 🧠 Notes:

* You can extend other classes.
* Recommended for better decoupling of logic from threading.

---

### ✅ 3. **Using anonymous `Runnable` or lambda expressions**

#### ✅ How it works:

```java
public class Main {
    public static void main(String[] args) {
        Thread t1 = new Thread(() -> {
            System.out.println("Thread running via lambda");
        });
        t1.start();
    }
}
```

#### 🧠 Notes:

* Short and clean syntax.
* Common in modern Java (Java 8+).

---

### ✅ 4. **Using `ExecutorService`**

A powerful way to manage a pool of threads.

#### ✅ How it works:

```java
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) {
        ExecutorService executor = Executors.newFixedThreadPool(3); // pool of 3 threads

        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                System.out.println("Running in: " + Thread.currentThread().getName());
            });
        }

        executor.shutdown();
    }
}
```

#### 🧠 Notes:

* Better control over thread pool size, task management.
* Supports graceful shutdown, retries, scheduling, etc.

---

### ✅ 5. **Using `Callable` and `Future` for return values**

Unlike `Runnable`, `Callable` can return a result and throw exceptions.

```java
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Callable<String> task = () -> "Task Result";

        Future<String> future = executor.submit(task);

        System.out.println(future.get()); // blocks and gets result

        executor.shutdown();
    }
}
```

#### 🧠 Notes:

* Used when you need the thread to return a result.

---

### ✅ 6. **Using `ForkJoinPool` (Parallelism)**

Best for divide-and-conquer algorithms.

```java
import java.util.concurrent.*;

class MyRecursiveTask extends RecursiveTask<Integer> {
    protected Integer compute() {
        return 42;
    }
}

public class Main {
    public static void main(String[] args) {
        ForkJoinPool pool = new ForkJoinPool();
        MyRecursiveTask task = new MyRecursiveTask();

        int result = pool.invoke(task);
        System.out.println("Result: " + result);
    }
}
```

#### 🧠 Notes:

* Supports recursive task splitting.
* Used for parallelism in streams (e.g. `parallelStream()`).

---

### ✅ 7. **Using `ScheduledExecutorService` for Delayed/Repeated Tasks**

```java
import java.util.concurrent.*;

public class Main {
    public static void main(String[] args) {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        Runnable task = () -> System.out.println("Running after delay");

        scheduler.schedule(task, 5, TimeUnit.SECONDS);

        scheduler.shutdown();
    }
}
```

---

### Summary Table:

| Method                      | Return Value | Custom Logic | Reusable | Pool Support |
| --------------------------- | ------------ | ------------ | -------- | ------------ |
| `Thread` class              | ❌            | ✅            | ❌        | ❌            |
| `Runnable` interface        | ❌            | ✅            | ✅        | ❌            |
| Lambda / Anonymous Runnable | ❌            | ✅            | ✅        | ❌            |
| `ExecutorService`           | ❌            | ✅            | ✅        | ✅            |
| `Callable + Future`         | ✅            | ✅            | ✅        | ✅            |
| `ForkJoinPool`              | ✅            | ✅            | ✅        | ✅            |
| `ScheduledExecutorService`  | ❌            | ✅            | ✅        | ✅            |

---

Sure! Let's dive deep into **`ExecutorService`** in Java.

---

## 🔧 What is `ExecutorService`?

`ExecutorService` is a part of Java's **concurrent framework** (`java.util.concurrent`). It is an **interface** that represents an advanced thread pool — it provides a **high-level API to manage threads**, making it easier and more powerful than manually creating threads using `Thread` or `Runnable`.

---

## ✅ Why use `ExecutorService` instead of `Thread`?

| Problem with `Thread`                   | Solution in `ExecutorService`  |
| --------------------------------------- | ------------------------------ |
| Hard to manage lifecycle of threads     | Executor manages it for you    |
| Can’t reuse threads (1 thread per task) | Thread pooling is built-in     |
| No way to return result from `Runnable` | Use `Callable` + `Future`      |
| No built-in scheduling/delayed tasks    | Use `ScheduledExecutorService` |

---

## 🔄 Common Executors (via `Executors` utility class)

### 1. `Executors.newFixedThreadPool(int n)`

* A thread pool with a **fixed number of threads**.

### 2. `Executors.newCachedThreadPool()`

* A pool that creates **new threads as needed**, but **reuses** idle threads.

### 3. `Executors.newSingleThreadExecutor()`

* **Single thread** to execute tasks sequentially.

### 4. `Executors.newScheduledThreadPool(int n)`

* Can **schedule tasks** to run after a delay or periodically.

---

## 🔨 How to use `ExecutorService`?

### ✅ Step-by-step example:

```java
import java.util.concurrent.*;

public class ExecutorExample {
    public static void main(String[] args) {
        // Step 1: Create ExecutorService
        ExecutorService executor = Executors.newFixedThreadPool(3);

        // Step 2: Submit tasks (Runnable or Callable)
        for (int i = 0; i < 5; i++) {
            int taskId = i;
            executor.submit(() -> {
                System.out.println("Task " + taskId + " executed by " + Thread.currentThread().getName());
            });
        }

        // Step 3: Shutdown (optional, but important)
        executor.shutdown();
    }
}
```

---

## 🔁 Callable + Future (with return value):

```java
import java.util.concurrent.*;

public class CallableExample {
    public static void main(String[] args) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();

        Callable<Integer> task = () -> {
            // Simulate computation
            Thread.sleep(1000);
            return 42;
        };

        Future<Integer> result = executor.submit(task);

        System.out.println("Waiting for result...");
        System.out.println("Result: " + result.get());  // Blocks until done

        executor.shutdown();
    }
}
```

---

## 🔄 Lifecycle Methods

| Method               | Description                                                               |
| -------------------- | ------------------------------------------------------------------------- |
| `submit(task)`       | Submits a task (Runnable/Callable)                                        |
| `shutdown()`         | Gracefully shuts down (executes remaining tasks, doesn't accept new ones) |
| `shutdownNow()`      | Immediately stops all tasks                                               |
| `isShutdown()`       | Returns true if shutdown has been initiated                               |
| `isTerminated()`     | Returns true if all tasks are finished after shutdown                     |
| `awaitTermination()` | Blocks until all tasks finish or timeout                                  |

---

## 🧠 Best Practices

* Always **shutdown** the executor after task submission is done.
* Use `Callable` and `Future` if you need to **get results** or **handle exceptions**.
* Use `ScheduledExecutorService` for **delayed or periodic tasks**.
* Avoid creating too many threads. Use a **fixed thread pool** to control concurrency.

---

## ⏱️ ScheduledExecutorService (Bonus)

```java
ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

scheduler.schedule(() -> {
    System.out.println("Task runs after 5 seconds");
}, 5, TimeUnit.SECONDS);
```

---

## ✅ Summary

| Feature                   | Thread       | ExecutorService              |
| ------------------------- | ------------ | ---------------------------- |
| Reusable Threads          | ❌            | ✅                            |
| Returns a result          | ❌ (Runnable) | ✅ (Callable + Future)        |
| Thread Pool               | ❌            | ✅                            |
| Schedule task after delay | ❌            | ✅ (ScheduledExecutorService) |
| Manage shutdown           | ❌            | ✅                            |

---
