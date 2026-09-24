# EXPLAIN + Slow Query Log

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

EXPLAIN shows the planner’s chosen access path (seq scan vs index, join type, estimated rows). Slow query log records queries over a time threshold.

---

## Why this band

This band’s main killer is **bad SQL**, not lack of servers. You need a measurement loop.

---

## How (interview-ready)

1. Enable slow log (e.g. >200–500ms).
2. Take top offenders → `EXPLAIN ANALYZE`.
3. Fix: index, rewrite, pagination, avoid `SELECT *`.
4. Re-measure p95 under load.

---

## How vs related ideas

| Tool | Tells you |
|------|-----------|
| Slow log | What hurts in prod |
| EXPLAIN | Why the planner chose it |
| Metrics | DB CPU / IOPS correlation |

---

## Pitfalls

- Tuning without EXPLAIN
- Indexing blindly from guesses
- Ignoring ordinality of composite keys

---

## 30-sec answer

> At 10–100 RPS I live in the slow query log and EXPLAIN. Most scale pain here is query shape — indexes and rewrites beat buying hardware.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
