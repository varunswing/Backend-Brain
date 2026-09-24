# Event Sourcing (Selective)

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Event sourcing stores state as an append-only event log; current state = fold(events). Selective = use for domains that need audit/replay — not everywhere.

---

## Why this band

When you already do CQRS + Kafka, some domains benefit from replayable history (ledger, booking timeline). Full-ES everywhere is ops heavy.

---

## How (interview-ready)

1. Choose bounded context (payments ledger, inventory mutations).
2. Append events; build projections.
3. Snapshots to speed rebuild.
4. Don’t ES your CMS pages ‘for purity’.

---

## How vs related ideas

| | State CRUD | Event sourced |
|--|------------|---------------|
| Audit/replay | Hard | Natural |
| Complexity | Lower | Higher |

---

## Pitfalls

- ES for all entities
- Huge events without snapshots
- Coupled consumers breaking replay

---

## 30-sec answer

> I use event sourcing selectively where audit and replay matter — ledger-like domains — while other domains stay CRUD + CDC. It’s a tool, not a religion.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
