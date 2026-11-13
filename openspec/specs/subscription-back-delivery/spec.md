# subscription-back-delivery Specification

## Purpose
Lets a subscription receive messages that were published to its topic **before** the subscription
existed, so a consumer created during a deploy does not silently lose that window. Defines the opt-in,
how far back the replay reaches, and the guarantee that it happens at most once per subscription.

## Requirements

### Requirement: Back-delivery is opt-in at subscription creation

A subscription SHALL be created with a start position of either `now` or `earliest`, defaulting to
`now`. With `now` the subscription receives only messages published after it was created, which is the
behaviour of a subscription created without a start position. With `earliest` the broker SHALL deliver
the messages already in the topic's journal to the new subscription.

#### Scenario: The default start position preserves existing behaviour
- **GIVEN** a topic with messages already published to it
- **WHEN** a subscription is created without a start position
- **THEN** it receives none of those earlier messages, only ones published afterwards

#### Scenario: An earliest subscription receives messages published before it existed
- **GIVEN** topic `orders` with three messages already published and no subscriptions
- **WHEN** a subscription is created on `orders` with start position `earliest`
- **THEN** all three messages are delivered to it

#### Scenario: The start position is journaled, not held in the request
- **GIVEN** a subscription created with start position `earliest`
- **WHEN** the subscription aggregate is recovered from its journal
- **THEN** the recovered state reflects the `earliest` choice, so the decision survives restart

#### Scenario: Edge case — an existing subscription's start position cannot be changed
- **GIVEN** a subscription that already exists
- **WHEN** creation is attempted again with a different start position
- **THEN** it is rejected as already existing, and no back-delivery is triggered

### Requirement: Back-delivery happens at most once per subscription

The broker SHALL record durably that a subscription's back-delivery has been performed, and SHALL NOT
perform it again for that subscription. This guard SHALL NOT rely on the idempotency of delivery:
`RecordDelivery` de-duplicates only while a message is still outstanding, so once a consumer has
acknowledged a back-delivered message, repeating the back-delivery would deliver it a second time.

#### Scenario: A repeated trigger does not re-deliver
- **GIVEN** a subscription whose back-delivery has completed
- **WHEN** the triggering event is processed again, as an at-least-once projection may do
- **THEN** no message is delivered a second time

#### Scenario: Back-delivery is not repeated after the consumer drains it
- **GIVEN** a subscription that was back-delivered three messages and has acknowledged all three
- **WHEN** the triggering event is reprocessed
- **THEN** the subscription receives nothing, and its outstanding set stays empty

#### Scenario: Edge case — an interrupted back-delivery can complete
- **GIVEN** a back-delivery that failed partway, before being marked complete
- **WHEN** it is triggered again
- **THEN** it runs again, and messages already delivered and still outstanding are not duplicated

### Requirement: Back-delivery reaches only what the journal still holds

The horizon of `earliest` SHALL be the oldest event still retained in the topic's journal, not the
first message ever published. Snapshot retention deletes events behind the retained snapshots, and
back-delivery SHALL NOT fail or warn because of it. The documented behaviour SHALL state this limit so
`earliest` is not read as "all history".

#### Scenario: Purged events are not back-delivered
- **GIVEN** a topic whose older events have been deleted by snapshot retention
- **WHEN** a subscription is created with start position `earliest`
- **THEN** it receives the messages still in the journal and the purged ones are simply absent

#### Scenario: Edge case — a topic with no retained messages back-delivers nothing
- **GIVEN** a topic that has never been published to, or whose events are all purged
- **WHEN** a subscription is created with start position `earliest`
- **THEN** the subscription is created successfully with nothing delivered, and no error is reported

### Requirement: Back-delivery respects message validity

Back-delivery SHALL NOT deliver a message that has expired under its TTL, and SHALL treat a deleted
topic as having nothing to deliver. A back-delivered message SHALL be indistinguishable from a
normally delivered one: same payload bytes, attributes, correlation id, and a delivery key derived from
`(subscription, message)` exactly as live delivery derives it.

#### Scenario: An expired message is not back-delivered
- **GIVEN** a topic holding one message whose TTL has passed and one that is still valid
- **WHEN** a subscription is created with start position `earliest`
- **THEN** only the still-valid message is delivered

#### Scenario: A back-delivered message is acknowledged like any other
- **GIVEN** a subscription that was back-delivered a message
- **WHEN** the consumer pulls and acknowledges it
- **THEN** it behaves exactly as a live delivery: the same key format, and acknowledging removes it

#### Scenario: Edge case — back-delivery on a deleted topic delivers nothing
- **GIVEN** a topic that has been deleted
- **WHEN** a subscription is created against it with start position `earliest`
- **THEN** nothing is delivered and no error is raised by the back-delivery
