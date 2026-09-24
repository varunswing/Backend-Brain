# Bulkheads + Circuit Breakers

**Band:** 1K → 10K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

**Bulkhead**: isolate resources (pools, threads, pods) so one dependency’s failure doesn’t take all capacity. **Circuit breaker**: after errors, fail fast awhile instead of waiting on timeouts.

---

## Why this band

Fan-out to many deps at multi-k RPS: one slow tax service can exhaust the whole pod’s threads/goroutines.

---

## How (interview-ready)

1. Separate connection pools / thread pools per dependency.
2. Timeouts per dependency &lt; overall SLA.
3. Breaker: Closed → Open on error threshold → Half-Open probe.
4. Shed non-critical features first.

---

## How vs related ideas

| | No isolation | Bulkhead |
|--|--------------|----------|
| Slow dep | Whole pod stuck | Cap blast |
| Recovery | Retry storm | Controlled |

---

## Pitfalls

- One shared executor for all IO
- Breaker flapping without cooldown
- Breaking critical path too aggressively without fallback

---

## 30-sec answer

> I isolate pools per dependency and trip circuit breakers on sustained errors so one slow gRPC doesn’t exhaust every goroutine at multi-k RPS.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
