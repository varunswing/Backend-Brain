# Fan-out: Push vs Pull

**Band:** 100K → 1M RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Push fan-out writes to many feeds on produce (celebrity post → millions writes). Pull fan-out stores one copy; readers fetch/merge on read.

---

## Why this band

Naive push at hyperscale creates write amplification that no DB survives. Pull/hybrid is mandatory for huge graphs.

---

## How (interview-ready)

1. Small users: push OK.
2. Celebrities: pull or hybrid (push to online followers only).
3. Cache timelines; async rank.
4. Measure write amplification explicitly.

---

## How vs related ideas

| | Push | Pull |
|--|------|------|
| Write cost | ×followers | ~1 |
| Read cost | Cheap | Heavier merge |

---

## Pitfalls

- Push for all users uniformly
- Ignoring amplification math
- No hybrid for celebs

---

## 30-sec answer

> Fan-out push multiplies writes by followers; pull keeps one copy and merges on read. For celebrities I use pull/hybrid — otherwise write amplification kills the system.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
