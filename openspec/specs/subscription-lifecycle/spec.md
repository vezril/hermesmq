# subscription-lifecycle Specification

## Purpose

Define the Subscription aggregate's pure write-side logic: creating a subscription, recording deliveries, and acknowledging / modifying the ack deadline of outstanding messages, as total `decide`/`evolve` functions with explicit rejections.

## Requirements

### Requirement: Create a subscription

The Subscription aggregate SHALL provide a pure `decide`/`evolve`. A `CreateSubscription` command bound to a `TopicId` on a not-yet-existing subscription SHALL emit `SubscriptionCreated`; creating an existing subscription SHALL be rejected.

#### Scenario: Creating a new subscription emits SubscriptionCreated
- **GIVEN** an empty subscription state
- **WHEN** `decide` handles `CreateSubscription(subscriptionId, topicId)`
- **THEN** it returns `Right(List(SubscriptionCreated(subscriptionId, topicId)))`, and evolving yields a state marked existing with no outstanding messages

#### Scenario: Edge case — creating an existing subscription is rejected
- **GIVEN** a subscription that already exists
- **WHEN** `decide` handles `CreateSubscription` again
- **THEN** it returns a `Left(Rejection)` (already exists) and emits no event

### Requirement: Record delivery of a message

A `RecordDelivery` command carrying an `AckId` and the full `Message` SHALL, on an existing subscription, emit `MessageDelivered` and `evolve` SHALL add that message to the outstanding set keyed by `AckId` in the **AVAILABLE** state with **no active ack deadline** — the deadline is assigned only when the message is leased on pull, not at delivery. Re-delivering an `AckId` that is already outstanding (in any state) SHALL be an idempotent no-op (no event, accepted) so projection replays do not duplicate outstanding entries. Recording delivery on a non-existent subscription SHALL be rejected.

#### Scenario: Recording delivery adds an available outstanding message
- **GIVEN** an existing subscription with no outstanding messages
- **WHEN** `decide` handles `RecordDelivery(ackId, message)`
- **THEN** it returns `Right(List(MessageDelivered(ackId, message)))`, and evolving adds `ackId` to the outstanding set as AVAILABLE with no active deadline

#### Scenario: Re-delivering an already-outstanding ackId is an idempotent no-op
- **GIVEN** a subscription with `ackId` already outstanding (AVAILABLE or LEASED)
- **WHEN** `decide` handles `RecordDelivery(ackId, message)` again
- **THEN** it returns `Right(Nil)` (no new event) and the outstanding set is unchanged

#### Scenario: Edge case — an undelivered-to-pull message is never expired by the sweeper
- **GIVEN** a message recorded as delivered but never pulled (still AVAILABLE, no deadline)
- **WHEN** the sweeper scans for overdue leases
- **THEN** the message is not a candidate for expiry (only LEASED messages with a passed deadline expire)

#### Scenario: Edge case — recording delivery on a non-existent subscription is rejected
- **GIVEN** an empty subscription state
- **WHEN** `decide` handles `RecordDelivery(...)`
- **THEN** it returns a `Left(Rejection)` (subscription not found) and emits no event

### Requirement: Pull outstanding messages

Pull SHALL be a **persisting, leasing** operation: it returns up to a requested maximum of the subscription's **AVAILABLE** outstanding messages (each with its `AckId`, payload, attributes, and publish time) and, for every message returned, SHALL lease it by setting its ack deadline to `now + ackDeadline` (the configured default, overridable per pull) and marking it **LEASED**, emitting `MessageLeased(ackIds, deadline)`. A message that is currently LEASED with an unexpired deadline SHALL NOT be returned by a subsequent pull (visibility timeout). A message whose **`expireTime` has passed SHALL NOT be returned** (it is left for the sweeper to purge). Pull SHALL never return more than the requested maximum, and a pull with no available messages SHALL return empty (not an error).

#### Scenario: Pull returns and leases available messages
- **GIVEN** a subscription with two AVAILABLE outstanding messages
- **WHEN** a pull for up to 10 is issued with ack deadline `D`
- **THEN** both messages are returned with their `AckId`/payload, `MessageLeased` is emitted, and evolving marks both LEASED with deadline `now + D`

#### Scenario: A leased message is invisible to an immediate second pull
- **GIVEN** a subscription whose only message was just leased with an unexpired deadline
- **WHEN** a second pull is issued before the deadline passes
- **THEN** the message is not returned (an empty result)

#### Scenario: Pull respects the requested maximum
- **GIVEN** a subscription with three AVAILABLE messages
- **WHEN** a pull for up to 2 is issued
- **THEN** at most 2 messages are returned and leased

#### Scenario: Edge case — a message whose lease expired is available to pull again
- **GIVEN** a leased message whose deadline has passed and which the sweeper returned to AVAILABLE
- **WHEN** a pull is issued
- **THEN** the message is returned and re-leased with a fresh deadline

#### Scenario: Edge case — an expired (TTL) available message is not returned
- **GIVEN** an AVAILABLE message whose `expireTime` has already passed
- **WHEN** a pull is issued at a time after `expireTime`
- **THEN** the message is not returned (it is skipped, awaiting purge)

#### Scenario: Edge case — pull on a subscription with no available messages returns nothing
- **GIVEN** a subscription with no AVAILABLE messages (empty, or all LEASED within deadline)
- **WHEN** a pull is issued
- **THEN** an empty result is returned (not an error)

### Requirement: Acknowledge a delivered message

An `Acknowledge` command referencing an `AckId` SHALL, when that `AckId` is outstanding, emit `MessageAcknowledged`, and `evolve` SHALL remove it from the outstanding set. Acknowledging an unknown or already-acknowledged `AckId` SHALL be rejected.

#### Scenario: Acknowledging an outstanding message removes it
- **GIVEN** a subscription with `ackId` outstanding
- **WHEN** `decide` handles `Acknowledge(ackId)`
- **THEN** it returns `Right(List(MessageAcknowledged(ackId)))`, and evolving removes `ackId` from outstanding

#### Scenario: Edge case — acknowledging an unknown ackId is rejected
- **GIVEN** a subscription with no outstanding message for `ackId`
- **WHEN** `decide` handles `Acknowledge(ackId)`
- **THEN** it returns a `Left(Rejection)` (unknown ackId) and emits no event

#### Scenario: Edge case — double acknowledge is rejected
- **GIVEN** `ackId` has already been acknowledged (removed from outstanding)
- **WHEN** `decide` handles `Acknowledge(ackId)` again
- **THEN** it returns a `Left(Rejection)` and emits no event

### Requirement: Modify the ack deadline of an outstanding message

A `ModifyAckDeadline` command referencing an outstanding `AckId` and a new `AckDeadline` SHALL emit `AckDeadlineModified`, and `evolve` SHALL update that message's deadline. Modifying an unknown `AckId` SHALL be rejected.

#### Scenario: Extending the deadline updates it
- **GIVEN** a subscription with `ackId` outstanding at some deadline
- **WHEN** `decide` handles `ModifyAckDeadline(ackId, newDeadline)`
- **THEN** it returns `Right(List(AckDeadlineModified(ackId, newDeadline)))`, and evolving updates the stored deadline

#### Scenario: Edge case — modifying the deadline of an unknown ackId is rejected
- **GIVEN** a subscription with no outstanding message for `ackId`
- **WHEN** `decide` handles `ModifyAckDeadline(ackId, newDeadline)`
- **THEN** it returns a `Left(Rejection)` (unknown ackId) and emits no event

### Requirement: Redeliver on ack-deadline expiry

An `ExpireAckDeadline` command for an overdue **LEASED** message SHALL emit `AckDeadlineExpired(ackId, attempt)`, and `evolve` SHALL return the message to **AVAILABLE**, clear its deadline, and increment its delivery attempt count (folded from the number of `AckDeadlineExpired` events for that `AckId`). Expiring a message that is not leased, whose deadline has not passed, or that is no longer outstanding SHALL be a no-op/rejection (so a sweep racing a late ack is harmless).

#### Scenario: Expiring an overdue leased message returns it for redelivery
- **GIVEN** a LEASED message with `attempt = 0` whose deadline has passed
- **WHEN** `decide` handles `ExpireAckDeadline(ackId, now)`
- **THEN** it emits `AckDeadlineExpired(ackId, 1)`, and evolving marks the message AVAILABLE with no deadline and attempt count 1

#### Scenario: Edge case — expiring a within-deadline lease is a no-op
- **GIVEN** a LEASED message whose deadline is still in the future
- **WHEN** `decide` handles `ExpireAckDeadline(ackId, now)`
- **THEN** it returns `Right(Nil)` (no event) and the message stays LEASED

#### Scenario: Edge case — expiring an acknowledged message is a no-op
- **GIVEN** an `ackId` that was acknowledged (removed from outstanding) after the sweep scanned it
- **WHEN** `decide` handles `ExpireAckDeadline(ackId, now)`
- **THEN** it returns `Right(Nil)`/`Left(Rejection)` with no state change (no resurrection)

### Requirement: Dead-letter after maximum delivery attempts

An expiry that reaches the configured `maxDeliveryAttempts` SHALL instead emit `MessageDeadLettered(ackId, attempt)`, and `evolve` SHALL remove the message from the outstanding set (it is not returned to AVAILABLE and is never redelivered). A `maxDeliveryAttempts` of `0` or absent SHALL mean unlimited — messages are always redelivered and never dead-lettered.

#### Scenario: Reaching the attempt limit dead-letters instead of redelivering
- **GIVEN** `maxDeliveryAttempts = 3` and a message that has already expired twice (attempt = 2)
- **WHEN** its lease expires a third time
- **THEN** the subscription emits `MessageDeadLettered(ackId, 3)` and evolving removes the message from outstanding

#### Scenario: Edge case — unlimited attempts never dead-letters
- **GIVEN** `maxDeliveryAttempts = 0`
- **WHEN** a message's lease expires many times
- **THEN** it is always returned to AVAILABLE and `MessageDeadLettered` is never emitted

#### Scenario: Edge case — a dead-lettered ackId is no longer outstanding
- **GIVEN** a message that has been dead-lettered
- **WHEN** `Acknowledge` or `ExpireAckDeadline` is handled for that `AckId`
- **THEN** it is rejected/no-op (the message is gone from outstanding)

### Requirement: Durable delivery-attempt accounting

The delivery attempt count SHALL be derived from journaled events so that attempt counts and dead-letter decisions survive a broker restart. On restart the aggregate SHALL rebuild the outstanding set from its journal; a message that was LEASED at crash time whose deadline has since passed SHALL be treated as expired by the next sweep and redelivered — never lost — consistent with at-least-once.

#### Scenario: Attempt count is rebuilt from the journal after restart
- **GIVEN** a message with two journaled `AckDeadlineExpired` events (attempt = 2)
- **WHEN** the aggregate recovers from its journal after a restart
- **THEN** the message's attempt count is 2 and one more expiry (with `maxDeliveryAttempts = 3`) dead-letters it

#### Scenario: Edge case — in-flight leases are redelivered after a crash
- **GIVEN** a LEASED message with an unexpired deadline at the moment of a crash
- **WHEN** the broker restarts, time passes beyond the deadline, and the sweeper runs
- **THEN** the message is expired and returned to AVAILABLE for redelivery

#### Scenario: Edge case — an already dead-lettered message stays gone after restart
- **GIVEN** a message that was dead-lettered before a restart
- **WHEN** the aggregate recovers from its journal
- **THEN** the message is not in the outstanding set and is not redelivered

### Requirement: Expire an outstanding message past its TTL

An `ExpireMessage` command referencing an outstanding `AckId` SHALL emit `MessageExpired(ackId)` when that message has an `expireTime` at or before `now`, and `evolve` SHALL remove it from the outstanding set (dropped — not returned to AVAILABLE, not redelivered, not dead-lettered). Expiring a message that is not outstanding, or whose `expireTime` has not passed (or has none), SHALL be a no-op. TTL expiry is independent of the delivery-attempt count.

#### Scenario: An outstanding message past its expireTime is expired and removed
- **GIVEN** an outstanding message whose `expireTime` is before `now` (in any lease state, any attempt count)
- **WHEN** `decide` handles `ExpireMessage(ackId, now)`
- **THEN** it emits `MessageExpired(ackId)` and evolving removes the message from outstanding

#### Scenario: Edge case — expiring a not-yet-expired message is a no-op
- **GIVEN** an outstanding message whose `expireTime` is after `now` (or has no `expireTime`)
- **WHEN** `decide` handles `ExpireMessage(ackId, now)`
- **THEN** it returns `Right(Nil)` and the message stays outstanding

#### Scenario: Edge case — expiring an unknown/gone ackId is a no-op
- **GIVEN** an `ackId` that is not outstanding (acked, dead-lettered, or already expired)
- **WHEN** `decide` handles `ExpireMessage(ackId, now)`
- **THEN** it returns `Right(Nil)` with no event and no resurrection
