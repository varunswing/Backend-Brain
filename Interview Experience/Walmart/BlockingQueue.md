**Thread-safe blocking queue implementation in Java** using `wait()` and `notifyAll()`. It supports:

* `put()` → waits if queue is full
* `take()` → waits if queue is empty

---

## ✅ BlockingQueue Implementation (Producer-Consumer Safe)

```java
import java.util.LinkedList;
import java.util.Queue;

public class BlockingQueue<T> {
    private Queue<T> queue = new LinkedList<>();
    private int capacity;

    public BlockingQueue(int capacity) {
        this.capacity = capacity;
    }

    // Put item into queue (wait if full)
    public synchronized void put(T item) throws InterruptedException {
        while (queue.size() == capacity) {
            wait(); // wait until space becomes available
        }
        queue.add(item);
        notifyAll(); // notify any waiting threads
    }

    // Take item from queue (wait if empty)
    public synchronized T take() throws InterruptedException {
        while (queue.isEmpty()) {
            wait(); // wait until an item is available
        }
        T item = queue.poll();
        notifyAll(); // notify threads waiting to put
        return item;
    }

    public synchronized int size() {
        return queue.size();
    }
}
```

---

## 🔁 Example Usage with Producer-Consumer

```java
public class Main {
    public static void main(String[] args) {
        BlockingQueue<Integer> queue = new BlockingQueue<>(5);

        // Producer Thread
        Thread producer = new Thread(() -> {
            for (int i = 1; i <= 10; i++) {
                try {
                    queue.put(i);
                    System.out.println("Produced: " + i);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        // Consumer Thread
        Thread consumer = new Thread(() -> {
            for (int i = 1; i <= 10; i++) {
                try {
                    int val = queue.take();
                    System.out.println("Consumed: " + val);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        producer.start();
        consumer.start();
    }
}
```

---

## 🧠 Notes

* `wait()` releases the lock and pauses the thread.
* `notifyAll()` wakes all waiting threads so they re-check their condition.
* Use `notifyAll()` instead of `notify()` to prevent **missed signals** in multi-producer/consumer scenarios.

---
