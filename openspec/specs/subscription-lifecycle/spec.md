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

A `RecordDelivery` command carrying an `AckId`, the full `Message`, and an `AckDeadline` SHALL, on an existing subscription, emit `MessageDelivered` and `evolve` SHALL add that message to the outstanding (unacknowledged) set keyed by `AckId` — storing the message itself so it can be returned when consumed. Re-delivering an `AckId` that is already outstanding SHALL be an idempotent no-op (no event, accepted) so that projection replays do not duplicate outstanding entries. Recording delivery on a non-existent subscription SHALL be rejected.

#### Scenario: Recording delivery adds an outstanding message with its payload
- **GIVEN** an existing subscription with no outstanding messages
- **WHEN** `decide` handles `RecordDelivery(ackId, message, deadline)`
- **THEN** it returns `Right(List(MessageDelivered(ackId, message, deadline)))`, and evolving adds `ackId` to the outstanding set with the stored message

#### Scenario: Re-delivering an already-outstanding ackId is an idempotent no-op
- **GIVEN** a subscription with `ackId` already outstanding
- **WHEN** `decide` handles `RecordDelivery(ackId, message, deadline)` again
- **THEN** it returns `Right(Nil)` (no new event) and the outstanding set is unchanged

#### Scenario: Edge case — recording delivery on a non-existent subscription is rejected
- **GIVEN** an empty subscription state
- **WHEN** `decide` handles `RecordDelivery(...)`
- **THEN** it returns a `Left(Rejection)` (subscription not found) and emits no event

### Requirement: Pull outstanding messages

The subscription SHALL support a non-persisting query that returns up to a requested maximum of its outstanding messages, each with its `AckId`, payload, attributes, and publish time, so a consumer can receive them.

#### Scenario: Pull returns outstanding messages
- **GIVEN** a subscription with two outstanding messages
- **WHEN** a pull for up to 10 messages is issued
- **THEN** both outstanding messages are returned, each with its `AckId` and payload, and nothing is persisted

#### Scenario: Pull respects the requested maximum
- **GIVEN** a subscription with three outstanding messages
- **WHEN** a pull for up to 2 messages is issued
- **THEN** at most 2 messages are returned

#### Scenario: Edge case — pull on an empty subscription returns nothing
- **GIVEN** a subscription with no outstanding messages
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
