# CQRS — What & How

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Full CQRS: commands mutate write store; queries hit denormalized read stores updated asynchronously. Often paired with events.

---

## Why this band

At 10K+ you may have many read shapes (search, feed, admin, analytics). One OLTP DB can’t serve all shapes at latency/QPS targets.

---

## How (interview-ready)

```text
Command API → Write DB
     → Events → Consumers → Read DB / ES / KV
Query API → Read stores only
```
1. Define write model (normalized, constrained).
2. Define read models per use case.
3. Consumers idempotent; version projections.
4. State consistency expectations per UI.

---

## How vs related ideas

| | CQRS-lite (band 4) | CQRS (band 5) |
|--|--------------------|---------------|
| Read stores | Cache mainly | Dedicated DBs/indexes |
| Complexity | Lower | Higher ops |

---

## Pitfalls

- Two SoTs that diverge with no repair
- Sync dual-write without outbox
- Eventual UI without messaging staleness

---

## 30-sec answer

> CQRS separates write DB from read models rebuilt by events. At 10K–100K I do it when read shapes and QPS diverge — search/feed never query the write schema directly.

## Related

- ../04-1k-to-10k-tps/08-cqrs-lite.md
- ./09-event-sourcing-selective.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
