## Why

Today consuming means **polling**: a client repeatedly calls unary `Pull`, trading latency for request overhead — poll too slowly and messages sit idle, poll too fast and most calls return empty. gRPC's server streaming lets the broker **push** leased messages to the consumer as they become available, over a single long-lived call. It is the natural next capability on the now-stable gRPC surface and the leasing model, and it materially improves consumer latency and efficiency without changing delivery semantics.

## What Changes

- Add a server-streaming RPC `StreamMessages(StreamRequest) returns (stream PulledMessage)` to `PubSubService`.
- The server repeatedly **leases** the subscription's AVAILABLE messages (via the existing `SubscriptionService.pull`, honoring the ack deadline) and emits them downstream as a Pekko Stream `Source`, with **backpressure**: a slow consumer stops new leases (messages stay AVAILABLE) rather than being over-leased.
- Acknowledgement stays on the existing unary `Ack`; unacked streamed messages still redeliver via the sweeper — the stream is push-only.
- Wire it through the metadata-aware **power API** so it authenticates and tenant-scopes exactly like the other gRPC calls; an unknown subscription fails `NOT_FOUND`, and client cancellation cleanly stops the stream (leases lapse and redeliver).
- Keep unary `Pull` and all REST behavior unchanged.

## Capabilities

### New Capabilities
- `streaming-consume`: A server-streaming gRPC consume that pushes leased messages to a consumer as a backpressured stream, authenticated and tenant-scoped like the unary API, with acknowledgement over the existing unary `Ack` and at-least-once redelivery preserved.

### Modified Capabilities
<!-- None at the spec level: unary Pull/Ack and REST are unchanged. Streaming is an additive gRPC method over the existing services. -->

## Impact

- **Proto:** add `StreamRequest { subscription_id, max_in_flight?, ack_deadline_seconds? }` and the `StreamMessages` server-streaming method to `hermes.proto`; regenerate stubs.
- **gRPC:** implement streaming in `PubSubGrpcService` (returns a `Source[PulledMessage, NotUsed]` built from repeated leasing) and expose it via `PubSubServicePowerApi` (authenticate + tenant-scope, mapping an unknown subscription to `NOT_FOUND`).
- **Streaming source:** a small builder that turns `SubscriptionService.pull` into a demand-driven, backpressured `Source` (poll-and-lease on downstream demand, not a fixed timer), reused/tested independently.
- **Config:** a stream poll interval / batch size under `hermesmq.grpc` (sane defaults, fail-fast) governing how often an idle stream re-checks for new messages.
- **Docs:** README gRPC streaming subsection (semantics, ack-via-unary, backpressure, cancellation) and a capability row.
- **Out of scope:** bidirectional streaming (ack over the same stream — noted as future), REST streaming/SSE, exactly-once, and consumer groups / competing-consumer load balancing.
