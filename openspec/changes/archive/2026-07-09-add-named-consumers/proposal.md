## Why

A subscription is already a competing-consumer group — concurrent consumers load-balance via exclusive leases — but its consumers are **anonymous**: nothing on `Pull`/`StreamMessages`/`Consume` identifies who is consuming, so operators can't see how many workers are attached to a subscription or notice when they drop off. That fan-out health signal is exactly what the constellation's observability + self-healing loop wants next, and it's cheap: an optional consumer id plus one gauge.

## What Changes

- Add an optional **consumer id** to the consume operations (gRPC `PullRequest`, `StreamRequest`, `ConsumeStart`; REST pull). Absent/empty = anonymous, i.e. today's behaviour (non-breaking).
- Maintain an in-memory **active-consumer registry** per subscription: each consume call carrying a consumer id "touches" it, and a consumer counts as active while it has been seen within `hermesmq.consumers.activity-window` (default 60s). No aggregate/state change — consumer liveness is ephemeral, kept off the event-sourced path.
- Expose a new gauge `hermesmq_subscription_consumers{subscription="…"}` on `/metrics` (the count of distinct named consumers active in the window). Anonymous consumers are not counted.
- Put the consumer id into the logging **MDC** for the duration of a consume call, so structured JSON logs (v1.7.0) carry a top-level `consumer` field — attributable per worker.
- New config `hermesmq.consumers.activity-window` (Duration; `0` disables the registry/metric).
- Prerequisite: add `consumer_id` to the three consume messages in the Hermes proto in **the-lexicon** and cut a new pinned `lexiconVersion`.

## Capabilities

### New Capabilities
- `named-consumers`: the optional consumer id on the consume surfaces, the in-memory active-consumer registry with its activity window, and the consumer id in the log MDC.

### Modified Capabilities
- `observability`: add the `hermesmq_subscription_consumers` gauge to the Prometheus exposition (rendered from the active-consumer registry).

## Impact

- **Contract** (the-lexicon): `consumer_id` on `PullRequest` / `StreamRequest` / `ConsumeStart`; new `lexicon-hermes-grpc` release; bump `lexiconVersion` in `build.sbt`.
- **Server** (new `observability/ConsumerRegistry.scala`; `grpc/PubSubGrpcService.scala` + `http/PubSubRoutes.scala` touch the registry and set MDC on consume; `observability/PrometheusText.scala` renders the new gauge; `observability/ObservabilityRoutes.scala` sources the registry; new `config/ConsumersConfig.scala`; `Main.scala` wires it): consume paths gain a cheap, off-hot-path registry touch + metric.
- **Clustering caveat**: the registry is per-node (an in-memory, best-effort liveness view), so the count reflects consumers attached to *this* instance — exact under the single-replica deployment; cluster-wide aggregation is a future extension. Documented, not silently assumed.
- **No breaking changes**: the id is optional and inert when absent; the window defaults on but the metric simply reads 0 until named consumers connect; existing tests and the anonymous consume path are unchanged.
