# Pizza Pricing Engine - Low Level Design (Machine Coding)

> **Interview lesson (L5 feedback):** Failures were not "wrong entities". Failures were: skip clarify → class explosion → static catalog → never wire end-to-end → don't inject pricing. This doc is the **expected answer**, structured like the parking-lot LLD.

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Clarifying Questions (ASK FIRST)](#clarifying-questions-ask-first)
3. [Requirements](#requirements)
4. [What NOT To Do (Interview Anti-Patterns)](#what-not-to-do-interview-anti-patterns)
5. [Data Model Design with Explanations](#data-model-design-with-explanations)
6. [Class Diagram](#class-diagram)
7. [Design Patterns Used](#design-patterns-used)
8. [SOLID Principles Applied](#solid-principles-applied)
9. [Code Implementation](#code-implementation)
10. [Request Flows](#request-flows)
11. [Edge Cases & Tests](#edge-cases--tests)
12. [Interview Talking Points](#interview-talking-points)
13. [Summary of Patterns & Principles](#summary-of-patterns--principles)
14. [Deep Dive: Why Each Pattern & Principle (Interview Learning)](#deep-dive-why-each-pattern--principle-interview-learning)

---

## Problem Statement

Design a **pricing engine** for a pizza delivery service. Price is calculated as:

```
pizza_price = base_price + size_price + sum(topping_prices)
order_price = sum(pizza_price for each pizza in order)
```

**Interview constraints:**
- In-memory first (no DB required for the coding round)
- Must be **extendable** (new base / size / topping without a new class per variant)
- Must support **multiple stores** with **different price catalogs**
- Pizza entity must **not** own pricing logic

---

## Clarifying Questions (ASK FIRST)

> Interviewer concern: *"Jumped into solution without clarifying pricing logic."*
> Ask these **before** writing classes (parking-lot style: requirements first, code second).

| # | Question | Why it matters | Default assumption |
|---|----------|----------------|--------------------|
| 1 | Price = base + size + toppings only? Tax / delivery / coupons? | Scope. Don't invent billing. | **No tax, coupon, delivery fee** |
| 2 | One order → multiple pizzas? | Need `Order` aggregate or single pizza? | **Yes, multiple pizzas** |
| 3 | Same topping twice (double cheese)? | `List` vs `Set` | **List — charge twice** |
| 4 | Fixed menu only? | Enums vs free strings | **Only listed enums** |
| 5 | Same prices all stores, or per-store? | Single catalog vs inject catalog | Start **single store**, then multi-store |
| 6 | Missing catalog price for a topping? | Silent `0` vs throw | **Throw exception — never 0** |
| 7 | Money type? | `int` cents vs `BigDecimal` | **`int` cents** (or BigDecimal if they prefer) |
| 8 | Pricing only, or kitchen / delivery too? | Stay on **core DI** | **Pricing only** |

**Say out loud:**
> "I'll assume: multi-pizza order, formula base+size+toppings, no tax/coupon, enum-only options, missing price throws, in-memory. Multi-store = inject catalog per store."

---

## Requirements

### Functional Requirements
1. Build a pizza from Base + Size + Toppings
2. Calculate pizza price from catalog lookups (base + size + toppings)
3. Calculate order total (sum of pizza prices)
4. Support per-store price catalogs (different cities / franchises)
5. Reject unknown / unpriced options loudly (exception, not silent 0)
6. Allow adding a new topping / size / base without a new Strategy class
7. Support optional promo pricing (percentage off) without changing Pizza

### Non-Functional Requirements
- In-memory, fast lookups (`EnumMap` O(1))
- Extensible for new product options and pricing strategies
- Testable via dependency injection (no static globals)
- Clear separation: domain data vs price data vs pricing behavior
- Thread-safe catalog registry for multi-store access

---

## What NOT To Do (Interview Anti-Patterns)

| Anti-pattern | Why interviewer rejects it | Correct move |
|--------------|----------------------------|--------------|
| Class per crust / size / topping | **Class explosion**. New SKU = code change | Enum + Map catalog |
| `Pizza.getPrice()` → static `PriceCatalog` | One global price world; multi-store broken | **Inject** catalog / strategy |
| Return `0` in switch default | Silent undercharge | **Throw** `PriceNotFoundException` |
| StoreManager / kitchen before pricing works | Peripheral before core | Wire Pizza → Service → Catalog **first** |
| Name SOLID without wiring | "Knew SRP but couldn't wire" | Show runnable end-to-end demo |

---

## Data Model Design with Explanations

> Interview said **in-memory / no DB**. Still design the **data model** the parking-lot way: what lives where, why, relationships. Below: in-memory structures first, then optional SQL if interviewer asks "how would you persist?".

### Why Each In-Memory Structure Exists

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ IN-MEMORY MODEL (coding round)                                              │
│                                                                             │
│  Enums: Base, Size, Topping                                                 │
│    WHY? Closed, type-safe product options. Compiler catches "chese" typos.  │
│    NOT classes — one class per topping = explosion (L5 fail mode).          │
│                                                                             │
│  PricingCatalog (per store)                                                 │
│    basePrices:    EnumMap<Base, Integer>                                    │
│    sizePrices:    EnumMap<Size, Integer>                                    │
│    toppingPrices: EnumMap<Topping, Integer>                                 │
│    WHY Maps? Prices are DATA. Change price ≠ change code.                   │
│    WHY EnumMap? O(1), compact, type-safe keys.                              │
│    WHY INSTANCE not static? Each store needs its own price set.             │
│                                                                             │
│  Pizza                                                                      │
│    base, size, toppings[]                                                   │
│    WHY no price field? Price depends on WHICH store's catalog is used.      │
│    Same Pizza @ SFO ≠ same Pizza @ NYC. Pizza is product config, not bill.  │
│                                                                             │
│  Order                                                                      │
│    pizzas[]                                                                 │
│    WHY aggregate? One checkout can have many pizzas; total = sum.           │
│                                                                             │
│  StoreCatalogRegistry                                                       │
│    storeId → PricingCatalog                                                 │
│    WHY? Multi-store: resolve which price world applies.                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Relationships (say these like parking-lot FK talk)

```
- Store HAS-A PricingCatalog (composition)
    WHY? Catalog lifetime tied to store's pricing config. Different stores,
    different prices. Pizza does NOT belong to a store — only catalog does.

- PricingService USES PricingStrategy (dependency / DI)
    WHY? Service orchestrates; strategy owns the algorithm. Swap catalog-based
    vs discount strategy without changing service.

- CatalogPricingStrategy HAS-A PricingCatalog (composition via injection)
    WHY? Strategy needs price data. Injected instance = multi-store + testable.

- Order HAS many Pizza (composition)
    WHY? Order owns its line items for this checkout. Pizzas don't exist
    outside the order in this simplified scope.

- Pizza references Base, Size, Topping (association to enums)
    WHY? Pizza describes WHAT was ordered. Price comes later from catalog.
```

### Optional Persistence Schema (if interviewer asks)

```sql
-- ============================================================================
-- TABLE: stores
-- WHY: Top-level entity for each franchise / city kitchen. Multiple stores
--      share the same product enums but NOT the same prices.
-- ============================================================================
CREATE TABLE stores (
    store_id    UUID PRIMARY KEY,
    store_code  VARCHAR(20) NOT NULL UNIQUE,  -- "SFO", "NYC"
    store_name  VARCHAR(200) NOT NULL,
    is_active   BOOLEAN DEFAULT true,
    created_at  TIMESTAMP DEFAULT NOW()
);

-- ============================================================================
-- TABLE: price_catalog_entries
-- WHY: Prices are DATA. One row per (store, option_type, option_key).
--      Adding a topping price = INSERT, not a new Java class.
--
-- RELATIONSHIP: price_catalog_entries → stores (Many-to-One)
--   WHY FK? Every price belongs to exactly one store. Prevents orphan prices.
--   WHY NOT columns on stores? Because option set grows (many toppings).
-- ============================================================================
CREATE TABLE price_catalog_entries (
    entry_id     UUID PRIMARY KEY,
    store_id     UUID NOT NULL REFERENCES stores(store_id) ON DELETE CASCADE,
    option_type  VARCHAR(20) NOT NULL,   -- BASE, SIZE, TOPPING
    option_key   VARCHAR(40) NOT NULL,   -- THIN, LARGE, CHEESE
    price_cents  INTEGER NOT NULL CHECK (price_cents >= 0),
    UNIQUE(store_id, option_type, option_key)
);

-- WHY this index? Hot path: load all prices for one store into EnumMaps at boot.
CREATE INDEX idx_catalog_store ON price_catalog_entries(store_id);

-- ============================================================================
-- TABLE: orders / order_pizzas
-- WHY: Persist what customer ordered. Snapshot store_id so historical orders
--      still make sense if catalog prices change later.
-- ============================================================================
CREATE TABLE orders (
    order_id     UUID PRIMARY KEY,
    store_id     UUID NOT NULL REFERENCES stores(store_id),
    total_cents  INTEGER NOT NULL,
    created_at   TIMESTAMP DEFAULT NOW()
);

CREATE TABLE order_pizzas (
    order_pizza_id UUID PRIMARY KEY,
    order_id       UUID NOT NULL REFERENCES orders(order_id) ON DELETE CASCADE,
    base_key       VARCHAR(40) NOT NULL,
    size_key       VARCHAR(40) NOT NULL,
    toppings       JSONB NOT NULL,          -- ["CHEESE","OLIVE"]
    line_price_cents INTEGER NOT NULL       -- snapshot at order time
);

-- WHY snapshot line_price_cents?
--   Same reason parking tickets snapshot rates: prices change; billing must not.
```

### Why In-Memory Maps in the Coding Round (not SQL)

1. Interview said no DB — maps are the catalog.
2. `EnumMap` gives type safety + O(1) like an indexed table.
3. Loading SQL rows into `PricingCatalog` later is a **repository detail**; Strategy/Service stay unchanged (Dependency Inversion).

---

## Class Diagram

```
┌──────────────────────────────────────────────────────────────────────────┐
│                           <<interface>>                                  │
│                         PricingStrategy                                  │
│  ──────────────────────────────────────────────                         │
│  + price(pizza): int                                                     │
├──────────────────────────────────────────────────────────────────────────┤
│           ▲                              ▲                               │
│           │                              │                               │
│  CatalogPricingStrategy      PercentageDiscountStrategy                  │
│  (uses PricingCatalog)       (decorates / wraps another strategy)        │
└──────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐  creates  ┌─────────────────┐  uses   ┌─────────────────┐
│ CatalogFactory  │──────────>│ PricingCatalog  │<────────│ CatalogPricing  │
│                 │           │                 │         │ Strategy        │
│ + default()     │           │ - basePrices{}  │         │ - catalog       │
│ + nycPremium()  │           │ - sizePrices{}  │         │ + price(pizza)  │
└─────────────────┘           │ - toppingPrices │         └─────────────────┘
                              │ + getBasePrice()│
                              │ + getSizePrice()│
                              │ + getTopping()  │
                              └─────────────────┘
                                       ▲
                                       │ looked up by
                              ┌────────┴────────┐
                              │ StoreCatalog    │
                              │ Registry        │
                              │ storeId→catalog │
                              │ + pricingService│
                              │   For(storeId)  │
                              └────────┬────────┘
                                       │ creates
                                       ▼
┌─────────────────┐  uses   ┌─────────────────┐  prices  ┌─────────────────┐
│  PizzaBuilder   │────────>│ PricingService  │─────────>│  Pizza / Order  │
│                 │         │                 │          │                 │
│ + base()        │         │ - strategy      │          │ Pizza: data only│
│ + size()        │         │ + pricePizza()  │          │ Order: pizzas[] │
│ + addTopping()  │         │ + priceOrder()  │          │ NO getPrice()   │
│ + build()       │         └─────────────────┘          │ with static cat │
└─────────────────┘                                      └─────────────────┘

┌──────────┐  ┌──────────┐  ┌──────────┐
│ <<enum>> │  │ <<enum>> │  │ <<enum>> │
│ Base     │  │ Size     │  │ Topping  │
└──────────┘  └──────────┘  └──────────┘
```

---

## Design Patterns Used

| Pattern | Where Used | Why |
|---------|-----------|-----|
| **Strategy** | `PricingStrategy`, `CatalogPricingStrategy`, `PercentageDiscountStrategy` | Different pricing algorithms (catalog lookup vs % off). Swap without changing `PricingService` or `Pizza`. **This is the core interview ask: inject pricing dependency.** |
| **Factory** | `CatalogFactory` | Centralize seeding of in-memory catalogs per store. Hides map construction from callers. |
| **Builder** | `Pizza.Builder` / `PizzaBuilder` | Pizza has base + size + variable toppings. Avoids telescoping constructors and invalid half-built pizzas. |
| **Registry** | `StoreCatalogRegistry` | `storeId → PricingCatalog`. Multi-store lookup without static globals. |
| **Decorator** (optional) | `PercentageDiscountStrategy` wraps another strategy | Add promo behavior without modifying catalog strategy (OCP). |
| **Dependency Injection** | Catalog → Strategy → Service | Fixes static-catalog bug; enables multi-store + unit tests. |

---

## SOLID Principles Applied

| Principle | How Applied |
|-----------|------------|
| **S - Single Responsibility** | `Pizza` = product config. `PricingCatalog` = price data. `PricingStrategy` = price algorithm. `PricingService` = orchestration. `StoreCatalogRegistry` = store→catalog map. Price change does **not** require editing `Pizza`. |
| **O - Open/Closed** | New topping = enum constant + catalog map entry. New pricing rule = new `PricingStrategy` impl (or decorator). Existing `PricingService` / `Pizza` **unchanged**. **Not** a new class per topping. |
| **L - Liskov Substitution** | Any `PricingStrategy` (`CatalogPricingStrategy`, `PercentageDiscountStrategy`) can replace another inside `PricingService`. Caller does not know which. |
| **I - Interface Segregation** | `PricingStrategy` has **one** method: `price(Pizza)`. Implementors are not forced to implement order APIs, store APIs, or persistence. |
| **D - Dependency Inversion** | `PricingService` depends on `PricingStrategy` **interface**, not `CatalogPricingStrategy` or raw maps. High-level module does not depend on low-level `EnumMap` details. |

---

## Code Implementation

### Enums

```java
// ============================================================================
// WHY ENUMS? Type safety. "CHEESE" as String can be "cheese" / "Cheese" / typo.
// Compiler catches invalid values. Matches parking-lot VehicleType / SpotSize.
//
// WHY NOT a class per topping?
//   Interview anti-pattern: ThinCrustStrategy, CheeseToppingStrategy, ...
//   New SKU must NOT require a new .java file. Enums + catalog maps scale.
// ============================================================================

public enum Base {
    THIN,
    THICK,
    CHEESE_BURST
}

public enum Size {
    SMALL,
    MEDIUM,
    LARGE
}

public enum Topping {
    CHEESE,
    MUSHROOM,
    OLIVE,
    JALAPENO,
    PEPPERONI,
    ONION
}
```

### Model Classes

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// ============================================================================
// MODEL: Pizza
// OOP: Encapsulation + Immutability after build.
// WHY no getPrice()? Price depends on injected catalog (which store?).
// Putting price on Pizza OR calling static PriceCatalog = L5 multi-store fail.
// ============================================================================
public final class Pizza {
    private final Base base;
    private final Size size;
    private final List<Topping> toppings;

    private Pizza(Base base, Size size, List<Topping> toppings) {
        this.base = base;
        this.size = size;
        this.toppings = List.copyOf(toppings);
    }

    public Base getBase() { return base; }
    public Size getSize() { return size; }
    public List<Topping> getToppings() { return toppings; }

    // ------------------------------------------------------------------------
    // PATTERN: Builder
    // WHY? Base + size required; toppings optional/variable.
    // Validates invariants at build() — never return half-built pizza.
    // ------------------------------------------------------------------------
    public static final class Builder {
        private Base base;
        private Size size;
        private final List<Topping> toppings = new ArrayList<>();

        public Builder base(Base base) {
            this.base = base;
            return this;
        }

        public Builder size(Size size) {
            this.size = size;
            return this;
        }

        public Builder addTopping(Topping topping) {
            this.toppings.add(Objects.requireNonNull(topping));
            return this;
        }

        public Builder addToppings(List<Topping> toppings) {
            for (Topping t : toppings) {
                addTopping(t);
            }
            return this;
        }

        public Pizza build() {
            if (base == null) {
                throw new IllegalArgumentException("base required");
            }
            if (size == null) {
                throw new IllegalArgumentException("size required");
            }
            return new Pizza(base, size, toppings);
        }
    }
}

// ============================================================================
// MODEL: Order
// WHY separate from Pizza? One checkout can contain many pizzas.
// Order total = sum of line prices (computed by PricingService, not stored here
// until you add persistence snapshots).
// ============================================================================
public final class Order {
    private final String orderId;
    private final List<Pizza> pizzas = new ArrayList<>();

    public Order(String orderId) {
        this.orderId = Objects.requireNonNull(orderId);
    }

    public void addPizza(Pizza pizza) {
        pizzas.add(Objects.requireNonNull(pizza));
    }

    public String getOrderId() { return orderId; }

    public List<Pizza> getPizzas() {
        return Collections.unmodifiableList(pizzas);
    }
}
```

### Pricing Catalog (Map lookups — instance, NOT static)

```java
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

// ============================================================================
// PricingCatalog
// WHY this class? Holds price DATA for ONE store / price world.
// WHY EnumMap? O(1) lookup, type-safe keys, compact memory.
// WHY INSTANCE fields (not static methods)?
//   Static = one global price set forever → multi-store impossible + hard tests.
//   Instance + DI = SFO catalog vs NYC catalog for the SAME Pizza object.
// ============================================================================
public final class PricingCatalog {
    private final Map<Base, Integer> basePrices;
    private final Map<Size, Integer> sizePrices;
    private final Map<Topping, Integer> toppingPrices;

    public PricingCatalog(
            Map<Base, Integer> basePrices,
            Map<Size, Integer> sizePrices,
            Map<Topping, Integer> toppingPrices) {
        this.basePrices = new EnumMap<>(Objects.requireNonNull(basePrices));
        this.sizePrices = new EnumMap<>(Objects.requireNonNull(sizePrices));
        this.toppingPrices = new EnumMap<>(Objects.requireNonNull(toppingPrices));
    }

    public int getBasePrice(Base base) {
        return requirePrice(basePrices, base, "base");
    }

    public int getSizePrice(Size size) {
        return requirePrice(sizePrices, size, "size");
    }

    public int getToppingPrice(Topping topping) {
        return requirePrice(toppingPrices, topping, "topping");
    }

    /**
     * WHY throw instead of return 0 / magic default 100?
     *   0  → silent undercharge (topping free). Bad product + interview fail.
     *   100 → invents prices. Hides misconfigured catalog.
     * Fail-fast so ops fix catalog data.
     */
    private static <T extends Enum<T>> int requirePrice(Map<T, Integer> map, T key, String kind) {
        Integer price = map.get(key);
        if (price == null) {
            throw new PriceNotFoundException("No price configured for " + kind + ": " + key);
        }
        return price;
    }
}

public class PriceNotFoundException extends RuntimeException {
    public PriceNotFoundException(String message) {
        super(message);
    }
}
```

### Factory — seed catalogs

```java
// ============================================================================
// PATTERN: Factory
// WHY? Callers should not hand-build three EnumMaps every time.
// Centralizes demo / test seed data. In production this becomes a repository
// that loads price_catalog_entries for a store_id into PricingCatalog.
// ============================================================================
public final class CatalogFactory {
    private CatalogFactory() {}

    public static PricingCatalog defaultStoreCatalog() {
        Map<Base, Integer> bases = new EnumMap<>(Base.class);
        bases.put(Base.THIN, 100);
        bases.put(Base.THICK, 120);
        bases.put(Base.CHEESE_BURST, 150);

        Map<Size, Integer> sizes = new EnumMap<>(Size.class);
        sizes.put(Size.SMALL, 50);
        sizes.put(Size.MEDIUM, 100);
        sizes.put(Size.LARGE, 150);

        Map<Topping, Integer> toppings = new EnumMap<>(Topping.class);
        toppings.put(Topping.CHEESE, 30);
        toppings.put(Topping.MUSHROOM, 40);
        toppings.put(Topping.OLIVE, 35);
        toppings.put(Topping.JALAPENO, 25);
        toppings.put(Topping.PEPPERONI, 50);
        toppings.put(Topping.ONION, 20);

        return new PricingCatalog(bases, sizes, toppings);
    }

    public static PricingCatalog nycPremiumCatalog() {
        // Same enums, different prices — proves multi-store without new Pizza classes
        Map<Base, Integer> bases = new EnumMap<>(Base.class);
        bases.put(Base.THIN, 140);
        bases.put(Base.THICK, 160);
        bases.put(Base.CHEESE_BURST, 200);

        Map<Size, Integer> sizes = new EnumMap<>(Size.class);
        sizes.put(Size.SMALL, 70);
        sizes.put(Size.MEDIUM, 130);
        sizes.put(Size.LARGE, 190);

        Map<Topping, Integer> toppings = new EnumMap<>(Topping.class);
        toppings.put(Topping.CHEESE, 40);
        toppings.put(Topping.MUSHROOM, 50);
        toppings.put(Topping.OLIVE, 45);
        toppings.put(Topping.JALAPENO, 35);
        toppings.put(Topping.PEPPERONI, 65);
        toppings.put(Topping.ONION, 30);

        return new PricingCatalog(bases, sizes, toppings);
    }
}
```

### Strategy + Service (core DI)

```java
// ============================================================================
// PATTERN: Strategy — THIS is the heart of the pizza LLD interview.
// PricingService depends on this interface (SOLID-D), not on EnumMaps.
// ============================================================================
public interface PricingStrategy {
    int price(Pizza pizza);
}

// ============================================================================
// CatalogPricingStrategy
// SOLID (S): Only computes base+size+toppings from an injected catalog.
// SOLID (D): Receives PricingCatalog via constructor (DI), never static calls.
// ============================================================================
public final class CatalogPricingStrategy implements PricingStrategy {
    private final PricingCatalog catalog;

    public CatalogPricingStrategy(PricingCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog required");
    }

    @Override
    public int price(Pizza pizza) {
        int total = catalog.getBasePrice(pizza.getBase());
        total += catalog.getSizePrice(pizza.getSize());
        for (Topping topping : pizza.getToppings()) {
            total += catalog.getToppingPrice(topping);
        }
        return total;
    }
}

// ============================================================================
// PATTERN: Decorator / wrapping Strategy
// WHY? Promo without modifying CatalogPricingStrategy or Pizza (OCP).
// ============================================================================
public final class PercentageDiscountStrategy implements PricingStrategy {
    private final PricingStrategy delegate;
    private final int percentOff; // 0-100

    public PercentageDiscountStrategy(PricingStrategy delegate, int percentOff) {
        if (percentOff < 0 || percentOff > 100) {
            throw new IllegalArgumentException("percentOff must be 0..100");
        }
        this.delegate = Objects.requireNonNull(delegate);
        this.percentOff = percentOff;
    }

    @Override
    public int price(Pizza pizza) {
        int base = delegate.price(pizza);
        return base - (base * percentOff / 100);
    }
}

// ============================================================================
// PricingService
// SOLID (S): Orchestrates pricing for pizza/order. Does not own price tables.
// SOLID (D): Depends on PricingStrategy abstraction.
// WHY exists separate from Strategy? Mirrors parking ParkingService:
//   service = use-case API; strategy = swappable algorithm.
// ============================================================================
public final class PricingService {
    private final PricingStrategy strategy;

    public PricingService(PricingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy);
    }

    public int pricePizza(Pizza pizza) {
        return strategy.price(Objects.requireNonNull(pizza));
    }

    public int priceOrder(Order order) {
        int total = 0;
        for (Pizza pizza : order.getPizzas()) {
            total += strategy.price(pizza);
        }
        return total;
    }
}
```

### Multi-store Registry

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ============================================================================
// StoreCatalogRegistry
// WHY? Multi-store follow-up in interview: same Pizza, different prices.
// WRONG response: put storeId on Pizza, or make PriceCatalog static.
// RIGHT response: registry resolves catalog; DI into strategy/service.
// ============================================================================
public final class StoreCatalogRegistry {
    private final Map<String, PricingCatalog> storeToCatalog = new ConcurrentHashMap<>();

    public void register(String storeId, PricingCatalog catalog) {
        storeToCatalog.put(
            Objects.requireNonNull(storeId),
            Objects.requireNonNull(catalog)
        );
    }

    public PricingCatalog getCatalog(String storeId) {
        PricingCatalog catalog = storeToCatalog.get(storeId);
        if (catalog == null) {
            throw new IllegalArgumentException("Unknown store: " + storeId);
        }
        return catalog;
    }

    /**
     * Wires Strategy + Service for a store in one place.
     * WHY here? Callers shouldn't forget DI steps. End-to-end wiring lives once.
     */
    public PricingService pricingServiceFor(String storeId) {
        PricingCatalog catalog = getCatalog(storeId);
        return new PricingService(new CatalogPricingStrategy(catalog));
    }
}
```

### Putting It Together (Demo)

```java
import java.util.List;

public class PizzaPricingDemo {
    public static void main(String[] args) {
        // ── 1. Seed per-store catalogs (in-memory) ───────────────────────
        StoreCatalogRegistry registry = new StoreCatalogRegistry();
        registry.register("SFO", CatalogFactory.defaultStoreCatalog());
        registry.register("NYC", CatalogFactory.nycPremiumCatalog());

        // ── 2. Build domain objects with Builder (no prices inside) ──────
        Pizza pizza1 = new Pizza.Builder()
            .base(Base.THIN)
            .size(Size.MEDIUM)
            .addTopping(Topping.CHEESE)
            .addTopping(Topping.OLIVE)
            .build();

        Pizza pizza2 = new Pizza.Builder()
            .base(Base.CHEESE_BURST)
            .size(Size.LARGE)
            .addToppings(List.of(Topping.PEPPERONI, Topping.MUSHROOM, Topping.JALAPENO))
            .build();

        Order order = new Order("ord-1");
        order.addPizza(pizza1);
        order.addPizza(pizza2);

        // ── 3. Wire pricing for stores (DI) ───────────────────────────────
        PricingService sfoPricing = registry.pricingServiceFor("SFO");
        PricingService nycPricing = registry.pricingServiceFor("NYC");

        // ── 4. Execute end-to-end ─────────────────────────────────────────
        System.out.println("SFO pizza1 = " + sfoPricing.pricePizza(pizza1));
        // THIN=100 + MEDIUM=100 + CHEESE=30 + OLIVE=35 = 265

        System.out.println("SFO order  = " + sfoPricing.priceOrder(order));
        System.out.println("NYC order  = " + nycPricing.priceOrder(order)); // different $

        // ── 5. Promo via Decorator strategy (OCP) ─────────────────────────
        PricingService sfoPromo = new PricingService(
            new PercentageDiscountStrategy(
                new CatalogPricingStrategy(registry.getCatalog("SFO")), 10));
        System.out.println("SFO pizza1 @10% off = " + sfoPromo.pricePizza(pizza1));
    }
}
```

**Critical WRONG vs RIGHT (say this):**

```java
// WRONG — static = one global price world (multi-store broken)
int p = PriceCatalog.getBasePrice(base);

// RIGHT — instance injected per store
PricingService svc = registry.pricingServiceFor("SFO");
int p = svc.pricePizza(pizza);
```

---

## Request Flows

### Price One Pizza Flow

```
Client builds Pizza (Builder)
    │
    ▼
Client chooses storeId ("SFO")
    │
    ▼
StoreCatalogRegistry.pricingServiceFor(storeId)
    │
    ├── getCatalog(storeId)                         [Registry]
    │
    ├── new CatalogPricingStrategy(catalog)         [Strategy + DI]
    │
    └── new PricingService(strategy)
            │
            ▼
PricingService.pricePizza(pizza)
    │
    ├── PricingStrategy.price(pizza)                [Strategy Pattern]
    │       │
    │       ├── catalog.getBasePrice(base)          [EnumMap lookup]
    │       ├── catalog.getSizePrice(size)
    │       └── Σ catalog.getToppingPrice(t)
    │               │
    │               └── missing key?
    │                     └── throw PriceNotFoundException   [never return 0]
    │
    └── return total cents
```

### Price Order Flow

```
Order (pizzas[])
    │
    ▼
PricingService.priceOrder(order)
    │
    ├── for each pizza:
    │       strategy.price(pizza)                   [reuse same Strategy]
    │
    └── return sum
```

### Multi-Store Same Pizza Flow

```
Same Pizza object
    │
    ├── registry.pricingServiceFor("SFO").pricePizza(pizza) → e.g. 230
    │
    └── registry.pricingServiceFor("NYC").pricePizza(pizza) → e.g. 310

WHY different?
  Different PricingCatalog instances injected into Strategy.
  Pizza unchanged. Catalog changed. That is the DI lesson.
```

### Promo Pricing Flow

```
CatalogPricingStrategy(catalog)
    │
    ▼ wrapped by
PercentageDiscountStrategy(delegate, 10%)          [Decorator / Strategy]
    │
    ▼ injected into
PricingService
    │
    └── pricePizza → delegate.price → apply % off
```

---

## Edge Cases & Tests

```java
import org.junit.jupiter.api.Test;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

// ============================================================================
// TESTS: Critical paths + interview edge cases.
// Each name = scenario + expected behavior (parking-lot style).
// ============================================================================

class PricingServiceTest {

    // 1. Happy path: base + size + toppings
    @Test
    void pricePizza_basePlusSizePlusToppings() {
        PricingService svc = new PricingService(
            new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog())
        );
        Pizza pizza = new Pizza.Builder()
            .base(Base.THIN)
            .size(Size.MEDIUM)
            .addTopping(Topping.CHEESE)
            .addTopping(Topping.OLIVE)
            .build();
        // 100 + 100 + 30 + 35 = 265
        assertEquals(265, svc.pricePizza(pizza));
    }

    // 2. Order sums all pizzas
    @Test
    void priceOrder_sumsAllPizzas() {
        PricingService svc = new PricingService(
            new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog())
        );
        Order order = new Order("o1");
        order.addPizza(new Pizza.Builder().base(Base.THIN).size(Size.SMALL).build()); // 150
        order.addPizza(new Pizza.Builder()
            .base(Base.THICK).size(Size.SMALL).addTopping(Topping.ONION).build());    // 190
        assertEquals(340, svc.priceOrder(order));
    }

    // 3. Empty toppings is valid (base + size only)
    @Test
    void pricePizza_noToppings_basePlusSizeOnly() {
        PricingService svc = new PricingService(
            new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog())
        );
        Pizza pizza = new Pizza.Builder().base(Base.THIN).size(Size.LARGE).build();
        assertEquals(250, svc.pricePizza(pizza)); // 100 + 150
    }

    // 4. Duplicate topping charged twice (List semantics)
    @Test
    void pricePizza_doubleCheese_chargesTwice() {
        PricingService svc = new PricingService(
            new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog())
        );
        Pizza pizza = new Pizza.Builder()
            .base(Base.THIN)
            .size(Size.SMALL)
            .addTopping(Topping.CHEESE)
            .addTopping(Topping.CHEESE)
            .build();
        // 100 + 50 + 30 + 30 = 210
        assertEquals(210, svc.pricePizza(pizza));
    }

    // 5. Missing catalog price MUST throw — never return 0
    @Test
    void missingToppingPrice_throwsNotZero() {
        Map<Base, Integer> bases = new EnumMap<>(Base.class);
        bases.put(Base.THIN, 100);
        Map<Size, Integer> sizes = new EnumMap<>(Size.class);
        sizes.put(Size.SMALL, 50);
        Map<Topping, Integer> toppings = new EnumMap<>(Topping.class);
        toppings.put(Topping.CHEESE, 30);
        // OLIVE intentionally missing

        PricingService svc = new PricingService(
            new CatalogPricingStrategy(new PricingCatalog(bases, sizes, toppings))
        );
        Pizza pizza = new Pizza.Builder()
            .base(Base.THIN).size(Size.SMALL).addTopping(Topping.OLIVE).build();

        assertThrows(PriceNotFoundException.class, () -> svc.pricePizza(pizza));
    }

    // 6. Builder rejects incomplete pizza
    @Test
    void builder_missingBase_throws() {
        assertThrows(IllegalArgumentException.class, () ->
            new Pizza.Builder().size(Size.SMALL).build());
    }

    // 7. Same pizza, different stores → different prices (DI proof)
    @Test
    void samePizza_differentStores_differentPrices() {
        StoreCatalogRegistry registry = new StoreCatalogRegistry();
        registry.register("SFO", CatalogFactory.defaultStoreCatalog());
        registry.register("NYC", CatalogFactory.nycPremiumCatalog());

        Pizza pizza = new Pizza.Builder()
            .base(Base.THIN).size(Size.MEDIUM).addTopping(Topping.CHEESE).build();

        int sfo = registry.pricingServiceFor("SFO").pricePizza(pizza); // 230
        int nyc = registry.pricingServiceFor("NYC").pricePizza(pizza); // 310

        assertEquals(230, sfo);
        assertEquals(310, nyc);
        assertNotEquals(sfo, nyc);
    }

    // 8. Unknown store throws
    @Test
    void registry_unknownStore_throws() {
        StoreCatalogRegistry registry = new StoreCatalogRegistry();
        assertThrows(IllegalArgumentException.class, () ->
            registry.pricingServiceFor("LAX"));
    }

    // 9. Percentage discount decorator
    @Test
    void promo_tenPercentOff_applies() {
        PricingStrategy base = new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog());
        PricingService svc = new PricingService(new PercentageDiscountStrategy(base, 10));
        Pizza pizza = new Pizza.Builder().base(Base.THIN).size(Size.SMALL).build(); // 150
        assertEquals(135, svc.pricePizza(pizza)); // 150 - 15
    }
}
```

### Edge-case table (say without being prompted)

| Case | Behavior |
|------|----------|
| Empty toppings | Valid — base + size only |
| Double cheese | Charge twice (`List`) |
| Null / missing base or size | Builder throws |
| Unknown storeId | `IllegalArgumentException` |
| Catalog missing price | `PriceNotFoundException` — **never 0** |
| New topping launched | Enum + map entry; no new Strategy class |
| Promo 10% off | Wrap with `PercentageDiscountStrategy` |
| Catalog prices change later | Historical orders should snapshot (persistence note) |

---

## Interview Talking Points

### Opening script (60 seconds)

1. Ask clarifying questions.
2. State assumptions: formula, no tax, enums, throw on missing price.
3. Sketch: Enums → Pizza/Order (data) → PricingCatalog (maps) → PricingStrategy (DI) → PricingService.
4. Say explicitly: **"No class per topping — that explodes. Maps + enums scale."**
5. Code core path first. Multi-store only after `pricePizza` works end-to-end.

### Multi-store follow-up

> "Pizza is store-agnostic. Prices live in per-store `PricingCatalog` instances. Inject via `CatalogPricingStrategy` into `PricingService`. Static catalog breaks this."

### Strategy follow-up

> "`PricingStrategy.price(Pizza)` is the seam. Default reads catalog. Promo wraps strategy. Pizza and Service stay closed for modification."

### Switch default / missing price

> "Return 0 = undercharge. Magic default = invent prices. Fail fast with `PriceNotFoundException`."

### Done checklist

- [ ] Clarifying questions asked
- [ ] Enums + Map catalog (no class explosion)
- [ ] Pizza has **no** static pricing calls
- [ ] Strategy + injected catalog instance
- [ ] Builder for Pizza
- [ ] PricingService.pricePizza + priceOrder
- [ ] Runnable demo prints a total
- [ ] Missing price throws
- [ ] Multi-store: same Pizza, different catalogs, different totals

---

## Summary of Patterns & Principles

```
┌─────────────────────────────────────────────────────────────────┐
│  DESIGN PATTERNS                                                │
│                                                                 │
│  Strategy   → PricingStrategy, CatalogPricing, % Discount       │
│  Factory    → CatalogFactory (seed per-store catalogs)          │
│  Builder    → Pizza.Builder (base/size/toppings)                │
│  Registry   → StoreCatalogRegistry (storeId → catalog)          │
│  Decorator  → PercentageDiscountStrategy wraps another strategy │
│  DI         → Catalog → Strategy → Service (NOT static)         │
│                                                                 │
│  SOLID PRINCIPLES                                               │
│                                                                 │
│  S → Pizza=data, Catalog=prices, Strategy=algo, Service=API     │
│  O → New topping = enum + map; new rule = new Strategy          │
│  L → Any PricingStrategy interchangeable in PricingService      │
│  I → PricingStrategy has 1 method: price(Pizza)                 │
│  D → PricingService depends on interface, not EnumMaps          │
│                                                                 │
│  OOP CONCEPTS                                                   │
│                                                                 │
│  Encapsulation → Catalog hides maps; Pizza hides toppings list  │
│  Composition   → Store HAS catalog; Strategy HAS catalog        │
│  Abstraction   → PricingStrategy hides algorithm                │
│  Polymorphism  → Catalog vs Discount strategies                 │
│  Immutability  → Pizza after build()                            │
│                                                                 │
│  KEY INTERVIEW TALKING POINTS                                   │
│                                                                 │
│  1. Ask clarify BEFORE coding                                   │
│  2. No class-per-topping (use Enum + Map)                       │
│  3. Inject catalog instance — never static PriceCatalog         │
│  4. Throw on missing price — never return 0                     │
│  5. Wire end-to-end: Pizza → Service → Strategy → Catalog       │
│  6. Multi-store = different catalogs, same Pizza                │
└─────────────────────────────────────────────────────────────────┘
```

**One-line loop close:**  
*Pizza is data; catalog is price data; strategy is injected behavior; service wires them — that is the LLD they wanted.*

---

## Deep Dive: Why Each Pattern & Principle (Interview Learning)

> **How to use this section:** The Summary above is for recall. This section is for **understanding**. In an interview you must explain **why** each pattern exists in *this* design — not just name-drop.
>
> **Core idea (repeat until automatic):**
> **Pizza = data · Catalog = price data · Strategy = injected behavior · Service = wires them.**

---

### 1. Strategy Pattern ⭐⭐⭐⭐⭐

> **1-liner:** Hold a **variable of interface type** (`PricingStrategy strategy`) and swap which algorithm runs — choose behavior, don't hardcode if-else.

**Most important pattern in this design.**

**Problem:** Pricing can have different algorithms.

| Today | Tomorrow | Later |
|-------|----------|-------|
| Base + Size + Toppings | Base + Size + Toppings − 20% | Festival / happy hour / membership / store-specific |

We do **not** want `PricingService` to grow a huge `if-else`.

#### Without Strategy (bad)

```java
class PricingService {
    int price(Pizza pizza) {
        if (normalPricing) {
            // calculate normal price
        } else if (discountPricing) {
            // calculate discount
        } else if (festivalPricing) {
            // calculate festival price
        }
        return price;
    }
}
```

Every new pricing rule **modifies** `PricingService` → breaks **Open/Closed**.

#### With Strategy (good)

```java
interface PricingStrategy {
    int price(Pizza pizza);
}
```

```text
PricingStrategy
       ↑
       |
----------------------------
|                          |
CatalogPricingStrategy     DiscountStrategy
```

`PricingService` does not care **how** price is calculated:

```java
class PricingService {
    private final PricingStrategy strategy;

    PricingService(PricingStrategy strategy) {
        this.strategy = strategy;
    }

    int pricePizza(Pizza pizza) {
        return strategy.price(pizza);
    }
}
```

This is also **Dependency Injection**.

#### Real-world analogy (payment)

```text
PaymentStrategy
       ↑
------------------------
|          |           |
UPI      Card       Wallet
```

Checkout service does not know how UPI/Card works.

Same idea:

```text
PricingStrategy
       ↑
-----------------------------
Normal    Discount    Festival
```

#### Interview line

> "I use Strategy because pricing is variable behavior. It lets me add or replace pricing algorithms without modifying the service that orchestrates pricing."

---

### 2. Factory Pattern ⭐⭐⭐⭐

> **1-liner:** One place **creates** objects (catalogs); callers don't scatter construction logic — Factory builds, Strategy prices.

Factory = **centralize object creation.**

Here:

```java
CatalogFactory.defaultStoreCatalog();
CatalogFactory.nycPremiumCatalog();
```

Instead of every caller doing:

```java
Map<Base, Integer> bases = ...
Map<Size, Integer> sizes = ...
Map<Topping, Integer> toppings = ...
return new PricingCatalog(...);
```

put creation in one place:

```text
CatalogFactory
      |
      | creates
      ↓
PricingCatalog
```

#### Why useful?

Tomorrow you may have SFO / NYC / London / Premium / Test catalogs:

```java
CatalogFactory.sfo();
CatalogFactory.nyc();
CatalogFactory.london();
```

#### Important distinction (frequently asked)

| Factory | Strategy |
|---------|----------|
| **Creates** the catalog | **Calculates** the price |
| Object construction | Algorithm / behavior |

```text
Factory  → creates Catalog
Strategy → calculates Price
```

#### Interview line

> "Factory centralizes how catalogs are seeded. It does not calculate price — Strategy does."

---

### 3. Builder Pattern ⭐⭐⭐⭐

> **1-liner:** Build step-by-step → `build()` validates → object is **closed to modification** (immutable/safe); never hand out a half-built Pizza.

Pizza shape:

```text
Pizza
 ├── Base (required)
 ├── Size (required)
 └── Toppings (0..N, optional/variable)
```

Ugly constructor (and worse if more fields appear):

```java
new Pizza(Base.THIN, Size.LARGE, List.of(CHEESE, OLIVE, MUSHROOM));
```

Later you risk **telescoping constructors**:

```text
base, size, toppings, stuffedCrust, extraSauce, extraCheese, ...
```

#### With Builder

```java
Pizza pizza = new Pizza.Builder()
    .base(Base.THIN)
    .size(Size.LARGE)
    .addTopping(Topping.CHEESE)
    .addTopping(Topping.OLIVE)
    .build();
```

`build()` validates — never create `base=null` / `size=null`:

```java
if (base == null) throw new IllegalArgumentException("base required");
if (size == null) throw new IllegalArgumentException("size required");
```

#### Interview line

> "I use Builder because Pizza has required fields and a variable number of toppings, and Builder validates before construction."

---

### 4. Registry Pattern ⭐⭐⭐⭐

> **1-liner:** Lookup table `storeId → PricingCatalog` — pick the right price world without a static global catalog.

Multiple stores:

```text
SFO → SFO prices
NYC → NYC prices
```

Need:

```text
storeId → PricingCatalog
```

That is `StoreCatalogRegistry`:

```text
Registry
"SFO" → Catalog A
"NYC" → Catalog B
"LON" → Catalog C
```

```java
registry.getCatalog("SFO");
registry.pricingServiceFor("SFO"); // catalog → strategy → service wired
```

#### Why not static?

**Bad:**

```java
PriceCatalog.getBasePrice(...);  // ONE global catalog forever
```

**Need:**

```text
SFO → different prices
NYC → different prices
```

Registry + **instance** catalogs solve multi-store. Static catalog cannot.

#### Interview line

> "Registry maps storeId to the correct PricingCatalog so the same Pizza can be priced under different store price worlds."

---

### 5. Decorator Pattern ⭐⭐⭐⭐

> **1-liner:** **Wrap** an existing strategy to **change/add behavior** (e.g. +10% off) without editing the inner algorithm — Strategy chooses algo; Decorator layers on top.

Normal price = ₹500. Want 10% off.

You *could* edit `CatalogPricingStrategy` — that modifies existing code (bad OCP).

Instead **wrap**:

```text
CatalogPricingStrategy
        ↓
PercentageDiscountStrategy
        ↓
PricingService
```

```java
PricingStrategy normal = new CatalogPricingStrategy(catalog);
PricingStrategy discount = new PercentageDiscountStrategy(normal, 10);

discount.price(pizza);
// internally: normal price → ₹500 → 10% off → ₹450
```

You can chain:

```text
CatalogPricingStrategy
        ↓
10% Discount
        ↓
Membership Discount
        ↓
Coupon
```

---

### Strategy vs Decorator (interview trap)

> **1-liner contrast:** **Strategy** = variable of type `PricingStrategy` → pick which algo. **Decorator** = wrap that algo → change/add behavior without rewriting it.

| Strategy | Decorator |
|----------|-----------|
| **Choose** an algorithm | **Add behavior around** an existing algorithm |
| Normal vs Discount vs Festival | Normal → +10% off → +membership |

```text
Strategy:  Which algorithm should I use?
Decorator: Keep this algorithm, wrap extra behavior on top.
```

`PercentageDiscountStrategy` in this design is a **Decorator that also implements Strategy** — common and powerful.

#### Interview line

> "Decorator wraps an existing PricingStrategy to add promo without modifying catalog pricing or Pizza."

---

### 6. Dependency Injection ⭐⭐⭐⭐⭐

> **1-liner:** Don't `new` your dependency inside — **pass it in** (catalog/strategy into service) so multi-store + tests work.

Not classic GoF, but critical here.

**Bad (service creates dependency):**

```java
class PricingService {
    private PricingCatalog catalog = new PricingCatalog(...);
}
```

**Good (dependency injected):**

```java
class PricingService {
    private final PricingStrategy strategy;

    PricingService(PricingStrategy strategy) {
        this.strategy = strategy;
    }
}
```

```text
PricingService
       ↓
PricingStrategy
       ↓
PricingCatalog
```

Service does not create its dependency — caller provides it.

#### Why useful?

**Testing:** inject `FakePricingStrategy`.

**Multi-store:**

```text
SFO → SFO Catalog → Strategy → Service
NYC → NYC Catalog → Strategy → Service
```

Same Pizza, different pricing.

#### Interview line

> "DI fixes the static-catalog bug. Catalog and strategy are injected, so multi-store and unit tests become possible."

---

### 7. SOLID — SRP ⭐⭐⭐⭐⭐

> **1-liner:** One class, one job — Pizza = config, Catalog = prices, Strategy = algo, Service = wire; price change doesn't touch Pizza.

**Single Responsibility:** one class → one reason to change.

```text
Pizza                    → product configuration
PricingCatalog           → price data
PricingStrategy          → pricing algorithm
PricingService           → orchestration / use case
StoreCatalogRegistry     → store → catalog mapping
```

| Change | Touch only |
|--------|------------|
| Prices change | `PricingCatalog` — not `Pizza` |
| Discount logic changes | `DiscountStrategy` — not `Pizza` / `PricingService` |

#### Interview line

> "Pizza is configuration; catalog is price data; strategy is behavior; service orchestrates. Each has one reason to change."

---

### 8. Open/Closed Principle ⭐⭐⭐⭐⭐

> **1-liner:** **Extend** (new topping / new Strategy) without **editing** existing PricingService or Pizza — open to add, closed to rewrite.

**Open for extension, closed for modification.**

#### New topping

Do **not** create `MushroomTopping.java`.

Do:

```java
enum Topping { CHEESE, MUSHROOM, OLIVE }
// + add price in catalog map
```

#### New pricing rule (e.g. buy-2 → 20% off)

Create `BuyTwoGetDiscountStrategy` — do **not** rewrite `PricingService`.

#### Interview line

> "New toppings = enum + catalog entry. New pricing rules = new Strategy. Existing service stays closed."

---

### 9. Liskov Substitution Principle

> **1-liner:** Swap `CatalogPricingStrategy` ↔ `DiscountStrategy` in `PricingService` **without breaking** it — if you can substitute safely, that's LSP.

Simple: any `PricingStrategy` impl can be passed where `PricingStrategy` is expected.

```text
CatalogPricingStrategy
PercentageDiscountStrategy
FestivalPricingStrategy
```

all work in:

```java
PricingService(PricingStrategy strategy)
```

Service does not break when you swap them.

#### Interview line

> "Any PricingStrategy implementation is usable wherever PricingStrategy is expected without breaking PricingService."

---

### 10. Interface Segregation Principle

> **1-liner:** Keep interfaces **tiny** (`price(Pizza)` only) — don't force Strategy to implement save/DB/notify junk it doesn't need.

**Bad giant interface:**

```java
interface PricingStrategy {
    pricePizza();
    priceOrder();
    saveCatalog();
    getStore();
    updateDatabase();
    sendNotification();
}
```

**Good small interface:**

```java
interface PricingStrategy {
    int price(Pizza pizza);
}
```

Implementors are not forced to implement unrelated APIs.

#### Interview line

> "PricingStrategy has one method so implementations stay focused — ISP."

---

### 11. Dependency Inversion Principle ⭐⭐⭐⭐⭐

> **1-liner:** High-level `PricingService` depends on **abstraction** (`PricingStrategy`), not concrete `EnumMap` / `CatalogPricingStrategy`.

**Bad:** high-level depends on details

```text
PricingService → CatalogPricingStrategy → EnumMap
```

**Good:** high-level depends on abstraction

```text
             PricingStrategy  ← interface
                  ↑
PricingService ───┘
                  │
        CatalogPricingStrategy
```

```java
// Service depends on PricingStrategy, NOT CatalogPricingStrategy / EnumMap
```

#### Interview line

> "PricingService depends on the PricingStrategy abstraction, not a concrete catalog or EnumMap."

---

### 12. Encapsulation

> **1-liner:** Hide maps/lists inside; outside only calls `getBasePrice()` / getters — no direct `catalog.basePrices.put(...)`.

Hide internal state; expose controlled access.

```java
class PricingCatalog {
    private final Map<Base, Integer> basePrices;
    // outside cannot do: catalog.basePrices.put(...)
    public int getBasePrice(Base base) { ... }
}
```

Pizza similarly exposes toppings as an immutable/unmodifiable view — callers cannot mutate mid-pricing.

#### Interview line

> "Catalog and Pizza hide their maps/lists; mutation happens only through controlled APIs."

---

### 13. Abstraction

> **1-liner:** Caller only knows `price(Pizza)` — hides whether price comes from EnumMap, DB, discount, or festival logic.

Caller only knows:

```java
interface PricingStrategy {
    int price(Pizza pizza);
}
```

Does **not** need to know: EnumMap? DB? Discount? AI pricing?

> "Give me the price." — that's abstraction.

---

### 14. Polymorphism

> **1-liner:** Same call `strategy.price(pizza)` — **runtime picks** Catalog vs Discount implementation.

```java
PricingStrategy strategy = ...;  // Catalog or Discount
strategy.price(pizza);           // correct impl runs
```

Same call site, different behavior — polymorphism via Strategy.

---

### 15. Immutability

> **1-liner:** After `build()`, Pizza is **frozen** (final + copyOf) — safe to share; nobody mutates toppings mid-pricing.

After:

```java
Pizza pizza = new Pizza.Builder()
    .base(Base.THIN)
    .size(Size.LARGE)
    .build();
```

`base` / `size` / `toppings` cannot change (`final` + `List.copyOf`).

**Why:** safe to pass around while pricing — no concurrent mutation of toppings mid-calc.

---

### How Everything Connects (do not memorize 15 islands)

```text
                Pizza.Builder
                     |
                     ↓
                   Pizza
                "just data"
                     |
        StoreCatalogRegistry
              |
              ↓
        PricingCatalog
        "price data"
              |
              ↓
    CatalogPricingStrategy
        "price algorithm"
              |
              ↓
        PricingService
       "orchestrates"
```

With promotion:

```text
CatalogPricingStrategy
          ↓
PercentageDiscountStrategy   ← Decorator
          ↓
     PricingService
```

---

### One Walkthrough Connecting Everything

**Pizza:** THIN + LARGE + CHEESE

**SFO catalog:** THIN=₹100, LARGE=₹150, CHEESE=₹30

| Step | Code / action | Pattern |
|------|---------------|---------|
| 1 | `new Pizza.Builder().base(THIN).size(LARGE).addTopping(CHEESE).build()` | **Builder** |
| 2 | `registry.pricingServiceFor("SFO")` | **Registry** |
| 3 | SFO → `PricingCatalog` | **Data** |
| 4 | `new CatalogPricingStrategy(sfoCatalog)` | **Strategy + DI** |
| 5 | `pricingService.pricePizza(pizza)` | **Service orchestration** |
| 6 | 100 + 150 + 30 = **₹280** | Result |

---

### What To Say When Asked "Explain your patterns"

**Weak (name-dropping):**

> "I used Strategy, Factory, Builder, Registry, Decorator, SOLID..."

**Strong (connected story):**

> "I separated pizza configuration, pricing data, and pricing behavior.
> **Builder** constructs a valid Pizza (required + variable toppings).
> **PricingStrategy** separates the algorithm from the service and enables **DI**.
> **PricingCatalog** stores price data so prices can differ by store without changing Pizza.
> **StoreCatalogRegistry** resolves the catalog for a store.
> **Factory** centralizes catalog creation.
> For promotions I **wrap** the base strategy with a **Decorator** instead of modifying existing pricing logic.
>
> That gives **SRP** (one responsibility each), **OCP** (new rules via new strategies), **LSP** (strategies interchangeable), **ISP** (tiny pricing interface), and **DIP** (service depends on `PricingStrategy` abstraction)."

That is a strong **SDE-2 LLD** answer — understanding, not memorization.