## Why

Consumers can name themselves (named-consumers), but **producers are anonymous** — nothing on a publish identifies who is producing to a topic, so operators (and the Zeus admin UI's topic-detail view) can't see who's publishing or notice when a producer stops. This is the producer-side twin of named-consumers, completing the "who touches this topic" picture: subscribers on one side, producers on the other.

## What Changes

- Add an optional **producer id** to the publish surface (gRPC `PublishRequest.producer_id`, REST publish body `producerId`). Absent/empty = anonymous, i.e. today's behaviour (non-breaking).
- Maintain an in-memory **producer registry** per topic: each publish carrying a producer id "touches" it, and a producer counts as active while it has been seen within `hermesmq.producers.activity-window` (default 60s). No aggregate/state change — producer liveness is ephemeral, kept off the event-sourced path (mirrors the consumer registry).
- Expose a new gauge `hermesmq_topic_producers{topic="…"}` on `/metrics` (distinct producers active in the window). Anonymous producers are not counted.
- Put the producer id into the logging **MDC** for the duration of a publish call, so structured JSON logs (v1.7.0) carry a top-level `producer` field — publishes attributable per producer.
- New config `hermesmq.producers.activity-window` (Duration; `0` disables the registry/metric).
- Prerequisite: add `producer_id` to `PublishRequest` in the Hermes proto in **the-lexicon** and cut a new pinned `lexiconVersion`.

## Capabilities

### New Capabilities
- `producer-identity`: the optional producer id on the publish surfaces, the in-memory active-producer registry with its activity window, and the producer id in the log MDC.

### Modified Capabilities
- `observability`: add the `hermesmq_topic_producers` gauge to the Prometheus exposition (rendered from the active-producer registry).

## Impact

- **Contract** (the-lexicon): `producer_id` on `PublishRequest`; new `lexicon-hermes-grpc` release; bump `lexiconVersion` in `build.sbt`.
- **Server** (new `observability/ProducerRegistry.scala`; `grpc/PubSubGrpcService.scala` + `http/PubSubRoutes.scala` touch the registry and set MDC on publish; `observability/PrometheusText.scala` renders the new gauge; `observability/ObservabilityRoutes.scala` sources the registry; new `config/ProducersConfig.scala`; `Main.scala` wires it): the publish path gains a cheap, off-hot-path registry touch + metric.
- **Clustering caveat**: the registry is per-node (in-memory, best-effort liveness), so the count reflects producers publishing to *this* instance — exact under the single-replica deployment; cluster-wide aggregation is a future extension. Documented, not silently assumed (same as the consumer gauge).
- **No breaking changes**: the id is optional and inert when absent; existing tests and the anonymous publish path are unchanged.
