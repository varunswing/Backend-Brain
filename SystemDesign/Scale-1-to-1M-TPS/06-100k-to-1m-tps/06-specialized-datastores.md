# Specialized Datastores

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Use the right engine: KV for sessions, wide-column for sparse, TSDB for metrics, search for text, blob/object for media, OLTP for money. Polyglot persistence.

---

## Why this band

One DB type cannot optimize all access patterns at this scale. Specialization is survival.

---

## How (interview-ready)

1. Map workload → store (QPS, query shape, retention).
2. Keep clear SoT per entity.
3. Sync via events; avoid dual-write chaos.
4. Ops expertise per store — don’t collect pets.

---

## How vs related ideas

| Workload | Store examples |
|----------|----------------|
| Session | Redis |
| Catalog cache | Aerospike |
| Search | ES/OpenSearch |
| Metrics | Prometheus/TSDB |
| Money | SQL |

---

## Pitfalls

- ‘One Postgres for everything’ at 1M
- Five stores without ownership
- Search as SoT

---

## 30-sec answer

> At 100K–1M I pick specialized stores per access pattern and keep a clear system of record. Polyglot is deliberate, not accidental sprawl.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
