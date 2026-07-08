# redelivery-timers Specification

## Purpose

The runtime that drives ack-deadline enforcement: a periodic sweeper that expires overdue leases, the configuration that governs it, and dead-letter topic routing for messages that exhaust their attempt limit.

## Requirements

### Requirement: Periodic lease sweep

A periodic sweeper SHALL run at a configurable interval and, for each outstanding **LEASED** message whose ack deadline has passed, issue `ExpireAckDeadline` to the owning subscription (triggering redelivery or dead-lettering per the subscription's attempt limit). The sweep SHALL be idempotent and safe to run concurrently with pulls and acks: an `ExpireAckDeadline` for a message that has since been acked, re-leased, or dead-lettered is a harmless no-op. Discovery of overdue leases SHALL be served from a read model of outstanding leased messages (mirroring the delivery projection), keeping the scan off the entities.

#### Scenario: The sweep expires overdue leases
- **GIVEN** a subscription with a LEASED message whose deadline passed before the sweep tick
- **WHEN** the sweeper runs
- **THEN** it issues `ExpireAckDeadline` for that message and the message returns to AVAILABLE (or is dead-lettered if at the limit)

#### Scenario: The sweep leaves within-deadline leases untouched
- **GIVEN** a LEASED message whose deadline is still in the future
- **WHEN** the sweeper runs
- **THEN** it issues no expiry for that message

#### Scenario: Edge case — a sweep over no overdue leases does nothing
- **GIVEN** a subscription with only AVAILABLE messages and within-deadline leases
- **WHEN** the sweeper runs
- **THEN** no `ExpireAckDeadline` is issued and no events are produced

#### Scenario: Edge case — a message acked between scan and expiry is a no-op
- **GIVEN** the sweeper has selected an overdue `ackId` to expire
- **WHEN** the consumer acknowledges that `ackId` before the expiry is applied
- **THEN** the resulting `ExpireAckDeadline` is a no-op and the message is not resurrected

### Requirement: Configurable ack deadline and delivery limits

The service SHALL make the default ack deadline, maximum delivery attempts, sweep interval, and dead-letter topic configurable via HOCON with environment-variable overrides, with sane defaults (`30s`, `5`, `5s`, unset). The ack deadline SHALL be overridable per pull. A non-positive ack deadline or sweep interval SHALL fail fast at startup with a clear error and a non-zero exit code.

#### Scenario: Defaults applied when unset
- **GIVEN** no redelivery settings are provided
- **WHEN** the service starts
- **THEN** it uses ack deadline `30s`, max delivery attempts `5`, sweep interval `5s`, and no dead-letter topic

#### Scenario: Per-pull ack-deadline override is honored
- **GIVEN** a pull requesting an ack deadline of `10s`
- **WHEN** the pull leases messages
- **THEN** those messages' deadlines are `now + 10s`, not the configured default

#### Scenario: Edge case — invalid deadline or interval fails fast
- **GIVEN** an ack deadline or sweep interval configured as zero or negative
- **WHEN** the service starts
- **THEN** it exits non-zero with an error naming the invalid setting (never silently uses a fallback)

### Requirement: Dead-letter topic routing

When a subscription dead-letters a message, the runtime SHALL republish that message's original payload and attributes to the configured dead-letter topic, adding metadata attributes `x-dead-letter-subscription`, `x-delivery-attempts`, and `x-original-message-id`. If no dead-letter topic is configured, the message SHALL be dropped with a warning log and SHALL NOT be redelivered. Republishing SHALL preserve the original payload bytes exactly.

#### Scenario: A dead-lettered message is republished with metadata
- **GIVEN** a configured dead-letter topic and a message dead-lettered after exhausting its attempts
- **WHEN** the runtime handles the `MessageDeadLettered` event
- **THEN** it publishes the original payload to the dead-letter topic with `x-dead-letter-subscription`, `x-delivery-attempts`, and `x-original-message-id` attributes

#### Scenario: Edge case — no dead-letter topic configured drops with a warning
- **GIVEN** no dead-letter topic is configured
- **WHEN** a message is dead-lettered
- **THEN** it is dropped, a warning is logged, and it is not redelivered

#### Scenario: Edge case — republish preserves payload bytes
- **GIVEN** a dead-lettered message with a specific binary payload
- **WHEN** it is republished to the dead-letter topic
- **THEN** the payload on the dead-letter topic is byte-identical to the original
