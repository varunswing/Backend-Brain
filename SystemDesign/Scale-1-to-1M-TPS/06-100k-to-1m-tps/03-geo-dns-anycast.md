# Geo-DNS + Anycast

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Geo-DNS returns different IPs by client location. Anycast advertises the same IP from many PoPs; BGP routes to nearby. Both steer traffic geographically.

---

## Why this band

Without steering, all users hammer one region — latency and capacity fail before app logic matters.

---

## How (interview-ready)

1. Health-check backends; withdraw bad PoPs.
2. Combine with CDN.
3. Understand DNS TTL vs failover speed.
4. Test regional withdraw.

---

## How vs related ideas

| | Unicast one region | Anycast/Geo |
|--|--------------------|-------------|
| Path | Often long | Local PoP |
| Failover | Manual/DNS slow | BGP/DNS health |

---

## Pitfalls

- Long DNS TTL + expecting instant failover
- Ignoring health withdraw
- Split-brain without cell design

---

## 30-sec answer

> Geo-DNS and anycast steer clients to nearby healthy PoPs. I plan TTLs and health withdraw — traffic steering is part of the 100K–1M design.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
