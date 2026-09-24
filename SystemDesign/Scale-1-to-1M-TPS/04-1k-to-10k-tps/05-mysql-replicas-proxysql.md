# MySQL Replicas + ProxySQL / Pool Split

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Multiple read replicas serve miss-path SELECTs. ProxySQL/pool split routes by query type or app pool name (`citysearch` vs `booking`) so search misses don’t starve writes.

---

## Why this band

Miss QPS at multi-k search needs more than one reader; connection chaos needs a proxy or strict pool budgets.

---

## How (interview-ready)

1. Primary: writes + read-your-writes critical paths.
2. Replicas: search/list miss SELECTs.
3. Separate pools per workload.
4. ProxySQL for routing/multiplexing optional.
5. Lag monitors; failover runbooks.

---

## How vs related ideas

| | One primary all reads | Replicas + split pools |
|--|------------------------|------------------------|
| Search miss | Fights bookings | Isolated |
| Blast | Total | Contained |

---

## Pitfalls

- Search on master ‘for freshness’ always
- One pool shared by batch jobs + API
- Ignoring replica lag for confirm

---

## 30-sec answer

> I send cache misses to MySQL replicas with dedicated pools — never park search on the master. ProxySQL or app-level pool names keep booking connections safe.

## Related

- ../03-100-to-1k-tps/05-read-replicas.md
- ../03-100-to-1k-tps/08-connection-budget.md

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
