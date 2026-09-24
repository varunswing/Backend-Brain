# Pagination

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Pagination returns a page of results instead of the full result set — limit/offset or keyset (cursor).

---

## Why this band

Unbounded `SELECT` for ‘list all’ kills I/O and memory when tables grow — often starts hurting in this band.

---

## How (interview-ready)

1. Always `LIMIT` (+ max cap).
2. Prefer **keyset**: `WHERE id > :cursor ORDER BY id LIMIT n` for stable deep pages.
3. Offset OK for small admin UIs only (deep offset = expensive).
4. Return next_cursor to clients.

---

## How vs related ideas

| | Offset | Keyset |
|--|--------|--------|
| Deep page | Slow | Fast |
| Stable under inserts | Can skip/dup | Better |

---

## Pitfalls

- No max page size (client asks limit=1e9)
- ORDER BY non-indexed columns
- Offset for infinite scroll at scale

---

## 30-sec answer

> Pagination caps how much you read. I use LIMIT with a hard max and keyset cursors for feeds — unbounded lists are silent time bombs.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
