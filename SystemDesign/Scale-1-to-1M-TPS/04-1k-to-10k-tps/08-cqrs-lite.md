# CQRS-lite (Read Model ≠ Write Model)

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

CQRS = Command Query Responsibility Segregation: writes go to an optimized write model; reads use a different model (cache, search index). ‘Lite’ = not full event sourcing — just split paths.

---

## Why this band

Search wants denormalized fat blobs; booking wants normalized transactional rows. One schema optimizing both fights you at this scale.

---

## How (interview-ready)

1. Write path: Inventory/Phoenix/MySQL master.
2. Read path: Goku + Aerospike projections.
3. Sync via events/CDC (async).
4. Accept brief staleness on query side.

---

## How vs related ideas

| | Single model | CQRS-lite |
|--|--------------|-----------|
| Schema | Compromise | Specialized |
| Consistency | Easier strong | Eventual reads |

---

## Pitfalls

- Dual writes without a single SoT story
- Calling it CQRS but still joining 20 tables on read
- Full event sourcing when lite split enough

---

## 30-sec answer

> CQRS-lite means booking writes to MySQL and search reads Aerospike projections fed by events. I don’t force one schema to be both OLTP and mega-QPS read.

## Related

- ../05-10k-to-100k-tps/02-cqrs.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
