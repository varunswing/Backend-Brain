# Multi-Region Active-Active / Active-Passive

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Serve traffic from multiple geographic regions. Active-passive: one writes, others standby. Active-active: many regions take writes with conflict rules.

---

## Why this band

Speed of light and regional failure domains dominate. One region cannot give low latency worldwide at this RPS.

---

## How (interview-ready)

1. Pin users to nearest region (geo-DNS/anycast).
2. Replicate async; define conflict policy (LWW, CRDT, sticky primary entity).
3. Keep strong SoT regional when possible.
4. Failover runbooks + lag monitors.
5. Data residency constraints.

---

## How vs related ideas

| | Active-passive | Active-active |
|--|----------------|---------------|
| Write simplicity | Easier | Conflict tax |
| Failover | Promote | Already live |

---

## Pitfalls

- Sync cross-region commit on hot path
- No conflict policy
- Assuming identical consistency globally

---

## 30-sec answer

> Multi-region pins users locally and replicates async. I avoid cross-region synchronous commits on the hot path and state conflict rules up front for active-active.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
