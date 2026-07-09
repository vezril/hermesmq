## Why

v1.1.0's server-streaming `StreamMessages` pushes messages efficiently, but acknowledgement still costs a separate unary `Ack` round-trip per batch. A **bidirectional** stream lets a consumer receive messages and acknowledge them over the *same* long-lived call — the tightest, lowest-overhead consume loop, and the natural completion of the streaming work (explicitly noted as future in v1.1.0). It keeps the exact lease/redelivery semantics; only the transport for acks changes.

## What Changes

- Add a bidirectional RPC `Consume(stream ConsumeRequest) returns (stream PulledMessage)`. The client's first message opens the subscription (`ConsumeStart{subscription_id, max}`); subsequent messages acknowledge received ids (`ConsumeAck{ack_ids}`). The server streams leased messages back, applying acks as they arrive.
- Reuse the existing leasing/backpressure (`MessageStream`) for the outbound side and the existing `Acknowledge` command for inbound acks — no change to lease, redelivery, or the sweeper.
- Wire it through the metadata-aware power API so it authenticates and tenant-scopes like every other gRPC call; an unknown subscription fails `NOT_FOUND`, a non-`ConsumeStart` first message fails `INVALID_ARGUMENT`, and cancellation/disconnect stops both directions cleanly.
- Keep unary `Pull`/`Ack` and the server-streaming `StreamMessages` unchanged (this is an additive RPC).

## Capabilities

### New Capabilities
- `bidi-consume`: A bidirectional gRPC consume where the client opens a subscription and acknowledges messages over the same stream while the server pushes leased messages back — backpressured, authenticated, and tenant-scoped, preserving at-least-once lease/redelivery semantics.

### Modified Capabilities
<!-- None at the spec level: unary Pull/Ack, server-streaming StreamMessages, and REST are unchanged. Consume is an additive bidirectional RPC. -->

## Impact

- **Proto:** add `ConsumeRequest { oneof kind { ConsumeStart start; ConsumeAck ack } }`, `ConsumeStart { subscription_id, max }`, `ConsumeAck { ack_ids }`, and `rpc Consume(stream ConsumeRequest) returns (stream PulledMessage)`; regenerate stubs.
- **gRPC:** implement `consume(inbound): Source[PulledMessage, NotUsed]` in `PubSubGrpcService` — take the first inbound element as `ConsumeStart` (else fail `INVALID_ARGUMENT`), probe existence (`NOT_FOUND`), stream leased messages outbound, and drain the inbound tail of `ConsumeAck`s into `Acknowledge` commands concurrently, sharing the call lifecycle.
- **Power API:** add `consume(inbound, metadata)` to `PubSubServicePowerApi` — authenticate + tenant-scope, reusing `Authenticator`/`TenantScope`.
- **Ack pipeline:** a small reusable flow that maps inbound `ConsumeAck`s to `Acknowledge` submissions (bounded parallelism, per-ack errors logged, not fatal to the stream).
- **Docs:** README bidirectional consume subsection (protocol: first message is Start, then Acks; lifecycle/cancellation) and a capability row.
- **Out of scope:** consumer groups / competing consumers, per-message flow-control credits, `modifyAckDeadline` over the stream (stays unary), and REST streaming.
