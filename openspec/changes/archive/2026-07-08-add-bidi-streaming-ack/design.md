## Context

`StreamMessages` (v1.1.0) is server-streaming: the server pushes leased messages via a `Source[PulledMessage]` built by `MessageStream.leased` (demand-driven `unfoldAsync` over `pull`), and the client acks separately with unary `Ack`. Handlers run through metadata-aware power APIs that authenticate + tenant-scope. Bidirectional consume reuses all of that — the outbound side is the *same* leasing source; only inbound acks move onto the stream.

## Goals / Non-Goals

**Goals:**
- A bidirectional `Consume(stream ConsumeRequest) returns (stream PulledMessage)`: client opens with `ConsumeStart`, then sends `ConsumeAck`s; server streams leased messages and applies acks as they arrive.
- Reuse leasing/backpressure and the `Acknowledge` command unchanged — same lease/redelivery/sweeper semantics.
- Authenticate + tenant-scope like every gRPC call; `NOT_FOUND` unknown subscription; `INVALID_ARGUMENT` if the first message isn't `ConsumeStart`; clean cancellation.
- TDD; unary `Pull`/`Ack` and server-streaming `StreamMessages` unchanged.

**Non-Goals:**
- Consumer groups / competing consumers, per-message credit flow-control, `modifyAckDeadline` over the stream, REST streaming.

## Decisions

- **Additive bidi RPC.** Proto: `ConsumeRequest { oneof kind { ConsumeStart start; ConsumeAck ack } }`, `ConsumeStart { subscription_id, max }`, `ConsumeAck { repeated ack_ids }`, `rpc Consume(stream ConsumeRequest) returns (stream PulledMessage)`. `StreamMessages` and unary `Pull`/`Ack` stay.
- **First message is `ConsumeStart`.** `consume(inbound)` uses `inbound.prefixAndTail(1)`: the first element must be `ConsumeStart` (else `Source.failed(INVALID_ARGUMENT)`); an empty inbound completes the stream. The subscription id/`max` come from it.
- **Existence probe up front.** After `Start`, `pull(id, 0)`; `None` → `Source.failed(NOT_FOUND)`, else `Source.futureSource(probe → outbound)` — identical to `StreamMessages`.
- **Outbound = the existing leasing source.** `MessageStream.leased(_ => pull(id, batch), batch, pollInterval).map(toProto)`. Backpressure, idle throttling, and completion-on-`None` are inherited unchanged.
- **Inbound acks drained on an INDEPENDENT stream.** The `prefixAndTail` tail of `ConsumeAck`s is materialized separately (`tail.collect(ack).mapAsync(p)(ackAll(id, _)).runWith(Sink.ignore)`), *not* merged into the outbound. This decouples ack processing from outbound demand — a client that stops reading messages but keeps acking is still processed (a naive `merge` would stall acks behind outbound demand). Lifecycle: when the client cancels/disconnects, pekko-grpc cancels the inbound `Source`, which cancels the tail and stops the ack drain; the outbound is cancelled in parallel — both directions stop cleanly. Per-ack failures are logged, never fatal to the stream (mirrors unary ack's non-fatal unknown-id handling).
- **Power API.** `consume(inbound, metadata)` authenticates (else `Source.failed(UNAUTHENTICATED)`) and delegates to a per-call tenant-scoped `PubSubGrpcService`; the scoped `SubscriptionService` qualifies the `Start` id and every ack automatically, so tenancy holds with no extra logic.

## Risks / Trade-offs

- **Two independently-materialized streams per call** (outbound messages + inbound ack drain). The lifecycle is tied through inbound/outbound cancellation, not a shared graph — simpler and it avoids the demand-coupling bug, but it relies on pekko-grpc cancelling the inbound `Source` on client disconnect (it does). Validated with a cancellation test.
- **No ack acknowledgement.** Acks are fire-and-forget over the stream (no per-ack response); a client wanting confirmation still uses unary `Ack`. Consistent with "push-only outbound, best-effort inbound" and at-least-once (an unprocessed ack just lets the message redeliver). Documented.
- **Ordering of Start vs. first ack.** The protocol requires `Start` first; acks before `Start` are impossible by construction (the first element *is* the start). A malformed first message fails `INVALID_ARGUMENT` before any leasing.
- **Ack parallelism.** `mapAsync` bounds concurrent `Acknowledge` submissions; too high floods the entity, too low lags acks under bursty clients. A small fixed parallelism (e.g. reuse a config value) is the default; tunable later if needed.
