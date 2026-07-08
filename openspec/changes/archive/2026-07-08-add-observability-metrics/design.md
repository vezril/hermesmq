## Context

Delivery already runs Pekko Projections over the journal (`DeliveryProjection`, `SubscriptionIndexProjection`, `LeaseProjection`, `DeadLetterProjection`), each a single cluster-wide `ShardedDaemonProcess` with a JDBC offset store, feeding trait+InMemory+Jdbc read-model repositories. Routes are assembled in `Main` (`TopicAdminRoutes ~ PubSubRoutes`) and `HttpServer` prepends `HealthRoutes` (`/health`, `/health/ready`). Events carry tags: `topic-message` on `MessagePublished`, `subscription-created`, `subscription-lease`. `MessageDelivered` and topic lifecycle events are currently untagged. This feature adds stats projections in exactly the same shape.

## Goals / Non-Goals

**Goals:**
- Durable read models for per-subscription backlog, oldest-unacked-age, redelivery count, dead-letter count, and per-topic published throughput — maintained off the hot path.
- REST admin listings (`GET /v1/subscriptions`, `GET /v1/topics`) and a Prometheus `/metrics` endpoint.
- Correct counters under at-least-once projection replay.
- TDD; existing behavior and event schemas unchanged (new tags are additive).

**Non-Goals:**
- gRPC listing endpoints, time-series/history, dashboards, alerting, `/metrics` auth, or a metrics client dependency.
- Per-message audit; changing `decide`/`evolve`, delivery, or retention.

## Decisions

- **Two additive tags, two projections.** Add a `subscription-stats` tag to *all* subscription events and a `topic-stats` tag to `TopicCreated`/`MessagePublished`/`TopicDeleted` (additive `.withTagger` entries; existing projections unaffected). `SubscriptionStatsProjection` and `TopicStatsProjection` each run as one cluster-wide daemon.
- **Read models (trait + InMemory + Jdbc, established style).**
  - `subscription_backlog(subscription_id, ack_id, delivered_at)` — a row per outstanding message (insert on `MessageDelivered`, delete on `MessageAcknowledged`/`MessageDeadLettered`). Backlog = row count; oldest-unacked-age = `now − min(delivered_at)`, computed at read time.
  - `subscription_stats(subscription_id, topic_id, redelivered_total, dead_lettered_total)` — registered on `SubscriptionCreated`; `redelivered_total`++ on `AckDeadlineExpired`, `dead_lettered_total`++ on `MessageDeadLettered`.
  - `topic_stats(topic_id, published_total, deleted)` — registered on `TopicCreated`, `published_total`++ on `MessagePublished`, `deleted` on `TopicDeleted`.
- **Exactly-once for counters.** Counter increments are not idempotent, so the stats projections use `JdbcProjection.exactlyOnce` — the read-model write and the offset advance commit in the **same** `HermesJdbcSession` transaction, so an at-least-once replay cannot double-count. (Backlog row insert/delete are idempotent, but sharing the transaction keeps the whole handler correct.)
- **Prometheus text, hand-rolled.** A small `PrometheusText` renderer emits `# HELP`/`# TYPE` + samples with escaped `{label="…"}`; no client library. Gauges: `hermesmq_subscription_backlog`, `hermesmq_subscription_oldest_unacked_age_seconds`; counters: `hermesmq_messages_published_total` (per topic), `hermesmq_messages_redelivered_total`, `hermesmq_messages_dead_lettered_total` (per subscription). `now` is supplied at the boundary so the renderer is pure/testable.
- **Routes.** `MetricsRoutes` serves `GET /metrics` (`text/plain; version=0.0.4`); admin listings serve `GET /v1/subscriptions` and `GET /v1/topics` as JSON, reading the stats repos. Wired into `apiRoutes` in `Main`; `/metrics` needs no readiness gate.
- **Read model separation.** Stats repos are independent of the delivery read models, so observability never blocks or is blocked by the delivery path.

## Risks / Trade-offs

- **Eventual consistency.** Stats lag the write side by the projection's processing delay; acceptable for observability and admin, and explicitly not on the delivery correctness path.
- **Retention interaction.** Counters and backlog are maintained *forward*; because journal retention purges old events, these read models cannot be rebuilt from a from-zero replay once events are deleted. Documented; the projections run continuously from first start, so live counts stay accurate. (A from-scratch redeploy against an already-retained journal would under-count history — noted in README.)
- **exactlyOnce cost.** Committing offset + write in one transaction is slightly heavier than atLeastOnce, but is required for counter correctness and is well off the hot path.
- **oldest-unacked-age at read time.** Computing `now − min(delivered_at)` per scrape is a single indexed aggregate; cheap. A subscription with an empty backlog reports age `0`/absent rather than a stale value.
- **Cardinality.** One metric series per topic/subscription; fine at broker scale. If ids ever explode, the `/metrics` surface grows linearly — acceptable and standard for Prometheus.
