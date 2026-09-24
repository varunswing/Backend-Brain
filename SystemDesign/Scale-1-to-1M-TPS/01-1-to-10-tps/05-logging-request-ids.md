# Structured Logs + Request ID

**Band:** 1 → 10 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A request ID (correlation ID) tags every log line for one client call so you can reconstruct a timeline.

---

## Why this band

With one box, grepping still needs a thread. Without IDs, ‘it failed’ is undebuggable when you add pods later.

---

## How (interview-ready)

1. Generate UUID at edge/middleware; pass downstream (`X-Request-Id`).
2. Log JSON: timestamp, level, request_id, user/order ids (redacted), latency.
3. Never log secrets/PII raw.
4. On error: log code + safe message; stack internal only.

---

## How vs related ideas

| | Printf debug | Structured + request_id |
|--|-------------|-------------------------|
| Search | Pain | Filter by id |
| Multi-service | Breaks | Survives |

---

## Pitfalls

- Logging bodies with cards/tokens
- New UUID per hop (breaks trace)
- Only logging success paths

---

## 30-sec answer

> I put a request ID on every request and structured logs around it. That’s how you debug now and how you survive when you scale to many pods.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
