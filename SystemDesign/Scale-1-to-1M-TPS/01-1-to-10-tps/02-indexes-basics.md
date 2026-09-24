# Indexes — Basics

**Band:** 1 → 10 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

An index is a side structure (usually B-tree) that lets the DB find rows by key without scanning the whole table.

---

## Why this band

Low RPS **hides** full scans. When data grows or someone runs a report, p99 explodes. Cheap insurance now.

---

## How (interview-ready)

1. Index columns in `WHERE` / `JOIN` / `ORDER BY` that appear in real queries.
2. Use `EXPLAIN` / `EXPLAIN ANALYZE` once per hot query.
3. Prefer selective indexes; avoid indexing every column.
4. Composite index: leftmost prefix rule (`(user_id, created_at)` helps `user_id` filters).

---

## How vs related ideas

| | Seq scan | Index lookup |
|--|----------|--------------|
| Small table | Often fine | Optional |
| Growing table | Death later | Need early |

---

## Pitfalls

- Over-indexing → slower writes
- Wrong column order in composite index
- Index on low-cardinality alone (`is_active`) without need

---

## 30-sec answer

> An index is a B-tree (usually) so lookups are log-time. At low RPS I still index real filter columns and verify with EXPLAIN — full scans fail later, not now.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
