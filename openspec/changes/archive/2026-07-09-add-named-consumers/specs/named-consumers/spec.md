## ADDED Requirements

### Requirement: Optional consumer id on consume operations

The consume operations — gRPC `Pull`, `StreamMessages`, and `Consume` (`ConsumeStart`), and the REST pull — SHALL accept an optional consumer id. An absent or empty-string consumer id SHALL be treated as an anonymous consume, reproducing the pre-existing behaviour (no identity, no registry effect). A supplied consumer id SHALL identify the caller to the active-consumer registry and the log MDC for that call, without affecting which messages are leased.

#### Scenario: A named pull is attributed to its consumer
- **GIVEN** a subscription and a consumer id `"worker-3"`
- **WHEN** a client pulls with that consumer id
- **THEN** the pull leases messages exactly as an anonymous pull would, and the consumer is recorded as active

#### Scenario: An anonymous consume is unchanged
- **GIVEN** a client that supplies no consumer id
- **WHEN** it pulls or streams
- **THEN** delivery is identical to today and no consumer is recorded

#### Scenario: Edge case — an empty-string consumer id is treated as anonymous
- **GIVEN** a consumer id of `""`
- **WHEN** a client consumes
- **THEN** it is treated as if no consumer id were supplied

### Requirement: Active-consumer registry with an activity window

The broker SHALL maintain, per subscription, an in-memory registry of consumer ids seen on consume calls, and SHALL count a consumer as active while it has been seen within `hermesmq.consumers.activity-window`. The count SHALL be distinct consumer ids, updated as consumers appear and expiring as they fall silent past the window. A window of `0` SHALL disable the registry. The registry SHALL NOT be part of the event-sourced state (it is ephemeral, best-effort, and per node).

#### Scenario: Distinct active consumers are counted
- **GIVEN** consumers `"a"` and `"b"` that both consume within the window
- **WHEN** the active count for the subscription is read
- **THEN** it is 2

#### Scenario: Edge case — a silent consumer expires from the count
- **GIVEN** consumer `"a"` last seen longer ago than the activity window
- **WHEN** the active count is read
- **THEN** `"a"` is not counted

#### Scenario: Edge case — a zero window disables the registry
- **GIVEN** `hermesmq.consumers.activity-window = 0`
- **WHEN** consumers consume with ids
- **THEN** no consumers are tracked and the active count is 0

### Requirement: Consumer id in the log MDC

While serving a consume call that carries a consumer id, the service SHALL place that id in the logging MDC so structured (JSON) logs emitted during the call carry a top-level `consumer` field, and SHALL clear it afterwards so it does not leak to unrelated log lines.

#### Scenario: A log line during a named consume carries the consumer field
- **GIVEN** a consume call with consumer id `"worker-3"` and JSON logging
- **WHEN** the service logs during that call
- **THEN** the log object includes `consumer` = `"worker-3"`

#### Scenario: Edge case — an anonymous consume adds no consumer field
- **GIVEN** a consume call with no consumer id
- **WHEN** the service logs during that call
- **THEN** the log object has no `consumer` field
