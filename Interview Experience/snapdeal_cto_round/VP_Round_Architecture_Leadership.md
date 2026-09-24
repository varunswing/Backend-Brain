# VP Round Attack Sheet — Architecture Deep Dive + Leadership + Culture

**Format (typical ~25–30 min):**
| Block | Time | What they want |
|-------|------|----------------|
| 1. Architecture & past projects | 10–12 min | Ownership, scale, bottlenecks, trade-offs, HLD |
| 2. Leadership & scalability | 8–10 min | HA, DR, consistency, peak concurrency scenario |
| 3. Culture / behavioral | 5–7 min | Mentorship, stakeholders, tech debt, incidents, disagreement |

**Interviewer signal (VP):** Less trivia than Rampal; more **judgment, ownership clarity, scale thinking, fintech/banking-safe instincts** (consistency, fault tolerance, security). Banking/fintech language maps cleanly to **Paytm money paths** + **MMT guest-money correctness**.

**Pick ONE primary deep-dive** (don’t thrash):
| If they lean… | Primary | Backup 60s |
|---------------|---------|------------|
| Commerce / platform invent | **TrueFlexi** (Ancillary + DO) | HCP / GST |
| Money correctness / trade-off | **HCP + GST program lead** | TrueFlexi |
| Fintech HA / concurrency / DR | **Paytm refund P0 + Kafka** | DR drill / multi-bank migration |

**Pronoun rule:** **I** owned / designed / investigated / decided — not “we did.”

**Related:** [`Rampal_Rao_Attack_Sheet.md`](./Rampal_Rao_Attack_Sheet.md) · [`CTO_Round_Prep.md`](./CTO_Round_Prep.md)

---

## BLOCK 1 — Deep Dive (10–12 min)

### How to use the clock

| Min | Do |
|-----|-----|
| 0–1 | Confirm which project they want; state ownership + scale in one breath |
| 1–4 | HLD whiteboard / verbal architecture + traffic flow |
| 4–7 | Bottleneck you found + how you fixed |
| 7–9 | Trade-offs (SQL/NoSQL, sync/async, speed vs quality) |
| 9–12 | Results + what you’d do differently; invite their drill |

**Opening line (pick one):**

**TrueFlexi:**
> “Most complex platform build: **TrueFlexi Early Check-in / Late Checkout**. I owned end-to-end across three phases — Special Request on the booking mesh, greenfield **INGO-Ancillary** as addon system of record, and **DO PaidEcLc** so extranet has one API. Happy to walk HLD, bottlenecks, and trade-offs.”

**HCP / GST:**
> “Most critical money-path work: **extra-guest HCP tax correctness** under India’s GST deadline. I **led** Goku forward slabs + Inventory booking→refund (teammates coded), and **personally fixed** HCP dual-method tax. Scale: ~15% bookings / ~50K/day extra-guest. Bar was don’t ship known-wrong guest money.”

**Paytm P0:**
> “Most critical production ownership: UPI **refund P0** — ~3% failure, ~₹2cr stuck. I led RCA across Kafka retries, HikariCP pool saturation, and DB locks, then stabilized and prevented recurrence.”

---

### Script A — TrueFlexi (HLD deep dive)

#### 1. Problem & ownership
> “Guests couldn’t reliably request EC/LC; hosts couldn’t sell priced tiers; extranet would have patched N booking services. **I** sequenced and delivered three phases.”

#### 2. HLD (speak + draw)
```text
Extranet ──GraphQL──► Enigma ──► DO PaidEcLc
                                    │ parallel
                         ┌──────────┴──────────┐
                         ▼                     ▼
                   Enigma metadata        Ancillary (SoR)
                   rooms, flags           5-table config
                                          state-diff upsert
                                          → Kafka cache topic

Booking path (Phase 1): Proto → RE → Interlink → Inventory → Enigma
```

#### 3. Why microservices here?
> “Ancillary = bounded context for addon config (search/price/inv at different grains + dedup). DO = simplify client. Booking/RE stays stable while addon product iterates. Cost: more ops — paid with Grafana + 143 gRPC ITs + Jenkins IT gate.”

#### 4. Bottlenecks you resolved
| Bottleneck | What you did |
|------------|----------------|
| Shared config CRUD corrupts rows | State-diff + dedup + single master txn |
| Extranet ↔ Ancillary impedance | Product-shaped DO API; parallel fan-out |
| Prod empty GET | EqualFold case fix |
| Bool / chargeValue unset | BoolValue wrappers; always send chargeValue |
| Release risk | 143 contract ITs before promote |

#### 5. Latency
> “DO runs Enigma + Ancillary **in parallel** — wall clock ≈ max(latency), not sum. Timeouts 10–15s on those hops; fail policy product-aware.”

#### 6. Database choice
> “**MySQL** for Ancillary — relational integrity, transactions for multi-table upsert, master/slave (build state from slave, write master). Not Mongo: money-ish config needs ACID-ish multi-row consistency. Kafka after commit for cache fan-out — SoT stays DB.”

#### 7. Trade-offs
> “Invent: concentrate complexity in Ancillary processor. Simplify: one DO API + Consul categories (no code deploy per addon type). Rejected: stuff into RE (couples releases) or extranet→Ancillary direct (leaks generic schema).”

#### 8. Results (30s)
> “Request → monetize TrueFlexi; one extranet contract; Grafana on ancillary + DO; 5 prodpp bug classes closed before wider rollout. JIRA INGO-42453 / 42883.”

#### Likely VP follow-ups
- “What was **your** code vs others?” → Phase ownership; Ancillary/DO depth; SR multi-repo drive  
- “10x upsert traffic?” → txn size, idempotency future, rate limit extranet, partition cache consumers  
- “Consistency if Kafka publish fails?” → DB committed = SoT; retry publish / alert; cache may lag briefly  

---

### Script B — HCP + GST (critical path / trade-off deep dive)

#### 1. Ownership clarity (say first — honesty)
> “Three tracks: **Goku** forward GST override, **Inventory** booking recalc→refund, **HCP** extra-guest tax. Teammates authored Goku/Inventory PRs; **I led** those tracks. **I coded** HCP dual-method + release gate.”

#### 2. HLD
```text
Search/Book ──► Goku (GST slabs, cache Aerospike/MySQL)
                    │
                    ▼
              PriceEngine (HCP differentials, tax)
                    │
Already booked ──► Inventory booking_gst_update ──► booking update API ──► refund delta
```

#### 3. Scale
> “~15% bookings with extra guests, ~50K/day. Wrong tax = wrong guest money + commission drift. Pricing SLA band ~200–250ms; HCP add &lt;2ms.”

#### 4. Bottleneck
> “Isolation `tax(base+extra)−tax(base)` fails at GST slab boundaries → negative/wrong HCP tax. Especially visible validating new 5% paths.”

#### 5. Fix
> “Boundary detector → **Direct** `extra × rate`; never emit negative; additive protobuf for 12 consumers; 500+ scenarios; coverage 68%→94.5%.”

#### 6. SQL vs NoSQL
> “Money path stayed on existing relational/pricing stores + cache-aside. No new NoSQL for tax SoT — correctness & audit over flexible docs.”

#### 7. Trade-off (VP gold)
> “Spent: temporary Goku date-gated override + multi-track lead work. Protected: legal new quotes + fair refunds + correct HCP. Refused: ‘search green only’ or ship known-wrong extra-guest tax. Later removed override (#1153).”

#### 8. Results
> “HCP error 3.2%→0%; &lt;2ms; support −28%; Day-0 forward + refund paths landed.”

---

### Script C — Paytm refund P0 (fintech VP favorite)

#### 1. Context
> “UPI refunds — trust-critical money. Peak evening load. I was on-call senior in the incident.”

#### 2. HLD (short)
```text
Refund API / jobs ──► Kafka ──► consumers ──► MySQL (HikariCP) + gateway/CBS
                         │
                      retries / lag
```

#### 3. Bottleneck cascade
> “Gateway slow → DB connections held 200ms→5–8s → pool 60 saturated → timeouts → Kafka retries → thundering herd → more pool pressure. ~3% fail, ~₹2cr stuck.”

#### 4. What **you** did
> “Correlated logs/metrics against ‘network blame’; redirected team to pool+lock+retry; tuned consumer retry, temporary pool bump, circuit breakers; alerts for pool saturation; identity-level idempotency discipline.”

#### 5. Trade-offs
> “Short-term pool increase vs root-cause-only purity — stabilize first, then structural fix. At-least-once Kafka + idempotent business logic over pretending exactly-once.”

#### 6. Consistency / security note (banking VP)
> “Refund SoT in DB; no double-pay via unique business keys; commits after success; auditability; least privilege on prod changes during incident.”

---

### Block 1 cheat — SQL vs NoSQL (generic answer)

> “**SQL** when multi-row transactions, strong invariants, money/ledger/config integrity (orders, refunds, tax, addon mappings).  
> **NoSQL/document** when flexible catalog attributes, high-scale simple key access, or event logs.  
> **Cache (Aerospike/Redis)** in front of SQL for read-heavy search — with correct failure mode (don’t poison on GET error).  
> I choose from **consistency needs + query patterns + team operability**, not fashion.”

---

## BLOCK 2 — Engineering Leadership & Scalability (8–10 min)

### Themes to hit
HA · DR · consistency · peak concurrency · fault tolerance · (light) security

### Q2.1 How do you design for high availability?

> “N+1 services behind LB; health probes; timeouts/retries with jitter; circuit breakers; degrade non-critical paths; multi-AZ; data: primary + replicas; Kafka replication/ISR. Money paths: idempotency + replay-safe consumers. I’ve run this on Paytm refunds and DR drills, and on MMT with staged prodpp→prod + dashboards as definition of done.”

### Q2.2 Disaster recovery — what have you done?

> “Paytm **DR drill** Mumbai→Noida: verified MySQL replication lag gates, Kafka MirrorMaker, Spring profile/host cutover, Aerospike endpoints, rollback scripts ~15 min. Principle: **don’t cut over until lag≈0**; validate read/write; practice rollback before you need it.”

### Q2.3 Consistency in fintech / money systems?

> “Strong consistency on ledger/refund/order state in SQL. Async fan-out via Kafka = eventual for projections/cache — acceptable if SoT is clear and rebuildable. Prefer **at-least-once + idempotent** over fragile distributed transactions across 5 services.”

### Q2.4 Scenario: high concurrency during peak / sale — structure your answer (90s)

**Use this skeleton every time:**

1. **Admit / protect** — rate limit, queue, feature flags  
2. **Scale reads** — cache, replicas, CDN  
3. **Protect writes** — conditional stock update, short txns, pool alignment (threads ≤ DB capacity)  
4. **Async** — non-critical work to Kafka  
5. **Backpressure** — CallerRuns / reject / shed  
6. **Observe** — lag, pool, p99, business success rate  
7. **Kill switch** — disable personalization/promos if checkout at risk  

**Speak:**
> “Peak is a **capacity and contention** problem. I’d pre-warm caches, shard or partition hot keys, bound ingress to what DB pools can hold — lesson from refund P0: more threads without more connections makes it worse. Checkout/payment path sync and hardened; recommendations async. Load test the money path; game-day the failure. Metrics: payment success, inventory oversell=0, p99, pool wait, Kafka lag.”

**MMT flavor if they want hotels:**
> “Search 10x: scale Goku/PE, protect Aerospike/DB, degrade enrichment. Never let cache GET errors async-write poison.”

### Q2.5 Latency reduction — examples

| Example | Move |
|---------|------|
| DO PaidEcLc | Parallel Enigma + Ancillary |
| HCP | Reuse PE→Goku pipelines (&lt;2ms add) |
| Refunds | Don’t hold DB conn across slow gateway; async stages |
| Search | Cache-aside + correct miss/error handling |

### Q2.6 How do you lead scale work across teams?

> “Shared SLO + one dashboard; explicit ownership of bottlenecks; sequenced deploys (contract→SoT→consumers); dry-run before mass money updates (GST Inventory). I led GST tracks without authoring every PR — ship gate was mine.”

### Q2.7 Security / fault tolerance (banking-flavored, keep short)

> “Least privilege, no secrets in logs, idempotent webhooks, audit trails on money updates, fail closed on legacy clients that can corrupt config (CUG advanced settings block). Chaos: timeouts default; bulkheads between pools.”

---

## BLOCK 3 — Culture / Behavioral (5–7 min)

### Q3.1 Mentor juniors?

> “Pair on RCA checklists; written wikis (GST Goku override + Inventory refund for team learning); PR review focused on blast radius first; insist IT + Grafana before ‘done’ on greenfield. Goal: they can ship the next addon category without me.”

### Q3.2 Cross-functional stakeholders?

> “GST: product/compliance wanted Day-0 green on search. I reframed already-booked 12% as guest fairness — Inventory refund same bar. Used business language (wrong money at 50K/day), proposed dry-run + temporary bridge to keep date.”

### Q3.3 Disagree with PM / peer?

> “Hour CP: shipping UI without engine field = refund liability. I refused display≠enforcement. CUG: blocked legacy app active edits on advanced hotels — fail closed vs silent wipe. Disagree on **risk to money/trust**, offer alternative that still ships.”

### Q3.4 Manage technical debt?

> “Prioritize debt that causes wrong money, outages, or multiplies every feature. Example: remove GST temporary override after stable (#1153). Sibling reconcile clarity &gt; cosmetic refactors. Make debt visible in sprint with a metric (error rate, lag, ticket class).”

### Q3.5 Production incident leadership?

> “Refund P0: incident command, hypothesis board, redirect from vendor-blame using data, stabilize then fix, exec updates with what changed / what proved / next test. Postmortem → alerts + pool/retry design changes.”

### Q3.6 Drive delivery under ambiguity?

> “TrueFlexi three phases — ship SR first to de-risk contracts, then SoR, then DO. Smaller blast radius than big-bang.”

### Q3.7 Why leave / tenure? (if VP asks)
> Use [`CTO_Round_Prep.md`](./CTO_Round_Prep.md) §1b — **domain + ownership** first; stack second; not fleeing MMT.

---

## 25-minute run-of-show (memorize)

| Time | You |
|------|-----|
| 0:00 | “Happy to deep-dive **[TrueFlexi / HCP / Refund P0]** — ownership + scale first.” |
| 0:00–12:00 | Block 1 script; pause for their interrupts (good — means drill) |
| 12:00–20:00 | HA/DR + **peak concurrency** skeleton; one latency example |
| 20:00–27:00 | Mentorship + disagree + debt + incident (pick 3) |
| 27:00–30:00 | Your questions (below) |

---

## Questions to ask the VP (pick 2–3)

1. Which systems are closest to the reliability/consistency bar of payments — checkout, inventory, or settlements?  
2. How does the org run incident command and error budgets today?  
3. What does great look like in the first 90 days for this role?  
4. Biggest scale bet for the next year (sale events, new category, platform rewrite)?  
5. How do engineering and product negotiate “date vs correctness” when money is involved?

---

## Do / Don’t (VP-specific)

| Do | Don’t |
|----|--------|
| One primary deep-dive, crisp ownership | Tour entire resume |
| Numbers you can defend | Invent peak QPS |
| Trade-off = spent vs protected | “I never compromise” |
| Fintech instincts (idempotency, DR, short txns) | Only feature laundry list |
| Invite drill: “where should I go deeper?” | Speak 12 min uninterrupted monologue |

---

## One-page cue card (print)

**Deep dive:** TrueFlexi HLD · parallel DO · MySQL txn · 143 IT · EqualFold  
**Alt:** HCP dual-method · GST lead≠author · 50K/day · trade-off override  
**Alt:** P0 pool+Kafka retry · ₹2cr · I redirected RCA  
**Scale:** peak skeleton — admit, cache, protect write, async, backpressure, kill switch  
**DR:** Mumbai→Noida lag gate + rollback  
**Culture:** mentor via wiki/review · disagree on money risk · debt = wrong money first · incident command  
**Ask:** 90-day bar · hardest reliability domain · date vs correctness  

---

*VP round — architecture ownership + scale leadership + culture — practice aloud with a timer*
