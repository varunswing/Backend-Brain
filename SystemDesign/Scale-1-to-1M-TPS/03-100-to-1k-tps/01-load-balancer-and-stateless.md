# Load Balancer + Stateless Apps

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A load balancer distributes traffic across app instances. Stateless apps keep no required local memory for correctness — any instance can serve any request.

---

## Why this band

Vertical scale tops out; you need **N identical pods**. Sticky state on one box fights that.

---

## How (interview-ready)

1. Put LB (NLB/ALB/Nginx) in front.
2. Move sessions to Redis/JWT.
3. Health checks; drain on deploy.
4. Homogeneous pods; config via env.

---

## How vs related ideas

| | Sticky stateful | Stateless + LB |
|--|-----------------|----------------|
| Scale out | Hard | Natural |
| Deploy | Pain | Rolling |

---

## Pitfalls

- Session in local memory after ‘adding pods’
- Uneven LB (one hot pod)
- Long-lived WS without plan

---

## 30-sec answer

> I put a load balancer in front of stateless app replicas. Sessions live in Redis or JWT so any pod can serve — that’s how you cross hundreds of RPS.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
