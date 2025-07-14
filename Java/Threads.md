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
