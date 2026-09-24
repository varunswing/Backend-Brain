# Band 6: 100K → 1M TPS/RPS

← [10K → 100K](../05-10k-to-100k-tps/) | [Index](../README.md)

**Traffic feel:** Global hyperscale — mega social actions, ad auctions, DNS-like infra, large chat/presence, top-tier edge APIs. Problem shifts from “add Redis” to **geography, coordination tax, specialized hardware/software, and organizational capacity**.

---

## 1. Snapshot

```text
Users worldwide
    → Anycast / Geo-DNS
        → Edge PoPs (CDN, WASM/workers, regional cache)
            → Regional cells (stateless + local data)
                → Async global replication / CRDTs / log shipping
                → Specialized stores (KV, wide-column, time-series, graph)
                → Control plane separate from data plane
```

| Plane | Role |
|-------|------|
| **Edge** | Terminate TLS, cache, authz fragments, bot filter — absorb majority GETs |
| **Regional cell** | Mostly autonomous; failure stays local |
| **Global truth** | Narrow: identity, payments, sparse strongly consistent entities |
| **Derived data** | Feeds, counters, recommendations — eventual, repairable |
| **Control plane** | Config, schema, routing — carefully rate-limited |

---

## 2. What happens

| Symptom | Reality |
|---------|---------|
| Cross-region p99 dominates | Speed of light ~65 ms US–EU one-way; sync tx across oceans hurts |
| “1M RPS” on one cluster | Usually **sum of many regions/cells**, not one MySQL |
| Consensus (Raft/Paxos) hot | Too much in the synchronous path |
| Config push = outage | Control plane coupled to data plane |
| Multiplier effects | 1 user action → 100 fan-out writes (friends, notifications) |

**Important interview honesty:** Few product APIs do **1M synchronous ACID TPS on one logical DB**. They do **1M RPS of mostly cacheable/edge/regional work**, with a much smaller strongly consistent core.

---

## 3. Why

| Constraint | Implication |
|------------|-------------|
| **Speed of light** | Pin users to region; async replicate; accept stale |
| **Coordination** | Every cross-node sync decision costs throughput |
| **Fan-out** | Design write amplification explicitly (push vs pull) |
| **Failure math** | At 1M RPS, 0.01% error = 100 rps errors — still huge |
| **Ops entropy** | Humans can’t ticket-scale; need automation + SLOs |

```text
In_flight ≈ RPS × latency
1e6 RPS × 0.005 s = 5,000 concurrent (edge)
1e6 RPS × 0.100 s = 100,000 concurrent (if every hit origin) → impossible without cache/edge
```

---

## 4. What you change

### Architecture moves

| Move | Detail |
|------|--------|
| **Multi-region active-active or active-passive** | Trade consistency for availability; conflict rules |
| **Cell-based isolation** | Blast radius = one cell |
| **Edge-heavy** | Aim 90–99%+ handled without origin |
| **Specialized stores** | TSDB for metrics, wide-column for sparse, KV for sessions |
| **Pull > push** when fan-out huge | Feed pull / fan-out-on-read vs write storm |
| **Approximate aggregates** | Count-min, HyperLogLog, pre-agg |
| **Formal capacity + error budgets** | SRE: burn rate → freeze features |

### Consistency playbook

| Data | Model |
|------|-------|
| Payments / inventory confirm | Region-local strong; careful cross-region |
| Profile / posts | Causal or eventual + merge |
| Presence / typing | Ephemeral, lossy OK |
| Counters | Approximate |

### What not to claim

- “We’ll 2PC across 5 regions at 1M TPS”  
- “One Kafka cluster globally for all ordered events”  
- “Strong consistency for every read”

---

## 5. Decomposition of “1M RPS”

Always break the number:

| Slice | Example share |
|-------|----------------|
| Static / CDN | 40–70% |
| Edge cached API | 20–40% |
| Regional origin | 5–15% |
| Strong write SoT | 0.1–2% |

**Interview move:** “1M RPS total → maybe 5–20K RPS at core write/read miss. Design the core for that, the edge for the rest.”

---

## 6. Interview script

### 30 sec

> “At 100K–1M RPS I design for **geography and blast radius**: edge and regional cells absorb most traffic; a **small strongly consistent core** handles money/identity; everything else is **async, approximate, or eventual**. I budget in-flight connections with RPS×latency, and I never put cross-region consensus on the hot path.”

### 3–5 min

1. Clarify what the 1M counts (ingress vs writes).  
2. Draw edge → cell → core.  
3. Light-latency argument.  
4. Fan-out push vs pull.  
5. Failure: cell down, traffic shift, degraded read.  
6. SLO / load shed.  
7. Tie back: early bands still apply **inside** a cell.

---

## 7. Past the ladder

- Custom transports, kernel bypass (rare interview depth)  
- Hardware rate limiters / DDoS scrubbing  
- Multi-cloud / regulatory data residency  
- Platform org: capacity reviews, progressive delivery at fleet scale  

---

## Topics to learn (this band)

Each file: **What** · **Why this band** · **How** · compare · pitfalls · 30-sec answer.

- [Multi-region](./01-multi-region.md)
- [Edge computing](./02-edge-computing.md)
- [Geo-DNS + anycast](./03-geo-dns-anycast.md)
- [Blast radius / cells](./04-cell-isolation-blast-radius.md)
- [Fan-out push vs pull](./05-fanout-push-vs-pull.md)
- [Specialized datastores](./06-specialized-datastores.md)
- [CRDT + approximate](./07-crdt-and-approximate.md)
- [SLOs + error budgets](./08-slo-error-budgets.md)
- [Decompose 1M RPS](./09-decomposing-1m-rps.md)

## Checklist

- [ ] 1M decomposed (edge vs origin vs strong write)  
- [ ] Region pin + conflict policy stated  
- [ ] Cell blast radius  
- [ ] Fan-out strategy explicit  
- [ ] In-flight / connection math  
- [ ] Error budget + shed non-critical first  
- [ ] Honest about what cannot be strongly consistent globally
