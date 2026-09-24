# Message Queues (SQS / Rabbit / Kafka lite)

**Band:** 100 → 1K RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A queue durably buffers work between producers and consumers so the API isn’t coupled to slow side effects.

---

## Why this band

Webhooks, emails, search index updates shouldn’t inflate request p99 at this RPS.

---

## How (interview-ready)

1. API writes DB then enqueues event (or outbox pattern).
2. Consumers process with retries + DLQ.
3. Make handlers idempotent.
4. Alert on depth and age.

---

## How vs related ideas

| | Sync fan-out | Queue |
|--|--------------|-------|
| Latency | Sum of deps | Fast ack |
| Spike | Melts API | Absorbs |

---

## Pitfalls

- Queue as infinite dump without consumers
- Non-idempotent consumers + at-least-once
- Huge payloads in queue

---

## 30-sec answer

> I put side effects on a queue so the request path stays thin. Consumers are idempotent with a DLQ — spikes get absorbed, not thrown at the user.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
