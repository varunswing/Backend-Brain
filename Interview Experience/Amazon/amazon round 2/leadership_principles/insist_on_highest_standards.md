# Insist on the Highest Standards — Full Prep (Learning Pack)

**Target status:** Strength  
**Canonical Round-2 Q:**
> Tell me about a time when you had to make a trade-off between delivering something very quickly and maintaining high quality. What did you decide and why?

**Primary story:** Extra-guest **HCP** tax/cost correctness (PriceEngine → Goku)  
**Backup:** GracePeriodAndHourCP (display ↔ enforcement parity)  
**Sources:** `../../HCP/STAR.md`, `../../GracePeriodAndHourCP/STAR.md`  
**Cross-questions:** [`insist_on_highest_standards_cross_questions.md`](./insist_on_highest_standards_cross_questions.md)

**Authorship (honesty):** Hands-on STAR depth = **HCP extra-guest differentials** (PE + Goku). As **lead**, also drove the Sep 2025 gov GST program’s two other tracks — **Goku forward slabs** (`#1094`/`#1153`) and **Inventory booking GST update → refund** (`#3274`/`#3353`/`#3347`) — but **teammates authored** those PRs. Say: *“I led the program; they coded those PRs; I coded HCP.”* Never claim Goku/Inventory PR authorship.

---

## What “Insist on the Highest Standards” means

Not “I wrote perfect code.” Under pressure to ship fast, you:

1. Name a **minimum quality bar** you will not cross  
2. **Refuse** options that cross that bar  
3. Still deliver — by accepting a *different* compromise (phased scope, additive contracts, more tests) — **not** by shipping broken customer money  

Here the bar = **money on the guest bill / ledger**: extra-guest **HCP tax must not be negative or wrong** at GST slab boundaries.

---

## Default pack (memorize)

| Layer | Choice |
|-------|--------|
| Story | **HCP** extra-guest differentials (~15% bookings / ~50K/day) |
| Structure | Conflict-first: refuse known-wrong tax under deadline |
| Bar | Release gate + additive proto + no wrong guest/ledger money |
| Senior signal | Dual-method engine, 4 hotel types, 12 consumers, &lt;2ms |
| Backup | Grace / Hour CP — multi-repo EasyJSON parity |

**Close line:**
> “Highest Standards = I wouldn’t ship wrong extra-guest money under delivery pressure. Speed came from reusing PE→Goku and additive protobuf — not from lowering the money bar.”

---

## Bar / Pressure / Decision / Trade (detailed)

### Bar I refused to drop

1. Extra-guest **HCP tax/cost/commission differentials are correct** across GST, International, Synxis, Derby.  
2. Isolation method must not emit **negative or wrong tax** at slab boundaries.  
3. Do not call the release “done” while we **know** boundary cases lie.  
4. Do not break **12** downstream consumers with a hard proto cut.

**Not good enough:** “Ship fields; fix negatives in a follow-up ticket.”

### Pressure

- Product/leadership wanted HCP fields landed **quickly** across pricing.  
- Easy path = one calc path, thin tests, ship proto, let consumers / prod catch edge cases.  
- At ~50K/day extra-guest bookings, boundary wrong tax is **not** polish — it’s wrong money.

### Decision

| Track | What I did | Why it meets standards |
|-------|------------|------------------------|
| **Hold money bar** | Boundary detection + **Direct** fallback + safeguards | Refuse isolation-only |
| **Keep speed** | Reuse PE→Goku pipelines; **additive** Protobuf | Timeline without cutting bar |
| **Release gate** | 47 unit + 23 IT; 500+ scenarios; coverage 68%→94.5% | Known-wrong tax cannot merge |

**Accepted:** more engineering under the same window; staggered consumer adopt.  
**Rejected:** shipping known-wrong extra-guest tax.

### Trade-off (say this clearly)

A trade-off = what you **spend** vs what you **protect**.

**Spent / gave up:** Extra cycles on dual-method + tests; deferred full supplier-adapter rewrite; not the smallest possible patch.  
**Protected:** Guest/ledger money correctness; 12-consumer safe rollout; SLA (&lt;2ms add).  
**Did not do:** Miss the delivery entirely to polish forever, **or** ship wrong tax to look fast.

**Analogy:** Deadline to open the pricing feature on Monday. Cash register sometimes prints **negative tax**. Highest Standards = open Monday **and** fix the register. Temporary shortcuts that don’t print garbage are OK; opening while you know the register lies is not.

**One-liner trade:**
> “I traded short-term ‘smallest patch’ elegance for dual-method + a hard release gate. I refused to trade **correct guest money** for an earlier merge.”

---

## 60-second cut

> “On MakeMyTrip pricing, ~**15%** of bookings (~**50K/day**) have extra guests. We needed correct **HCP differentials** — cost/tax/commission savings on those guests after discounts — across GST, International, Synxis, and Derby. Delivery pressure was to land the fields and ship. I found isolation-based tax breaking at **GST slab boundaries** — wrong or **negative** tax. Fast path = ship anyway. I refused. We added a **boundary detector** and **direct-method fallback**, kept **additive protobuf** so **12** consumers didn’t break, reused PE→Goku, and held release for **500+** scenarios. Error **3.2% → 0%**, **&lt;2ms**, support **−28%**. The formula was the tool; the standard was **don’t ship known-wrong guest money**.”

---

## Full STAR (~2–2.5 min)

### Situation

MakeMyTrip hotel pricing (PriceEngine → Goku). Large share of bookings include **extra guests**. For those we need **HCP differentials** — difference in cost, tax, service charge, commission between undiscounted and discounted extra-guest price — for revenue attribution and price transparency.

Four hotel types lacked proper extra-guest HCP — wrong/incomplete money on ~**15%** of bookings (~**50K/day**). Leadership wanted it landed quickly.

Hard failure on **GST hotels**: isolation `tax(base+extra) − tax(base)` produced **wrong or negative** tax at **slab boundaries**. Shipping under deadline would institutionalize bad guest-facing and ledger numbers.

### Task

Ship correct extra-guest HCP across hotel types **without** breaking **12** downstream services, inside **200–250ms** SLA, with a release bar that forbids known-wrong tax.

### Action

**Fast path (rejected as sole plan):** one calculation path, thin tests, ship fields, fix boundaries later.

**What I insisted on:**
1. **Refuse** isolation-only once slab boundaries lied.  
2. **Dual-method** — isolation primary; **direct** `extra × effectiveRate` when rate-change **>15%**, tax differential **>1%**, or isolation **&lt;0**.  
3. **Additive** protobuf (5 fields) — quality of **rollout**, not only formula beauty.  
4. **Reuse** PE→Goku pipelines (speed without cutting money bar).  
5. **Release gate** — 47 unit + 23 integration, 500+ scenarios, coverage **68% → 94.5%**.  
6. Explicit stakeholder pushback: monitoring wrong tax in prod is not “fast.”

Ownership: differential engine + PE/Goku wiring (extra-pax HCP tickets). Not the separate gov slab override program.

### Result

- Calculation error narrative **3.2% → 0%**  
- **&lt;2ms** latency add; pricing support **−28%**; commission reporting precision **+15.3%**  
- Zero post-release incidents; consumers adopt additive fields  
- Speed via reuse + additive contracts; quality via refuse + gate  

**Close:** I would not call pricing “done” while we still shipped known-wrong extra-guest tax.

---

### STAR crib

| | Speak |
|---|--------|
| **S** | Extra-guest HCP missing/wrong (~15%, ~50K/day); isolation breaks at GST edges → negative tax; ship pressure |
| **T** | Correct money + 12 consumers safe + SLA + release gate |
| **A** | Refuse isolation-only; dual-method; additive proto; PE→Goku reuse; 500+ scenarios |
| **R** | 3.2%→0%; &lt;2ms; −28% tickets; zero incidents |
| **Close** | Don’t ship known-wrong money; speed ≠ lower bar |

---

## Problems & Challenges (after Result — interview depth)

### 1. Negative / wrong extra-guest tax at GST slab boundaries

**Problem:** Isolation yields non-physical tax at slab cliffs.  
**Fix:** Boundary detector → Direct method; never emit negative tax.  
**One-liner:** *“At GST boundaries isolation lies — fall back to direct tax on extra price.”*

### 2. Four hotel types, one pipeline

**Problem:** GST / Intl / Synxis / Derby each need different tax/commission/net logic.  
**Fix:** Unified `setHCPForExtraGuest` + per-type paths; additive `ExtraPax` fields.

### 3. Twelve consumers at risk

**Problem:** Hard proto break forces synchronized deploy under deadline.  
**Fix:** Additive fields only; defaults; staged consumer adopt.

### 4. Other issues (table)

| Issue | Fix |
|-------|-----|
| Synxis rack ratios | `nett/base` handling |
| Derby missing net breakup | Validate/calculate when absent |
| Zero-division / rounding | `RoundFloat` + guards |
| Duplicate tax paths | Consolidate unified pipeline |

---

## Backup — Grace / Hour CP (60s)

> Source STAR (Highest Standards shape): `../../GracePeriodAndHourCP/STAR.md`

> “Cancellation policy: hosts needed hotel-level **grace** and **hour** rules for last-minute stays. Fast path = ship admin UI. I found hour could display while RE never stored it — **display↔enforcement** lie, refund risk. I refused. Contract-first Proto→RE→DO→Enigma: grace **auto-reactivate**, hour `6H40P` + EasyJSON/passthrough so cancel uses the same clock as the UI. Bar = what you configure is what cancel evaluates.”

---

## Org context only (never as your STAR)

> “Same GST deadline had **three tracks**: Goku forward slabs + Inventory existing-booking recalc/refund (teammates coded; I led) + **HCP** (my hands-on). Interview depth stays on HCP refuse/wrong-money bar.”

---

## Positives / concerns / checklist

**Want written:** Named money bar + refuse · additive proto · 3.2%→0% · &lt;2ms · clear HCP ownership  

**Never leave open:** Claiming Goku `#1094`/`#1153` or Inventory `#3274`/`#3347` as **your** commits · 10-min GST lecture · no refuse moment · hiding lead role on booking-refund track  

**Checklist:**
- [ ] Lead with **wrong extra-guest money**  
- [ ] Say **refuse** explicitly  
- [ ] Name **additive proto + 12 consumers + 500+ scenarios**  
- [ ] Quote **3.2%→0%** and **&lt;2ms**  
- [ ] Gov Sep-22 ask → **led** Goku + Inventory refund; **coded** HCP  
- [ ] Grace 60s warm for second ask  

**Self-assessment target:**  
> Refused to ship known-wrong extra-guest HCP tax under delivery pressure; dual-method safety path, additive multi-consumer contracts, scenario release gate; outcomes quantified; ownership limited to HCP PE/Goku work.
