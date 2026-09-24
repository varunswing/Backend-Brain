# Short Transactions + Row Locks

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A transaction groups writes atomically. Row locks (`FOR UPDATE`) serialize access to hot rows until commit/rollback.

---

## Why this band

Long transactions = long lock time = other requests pile up → timeouts at modest RPS.

---

## How (interview-ready)

1. Keep transactions tiny: read what you need, write, commit.
2. Never call external HTTP/RPC inside an open transaction.
3. Use `FOR UPDATE` only for true critical sections (inventory decrement).
4. Prefer optimistic locking (`version` column) when contention is rare.

---

## How vs related ideas

| | Pessimistic lock | Optimistic version |
|--|-----------------|--------------------|
| Conflict | Wait | Retry on version mismatch |
| Best | Hot scarce resource | Low contention updates |

---

## Pitfalls

- Big transaction wrapping whole HTTP handler
- Lock order deadlocks (A then B vs B then A)
- Holding locks during user think-time

---

## 30-sec answer

> I keep DB transactions short and never hold row locks across network calls. Lock time, not RPS, is what melts 10–100 systems.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
