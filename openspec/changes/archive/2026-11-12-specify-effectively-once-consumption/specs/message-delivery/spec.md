# Spec Delta

## ADDED Requirements

### Requirement: The delivery key is stable across redeliveries

Every delivery of a message to a subscription SHALL carry an `ackId` derived solely from
`(subscription, message)`. The broker SHALL NOT mint a fresh key per delivery attempt, so every
delivery of that pair carries an identical `ackId`. A consumer that records processed `ackId`s can
therefore recognise and discard a repeat — this is the contract that makes at-least-once delivery
usable for once-only processing.

#### Scenario: A redelivery after the ack deadline carries the same key
- **GIVEN** a message delivered to subscription `s1` and left unacknowledged until its ack deadline lapses
- **WHEN** the sweeper makes it available and it is delivered again
- **THEN** the second delivery carries the same `ackId` as the first

#### Scenario: A redelivery after a nack carries the same key
- **GIVEN** a message pulled from `s1` and nacked by setting its ack deadline to zero
- **WHEN** it is delivered again
- **THEN** the delivery carries the same `ackId` as before the nack

#### Scenario: A projection replay re-delivers under the same key
- **GIVEN** a message already delivered to `s1`
- **WHEN** the delivery projection reprocesses the originating `MessagePublished` event
- **THEN** the delivery uses the same `ackId`, so the subscription treats it as a duplicate rather than a new message

#### Scenario: The same message to two subscriptions gets two distinct keys
- **GIVEN** topic `orders` has subscriptions `s1` and `s2`
- **WHEN** a message is published and fanned out to both
- **THEN** each subscription's delivery carries a different `ackId`, so one subscription's dedup state cannot suppress another's delivery

#### Scenario: Edge case — a consumer that dedups on the key processes once despite redelivery
- **GIVEN** a consumer that records each processed `ackId` and skips keys it has seen
- **WHEN** a message is delivered, processed, left unacknowledged, and redelivered
- **THEN** the consumer recognises the repeated `ackId` and does not process the message a second time

### Requirement: The broker does not claim exactly-once delivery

The broker SHALL NOT claim exactly-once delivery, in its documentation or its API. The guarantee ends
at the broker boundary: whether a consumer's side effects happened is not something the broker can
observe, so it cannot suppress a redelivery on that basis. Once-only processing is achieved by the
consumer deduplicating on the stable delivery key, not by the broker promising single delivery.

#### Scenario: A consumer crashing after its side effect still sees a redelivery
- **GIVEN** a consumer that writes to its own store and then crashes before acknowledging
- **WHEN** the ack deadline lapses
- **THEN** the message is redelivered — the broker cannot know the write happened, which is precisely why exactly-once is not offered and the consumer must dedup
