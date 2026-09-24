# LLD practice notes (interviewer format)

**Status:** Strength *(target after drills)*

**Problem:**
> Design a pricing engine for a pizza delivery service (base + size + toppings). In-memory, no DB.

---

## Positives *(example — rewrite after your mock)*

- Clarified flat toppings, multi-pizza order, multi-store = price books first
- Enums for Base / Size / Topping; Pizza/Order as data-only
- Map-based `PricingCatalog` instead of Strategy-per-topping
- `PricingService` constructor-injected with catalog; multi-store via `StoreManager`
- Volunteered edge cases (empty order, missing catalog key, negative seed)
- Wired end-to-end (`Main` demo: same pizza, two store totals)

## Concerns *(example empty = good mock)*

1. *(none if Strength)*
2. 
3. 

## Assessment *(target)*

> Candidate clarified requirements, separated pizza data from pricing policy, used a map-based catalog for extensibility, and correctly extended to multi-store via per-store catalog injection without modifying PricingService. Edge cases handled without prompting. Design fully wired end-to-end.

---

## Self-check before calling it Strength

- [ ] Clarifying questions asked first
- [ ] Enums for Base / Size / Topping
- [ ] `Pizza` has no pricing rules
- [ ] `PricingCatalog` is map-based (not class-per-topping)
- [ ] `PricingService` takes catalog via constructor
- [ ] Multi-store = different catalog instances
- [ ] Edge cases handled without prompt
- [ ] End-to-end call path demonstrated (main / test)

## Time log

| Phase | Minutes |
|-------|---------|
| Clarify + design | |
| Code | |
| Test / walkthrough | |
| Left for Invent & Simplify? | Y / N |

**Drill ref:** `CrossQuestions.md` — especially §B (SRP/Strategy), §C (multi-store), §D (edges).
