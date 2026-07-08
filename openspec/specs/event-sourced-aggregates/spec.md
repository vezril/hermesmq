# event-sourced-aggregates Specification

## Purpose

Define the persistent Topic and Subscription aggregates: `EventSourcedBehavior` actors that delegate to the pure `decide`/`evolve`, persist events before replying (durable-write-before-acknowledgement), and reconstruct state by replaying the journal after a restart.

## Requirements

### Requirement: Durable write before acknowledgement

The Topic and Subscription aggregates SHALL be `EventSourcedBehavior` actors whose command handler runs the pure `decide`: on `Right(events)` it SHALL persist the events and only then reply success; on `Left(rejection)` it SHALL persist nothing and reply with the rejection. A command SHALL NOT be acknowledged before its event is durably journaled.

#### Scenario: Accepted command persists an event then replies success
- **GIVEN** a Topic entity that exists
- **WHEN** it receives a `Publish` command
- **THEN** a `MessagePublished` event is written to the journal and the reply indicates success only after the write

#### Scenario: Rejected command persists nothing
- **GIVEN** an empty (non-existent) Topic entity
- **WHEN** it receives a `Publish` command
- **THEN** no event is persisted and the reply carries the `TopicNotFound` rejection

#### Scenario: Edge case — reply carries the typed rejection, not an exception
- **GIVEN** an existing Subscription entity
- **WHEN** it receives `Acknowledge` for an unknown `AckId`
- **THEN** the reply carries `UnknownAckId` and no event is journaled, without throwing

### Requirement: Recovery by replay

On restart, an aggregate SHALL reconstruct its state by replaying its journaled events through `evolve`, so accepted state survives a crash and unacknowledged messages resume as outstanding.

#### Scenario: State is rebuilt from the journal after restart
- **GIVEN** a Subscription entity that has persisted `SubscriptionCreated` and `MessageDelivered`
- **WHEN** the entity is stopped and started again with the same persistence id
- **THEN** its recovered state exists and the delivered message is outstanding

#### Scenario: Edge case — acknowledged messages do not reappear after restart
- **GIVEN** a Subscription that delivered then acknowledged a message
- **WHEN** the entity is restarted and its state recovered
- **THEN** the acknowledged message is not outstanding (the acknowledgement was durable)

#### Scenario: Edge case — a fresh persistence id recovers to empty state
- **GIVEN** a persistence id with no journaled events
- **WHEN** the entity starts
- **THEN** it recovers to the empty initial state and accepts a create command
