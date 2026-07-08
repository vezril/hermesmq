# streaming-consume Specification

## Purpose

A server-streaming gRPC consume that pushes leased messages to a consumer as a
backpressured stream, authenticated and tenant-scoped like the unary API, with
acknowledgement over the existing unary `Ack` and at-least-once redelivery preserved.
Unary `Pull` and REST are unchanged.

## Requirements

### Requirement: Stream leased messages to a consumer

The service SHALL expose a server-streaming gRPC RPC `StreamMessages(StreamRequest)
returns (stream PulledMessage)` that repeatedly leases the subscription's AVAILABLE
messages (honoring the configured ack deadline) and emits each as a `PulledMessage` on the
stream. A leased message SHALL NOT be re-emitted while its lease is unexpired, and messages
delivered to the subscription while a stream is open SHALL be emitted without the consumer
issuing a new request.

#### Scenario: Available messages are pushed to the stream
- **GIVEN** a subscription with two AVAILABLE messages and an open `StreamMessages` call
- **WHEN** the consumer reads from the stream
- **THEN** it receives both messages, each with its `ackId` and payload, and each is now leased

#### Scenario: A message delivered after the stream opens is pushed without a new call
- **GIVEN** an open stream on a subscription that is currently empty
- **WHEN** a message is later delivered to that subscription
- **THEN** it appears on the already-open stream (within the poll interval)

#### Scenario: Edge case — a leased message is not re-emitted while its lease holds
- **GIVEN** a message already emitted (and thus leased) on the stream
- **WHEN** the stream continues before the ack deadline passes
- **THEN** that message is not emitted again

#### Scenario: Edge case — an empty subscription keeps the stream open without busy-looping
- **GIVEN** a subscription with no available messages
- **WHEN** the stream is open
- **THEN** it stays open emitting nothing (re-checking on the poll interval), not erroring or spinning

### Requirement: Backpressure limits leasing to consumer demand

The stream SHALL be demand-driven: it leases further messages only as the consumer takes
them, so a slow or stalled consumer SHALL cause the server to stop leasing (messages remain
AVAILABLE) rather than leasing the whole backlog ahead of demand.

#### Scenario: A slow consumer does not cause the whole backlog to be leased
- **GIVEN** a subscription with many AVAILABLE messages and a consumer that reads slowly
- **WHEN** the stream is open but the consumer has taken only a few
- **THEN** only a bounded number ahead of demand are leased; the rest remain AVAILABLE

#### Scenario: Edge case — cancelling the stream stops further leasing
- **GIVEN** an open stream that has been leasing messages
- **WHEN** the consumer cancels or disconnects
- **THEN** the server stops leasing and the stream ends cleanly

#### Scenario: Edge case — unacked streamed messages redeliver after the deadline
- **GIVEN** messages emitted on a stream but never acknowledged, and the stream cancelled
- **WHEN** their ack deadline passes and the sweeper runs
- **THEN** they return to AVAILABLE and can be delivered again (at-least-once preserved)

### Requirement: Streaming is authenticated and tenant-scoped

The `StreamMessages` RPC SHALL authenticate and tenant-scope exactly like the unary gRPC
calls: it resolves the caller's tenant from call metadata, streams only that tenant's
subscription, fails `UNAUTHENTICATED` without a valid credential, and fails `NOT_FOUND` for
a subscription the tenant does not have. Acknowledgement remains on the existing unary `Ack`.

#### Scenario: A valid caller streams only its own subscription
- **GIVEN** tenants `acme` and `beta` that each have a subscription `s1`
- **WHEN** `acme` opens `StreamMessages` for `s1`
- **THEN** it receives only `acme`'s messages, never `beta`'s

#### Scenario: Edge case — an unauthenticated stream is rejected
- **GIVEN** auth is enabled
- **WHEN** `StreamMessages` is called with no valid credential
- **THEN** the call fails `UNAUTHENTICATED` and no messages are streamed

#### Scenario: Edge case — streaming an unknown subscription fails NOT_FOUND
- **GIVEN** a caller whose tenant has no subscription `ghost`
- **WHEN** it calls `StreamMessages` for `ghost`
- **THEN** the call fails `NOT_FOUND`
