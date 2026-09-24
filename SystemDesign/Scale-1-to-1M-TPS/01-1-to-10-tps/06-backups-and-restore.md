# Backups + Restore Drill

**Band:** 1 → 10 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Backups copy data off-box; a restore drill proves you can bring it back within an acceptable RPO/RTO.

---

## Why this band

Disk death / bad migration at 5 RPS still loses the business. Backups unused = fiction.

---

## How (interview-ready)

1. Automated daily (or continuous) backups to separate storage.
2. Define RPO (how much data loss OK) and RTO (how fast restore).
3. Restore to a scratch DB **once**; time it; document steps.
4. Encrypt backups; restrict access.

---

## How vs related ideas

| | Backup exists | Restore tested |
|--|---------------|----------------|
| Safety | Partial | Real |

---

## Pitfalls

- Only backups on same disk
- Never tested restore
- No point-in-time if needed for money systems

---

## 30-sec answer

> I don’t claim HA at 1–10 RPS, but I do automated backups and at least one restore drill — untested backups don’t count.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
