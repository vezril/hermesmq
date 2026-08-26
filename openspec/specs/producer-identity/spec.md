# producer-identity Specification

## Purpose

Define producer identity on the publish path — the optional producer id accepted by both APIs, the per-topic active-producer registry with its activity window, and the producer field in the log MDC.

## Requirements

### Requirement: Optional producer id on publish

The publish surface SHALL accept an optional producer id on both the gRPC `PublishRequest` (`producer_id`) and the REST publish body (`producerId`). An absent or empty-string producer id SHALL be treated as an anonymous publish, reproducing the pre-existing behaviour (no identity, no registry effect). A supplied producer id SHALL identify the caller to the active-producer registry and the log MDC for that call, without affecting whether or how the message is published.

#### Scenario: A named publish is attributed to its producer
- **GIVEN** an active topic and producer id `"ingest-7"`
- **WHEN** a client publishes with that producer id
- **THEN** the message is published exactly as an anonymous publish would be, and the producer is recorded as active for that topic

#### Scenario: An anonymous publish is unchanged
- **GIVEN** a client that supplies no producer id
- **WHEN** it publishes
- **THEN** publishing is identical to today and no producer is recorded

#### Scenario: Edge case — an empty-string producer id is treated as anonymous
- **GIVEN** a producer id of `""`
- **WHEN** a client publishes
- **THEN** it is treated as if no producer id were supplied

### Requirement: Active-producer registry with an activity window

The broker SHALL maintain, per topic, an in-memory registry of producer ids seen on publish calls, and SHALL count a producer as active while it has been seen within `hermesmq.producers.activity-window`. The count SHALL be distinct producer ids, updated as producers appear and expiring as they fall silent past the window. A window of `0` SHALL disable the registry. The registry SHALL NOT be part of the event-sourced state (it is ephemeral, best-effort, and per node).

#### Scenario: Distinct active producers are counted
- **GIVEN** producers `"a"` and `"b"` that both publish to one topic within the window
- **WHEN** the active count for the topic is read
- **THEN** it is 2

#### Scenario: Edge case — a silent producer expires from the count
- **GIVEN** producer `"a"` last seen longer ago than the activity window
- **WHEN** the active count is read
- **THEN** `"a"` is not counted

#### Scenario: Edge case — a zero window disables the registry
- **GIVEN** `hermesmq.producers.activity-window = 0`
- **WHEN** producers publish with ids
- **THEN** no producers are tracked and the active count is 0

### Requirement: Producer id in the log MDC

While serving a publish call that carries a producer id, the service SHALL place that id in the logging MDC so structured (JSON) logs emitted during the call carry a top-level `producer` field, and SHALL clear it afterwards so it does not leak to unrelated log lines.

#### Scenario: A log line during a named publish carries the producer field
- **GIVEN** a publish call with producer id `"ingest-7"` and JSON logging
- **WHEN** the service logs during that call
- **THEN** the log object includes `producer` = `"ingest-7"`

#### Scenario: Edge case — an anonymous publish adds no producer field
- **GIVEN** a publish call with no producer id
- **WHEN** the service logs during that call
- **THEN** the log object has no `producer` field
