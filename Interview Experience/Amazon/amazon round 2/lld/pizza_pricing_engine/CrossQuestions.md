# LLD — Pizza Pricing Engine — Cross Questions (Learning Pack)

**Problem:** Design a pricing engine for a pizza delivery service (base + size + toppings). In-memory, no DB. Extensible. Multi-store.

**Code:** `./src/` (`PricingCatalog` + `PricingService` + `StoreManager`)  
**Related:** `PROBLEM.md`, `DESIGN.md`, `NOTES.md`

Use this file like your project `CrossQuestions.md` files — read aloud, don’t memorize word-for-word.

---

## A. Clarifying questions (ask *before* coding)

| You ask | Why interviewers like it | Default if they shrug |
|---------|--------------------------|------------------------|
| Flat add toppings or % of base? | Locks formula | Flat add |
| Multi-pizza orders? | Need `Order` | Yes — sum pizzas |
| Duplicate toppings allowed? | Validation | Allow; charge twice (or ask) |
| Invalid topping for store? | Error model | Throw / error object |
| Multi-store: different prices or menus? | DI vs polymorphism | Same enums, different prices first |
| Tax / delivery in scope? | Scope control | Out of scope v1 |
| Currency / precision? | Money type | Integer paise/cents |

**Learning:** Clarifying is a **Positive** in your past feedback. Do it every time — 60–90 seconds max.

---

## B. Design & OOP

### Q1. Why not put `getPrice()` on `Pizza`?

**A:** Violates **SRP**. `Pizza` is a **configuration of ingredients** (what the customer ordered). Pricing rules change with store, promotions, tax — if price lives on `Pizza`, every pricing change edits the domain entity and you cannot swap catalogs per store without mutating pizza.

**Correct:** `PricingService.getPrice(Pizza)` reads data from pizza, rules from **injected** `PricingCatalog`.

**Learning line:** “Entities hold state; services apply policies.”

---

### Q2. You mentioned Strategy — why did interviewers dislike Strategy-per-topping?

**A:** Strategy is right when **algorithms differ**. Here every topping is the **same algorithm** (`price += map.get(topping)`). A class per crust/topping explodes:
- `CheeseStrategy`, `OliveStrategy`, … → N classes, zero behavior difference  
- Open/Closed violated in spirit — “open” becomes “open a PR for every topping”

**Better:** **map-based catalog** (data-driven). Use Strategy later for *true* algorithm forks (e.g. `% of base` vs flat, BOGO, time-of-day surge) as **one** `PricingRule` interface with few implementations — not one class per SKU.

**Learning:** Patterns are tools; misapplied patterns are a Mild Concern signal.

---

### Q3. What is Open/Closed here?

**A:**
- **Open** to extension: new topping = enum value + catalog entry (and maybe seed data)  
- **Closed** to modification: `PricingService` loop does not change  

Anti-OCP: `switch(topping) { case CHEESE: ... }` inside service — every topping edits service.

---

### Q4. Walk the class diagram and wiring (they said you “named classes but didn’t wire”)

**A:** Speak the call path out loud while coding:

```text
StoreManager.register(storeId, catalog)
catalog = StoreManager.getCatalog(storeId)
service = new PricingService(catalog)          // constructor injection
price   = service.getPrice(pizza)
        → catalog.getBasePrice(base)
        → catalog.getSizePrice(size)
        → Σ catalog.getToppingPrice(t)
```

**Multi-store:** same `PricingService` **class**, different **catalog instance**. Never `static Map` global prices.

**Learning:** Wiring = who constructs whom. If you can’t draw arrows, you don’t have a design yet.

---

### Q5. Why `StoreManager` instead of passing maps into main only?

**A:** Makes “store → prices” an explicit domain concept. Tomorrow: load catalogs from config file, cache per store, A/B price books — still one lookup API. Main/demo shouldn’t own the registry forever, but for interview a simple `Map<String, PricingCatalog>` behind `StoreManager` is enough.

---

### Q6. Immutable pizza — why?

**A:** Once ordered, toppings shouldn’t mutate mid-pricing (race / bug). Builder optional; constructor + unmodifiable list is enough for LLD.

---

## C. Multi-store (your Mild Concern #2 — master this)

### Q7. “Now support different prices per store. What changes?”

**Wrong answer:** `if (storeId.equals("A"))` inside `getPrice`.  
**Wrong answer:** static catalog with store keys sprinkled in service.  
**Wrong answer:** subclass `StoreAPricingService`.

**Right answer:**
1. Each store gets its **own `PricingCatalog` instance** (different map values).  
2. `PricingService` depends on **interface/catalog**, not store id.  
3. Resolve catalog via `StoreManager`, then `new PricingService(catalog)` (or service holds catalog field set per request).

**Dependency direction:**
```text
PricingService → PricingCatalog (abstraction)
StoreManager  → PricingCatalog instances
Client        → StoreManager + PricingService
```

**Learning:** This is **Dependency Inversion** in miniature — high-level pricing doesn’t depend on store specifics; store specifics are data.

---

### Q8. Should `PricingService` be a singleton?

**A:** Prefer **stateless service + catalog reference**. One service instance per catalog is fine; sharing one service and swapping catalog is riskier (thread safety). Interview default: construct service with catalog; no global singleton.

---

### Q9. Same menu, different prices vs different menus?

**A:**  
- **v1 (interview):** same enums, different prices — catalog maps differ.  
- **v2:** store menu = which enum keys exist; missing key → “not sold here” error (already how catalog lookup works).

Don’t build a second type system unless asked.

---

## D. Edge cases (your Mild Concern #1 — don’t wait for prompts)

| Case | Behavior (in our `src/`) | Say this |
|------|--------------------------|----------|
| Null pizza / order | NPE → `requireNonNull` | Fail fast |
| Empty order | `IllegalArgumentException` | Business rule: nothing to price |
| Unknown enum in catalog | `IllegalArgumentException` | Data error — don’t invent 0 silently |
| Zero toppings | OK — base + size only | Valid pizza |
| Duplicate topping | Charge twice | Or dedupe if product says unique |
| Negative price seed | Reject at `put*` | Catalog integrity |
| Missing store id | `unknown store` | Explicit |

**Learning:** Edge cases are **Positives** when volunteered. After happy path, say: “Before multi-store, let me lock validation…”

---

## E. Formula & money

### Q10. Exact formula?

```text
price(pizza) = base(base) + size(size) + Σ topping(t)
orderTotal   = Σ price(pizza_i)
```

### Q11. Why `int` paise instead of `double`?

**A:** Money in binary floating point → rounding bugs (classic interview trap). Prefer integer minor units or `BigDecimal` with defined scale. For LLD, **int paise** is clean and fast to explain.

### Q12. Where would tax / delivery fit later?

**A:** New collaborator, e.g. `TaxPolicy` / `FeeCalculator` applied in `getOrderPrice` — **not** inside `Pizza`. Keeps OCP: pizza data unchanged.

---

## F. Extensibility drills

### Q13. Add topping “Jalapeno” — steps?

1. Add `JALAPENO` to enum  
2. `catalog.putTopping(JALAPENO, price)` per store  
3. No change to `PricingService`  

### Q14. Add “stuffed crust” base?

Same — enum + catalog entries.

### Q15. 50% off all toppings after 9pm?

Now algorithm changes → introduce `PricingRule` / decorator on service or order-level promotion. Still **don’t** create `CheeseHalfOffStrategy` + `OliveHalfOffStrategy`. One `TimeWindowToppingDiscount` rule is enough.

### Q16. Combo: 2 large = 10% off order?

Order-level rule after pizza pricing. Catalog untouched.

---

## G. SOLID quick map (speak if asked)

| Principle | How we apply |
|-----------|--------------|
| **S** | Pizza = data; Catalog = prices; Service = formula; StoreManager = registry |
| **O** | New topping without editing service loop |
| **L** | (If interface) any `PricingCatalog` can substitute |
| **I** | Don’t force Order to implement topping APIs |
| **D** | Service depends on catalog abstraction, not static globals |

---

## H. Complexity / complexity traps

### Q17. Is this over-engineered for an interview?

**A:** For base+size+toppings, catalog + service is **right-sized**. Over-engineered = AbstractFactory + Visitor + Strategy per topping. Under-engineered = one method with three switches and static maps.

**Calibration:** Match complexity to stated requirements (multi-store + extensible).

### Q18. Thread safety?

**A:** Immutable pizza + read-only catalog after build → service is thread-safe if catalogs aren’t mutated after publish. If live price updates, use concurrent maps or copy-on-write publish of new catalog instance per store.

---

## I. Testing what you’d write (say it even if no IDE tests)

1. Single pizza known total (thin+large+cheese+olive)  
2. Same pizza, two stores → different totals (proves DI)  
3. Empty order throws  
4. Missing topping in catalog throws  
5. Zero toppings = base+size only  

**Learning:** Tests are how you show edge cases without waiting for interviewer prompts.

---

## J. Timed whiteboard script (35 min)

| Min | Do |
|-----|-----|
| 0–2 | Clarify + write formula |
| 2–8 | Boxes: enums, Pizza, Order, Catalog, Service, StoreManager + arrows |
| 8–25 | Code happy path + injection |
| 25–30 | Edge cases + second store demo |
| 30–35 | Stop if needed → leave Invent & Simplify time |

---

## K. Self-grade checklist (from your feedback)

**Positives to force**
- [ ] Clarifying questions  
- [ ] Enums  
- [ ] SRP / OCP named **and applied**  
- [ ] Catalog map approach (not class explosion)  
- [ ] Multi-store via instance injection  

**Concerns to eliminate**
- [ ] Edge cases only after prompt → volunteer them  
- [ ] Classes named, never wired → run Main / walk call path  
- [ ] Static global prices → instance catalogs  

**Assessment target paragraph**

> Candidate clarified requirements, separated pizza data from pricing policy, used a map-based catalog for extensibility, and correctly extended to multi-store via per-store catalog injection without modifying PricingService. Edge cases handled without prompting. Design was fully wired end-to-end.

---

## L. Mini glossary (if brain blanks)

| Term | One line |
|------|----------|
| Catalog | Lookup table of prices |
| DI | Pass dependency in constructor |
| SRP | One reason to change per class |
| OCP | Add behavior by adding data/modules, not editing core |
| Strategy | Pluggable *algorithm* — use when algorithms differ |
