## Context

Every HermesMQ operation is currently reachable only over REST (`PubSubRoutes`, `TopicAdminRoutes`), which delegate to `TopicService`/`SubscriptionService` (cluster-sharded entity fronts). The architecture designates Pekko gRPC as the primary API. The server is a Pekko HTTP app started in `Main`; it already binds one HTTP/1.1 endpoint. We add a second, HTTP/2 gRPC endpoint over the *same* services — no new write path — so the two protocols stay behaviorally identical by construction.

## Goals / Non-Goals

**Goals:**
- A protobuf contract (`hermes.proto`) for topic admin + pub/sub, generating Scala stubs via the Pekko gRPC sbt plugin.
- gRPC handlers delegating to the existing `TopicService`/`SubscriptionService`, with `Rejection` → gRPC `Status` mapping.
- A separately configurable gRPC bind (own host/port), started alongside REST, fail-fast on bad config.
- REST left byte-for-byte unchanged; gRPC is additive.
- TDD: handler behavior and an in-process client round-trip, covering rejection→status mapping edge cases.

**Non-Goals:**
- Server-streaming pull / long-poll (unary pull-with-lease only for now; streaming is a later feature).
- TLS/mTLS termination (plaintext h2c behind a proxy, mirroring how REST is deployed); auth/authz.
- Removing or changing REST; changing domain, persistence, delivery, or the lease/redelivery semantics.
- A gRPC surface for internal cluster traffic (this is the external client API only).

## Decisions

- **Plugin & codegen.** Enable `PekkoGrpcPlugin` on the `server` module and add `pekko-grpc-sbt-plugin` to `project/plugins.sbt`. Proto lives at `server/src/main/protobuf/hermes.proto`; stubs (messages + `*Handler` + client) are generated at compile time. Pin the Pekko gRPC version compatible with Pekko 1.1.x and Scala 3 during apply, and confirm codegen coexists with `DockerPlugin`.
- **Two endpoints, two ports.** REST keeps `hermesmq.http` (8080, HTTP/1.1). gRPC gets `hermesmq.grpc` (default 8081) served as **h2c** (plaintext HTTP/2) via `Http().newServerAt(host, port).bind(handler)` with HTTP/2 enabled. Both bind from `Main`; a gRPC bind failure logs and terminates like the REST one.
- **Handlers delegate, never re-implement.** `TopicAdminGrpcService` and `PubSubGrpcService` implement the generated service traits by calling the same `TopicService`/`SubscriptionService`. Request/response protobuf messages are mapped to/from domain types at the boundary, exactly as the REST routes map JSON.
- **Error mapping.** A single helper maps `Rejection` → `io.grpc.Status`: `*NotFound`/`UnknownAckId` → `NOT_FOUND`; `*AlreadyExists` → `ALREADY_EXISTS`; validation (`ValidationError`) → `INVALID_ARGUMENT`; service/`Future` failure → `UNAVAILABLE`. Handlers fail their `Future` with `GrpcServiceException(status)`. Batch operations (ack, modifyAckDeadline) mirror REST: partition into done/unknown in the response, never fail the RPC for an unknown id.
- **Pull parity.** gRPC `Pull` is unary and leases exactly like REST pull (`now + ackDeadline`, per-request max), returning the leased messages; an unknown subscription → `NOT_FOUND`.
- **Config.** New `GrpcConfig(host, port)` parsed with the existing `ConfigError` fail-fast style (`HERMESMQ_GRPC_HOST`/`HERMESMQ_GRPC_PORT`), validated for port range, wired into the `Main` load-for-comprehension.
- **Testing.** Handler-level tests call handler methods with stub services (as `PubSubRoutesSpec` does) to assert mapping/edge cases without a socket. One integration test binds the real handler on port 0 and drives it with the generated client stub to prove end-to-end HTTP/2 wiring and status propagation.

## Risks / Trade-offs

- **Pekko gRPC + Scala 3 + sbt codegen** is the main integration risk (plugin/runtime version alignment with Pekko 1.1.x, generated-source compilation under `-Werror -Wunused:all`). Mitigation: pin versions early, and scope `-Wunused` off generated sources if needed (codegen output is not our code to lint).
- **h2c (plaintext HTTP/2)** requires clients to opt into prior-knowledge H2; documented in the README. TLS is delegated to the deployment proxy, consistent with REST.
- **Two bind points** widen the runtime surface (a port that can fail to bind, a second thing to shut down cleanly). Mitigation: bind/terminate both in `Main` under the existing lifecycle; a gRPC bind failure is fatal like REST.
- **Contract duplication** (proto mirrors JSON models): drift risk between the two surfaces. Mitigation: both delegate to one service layer, and acceptance scenarios assert equivalent behavior, so semantics can't diverge even though wire formats differ.
