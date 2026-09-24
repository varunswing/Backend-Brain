# CDN + Edge Caching

**Band:** 10K → 100K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

CDN caches responses at PoPs near users. Edge can run light logic (auth check, A/B, bot filter). Origin sees a fraction of RPS.

---

## Why this band

Global read-heavy traffic: TLS+bytes from origin for every image/API is impossible at tens of thousands RPS.

---

## How (interview-ready)

1. Cache static aggressively (`Cache-Control`).
2. Cacheable GETs with keyed vary headers carefully.
3. Purge/invalidate on publish.
4. Origin shield to protect primary region.
5. Don’t cache personalized private data without care.

---

## How vs related ideas

| | Origin only | CDN |
|--|-------------|-----|
| Latency | Far users slow | Near PoP |
| Origin RPS | = client RPS | ≪ client |

---

## Pitfalls

- Caching authenticated private pages publicly
- No purge story
- Ignoring `Vary` → wrong user sees cache

---

## 30-sec answer

> CDN absorbs static and cacheable GETs at the edge so origin RPS is a slice of client RPS. I set TTLs, purges, and never accidentally cache private personalized bodies.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
