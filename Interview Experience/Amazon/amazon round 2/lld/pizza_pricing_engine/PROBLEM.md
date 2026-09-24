# LLD — Pizza pricing engine

## Problem (exact bar from Round 2)

Design a **pricing engine for a pizza delivery service**:
- Price = **base** + **size** + **toppings**
- **In-memory**, no DB
- Must be **extendable** (new bases / sizes / toppings / stores without rewriting callers)
- Support **multi-store** pricing (different catalogs per store)

## Clarifying questions (ask first — they liked this)

- [ ] Currency? Assume one currency for interview.
- [ ] Toppings: flat add vs % of base? Assume flat add.
- [ ] Same topping twice? Allow / dedupe?
- [ ] Multi-pizza order? Price per pizza then sum.
- [ ] Invalid combo (e.g. topping not sold)? Throw / return error.
- [ ] Multi-store: different menus or only prices? Start with prices; same enums.
- [ ] Tax / delivery fee in scope? Out of scope unless asked.

## Functional requirements

1. Given a pizza config, return total price.
2. Given an order of pizzas, return order total.
3. Swap store → different prices without changing `PricingService` logic.

## Non-functional

- In-memory only
- O(1) lookups via catalog maps
- Open for extension (new topping = catalog entry, not new class)

## What interviewers rewarded

- Clarifying questions
- Enums for Base / Size / Topping
- SRP: pizza is data; pricing is a service
- `PricingCatalog` + `StoreManager` (or equivalent)

## What scored Concern / Mild Concern — do not repeat

| Mistake | Fix |
|---------|-----|
| `getPrice()` on `Pizza` owning rules | `PricingService.getPrice(Pizza)` |
| Strategy class per topping / crust | One catalog with maps |
| Static global prices | Instance catalog injected per store |
| Edge cases only after prompting | Defaults + validation up front |
| Classes named, never wired | Show constructor injection + call path |

## Target shape

```
Order
  └── List<Pizza>
Pizza
  └── Base, Size, List<Topping>   // data only
PricingCatalog                    // maps: base/size/topping → money
PricingService(catalog)           // getPrice(pizza), getOrderPrice(order)
StoreManager                      // storeId → PricingCatalog
```

## Deliverables in this folder

1. Fill `DESIGN.md` (UML-ish + sequence)
2. Implement under `src/`
3. Self-grade in `NOTES.md` (Positives / Concerns / Assessment)
