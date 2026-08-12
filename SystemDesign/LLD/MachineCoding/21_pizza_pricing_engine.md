# Pizza Pricing Engine - Low Level Design (Machine Coding)

> **Interview lesson from L5 feedback:** Failures were *not* "wrong entities". Failures were: skip clarify → class explosion → static catalog → never wire end-to-end → don't inject pricing. This doc is the **expected answer**.

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Clarifying Questions (ASK FIRST)](#clarifying-questions-ask-first)
3. [Requirements](#requirements)
4. [What NOT To Do (Interview Anti-Patterns)](#what-not-to-do-interview-anti-patterns)
5. [Design Approach](#design-approach)
6. [Class Diagram](#class-diagram)
7. [Design Patterns & SOLID](#design-patterns--solid)
8. [Code Implementation](#code-implementation)
9. [End-to-End Wiring](#end-to-end-wiring)
10. [Edge Cases & Tests](#edge-cases--tests)
11. [Interview Talking Points](#interview-talking-points)

---

## Problem Statement

Design an **in-memory pricing engine** for a pizza delivery service.

**Price formula:**
```
pizza_price = base_price + size_price + sum(topping_prices)
order_price = sum(pizza_price for each pizza in order)
```

**Constraints:**
- In-memory only (no database)
- Must be **extendable** (new base / size / topping without new classes)
- Must support **multiple stores** with **different price catalogs**
- Pizza entity must **not** own pricing logic

---

## Clarifying Questions (ASK FIRST)

> Interviewer concern: *"Jumped into solution without clarifying pricing logic."*
> Always ask these **before** writing classes.

| # | Question | Why it matters | Typical assumption if they say "you decide" |
|---|----------|----------------|-----------------------------------------------|
| 1 | Is price = base + size + toppings only? Taxes / delivery / coupons? | Scope. Don't invent billing. | **No tax, no coupon, no delivery fee** |
| 2 | Can one order have multiple pizzas? | Need `Order` aggregate or just single pizza? | **Yes, multiple pizzas** |
| 3 | Are toppings unique per pizza, or can same topping appear twice? | List vs Set | **List allowed (double cheese)** |
| 4 | Fixed menu only, or free-form custom pizza? | Enums vs free strings | **Only listed Base / Size / Topping enums** |
| 5 | Same prices for all stores, or per-store prices? | Single catalog vs inject catalog | Start **single store**, then ask if multi-store is in scope |
| 6 | What if catalog missing a price for a topping? | Return 0 (silent bug) vs throw | **Throw exception** — never silent 0 |
| 7 | Currency currency / precision? | `int` paise / cents vs `BigDecimal` | **int cents** (or `BigDecimal` if interview prefers money type) |
| 8 | Is system only pricing, or also place-order / kitchen? | Stay on **core**: pricing dependency injection | **Pricing only** — don't digress into StoreManagement UI |

**Say out loud:**
> "I'll assume: multi-pizza order, formula base+size+toppings, no tax/coupon, enum-only options, missing price throws, in-memory. If multi-store pricing is required, I'll inject catalog per store."

---

## Requirements

### Functional
1. Build a pizza from Base + Size + Toppings
2. Calculate pizza price from catalog lookups
3. Calculate order total (sum of pizzas)
4. Support per-store price catalogs (different cities / franchises)
5. Reject unknown / unpriced options loudly

### Non-Functional
1. **Extendable:** add new topping = catalog entry, **not** new class
2. **SRP:** Pizza = data; PricingService = math; Catalog = prices
3. **In-memory**, thread-safe enough for interview demo
4. **Testable:** inject catalog → unit test without static globals

---

## What NOT To Do (Interview Anti-Patterns)

| Anti-pattern | Why interviewer rejects it | Correct move |
|--------------|----------------------------|--------------|
| Class per crust / size / topping (`ThinCrustStrategy`, `CheeseToppingStrategy`…) | **Class explosion**. New SKU = code change + redeploy | Enum + Map catalog |
| `Pizza.getPrice()` calling `PriceCatalog.getBasePrice()` **static** | Global single price set. Multi-store impossible. Hard to test | **Inject** `PricingCatalog` / `PricingStrategy` |
| Return `0` for unknown topping in switch/default | Silent undercharge. Customer thinks toppings free | **Throw** `PriceNotFoundException` |
| Jump into StoreManager / kitchen / delivery before pricing works | "Peripheral concerns" — core miss is **inject pricing dependency** | Wire Pizza → Service → Catalog **first**, then multi-store |
| Only talk SOLID names without wiring | "Knew SRP but couldn't wire design" | Show runnable end-to-end main / test |

---

## Design Approach

### Core idea (what interviewer wanted)

```
┌─────────────┐     uses      ┌──────────────────┐     reads     ┌─────────────────┐
│    Pizza    │ ────────────► │  PricingService  │ ────────────► │ PricingCatalog  │
│ (data only) │               │ (calculates $)   │               │ (Map lookups)   │
└─────────────┘               └──────────────────┘               └─────────────────┘
                                      ▲
                                      │ implements
                              ┌───────┴────────┐
                              │ PricingStrategy│  ← Strategy interface (DI seam)
                              └────────────────┘
```

1. **Enums** = closed set of product options (type-safe, no string typos)
2. **PricingCatalog** = Maps of enum → price (data, not classes)
3. **PricingStrategy** = Strategy interface (swap catalog / promo / store pricing)
4. **CatalogPricingStrategy** = default impl that uses a catalog instance
5. **PricingService** = orchestrates pricing for Pizza / Order
6. **StoreCatalogRegistry** (or StoreManager) = `storeId → PricingCatalog` for multi-store
7. **Never** put price math inside `Pizza`

### Formula (state clearly)

```
price(pizza) = catalog.base(base) + catalog.size(size) + Σ catalog.topping(t)
price(order) = Σ price(pizza)
```

### Multi-store extension (after core works)

```
Store "SFO" → Catalog{ Margherita base=200, ... }
Store "NYC" → Catalog{ Margherita base=250, ... }

PricingService(strategyFor(storeId)).price(pizza)
```

Pizza stays identical across stores. **Only catalog instance changes.**

---

## Class Diagram

```
┌──────────────────────┐
│ «enum» Base          │
│ THIN, THICK, CHEESE  │
└──────────────────────┘
┌──────────────────────┐
│ «enum» Size          │
│ SMALL, MEDIUM, LARGE │
└──────────────────────┘
┌──────────────────────┐
│ «enum» Topping       │
│ CHEESE, OLIVE, ...   │
└──────────────────────┘

┌──────────────────────┐
│ Pizza                │
│ - base: Base         │
│ - size: Size         │
│ - toppings: List<>   │
│ + getters            │  ← NO getPrice() that hits static catalog
└──────────────────────┘

┌──────────────────────┐
│ Order                │
│ - pizzas: List<Pizza>│
└──────────────────────┘

┌──────────────────────┐
│ PricingCatalog       │
│ - basePrices: Map    │
│ - sizePrices: Map    │
│ - toppingPrices: Map │
│ + getBasePrice()     │
│ + getSizePrice()     │
│ + getToppingPrice()  │  ← throws if missing
└──────────────────────┘

┌─────────────────────────────┐
│ «interface» PricingStrategy │
│ + price(Pizza): int         │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│ CatalogPricingStrategy      │
│ - catalog: PricingCatalog   │  ← injected instance (NOT static)
│ + price(Pizza)              │
└─────────────────────────────┘

┌──────────────────────┐
│ PricingService       │
│ - strategy: Strategy │
│ + pricePizza()       │
│ + priceOrder()       │
└──────────────────────┘

┌──────────────────────────┐
│ StoreCatalogRegistry     │
│ - storeToCatalog: Map    │
│ + getCatalog(storeId)    │
│ + register(store, cat)   │
└──────────────────────────┘
```

---

## Design Patterns & SOLID

| Pattern / Principle | Where | Why |
|---------------------|-------|-----|
| **Strategy** | `PricingStrategy` | Inject different pricing (store catalog, promo, happy-hour) without changing Pizza |
| **Dependency Injection** | Catalog into Strategy; Strategy into Service | Fixes static-catalog / multi-store bug |
| **Registry** | `StoreCatalogRegistry` | `storeId → PricingCatalog` |
| **S** | Pizza ≠ pricing; Catalog ≠ calculation | Price change ≠ Pizza change |
| **O** | New topping = enum + map entry | No new Strategy class per topping |
| **L** | Any `PricingStrategy` substitutable | Service doesn't care which |
| **I** | Strategy has one method: `price(Pizza)` | Thin interface |
| **D** | Service depends on `PricingStrategy` abstraction | Not on concrete catalog maps |

> Interviewer quote theme: *"Key gap = injecting pricing dependency."* Strategy + DI is the answer — not more StoreManager ceremony.

---

## Code Implementation

### 1. Enums (not class-per-variant)

```java
public enum Base {
    THIN, THICK, CHEESE_BURST
}

public enum Size {
    SMALL, MEDIUM, LARGE
}

public enum Topping {
    CHEESE, MUSHROOM, OLIVE, JALAPENO, PEPPERONI, ONION
}
```

**Why enums not classes?** Adding `PINEAPPLE` = one enum constant + one catalog price. Not `PineappleTopping implements ToppingStrategy`.

---

### 2. Domain entities (data only)

```java
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class Pizza {
    private final Base base;
    private final Size size;
    private final List<Topping> toppings;

    public Pizza(Base base, Size size, List<Topping> toppings) {
        this.base = Objects.requireNonNull(base, "base required");
        this.size = Objects.requireNonNull(size, "size required");
        this.toppings = List.copyOf(toppings == null ? List.of() : toppings);
    }

    public Base getBase() { return base; }
    public Size getSize() { return size; }
    public List<Topping> getToppings() { return toppings; }

    // NO getPrice() here that calls static PriceCatalog.
    // Pricing is a separate concern → PricingService + injected strategy.
}

public final class Order {
    private final List<Pizza> pizzas = new ArrayList<>();

    public void addPizza(Pizza pizza) {
        pizzas.add(Objects.requireNonNull(pizza));
    }

    public List<Pizza> getPizzas() {
        return Collections.unmodifiableList(pizzas);
    }
}
```

---

### 3. PricingCatalog — Map lookups (instance, not static)

```java
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

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

    private static <T extends Enum<T>> int requirePrice(Map<T, Integer> map, T key, String kind) {
        Integer price = map.get(key);
        if (price == null) {
            // NEVER return 0 — silent undercharge. Customer / QA / interviewer all hate this.
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

**Factory for a store's catalog (in-memory seed):**

```java
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

---

### 4. Strategy + PricingService (core DI)

```java
public interface PricingStrategy {
    int price(Pizza pizza);
}

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

// Optional later: promo / happy-hour without touching Pizza
public final class PercentageDiscountStrategy implements PricingStrategy {
    private final PricingStrategy delegate;
    private final int percentOff; // 0-100

    public PercentageDiscountStrategy(PricingStrategy delegate, int percentOff) {
        this.delegate = delegate;
        this.percentOff = percentOff;
    }

    @Override
    public int price(Pizza pizza) {
        int base = delegate.price(pizza);
        return base - (base * percentOff / 100);
    }
}

public final class PricingService {
    private final PricingStrategy strategy;

    public PricingService(PricingStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy);
    }

    public int pricePizza(Pizza pizza) {
        return strategy.price(pizza);
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

---

### 5. Multi-store registry (after core pricing works)

```java
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    public PricingService pricingServiceFor(String storeId) {
        PricingCatalog catalog = getCatalog(storeId);
        return new PricingService(new CatalogPricingStrategy(catalog));
    }
}
```

**Critical insight (what candidates missed):**

```java
// WRONG — static = one global price world
int p = PriceCatalog.getBasePrice(base);  // Pizza or Service calling static

// RIGHT — instance injected per store
PricingCatalog sfo = registry.getCatalog("SFO");
PricingService svc = new PricingService(new CatalogPricingStrategy(sfo));
int p = svc.pricePizza(pizza);
```

`Pizza` never knows which store's prices apply. Caller chooses catalog via DI.

---

## End-to-End Wiring

> Interviewer: *"Never finished wiring… how Pizza connects to pricing to get final total."*
> This section is non-negotiable in the interview.

```java
public class PizzaPricingDemo {
    public static void main(String[] args) {
        // 1. Seed per-store catalogs (in-memory)
        StoreCatalogRegistry registry = new StoreCatalogRegistry();
        registry.register("SFO", CatalogFactory.defaultStoreCatalog());
        registry.register("NYC", CatalogFactory.nycPremiumCatalog());

        // 2. Build domain objects (no prices inside)
        Pizza pizza1 = new Pizza(
            Base.THIN,
            Size.MEDIUM,
            List.of(Topping.CHEESE, Topping.OLIVE)
        );
        Pizza pizza2 = new Pizza(
            Base.CHEESE_BURST,
            Size.LARGE,
            List.of(Topping.PEPPERONI, Topping.MUSHROOM, Topping.JALAPENO)
        );

        Order order = new Order();
        order.addPizza(pizza1);
        order.addPizza(pizza2);

        // 3. Wire pricing for a store (DI)
        PricingService sfoPricing = registry.pricingServiceFor("SFO");
        PricingService nycPricing = registry.pricingServiceFor("NYC");

        // 4. Execute end-to-end
        System.out.println("SFO pizza1 = " + sfoPricing.pricePizza(pizza1));
        System.out.println("SFO order  = " + sfoPricing.priceOrder(order));
        System.out.println("NYC order  = " + nycPricing.priceOrder(order)); // different $

        // Manual check for pizza1 @ SFO:
        // THIN=100 + MEDIUM=100 + CHEESE=30 + OLIVE=35 = 265
    }
}
```

### Request flow (say this while coding)

```
1. Client builds Pizza(base, size, toppings)          → data only
2. Client resolves storeId → PricingCatalog           → StoreCatalogRegistry
3. Client builds CatalogPricingStrategy(catalog)      → Strategy + DI
4. Client builds PricingService(strategy)
5. PricingService.pricePizza(pizza)
      → strategy.price(pizza)
         → catalog.getBasePrice + getSizePrice + Σ getToppingPrice
6. Missing map entry → PriceNotFoundException (not 0)
```

---

## Edge Cases & Tests

```java
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PricingServiceTest {

    @Test
    void pricesPizza_basePlusSizePlusToppings() {
        PricingService svc = new PricingService(
            new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog())
        );
        Pizza pizza = new Pizza(Base.THIN, Size.MEDIUM, List.of(Topping.CHEESE, Topping.OLIVE));
        // 100 + 100 + 30 + 35 = 265
        assertEquals(265, svc.pricePizza(pizza));
    }

    @Test
    void pricesOrder_sumsAllPizzas() {
        PricingService svc = new PricingService(
            new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog())
        );
        Order order = new Order();
        order.addPizza(new Pizza(Base.THIN, Size.SMALL, List.of()));           // 100+50=150
        order.addPizza(new Pizza(Base.THICK, Size.SMALL, List.of(Topping.ONION))); // 120+50+20=190
        assertEquals(340, svc.priceOrder(order));
    }

    @Test
    void missingToppingPrice_throwsNotZero() {
        Map<Base, Integer> bases = Map.of(Base.THIN, 100);
        Map<Size, Integer> sizes = Map.of(Size.SMALL, 50);
        Map<Topping, Integer> toppings = Map.of(Topping.CHEESE, 30);
        // OLIVE intentionally missing
        PricingCatalog incomplete = new PricingCatalog(
            new EnumMap<>(bases), new EnumMap<>(sizes), new EnumMap<>(toppings)
        );
        PricingService svc = new PricingService(new CatalogPricingStrategy(incomplete));
        Pizza pizza = new Pizza(Base.THIN, Size.SMALL, List.of(Topping.OLIVE));

        assertThrows(PriceNotFoundException.class, () -> svc.pricePizza(pizza));
    }

    @Test
    void samePizza_differentStores_differentPrices() {
        StoreCatalogRegistry registry = new StoreCatalogRegistry();
        registry.register("SFO", CatalogFactory.defaultStoreCatalog());
        registry.register("NYC", CatalogFactory.nycPremiumCatalog());

        Pizza pizza = new Pizza(Base.THIN, Size.MEDIUM, List.of(Topping.CHEESE));
        int sfo = registry.pricingServiceFor("SFO").pricePizza(pizza); // 100+100+30=230
        int nyc = registry.pricingServiceFor("NYC").pricePizza(pizza); // 140+130+40=310

        assertEquals(230, sfo);
        assertEquals(310, nyc);
        assertNotEquals(sfo, nyc);
    }

    @Test
    void pizzaDoesNotEmbedStaticPricing() {
        // Compile-time design check: Pizza has no getPrice().
        // If you add Pizza.getPrice() that calls static catalog — you failed multi-store.
        Pizza pizza = new Pizza(Base.THIN, Size.LARGE, List.of());
        assertNotNull(pizza.getBase());
        // pricing only via service:
        assertEquals(250, // 100+150
            new PricingService(new CatalogPricingStrategy(CatalogFactory.defaultStoreCatalog()))
                .pricePizza(pizza));
    }

    @Test
    void extendTopping_withoutNewClass() {
        // New product option = enum + map entry. Zero new Strategy classes.
        // (In real code you'd add Topping.PINEAPPLE to enum and put price in catalog.)
        Map<Topping, Integer> toppings = new EnumMap<>(Topping.class);
        toppings.put(Topping.CHEESE, 30);
        // ... all required toppings for test ...
        assertTrue(true); // document the extensibility story in interview
    }
}
```

### Edge-case table (say these without being prompted)

| Case | Behavior |
|------|----------|
| Empty toppings list | Valid — price = base + size only |
| Duplicate topping (double cheese) | Charge twice (List, not Set) — confirm with interviewer |
| Null base / size | Reject in Pizza constructor |
| Unknown storeId | `IllegalArgumentException` |
| Catalog missing price | `PriceNotFoundException` — **never 0** |
| New topping launched | Add enum + catalog price; no new class |
| Promo 10% off | Wrap strategy: `new PercentageDiscountStrategy(inner, 10)` |

---

## Interview Talking Points

### Opening script (60 seconds)

1. Ask clarifying questions (table above).
2. State assumptions: formula, no tax, enums only, throw on missing price.
3. Sketch: Enums → Pizza/Order (data) → PricingCatalog (maps) → PricingStrategy (DI) → PricingService.
4. Explicitly say: **"I will not create a class per topping — that explodes. Maps + enums scale."**
5. Code core path first. Multi-store only after `pricePizza` works end-to-end.

### When interviewer says "what if multiple stores?"

> "Pizza definition is store-agnostic. Prices live in per-store `PricingCatalog` instances. I'll inject the catalog via `CatalogPricingStrategy` into `PricingService`. Static catalog would break this — that's why catalog is an instance, not static methods."

### When interviewer asks about Strategy

> "`PricingStrategy.price(Pizza)` is the seam. Default impl reads a catalog. Later I can wrap with discount strategy without changing Pizza or Service callers."

### When they ask default/switch case for missing enum

> "Returning 0 hides data bugs and undercharges. Returning a magic default (100) invents prices. Correct product behavior: **fail fast** with `PriceNotFoundException` so ops fix the catalog."

### What "done" looks like in the interview

- [ ] Clarifying questions asked
- [ ] Enums + Map catalog (no class explosion)
- [ ] Pizza has **no** static pricing calls
- [ ] Strategy interface + injected catalog instance
- [ ] PricingService.pricePizza + priceOrder
- [ ] One runnable demo / test that prints a total
- [ ] Missing price throws
- [ ] Multi-store shown as registry → different catalogs → different totals for **same** Pizza

---

## Summary (cheat sheet)

```
ASK FIRST     → scope, formula, multi-store?, missing price behavior
DATA          → Base/Size/Topping enums, Pizza, Order
PRICES        → PricingCatalog (EnumMap), instance NOT static
STRATEGY      → PricingStrategy + CatalogPricingStrategy (DI seam)
SERVICE       → PricingService uses strategy
MULTI-STORE   → StoreCatalogRegistry: storeId → catalog → service
EXTEND        → new topping = enum + map entry (Open/Closed)
EDGE          → throw on missing price, never return 0
WIRE          → demo main that builds pizza → service → prints total
AVOID         → class-per-variant, static catalog, peripheral Store UI before core DI
```

**One sentence for the loop:**  
*Pizza is data; catalog is price data; strategy is injected behavior; service wires them — that's the LLD they wanted.*
