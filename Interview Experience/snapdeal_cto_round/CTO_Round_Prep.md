# Snapdeal CTO Round — Question Bank & Model Answers

**Role signal from JD:** Backend engineer on **high-scale e-commerce** — Java/Spring microservices, HA, observability (Grafana/Loki/Pinpoint), tests + reviews, perf/scalability, prod ownership, CI/CD with DevOps.

**CTO round ≠ coding drill.** Expect: judgment, ownership, architecture trade-offs, scale stories, how you debug prod, how you lead without title, culture fit, and whether you can grow into senior/staff impact on Snapdeal’s core commerce stack.

**Your story map (use these — don’t invent):**

| JD theme | Best story |
|----------|------------|
| Scale + money correctness | **HCP** + GST Goku/Inventory program (lead) |
| Invent platform / simplify clients | **TrueFlexi** (Ancillary + DO) |
| Multi-service contracts / quality bar | **Grace/Hour CP** |
| E-commerce promotions at scale | **LastMinutesRate**, **CUG Additional Discount** |
| Java/Spring + Kafka + HA + DR | **Paytm:** UPI refunds, 3% loss P0, ServiceMigration, DRDrill, Job orchestration |
| Observability / Grafana / IT | Ancillary/DO Grafana + Jenkins IT + coverage (TrueFlexi ops) |
| Ownership across teams | GST program lead; TrueFlexi 3 phases; CUG 6-repo coord |

---

## 0. How to open (60–90s — memorize)

> “I’m a backend engineer who’s spent recent years on **MakeMyTrip INGO Hotels** (Go microservices, pricing, booking, extranet) and earlier **Paytm Bank UPI/refunds** (Java/Spring, Kafka, MySQL, K8s).  
>  
> At MMT I care about **guest money and host productivity** — examples: led India’s hotel GST change across **forward pricing (Goku)** and **already-booked refunds (Inventory)** while personally fixing **extra-guest HCP tax** correctness under deadline; and built **TrueFlexi EC/LC** as a greenfield addon platform plus one DO API for extranet.  
>  
> At Paytm I lived **high-availability money movement** — UPI refunds at lakhs/day, P0 incident ownership, multi-bank migration, DR drills.  
>  
> I’m excited about Snapdeal because the JD is exactly that intersection: **scalable commerce backends**, **observability**, and **prod ownership**. Happy to go deep on architecture, incidents, or how I’d ramp on Java/Spring day-to-day.”

**Bridge Java gap honestly if asked:**
> “Day-to-day at MMT is largely **Go + gRPC**; Paytm was **Java/Spring Boot + Kafka + MySQL**. OOP, concurrency, REST/JPA patterns, and Spring profiles/CI are familiar from Paytm. I’m comfortable ramping hard on Spring Boot 17 / JPA idioms — the systems problems transfer.”

---

## 1. Role & motivation

### Q1. Walk me through your experience relevant to this JD.

**Answer structure:** Paytm (Java/HA/Kafka) → MMT (scale commerce/pricing) → why Snapdeal.

**Say:**
> “Three layers match your JD.  
> **1) Java backend & messaging:** Paytm refund/UPI services — Spring Boot, MySQL, Kafka at-least-once, concurrency, DR.  
> **2) High-scale consumer commerce:** MMT hotels search/pricing/booking — tens of thousands of properties, ~50K/day extra-guest bookings on HCP paths, multi-service rollouts.  
> **3) Observability & quality:** Grafana dashboards for new services, Jenkins IT gates, Sonar/coverage, prodpp→prod hardening.  
> I’m not a pure CRUD CRUD engineer — I own money paths, platforms, and incidents end-to-end.”

### Q2. Why Snapdeal? Why this role?

**Say:**
> “Snapdeal is a **core e-commerce** problem set: catalog, pricing, checkout, availability, promotions — same class of blast-radius as hotels/payments. The JD emphasizes **HA, observability, and CI/CD**, which is how I already work. I want a place where backend quality on the money path is non-negotiable, and where I can grow architecture ownership.”

### Q3. What are you looking for in your next role?

**Say:**
> “Ownership of a critical commerce subdomain; mentorship/design reviews; measurable impact on latency, reliability, and conversion; room to deepen Java/Spring at Snapdeal scale while bringing multi-service product delivery habits from MMT.”

### Q4. What’s your biggest weakness relative to this JD?

**Say (honest + mitigation):**
> “Recent primary language at MMT is Go, while JD is Java-first. I’ve shipped production Java/Spring at Paytm, so concurrency, Spring, Kafka, MySQL aren’t new — but I’d spend early weeks deliberately on Snapdeal’s Spring Boot conventions, JPA patterns, and Pinpoint/Loki tooling. Weakness is **stack familiarity depth today**, not **backend systems depth**.”

---

## 1b. Tenure / “why leave so early?” (CTO will probe this)

**Why they ask:** ~1.5 years at MMT looks short. CTO fears job-hopping, unfinished ownership, or running from conflict. You must reframe: **density of impact**, **pull toward Snapdeal fit**, **commitment going forward** — never trash MMT.

### Q4a. You’ve only been at MakeMyTrip ~1.5 years — why leave so early?

**Answer frame (4 beats):** respect → impact already delivered → what you want next → why Snapdeal (not “escape”).

**Say (~60–75s):**
> “I’m not leaving because MMT was a bad experience — I’ve had strong ownership there: GST money-path program lead, HCP correctness under deadline, TrueFlexi addon platform, multi-repo CP/promo work. In ~1.5 years the **scope was senior-shaped**, not ‘just ramping.’  
>  
> I’m looking ahead at the **next 3–5 years of problem set**. Snapdeal’s JD is core e-commerce HA backends on **Java/Spring**, observability, and commerce blast radius at marketplace scale. That’s the stack and domain I want to go deep on long-term. At MMT my day-to-day is heavily Go/hotels supply; I’m choosing a move that matches where I want to specialize, not bouncing for a title bump.  
>  
> On tenure: my earlier Paytm chapter was substantial Java/Kafka/money systems; MMT compounded product/platform delivery. I’m optimizing for **fit and depth**, and if I join Snapdeal I’m joining to **own a domain and stay** — I’m not shopping every year.”

### Q4b. Will you leave Snapdeal in another year?

**Say:**
> “Short stints happen when role and growth trajectory diverge. I’m being deliberate: I want a commerce backend home where Java/Spring HA work is the main craft. My ask of you is clarity on domain ownership and 90-day expectations; my commitment is to earn trust and take multi-year ownership. I don’t switch for incremental comp alone.”

### Q4c. Why not stay and grow at MMT?

**Say:**
> “I can grow at MMT — and I have. The question for me is **where the next steep learning curve is**. I want deeper reps on Java/Spring consumer e-commerce at Snapdeal’s shape of catalog/checkout/promos, plus the observability stack in your JD. Staying only because ‘1.5 years looks short on a resume’ would be optimizing optics over career fit. I’d rather explain a thoughtful move than stretch a mismatch.”

### Q4d. Are you running from something / bad review / conflict?

**Say:**
> “No. Relationships and delivery are solid — I can speak to leads/peers if useful. This is a **pull** toward Snapdeal’s problem space and stack, not a push out of MMT.”

### Q4e. Walk me through your full tenure decisions (Paytm → MMT → now).

**Say:**
> “Paytm: deep **Java/Spring, Kafka, HA, incidents** on refunds/UPI.  
> MMT: **marketplace/hotels commerce** — pricing, booking, platforms, cross-repo ownership.  
> Now: combine both — **Java-first commerce at Snapdeal scale**. Each move added a capability; this one closes the loop toward the JD you posted.”

### Q4f. Why not just switch to a Java team inside MMT?

**They mean:** Leaving company is overkill if stack is the issue.

**Say (~45–60s):**
> “Fair question. An internal move can fix **language**. It doesn’t automatically fix **problem space**.  
>  
> At MMT my strongest impact sits in **hotels supply / pricing / booking** — GST money path, HCP, TrueFlexi, CP, promos. A Java seat elsewhere in MMT might be a different org, different roadmap, or no open role with the same ownership. I looked at growth inside MMT; the realistic near-term path was continuing on the current mesh, not a guaranteed **Java + core commerce** mandate.  
>  
> Snapdeal’s JD is already that package: marketplace-scale backends, HA, observability, Java/Spring as the main craft. So this isn’t ‘I refused to walk to another floor.’ It’s choosing **company + domain + ownership** for the next multi-year chapter — stack is part of fit, not the whole story.”

**If they ask “did you actually try for transfer?”:**
> “I aligned with my chain on growth. Waiting many months hoping a perfect internal Java+commerce seat opens was a bigger career risk than joining a team that already owns that mandate. Happy to be precise without badmouthing internal mobility.”

**Don’t:** “MMT has no Java.”  
**Do:** internal transfer ≠ domain + timing + ownership.

---

### Q4g. What if Snapdeal moves to Go in the future — will you leave again?

**They mean:** Your reason is brittle; you’ll hop when stack fashion changes.

**Say:**
> “No. Stack is **one input**, not the job’s identity.  
>  
> If Snapdeal’s commerce cores moved toward Go later, I’d **stay with the domain** — I’ve already shipped production Go at MMT. I’m here to own **high-blast-radius e-commerce backends** (HA, observability, multi-year ownership), not ‘Java forever or I quit.’  
>  
> Leaving only because a repo’s language ratio changed would be unreasonable — and that’s not what I’m doing now either. I chose Snapdeal’s **product and reliability problems** under your current stack; I’ll adapt if the stack evolves.”

**One-liner:**
> “I’d change languages for Snapdeal’s domain; I wouldn’t change companies every time the runtime changes.”

---

### Q4h. “Only tech-stack change isn’t a reasonable reason to leave.”

**They mean:** Push back — give a grown-up reason. **Agree, then expand.**

**Say (~60s):**
> “I agree — **stack alone is not a good reason** to leave. If that were my only story, you should reject it.  
>  
> Real reasons, in order:  
> **1) Domain** — next years on **core marketplace / e-commerce money paths** (pricing, availability, checkout-adjacent, promos) at Snapdeal’s shape of scale.  
> **2) Ownership** — own a subdomain multi-year: design, on-call, dashboards, mentoring — your JD reads like that.  
> **3) Craft alignment** — Java/Spring + your monitoring stack matches Paytm foundation and reduces ramp friction. That’s **supporting evidence**, not the resignation thesis.  
>  
> Drop stack from the sentence and it still holds: I’m joining for **Snapdeal’s commerce reliability problems and the chance to own them**. I’ll write Java, Go, or both — whatever this codebase needs.”

---

### Hostile chain (Q4a → Q4f → Q4h) — one breath

> “I’m not fleeing MMT or chasing JVM fashion. Dense ownership in 1.5 years there. Choosing Snapdeal for **core e-commerce HA ownership** long-term. Internal Java transfer wouldn’t guarantee that domain. If you later standardize on Go, I stay with the domain — I’ve done Go. Stack is alignment, not the reason.”

---

### Tenure — do / don’t

| Do | Don’t |
|----|--------|
| Credit MMT; list concrete wins in 1.5y | “Culture is toxic” / blame manager |
| Lead with **domain + ownership**; stack second | “Got a better offer” / **stack-only** story |
| “Pull to Snapdeal fit” | Imply MMT has no Java / no mobility |
| Signal multi-year intent even if stack evolves | Joke about hopping when Go appears |
| Tie to JD (e-comm HA, observability, ownership) | “Bored” / “no work” (contradicts STARs) |
| Offer that impact ≠ calendar years | Apologize weakly for 1.5y |

**One-liner if interrupted:**
> “1.5y at MMT was high-density ownership — I’m moving for Snapdeal’s **commerce domain + multi-year ownership**; Java is fit, not the whole reason.”

---

## 2. Microservices, APIs, architecture (JD core)

### Q5. How do you design a scalable microservice?

**Answer framework:** boundaries → API contract → data ownership → async where needed → SLOs → observe → fail gracefully.

**Say:**
> “I start with a **bounded context** and a clear system of record. Example: TrueFlexi — instead of stuffing paid EC/LC into ReservationEngine/Enigma, we created **INGO-Ancillary** as SoR for addons (5-table config, state-diff upsert), and **DO** as the product-shaped API for extranet. That kept booking releases decoupled from addon experiments.  
>  
> Patterns I insist on: additive contracts, idempotent writes where money moves, timeouts/circuit breakers on downstreams, metrics on every external call, and IT contracts before promote.”

### Q6. REST vs gRPC — when which?

**Say:**
> “**REST/JSON** for public/partner/extranet and broad client ecosystems. **gRPC/protobuf** for internal high-QPS service meshes (search, pricing, orchestration) — schema evolution, codegen, streaming if needed. At MMT we used proto-first across many repos; at Paytm Spring REST + Kafka was common. For Snapdeal I’d follow org standards but argue gRPC internal / REST edge unless there’s a strong reason not to.”

### Q7. Tell me about a system you designed end-to-end.

**Use TrueFlexi (Invent) or Paytm refunds.**

**TrueFlexi (~2 min):**
> Situation: no end-to-end Early Check-in / Late Checkout.  
> Task: request → monetize → one host API.  
> Action: Phase1 Special Request (5 repos) → Phase2 Ancillary greenfield → Phase3 DO PaidEcLc + 143 gRPC ITs.  
> Result: Consul-driven addon categories; extranet one API; prodpp bugs fixed before rollout.  
> **CTO signal:** platform invent + client simplify + release gates.

### Q8. How do you ensure backward compatibility across services?

**Say:**
> “Additive protobuf/API fields; deploy **contract → producer of truth → consumers**; feature flags for hotel/capability gates; never break 12 consumers with a hard cut (HCP ExtraPax fields). For admin UIs, omit ≠ clear (Grace/Hour CP EasyJSON lesson).”

### Q9. Microservices vs monolith — your view?

**Say:**
> “Neither religion. Split when **team + data ownership + scale/release velocity** justify cost (network, consistency, ops). I won’t split for fashion. Ancillary earned a service because addon config sharing + dedup needed a SoR; a BFF/DO layer earned itself because extranet shouldn’t speak generic nested addon JSON.”

---

## 3. High availability, scale, performance (e-commerce)

### Q10. How have you built for high availability?

**Paytm DR + UPI:**
> “DR drills Mumbai→Noida: MySQL replication lag gates, Kafka MirrorMaker validation, Spring profile/host cutover, rollback scripts ~15 min. UPI refunds: Kafka acks=all, idempotent producers, manual commits after success, connection-pool/lock incident ownership when we had stuck refunds.”

**MMT angle:**
> “Search/pricing SLA bands (e.g. HCP &lt;2ms add on 200–250ms path); additive rollouts; prodpp before prod.”

### Q11. Describe a performance problem you fixed.

**Options:**
- PriceEngine performance crisis (if comfortable with that STAR)  
- Paytm 3% refund P0 (pool + lock contention)  
- Goku search optimization themes (cache guardrail)

**3% loss pattern (CTO loves):**
> Symptom → metrics → wrong first hypothesis → root cause (pool exhaustion + lock windows + retry stampede) → fix → validation → prevention. Emphasize **customer money stuck** and executive pressure.

### Q12. How do you think about scaling an e-commerce checkout / pricing path?

**Map hotels → Snapdeal:**
> “Read path: cache aggressively with **correct failure modes** (don’t write poison on cache GET errors — Goku Aerospike lesson).  
> Write/money path: strong consistency or outbox; idempotency keys; clear SoT.  
> Fan-out: parallelize independent downstreams (DO Enigma+Ancillary parallel).  
> Backpressure: Kafka lag SLOs, rate limits on retries.  
> Catalog/pricing: isolate experiment velocity from checkout core (same reason Ancillary ≠ RE).”

### Q13. Multithreading / concurrency experience?

**Say:**
> “Paytm: concurrent Kafka consumers, `@Transactional` boundaries, connection pools, avoiding pass-by-value bugs that drop maps under load (CUG PE weekday map panic class of bug).  
> Go side: worker pools, context timeouts, careful shared-state in processors.  
> Principles: share-nothing where possible; bound pools; never hold DB locks across remote calls; make retries idempotent.”

---

## 4. Data: MySQL, NoSQL, messaging

### Q14. MySQL / JPA experience?

**Say:**
> “Paytm refunds/archival: MySQL masters, replicas, GTID migration across banks, indexing for hot refund paths, transactional archival. Comfortable with schema design, EXPLAIN, lock contention. JPA/Hibernate used in Spring services; I care about N+1, transaction boundaries, and not treating ORM as a magic DB.”

### Q15. When NoSQL / cache?

**Say:**
> “Cache (Aerospike/Redis-class) for read-heavy search metadata with explicit miss/error handling. Kafka as durable log for async. Avoid NoSQL as primary ledger for money unless strong reason — money SoT stays relational + clear audit.”

### Q16. Kafka — design a reliable consumer.

**Say:**
> “At-least-once + idempotent business handler; commit after success; poison-pill strategy; lag alerts; separate retry topics/consumer groups so retries don’t starve live traffic (Paytm pattern). Partition key = business identity (order/refund id) for ordering where required.”

### Q17. How would you design order / inventory consistency for Snapdeal?

**High-level CTO answer:**
> “Inventory reservation with short TTL; payment webhook idempotency; outbox for ‘order confirmed’ events; compensating cancel on payment fail; clear state machine (CREATED→PAID→CONFIRMED…). Never dual-write DB+Kafka without outbox/CDC. I’m happy to whiteboard this if useful.”

---

## 5. Observability (Grafana, Loki, Prometheus, Pinpoint) — JD heavy

### Q18. How do you make systems debuggable?

**Say:**
> “Three pillars: **metrics** (RED/USE + business), **logs** with correlation/trace id, **traces** across services.  
> For new Go services we enabled Kafka→OpenTSDB metrics and built Grafana rows: API count/p95, per-downstream call/error/panic, goroutines/GC.  
> Definition of done for a greenfield service includes dashboard + IT + coverage gate — not only merge.  
> On Paytm, Datadog + lag/error/latency alerts with escalation.  
> For Snapdeal I’d lean into **Grafana + Loki + Pinpoint** the same way: every new API and every external call must be visible before prod.”

### Q19. Tell me about a production issue you debugged with observability.

**Pick one:**
1. **DO GET null** — case-sensitive category filter (EqualFold) — logs + DB vs response mismatch  
2. **Paytm refund P0** — dashboards showing pool wait + lag  
3. **Hour CP** — UI showed hour, RE empty — traced GraphQL→EasyJSON→DO→RE  

**Hour CP one-liner:**
> “Display↔enforcement mismatch. Traced optional field death across codecs; fixed every hop; bar was refund correctness not UI demo.”

### Q20. What would you monitor for an e-commerce pricing/checkout service?

**Say:**
> “API: QPS, p50/p95/p99, error rate by endpoint.  
> Downstream: PE/catalog/payment call counts, timeouts, 5xx.  
> Business: cart conversion, payment success, refund success, promo attach rate.  
> Saturation: DB connections, thread pools, Kafka lag, GC/CPU.  
> Alerts on SLO burn, not only host CPU.”

---

## 6. Quality: tests, reviews, CI/CD

### Q21. What’s your testing philosophy?

**Say:**
> “Unit for pure logic (tax/dedup/validators); integration/contract tests for multi-service money paths; performance tests for hot paths.  
> TrueFlexi: **143 grpcurl IT scripts** as extranet contract gate in Jenkins.  
> HCP: scenario matrix + coverage gate (68%→94.5% narrative).  
> ITs don’t replace units — they catch BoolValue/chargeValue/case bugs units miss.”

### Q22. How do you do code reviews?

**Say:**
> “Correctness and blast radius first (money, auth, backwards compat), then readability, then nits. I ask: what’s the failure mode? idempotency? observability? feature flag? For GST Inventory path I reviewed eligibility + dry-run gates even when I wasn’t the author.”

### Q23. CI/CD experience?

**Say:**
> “Jenkins IT images, DockerfileIT, Sonar gates, staged prodpp→prod. Collaborate with DevOps on pipeline definition of done. Prefer: build → unit → IT → quality gate → deploy → smoke. Rollback plan before cutover (Paytm migration/DR).”

---

## 7. Production ownership & incidents

### Q24. Tell me about a Sev-1 / P0 you owned.

**Use Paytm 3% refund crisis** (strongest CTO story for ownership under pressure).

**Structure:** impact (₹ stuck, trust) → stabilize → RCA → fix → prevent → communicate to leadership.

### Q25. Trade-off: ship fast vs high quality?

**Use HCP / GST Highest Standards answer** (you already practiced):

> Spent: temporary Goku override + multi-track lead work.  
> Protected: guest money (forward + refund + HCP).  
> Refused: search-green-only / known-wrong tax.

### Q26. Time you disagreed with a stakeholder?

**Say:**
> “GST Day-0: pressure to mark compliance green on Goku alone. I pushed Inventory booking update→refund as same bar. Framed in business language: already-booked guests still on 12%. We dry-ran then shipped both.”

### Q27. How do you prioritize tech debt?

**Say:**
> “Debt that creates **wrong money**, **outages**, or **slows every feature** first. Example: remove GST temporary override (#1153) after stable period so bridge doesn’t become permanent. Sibling offer reconcile debt &gt; cosmetic refactors.”

---

## 8. Leadership / seniority signals (CTO cares)

### Q28. Have you led without authority?

**Say:**
> “GST program: assigned Goku slab + Inventory refund implementers; I scoped, reviewed, held ship gate; coded HCP myself. TrueFlexi: sequenced three phases across proto/RE/Ancillary/DO. CUG: owned Extranet+Enigma+Goku integration while coordinating PE/Promo-Genie.”

### Q29. How do you mentor / raise the bar?

**Say:**
> “Written wikis (GST Goku override + Inventory refund), PR checklists (contract→domain→BFF), insisting IT+Grafana before ‘done’, sharing RCA docs so bugs don’t repeat (EqualFold, BoolValue, EasyJSON).”

### Q30. Where do you want to be in 2–3 years?

**Say:**
> “Trusted owner of a Snapdeal commerce subdomain (pricing/promos/checkout/inventory-class), driving design reviews, mentoring, and measurable reliability/conversion outcomes — staff-shaped impact with hands still in code.”

---

## 9. E-commerce domain mapping (Snapdeal ↔ you)

### Q31. Do you have e-commerce experience?

**Say:**
> “Yes — **consumer marketplace side** via MMT hotels (search, price, promo, booking, cancel policy, host extranet) and **payments/refunds** via Paytm. Different catalog SKU model than Snapdeal fashion/electronics, but isomorphic problems: inventory truth, price correctness, promo layers, cancellation/refund liability, peak traffic, partner integrations.”

### Q32. Promotions / pricing complexity?

**Use:** CUG Additional Discount (sibling offers), LastMinutesRate (Kafka-driven rush deals), HCP differentials.

### Q33. Catalog / partner onboarding?

**Use:** LastMinutes progressive partner rollout; Content feature flags; Consul addon categories.

---

## 10. System design prompts CTO may throw

Prepare 5–8 min whiteboard for each:

| Prompt | Your approach anchors |
|--------|------------------------|
| Design product search at Snapdeal scale | Cache hierarchy, fan-out, ranking vs filter split, degradation |
| Design checkout | State machine, idempotency, payment webhook, inventory hold |
| Design flash sale / deal | LastMinutes-like event pipeline; oversell guards |
| Design refund service | Paytm UPI refunds; Kafka; status machine; reconciliation |
| Design notification/order events | Outbox + Kafka; consumer lag SLOs |
| Add observability to a brownfield service | Metrics → Grafana → trace ids → alert SLO |

**Always state:** requirements, capacity assumptions, APIs, data model, failure modes, rollout, metrics.

---

## 11. Behavioral bank (short STAR cues)

| Question | Story |
|----------|-------|
| Failure you learned from | Hour field silent drop / DO case sensitivity |
| Most complex project | TrueFlexi 3-phase or GST 3-track |
| Conflict on design | Sibling AD vs mega-row; dual-method HCP vs ship isolation |
| Deliver under deadline | GST 22 Sep |
| Cross-team collaboration | CUG 6 repos; Grace Proto→RE→DO→Enigma |
| Improve a process | IT+Grafana definition of done; dry-run before mass refund |
| Pride | HCP 3.2%→0% wrong tax; TrueFlexi platform |

---

## 12. Questions **you** ask the CTO (pick 4–5)

1. Which Snapdeal domains are most fragile today — pricing, inventory, checkout, or promotions?  
2. What does the on-call / error-budget culture look like?  
3. How are architecture decisions made — ADR, guild, CTO review?  
4. Biggest reliability or cost problem for the next 12 months?  
5. How is Java version / Spring upgrade strategy handled?  
6. What separates strong vs exceptional engineers on this team?  
7. How do backend and DevOps share CI/CD ownership?  
8. Expectations for first 90 days in this role?

---

## 13. Red flags — don’t do these

- Claim Goku `#1094` / Inventory `#3274` as **your commits** (you **led**)  
- “I never compromise on quality” (say **which** compromise you accept)  
- Trash Go vs Java or previous employers  
- **Trash MMT / “I’m leaving early because…” blame** — use pull-to-Snapdeal + impact density (§1b)  
- Pure feature list with no metrics / blast radius  
- Pretend deep Pinpoint experience if you haven’t used it — map to Grafana/Datadog/Jaeger-class tracing and say you’ll ramp  
- 15-minute GST arithmetic with no decision/trade-off  
- Sound like a hopper: “open to anything” / no multi-year intent

---

## 14. 90-day plan (if asked “how would you ramp?”)

| Days | Focus |
|------|--------|
| 1–30 | Service map, local bring-up, on-call shadow, one small prod bugfix, learn Grafana/Loki/Pinpoint dashboards |
| 31–60 | Own a vertical slice (API + DB + tests + dashboard), design review participation, one performance or reliability win |
| 61–90 | Lead a medium project (promo/pricing/checkout adjacency), propose one platform improvement (observability template, IT gate) |

---

## 15. Cheat sheet — JD line → your proof

| JD requirement | Proof line |
|----------------|------------|
| Java / Spring Boot | Paytm refunds, jobs, migration, DR (Spring profiles) |
| Scalable microservices / APIs | Ancillary, DO, Goku, PE, Enigma mesh |
| HA system design | DR drill, Kafka HA, multi-bank migration |
| Grafana / Loki / tracing | Grafana OpenTSDB dashboards; Datadog; correlation RCA |
| Unit / IT / perf tests | 143 gRPC ITs; HCP scenario suite; PE tests |
| Perf / scalability | PriceEngine / Goku / refund P0 tuning |
| Monitor prod / resolve issues | On-call, P0, EqualFold, Hour CP |
| CI/CD + DevOps collab | Jenkins DockerfileIT, Sonar, staged deploy |
| MySQL + NoSQL plus | MySQL GTID/replication; Aerospike cache |
| Multithreading / async | Kafka consumers; concurrent pools |
| Kafka / messaging | UPI refunds; LastMinutes; Ancillary cache topic |
| E-commerce / high-scale consumer | MMT hotels + Paytm payments |

---

## 16. Recommended answer order in the room

1. **Opening pitch** (90s)  
2. If tech deep-dive → **TrueFlexi** or **Paytm refunds**  
3. If leadership/quality → **HCP/GST trade-off**  
4. If incident → **3% loss P0** or **Hour CP / EqualFold**  
5. If e-comm promo → **CUG AD** or **LastMinutes**  
6. Close with questions from §12  

**Primary narrative for Snapdeal CTO:**  
*“I build and own high-blast-radius commerce and money backends — with observability and release bars — and I’ve led multi-team delivery under hard deadlines without lowering the correctness bar.”*

---

*Prep pack — Snapdeal CTO round ↔ JD — use with HCP/TrueFlexi/Paytm STARs*
