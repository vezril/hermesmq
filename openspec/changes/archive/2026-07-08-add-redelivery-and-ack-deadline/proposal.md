# Change: add-redelivery-and-ack-deadline

## Why

HermesMQ delivers at-least-once, and the Subscription aggregate already models an
outstanding set with a per-message ack deadline (`MessageDelivered` carries a
deadline; `ModifyAckDeadline` can extend it). But **nothing enforces that
deadline**: a message pulled and never acknowledged is neither redelivered nor
timed out, and a poison message that always fails would, once enforcement exists,
loop forever. The README lists "redelivery timers / ack-deadline expiry" as the
outstanding gap.

This change makes the ack deadline real. A pull **leases** the messages it returns
(true visibility timeout); a periodic sweeper **expires** overdue leases and
returns them for redelivery; delivery attempts are counted durably; and messages
that exhaust a configured attempt limit are **dead-lettered** to a configured
topic instead of cycling forever. This closes the honesty gap between HermesMQ's
"at-least-once, survives crashes" claim and its actual delivery behavior.

## What Changes

- **subscription-lifecycle** (MODIFIED + ADDED):
  - `RecordDelivery` now adds a message as **AVAILABLE** with no active deadline —
    the deadline is assigned when the message is leased on pull, not at delivery.
  - **Pull becomes a persisting, leasing command**: it returns available messages
    and leases each (sets deadline = now + ack-deadline, marks LEASED); a leased
    message within its deadline is invisible to further pulls.
  - New: **redeliver on ack-deadline expiry** (`ExpireAckDeadline` →
    `AckDeadlineExpired`, message returns to AVAILABLE, attempt++).
  - New: **dead-letter after max attempts** (`MessageDeadLettered`, removed from
    outstanding).
  - New: **durable delivery-attempt accounting** derived from journaled events, so
    counts and dead-letter decisions survive restart.
- **redelivery-timers** (new capability):
  - A **periodic sweeper** that scans overdue leases and issues expiries.
  - **Configurable** ack-deadline default, max delivery attempts, sweep interval,
    dead-letter topic (HOCON/env, fail-fast validation).
  - **Dead-letter topic routing**: republish the dead-lettered payload (with
    metadata) to the configured topic, or drop-with-warning if none configured.
- **pubsub-api** (ADDED):
  - Expose `POST /v1/subscriptions/{id}/modifyAckDeadline` for lease extension, with
    `ackDeadlineSeconds: 0` acting as an immediate nack (make available now).

## Impact

- Affected specs: `subscription-lifecycle` (MODIFIED: record-delivery, pull; ADDED:
  expiry, dead-letter, attempt accounting), `redelivery-timers` (ADDED — new),
  `pubsub-api` (ADDED: modifyAckDeadline endpoint).
- Affected code: Subscription aggregate (`decide`/`evolve`, new events), a new
  periodic-sweep runtime component, `ServiceConfig` (new settings), dead-letter
  republish wiring, one new HTTP route. Java serialization stays disabled; new
  events are explicit JSON with additive schema evolution.
- Behavior change: **pull is no longer a pure query** — it persists a lease event.
  This is the deliberate semantic shift that makes the ack deadline meaningful.
- Guarantees: at-least-once is preserved and strengthened (in-flight leases that a
  crash loses are treated as expired → redelivered). Exactly-once remains a non-goal.
- Out of scope: consumer-side automatic lease renewal (streaming pull), exactly-once,
  ordered delivery.
