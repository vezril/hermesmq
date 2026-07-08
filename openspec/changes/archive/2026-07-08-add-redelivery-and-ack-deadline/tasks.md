# Tasks: add-redelivery-and-ack-deadline

TDD throughout — for each behavior write the failing test first (Red), implement to
green, then refactor. Run `sbt test` after each step. Order is dependency-first:
pure aggregate logic → runtime sweeper → config → dead-letter routing → REST → integration.

## 1. Domain: lease states & new events (pure `decide`/`evolve`)

- [x] 1.1 (test) Outstanding entries carry an AVAILABLE/LEASED state and (LEASED) a deadline + attempt count folded from events
- [x] 1.2 (test) `RecordDelivery` adds a message as AVAILABLE with no deadline; re-delivery of an outstanding `AckId` is a no-op; non-existent subscription rejected
- [x] 1.3 (impl) Add `MessageLeased`, `AckDeadlineExpired`, `MessageDeadLettered` events (explicit JSON, additive schema) and the lease-state model; update `evolve`
- [x] 1.4 (test) `ExpireAckDeadline` on an overdue LEASED message → `AckDeadlineExpired(attempt+1)`, returns AVAILABLE; within-deadline / acked / absent → no-op
- [x] 1.5 (test) At `maxDeliveryAttempts`, expiry emits `MessageDeadLettered` and removes the message; `0` = unlimited (never dead-letters)
- [x] 1.6 (impl) Implement `ExpireAckDeadline` decide-logic incl. the dead-letter branch
- [x] 1.7 (test) Attempt count rebuilds from journaled `AckDeadlineExpired` events across a simulated recovery
- [x] 1.8 (refactor) Tidy the aggregate; assert exhaustive command/event matches

## 2. Pull becomes a leasing command

- [x] 2.1 (test) Pull returns only AVAILABLE messages, up to max, and emits `MessageLeased` setting deadline = now + ackDeadline
- [x] 2.2 (test) A within-deadline LEASED message is invisible to a second pull; an expired-then-swept message is pullable again
- [x] 2.3 (impl) Convert pull from a non-persisting query to a persisting, leasing command; support a per-pull ack-deadline override
- [x] 2.4 (test) Edge: pull on no-available returns empty (not error); max respected

## 3. Read model for overdue-lease discovery

- [x] 3.1 (test) A projection/read model lists outstanding LEASED messages with their deadlines per subscription
- [x] 3.2 (impl) Add the projection (mirroring the existing delivery projection); keep the scan off the entities

## 4. Periodic sweeper (runtime)

- [x] 4.1 (test) The sweeper issues `ExpireAckDeadline` for overdue leases only; within-deadline leases untouched
- [x] 4.2 (test) Idempotent/concurrency-safe: an ack landing between scan and expiry yields a no-op
- [x] 4.3 (impl) Implement the interval-driven sweeper reading the projection and dispatching expiries
- [x] 4.4 (refactor) Ensure the sweeper is cancelled cleanly on `CoordinatedShutdown`

## 5. Configuration (fail-fast)

- [x] 5.1 (test) Defaults: ack deadline `30s`, max attempts `5`, sweep interval `5s`, no dead-letter topic
- [x] 5.2 (test) Env overrides parsed; non-positive ack deadline / sweep interval fail fast with non-zero exit
- [x] 5.3 (impl) Extend `ServiceConfig` with the new settings + validation; document env vars in README

## 6. Dead-letter topic routing

- [x] 6.1 (test) On `MessageDeadLettered` with a configured topic, the payload is republished with `x-dead-letter-subscription`, `x-delivery-attempts`, `x-original-message-id`; payload bytes preserved
- [x] 6.2 (test) Edge: no dead-letter topic → dropped + warning, not redelivered
- [x] 6.3 (impl) Wire the dead-letter republish (reuse the publish path) reacting to `MessageDeadLettered`

## 7. REST: modifyAckDeadline

- [x] 7.1 (test) `POST /v1/subscriptions/{id}/modifyAckDeadline` extends deadlines; `ackDeadlineSeconds:0` nacks for immediate redelivery; unknown `AckId` reported (not fatal); unknown subscription → 404
- [x] 7.2 (impl) Add the route over the existing `ModifyAckDeadline` command

## 8. Integration & docs

- [x] 8.1 (test) End-to-end (testcontainers Postgres): publish → pull(lease) → no ack → sweep expires → redelivered → exhausts attempts → dead-lettered to topic
- [x] 8.2 (test) Restart mid-flight: LEASED messages redeliver; dead-lettered stay gone; attempt counts survive
- [x] 8.3 (docs) Update README: pull-leases semantics, redelivery/ack-deadline, dead-letter, new config table, modifyAckDeadline endpoint; flip the README capability row to ✅
- [x] 8.4 (refactor) Final pass; `sbt test` green; `openspec validate --strict` clean
