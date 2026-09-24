# SLOs + Error Budgets

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

SLO = target reliability/latency. Error budget = allowed unreliability. Burn budget fast → freeze features / focus reliability.

---

## Why this band

At this scale absolute errors are huge; intuition fails. Formal budgets align product vs reliability.

---

## How (interview-ready)

1. Define SLI (success %, p99).
2. SLO e.g. 99.9% monthly.
3. Alert on burn rate, not raw spikes only.
4. Policy: budget exhausted → reliability work prioritized.

---

## How vs related ideas

| | Uptime folklore | SLO + budget |
|--|-----------------|--------------|
| Decision | Tribal | Numeric |
| Tradeoff | Hidden | Explicit |

---

## Pitfalls

- 100% SLO fantasy
- Alerting only on tickets
- No link from budget to roadmap

---

## 30-sec answer

> SLOs and error budgets turn reliability into a numeric tradeoff. At 100K–1M RPS I alert on budget burn and freeze features when reliability is at risk.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
