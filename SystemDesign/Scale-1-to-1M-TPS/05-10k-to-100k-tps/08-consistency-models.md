# Consistency Models (Strong / Eventual / Causal)

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Strong: read sees latest committed write (costly to coordinate). Eventual: replicas converge later. Causal: respects cause-effect order without full global order.

---

## Why this band

You cannot afford strong consistency on every read at 10K–100K across shards/regions. Pick per data type.

---

## How (interview-ready)

1. Money/inventory confirm → strong, narrow path.
2. Search/feed → eventual + TTL/events.
3. Say the model in the design explicitly.
4. Repair tools (reconciliation) for eventual systems.

---

## How vs related ideas

| Model | Cost | Use |
|-------|------|-----|
| Strong | High | Pay, book confirm |
| Eventual | Low | Search cache |
| Causal | Medium | Comments/social |

---

## Pitfalls

- ‘Eventual’ without user-visible explanation
- Strong cross-shard by default
- No repair/recon for drift

---

## 30-sec answer

> I use strong consistency only on narrow money paths and eventual for search/feed. At 10K+ RPS, pretending everything is strongly consistent is how designs fail.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
