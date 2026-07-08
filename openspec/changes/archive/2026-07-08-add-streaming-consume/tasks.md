# Tasks: add-streaming-consume

TDD throughout — for each behavior write the failing test first (Red), implement to green,
then refactor. Run `sbt test` after each step. Order is dependency-first: proto → streaming
source → config → gRPC handler (unary trait) → power API (auth/tenancy) → wiring → integration
& docs. Unary `Pull`, `Ack`, and all REST behavior stay unchanged.

## 1. Protobuf contract

- [x] 1.1 (impl) Add `StreamRequest { subscription_id, max }` and `rpc StreamMessages(StreamRequest) returns (stream PulledMessage)` to `hermes.proto`
- [x] 1.2 (impl) Regenerate; confirm the streaming method + power-API streaming signature (`Source[PulledMessage, NotUsed]`) compile

## 2. Demand-driven leasing source

- [x] 2.1 (test) A `Source` built from `SubscriptionService.pull` emits leased messages, emits nothing (without erroring) when empty, and completes when the subscription is gone
- [x] 2.2 (test) Backpressure: with a slow/limited downstream, only a bounded number ahead of demand are pulled (leased) — the rest are not requested
- [x] 2.3 (test) Edge: an empty subscription re-checks on the poll interval rather than busy-looping; cancellation stops further pulls
- [x] 2.4 (impl) A `MessageStream` builder: `unfoldAsync` over `pull(id, batch)` with idle `pollInterval` throttling via `pekko.pattern.after`, `mapConcat` to per-message

## 3. Streaming configuration (fail-fast)

- [x] 3.1 (test) Defaults: poll interval `1s`, batch size `100`; env overrides parsed; non-positive values fail fast
- [x] 3.2 (impl) `StreamConfig(pollInterval, batchSize)` parser + `hermesmq.grpc.stream` block (`HERMESMQ_STREAM_*`), separate from `GrpcConfig`

## 4. gRPC streaming handler (unary trait)

- [x] 4.1 (test) `PubSubGrpcService.streamMessages` returns a source of the subscription's leased messages; an unknown subscription yields a `NOT_FOUND`-failed source
- [x] 4.2 (impl) Implement `streamMessages(in): Source[PulledMessage, NotUsed]` via the existence probe (`pull(id,0)`) + `Source.futureSource`/`Source.failed` + the `MessageStream` builder

## 5. Power API (auth + tenancy)

- [x] 5.1 (test) The power-API `streamMessages(in, metadata)` authenticates (missing/invalid → `UNAUTHENTICATED`) and tenant-scopes (isolated subscription; unknown → `NOT_FOUND`)
- [x] 5.2 (impl) Add `streamMessages` to `PubSubPowerApi`: authenticate from metadata, build the tenant-scoped service, return the tenant-scoped source

## 6. Runtime wiring

- [x] 6.1 (impl) Thread `StreamConfig` into `PubSubGrpcService`/`PubSubPowerApi` in `Main`; confirm the existing gRPC/REST wiring is otherwise unchanged

## 7. Integration & docs

- [x] 7.1 (test) End-to-end over HTTP/2 with the generated client: open `StreamMessages`, publish/deliver messages, assert they arrive on the stream (leased); cancel and confirm clean shutdown
- [x] 7.2 (docs) README gRPC streaming subsection (semantics, ack via unary `Ack`, backpressure, cancellation, poll interval/batch config) and a capability row (✅); note bidirectional streaming as future
- [x] 7.3 (refactor) Final pass; `sbt test` green; `openspec validate add-streaming-consume --strict` clean
