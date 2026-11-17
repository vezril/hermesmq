# Spec Delta

## MODIFIED Requirements

### Requirement: Create a subscription

The Subscription aggregate SHALL provide a pure `decide`/`evolve`. A `CreateSubscription` command bound to a `TopicId` on a not-yet-existing subscription SHALL emit `SubscriptionCreated` carrying the requested start position; creating an existing subscription SHALL be rejected.

#### Scenario: Creating a new subscription emits SubscriptionCreated
- **GIVEN** an empty subscription state
- **WHEN** `decide` handles `CreateSubscription(subscriptionId, topicId)`
- **THEN** it returns `Right(List(SubscriptionCreated(subscriptionId, topicId, startFrom)))`, and evolving yields a state marked existing with no outstanding messages

#### Scenario: The start position defaults to now
- **GIVEN** an empty subscription state
- **WHEN** `decide` handles a `CreateSubscription` that names no start position
- **THEN** the emitted event carries the `now` start position, preserving the behaviour of subscriptions created before this field existed

#### Scenario: Edge case — an older journaled event without a start position reads as now
- **GIVEN** a `SubscriptionCreated` event journaled before the start position existed
- **WHEN** it is deserialized during recovery
- **THEN** it yields the `now` start position rather than failing to parse

#### Scenario: Edge case — creating an existing subscription is rejected
- **GIVEN** a subscription that already exists
- **WHEN** `decide` handles `CreateSubscription` again
- **THEN** it returns a `Left(Rejection)` (already exists) and emits no event
