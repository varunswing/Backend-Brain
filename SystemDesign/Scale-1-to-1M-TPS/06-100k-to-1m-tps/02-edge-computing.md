# Edge Computing / Edge Workers

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Run lightweight logic at CDN PoPs (auth gate, A/B, bot score, edge cache key) so most requests never hit origin regions.

---

## Why this band

At 100K–1M, origin fleets can’t terminate everything. Edge cuts RTT and origin load.

---

## How (interview-ready)

1. Push cacheable + filter logic to edge.
2. Keep heavy business logic in region.
3. Short-lived edge state only.
4. Observe edge vs origin hit ratios.

---

## How vs related ideas

| | Regional only | Edge + region |
|--|---------------|---------------|
| RTT | High for far users | Lower |
| Origin RPS | High | Reduced |

---

## Pitfalls

- Putting full monolith at edge
- Sensitive secrets scattered unsafely
- No purge/version story

---

## 30-sec answer

> Edge workers absorb TLS, bot filters, and cacheable GETs near users. Origin keeps heavy domain logic — that’s how you approach million-scale ingress.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
