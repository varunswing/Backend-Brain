# Invent and Simplify — Cross Questions

> Separate from the main pack. Primary story: **TrueFlexiECLC** (`../../TrueFlexiECLC/STAR.md`).  
> Main prep: [`invent_and_simplify.md`](./invent_and_simplify.md)  
> Deeper product Q&A also: `../../TrueFlexiECLC/CrossQuestions.md`

---

## TrueFlexi — invent / simplify probes

### Q1. What was the naive complex alternative?

**A:** Embed paid EC/LC rules inside **Enigma + RE + Inventory** ad hoc:
- Price/inventory/search duplicated or inconsistently owned  
- Every new subcategory (EC_9) = code changes in multiple repos  
- Extranet fans out to several backends and merges in UI  
- Booking train blocked by ancillary experiments  

That “works once” and becomes unmaintainable.

---

### Q2. Why was your solution simpler, not just different?

**A:** Simpler **for the majority of future change**:

| Change | Old world | New world |
|--------|-----------|-----------|
| New EC tier / price | Multi-repo | Ancillary upsert + Consul |
| Host configures blackouts | Scattered | Search config level |
| Extranet reads EC/LC | Multi-call merge in FE | One DO GET |
| New addon category | New service spaghetti | Category config + same APIs |

Accepted **more complexity inside Ancillary processor** (state-diff, dedup) so **everyone else** stays simple. Invent & Simplify often means **concentrate** complexity behind a clean boundary.

---

### Q3. What did you deliberately not build?

**A:**
- Hard deletes (soft `isActive` only)  
- Cross-level config migration (search can’t casually move category→entity)  
- Extranet speaking raw Ancillary nested IDs  
- Idempotency / optimistic locking in v1 (call out as future — don’t pretend shipped)  
- Rewriting Special Request into Ancillary (kept communication vs monetization phases clear)

---

### Q4. How did others adopt it?

**A:**
- Extranet / host flows via **DO PaidEcLc**  
- Goku cache path via Kafka addon topic  
- Consul-driven categories so product can add variants without a platform release train  
- New addon types use **same Get/Upsert** contracts

---

### Q5. Trade-off of the simplification?

**A:**
- **Cost:** New service to run (MySQL, auth, deploy, IT)  
- **Cost:** DO translation layer must stay correct (case folding, BoolValue)  
- **Benefit:** Isolates booking stack from addon experiment velocity  
- **Mitigation:** 143 gRPC tests, layered validator, read-slave / write-master txn discipline  

Simplification ≠ fewer lines in the universe — it’s **fewer places product must touch**.

---

### Q6. Walk the config hierarchy (whiteboard)

**A:**
```text
Category EarlyCheckin
  Search S1 ──────────── shared by EC_3, EC_6, EC_9
  EC_3  Price P1 ─────── shared by all rooms in EC_3
  EC_6  Price P2
  Room 451 Inv I1 ────── shared across EC_3/6/9 for that room
  Room 452 Inv I2
```
Enrollment: new room in category → mapping rows for **all active** subcategories.

---

### Q7. Dedup example (they love this)

**A:** EC_3 price shared as row 300 for rooms R1+R2. Override R1 to 18%:
- Cannot UPDATE 300 in place (R2 still needs old %)  
- **INSERT** price 302, **UPDATE** only R1 mapping → 302  

Shows you **invented** safe mutation rules for shared configs.

---

### Q8. Why DO if Ancillary already has APIs?

**A:** Ancillary is **generic** (nested, request-local IDs). Extranet product is **TrueFlexi-shaped**. DO:
- Hides generic complexity  
- Parallel-fetches Enigma + Ancillary  
- One auth/product boundary for extranet  

Simplify for the **client**, invent for the **platform**.

---

### Q9. Invent vs Ownership — how do you tell them apart?

**A:**
- **Ownership:** I drove three phases until guests could buy EC/LC  
- **Invent & Simplify:** The *shape* — new SoR + one DO contract vs N-repo patches  

Same project can serve both; pick the LP the question asks for and lead with that sentence.

---

### Q10. What would you invent differently next time?

**A:** Earlier case-insensitive contracts in DO; stricter proto wrappers for bools from day one; “addon onboarding guide” so Consul categories are self-serve for PMs. Core invent (SoR + DO façade) I’d keep.

---

### Q11. Hardest production bug?

**A:** DO GET empty despite Ancillary rows — case-sensitive category filter vs lowercase DB (`EqualFold`). Or BoolValue / `chargeValue=0` persistence. Point to 143 tests catching regressions.

---

### Q12. Why three phases instead of one big bang?

**A:** SR validates UX/contracts on live booking paths with lower risk. Paid addons need pricing, inventory, enrollment — different domain. Stuffing into RE couples rate changes to reservation releases.

---

## Backup — Enigma Dual Server cross-Q

| Probe | Answer |
|-------|--------|
| Naive alternative? | Keep 2 pods; document harder |
| Why simpler? | One command, one coverage report, one debug process |
| Trade-off? | Dual-mode complexity in bootstrap — isolated behind ENV gate |
| Prod safety? | Prod code path byte-identical; feature only non-PROD |

---

## Backup — CUG Additional Discount cross-Q

| Probe | Answer |
|-------|--------|
| Naive? | One mega offer document with nested weekday maps |
| Why sibling? | Clear attribution base vs AD; edit/legacy isolation; PE ApplyBestCug stays coherent |
| Your role? | Extranet + Enigma + Goku integration; coordinate PG/PE/Content |
