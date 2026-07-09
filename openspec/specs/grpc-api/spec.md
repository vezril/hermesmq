# grpc-api Specification

## Purpose

A Pekko gRPC (HTTP/2, protobuf) service exposing topic administration and pub/sub over
its own configurable endpoint, delegating to the same topic/subscription services as
REST, with domain rejections mapped to gRPC status codes. REST is unchanged.

## Requirements

### Requirement: Topic administration over gRPC

The service SHALL expose a gRPC `TopicAdminService` with `CreateTopic`, `GetTopic`,
`UpdateTopic`, and `DeleteTopic` RPCs, delegating to the same `TopicService` the REST
admin API uses. A create on a new id SHALL succeed; get/update/delete SHALL operate on
an existing topic. Creating an already-existing topic SHALL fail with `ALREADY_EXISTS`;
operating on a missing topic SHALL fail with `NOT_FOUND`; a malformed id or body SHALL
fail with `INVALID_ARGUMENT`.

#### Scenario: Create and read back a topic
- **GIVEN** a running gRPC endpoint with no `orders` topic
- **WHEN** a client calls `CreateTopic{topicId:"orders", labels:{team:"payments"}}` then `GetTopic{topicId:"orders"}`
- **THEN** create returns success and get returns the topic with its labels

#### Scenario: Update and delete a topic
- **GIVEN** an existing `orders` topic
- **WHEN** a client calls `UpdateTopic` with new labels, then `DeleteTopic{topicId:"orders"}`
- **THEN** update succeeds with the new labels and delete succeeds

#### Scenario: Edge case — creating a duplicate topic fails with ALREADY_EXISTS
- **GIVEN** an existing `orders` topic
- **WHEN** a client calls `CreateTopic{topicId:"orders"}` again
- **THEN** the RPC fails with gRPC status `ALREADY_EXISTS` and no state changes

#### Scenario: Edge case — operating on a missing topic fails with NOT_FOUND
- **GIVEN** no `ghost` topic exists
- **WHEN** a client calls `GetTopic`/`UpdateTopic`/`DeleteTopic` for `ghost`
- **THEN** the RPC fails with gRPC status `NOT_FOUND`

#### Scenario: Edge case — a blank topic id fails with INVALID_ARGUMENT
- **GIVEN** a running gRPC endpoint
- **WHEN** a client calls `CreateTopic{topicId:""}`
- **THEN** the RPC fails with gRPC status `INVALID_ARGUMENT` and no event is persisted

### Requirement: Publish and create subscriptions over gRPC

The service SHALL expose a gRPC `PubSubService` with `Publish` and `CreateSubscription`
RPCs delegating to the existing services. `Publish` to an existing topic SHALL return the
assigned message id; `CreateSubscription` on a new id SHALL succeed. Publishing to a
missing topic SHALL fail with `NOT_FOUND`, an empty payload SHALL fail with
`INVALID_ARGUMENT`, and a duplicate subscription SHALL fail with `ALREADY_EXISTS`.

#### Scenario: Publish a message to a topic
- **GIVEN** an existing `orders` topic
- **WHEN** a client calls `Publish{topicId:"orders", payload:…, attributes:{k:"v"}}`
- **THEN** the RPC returns success with a non-empty `messageId`

#### Scenario: Create a subscription
- **GIVEN** an existing `orders` topic and no `s1` subscription
- **WHEN** a client calls `CreateSubscription{subscriptionId:"s1", topicId:"orders"}`
- **THEN** the RPC returns success

#### Scenario: Edge case — publishing to a missing topic fails with NOT_FOUND
- **GIVEN** no `ghost` topic exists
- **WHEN** a client calls `Publish{topicId:"ghost", payload:…}`
- **THEN** the RPC fails with gRPC status `NOT_FOUND`

#### Scenario: Edge case — an empty payload fails with INVALID_ARGUMENT
- **GIVEN** an existing `orders` topic
- **WHEN** a client calls `Publish{topicId:"orders", payload:<empty>}`
- **THEN** the RPC fails with gRPC status `INVALID_ARGUMENT`

#### Scenario: Edge case — a duplicate subscription fails with ALREADY_EXISTS
- **GIVEN** subscription `s1` already exists
- **WHEN** a client calls `CreateSubscription{subscriptionId:"s1", topicId:"orders"}` again
- **THEN** the RPC fails with gRPC status `ALREADY_EXISTS`

### Requirement: Consume with leasing over gRPC

The service SHALL expose gRPC `Pull`, `Ack`, and `ModifyAckDeadline` RPCs that behave
identically to their REST counterparts. `Pull` SHALL lease up to the requested maximum of
the subscription's AVAILABLE messages (deadline `now + ackDeadline`) and return them with
their `ackId`s. `Ack` and `ModifyAckDeadline` SHALL accept a batch and report which ids
were applied and which were unknown — an unknown id SHALL NOT fail the RPC. Pull/ack/modify
on a missing subscription SHALL fail with `NOT_FOUND`.

#### Scenario: Pull leases available messages then ack removes them
- **GIVEN** subscription `s1` with two AVAILABLE messages
- **WHEN** a client calls `Pull{subscriptionId:"s1", max:10}` then `Ack` with the returned `ackId`s
- **THEN** pull returns both messages (now leased) and ack reports them acknowledged

#### Scenario: Modify ack deadline extends or nacks a lease
- **GIVEN** subscription `s1` with a LEASED `ackId`
- **WHEN** a client calls `ModifyAckDeadline{ackIds:[…], ackDeadlineSeconds:60}` (or `0` to nack)
- **THEN** the RPC returns success and reports the `ackId` modified

#### Scenario: Edge case — pulling a missing subscription fails with NOT_FOUND
- **GIVEN** no `ghost` subscription exists
- **WHEN** a client calls `Pull{subscriptionId:"ghost", max:10}`
- **THEN** the RPC fails with gRPC status `NOT_FOUND`

#### Scenario: Edge case — an unknown ackId is reported without failing the batch
- **GIVEN** subscription `s1` and an `ackId` that is not outstanding
- **WHEN** a client calls `Ack{ackIds:["unknown"]}`
- **THEN** the RPC returns success and reports that `ackId` as unknown (not acknowledged)

### Requirement: gRPC endpoint, error mapping, and REST coexistence

The gRPC service SHALL be served over HTTP/2 on its own configurable host/port
(`hermesmq.grpc`, overridable via `HERMESMQ_GRPC_*`), started alongside the REST server
from the same process. Domain `Rejection`s SHALL map to gRPC statuses (`NOT_FOUND`,
`ALREADY_EXISTS`, `INVALID_ARGUMENT`), a backend/service failure SHALL map to `UNAVAILABLE`,
and an invalid gRPC port SHALL fail fast at startup with a non-zero exit. The existing REST
API SHALL remain available and unchanged.

#### Scenario: gRPC is served on the configured port alongside REST
- **GIVEN** the service configured with a REST port and a distinct gRPC port
- **WHEN** the service starts and a gRPC client connects to the gRPC port
- **THEN** gRPC RPCs succeed on their port and REST requests still succeed on the REST port

#### Scenario: A service failure maps to UNAVAILABLE
- **GIVEN** the backing service is unavailable (the delegate call fails)
- **WHEN** a client issues any RPC
- **THEN** the RPC fails with gRPC status `UNAVAILABLE` (not an opaque INTERNAL error)

#### Scenario: Edge case — an out-of-range gRPC port fails fast
- **GIVEN** `hermesmq.grpc.port` configured as `70000`
- **WHEN** the service starts
- **THEN** it exits non-zero with a clear error naming the invalid setting and binds nothing

#### Scenario: Edge case — REST is unaffected by the gRPC surface
- **GIVEN** the gRPC endpoint is running
- **WHEN** a client uses the REST pull/ack endpoints
- **THEN** they behave exactly as before (gRPC is purely additive)

### Requirement: gRPC contract sourced from the Lexicon

The HermesMQ gRPC service definition SHALL be sourced from the shared Lexicon artifact — a
single, versioned source of truth — rather than a repo-local `.proto`. The server SHALL generate
its service stubs from a **pinned** Lexicon version, preserving the `hermesmq.v1` /
`me.cference.hermesmq.grpc` package so the API surface (RPC methods, messages, and status
mappings) is identical to the previous local definition. A version mismatch between the server
and the pinned contract SHALL surface as a build error, not a runtime failure.

#### Scenario: Server builds against the pinned Lexicon contract
- **GIVEN** the HermesMQ gRPC service published in the Lexicon at a pinned version
- **WHEN** the server module builds
- **THEN** it generates and implements the service from that artifact, with no repo-local `hermes.proto` present and no `PekkoGrpcPlugin` codegen running

#### Scenario: The API surface is unchanged by the move
- **GIVEN** the existing gRPC test suites (auth, handler unit, streaming, in-process HTTP/2 client)
- **WHEN** they run against the Lexicon-generated stubs
- **THEN** they pass unchanged — the RPC methods and message shapes are identical, proving the move is not a redesign

#### Scenario: Edge case — an incompatible contract fails the build
- **GIVEN** the server pinned to a Lexicon version incompatible with its implementation
- **WHEN** the module is compiled
- **THEN** the incompatibility is a build/type error, not a runtime surprise
