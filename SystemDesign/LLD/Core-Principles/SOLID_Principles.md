# SOLID Principles: Complete Guide with Examples

## Overview
SOLID is an acronym for five design principles that make software designs more understandable, flexible, and maintainable. Essential for senior developer interviews.

---

## S - Single Responsibility Principle (SRP)

> **A class should have only one reason to change.**

### ❌ Bad Example
```java
public class User {
    private String name;
    private String email;
    
    // User data management
    public void save() {
        // Save to database
    }
    
    // Email functionality
    public void sendEmail(String message) {
        // Send email
    }
    
    // Report generation
    public void generateReport() {
        // Generate PDF report
    }
}
```
**Problem**: User class handles data, email, and reporting - three reasons to change.

### ✅ Good Example
```java
// Handles only user data
public class User {
    private String name;
    private String email;
    
    // Getters, setters, validation
}

// Handles user persistence
public class UserRepository {
    public void save(User user) {
        // Save to database
    }
    
    public User findById(Long id) {
        // Find user
    }
}

// Handles email sending
public class EmailService {
    public void sendEmail(User user, String message) {
        // Send email
    }
}

// Handles report generation
public class UserReportGenerator {
    public byte[] generateReport(User user) {
        // Generate PDF
    }
}
```

### When to Apply
- Class has multiple unrelated methods
- Changes in one area affect unrelated functionality
- Class name requires "And" or "Or"

---

## O - Open/Closed Principle (OCP)

> **Software entities should be open for extension, but closed for modification.**

### ❌ Bad Example
```java
public class PaymentProcessor {
    public void processPayment(String type, double amount) {
        if (type.equals("CREDIT_CARD")) {
            // Process credit card
        } else if (type.equals("PAYPAL")) {
            // Process PayPal
        } else if (type.equals("BITCOIN")) {
            // Process Bitcoin - Added later, modifies existing code!
        }
        // Need to modify this class for every new payment type
    }
}
```

### ✅ Good Example
```java
// Payment strategy interface
public interface PaymentStrategy {
    void process(double amount);
}

// Credit card implementation
public class CreditCardPayment implements PaymentStrategy {
    @Override
    public void process(double amount) {
        // Process credit card payment
    }
}

// PayPal implementation
public class PayPalPayment implements PaymentStrategy {
    @Override
    public void process(double amount) {
        // Process PayPal payment
    }
}

// Bitcoin - new type without modifying existing code
public class BitcoinPayment implements PaymentStrategy {
    @Override
    public void process(double amount) {
        // Process Bitcoin payment
    }
}

// Payment processor - closed for modification
public class PaymentProcessor {
    public void processPayment(PaymentStrategy strategy, double amount) {
        strategy.process(amount);
    }
}
```

### When to Apply
- Use inheritance/interfaces for variations
- Use Strategy pattern for algorithms
- Use Factory pattern for object creation

---

## L - Liskov Substitution Principle (LSP)

> **Objects of a superclass should be replaceable with objects of subclasses without affecting correctness.**

### ❌ Bad Example
```java
public class Rectangle {
    protected int width;
    protected int height;
    
    public void setWidth(int width) {
        this.width = width;
    }
    
    public void setHeight(int height) {
        this.height = height;
    }
    
    public int getArea() {
        return width * height;
    }
}

public class Square extends Rectangle {
    @Override
    public void setWidth(int width) {
        this.width = width;
        this.height = width;  // Violates LSP!
    }
    
    @Override
    public void setHeight(int height) {
        this.height = height;
        this.width = height;  // Violates LSP!
    }
}

// This breaks:
Rectangle rect = new Square();
rect.setWidth(5);
rect.setHeight(10);
int area = rect.getArea();  // Expected 50, but got 100!
```

### ✅ Good Example
```java
public interface Shape {
    int getArea();
}

public class Rectangle implements Shape {
    private final int width;
    private final int height;
    
    public Rectangle(int width, int height) {
        this.width = width;
        this.height = height;
    }
    
    @Override
    public int getArea() {
        return width * height;
    }
}

public class Square implements Shape {
    private final int side;
    
    public Square(int side) {
        this.side = side;
    }
    
    @Override
    public int getArea() {
        return side * side;
    }
}
```

### Rules for LSP
1. **Preconditions** cannot be strengthened in subtype
2. **Postconditions** cannot be weakened in subtype
3. **Invariants** of supertype must be preserved
4. **History constraint**: Subtypes shouldn't allow state changes that base type wouldn't allow

---

## I - Interface Segregation Principle (ISP)

> **Clients should not be forced to depend on interfaces they don't use.**

### ❌ Bad Example
```java
public interface Worker {
    void work();
    void eat();
    void sleep();
}

// Human worker - uses all methods
public class HumanWorker implements Worker {
    @Override
    public void work() { /* works */ }
    
    @Override
    public void eat() { /* eats */ }
    
    @Override
    public void sleep() { /* sleeps */ }
}

// Robot worker - forced to implement eat and sleep!
public class RobotWorker implements Worker {
    @Override
    public void work() { /* works */ }
    
    @Override
    public void eat() { 
        throw new UnsupportedOperationException(); // Bad!
    }
    
    @Override
    public void sleep() { 
        throw new UnsupportedOperationException(); // Bad!
    }
}
```

### ✅ Good Example
```java
public interface Workable {
    void work();
}

public interface Eatable {
    void eat();
}

public interface Sleepable {
    void sleep();
}

// Human implements all
public class HumanWorker implements Workable, Eatable, Sleepable {
    @Override
    public void work() { /* works */ }
    
    @Override
    public void eat() { /* eats */ }
    
    @Override
    public void sleep() { /* sleeps */ }
}

// Robot only implements what it needs
public class RobotWorker implements Workable {
    @Override
    public void work() { /* works */ }
}
```

### When to Apply
- Interface has methods that some implementers don't need
- Clients only use a subset of interface methods
- Interface is getting too large

---

## D - Dependency Inversion Principle (DIP)

> **High-level modules should not depend on low-level modules. Both should depend on abstractions.**

### ❌ Bad Example
```java
// Low-level module
public class MySQLDatabase {
    public void save(String data) {
        // Save to MySQL
    }
}

// High-level module directly depends on low-level module
public class UserService {
    private MySQLDatabase database = new MySQLDatabase();  // Tight coupling!
    
    public void createUser(User user) {
        database.save(user.toString());
    }
}
```

### ✅ Good Example
```java
// Abstraction
public interface Database {
    void save(String data);
}

// Low-level module implements abstraction
public class MySQLDatabase implements Database {
    @Override
    public void save(String data) {
        // Save to MySQL
    }
}

public class MongoDatabase implements Database {
    @Override
    public void save(String data) {
        // Save to MongoDB
    }
}

// High-level module depends on abstraction
public class UserService {
    private final Database database;
    
    // Dependency injection
    public UserService(Database database) {
        this.database = database;
    }
    
    public void createUser(User user) {
        database.save(user.toString());
    }
}

// Usage - can swap implementations easily
Database mysql = new MySQLDatabase();
Database mongo = new MongoDatabase();

UserService service1 = new UserService(mysql);
UserService service2 = new UserService(mongo);
```

### Benefits
- Easy to swap implementations
- Easier unit testing (mock dependencies)
- Loose coupling between modules

---

## SOLID Summary

| Principle | Focus | Key Benefit |
|-----------|-------|-------------|
| **SRP** | One reason to change | Easier maintenance |
| **OCP** | Extend without modifying | Reduced regression risk |
| **LSP** | Substitutable subtypes | Reliable inheritance |
| **ISP** | Focused interfaces | No forced dependencies |
| **DIP** | Depend on abstractions | Loose coupling |

---

## Applying SOLID in Practice

### Order Processing Example

```java
// Interfaces (ISP, DIP)
public interface OrderValidator {
    boolean validate(Order order);
}

public interface PaymentProcessor {
    PaymentResult process(Order order);
}

public interface NotificationSender {
    void send(User user, String message);
}

public interface OrderRepository {
    void save(Order order);
}

// Implementations (SRP)
public class InventoryValidator implements OrderValidator {
    @Override
    public boolean validate(Order order) {
        // Check inventory
    }
}

public class CreditCardProcessor implements PaymentProcessor {
    @Override
    public PaymentResult process(Order order) {
        // Process payment
    }
}

// Main service (DIP, OCP)
public class OrderService {
    private final OrderValidator validator;
    private final PaymentProcessor paymentProcessor;
    private final NotificationSender notificationSender;
    private final OrderRepository repository;
    
    public OrderService(
            OrderValidator validator,
            PaymentProcessor paymentProcessor,
            NotificationSender notificationSender,
            OrderRepository repository) {
        this.validator = validator;
        this.paymentProcessor = paymentProcessor;
        this.notificationSender = notificationSender;
        this.repository = repository;
    }
    
    public OrderResult processOrder(Order order) {
        if (!validator.validate(order)) {
            return OrderResult.invalid();
        }
        
        PaymentResult payment = paymentProcessor.process(order);
        if (!payment.isSuccessful()) {
            return OrderResult.paymentFailed();
        }
        
        repository.save(order);
        notificationSender.send(order.getUser(), "Order confirmed!");
        
        return OrderResult.success();
    }
}
```

---

## Interview Tips

### Common Questions

**Q: What is SOLID?**
> Five principles for maintainable OOP: Single Responsibility, Open-Closed, Liskov Substitution, Interface Segregation, Dependency Inversion.

**Q: Why use SOLID?**
> Maintainability, testability, flexibility. Code is easier to understand, modify, and extend without breaking existing functionality.

**Q: When NOT to use SOLID?**
> Don't over-engineer simple code. Apply when complexity justifies it. YAGNI (You Aren't Gonna Need It) still applies.

### Red Flags in Code
- God classes (violates SRP)
- Long switch/if-else chains (violates OCP)
- Throwing UnsupportedOperationException (violates LSP)
- Fat interfaces (violates ISP)
- Direct instantiation of dependencies (violates DIP)
