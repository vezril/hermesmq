# Tasks: add-observability-metrics

TDD throughout — for each behavior write the failing test first (Red), implement to green,
then refactor. Run `sbt test` after each step. Order is dependency-first: read-model repos →
event tags → projections → Prometheus renderer → routes (metrics + listings) → wiring →
integration & docs. Existing delivery/pub-sub/admin behavior and event schemas stay unchanged.

## 1. Stats read-model repositories

- [x] 1.1 (test) Subscription stats repo: delivered adds to backlog, ack/dead-letter removes; backlog count and oldest-unacked (min delivered_at) computed; redelivery/dead-letter counters accrue
- [x] 1.2 (test) Topic stats repo: register-on-create (count 0), increment-on-publish, mark-deleted; list returns all
- [x] 1.3 (impl) `SubscriptionStatsRepository` and `TopicStatsRepository` (trait + InMemory + Jdbc) in the established style; add `subscription_backlog`, `subscription_stats`, `topic_stats` tables to `schema/postgres.sql`

## 2. Additive event tags for stats

- [x] 2.1 (test) Tagging: all subscription events carry a `subscription-stats` tag; `TopicCreated`/`MessagePublished`/`TopicDeleted` carry a `topic-stats` tag; existing tags are unchanged
- [x] 2.2 (impl) Add the two tags via additive `.withTagger` entries on `SubscriptionEntity`/`TopicEntity` (no event-schema change)

## 3. Stats projections (exactly-once)

- [x] 3.1 (test) The pure per-event effect updates the subscription stats read model (backlog +/-, redelivery/dead-letter counters, register on create)
- [x] 3.2 (test) The pure per-event effect updates the topic stats read model (register, publish++, deleted)
- [x] 3.3 (impl) `SubscriptionStatsProjection` and `TopicStatsProjection` using `JdbcProjection.exactlyOnce` (write + offset in one transaction, so counters don't double-count on replay)

## 4. Prometheus text renderer

- [x] 4.1 (test) Renders gauges/counters with `# HELP`/`# TYPE` and `{label="…"}` samples from a stats snapshot at a given `now`
- [x] 4.2 (test) Edge: label values are escaped (backslash/quote/newline); empty input yields TYPE metadata with no samples
- [x] 4.3 (impl) A pure `PrometheusText` renderer (no client dependency)

## 5. HTTP endpoints

- [x] 5.1 (test) `GET /metrics` returns `200` `text/plain` with the expected metric names/samples; no readiness gate
- [x] 5.2 (test) `GET /v1/subscriptions` and `GET /v1/topics` return `200` JSON with stats; empty listings return `[]` (not an error)
- [x] 5.3 (impl) `MetricsRoutes` (`/metrics`) and admin listing routes reading the stats repos

## 6. Runtime wiring

- [x] 6.1 (impl) Instantiate the Jdbc stats repos; run both stats projections as single cluster-wide `ShardedDaemonProcess`es in `Main`
- [x] 6.2 (impl) Add `MetricsRoutes` + listing routes to `apiRoutes`; confirm existing routes/health unaffected

## 7. Integration & docs

- [x] 7.1 (test) End-to-end (testcontainers Postgres): publish + deliver + ack + expire + dead-letter, then assert the listing endpoints and `/metrics` reflect the resulting backlog/age/counts/throughput
- [x] 7.2 (docs) README: capability row (✅), `/metrics` + admin listing endpoints, metric names table, and a note that counts are maintained forward (not rebuilt from a retention-purged journal)
- [x] 7.3 (refactor) Final pass; `sbt test` green; `openspec validate add-observability-metrics --strict` clean
