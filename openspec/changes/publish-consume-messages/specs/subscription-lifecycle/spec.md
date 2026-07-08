## MODIFIED Requirements

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

## ADDED Requirements

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
