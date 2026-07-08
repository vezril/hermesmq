# snapshot-retention Specification

## Purpose

Periodic state snapshots and journal event-retention for the event-sourced Topic and
Subscription aggregates: bounded recovery, explicit snapshot serialization, transparent
restore, and configurable, validated retention parameters. Event schemas and
`decide`/`evolve` are unchanged.

## Requirements

### Requirement: Periodic snapshots bound recovery

Each event-sourced aggregate (Topic, Subscription) SHALL persist a state snapshot every
`snapshotEveryEvents` persisted events and retain the last `keepNSnapshots` snapshots. On
recovery the aggregate SHALL restore from the most recent valid snapshot and replay only
the events persisted after it, rather than replaying the entire journal. A snapshot-bounded
recovery SHALL yield exactly the state a full replay of all events would yield.

#### Scenario: A snapshot is taken after the configured number of events
- **GIVEN** an aggregate configured to snapshot every N events
- **WHEN** N events have been persisted to it
- **THEN** a state snapshot is stored

#### Scenario: Recovery restores from the latest snapshot plus later events
- **GIVEN** an aggregate that has snapshotted and then persisted a few more events
- **WHEN** the entity is restarted and recovers
- **THEN** its recovered state equals the state produced by folding all events, and recovery did not require replaying events older than the snapshot

#### Scenario: Edge case — an aggregate with fewer than N events has no snapshot but still recovers fully
- **GIVEN** an aggregate that has persisted fewer than N events (no snapshot yet)
- **WHEN** it is restarted
- **THEN** it recovers correct state by replaying all its events (snapshotting never loses state)

#### Scenario: Edge case — recovery is identical with or without snapshots enabled
- **GIVEN** the same sequence of commands applied to an aggregate
- **WHEN** it recovers with snapshots enabled versus a pure event replay
- **THEN** the resulting state is identical (snapshots are transparent)

### Requirement: Journal events are retained (purged) after snapshotting

When a snapshot is taken, the aggregate SHALL delete journal events older than the
retained snapshot window, so the journal does not grow without bound (realizing
"acknowledged messages are eventually purged"). Deletion SHALL never remove events needed
to recover the current state from the most recent retained snapshot.

#### Scenario: Old events are deleted once captured in a snapshot
- **GIVEN** an aggregate that snapshots every N events with delete-on-snapshot enabled
- **WHEN** it crosses a snapshot boundary
- **THEN** journal events older than the retained snapshot are deleted and the entity still recovers correct state

#### Scenario: Edge case — deletion never breaks recovery of current state
- **GIVEN** an aggregate that has snapshotted and had older events deleted
- **WHEN** it is restarted
- **THEN** it recovers the same current state (the retained snapshot plus surviving later events are sufficient)

#### Scenario: Edge case — a subscription that acked all messages retains a small journal
- **GIVEN** a subscription that delivered, leased, and acknowledged many messages over time
- **WHEN** snapshots and retention have run
- **THEN** its recovered state has no outstanding messages and its journal has been bounded (old delivery/ack events purged)

### Requirement: Explicit snapshot serialization

`TopicState` and `SubscriptionState` snapshots SHALL be serialized with the explicit
JSON serializer (never Java serialization, which remains disabled), and SHALL round-trip
losslessly — including a subscription's outstanding messages with their lease state and
delivery-attempt counts.

#### Scenario: A subscription state round-trips through the snapshot serializer
- **GIVEN** a `SubscriptionState` with outstanding messages in AVAILABLE and LEASED states and non-zero attempt counts
- **WHEN** it is serialized and deserialized via the snapshot serializer
- **THEN** the result equals the original state

#### Scenario: A topic state round-trips through the snapshot serializer
- **GIVEN** a `TopicState` with labels
- **WHEN** it is serialized and deserialized
- **THEN** the result equals the original state

#### Scenario: Edge case — snapshot state is refused by Java serialization
- **GIVEN** Java serialization is disabled
- **WHEN** a state snapshot is persisted
- **THEN** it is serialized by the explicit JSON serializer (an unbound Java-serializable would be refused)

### Requirement: Configurable and validated retention

The service SHALL make the snapshot cadence (`snapshotEveryEvents`) and retained-snapshot count (`keepNSnapshots`) configurable via HOCON with environment-variable overrides and sane defaults (`100`, `2`). A non-positive value for either SHALL fail fast at startup with a clear error and a non-zero exit code.

#### Scenario: Defaults applied when unset
- **GIVEN** no retention settings are provided
- **WHEN** the service starts
- **THEN** it snapshots every `100` events and keeps `2` snapshots

#### Scenario: Overrides are honored
- **GIVEN** `snapshotEveryEvents` and `keepNSnapshots` set via environment variables
- **WHEN** the config is parsed
- **THEN** the parsed retention reflects the overrides

#### Scenario: Edge case — a non-positive snapshot interval fails fast
- **GIVEN** `snapshotEveryEvents` configured as `0` or negative
- **WHEN** the service starts
- **THEN** it exits non-zero with an error naming the invalid setting

#### Scenario: Edge case — a non-positive keep-count fails fast
- **GIVEN** `keepNSnapshots` configured as `0` or negative
- **WHEN** the service starts
- **THEN** it exits non-zero with an error naming the invalid setting
