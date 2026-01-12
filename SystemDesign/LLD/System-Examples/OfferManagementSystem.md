## Problem Statement:

Design Flash Sale type of system where a product will be available at a special rate for a given time period & fixed quantity. Focus was more on how will you be able to handle sudden spike of traffic when the sale will begin & how will you ensure strong consistency.

1. Functional Requirements
Product Availability: The system should offer a product at a discounted rate for a specific time and quantity.
User Actions: Users can view, add to cart, and purchase the product during the sale.
Inventory Management: The system must accurately track and decrement the product inventory.
High Traffic Handling: The system must handle a massive spike in traffic when the sale begins.
Consistency: Ensure that the product is not oversold.

2. Non-Functional Requirements
Scalability: Handle millions of users attempting to purchase simultaneously.
Reliability: Ensure that the system remains operational during high traffic.
Performance: Low latency for critical actions like adding to cart and checking out.
Strong Consistency: Ensure no overselling occurs, even under high load.

Certainly, let's design a **Flash Sale System** with a focus on handling high traffic and ensuring strong consistency. We'll cover:

1. Overview of Components
2. Class Diagrams and Definitions
3. Database Schema Design

We'll use Java for the class implementations and SQL for the database schema.

## 1. Overview of Components

### Core Entities
    * User: Represents a customer participating in the flash sale.
    * Product: Represents items available for purchase.
    * Inventory: Tracks the available quantity of each product.
    * FlashSale: Represents the flash sale event with specific products, discounted prices, time frames, and quantities.
    * Cart: Holds products that a user intends to purchase.
    * Order: Represents a confirmed purchase by a user.
    * Payment: Handles payment processing for orders.

### Service Components
    * UserService: Manages user-related operations.
    * ProductService: Handles product information retrieval.
    * InventoryService: Manages inventory checks and updates.
    * FlashSaleService: Manages flash sale configurations and validations.
    * CartService: Handles adding/removing items to/from the cart.
    * OrderService: Processes order creation and confirmation.
    * PaymentService: Processes payments and handles payment confirmations.

### Concurrency and Consistency Handling
    * Distributed Locking: Ensures that inventory updates are atomic and consistent across multiple instances using Redis or similar systems.
    * Message Queues: Processes orders asynchronously to handle high traffic and maintain system stability.

## 2. Class Diagrams and Definitions

We'll define classes for each component with their respective attributes and methods.

+-------------------+
|      User         |
+-------------------+
| - userId: String  |
| - name: String    |
| - email: String   |
| - password: String|
+-------------------+
| + register()      |
| + login()         |
| + viewCart()      |
| + placeOrder()    |
+-------------------+

+-------------------+
|     Product       |
+-------------------+
| - productId: String|
| - name: String     |
| - description: String |
| - price: Double    |
+-------------------+
| + viewDetails()    |
| + getPrice()       |
+-------------------+

+-------------------+
|    Inventory      |
+-------------------+
| - productId: String|
| - quantity: Integer|
+-------------------+
| + checkAvailability()|
| + updateQuantity()   |
+-------------------+

+-------------------+
|    FlashSale      |
+-------------------+
| - saleId: String   |
| - productId: String|
| - discount: Double |
| - startTime: DateTime |
| - endTime: DateTime   |
+-------------------+
| + startSale()      |
| + endSale()        |
| + isActive()       |
+-------------------+

+-------------------+
|      Cart         |
+-------------------+
| - cartId: String   |
| - userId: String   |
| - items: List<Product> |
+-------------------+
| + addItem()        |
| + removeItem()     |
| + viewCart()       |
+-------------------+

+-------------------+
|      Order        |
+-------------------+
| - orderId: String  |
| - userId: String   |
| - items: List<Product> |
| - totalAmount: Double |
| - status: String   |
+-------------------+
| + createOrder()    |
| + confirmOrder()   |
+-------------------+

+-------------------+
|     Payment       |
+-------------------+
| - paymentId: String |
| - orderId: String   |
| - amount: Double    |
| - status: String    |
+-------------------+
| + processPayment()  |
| + confirmPayment()  |
+-------------------+

+-------------------+
|   UserService     |
+-------------------+
| + registerUser()   |
| + loginUser()      |
| + getUserCart()    |
+-------------------+

+-------------------+
| ProductService    |
+-------------------+
| + getProductDetails() |
| + listProducts()      |
+-------------------+

+-------------------+
| InventoryService  |
+-------------------+
| + checkInventory()|
| + reduceInventory() |
+-------------------+

+-------------------+
| FlashSaleService  |
+-------------------+
| + configureSale() |
| + validateSale()  |
| + getActiveSales() |
+-------------------+

+-------------------+
|    CartService    |
+-------------------+
| + addToCart()     |
| + removeFromCart()|
+-------------------+

+-------------------+
|   OrderService    |
+-------------------+
| + createOrder()   |
| + confirmOrder()  |
+-------------------+

+-------------------+
|  PaymentService   |
+-------------------+
| + initiatePayment() |
| + confirmPayment()  |
+-------------------+

### 2.1. User
```java
public class User {
    private String userId;
    private String name;
    private String email;
    private String password;
    private String address;
    private String phoneNumber;
    
    // Constructors, Getters, and Setters
}
```

**Explanation:** The User class stores user information necessary for authentication and order processing.

### 2.2. Product

```java
public class Product {
    private String productId;
    private String name;
    private String description;
    private double price;
    private String imageUrl;
    private List<String> categories;
    
    // Constructors, Getters, and Setters
}
```
**Explanation:** The Product class contains detailed information about products available in the system.

### 2.3. Inventory
```java
public class Inventory {
    private String inventoryId;
    private String productId;
    private int totalQuantity;
    private int reservedQuantity;
    
    // Constructors, Getters, and Setters
    
    public synchronized boolean reserveStock(int quantity) {
        if (totalQuantity - reservedQuantity >= quantity) {
            reservedQuantity += quantity;
            return true;
        }
        return false;
    }
    
    public synchronized void releaseStock(int quantity) {
        reservedQuantity -= quantity;
    }
    
    public synchronized void deductStock(int quantity) {
        totalQuantity -= quantity;
        reservedQuantity -= quantity;
    }
}
```
**Explanation:** The Inventory class manages the stock levels of products. Methods are synchronized to ensure thread safety during concurrent access.

### 2.4. FlashSale
```java
import java.time.LocalDateTime;

public class FlashSale {
    private String flashSaleId;
    private String productId;
    private double discountedPrice;
    private int availableQuantity;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    
    // Constructors, Getters, and Setters
    
    public boolean isSaleActive() {
        LocalDateTime now = LocalDateTime.now();
        return now.isAfter(startTime) && now.isBefore(endTime);
    }
    
    public synchronized boolean reserveSaleStock(int quantity) {
        if (availableQuantity >= quantity) {
            availableQuantity -= quantity;
            return true;
        }
        return false;
    }
}
```
**Explanation:** The FlashSale class defines the parameters of a flash sale event and includes methods to check sale activity and reserve stock specifically allocated for the sale.

### 2.5. Cart
```java
import java.util.HashMap;
import java.util.Map;

public class Cart {
    private String cartId;
    private String userId;
    private Map<String, Integer> products; // productId -> quantity
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    
    // Constructors, Getters, and Setters
    
    public void addProduct(String productId, int quantity) {
        products.put(productId, products.getOrDefault(productId, 0) + quantity);
    }
    
    public void removeProduct(String productId) {
        products.remove(productId);
    }
    
    public void clearCart() {
        products.clear();
    }
}
```

**Explanation:** The Cart class manages products that a user intends to purchase. It includes methods to add, remove, and clear products from the cart.

### 2.6. Order
```java
import java.time.LocalDateTime;
import java.util.List;

public class Order {
    private String orderId;
    private String userId;
    private List<OrderItem> orderItems;
    private double totalAmount;
    private OrderStatus status;
    private LocalDateTime orderTime;
    private Payment payment;
    
    // Constructors, Getters, and Setters
}

public class OrderItem {
    private String productId;
    private int quantity;
    private double price;
    
    // Constructors, Getters, and Setters
}

public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    COMPLETED
}
```

**Explanation:** The Order class represents a purchase made by a user. It includes order items, total amount, status, and associated payment information. OrderItem represents individual products within an order.

### 2.7. Payment
```java
import java.time.LocalDateTime;

public class Payment {
    private String paymentId;
    private String orderId;
    private PaymentMethod method;
    private PaymentStatus status;
    private double amount;
    private LocalDateTime paymentTime;
    private String transactionId;
    
    // Constructors, Getters, and Setters
}

public enum PaymentMethod {
    CREDIT_CARD,
    DEBIT_CARD,
    PAYPAL,
    NET_BANKING,
    UPI
}

public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    CANCELLED
}
```

**Explanation:** The Payment class handles payment details for an order, including method, status, amount, and transaction details.

### 2.8. Services
We'll define service classes responsible for business logic and interactions between entities.

2.8.1. UserService
```java
public class UserService {
    
    public User registerUser(String name, String email, String password, String address, String phoneNumber) {
        // Logic to register user and save to database
    }
    
    public User authenticateUser(String email, String password) {
        // Logic to authenticate user credentials
    }
    
    public User getUserDetails(String userId) {
        // Fetch user details from database
    }
}
```

**Explanation:** UserService manages user registration, authentication, and retrieval of user details.

2.8.2. ProductService
```java
public class ProductService {
    
    public Product getProductById(String productId) {
        // Fetch product details from database
    }
    
    public List<Product> getAllProducts() {
        // Fetch all products
    }
    
    public void addProduct(Product product) {
        // Add new product to database
    }
    
    public void updateProduct(Product product) {
        // Update existing product details
    }
}
```

**Explanation:** ProductService handles retrieval and management of product information.

2.8.3. InventoryService
```java
public class InventoryService {
    
    public boolean checkAndReserveInventory(String productId, int quantity) {
        // Check inventory and reserve stock if available
    }
    
    public void releaseInventory(String productId, int quantity) {
        // Release reserved inventory
    }
    
    public void deductInventory(String productId, int quantity) {
        // Deduct inventory after successful order
    }
}
```

**Explanation:** InventoryService manages stock levels, ensuring accurate tracking and updates during the purchase process.

2.8.4. FlashSaleService
```java
public class FlashSaleService {
    
    public FlashSale getFlashSaleByProductId(String productId) {
        // Fetch active flash sale for the product
    }
    
    public boolean isFlashSaleActive(String productId) {
        // Check if flash sale is active for the product
    }
    
    public boolean reserveFlashSaleStock(String productId, int quantity) {
        // Reserve stock specifically allocated for flash sale
    }
}
```

**Explanation:** FlashSaleService manages flash sale events, including validation and reservation of flash sale stock.

2.8.5. CartService
```java
public class CartService {
    
    public Cart createCart(String userId) {
        // Create new cart for user
    }
    
    public Cart getCartByUserId(String userId) {
        // Retrieve user's cart
    }
    
    public void addToCart(String userId, String productId, int quantity) {
        // Add product to user's cart
    }
    
    public void removeFromCart(String userId, String productId) {
        // Remove product from user's cart
    }
    
    public void clearCart(String userId) {
        // Clear all items from user's cart
    }
}
```

**Explanation:** CartService handles all operations related to the user's shopping cart.

2.8.6. OrderService
```java
public class OrderService {
    
    public Order createOrder(String userId, List<OrderItem> items) {
        // Validate cart items, reserve inventory, and create order
    }
    
    public Order getOrderById(String orderId) {
        // Retrieve order details
    }
    
    public void updateOrderStatus(String orderId, OrderStatus status) {
        // Update the status of an order
    }
}
```
**Explanation:** OrderService processes orders by validating items, reserving inventory, and managing order statuses.

2.8.7. PaymentService
```java
public class PaymentService {
    
    public Payment processPayment(String orderId, PaymentMethod method, double amount) {
        // Process payment through selected method
    }
    
    public Payment getPaymentDetails(String paymentId) {
        // Retrieve payment details
    }
    
    public void updatePaymentStatus(String paymentId, PaymentStatus status, String transactionId) {
        // Update payment status after processing
    }
}
```

**Explanation:** PaymentService handles payment processing and tracking for orders.

### 2.9. Workflow Example
Step-by-step process during a flash sale:

**User Login:**

User logs in through UserService.
Viewing Flash Sale Product:

User retrieves product and flash sale details via ProductService and FlashSaleService.
Adding to Cart:

User adds product to cart using CartService.
FlashSaleService and InventoryService reserve the required stock atomically.
A timeout is set for the reservation (e.g., 10 minutes).
Checkout and Order Creation:

User proceeds to checkout.
OrderService creates an order after validating cart and confirming inventory reservation.
Payment Processing:

PaymentService processes payment.
Upon successful payment, InventoryService deducts the stock permanently.
OrderService updates order status to CONFIRMED.
Handling Failures:

If payment fails or times out, InventoryService releases the reserved stock.
OrderService updates order status to CANCELLED.

## 3. Database Schema Design
We'll design the database schema using relational databases (e.g., MySQL or PostgreSQL).

### 3.1. users Table
```sql
CREATE TABLE users (
    user_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    address TEXT,
    phone_number VARCHAR(20),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**Explanation:** Stores user information necessary for authentication and order processing.

### 3.2. products Table
```sql
CREATE TABLE products (
    product_id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price DECIMAL(10, 2) NOT NULL,
    image_url TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**Explanation:** Stores detailed information about products.

### 3.3. inventory Table
```sql
CREATE TABLE inventory (
    inventory_id VARCHAR(36) PRIMARY KEY,
    product_id VARCHAR(36) NOT NULL,
    total_quantity INT NOT NULL,
    reserved_quantity INT DEFAULT 0,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id) REFERENCES products(product_id)
);
```

**Explanation:** Tracks the available and reserved quantity of each product.

### 3.4. flash_sales Table
```sql
CREATE TABLE flash_sales (
    flash_sale_id VARCHAR(36) PRIMARY KEY,
    product_id VARCHAR(36) NOT NULL,
    discounted_price DECIMAL(10, 2) NOT NULL,
    available_quantity INT NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    FOREIGN KEY (product_id) REFERENCES products(product_id)
);
```

**Explanation:** Defines flash sale events with associated product, pricing, quantity, and time frame.

### 3.5. carts Table
```sql
CREATE TABLE carts (
    cart_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);
```

**Explanation:** Stores cart information for each user.

### 3.6. cart_items Table
```sql
CREATE TABLE cart_items (
    cart_item_id VARCHAR(36) PRIMARY KEY,
    cart_id VARCHAR(36) NOT NULL,
    product_id VARCHAR(36) NOT NULL,
    quantity INT NOT NULL,
    added_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (cart_id) REFERENCES carts(cart_id),
    FOREIGN KEY (product_id) REFERENCES products(product_id)
);
```

**Explanation:** Stores individual items added to carts.

### 3.7. orders Table
```sql
CREATE TABLE orders (
    order_id VARCHAR(36) PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    total_amount DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    order_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES users(user_id)
);
```
**Explanation:** Stores order details including total amount and status.

### 3.8. order_items Table
```sql
CREATE TABLE order_items (
    order_item_id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    product_id VARCHAR(36) NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(10, 2) NOT NULL,
    FOREIGN KEY (order_id) REFERENCES orders(order_id),
    FOREIGN KEY (product_id) REFERENCES products(product_id)
);
```
**Explanation:** Stores individual items associated with each order.

### 3.9. payments Table
```sql
CREATE TABLE payments (
    payment_id VARCHAR(36) PRIMARY KEY,
    order_id VARCHAR(36) NOT NULL,
    method VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    amount DECIMAL(10, 2) NOT NULL,
    payment_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    transaction_id VARCHAR(255),
    FOREIGN KEY (order_id) REFERENCES orders(order_id)
);
```
**Explanation:** Stores payment information for orders including method, status, and transaction details.

### 3.10. Database Indexing and Optimization
**Indexes:** Create indexes on frequently queried columns like user_id, product_id, and order_id to improve query performance.

```sql
CREATE INDEX idx_user_id ON orders(user_id);
CREATE INDEX idx_product_id ON inventory(product_id);
CREATE INDEX idx_order_id ON payments(order_id);
```

**Partitioning:** Consider partitioning large tables like orders and payments by date to improve performance and manageability.

**Replication:** Implement database replication for read-heavy operations to distribute load and increase availability.

**Caching:** Use caching mechanisms (e.g., Redis) to store frequently accessed data such as product details and flash sale information.

#### Conclusion

This design provides a comprehensive low-level architecture for a Flash Sale System capable of handling high traffic volumes while ensuring strong consistency and reliability. The use of synchronized methods, distributed locking, and robust service layering ensures that inventory is accurately tracked and no overselling occurs.

#### Scalability and Performance Considerations:

**Load Balancing:** Distribute incoming requests across multiple servers.<br>
**Auto-Scaling:** Automatically scale resources based on real-time traffic.<br>
**Asynchronous Processing:** Use message queues to process orders asynchronously and improve responsiveness.<br>
**Monitoring and Alerting:** Implement monitoring tools to track system performance and trigger alerts for anomalies.<br>

#### Security Measures:

**Input Validation:** Ensure all inputs are validated to prevent SQL injection and other attacks.<br>
**Authentication and Authorization:** Secure endpoints with proper authentication and role-based access control.<br>
**HTTPS:** Encrypt data in transit using HTTPS.<br>
**Rate Limiting:** Prevent abuse by limiting the number of requests from a single user/IP address.<br>


#### Testing:

**Unit Testing:** Write comprehensive unit tests for all service methods.<br>
**Integration Testing:** Ensure all components work seamlessly together.<br>
**Load Testing:** Simulate high traffic scenarios to test system resilience and performance.<br>

#### Further Enhancements:

**User Notifications:** Implement email/SMS notifications for order confirmations and payment receipts.<br>
**Analytics:** Collect and analyze data on user behavior and sales performance.<br>
**Admin Portal:** Develop an administrative interface to manage products, inventory, and flash sales.<br>
