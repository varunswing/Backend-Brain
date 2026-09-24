# Invent and Simplify — Full Prep (Learning Pack)

**Target status:** Strength  
**Past Round-2 risk:** **No Data** (LLD ate the clock) — always keep the **60s cut** warm.

**Primary story:** TrueFlexi EC/LC — Special Request → greenfield **INGO-Ancillary** → **DO PaidEcLc**  
**Backup:** Enigma Dual Server · CUG Additional Discount  
**Sources:** `../../TrueFlexiECLC/STAR.md`, `../../EnigmaDualServer/STAR.md`, `../../CUGAdditionalDiscount/STAR.md`  
**Cross-questions:** [`invent_and_simplify_cross_questions.md`](./invent_and_simplify_cross_questions.md)

---

## What “Invent and Simplify” means at Amazon

| Not enough | Strength signal |
|------------|-----------------|
| “I built a new service” | You **removed** complexity for others |
| “I used a cool pattern” | Naive alternative was **more complex**; you chose simpler |
| Feature dump | One invent moment + measurable simplicity |

**Close line (TrueFlexi):**
> “Invent = Ancillary as **system of record** for addons. Simplify = extranet talks **one DO API**, not N-repo patches per product tweak.”

---

## 60-second cut (use if only 5 minutes left)

> “Hotels had no end-to-end **Early Check-in / Late Checkout** path: guests couldn’t ask reliably, hosts couldn’t sell priced tiers, extranet would have patched **many** booking services. I led three phases — Special Request across **5 repos**, greenfield **INGO-Ancillary** (5-table config + state-diff upsert), then **DO PaidEcLc** that merges Enigma metadata + Ancillary in **one** client contract. Invented a **generic addon framework** (new types via Consul, not code deploy). Simplified host/extranet integration to **one API**. Hardened with **143** gRPC tests and prod fixes (BoolValue, EqualFold). Result: request → monetize TrueFlexi without turning every product change into a multi-repo fire drill.”

---

## Full STAR — TrueFlexiECLC (~2–2.5 min)

### Situation

INGO Hotels Supply had **three gaps** (from `TrueFlexiECLC/STAR.md`):

1. Guests could not reliably **request** EC/LC at booking (communication).  
2. Hotels could not **sell** EC/LC as priced products (EC_3/6/9, LC_3/6/9; %/fixed; inventory; blackouts; min LOS/AP).  
3. Extranet needed **one API** merging **Enigma metadata** + **addon configs** — different schemas.

**Naive complex path:** Stuff paid EC/LC into Enigma or ReservationEngine → every product tweak = multi-service deploy; no clean sharing of search/price/inventory at different levels; booking releases coupled to ancillary experiments.

### Task

| Phase | Deliverables |
|-------|----------------|
| **1 — Special Request** | Proto #183; RE #124; Interlink #117; Inventory #2950; Enigma #1537 |
| **2 — INGO-Ancillary** | Schema, Get/Upsert APIs, processor/DAO, auth, audit, Kafka, bootstrap |
| **3 — DO PaidEcLc** | `GetPaidEcLcData`, `UpsertPaidEcLcData`, graph nodes, builders, **143** tests |

Deliver **request → monetize** with a client-friendly contract and a platform that grows to other addons.

### Action — invent + simplify

**Phase 1 — Special Request (scoped invent)**  
Proto-first across 5 repos — guest can ask; host can see. Not yet pricing.

**Phase 2 — Invent: INGO-Ancillary (system of record)**
```text
Proto → Router → Factory → Service → Validator → Formatter → Processor → DAO → Transformer
```
- **5 tables:** config, search, price, inventory, mapping  
- **State-diff upsert** + inline **dedup** (shared config UPDATE vs INSERT override)  
- TrueFlexi sharing: Search@category, Price@subcategory, Inv@Entity  
- **Consul `addon_categories`:** new addon types without code deploy  
- Audit + Kafka cache refresh post-commit  

**Phase 3 — Simplify: DO PaidEcLc (one extranet contract)**  
- Extranet does **not** speak Ancillary nested generic JSON  
- DO product-shaped GET/UPSERT; parallel Enigma + Ancillary; merges rooms, blackouts, inventory  
- Updates Enigma `isPaidEcLc` when needed  
- Prod hardening: `BoolValue`, `chargeValue=0`, `EqualFold` case fixes  

**Deliberately did not build:** per-product forks in RE for every EC/LC rule; extranet→Ancillary direct coupling.

### Result

**Speak (30–40s):**
> “Before: no EC/LC product, or every tweak = multi-repo fire drill. After: request → monetize TrueFlexi; extranet = **one** DO API; new addon types via **Consul** (no code deploy). Gated with **143** gRPC ITs, Jenkins DockerfileIT, Sonar/coverage, Grafana on Ancillary + DO. Five prodpp bug classes closed before rollout. Invent = Ancillary SoR; simplify = everyone else talks one contract.”

| Outcome | Before → After |
|---------|----------------|
| Guest / host | No path → Special Request + paid EC/LC tiers |
| Extranet | N-service / nested Ancillary → **one** DO Get/Upsert |
| New addon type | Code deploy spaghetti → **Consul categories** |
| Quality gate | Manual → **143** IT + Jenkins IT + Sonar coverage |
| Ops | Blind greenfield → Grafana (`ingo-ancillary`, `dynamic-orchestrator`) |
| Prod | — → **5** bug classes fixed in prodpp first |

| Delivery | Value |
|----------|-------|
| Phase 1 / 2 / 3 | 5 repos · greenfield Ancillary (2 APIs, 5 tables) · DO + **143** tests |
| JIRA | INGO-42453, INGO-42883 |

### Close
> “Invented the addon platform; simplified the client surface. Complexity moved to **one place** (Ancillary processor), not deleted from the universe — that’s the right simplify.”

---

## Problems & Challenges (after Result)

### 1. State-diff upsert + config dedup
Per-room price override while sharing search/inv across EC_3/6/9 — naive CRUD duplicates or corrupts shared rows.  
**Fix:** 5-table schema; `buildExistingState` → `buildNewState` → dedup → master txn.

### 2. DO GET null despite DB rows
Legacy lowercase `earlycheckin` vs exact `==` filter.  
**Fix:** `EqualFold` on category/subcategory.

### 3. Proto3 bool vs unset
MBW/MLOS couldn’t distinguish unset vs deactivate.  
**Fix:** `google.protobuf.BoolValue`; always send `chargeValue` with `chargeType`.

### 4. Other
Policy-only upsert defaults · ANC_409 auth · UPDATE→GET verify suite (39 cases) · single txn for concurrent edits.

---

## Backup #2 — Enigma Dual Server (ops simplify)

### 60s
> “Enigma needed **HTTP + gRPC** as **two pods** — 4–5 min local setup, multi-container IT, fragmented coverage. I built **`SetUpBothServers()`** — one process, shared DI, unified signals, **30s** drain — gated by **`ENV != PROD`**. Prod path **unchanged** for **50M+/day**. Setup **&lt;1 min**, IT **−60%**, coverage **+40%**, zero prod incidents; pattern copied.”

---

## Backup #3 — CUG Additional Discount (model simplify)

### 60s
> Source STAR (Invent & Simplify shape): `../../CUGAdditionalDiscount/STAR.md`

> “Hosts needed weekday/segment **extra** discount on CUG. Stuffing a weekday matrix into **one** offer row explodes. We invented **parent CUG + sibling AD rows**, Extranet tier-2 drawer, Enigma path, Goku `CUG_ADDITIONAL_DISCOUNT` — **6 repos**. Simplify = **reconcile siblings**, don’t overload one row. I owned Extranet + Enigma + Goku integration; PG/PE coordinated.”

---

## Positives / concerns / time-box

**Want written:** Clear invent (Ancillary SoR) · complexity removed for clients · one API / Consul no-deploy · honest trade-offs  

**Never leave open:** Ownership-only story with no invent · no naive alternative · **No Data** because LLD overran  

**Time-box:** At LLD minute **35**, stop coding → deliver **60s TrueFlexi** → offer Enigma dual-server if they want a second data point.

**Self-assessment target:**  
> Created an addon system of record and simplified extranet to a single orchestrated API, with clear contrast to the multi-repo naive path and measurable reduction in change surface.
