# Design: Redelivery Timers & Ack-Deadline Expiry

Design record for making HermesMQ's ack deadline enforceable. Builds directly on the
existing Subscription aggregate (`decide`/`evolve`, outstanding set keyed by `AckId`,
`MessageDelivered`/`Acknowledge`/`ModifyAckDeadline`).

## Message lifecycle (the model this introduces)

```
   RecordDelivery (projection, at publish)
        │  adds to outstanding as AVAILABLE (no active deadline)
        ▼
   ┌───────────┐   pull → LEASE (deadline = now + D)     ┌────────┐
   │ AVAILABLE │ ─────────────────────────────────────▶  │ LEASED │
   └───────────┘                                          └────────┘
        ▲                                                  │      │
        │  AckDeadlineExpired (attempt++, deadline cleared)│ ack  │ deadline
        │  when attempt < maxDeliveryAttempts               ▼      ▼ passes
        └──────────────────────────────────────────── (removed)  swept
                                                                  │
                                       attempt ≥ maxDeliveryAttempts │
                                                                  ▼
                                                     MessageDeadLettered
                                                     → republish to dead-letter topic
                                                       (or drop+warn if none)
```

Two outstanding sub-states, both keyed by `AckId` in the aggregate state:
- **AVAILABLE** — deliverable; returned (and leased) by the next pull.
- **LEASED** — has an active `deadline`; invisible to pull until acked or the deadline
  passes and the sweeper expires it.

## Key decisions (resolved)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| When the ack clock starts | **At pull (Option B)** — pull leases | Only model where the deadline is honest; matches the Pub/Sub semantics the API already borrows |
| Pull persistence | **Persisting command** (emits a lease event) | Required for a durable visibility timeout; cheap at homelab throughput |
| Dead-letter destination | **Republish to a configured topic** | Reuses existing topic/publish machinery; inspectable backlog of failures |
| Attempt-count durability | **Journaled** (derived from `AckDeadlineExpired` events) | Dead-lettering must survive restart; poison messages must not get infinite fresh tries |
| Expiry mechanism | **Periodic sweep** | Simple, one timer per node; precise-enough at homelab scale; avoids N per-message timers |

## Events & commands (aggregate deltas)

```
   new command   ExpireAckDeadline(ackId, now)     ← issued by the sweeper
   new events    MessageLeased(ackIds, deadline)    ← pull leases
                 AckDeadlineExpired(ackId, attempt) ← redelivery
                 MessageDeadLettered(ackId, attempt)← terminal, removed from outstanding
   changed       RecordDelivery no longer carries a deadline (assigned at lease)
   reused        Acknowledge, ModifyAckDeadline (extend / nack-at-0)
```

Attempt count is **not** a stored scalar that could drift — it is folded from the
count of `AckDeadlineExpired` events for that `AckId` during `evolve`, so a journal
replay reconstructs it exactly.

## Restart semantics (at-least-once, honest)

In-flight leases are **not** separately journaled beyond `MessageLeased`; on restart
the aggregate replays its journal and rebuilds the outstanding set. A message that was
LEASED at crash time is rebuilt with its last-known deadline; if that deadline has
already passed (likely, after downtime), the next sweep expires it → redelivery. Net
effect: a crash behaves like a lease expiry — messages are redelivered, never lost.
This strengthens, rather than weakens, at-least-once. Exactly-once stays a non-goal.

## The periodic sweeper (runtime)

```
   every <sweepInterval>:
     for each subscription with LEASED messages:
       for each leased msg where deadline < now:
         send ExpireAckDeadline(ackId, now) to the subscription entity
```

- **Idempotent & concurrency-safe**: `ExpireAckDeadline` on a message that was acked
  (removed) or is no longer leased is a no-op/rejection — so a sweep racing a late ack
  is harmless.
- **Discovery of overdue leases**: driven by a read-model/projection of outstanding
  leased messages and their deadlines (keeps the hot scan off the entities), or a
  bounded fan-out to known subscriptions. The projection approach mirrors the existing
  delivery projection and is preferred.
- **maxDeliveryAttempts = 0 / absent** ⇒ unlimited; never dead-letters.

## Dead-letter routing

On `MessageDeadLettered`, the runtime republishes the original payload and attributes
to the configured dead-letter topic, adding metadata attributes:
`x-dead-letter-subscription`, `x-delivery-attempts`, `x-original-message-id`. If no
dead-letter topic is configured, the message is dropped with a warning log (never
silently, never redelivered).

## Configuration (HOCON / env, fail-fast)

| Setting | Env | Default |
|---------|-----|---------|
| ack deadline | `HERMESMQ_ACK_DEADLINE` | `30s` |
| max delivery attempts | `HERMESMQ_MAX_DELIVERY_ATTEMPTS` | `5` (0 = unlimited) |
| sweep interval | `HERMESMQ_SWEEP_INTERVAL` | `5s` |
| dead-letter topic | `HERMESMQ_DEADLETTER_TOPIC` | *(unset ⇒ drop+warn)* |

Ack deadline is overridable per pull, and extendable via `ModifyAckDeadline`
(`0` seconds = immediate nack → available now). Non-positive deadlines or sweep
intervals fail fast at startup.

## Alternatives considered

- **Option A (clock at delivery, stateless pull)** — rejected: the lease is fiction;
  a consumer pulling twice within the deadline double-receives, and the deadline can
  expire before the consumer ever pulls.
- **Per-message Pekko timers** instead of a sweep — rejected: precise but proliferates
  scheduled objects; the sweep is simpler and adequate at homelab throughput.
- **Drop-only dead-letter** — rejected as the default; a dead-letter topic keeps a
  visible, inspectable record of poison messages (drop remains the no-config fallback).
