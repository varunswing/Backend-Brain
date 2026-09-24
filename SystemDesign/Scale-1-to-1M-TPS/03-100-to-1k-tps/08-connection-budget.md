# DB Connection Budget

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Total app connections must fit under DB `max_connections` (minus admin headroom). Budget = pods × pool_size (+ proxy).

---

## Why this band

Classic failure: autoscaling pods × generous pools exhaust primary — looks like ‘DB meltdown’ but it’s connection math.

---

## How (interview-ready)

```text
budget = max_connections - reserve
pods × pool_max ≤ budget
```
1. Use PgBouncer/ProxySQL transaction pooling if needed.
2. Right-size pool; measure wait.
3. Cap HPA max from budget.

---

## How vs related ideas

| Symptom | Likely |
|---------|--------|
| `too many connections` | Budget exceeded |
| Pool wait high | Pool too small or queries slow |

---

## Pitfalls

- Copy-paste pool=50 on 40 pods
- No reserve for migrations/admin
- Opening raw connections outside pool

---

## 30-sec answer

> I treat DB connections as a global budget: pods times pool size must fit max_connections. HPA max is limited by that math.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
