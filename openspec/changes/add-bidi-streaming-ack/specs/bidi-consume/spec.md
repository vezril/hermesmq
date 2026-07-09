# bidi-consume

A bidirectional gRPC consume where the client opens a subscription and acknowledges
messages over the same stream while the server pushes leased messages back —
backpressured, authenticated, and tenant-scoped, preserving at-least-once lease and
redelivery semantics. Unary `Pull`/`Ack` and server-streaming `StreamMessages` are unchanged.

## ADDED Requirements

### Requirement: Consume and acknowledge over one bidirectional stream

The service SHALL expose a bidirectional gRPC RPC `Consume(stream ConsumeRequest) returns
(stream PulledMessage)`. The client's first request SHALL be a `ConsumeStart` naming the
subscription (and optional `max` batch); the server SHALL then stream that subscription's
leased messages, and SHALL apply each subsequent `ConsumeAck` from the client by
acknowledging the referenced ids. A first request that is not a `ConsumeStart` SHALL fail
`INVALID_ARGUMENT`, and the outbound leasing SHALL behave exactly as server-streaming
`StreamMessages` (same lease deadline and redelivery).

#### Scenario: Open with Start, receive messages, then acknowledge over the stream
- **GIVEN** a subscription with available messages
- **WHEN** a client sends `ConsumeStart` then, after receiving messages, `ConsumeAck` with their ids
- **THEN** it receives the messages (leased) and the acked ids are removed from the subscription's outstanding set

#### Scenario: Acked messages are not redelivered; unacked ones are
- **GIVEN** an open consume stream that received two messages and acked only one
- **WHEN** the ack deadline passes and the sweeper runs
- **THEN** the acked message stays gone and the unacked one returns to AVAILABLE for redelivery

#### Scenario: Edge case — a first message that is not ConsumeStart fails INVALID_ARGUMENT
- **GIVEN** a new `Consume` call
- **WHEN** the client's first request is a `ConsumeAck` (not `ConsumeStart`)
- **THEN** the call fails `INVALID_ARGUMENT` and no subscription is opened

#### Scenario: Edge case — an unknown ackId in a ConsumeAck does not fail the stream
- **GIVEN** an open consume stream
- **WHEN** the client acks an id that is not outstanding
- **THEN** the stream stays open (the unknown ack is ignored/logged), consistent with unary ack

### Requirement: Backpressure and clean lifecycle

The outbound message stream SHALL be demand-driven (a slow consumer stops new leases, as
with `StreamMessages`), and inbound acknowledgement SHALL be processed independently of
outbound demand so a client that keeps acking is not stalled by slow reading. Cancelling or
disconnecting the call SHALL stop both directions cleanly; messages already leased but
unacked at that point SHALL redeliver after their deadline (at-least-once preserved).

#### Scenario: A slow reader does not stall acknowledgement
- **GIVEN** an open consume stream whose client is reading messages slowly but sending acks
- **WHEN** acks arrive while outbound demand is paused
- **THEN** the acks are still applied (inbound processing is not blocked on outbound demand)

#### Scenario: Edge case — cancelling the stream stops leasing and ack processing
- **GIVEN** an open consume stream
- **WHEN** the client cancels or disconnects
- **THEN** the server stops leasing and stops processing acks, and the call ends cleanly

#### Scenario: Edge case — messages unacked at cancellation redeliver
- **GIVEN** messages received on a consume stream but not yet acked when the client disconnects
- **WHEN** their ack deadline passes and the sweeper runs
- **THEN** they return to AVAILABLE and can be delivered again

### Requirement: Bidirectional consume is authenticated and tenant-scoped

The `Consume` RPC SHALL authenticate from call metadata and tenant-scope like the other
gRPC calls: it resolves the caller's tenant, consumes only that tenant's subscription,
fails `UNAUTHENTICATED` without a valid credential, and fails `NOT_FOUND` for a subscription
the tenant does not have.

#### Scenario: A valid caller consumes only its own subscription
- **GIVEN** tenants `acme` and `beta` that each have subscription `s1`
- **WHEN** `acme` opens `Consume` for `s1`
- **THEN** it receives only `acme`'s messages and its acks affect only `acme`'s subscription

#### Scenario: Edge case — an unauthenticated consume is rejected
- **GIVEN** auth is enabled
- **WHEN** `Consume` is opened with no valid credential
- **THEN** the call fails `UNAUTHENTICATED` and no messages are streamed

#### Scenario: Edge case — consuming an unknown subscription fails NOT_FOUND
- **GIVEN** a caller whose tenant has no subscription `ghost`
- **WHEN** it opens `Consume` with `ConsumeStart` for `ghost`
- **THEN** the call fails `NOT_FOUND`
