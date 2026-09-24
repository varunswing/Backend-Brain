# Connection Pooling

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A pool keeps reusable DB connections so each request doesn’t pay full TCP/auth handshake, and caps max concurrent DB sessions.

---

## Why this band

Each slow query holds a connection. Default huge pools or no pool → primary `max_connections` exhaustion when you add app instances later.

---

## How (interview-ready)

1. Use pooler in app (Hikari, SQLAlchemy pool) and/or PgBouncer/ProxySQL.
2. Size ≈ small multiple of cores — not ‘500 because traffic’.
3. Watch pool wait time metric.
4. Timeouts: connection acquire timeout + query timeout.

---

## How vs related ideas

| | New conn per request | Pool |
|--|----------------------|------|
| Latency | High | Lower |
| DB sessions | Unbounded risk | Capped |

---

## Pitfalls

- pool_size × pods > max_connections
- Holding connections across external HTTP calls
- Unlimited queue of waiters

---

## 30-sec answer

> Connection pools reuse and cap DB sessions. I size them from concurrency math and never hold a connection while calling slow HTTP.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
