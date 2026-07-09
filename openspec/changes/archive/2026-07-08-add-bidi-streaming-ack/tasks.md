# Tasks: add-bidi-streaming-ack

TDD throughout — for each behavior write the failing test first (Red), implement to green,
then refactor. Run `sbt test` after each step. Order is dependency-first: proto → ack drain →
gRPC handler → power API → wiring → integration & docs. Unary `Pull`/`Ack` and
server-streaming `StreamMessages` stay unchanged.

## 1. Protobuf contract

- [x] 1.1 (impl) Add `ConsumeStart { subscription_id, max }`, `ConsumeAck { ack_ids }`, `ConsumeRequest { oneof kind { start; ack } }`, and `rpc Consume(stream ConsumeRequest) returns (stream PulledMessage)`
- [x] 1.2 (impl) Regenerate; confirm the bidi method + power-API bidi signature (`Source[ConsumeRequest] => Source[PulledMessage]`) compile

## 2. Inbound ack drain

- [x] 2.1 (test) A flow that maps a stream of `ConsumeAck`s to `Acknowledge` submissions applies every id; an unknown id is non-fatal (logged, stream continues)
- [x] 2.2 (impl) An ack-drain builder over the inbound tail: `collect` acks → `mapAsync(parallelism)` `Acknowledge` → drop, errors logged

## 3. gRPC bidi handler (unary trait)

- [x] 3.1 (test) `consume`: first `ConsumeStart` opens the subscription and streams leased messages; a non-Start first message fails `INVALID_ARGUMENT`; unknown subscription fails `NOT_FOUND`
- [x] 3.2 (test) Backpressure/lifecycle: acks are processed independently of outbound demand; cancelling the returned source stops leasing and the ack drain
- [x] 3.3 (impl) Implement `consume(in): Source[PulledMessage, NotUsed]` via `prefixAndTail(1)` (Start extraction), existence probe + `Source.futureSource`/`Source.failed`, the outbound `MessageStream` source, and the independently-materialized ack drain

## 4. Power API (auth + tenancy)

- [x] 4.1 (test) The power-API `consume(in, metadata)` authenticates (missing/invalid → `UNAUTHENTICATED`) and tenant-scopes (isolated subscription and acks; unknown → `NOT_FOUND`)
- [x] 4.2 (impl) Add `consume` to `PubSubPowerApi`: authenticate from metadata, build the tenant-scoped service, return the tenant-scoped bidi source

## 5. Runtime wiring

- [x] 5.1 (impl) Confirm `Consume` is served by the existing power-API handler binding in `GrpcServer`/`Main` (no new bind); existing gRPC/REST wiring otherwise unchanged

## 6. Integration & docs

- [x] 6.1 (test) End-to-end over HTTP/2 with the generated client: open `Consume` with `ConsumeStart`, receive messages, send `ConsumeAck`, and assert the acked messages are removed; cancel and confirm clean shutdown
- [x] 6.2 (docs) README bidirectional consume subsection (protocol: first message Start then Acks, backpressure, fire-and-forget acks, cancellation) and a capability row (✅)
- [x] 6.3 (refactor) Final pass; `sbt test` green; `openspec validate add-bidi-streaming-ack --strict` clean
