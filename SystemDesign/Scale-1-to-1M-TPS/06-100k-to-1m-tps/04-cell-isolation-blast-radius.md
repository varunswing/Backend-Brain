# Blast Radius + Cell Isolation

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Blast radius = % of users/traffic hurt by one failure. Cells/partitions cap that %. Control plane is separate so config bugs don’t take all cells.

---

## Why this band

At 1M RPS, 1% impact is still huge absolute error volume. Shrink blast or die by a thousand cuts.

---

## How (interview-ready)

1. Cell routing deterministic.
2. Deployments staged cell-by-cell.
3. Separate control plane rate limits.
4. Game-day: kill one cell, verify containment.

---

## How vs related ideas

| Failure | Bad design | Good design |
|---------|------------|-------------|
| Bad deploy | 100% |
| One cell | |

---

## Pitfalls

- Shared DB across ‘cells’
- Global feature flag without progressive rollout
- Coupled control plane

---

## 30-sec answer

> I design cells so a bad deploy or outage hits one slice of users. At million RPS, blast radius is the product metric — not just raw availability.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
