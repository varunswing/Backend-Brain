# Cache Miss vs Cache Error

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

**Miss** = key not found / expired → load DB → **may repopulate**. **Error/timeout** = cache infra unhealthy → load DB for user if possible → **do not** async write storm into sick cache.

---

## Why this band

Confusing miss and error causes outage amplification: every timeout triggers `go store` → Aerospike worse → more timeouts. Classic multi-k RPS failure.

---

## How (interview-ready)

```text
err, records = aero.get(keys)
if err is Nil / NotFound:
  load DB; async repopulate  # miss
elif err is Timeout / Unavailable:
  load DB; DO NOT repopulate  # error
else:
  handle
```
1. One policy function (`ShouldRepopulateCache`).
2. Getters return real errors.
3. Unit-test timeout path.
4. Metric: write QPS during incidents should drop, not spike.

---

## How vs related ideas

| | Miss | Error |
|--|------|-------|
| Meaning | No data | Infra sick |
| Repopulate? | Yes | No |
| User | DB path | DB path if possible |

---

## Pitfalls

- `err != nil` treated as miss always
- Empty bin treated as hit with zero inventory
- Disabling whole cache as first reaction (can melt DB)

---

## 30-sec answer

> Miss warms the cache; timeout must not. I gate async Aerospike writes on miss-only so degraded cache doesn’t get a write storm at 1K–10K RPS.

## Related

- [./02-aerospike.md](./02-aerospike.md)
- Goku real fix: `ShouldRepopulateCache` (see Resume `MyWorkExp/GokuSearchOptimization.md`)

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
