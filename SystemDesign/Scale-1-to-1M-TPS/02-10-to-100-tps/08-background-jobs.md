# Background Jobs

**Band:** 10 → 100 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

Move slow/non-critical work (email, PDF, webhooks) off the request thread onto a worker/queue/cron.

---

## Why this band

Keeping email SMTP in the request path inflates latency and failure coupling at this load.

---

## How (interview-ready)

1. Enqueue job with payload + idempotency key.
2. Workers pull with retries/DLQ.
3. Request returns fast after durable enqueue.
4. Monitor queue depth and failure rate.

---

## How vs related ideas

| | Sync in request | Async job |
|--|-----------------|-----------|
| Latency | High | Low for user |
| Failure | User sees SMTP down | Retryable |

---

## Pitfalls

- Fire-and-forget without persistence
- Unbounded retries
- Jobs that must be sync for UX (login) moved wrongly

---

## 30-sec answer

> I push email and reports to background workers so the API stays thin. The user waits on enqueue durability, not on SMTP.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
