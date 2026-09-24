# Redis for Sessions (intro)

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Redis is an in-memory data store. For sessions, you store session_id → user session blob with TTL so any app instance can serve the user.

---

## Why this band

Second app instance for deploys needs **shared session** (or JWT). Sticky sessions alone are fragile.

---

## How (interview-ready)

1. Run Redis (single node OK here).
2. On login: write session key with TTL.
3. App becomes stateless w.r.t. session memory.
4. Don’t put huge objects; keep session small.

---

## How vs related ideas

| | Server memory session | Redis session | JWT |
|--|----------------------|---------------|-----|
| Multi-instance | Needs sticky | Shared | Stateless |
| Revoke | Easy | Easy | Harder |

---

## Pitfalls

- Redis as only SoT for money
- No TTL → memory leak
- Giant sessions

---

## 30-sec answer

> I use Redis as a small shared session store so I can run 2 app instances. It’s not my system of record — just shared fast state.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
