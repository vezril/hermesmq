## Context

named-consumers gave the consume side identity (an optional `consumer_id`, an in-memory `ConsumerRegistry` keyed by subscription, a `hermesmq_subscription_consumers` gauge, and a `consumer` MDC field). The publish side has no equivalent: `PubSubGrpcService.publish` / `PubSubRoutes` read `topic_id`, payload, attributes, `ttl_seconds`, `idempotency_key` — no producer field — so publishes are anonymous. This change is the direct producer-side mirror. Metrics are still rendered by `observability/PrometheusText.render(...)` (which already takes consumer counts and dedup counts), and structured logging promotes MDC entries to top-level fields. The gRPC contract lives in the external `lexicon-hermes-grpc` artifact.

## Goals / Non-Goals

**Goals:**
- Let a producer optionally name itself on every publish; surface a per-topic **active-producer count** on `/metrics` and a `producer` field on publish logs.
- Keep it off the event-sourced hot path and non-breaking (anonymous = today).
- Match the named-consumers shape exactly, so the two read as one pattern.

**Non-Goals:**
- Storing producer identity in the `Message` or Topic aggregate — it's ephemeral telemetry, not part of the durable record (same call as consumers).
- Per-message producer attribution in the journal, or authn of producers (that's the auth/tenant layer).
- Cluster-wide producer aggregation — per-node, best-effort (exact under the single-replica deploy).

## Decisions

**1. Producer id as a typed proto field, not gRPC metadata.**
Add `producer_id` to `PublishRequest` (the-lexicon), read as `in.producerId` in the handler; REST publish body gains `producerId`. Chosen over a metadata header for the same reasons as `consumer_id`/`idempotency_key` — trivial to read, discoverable, consistent. One more `lexicon-hermes-grpc` release. Empty string = anonymous.

**2. In-memory `ProducerRegistry` keyed by topic, not a read model or aggregate state.**
`ProducerRegistry` holds `Map[TopicId, Map[String, Instant]]` (last-seen). `touch(topic, producer, now)` on each publish carrying an id; `activeCount(topic, now)` / `activeCountsByTopic(now)` count entries newer than `now - activityWindow`; stale entries pruned lazily. Byte-for-byte the `ConsumerRegistry` design with `TopicId` in place of `SubscriptionId`. Chosen over a durable read model (publish is hot; a DB write per publish for a best-effort liveness signal is wrong) and over aggregate state (would pollute the journal with transient identity).

**3. Touch on the publish call, regardless of the aggregate outcome.**
A producer that publishes is active whether the message is accepted, deduplicated, or even rejected — so `touch` happens once `topicId` parses and the request carries a `producer_id`, before/around the aggregate submit. (The dedup counter, by contrast, only increments on a *duplicate* reply — different signal, different point.)

**4. Render the gauge from the registry in `PrometheusText`; MDC `producer` around the publish.**
Add `hermesmq_topic_producers{topic="…"}` sourced from `registry.activeCountsByTopic(now)` at scrape time (mirrors the consumer gauge). Set `MDC.put("producer", id)` around the publish call and clear it after (best-effort, synchronous-scope), so publish logs carry a top-level `producer`.

**5. Config `hermesmq.producers.activity-window` (default 60s, `0` disables).**
Identical to `ConsumersConfig`: `0` disables the registry/metric, negative fails fast.

## Risks / Trade-offs

- **Per-node count under-reports in a multi-node cluster.** → Documented; exact under the single-replica deploy; cluster-wide aggregation is the same future extension as the consumer gauge (metric name/meaning stable).
- **Registry memory grows with distinct producer ids per topic.** → Bounded by active-window pruning; a pathological producer minting unique ids self-prunes.
- **Two touch sites (gRPC + REST publish).** → Both already parse `topicId` and the reply; the touch is one line at each, covered by a handler test per surface.

## Migration Plan

1. the-lexicon: add `producer_id` to `PublishRequest`, release `lexicon-hermes-grpc` (next SemVer), bump `lexiconVersion`.
2. Ship the server change; the metric appears (reading 0) and publish stays anonymous until producers send ids. Fully backward-compatible.
3. Rollback: set `activity-window = 0` (registry/metric inert) or redeploy the prior image; no persistent state to undo.

## Open Questions

- None. This is a deliberate mirror of named-consumers; the same deferred concern (cross-node aggregation) applies.
