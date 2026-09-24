# Cell-Based Architecture

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A cell is a self-contained slice of the stack (LB→app→data) serving a subset of users/tenants. Failure of one cell doesn’t take the whole site.

---

## Why this band

Blast radius at this scale must be bounded. One bad deploy or hot shard shouldn’t be 100% impact.

---

## How (interview-ready)

1. Partition users/tenants into cells.
2. Each cell has own compute + datastore slice.
3. Control plane assigns membership.
4. Deploy/canary per cell.
5. No noisy cross-cell sync on hot path.

---

## How vs related ideas

| | One mega cluster | Cells |
|--|------------------|-------|
| Outage | Global |
| Impact | One cell |

---

## Pitfalls

- Cells that still share one DB
- Tiny cells with huge ops overhead
- Cross-cell chatty calls

---

## 30-sec answer

> Cells are mini-stacks per user subset so an outage or bad deploy is blasted to one cell, not the world. Data and compute both partition.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
