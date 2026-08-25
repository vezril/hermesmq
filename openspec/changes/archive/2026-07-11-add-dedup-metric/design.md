## Context

Idempotent publish resolves a duplicate inside the Topic aggregate: `Topic.decide` returns `Right(Nil)` (no event) and the entity replies `CommandReply.Published(originalId, deduplicated = true)`. Because nothing is journaled on a duplicate, the existing metrics path — durable read models (`TopicStats.publishedTotal`) maintained by projections over `MessagePublished`, rendered by `observability/PrometheusText.render(...)` — cannot see dedup collapses. The publish **reply** is the only place a duplicate is observable, and both the gRPC (`PubSubGrpcService.publish`) and REST (`PubSubRoutes` pull... publish) handlers already pattern-match `Published(mid, deduplicated)`. The active-consumer gauge established the precedent for a small in-memory, per-node observability structure rendered at scrape time.

## Goals / Non-Goals

**Goals:**
- Expose `hermesmq_publish_deduplicated_total{topic}` so operators can see how often dedup fires.
- Keep it off the event-sourced path and cheap on publish (a counter bump on a duplicate only).

**Non-Goals:**
- A durable / cross-node aggregate count — per-node best-effort, consistent with the consumer gauge.
- Emitting a journaled event on a duplicate (that would defeat the "persist nothing on a dup" design and tax the journal on retries).
- Any config knob — the counter is always on and reads `0` until dedup fires.

## Decisions

**1. In-memory `DedupCounter`, incremented from the publish reply.**
A `DedupCounter` holds `Map[TopicId, Long]` and exposes `increment(topic)` and `counts`. The gRPC and REST publish handlers call `increment(topic)` exactly when the reply is `Published(_, deduplicated = true)`. Chosen over (a) a durable read model — there's no event to project, and a handler-driven DB write per duplicate mixes concerns and taxes the hot path; and (b) emitting a `PublishDeduplicated` event — that reintroduces a journal write on every retry, the opposite of dedup's intent. Thread-safe via an atomic swap of an immutable map (same as `ConsumerRegistry`).

**2. Render as a counter in `PrometheusText`.**
Add `hermesmq_publish_deduplicated_total{topic="…"}` with `# HELP`/`# TYPE … counter`, sourced by passing `counter.counts` into `render` (which already takes the active-consumer counts). Chosen over a separate endpoint or a background exporter: one Prometheus surface, computed lazily at scrape time.

**3. No config.**
Unlike the consumer registry (which needed an activity window), a counter needs no bound — it monotonically counts within a process. Prometheus handles the reset on restart. So no `ConsumersConfig`-style knob.

## Risks / Trade-offs

- **Per-node, resets on restart.** → A Prometheus counter is expected to reset on process restart (`rate()`/`increase()` handle it); per-node is exact under the single-replica deploy, and cluster-wide aggregation would be the same future extension as the consumer gauge — the metric name/meaning wouldn't change.
- **Unbounded distinct topics in the map.** → One `Long` per topic that has ever deduplicated in this process; negligible, and bounded by the topic count.
- **Two increment sites (gRPC + REST).** → Both already branch on the `Published` reply; the increment is one line at each, covered by a handler test per surface.

## Migration Plan

Additive: ship it; the metric appears reading `0` and rises as dedup fires. No data, config, or contract change; rollback is removing the metric (or redeploying the prior image).

## Open Questions

- None. Cross-node aggregation is the same deferred concern as the consumer gauge.
