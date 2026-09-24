# Design — Pizza pricing engine

Fill this **before** coding. Interviewers grade wiring, not class name lists.

---

## 1. Entities

| Type | Responsibility | Notes |
|------|----------------|-------|
| `Base` (enum) | Crust / base type | |
| `Size` (enum) | S / M / L … | |
| `Topping` (enum) | Cheese, olive, … | |
| `Pizza` | Data: base, size, toppings | **No** pricing logic |
| `Order` | List of pizzas | |
| `Money` / `int` | Price unit (paise / cents) | Pick one; stay consistent |
| `PricingCatalog` | Maps → prices | Per-store instance |
| `PricingService` | Uses catalog to price | Injected catalog |
| `StoreManager` | storeId → catalog | |

---

## 2. Class sketch

```text
┌─────────────┐     ┌──────────────────┐
│ StoreManager│────►│ PricingCatalog   │  (1 instance per store)
└─────────────┘     │  base/size/top   │
                    └────────▲─────────┘
                             │ inject
┌───────┐  getPrice   ┌──────┴─────────┐
│ Pizza │────────────►│ PricingService │
│ Order │────────────►│                │
└───────┘             └────────────────┘

Pizza/Order = data only
Enums: Base, Size, Topping
```

---

## 3. Catalog API

```text
getBasePrice(Base) -> money
getSizePrice(Size) -> money
getToppingPrice(Topping) -> money
```

Multi-store: **new catalog instance** with different maps, same interface.

---

## 4. Pricing formula

```
price(pizza) = basePrice(base) + sizePrice(size) + sum(toppingPrice(t))
```

Edge cases (locked — also in `CrossQuestions.md` §D and `src/`):
| Case | Behavior |
|------|----------|
| Null pizza / empty order | `requireNonNull`; empty order → `IllegalArgumentException` |
| Unknown enum in catalog | `IllegalArgumentException` — do not default to 0 |
| Duplicate topping | Charge twice (product may later dedupe) |
| Zero toppings | Valid — base + size only |
| Negative price in catalog | Reject in `put*` (`price must be >= 0`) |

---

## 5. Wiring (this is what Loop A/B said you missed)

```
StoreManager.getCatalog(storeId)
        │
        ▼
PricingService(catalog)
        │
        ▼
getPrice(pizza) / getOrderPrice(order)
```

Constructor injection — **no** static `PricingCatalog.getInstance()` for multi-store.

---

## 6. Extensibility check (Open/Closed)

| Change | Touch only |
|--------|------------|
| New topping | Enum + one catalog map entry |
| New store prices | New catalog instance registered in StoreManager |
| New fee type (tax) | New collaborator / decorator — not pizza |

**Anti-pattern:** `ThinCrustStrategy`, `CheeseStrategy`, … one class per variant.

---

## 7. Sequence — price one pizza

```
Client → StoreManager.getCatalog(store)
Client → new PricingService(catalog)
Client → service.getPrice(pizza)
service → catalog.getBasePrice / getSizePrice / getToppingPrice
service → return sum
```

---

## 8. Optional stretch (only if time)

- Combo deals / % discount strategy **on Order**, still using catalog for base prices
- Immutable pizza builder

Do **not** start stretch before core wiring works.
