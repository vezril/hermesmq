## MODIFIED Requirements

### Requirement: Prometheus metrics endpoint

The service SHALL expose `GET /metrics` in Prometheus text exposition format, emitting
gauges `hermesmq_subscription_backlog` and `hermesmq_subscription_oldest_unacked_age_seconds`
(labelled by subscription) and counters `hermesmq_messages_published_total` (labelled by
topic), `hermesmq_messages_redelivered_total`, and `hermesmq_messages_dead_lettered_total`
(labelled by subscription), each with `# HELP` and `# TYPE` lines. It SHALL additionally emit
the gauge `hermesmq_subscription_consumers` (labelled by subscription) reporting the number of
distinct named consumers active within the configured activity window, read from the in-memory
consumer registry, and the counter `hermesmq_publish_deduplicated_total` (labelled by topic)
reporting how many publishes were collapsed as duplicates, read from the in-memory dedup counter.
The endpoint SHALL read the stats read models (and the in-memory consumer registry and dedup
counter) and require no readiness gate.

#### Scenario: Metrics are exposed in Prometheus format
- **GIVEN** a subscription with a backlog and a topic with published messages
- **WHEN** a client scrapes `GET /metrics`
- **THEN** the response is `200` `text/plain` and contains `hermesmq_subscription_backlog{subscription="…"}` and `hermesmq_messages_published_total{topic="…"}` samples with `# TYPE` lines

#### Scenario: The active-consumer gauge reflects named consumers
- **GIVEN** a subscription being consumed by two named consumers within the activity window
- **WHEN** `/metrics` is scraped
- **THEN** it contains a `hermesmq_subscription_consumers{subscription="…"} 2` sample with `# HELP`/`# TYPE` lines

#### Scenario: The dedup counter reflects collapsed duplicate publishes
- **GIVEN** a topic for which two publishes have been collapsed as duplicates
- **WHEN** `/metrics` is scraped
- **THEN** it contains a `hermesmq_publish_deduplicated_total{topic="…"} 2` sample with `# HELP`/`# TYPE … counter` lines

#### Scenario: Edge case — label values are escaped
- **GIVEN** an id containing a character that must be escaped in a Prometheus label (e.g. a backslash or quote)
- **WHEN** `/metrics` is scraped
- **THEN** the label value is escaped so the exposition remains valid

#### Scenario: Edge case — no data yields a valid, sample-free exposition
- **GIVEN** no subscriptions or topics with activity
- **WHEN** `/metrics` is scraped
- **THEN** the response is `200` with `# TYPE` metadata and no samples (never a 5xx)
