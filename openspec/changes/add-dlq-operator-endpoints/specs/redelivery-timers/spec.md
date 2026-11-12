# Spec Delta

## MODIFIED Requirements

### Requirement: Dead-letter topic routing

When a subscription dead-letters a message, the runtime SHALL republish that message's original payload and attributes to the configured dead-letter topic, adding metadata attributes `x-dead-letter-subscription`, `x-original-topic`, `x-delivery-attempts`, and `x-original-message-id`. If no dead-letter topic is configured, the message SHALL be dropped with a warning log and SHALL NOT be redelivered. Republishing SHALL preserve the original payload bytes exactly.

#### Scenario: A dead-lettered message is republished with metadata
- **GIVEN** a configured dead-letter topic and a message dead-lettered after exhausting its attempts
- **WHEN** the runtime handles the `MessageDeadLettered` event
- **THEN** it publishes the original payload to the dead-letter topic with `x-dead-letter-subscription`, `x-original-topic`, `x-delivery-attempts`, and `x-original-message-id` attributes

#### Scenario: The origin topic is recorded for self-contained replay
- **GIVEN** a message published to topic `orders` and dead-lettered from subscription `s1`
- **WHEN** it is republished to the dead-letter topic
- **THEN** `x-original-topic` is `orders`, so a replay needs no lookup of the subscription's topic

#### Scenario: Edge case — no dead-letter topic configured drops with a warning
- **GIVEN** no dead-letter topic is configured
- **WHEN** a message is dead-lettered
- **THEN** it is dropped, a warning is logged, and it is not redelivered

#### Scenario: Edge case — republish preserves payload bytes
- **GIVEN** a dead-lettered message with a specific binary payload
- **WHEN** it is republished to the dead-letter topic
- **THEN** the payload on the dead-letter topic is byte-identical to the original
