# Amazon Shopping Cart - Low Level Design (Machine Coding)

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Requirements](#requirements)
3. [Database Design with Explanations](#database-design-with-explanations)
4. [Design Patterns & SOLID](#design-patterns--solid)
5. [Code Implementation](#code-implementation)
6. [Edge Cases & Tests](#edge-cases--tests)

---

## Problem Statement

Design a shopping cart system (Amazon-style) supporting add/remove items, quantity updates, coupon application, guest carts, cart merging on login, and real-time price/inventory validation.

---

## Requirements

### Functional
1. Add/remove/update items in cart
2. Guest cart (anonymous users, session-based)
3. Merge guest cart with user cart on login
4. Apply coupon codes (percentage, flat, BOGO)
5. Real-time inventory and price validation
6. Calculate totals with tax and shipping
7. Save for later (wishlist-like feature)

### Non-Functional
- < 100ms for cart operations
- 100M users, 30K RPS during peak sales
- Cart persists 90 days (user), 7 days (guest)

---

## Database Design with Explanations

```sql
-- ============================================================================
-- TABLE: carts
-- WHY: Every user (or guest session) has exactly one ACTIVE cart.
--      This is the parent table that owns cart items.
--
-- WHY support both user_id and guest_session_id?
--   Because anonymous users can shop without logging in.
--   - Logged-in user: user_id is set, guest_session_id is NULL
--   - Guest user: user_id is NULL, guest_session_id is set
--   On login, we MERGE the guest cart into the user cart.
--
-- WHY store subtotal/total_amount here (denormalized)?
--   Performance. The cart is displayed on EVERY page (header icon shows
--   "Cart (3 items) - $59.99"). Recalculating from cart_items every time
--   is wasteful. We update these on each cart operation.
-- ============================================================================
CREATE TABLE carts (
    cart_id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(user_id),      -- NULL for guests
    guest_session_id VARCHAR(100),                -- NULL for logged-in users
    
    cart_status VARCHAR(20) DEFAULT 'ACTIVE',
    -- ACTIVE     → User is shopping
    -- CHECKED_OUT → Converted to order
    -- ABANDONED   → Expired (cleanup job)
    
    -- Pricing (denormalized for fast display)
    item_count INTEGER DEFAULT 0,
    subtotal DECIMAL(10,2) DEFAULT 0,
    discount_amount DECIMAL(10,2) DEFAULT 0,
    tax_amount DECIMAL(10,2) DEFAULT 0,
    shipping_amount DECIMAL(10,2) DEFAULT 0,
    total_amount DECIMAL(10,2) DEFAULT 0,
    
    -- Coupon
    applied_coupon_code VARCHAR(50),
    
    -- Lifecycle
    expires_at TIMESTAMP,                         -- 90 days for users, 7 for guests
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    -- Only ONE active cart per user
    -- WHY? A user shouldn't have multiple active carts. This constraint
    -- prevents bugs that create duplicate carts.
    UNIQUE(user_id) -- Note: Doesn't apply to guests (user_id is NULL)
);

-- WHY these separate indexes? Two query patterns:
-- 1. Logged-in user: find cart by user_id
-- 2. Guest user: find cart by session_id
CREATE INDEX idx_carts_user ON carts(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX idx_carts_guest ON carts(guest_session_id) WHERE guest_session_id IS NOT NULL;

-- WHY? Background job finds expired carts to clean up
CREATE INDEX idx_carts_expires ON carts(expires_at) WHERE cart_status = 'ACTIVE';

-- ============================================================================
-- TABLE: cart_items
-- WHY: Each row = one product in the cart with its quantity and price.
--      Separated from carts because:
--      1. A cart has 0-N items (one-to-many)
--      2. Each item has its own product reference, quantity, price
--      3. Items are added/removed independently
--
-- RELATIONSHIP: cart_items → carts (Many-to-One, ON DELETE CASCADE)
--   WHY CASCADE? When a cart is deleted (expired), all its items go too.
--   No orphan items.
--
-- WHY store unit_price here instead of reading from products table?
--   PRICE SNAPSHOTTING. The price at add-to-cart time is what matters.
--   If a product price changes after the user adds it, they should see
--   the original price (unless we explicitly reprice on next cart view).
--   This prevents the "price changed in my cart" shock.
-- ============================================================================
CREATE TABLE cart_items (
    item_id UUID PRIMARY KEY,
    cart_id UUID NOT NULL REFERENCES carts(cart_id) ON DELETE CASCADE,
    product_id UUID NOT NULL,
    product_name VARCHAR(300) NOT NULL,       -- Snapshot: product may be renamed
    
    quantity INTEGER NOT NULL DEFAULT 1 CHECK (quantity > 0),
    max_quantity INTEGER DEFAULT 10,          -- Per-product limit
    
    -- Price snapshot at time of adding to cart
    unit_price DECIMAL(10,2) NOT NULL,
    line_total DECIMAL(10,2) NOT NULL,        -- unit_price × quantity
    
    -- Where is this item?
    item_status VARCHAR(20) DEFAULT 'IN_CART',
    -- IN_CART        → Normal cart item
    -- SAVED_FOR_LATER → Moved to "save for later" section
    -- OUT_OF_STOCK    → Marked when inventory check fails
    
    added_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    
    -- UNIQUE: Same product can't appear twice in a cart.
    -- WHY? If user adds "Widget" again, we INCREMENT quantity, not add a new row.
    -- This prevents UI confusion and simplifies total calculation.
    UNIQUE(cart_id, product_id)
);

CREATE INDEX idx_cart_items_cart ON cart_items(cart_id);

-- ============================================================================
-- TABLE: coupons
-- WHY: Separate table because coupons are an independent entity.
--      Multiple carts can use the same coupon. Coupons have their own
--      validity period, usage limits, and discount rules.
--
-- WHY NOT just store discount as a simple percentage?
--   Because coupons come in many types:
--   - Percentage (10% off)
--   - Flat discount ($20 off)
--   - BOGO (Buy one get one free)
--   - Free shipping
--   Strategy pattern handles this — coupon_type maps to a discount strategy.
-- ============================================================================
CREATE TABLE coupons (
    coupon_id UUID PRIMARY KEY,
    coupon_code VARCHAR(50) UNIQUE NOT NULL,
    coupon_type VARCHAR(20) NOT NULL,         -- PERCENTAGE, FLAT, BOGO, FREE_SHIPPING
    discount_value DECIMAL(10,2) NOT NULL,    -- 10 (=10% or $10 depending on type)
    min_order_amount DECIMAL(10,2) DEFAULT 0, -- Min cart total to apply
    max_discount DECIMAL(10,2),               -- Cap on discount amount
    max_uses INTEGER,                         -- Total uses across all users
    current_uses INTEGER DEFAULT 0,
    max_uses_per_user INTEGER DEFAULT 1,
    valid_from TIMESTAMP NOT NULL,
    valid_until TIMESTAMP NOT NULL,
    is_active BOOLEAN DEFAULT true,
    created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_coupons_code ON coupons(coupon_code) WHERE is_active = true;
```

### Entity Relationship Summary

```
carts (1) ──── (N) cart_items
  │
  │── applied_coupon_code → coupons (lookup by code)
  │
  │── user_id → users (Many-to-One, nullable)
  │── guest_session_id (for anonymous users)

KEY DESIGN DECISIONS:
- UNIQUE(cart_id, product_id) on cart_items: prevents duplicate products in cart.
  "Add same item again" = increment quantity, not new row.

- cart.user_id is NULLABLE: Supports guest carts without creating dummy user accounts.

- Price snapshots in cart_items: User sees stable prices while shopping.
  Price validation happens at checkout (not on every page load).

- Coupon is referenced by code (not FK): Carts store the applied code string.
  On checkout, we validate the coupon is still valid and not exhausted.
```

---

## Design Patterns & SOLID

| Pattern | Where | Why |
|---------|-------|-----|
| **Strategy** | DiscountStrategy (Percentage, Flat, BOGO) | Different coupon types calculate discounts differently. Same interface. |
| **Observer** | CartEventObserver | Notify analytics, recommendations, inventory on cart changes. |
| **Factory** | DiscountStrategyFactory | Create correct strategy from coupon type. |
| **Template Method** | CartOperationTemplate (validate → execute → recalculate) | All cart operations follow the same skeleton. |

| SOLID | How |
|-------|-----|
| **S** | `CartService` manages cart. `PricingService` calculates totals. `CouponService` handles coupons. |
| **O** | New coupon type (e.g., "Bundle discount") = new `DiscountStrategy`. No changes to `CartService`. |
| **L** | Any `DiscountStrategy` is interchangeable. `PricingService` works with all. |
| **I** | `DiscountStrategy` has one method: `applyDiscount()`. Focused. |
| **D** | `CartService` depends on `DiscountStrategy` interface, not `PercentageDiscount` class. |

---

## Code Implementation

### Enums & Value Objects

```java
public enum CartItemStatus {
    IN_CART,
    SAVED_FOR_LATER,
    OUT_OF_STOCK
}

public enum CouponType {
    PERCENTAGE,     // 10% off
    FLAT,           // $20 off
    BOGO,           // Buy one get one free
    FREE_SHIPPING   // Free shipping
}
```

### Models

```java
// ============================================================================
// MODEL: CartItem
// OOP: Encapsulation — quantity changes go through updateQuantity() which
// validates and recalculates line_total. Can't set quantity to 0 or negative.
// ============================================================================
public class CartItem {
    private final String itemId;
    private final String productId;
    private final String productName;
    private final BigDecimal unitPrice;
    private final int maxQuantity;

    private int quantity;
    private BigDecimal lineTotal;
    private CartItemStatus status;

    public CartItem(String itemId, String productId, String productName,
                    BigDecimal unitPrice, int quantity, int maxQuantity) {
        this.itemId = itemId;
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.maxQuantity = maxQuantity;
        this.status = CartItemStatus.IN_CART;
        updateQuantity(quantity);
    }

    /**
     * Update quantity with validation. Recalculates line total.
     * WHY a method instead of setter? Encapsulation:
     * - Enforces min/max constraints
     * - Automatically recalculates line total
     * - Prevents invalid states (quantity=0 or quantity=999)
     */
    public void updateQuantity(int newQuantity) {
        if (newQuantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (newQuantity > maxQuantity) {
            throw new MaxQuantityExceededException(
                "Max " + maxQuantity + " units per product");
        }
        this.quantity = newQuantity;
        this.lineTotal = unitPrice.multiply(BigDecimal.valueOf(quantity));
    }

    public void markOutOfStock() { this.status = CartItemStatus.OUT_OF_STOCK; }
    public void saveForLater() { this.status = CartItemStatus.SAVED_FOR_LATER; }
    public void moveToCart() { this.status = CartItemStatus.IN_CART; }

    // Getters
    public String getItemId() { return itemId; }
    public String getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public BigDecimal getLineTotal() { return lineTotal; }
    public CartItemStatus getStatus() { return status; }
    public boolean isInCart() { return status == CartItemStatus.IN_CART; }
}

// ============================================================================
// MODEL: Cart
// Manages its own items collection. Recalculates totals on every change.
// OOP: Composition — Cart HAS items. Encapsulation — totals auto-recalculate.
// ============================================================================
public class Cart {
    private final String cartId;
    private final String userId;          // Null for guests
    private final String guestSessionId;  // Null for logged-in users
    
    private final Map<String, CartItem> items; // productId → CartItem
    
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal shippingAmount;
    private BigDecimal totalAmount;
    private String appliedCouponCode;

    public Cart(String cartId, String userId, String guestSessionId) {
        this.cartId = cartId;
        this.userId = userId;
        this.guestSessionId = guestSessionId;
        this.items = new LinkedHashMap<>(); // Preserves insertion order
        this.subtotal = BigDecimal.ZERO;
        this.discountAmount = BigDecimal.ZERO;
        this.taxAmount = BigDecimal.ZERO;
        this.shippingAmount = BigDecimal.ZERO;
        this.totalAmount = BigDecimal.ZERO;
    }

    /**
     * Add item to cart. If product already exists, increment quantity.
     * WHY check existing? UNIQUE(cart_id, product_id) in DB.
     * Same behavior in code: adding "Widget" twice = quantity 2, not two rows.
     */
    public void addItem(String productId, String productName, BigDecimal unitPrice,
                        int quantity, int maxQuantity) {
        if (items.containsKey(productId)) {
            CartItem existing = items.get(productId);
            existing.updateQuantity(existing.getQuantity() + quantity);
        } else {
            CartItem newItem = new CartItem(
                UUID.randomUUID().toString(), productId, productName,
                unitPrice, quantity, maxQuantity);
            items.put(productId, newItem);
        }
        recalculate();
    }

    public void removeItem(String productId) {
        items.remove(productId);
        recalculate();
    }

    public void updateItemQuantity(String productId, int quantity) {
        CartItem item = items.get(productId);
        if (item == null) throw new ItemNotFoundException("Product not in cart: " + productId);
        item.updateQuantity(quantity);
        recalculate();
    }

    public void saveItemForLater(String productId) {
        CartItem item = items.get(productId);
        if (item == null) throw new ItemNotFoundException(productId);
        item.saveForLater();
        recalculate(); // Saved items don't count toward total
    }

    public void moveItemToCart(String productId) {
        CartItem item = items.get(productId);
        if (item == null) throw new ItemNotFoundException(productId);
        item.moveToCart();
        recalculate();
    }

    /**
     * Merge another cart into this one (guest cart → user cart on login).
     * If both carts have the same product, keep the HIGHER quantity.
     */
    public void mergeWith(Cart other) {
        for (CartItem otherItem : other.getActiveItems()) {
            if (items.containsKey(otherItem.getProductId())) {
                CartItem existing = items.get(otherItem.getProductId());
                int mergedQty = Math.max(existing.getQuantity(), otherItem.getQuantity());
                existing.updateQuantity(mergedQty);
            } else {
                items.put(otherItem.getProductId(), otherItem);
            }
        }
        recalculate();
    }

    /**
     * Recalculate all totals from items.
     * Called after every modification to keep totals in sync.
     */
    private void recalculate() {
        this.subtotal = getActiveItems().stream()
            .map(CartItem::getLineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        // Tax and shipping would be calculated by external services
        this.totalAmount = subtotal
            .subtract(discountAmount)
            .add(taxAmount)
            .add(shippingAmount);
    }

    public void applyDiscount(BigDecimal discount) {
        this.discountAmount = discount;
        recalculate();
    }

    public void applyCoupon(String code) { this.appliedCouponCode = code; }
    public void removeCoupon() { 
        this.appliedCouponCode = null; 
        this.discountAmount = BigDecimal.ZERO;
        recalculate();
    }

    public List<CartItem> getActiveItems() {
        return items.values().stream()
            .filter(CartItem::isInCart)
            .collect(Collectors.toList());
    }

    public List<CartItem> getSavedForLater() {
        return items.values().stream()
            .filter(i -> i.getStatus() == CartItemStatus.SAVED_FOR_LATER)
            .collect(Collectors.toList());
    }

    public int getItemCount() {
        return getActiveItems().stream().mapToInt(CartItem::getQuantity).sum();
    }

    public boolean isEmpty() { return getActiveItems().isEmpty(); }

    // Getters
    public String getCartId() { return cartId; }
    public String getUserId() { return userId; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getAppliedCouponCode() { return appliedCouponCode; }
}
```

### Coupon Strategy

```java
// ============================================================================
// INTERFACE: DiscountStrategy
// PATTERN: Strategy — Different coupon types calculate discounts differently.
// SOLID (I): One method. No bloat.
// ============================================================================
public interface DiscountStrategy {
    BigDecimal calculateDiscount(Cart cart, Coupon coupon);
}

public class PercentageDiscountStrategy implements DiscountStrategy {
    @Override
    public BigDecimal calculateDiscount(Cart cart, Coupon coupon) {
        BigDecimal discount = cart.getSubtotal()
            .multiply(coupon.getDiscountValue())
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        
        // Cap at max discount
        if (coupon.getMaxDiscount() != null) {
            discount = discount.min(coupon.getMaxDiscount());
        }
        return discount;
    }
}

public class FlatDiscountStrategy implements DiscountStrategy {
    @Override
    public BigDecimal calculateDiscount(Cart cart, Coupon coupon) {
        // Flat discount can't exceed subtotal
        return coupon.getDiscountValue().min(cart.getSubtotal());
    }
}

public class BuyOneGetOneFreeStrategy implements DiscountStrategy {
    @Override
    public BigDecimal calculateDiscount(Cart cart, Coupon coupon) {
        // BOGO: For every 2 items of the same product, one is free
        BigDecimal discount = BigDecimal.ZERO;
        for (CartItem item : cart.getActiveItems()) {
            int freeItems = item.getQuantity() / 2;
            discount = discount.add(
                item.getUnitPrice().multiply(BigDecimal.valueOf(freeItems)));
        }
        return discount;
    }
}

// ============================================================================
// PATTERN: Factory — Creates discount strategy from coupon type.
// ============================================================================
public class DiscountStrategyFactory {
    public DiscountStrategy create(CouponType type) {
        return switch (type) {
            case PERCENTAGE -> new PercentageDiscountStrategy();
            case FLAT -> new FlatDiscountStrategy();
            case BOGO -> new BuyOneGetOneFreeStrategy();
            case FREE_SHIPPING -> (cart, coupon) -> BigDecimal.ZERO; // Handled separately
        };
    }
}
```

### Core Services

```java
// ============================================================================
// CartService — Orchestrates all cart operations.
// SOLID (S): Cart operations only. No pricing, no coupon validation.
// SOLID (D): Depends on DiscountStrategy interface for coupon application.
// ============================================================================
public class CartService {

    private final CartRepository cartRepository;
    private final ProductService productService;
    private final CouponService couponService;
    private final DiscountStrategyFactory discountFactory;
    private final List<CartEventObserver> observers;

    public CartService(CartRepository cartRepository, ProductService productService,
                       CouponService couponService, DiscountStrategyFactory discountFactory) {
        this.cartRepository = cartRepository;
        this.productService = productService;
        this.couponService = couponService;
        this.discountFactory = discountFactory;
        this.observers = new ArrayList<>();
    }

    public void addObserver(CartEventObserver observer) {
        observers.add(observer);
    }

    /**
     * Get or create cart for a user/guest.
     */
    public Cart getOrCreateCart(String userId, String guestSessionId) {
        if (userId != null) {
            return cartRepository.findActiveByUserId(userId)
                .orElseGet(() -> {
                    Cart cart = new Cart(UUID.randomUUID().toString(), userId, null);
                    cartRepository.save(cart);
                    return cart;
                });
        }
        return cartRepository.findActiveByGuestSession(guestSessionId)
            .orElseGet(() -> {
                Cart cart = new Cart(UUID.randomUUID().toString(), null, guestSessionId);
                cartRepository.save(cart);
                return cart;
            });
    }

    /**
     * Add item to cart with real-time validation.
     */
    public Cart addItem(String cartId, String productId, int quantity) {
        Cart cart = cartRepository.findById(cartId).orElseThrow();
        
        // Validate product exists and has stock
        Product product = productService.getProduct(productId);
        if (product.getStock() < quantity) {
            throw new InsufficientStockException(
                "Only " + product.getStock() + " available for " + product.getName());
        }

        cart.addItem(productId, product.getName(), product.getPrice(),
                     quantity, product.getMaxPerOrder());
        
        // Reapply coupon if one exists (total changed, discount may change)
        reapplyCoupon(cart);
        
        cartRepository.save(cart);
        notifyObservers(cart, "ITEM_ADDED", productId);
        return cart;
    }

    public Cart removeItem(String cartId, String productId) {
        Cart cart = cartRepository.findById(cartId).orElseThrow();
        cart.removeItem(productId);
        reapplyCoupon(cart);
        cartRepository.save(cart);
        notifyObservers(cart, "ITEM_REMOVED", productId);
        return cart;
    }

    public Cart updateQuantity(String cartId, String productId, int quantity) {
        Cart cart = cartRepository.findById(cartId).orElseThrow();
        
        Product product = productService.getProduct(productId);
        if (product.getStock() < quantity) {
            throw new InsufficientStockException("Only " + product.getStock() + " available");
        }
        
        cart.updateItemQuantity(productId, quantity);
        reapplyCoupon(cart);
        cartRepository.save(cart);
        return cart;
    }

    /**
     * Apply coupon to cart.
     * Validates coupon and uses Strategy pattern for discount calculation.
     */
    public Cart applyCoupon(String cartId, String couponCode) {
        Cart cart = cartRepository.findById(cartId).orElseThrow();
        
        Coupon coupon = couponService.validate(couponCode, cart.getUserId(), cart.getSubtotal());
        
        // Factory creates the right strategy based on coupon type
        DiscountStrategy strategy = discountFactory.create(coupon.getType());
        BigDecimal discount = strategy.calculateDiscount(cart, coupon);
        
        cart.applyCoupon(couponCode);
        cart.applyDiscount(discount);
        
        cartRepository.save(cart);
        return cart;
    }

    /**
     * Merge guest cart into user cart (called on login).
     * WHY? User may have added items while browsing as guest.
     * On login, those items should appear in their account cart.
     */
    public Cart mergeGuestCart(String userId, String guestSessionId) {
        Cart userCart = getOrCreateCart(userId, null);
        Optional<Cart> guestCart = cartRepository.findActiveByGuestSession(guestSessionId);
        
        guestCart.ifPresent(gc -> {
            userCart.mergeWith(gc);
            // Deactivate guest cart after merge
            cartRepository.delete(gc.getCartId());
            cartRepository.save(userCart);
        });
        
        return userCart;
    }

    /** Reapply existing coupon after cart changes (total might have changed). */
    private void reapplyCoupon(Cart cart) {
        if (cart.getAppliedCouponCode() != null) {
            try {
                Coupon coupon = couponService.findByCode(cart.getAppliedCouponCode());
                DiscountStrategy strategy = discountFactory.create(coupon.getType());
                BigDecimal discount = strategy.calculateDiscount(cart, coupon);
                cart.applyDiscount(discount);
            } catch (Exception e) {
                // Coupon no longer valid → remove it silently
                cart.removeCoupon();
            }
        }
    }

    private void notifyObservers(Cart cart, String event, String productId) {
        for (CartEventObserver obs : observers) {
            obs.onCartEvent(cart, event, productId);
        }
    }
}
```

### Observer

```java
public interface CartEventObserver {
    void onCartEvent(Cart cart, String event, String productId);
}

// "Customers who added X also bought Y" — drives recommendations
public class RecommendationObserver implements CartEventObserver {
    @Override
    public void onCartEvent(Cart cart, String event, String productId) {
        if ("ITEM_ADDED".equals(event)) {
            System.out.println("📊 Update recommendations model for product: " + productId);
        }
    }
}

// Track abandoned carts for remarketing emails
public class AbandonedCartObserver implements CartEventObserver {
    @Override
    public void onCartEvent(Cart cart, String event, String productId) {
        System.out.println("📧 Reset abandonment timer for cart: " + cart.getCartId());
    }
}
```

### Demo

```java
public class ShoppingCartDemo {
    public static void main(String[] args) {
        // Setup
        CartService cartService = new CartService(cartRepo, productService, 
            couponService, new DiscountStrategyFactory());
        cartService.addObserver(new RecommendationObserver());
        cartService.addObserver(new AbandonedCartObserver());

        // 1. Guest adds items
        Cart guestCart = cartService.getOrCreateCart(null, "session-abc");
        cartService.addItem(guestCart.getCartId(), "prod-iphone", 1);
        cartService.addItem(guestCart.getCartId(), "prod-case", 2);
        System.out.println("Guest cart: " + guestCart.getItemCount() + " items, ₹" 
            + guestCart.getSubtotal());

        // 2. Guest logs in → merge
        Cart userCart = cartService.mergeGuestCart("user-1", "session-abc");
        System.out.println("After merge: " + userCart.getItemCount() + " items");

        // 3. Apply coupon (Strategy pattern in action)
        cartService.applyCoupon(userCart.getCartId(), "SAVE10");
        System.out.println("After coupon: ₹" + userCart.getTotalAmount());

        // 4. Save for later
        cartService.saveForLater(userCart.getCartId(), "prod-case");
        System.out.println("Active items: " + userCart.getItemCount());
        System.out.println("Saved items: " + userCart.getSavedForLater().size());
    }
}
```

---

## Edge Cases & Tests

```java
// 1. Add same product twice → quantity increases, not duplicate
@Test
void addItem_sameProductTwice_incrementsQuantity() {
    Cart cart = new Cart("c1", "u1", null);
    cart.addItem("prod-1", "Widget", new BigDecimal("10.00"), 2, 10);
    cart.addItem("prod-1", "Widget", new BigDecimal("10.00"), 3, 10);
    
    assertEquals(1, cart.getActiveItems().size()); // Still 1 product
    assertEquals(5, cart.getActiveItems().get(0).getQuantity()); // But 5 units
    assertEquals(new BigDecimal("50.00"), cart.getSubtotal());
}

// 2. Exceeding max quantity per product
@Test
void updateQuantity_exceedsMax_throwsException() {
    Cart cart = new Cart("c1", "u1", null);
    cart.addItem("prod-1", "Widget", new BigDecimal("10.00"), 1, 5);
    
    assertThrows(MaxQuantityExceededException.class, () ->
        cart.updateItemQuantity("prod-1", 6)); // Max is 5
}

// 3. Guest cart merge keeps higher quantity
@Test
void mergeCart_sameProduct_keepsHigherQuantity() {
    Cart userCart = new Cart("c1", "u1", null);
    userCart.addItem("prod-1", "Widget", new BigDecimal("10.00"), 2, 10);
    
    Cart guestCart = new Cart("c2", null, "session-1");
    guestCart.addItem("prod-1", "Widget", new BigDecimal("10.00"), 5, 10);
    
    userCart.mergeWith(guestCart);
    
    assertEquals(5, userCart.getActiveItems().get(0).getQuantity()); // Max(2, 5) = 5
}

// 4. Percentage coupon with max discount cap
@Test
void percentageCoupon_cappedAtMax() {
    Cart cart = new Cart("c1", "u1", null);
    cart.addItem("prod-1", "Laptop", new BigDecimal("1000.00"), 1, 1);
    
    Coupon coupon = new Coupon("SAVE10", CouponType.PERCENTAGE, 
        new BigDecimal("10"), new BigDecimal("50")); // 10% off, max $50
    
    DiscountStrategy strategy = new PercentageDiscountStrategy();
    BigDecimal discount = strategy.calculateDiscount(cart, coupon);
    
    // 10% of $1000 = $100, but capped at $50
    assertEquals(new BigDecimal("50.00"), discount);
}

// 5. BOGO discount calculation
@Test
void bogoCoupon_calculatesCorrectly() {
    Cart cart = new Cart("c1", "u1", null);
    cart.addItem("prod-1", "T-Shirt", new BigDecimal("30.00"), 3, 10);
    
    Coupon coupon = new Coupon("BOGO", CouponType.BOGO, BigDecimal.ZERO, null);
    DiscountStrategy strategy = new BuyOneGetOneFreeStrategy();
    BigDecimal discount = strategy.calculateDiscount(cart, coupon);
    
    // 3 shirts: buy 2, get 1 free → discount = 1 × $30 = $30
    assertEquals(new BigDecimal("30.00"), discount);
}

// 6. Removing item invalidates coupon if below minimum
@Test
void removeItem_belowCouponMinimum_removesCoupon() {
    // Coupon requires min $100 order
    cartService.applyCoupon(cartId, "MIN100");
    cartService.removeItem(cartId, "prod-expensive"); // Cart drops below $100
    
    Cart cart = cartService.getCart(cartId);
    assertNull(cart.getAppliedCouponCode()); // Coupon auto-removed
}

// 7. Saved-for-later items don't count in totals
@Test
void saveForLater_excludedFromTotal() {
    Cart cart = new Cart("c1", "u1", null);
    cart.addItem("prod-1", "A", new BigDecimal("50.00"), 1, 10);
    cart.addItem("prod-2", "B", new BigDecimal("30.00"), 1, 10);
    assertEquals(new BigDecimal("80.00"), cart.getSubtotal());
    
    cart.saveItemForLater("prod-2");
    assertEquals(new BigDecimal("50.00"), cart.getSubtotal()); // Only prod-1
    assertEquals(1, cart.getSavedForLater().size()); // prod-2 saved
}
```

---

## Summary

```
┌─────────────────────────────────────────────────────────────────┐
│  DESIGN PATTERNS                                                │
│                                                                 │
│  Strategy  → DiscountStrategy (Percentage, Flat, BOGO)         │
│  Factory   → DiscountStrategyFactory (from coupon type)        │
│  Observer  → CartEventObserver (recommendations, analytics)    │
│  Template  → validate → execute → recalculate → notify         │
│                                                                 │
│  SOLID                                                          │
│                                                                 │
│  S → CartService, CouponService, PricingService separated      │
│  O → New coupon type = new DiscountStrategy class              │
│  L → All discount strategies interchangeable                   │
│  I → DiscountStrategy has 1 method                             │
│  D → CartService depends on strategy interface                 │
│                                                                 │
│  KEY INTERVIEW POINTS                                           │
│                                                                 │
│  1. Guest cart merge on login (real Amazon feature)            │
│  2. Price snapshotting (unit_price in cart_items)              │
│  3. UNIQUE(cart_id, product_id) prevents duplicate items       │
│  4. Coupon revalidation after cart changes                     │
│  5. Saved-for-later excluded from totals                       │
│  6. Abandoned cart tracking (observer pattern)                 │
└─────────────────────────────────────────────────────────────────┘
```
