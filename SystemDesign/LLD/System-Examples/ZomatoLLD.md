[Detailed](https://github.com/keertipurswani/Uber-Ola-Low-Level-Design)

This is demo code for LLD Bootcamp. It is not intended to be production level code and can be improved, but it sets the thought process and provides examples for how classes can be structured in a food delivery app like Swiggy or Zomato.

![Alt Tex](images/Zomato_LLD.png)


For a Zomato-like online food delivery system, here’s a detailed breakdown including a UML class diagram, functional and non-functional requirements, database schema, API endpoints, and service layer definitions.

---

## 1. UML Class Diagram

```plaintext
+-----------------------------------+
|              User                 |
+-----------------------------------+
| - userId: long                    |
| - name: String                    |
| - email: String                   |
| - password: String                |
| - address: String                 |
|-----------------------------------|
| + placeOrder()                    |
| + cancelOrder()                   |
| + viewOrderHistory()              |
+-----------------------------------+

+-----------------------------------+
|           Restaurant              |
+-----------------------------------+
| - restaurantId: long              |
| - name: String                    |
| - address: String                 |
| - cuisine: String                 |
| - rating: double                  |
|-----------------------------------|
| + addMenuItem()                   |
| + updateMenuItem()                |
| + viewOrders()                    |
+-----------------------------------+

+-----------------------------------+
|           MenuItem                |
+-----------------------------------+
| - menuItemId: long                |
| - name: String                    |
| - description: String             |
| - price: double                   |
| - image: String                   |
+-----------------------------------+

+-----------------------------------+
|             Order                 |
+-----------------------------------+
| - orderId: long                   |
| - userId: long                    |
| - restaurantId: long              |
| - orderDate: DateTime             |
| - total: double                   |
| - status: OrderStatus             |
| - items: List<MenuItem>           |
|-----------------------------------|
| + addMenuItem()                   |
| + removeMenuItem()                |
| + updateStatus()                  |
+-----------------------------------+

+-----------------------------------+
|           Payment                 |
+-----------------------------------+
| - paymentId: long                 |
| - orderId: long                   |
| - amount: double                  |
| - paymentMethod: String           |
| - status: PaymentStatus           |
|-----------------------------------|
| + processPayment()                |
| + updateStatus()                  |
+-----------------------------------+

+-----------------------------------+
|        DeliveryPartner            |
+-----------------------------------+
| - deliveryPartnerId: long         |
| - name: String                    |
| - contactInfo: String             |
|-----------------------------------|
| + assignOrder()                   |
| + updateOrderStatus()             |
+-----------------------------------+
```

---

## 2. Functional Requirements

1. **User Management**:
   - Register, login, and logout for users.
   - Update user profiles and addresses.
   - View order history.

2. **Restaurant Management**:
   - Register and manage restaurants.
   - Add, update, and delete menu items.
   - View orders placed at the restaurant.

3. **Order Management**:
   - Place an order by selecting items from a restaurant’s menu.
   - Cancel orders (before preparation).
   - Track the status of the order.

4. **Payment Processing**:
   - Integrate payment gateway for online transactions.
   - Process payments and update payment status.
   - Refund processing for canceled orders.

5. **Delivery Management**:
   - Assign orders to available delivery partners.
   - Update order status during delivery.
   - Contact information of the delivery partner.

## 3. Non-Functional Requirements

- **Scalability**: System should handle high volume of requests during peak hours.
- **Availability**: High availability to ensure the service is accessible anytime.
- **Performance**: Quick response times for restaurant searches and order placement.
- **Security**: Secure storage of sensitive user information, including passwords and payment details.
- **Reliability**: Ensure order and payment information is accurate and consistent.
- **Maintainability**: Modular architecture to support easy maintenance and feature updates.
- **Usability**: User-friendly interface for browsing restaurants, placing orders, and making payments.

---

## 4. Database Schema

#### Tables and Fields

1. **Users**
   - `userId` (PK, long)
   - `name` (String)
   - `email` (String, unique)
   - `password` (String, hashed)
   - `address` (String)

2. **Restaurants**
   - `restaurantId` (PK, long)
   - `name` (String)
   - `address` (String)
   - `cuisine` (String)
   - `rating` (double)

3. **MenuItems**
   - `menuItemId` (PK, long)
   - `name` (String)
   - `description` (String)
   - `price` (double)
   - `image` (String)
   - `restaurantId` (FK, long)

4. **Orders**
   - `orderId` (PK, long)
   - `userId` (FK, long)
   - `restaurantId` (FK, long)
   - `orderDate` (DateTime)
   - `total` (double)
   - `status` (enum: Pending, Preparing, Out for Delivery, Completed)

5. **OrderItems**
   - `orderItemId` (PK, long)
   - `orderId` (FK, long)
   - `menuItemId` (FK, long)
   - `quantity` (int)

6. **Payments**
   - `paymentId` (PK, long)
   - `orderId` (FK, long)
   - `amount` (double)
   - `paymentMethod` (enum: Card, UPI, Cash)
   - `status` (enum: Initiated, Success, Failed)

7. **DeliveryPartners**
   - `deliveryPartnerId` (PK, long)
   - `name` (String)
   - `contactInfo` (String)

---

## 5. API Endpoints

1. **User APIs**:
   - `POST /users/register`: Register a new user.
   - `POST /users/login`: User login.
   - `GET /users/{userId}/orders`: View order history.

2. **Restaurant APIs**:
   - `GET /restaurants`: Fetch all restaurants.
   - `GET /restaurants/{restaurantId}`: Get restaurant details.
   - `POST /restaurants/{restaurantId}/menu`: Add menu item.
   - `PUT /restaurants/{restaurantId}/menu/{menuItemId}`: Update menu item.

3. **Order APIs**:
   - `POST /orders`: Place a new order.
   - `PUT /orders/{orderId}/cancel`: Cancel an order.
   - `GET /orders/{orderId}`: Get order details.

4. **Payment APIs**:
   - `POST /payments`: Initiate payment.
   - `PUT /payments/{paymentId}/status`: Update payment status.

5. **Delivery Partner APIs**:
   - `POST /delivery/assign`: Assign an order to a delivery partner.
   - `PUT /delivery/{orderId}/status`: Update delivery status.

---

## 6. Service Layer

Here’s a detailed service layer implementation for the various services in a Zomato-like food delivery system. Each service manages specific functionalities as per the design.

---

### 1. **UserService** - Manages user-related operations

```java
@Service
public class UserService {

    @Autowired
    private UserRepository userRepository;

    public User registerUser(User user) {
        return userRepository.save(user);
    }

    public Optional<User> getUserById(Long userId) {
        return userRepository.findById(userId);
    }

    public List<Order> getOrderHistory(Long userId) {
        Optional<User> user = userRepository.findById(userId);
        return user.map(User::getOrderHistory).orElse(new ArrayList<>());
    }
}
```

---

### 2. **RestaurantService** - Handles restaurant operations and menu management

```java
@Service
public class RestaurantService {

    @Autowired
    private RestaurantRepository restaurantRepository;

    public List<Restaurant> getAllRestaurants() {
        return restaurantRepository.findAll();
    }

    public Optional<Restaurant> getRestaurantById(Long restaurantId) {
        return restaurantRepository.findById(restaurantId);
    }

    public MenuItem addMenuItem(Long restaurantId, MenuItem menuItem) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new EntityNotFoundException("Restaurant not found"));
        restaurant.getMenuItems().add(menuItem);
        return menuItem;
    }

    public MenuItem updateMenuItem(Long restaurantId, Long menuItemId, MenuItem menuItemDetails) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new EntityNotFoundException("Restaurant not found"));
        MenuItem menuItem = restaurant.getMenuItems().stream()
                .filter(item -> item.getId().equals(menuItemId))
                .findFirst()
                .orElseThrow(() -> new EntityNotFoundException("Menu item not found"));

        menuItem.setName(menuItemDetails.getName());
        menuItem.setDescription(menuItemDetails.getDescription());
        menuItem.setPrice(menuItemDetails.getPrice());

        return menuItem;
    }
}
```

---

### 3. **OrderService** - Manages order placement and status updates

```java
@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private InventoryService inventoryService;
    @Autowired
    private DeliveryService deliveryService;

    public Order placeOrder(Order order) {
        // Check inventory for each menu item in the order
        for (OrderItem item : order.getItems()) {
            inventoryService.checkAndReduceStock(item.getMenuItemId(), item.getQuantity());
        }
        order.setStatus("Placed");
        Order savedOrder = orderRepository.save(order);

        // Assign delivery partner
        deliveryService.assignDeliveryPartner(savedOrder);

        return savedOrder;
    }

    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        order.setStatus("Canceled");
        orderRepository.save(order);
    }

    public Optional<Order> getOrderDetails(Long orderId) {
        return orderRepository.findById(orderId);
    }
}
```

---

### 4. **PaymentService** - Processes payments for orders

```java
@Service
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private OrderService orderService;

    public Payment initiatePayment(Payment payment) {
        payment.setStatus("Initiated");
        return paymentRepository.save(payment);
    }

    public Payment updatePaymentStatus(Long paymentId, String status) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found"));
        payment.setStatus(status);
        paymentRepository.save(payment);

        // Update order status if payment is completed
        if ("Completed".equals(status)) {
            orderService.updateOrderStatus(payment.getOrderId(), "Paid");
        }

        return payment;
    }
}
```

---

### 5. **DeliveryService** - Manages delivery partner assignment and tracking

```java
@Service
public class DeliveryService {

    @Autowired
    private DeliveryPartnerRepository deliveryPartnerRepository;
    @Autowired
    private OrderRepository orderRepository;

    public void assignDeliveryPartner(Order order) {
        // Find an available delivery partner
        DeliveryPartner partner = deliveryPartnerRepository.findAvailablePartner()
                .orElseThrow(() -> new RuntimeException("No delivery partner available"));
        
        order.setDeliveryPartnerId(partner.getId());
        order.setStatus("Assigned to Delivery");
        orderRepository.save(order);

        partner.setAvailable(false);
        deliveryPartnerRepository.save(partner);
    }

    public void updateOrderStatus(Long orderId, String status) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Order not found"));
        order.setStatus(status);
        orderRepository.save(order);
    }
}
```

---

### 6. **NotificationService** - Sends notifications to users

```java
@Service
public class NotificationService {

    public void sendOrderUpdate(Long userId, String message) {
        // Logic to send notification to the user
        System.out.println("Notification to user " + userId + ": " + message);
    }

    public void sendPaymentConfirmation(Long userId, Long paymentId) {
        // Logic to send payment confirmation
        System.out.println("Payment confirmation for payment ID " + paymentId + " to user " + userId);
    }
}
```

---

This implementation includes core functionalities such as user registration, restaurant and menu item management, order lifecycle management, payment processing, delivery assignment, and notifications. Each service has a specific responsibility, adhering to Single Responsibility and Dependency Injection principles for maintainability and testability.

These services could be further expanded with additional error handling, security checks, and logging to make them production-ready.

---

## 7. Design Patterns Used

This breakdown should help in constructing a Zomato-like online food delivery system, offering a clear view of the entities, their relationships, and the responsibilities of each service in the system.

The following design patterns are utilized across the API and service layers to ensure modularity, maintainability, and scalability:

1. **Repository Pattern**: 
   - Used in the data access layer (not shown here but implied with data-related operations) to abstract the persistence logic from business logic. This pattern helps keep the service layer clean by interacting with repositories like `UserRepository`, `RestaurantRepository`, and `OrderRepository`.

2. **Service Layer Pattern**: 
   - Each entity has a dedicated service (`UserService`, `RestaurantService`, `OrderService`, etc.), centralizing the business logic for that entity in one layer. This pattern ensures separation between business logic and controller logic, making it easier to scale, maintain, and test each service independently.

3. **Controller Pattern**:
   - The system adheres to the Controller pattern for handling HTTP requests in a RESTful manner. Controllers like `UserController`, `OrderController`, and `PaymentController` serve as entry points for client requests, which they delegate to the appropriate service. This separation makes the application modular and helps maintain RESTful API standards.

4. **Dependency Injection (DI)**:
   - Spring’s `@Autowired` DI injects dependencies, enabling loose coupling. This pattern allows for easier testing and swapping of implementations (e.g., mock services during testing) without modifying the dependent code. For instance, services can be injected into controllers and repositories into services without hard-coding dependencies.

5. **Factory Pattern** (potentially in `NotificationService`):
   - The `NotificationService` could use a factory pattern to create different types of notifications (e.g., SMS, email, push) based on the user’s preferences. This pattern would abstract the instantiation of different notification types, providing flexibility if the notification system grows to include various types and channels.

6. **Singleton Pattern**:
   - Services and repositories are typically managed as singletons in Spring, meaning only one instance exists within the application context. This pattern optimizes resource usage, especially for stateless services like `UserService` and `OrderService`.

7. **Strategy Pattern** (potentially in `PaymentService`):
   - The `PaymentService` could apply the Strategy pattern to handle multiple payment methods (e.g., credit card, UPI, wallet) by creating different strategy classes for each payment type. This approach allows `PaymentService` to select a payment strategy at runtime based on the user’s choice, making it adaptable to various payment types.

8. **Observer Pattern** (potentially for `NotificationService`):
   - If the application has a real-time notification system for order status updates, the Observer pattern can be used. Here, the `NotificationService` might register various observers (like SMS, email, or app notifications) to listen for order updates, allowing multiple channels to receive updates when an order's status changes.

9. **State Pattern** (for `OrderService` and `PaymentService` statuses):
   - The `OrderService` could apply the State pattern to manage the lifecycle of an order (`placed`, `in-progress`, `completed`, `canceled`). Similarly, `PaymentService` could manage payment statuses (`initiated`, `completed`, `failed`). By encapsulating state-specific behavior within each state, this pattern makes it easier to manage complex transitions and add new states without modifying existing code.

10. **Facade Pattern**:
    - The `FlashSaleService`, `CartService`, and `OrderService` could collectively be wrapped by a higher-level service to handle complex workflows for flash sales, like validating product availability, handling payments, and assigning delivery, providing a simplified interface for these interdependent operations.

11. **Template Method Pattern** (potentially in `OrderService`):
    - The `OrderService` could use a template method if there’s a predefined set of steps for processing orders (e.g., validating cart, confirming payment, updating inventory, assigning delivery). Each of these steps could be defined in a base class, with some customizable by subclasses or even skipped, creating a standardized, yet flexible, order processing flow. 

Each of these patterns contributes to a scalable, maintainable, and testable design that helps meet the demands of a high-performance online food delivery system, as outlined in the provided API endpoints and services.