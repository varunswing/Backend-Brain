# Insist on Highest Standards — Cross Questions

> Separate from the main pack. Source story: **HCP** (`../../HCP/STAR.md`).  
> Main prep: [`insist_on_highest_standards.md`](./insist_on_highest_standards.md)

**Canonical Q:** Trade-off between delivering quickly and maintaining high quality — what did you decide and why?

---

## Follow-ups (HCP DEFAULT)

### Q1. What was the quality risk if you rushed?

**A:** Wrong/negative extra-guest tax → guest quote vs settlement drift, commission/revenue wrong, support load. At ~50K/day that is not “edge case polish.”

---

### Q2. What did you defer?

**A:** Full rewrite of all supplier adapters; bundling unrelated offer ontology. Shipped correct core differentials + additive contract; staggered consumer adopt.

---

### Q3. Closed loop — how did you know quality held?

**A:** Scenario matrix + coverage gate **before** release; consumers on additive fields; metrics after (error rate, tickets −28%).

---

### Q4. Isolation vs Direct (one minute max)?

**A:** Isolation = incremental tax from combined vs base. At slab cliffs it goes non-physical → Direct = `extra × effective rate`. Detector trips the switch. Then return to **refuse + gate** — don’t stay in formula land.

---

### Q5. Why additive protobuf?

**A:** Clean break forces 12-way sync under deadline = outage risk. Additive = quality of **rollout**, not only formula beauty.

---

### Q6. Standards vs Dive Deep — how do you tell them apart?

**A:** Dive Deep = finding slab failure / how isolation breaks. **Standards = refused to ship it** and set the release bar. For the trade-off Q, lead Standards.

---

### Q7. Did you do the Sep 2025 gov GST slab override / booking refund?

**A:** **Lead, not author — be honest.**

| Track | What | Code authors | You |
|-------|------|--------------|-----|
| Goku forward | `getFinalTaxSlabs` override 12%→5% after change date; later `#1153` remove | Teammates (`#1093`–`#1153`) | Lead: scope, review, ship gate |
| Inventory booking update | Recalc already-booked breakups 12%→5% → booking update → **tax delta refund** | Teammates (`#3274`, `#3353`, `#3347`) | Lead: must cover existing stays, not only search |
| HCP | Extra-guest boundary tax | **You** | Hands-on STAR |

One-liner: *“I led the GST money program; teammates shipped Goku + Inventory PRs; I personally fixed HCP under the same deadline.”*

---

### Q8. Same trade-off again — what would you repeat?

**A:** Refuse known-wrong money; keep additive contracts + scenario gates. Improve earlier shared slab fixtures with QA so boundary cases are caught sooner.

---

### Q9. What was the explicit trade-off?

**A:** Spent sprint capacity on dual-method + tests instead of the smallest field-only patch. Protected guest/ledger correctness and consumer-safe rollout. Did **not** miss delivery entirely, and did **not** ship known-wrong tax.

---

### Q10. How did you push back on stakeholders?

**A:** Framed risk in business language: wrong tax at 50K/day scale ≠ “edge case.” Proposed reuse + additive proto to keep timeline while holding the money bar. Asked for release gate (scenario matrix) as definition of done.

---

### Q11. Backup — Grace / Hour CP Standards angle?

**A:** Bar = display ↔ enforcement parity. Refuse “hour in UI only.” Trace EasyJSON/passthrough; Proto→RE→DO→Enigma; hotel flag. Trade = phased deploy order, not half-shipped hour.

---

### Q12. Metrics they might challenge?

| Claim | Defend |
|-------|--------|
| 3.2% → 0% | Pre-safeguard error rate on scenario suite → post dual-method |
| &lt;2ms | Reused tax pipelines; no new remote hops |
| −28% tickets | Extra-guest pricing support class post-deploy |
| 12 consumers | Additive ExtraPax fields; no hard break |

---

## Anti-patterns (fail the LP)

- Claiming gov slab `#1094` / `#1153` as yours  
- 10-minute GST arithmetic with no **refuse**  
- “We just implemented HCP” with no quality bar  
- Absolute “hotels had no tax” (false)
