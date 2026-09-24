# Round debrief — failure modes to kill

Two interviewer loops on the same round shape. Extract only the patterns that cost you signals.

---

## Loop A — Sr SDE L6 (Not Inclined)

### Summary failure
- OOP / SRP / Strategy **named** but not turned into a **working, wired** design.
- Highest Standards story did not show a real quality–speed tension.

### Highest Standards — Concern
**Q:** Trade-off between delivering quickly and maintaining high quality. What did you decide and why?

**Story used:** Middle-mile reconciliation (logistics).

**Why it scored Concern**
1. Could not crisply explain **business meaning** of “reconciliation” (who loses money / what breaks).
2. Even after prompts, no clear **tension** — sounded like “we just shipped quality,” not a hard choice.
3. No data that **you** pushed the quality bar under deadline pressure (metrics, reject ship, negotiate scope).

**Fix for next time**
- Lead with HCP / Recon / GracePeriod (see LP file).
- Opening line must state: deadline + quality risk + what you refused to ship + what you deferred with owner + how you verified later.

### LLD — Mild Concern
**Problem:** Pizza pricing engine (base + size + toppings), in-memory, no DB.

**Positives they still wrote**
- Clarifying questions
- Enums for Base / Size / Toppings
- SRP + Open/Closed called out
- Classes like `PriceCatalog`, `StoreManager` identified

**Concerns**
1. Edge cases needed **heavy prompting** (defaults, invalid topping, empty order).
2. Multi-store pricing: did not see that static catalog → **instance catalog injected per store** (DI), so design did not extend cleanly.

**Assessment pattern:** Knows foundations; struggles to **wire** a complex design independently.

### Invent and Simplify — No Data
Time ran out. Pace LLD so this LP still gets one story + one metric.

---

## Loop B — Sr SDE L6 (Not Inclined)

### Highest Standards — Mild Concern
**Q:** Same quality vs speed trade-off.

**Story used:** Facebook Graph API migration v12 → v17 (1-week deadline).

**What worked (Positives)**
- Did not derail migration for latency side-quest.
- Proactively flagged latency scope to manager and got explicit trade-off buy-in.

**What still hurt**
- Follow-up: could not say whether deferred latency work **ever landed**.
- Weak “insist” signal — more “chose deadline” than “raised the bar then closed the loop.”

**Fix**
- Every trade-off story needs a **closed loop**: ticket / owner / later metric (or honest “I tracked X; closed in sprint N”).

### LLD — same pizza problem
**Entities they expected you to land**
- `Order`, `Pizza`, `PricingService`, `PricingCatalog`
- Enums: `Base`, `Size`, `Topping`
- Catalog methods: `getBasePrice`, `getSizePrice`, `getToppingPrice`
- `PricingService.getPrice(pizza)` — **not** `Pizza.getPrice()` owning pricing rules

**Anti-pattern they called out**
- Strategy class per crust / per topping → explosion of classes
- Prefer **map-based `PricingCatalog`** + single service

**Missing wiring**
- How catalog is injected into `PricingService`
- How multi-store swaps catalogs without rewriting service

### Invent and Simplify — No Data again

---

## Non-negotiable checklist before next Round 2

- [ ] Highest Standards: 60s story with tension + metric + closed loop
- [ ] Backup Highest Standards story ready (different domain)
- [ ] Invent & Simplify: 60s story ready even if only 5 minutes left
- [ ] Pizza LLD: catalog injection + multi-store + edge cases without prompt
- [ ] Timed dry-run once: do not let LLD steal Invent & Simplify
