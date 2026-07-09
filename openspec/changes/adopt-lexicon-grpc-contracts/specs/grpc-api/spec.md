# grpc-api

Records that the HermesMQ gRPC service contract is sourced from the Lexicon rather than a
repo-local `.proto`. The RPC methods, message shapes, and status mappings are unchanged — only
the definition's home and build provenance move.

## ADDED Requirements

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
