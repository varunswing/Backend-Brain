# Traffic Pool Isolation (User vs Bot vs Async)

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Separate deployments/pools/queues for user traffic vs bots vs async refresh so scrapers and batch writers can’t starve interactive latency.

---

## Why this band

At multi-k RPS a large fraction may be bots/pullers. One HPA fleet = bots win, humans lose.

---

## How (interview-ready)

1. Distinct LB pools or clusters (`search`, `search_bot`, `search_async`).
2. Separate HPA + rate limits.
3. Shared cache OK; isolate CPU/concurrency.
4. Fairness policies at gateway.

---

## How vs related ideas

| | One fleet | Isolated pools |
|--|-----------|----------------|
| Bot spike | Users p99↑ | Bots contained |

---

## Pitfalls

- Same DB pool still shared without limits
- Isolation without separate SLOs/alerts
- Over-fragmenting into 20 micro-fleets

---

## 30-sec answer

> I isolate user search from bot and async pools with separate HPA and limits. Shared Aerospike is fine — shared CPU fairness is not.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
