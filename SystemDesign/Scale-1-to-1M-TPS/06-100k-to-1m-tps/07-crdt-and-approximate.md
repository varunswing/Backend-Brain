# CRDTs + Approximate Structures

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

CRDTs merge concurrent updates without coordination. Approximate structures (HLL, count-min) give statistical answers with tiny memory.

---

## Why this band

Exact global counts/sets with strong sync can’t keep up. Approximation and mergeable types remove coordination.

---

## How (interview-ready)

1. Use CRDTs for presence, counters, collaborative edits where OK.
2. HLL for unique counts; CMS for heavy hitters.
3. State error bounds to product.
4. Don’t approximate money balances.

---

## How vs related ideas

| | Exact global | Approx/CRDT |
|--|--------------|-------------|
| Coordination | High | Low |
| Error | Zero | Bounded |

---

## Pitfalls

- Approx for ledger balances
- CRDT without understanding merge semantics
- Hiding error from product

---

## 30-sec answer

> I use CRDTs and HyperLogLog-style approx where mergeability beats exact global sync — never for money. Error bounds are part of the API contract.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
