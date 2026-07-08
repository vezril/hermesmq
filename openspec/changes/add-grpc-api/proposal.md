## Why

The architecture names **Pekko gRPC** as HermesMQ's intended primary service API, and the README already promises it "in a later feature." Today every operation is reachable only over the interim REST surface. A protobuf-defined, HTTP/2 gRPC API gives consumers a typed, streaming-capable, cross-language contract with generated stubs — the connector's canonical interface — while REST remains for humans and simple tooling.

## What Changes

- Introduce a `.proto` contract for topic administration and pub/sub, and generate Scala bindings with the Pekko gRPC sbt plugin.
- Implement gRPC service handlers that delegate to the **same** `TopicService` and `SubscriptionService` the REST routes use — no new domain logic, one write path.
- Bind a gRPC (HTTP/2) endpoint alongside the existing REST server, on its own configurable host/port, started from `Main`.
- Map domain `Rejection`s to gRPC status codes (e.g. `NOT_FOUND`, `ALREADY_EXISTS`, `INVALID_ARGUMENT`) and validation failures to `INVALID_ARGUMENT`.
- Keep the REST API working unchanged; gRPC is additive.
- Document the gRPC surface in the README and flip its capability row to done.

## Capabilities

### New Capabilities
- `grpc-api`: A Pekko gRPC (HTTP/2, protobuf) service exposing topic admin (create/get/update/delete) and pub/sub (publish, create subscription, pull-with-lease, ack, modify-ack-deadline), delegating to the existing topic/subscription services, with domain rejections mapped to gRPC status codes and its own configurable bind host/port.

### Modified Capabilities
<!-- None: REST behavior is unchanged; gRPC is a new, additive protocol surface over the existing services. -->

## Impact

- **Build:** add the Pekko gRPC sbt plugin (`project/plugins.sbt`) and enable it on the `server` module in `build.sbt`; add a `src/main/protobuf/hermes.proto`. Code generation runs at compile time.
- **Runtime:** `Main` binds a second endpoint (gRPC) via `Http().newServerAt(...).bind(handler)`; new `grpc` service handlers under `server/.../grpc`.
- **Config:** new `hermesmq.grpc` block (host, port, optional enable) with `HERMESMQ_GRPC_*` env overrides and fail-fast validation, extending the config layer.
- **Docs:** README gains a gRPC section (contract, port, example) and the capability table flips the gRPC row to ✅.
- **Tests:** gRPC handler/integration tests using a generated in-process client; REST suite untouched.
- No changes to the domain, persistence, delivery, or existing REST modules beyond wiring.
