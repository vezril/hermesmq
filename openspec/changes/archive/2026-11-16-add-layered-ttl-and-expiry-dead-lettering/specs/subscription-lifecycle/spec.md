# Spec Delta

## ADDED Requirements

### Requirement: A subscription carries an optional TTL

A subscription SHALL carry an optional time-to-live, set when it is created and journaled so it
survives recovery. It SHALL apply to messages delivered to that subscription, shortening their expiry
where it is earlier than the expiry already carried by the message, and SHALL NOT extend it.

#### Scenario: A subscription TTL shortens its copy of a message
- **GIVEN** a subscription created with a one-minute TTL on a topic with no TTL
- **WHEN** a message is delivered to it
- **THEN** that outstanding message expires a minute after the message was published

#### Scenario: The TTL survives recovery
- **GIVEN** a subscription created with a TTL
- **WHEN** its aggregate is recovered from the journal
- **THEN** the recovered state carries the same TTL

#### Scenario: Edge case — a subscription journaled before TTLs existed has none
- **GIVEN** a `SubscriptionCreated` event journaled before subscription TTLs existed
- **WHEN** it is deserialized during recovery
- **THEN** the subscription has no TTL rather than failing to parse
