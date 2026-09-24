# MySQL/Postgres Read Replicas

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A replica is an async copy of the primary. You route heavy reads there to protect the primary’s CPU/IOPS.

---

## Why this band

Reporting and heavy GETs contend with writes on one node. Replica splits read load — with lag tradeoffs.

---

## How (interview-ready)

1. Add replica; replicate via WAL/binlog.
2. Route analytics / heavy list GETs to replica.
3. Keep read-your-writes on primary (or session consistency).
4. Monitor replication lag.

---

## How vs related ideas

| | Primary | Replica |
|--|---------|---------|
| Writes | Yes | No |
| Freshness | Latest | Lag possible |

---

## Pitfalls

- All reads to replica including ‘my just-written profile’
- Ignoring lag SLOs
- Using replica for money confirm

---

## 30-sec answer

> Read replicas take select load off the primary. I keep confirm/read-your-writes on primary and watch lag — eventual read is a choice, not an accident.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
