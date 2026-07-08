# Tasks: add-grpc-api

TDD throughout — for each behavior write the failing test first (Red), implement to green,
then refactor. Run `sbt test` after each step. Order is dependency-first: build/codegen →
proto contract → config → error mapping → handlers → runtime wiring → integration → docs.
REST must stay green and unchanged the entire time.

## 1. Build: Pekko gRPC plugin & codegen

- [x] 1.1 Add `pekko-grpc-sbt-plugin` (version aligned to Pekko 1.1.x, Scala 3) to `project/plugins.sbt`
- [x] 1.2 Enable `PekkoGrpcPlugin` on the `server` module in `build.sbt`; confirm it coexists with `JavaAppPackaging`/`DockerPlugin`
- [x] 1.3 Add a minimal `server/src/main/protobuf/hermes.proto` (empty service) and verify `sbt server/compile` runs codegen and compiles
- [x] 1.4 Scope `-Wunused:all`/`-Werror` so generated sources don't fail the build (exclude managed/generated sources from linting)

## 2. Protobuf contract

- [x] 2.1 Define messages: Topic (id, labels), Message (id, payload bytes, attributes, publishTime), PulledMessage (ackId, message), and request/response envelopes
- [x] 2.2 Define `TopicAdminService` (CreateTopic, GetTopic, UpdateTopic, DeleteTopic) and `PubSubService` (Publish, CreateSubscription, Pull, Ack, ModifyAckDeadline)
- [x] 2.3 (impl) Regenerate and confirm the service traits + client stubs compile

## 3. Configuration (fail-fast)

- [x] 3.1 (test) Defaults: gRPC host `0.0.0.0`, port `8081`; env overrides `HERMESMQ_GRPC_HOST`/`HERMESMQ_GRPC_PORT` parsed
- [x] 3.2 (test) An out-of-range/invalid gRPC port yields a `ConfigError` (fail-fast), not an exception
- [x] 3.3 (impl) Add `GrpcConfig(host, port)` parser mirroring `ServiceConfig`; add the `hermesmq.grpc` block to `application.conf`

## 4. Rejection → gRPC status mapping

- [x] 4.1 (test) `TopicNotFound`/`SubscriptionNotFound`/`UnknownAckId` → `NOT_FOUND`; `*AlreadyExists` → `ALREADY_EXISTS`; validation → `INVALID_ARGUMENT`; service failure → `UNAVAILABLE`
- [x] 4.2 (impl) A pure `GrpcErrors` helper mapping `Rejection`/`ValidationError`/failure to `GrpcServiceException(Status)`

## 5. Topic admin gRPC handler (TDD)

- [x] 5.1 (test) CreateTopic/GetTopic/UpdateTopic/DeleteTopic delegate to a stub `TopicService` and return the mapped responses
- [x] 5.2 (test) Edge: duplicate create → `ALREADY_EXISTS`; missing topic get/update/delete → `NOT_FOUND`; blank id → `INVALID_ARGUMENT`
- [x] 5.3 (impl) Implement `TopicAdminGrpcService` over the generated trait, mapping proto ↔ domain
- [x] 5.4 (refactor) De-duplicate proto/domain conversions into small pure functions

## 6. Pub/sub gRPC handler (TDD)

- [x] 6.1 (test) Publish returns messageId; CreateSubscription succeeds; Pull leases + returns; Ack/ModifyAckDeadline report done/unknown
- [x] 6.2 (test) Edge: publish to missing topic → `NOT_FOUND`; empty payload → `INVALID_ARGUMENT`; duplicate subscription → `ALREADY_EXISTS`
- [x] 6.3 (test) Edge: pull on missing subscription → `NOT_FOUND`; unknown ackId reported without failing the batch
- [x] 6.4 (impl) Implement `PubSubGrpcService` over the generated trait, delegating to `SubscriptionService`/`TopicService` (pull leases via the existing service, same as REST)
- [x] 6.5 (refactor) Share the ack/modify batch-partition logic with the shape used by REST where practical

## 7. Runtime wiring (bind alongside REST)

- [x] 7.1 (impl) Combine the two service handlers (`ServiceHandler.concatOrNotFound`) and bind h2c via `Http().newServerAt(grpcHost, grpcPort).bind(handler)` with HTTP/2 enabled
- [x] 7.2 (impl) Start the gRPC bind from `Main` alongside REST; a bind failure logs and terminates like the REST bind; both stop cleanly on shutdown
- [x] 7.3 (impl) Thread `GrpcConfig` through the `Main` load-for-comprehension (fail-fast before binding anything)

## 8. Integration & docs

- [x] 8.1 (test) In-process end-to-end: bind the real handler on port 0, drive it with the generated client stub — create topic → publish → subscribe → pull(lease) → ack round-trip
- [x] 8.2 (test) Status propagation over the wire: a rejection surfaces to the client as the mapped gRPC status (e.g. duplicate → `ALREADY_EXISTS`)
- [x] 8.3 (test) REST regression: existing REST suite still green; a REST pull/ack works while gRPC is bound
- [x] 8.4 (docs) README: gRPC section (contract summary, h2c port, `HERMESMQ_GRPC_*`, a client snippet), flip the gRPC capability row to ✅
- [x] 8.5 (refactor) Final pass; `sbt test` green; `openspec validate add-grpc-api --strict` clean
