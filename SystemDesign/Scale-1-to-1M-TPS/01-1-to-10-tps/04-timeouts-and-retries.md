# Timeouts (and when not to retry)

**Band:** 1 → 10 RPS · ← [Band README](./README.md) · [Ladder](../README.md)

---

## What

A timeout caps how long you wait on a dependency. Without it, one hung call holds a thread/connection forever.

---

## Why this band

Even one outbound HTTP to SMS/email can wedge your only process. Failures are rare but deadly on a single box.

---

## How (interview-ready)

1. Set explicit connect + read timeouts on every HTTP/DB/RPC client.
2. Retry **only** idempotent / safe ops; add jittered backoff.
3. Don’t retry 4xx validation errors.
4. Prefer fail-fast + user message over infinite wait.

---

## How vs related ideas

| | Timeout | Retry |
|--|---------|-------|
| Goal | Bound wait | Overcome transient fail |
| Risk | False fail if too low | Amplify load if blind |

---

## Pitfalls

- Default infinite HTTP client timeouts
- Retry non-idempotent POST
- Retry storm without jitter

---

## 30-sec answer

> Every outbound call gets an explicit timeout. Retries only when safe and with backoff — at low RPS this prevents one hung dependency from freezing the app.

---

## Checklist

- [ ] Can explain **what** without buzzwords
- [ ] Can draw **how** on whiteboard
- [ ] Know **when not** to use it
- [ ] One real failure mode / pitfall ready
