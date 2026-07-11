## Why

Idempotent publish (v1.5.0) silently collapses a retried publish to the original, but that collapse is **invisible to operators** — there's no signal for how often dedup is firing, so you can't tell whether producers are retrying heavily or whether the window is sized right. A per-topic counter closes that gap and finishes the one deferred item from the idempotent-publish design.

## What Changes

- Add a Prometheus counter `hermesmq_publish_deduplicated_total{topic="…"}`, incremented each time a publish is collapsed as a duplicate.
- Because a deduplicated publish **emits no event** (the Topic aggregate returns `Right(Nil)`), the count is derived from the publish **reply** (`Published(_, deduplicated = true)`), not from a projection over the journal. It's held in a small in-memory, per-node counter (the same shape as the consumer registry) and rendered at scrape time.
- Increment in both publish surfaces (gRPC and REST) when the aggregate reports a duplicate.
- No config, no aggregate/state change, no new proto — purely additive observability.

## Capabilities

### New Capabilities
_None._

### Modified Capabilities
- `observability`: add the `hermesmq_publish_deduplicated_total` counter to the Prometheus exposition, sourced from an in-memory per-node dedup counter.

## Impact

- **Server** (new `observability/DedupCounter.scala`; `grpc/PubSubGrpcService.scala` + `http/PubSubRoutes.scala` increment on a duplicate reply; `observability/PrometheusText.scala` renders the counter; `observability/ObservabilityRoutes.scala` sources it; `Main.scala` wires a shared instance).
- **Per-node caveat**: like the active-consumer gauge, the counter is in-memory and per node (resets on restart — Prometheus handles counter resets), so it reflects duplicates seen by *this* instance — exact under the single-replica deployment. Documented, not silently assumed.
- **No breaking changes**: the metric reads `0` until dedup fires; existing behaviour, tests, and the publish path (beyond a cheap counter bump on a dup) are unchanged.
