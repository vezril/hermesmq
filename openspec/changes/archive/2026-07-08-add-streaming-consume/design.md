## Context

Consuming is unary today: `PubSubService.Pull` leases up to `max` AVAILABLE messages and returns them; the client polls and acks via unary `Ack`. gRPC handlers run through metadata-aware power APIs (`PubSubPowerApi`) that authenticate and tenant-scope each call via `TenantScopedSubscriptionService`. `SubscriptionService.pull(id, max)` already performs the lease (deadline = now + configured ack deadline). Pekko Streams underpins the runtime. Server streaming is therefore an additive gRPC method that turns repeated leasing into a pushed `Source`, reusing the existing lease/ack/redelivery machinery unchanged.

## Goals / Non-Goals

**Goals:**
- A `StreamMessages` server-streaming RPC that pushes leased messages to a consumer as they become available.
- Backpressure: a slow consumer stops new leases (messages stay AVAILABLE) rather than being over-leased.
- Authenticate + tenant-scope like the unary calls; unknown subscription → `NOT_FOUND`; clean cancellation.
- Reuse leasing, unary `Ack`, and sweeper redelivery unchanged; unary `Pull` and REST untouched.
- TDD, including the streaming source in isolation.

**Non-Goals:**
- Bidirectional streaming (ack over the same stream) — noted as future.
- REST streaming/SSE, exactly-once, consumer groups / competing-consumer load balancing, per-message flow-control credits.

## Decisions

- **Server streaming only.** Proto: `rpc StreamMessages(StreamRequest) returns (stream PulledMessage)`, `StreamRequest { string subscription_id; int32 max; }` (`max` = per-lease batch; `0` → configured default). Ack stays on unary `Ack`, so the stream is push-only and redelivery via the sweeper is unchanged.
- **Demand-driven leasing via `Source.unfoldAsync`.** The source leases only when downstream demands, so backpressure naturally throttles leasing:
  ```
  Source.unfoldAsync(())(_ => pull(id, batch).flatMap {
    case None        => Future.successful(None)                 // subscription gone → complete
    case Some(Nil)   => after(pollInterval)(Some(((), Nil)))    // idle: wait, then re-check
    case Some(msgs)  => Future.successful(Some(((), msgs)))     // emit a batch
  }).mapConcat(identity)
  ```
  A slow consumer produces no demand → no `pull` → no lease. Idle polls are throttled by `pollInterval` (via `pekko.pattern.after`) so an empty subscription doesn't busy-loop.
- **Existence check up front.** Before streaming, probe with `pull(id, 0)`; `None` → the method returns `Source.failed(GrpcErrors.rejected(SubscriptionNotFound))` (→ `NOT_FOUND`). Otherwise `Source.futureSource(probe → leasingSource)`. A subscription deleted mid-stream completes the stream gracefully.
- **Power-API integration.** `StreamMessages` is added to `PubSubPowerApi`: authenticate from metadata (else `UNAUTHENTICATED`), build a per-call `TenantScopedSubscriptionService`, and return the tenant-scoped leasing source. No admin scope required (data-plane).
- **Config.** A separate `StreamConfig(pollInterval, batchSize)` under `hermesmq.grpc.stream` (defaults `1s`, `100`), fail-fast on non-positive — kept separate from `GrpcConfig` so its existing tests/shape are unchanged.
- **Cancellation.** Client cancel / disconnect cancels the `Source`, stopping `unfoldAsync`; already-leased-but-undelivered messages simply lapse their deadline and redeliver via the sweeper — consistent with at-least-once.

## Risks / Trade-offs

- **Over-leasing vs. round-trips (the `max`/batch knob).** `unfoldAsync` leases up to `batch` per step; if the consumer takes one then stalls, the rest are leased (invisible) but buffered — on cancel they lapse and redeliver. Large batches reduce round-trips but widen the over-lease window; `batch = 1` is tightest but chattiest. Mitigation: small default (`10`–`100`), documented; per-message credit-based flow control is a future refinement.
- **Idle poll latency.** With no messages, new arrivals are noticed within `pollInterval` (default `1s`) — a latency/So-load trade-off. A journal-tailing push (eventsByTag) would be lower-latency but couples the hot consume path to the read journal; polling-lease keeps it simple and reuses the exact unary semantics. Documented.
- **Long-lived calls.** Streams hold a connection and a lease cursor; many idle streams cost little (demand-driven, no timer per stream beyond the idle re-check). Standard gRPC keepalive/deadlines apply at the deployment.
- **pekko-grpc streaming + power API + auth** returns `Source[…]`; authentication/existence must resolve into the `Source` (via `Source.failed` / `futureSource`) rather than a thrown exception. Validated during apply against the generated streaming signature.
