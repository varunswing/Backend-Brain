# Decomposing ‘1M RPS’

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Always split a huge RPS number into CDN/edge vs regional origin vs strong write core. Designing one OLTP for 1M is usually the wrong problem.

---

## Why this band

Interviewers test whether you naively ‘shard MySQL to 1M’ or think in layers.

---

## How (interview-ready)

Example split:
| Layer | Share |
|-------|-------|
| CDN/static | 40–70% |
| Edge cached API | 20–40% |
| Regional origin | 5–15% |
| Strong write SoT | 0.1–2% |

1. Ask what the 1M counts.
2. Design edge for the bulk.
3. Design core for the residual.
4. Show in-flight = RPS × latency at each layer.

---

## How vs related ideas

| Mistake | Better |
|---------|--------|
| One DB @ 1M | Layered RPS |
| Ignore writes≪reads | Separate write TPS |

---

## Pitfalls

- Accepting 1M as uniform OLTP
- Forgetting fan-out multipliers
- No latency in capacity math

---

## 30-sec answer

> I decompose 1M RPS into edge vs origin vs strong writes. Usually the core sees a few percent — that’s what I size OLTP and consensus for.

## Related

- ./02-edge-computing.md
- ./01-multi-region.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
