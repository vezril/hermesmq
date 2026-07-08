## Why

The architecture describes a query side that "consumes the event journal to maintain read models — subscription backlog and oldest-unacked-age, per-topic throughput, redelivery counts, admin listings — which serve the admin API and metrics without ever touching the hot delivery path." Today none of that exists: operators can create and consume, but cannot see how many messages are backlogged, how stale the oldest unacked message is, how much traffic a topic carries, or how often redelivery/dead-lettering fires. This is the last major architecture capability unbuilt, and it is what makes the broker operable in production.

## What Changes

- Add **Pekko Projections** that fold the event journal into durable read models, off the hot delivery path (mirroring the existing delivery/lease projections):
  - per-subscription **backlog** (outstanding count), **oldest-unacked-age**, **redelivery count**, **dead-letter count**;
  - per-topic **throughput** (messages published), plus topic/subscription existence for listings.
- Expose these two ways:
  - **REST admin listings** — `GET /v1/subscriptions` and `GET /v1/topics` returning each entity with its stats;
  - a **Prometheus `/metrics`** scrape endpoint in text exposition format (hand-rolled, dependency-light, matching the project style) exposing gauges (`hermesmq_subscription_backlog`, `hermesmq_subscription_oldest_unacked_age_seconds`) and counters (`hermesmq_messages_published_total`, `hermesmq_messages_redelivered_total`, `hermesmq_messages_dead_lettered_total`).
- Tag the currently-untagged events needed for stats (additively) so the new projections can consume them; existing projections and REST/gRPC behavior are unchanged.

## Capabilities

### New Capabilities
- `observability`: Query-side read models for broker health (per-subscription backlog, oldest-unacked-age, redelivery/dead-letter counts; per-topic throughput) maintained by projections, exposed via REST admin listing endpoints and a Prometheus `/metrics` endpoint. Eventually consistent, off the hot path.

### Modified Capabilities
<!-- None at the spec level: existing delivery/pub-sub/admin behavior is unchanged. New event tags are additive metadata, not requirement changes. -->

## Impact

- **Projections:** new `SubscriptionStatsProjection` (over a new `subscription-stats` tag on all subscription events) and `TopicStatsProjection` (over a `topic-stats` tag on topic lifecycle/publish events), each a single cluster-wide daemon alongside the existing projections.
- **Read models:** new durable tables — `subscription_backlog(subscription_id, ack_id, delivered_at)` (backlog count + oldest-unacked-age), `subscription_stats(subscription_id, topic_id, redelivered_total, dead_lettered_total)`, `topic_stats(topic_id, published_total, deleted)` — with trait + InMemory + Jdbc repositories in the established style.
- **HTTP:** new `MetricsRoutes` (`/metrics`, Prometheus text) and admin listing routes (`GET /v1/subscriptions`, `GET /v1/topics`); wired in `Main` next to the existing routes/health.
- **Entities:** additive `.withTagger` entries (tag `MessageDelivered` and topic lifecycle events for stats); no event-schema or `decide`/`evolve` change.
- **Schema/docs:** new tables in `schema/postgres.sql`; README capability row, metrics/endpoint docs, and a note that counts are maintained forward and are not rebuilt from a retention-purged journal.
- **Out of scope:** gRPC listing endpoints, historical/time-series storage, dashboards, alerting, and auth on `/metrics` (delegated to the deployment).
