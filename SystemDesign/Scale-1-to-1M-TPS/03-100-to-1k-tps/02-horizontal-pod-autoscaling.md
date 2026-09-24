# Horizontal Scaling / HPA

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Horizontal scaling adds/removes instances. HPA automatically targets CPU/RPS/custom metrics.

---

## Why this band

Traffic swings; fixed fleet wastes money or melts at peak. Autoscaling needs headroom and warm time awareness.

---

## How (interview-ready)

1. Metric: CPU, RPS/pod, or latency (careful).
2. min/max replicas; scale-up fast, scale-down slow.
3. Load-test cold start / connection storms.
4. Remember: more pods ≠ help if DB-bound.

---

## How vs related ideas

| | Vertical | Horizontal |
|--|----------|------------|
| Limit | One machine | Fleet + deps |
| Failover | Weak | Better |

---

## Pitfalls

- Scaling on CPU while DB is bottleneck
- max pods × pool > DB max_connections
- Flapping (no stabilization window)

---

## 30-sec answer

> HPA adds pods from CPU/RPS with min/max caps. I always check connection budgets — autoscaling a DB-bound app just multiplies pool pressure.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
