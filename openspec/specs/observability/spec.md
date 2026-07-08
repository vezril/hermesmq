# observability Specification

## Purpose

Query-side read models for broker health — per-subscription backlog, oldest-unacked-age,
redelivery and dead-letter counts, and per-topic published throughput — maintained by
projections off the hot delivery path, and exposed via REST admin listings and a Prometheus
`/metrics` endpoint. Existing delivery, pub/sub, and admin behavior is unchanged.

## Requirements

### Requirement: Per-subscription stats read model

The service SHALL maintain, per subscription, a durable backlog (count of outstanding —
delivered-but-not-acknowledged — messages), the age of its oldest unacknowledged message,
a redelivery count, and a dead-letter count, folded from the subscription's events by a
projection off the hot delivery path. Acknowledging or dead-lettering a message SHALL
decrease the backlog; a redelivery (ack-deadline expiry) SHALL increment the redelivery
count without changing the backlog.

#### Scenario: Delivering and acknowledging updates the backlog
- **GIVEN** a subscription with an empty backlog
- **WHEN** three messages are delivered and one is acknowledged
- **THEN** the subscription's backlog reads `2`

#### Scenario: Oldest-unacked-age reflects the oldest outstanding message
- **GIVEN** a subscription whose oldest outstanding message was delivered at time `T`
- **WHEN** the stats are read at time `now`
- **THEN** the oldest-unacked-age is `now − T`

#### Scenario: Redelivery and dead-letter counts accrue
- **GIVEN** a subscription
- **WHEN** a message's ack deadline expires (redelivery) and later another message is dead-lettered
- **THEN** the redelivery count is at least `1` and the dead-letter count is at least `1`

#### Scenario: Edge case — an empty backlog reports zero age, not a stale value
- **GIVEN** a subscription whose messages have all been acknowledged
- **WHEN** its stats are read
- **THEN** the backlog is `0` and the oldest-unacked-age is `0` (or absent), not the age of a past message

#### Scenario: Edge case — a redelivery does not change the backlog
- **GIVEN** a subscription with one outstanding leased message
- **WHEN** that lease expires and the message returns to available (a redelivery)
- **THEN** the backlog is still `1` and the redelivery count increased by one

### Requirement: Per-topic throughput read model

The service SHALL maintain, per topic, a durable count of messages published to it, folded
from `MessagePublished` events by a projection. A topic SHALL be registered on creation so
it appears in listings with a zero count before any publish, and marked deleted on deletion.

#### Scenario: Publishing increments the topic's published count
- **GIVEN** an existing topic with a published count of `0`
- **WHEN** two messages are published to it
- **THEN** its published count reads `2`

#### Scenario: A newly created topic appears with a zero count
- **GIVEN** a topic just created with no messages
- **WHEN** the topic stats are read
- **THEN** the topic is listed with a published count of `0`

#### Scenario: Edge case — counts are not double-counted on projection replay
- **GIVEN** a topic with a published count of `N`
- **WHEN** the stats projection reprocesses an already-handled `MessagePublished` (at-least-once replay)
- **THEN** the published count stays `N` (the increment and offset commit atomically)

### Requirement: REST admin listing endpoints

The service SHALL expose `GET /v1/subscriptions` and `GET /v1/topics` returning each
subscription (with its topic, backlog, oldest-unacked-age, redelivery and dead-letter
counts) and each topic (with its published count), as JSON. These listings SHALL read the
stats read models and never touch the hot delivery path.

#### Scenario: Listing subscriptions returns their stats
- **GIVEN** a subscription with a known backlog and counts
- **WHEN** a client calls `GET /v1/subscriptions`
- **THEN** the response is `200` and includes that subscription with its backlog, oldest-unacked-age, and counts

#### Scenario: Listing topics returns their published counts
- **GIVEN** two topics with known published counts
- **WHEN** a client calls `GET /v1/topics`
- **THEN** the response is `200` and lists both topics with their counts

#### Scenario: Edge case — empty listings return an empty array, not an error
- **GIVEN** no topics or subscriptions exist
- **WHEN** a client calls the listing endpoints
- **THEN** each returns `200` with an empty list

### Requirement: Prometheus metrics endpoint

The service SHALL expose `GET /metrics` in Prometheus text exposition format, emitting
gauges `hermesmq_subscription_backlog` and `hermesmq_subscription_oldest_unacked_age_seconds`
(labelled by subscription) and counters `hermesmq_messages_published_total` (labelled by
topic), `hermesmq_messages_redelivered_total`, and `hermesmq_messages_dead_lettered_total`
(labelled by subscription), each with `# HELP` and `# TYPE` lines. The endpoint SHALL read
the stats read models and require no readiness gate.

#### Scenario: Metrics are exposed in Prometheus format
- **GIVEN** a subscription with a backlog and a topic with published messages
- **WHEN** a client scrapes `GET /metrics`
- **THEN** the response is `200` `text/plain` and contains `hermesmq_subscription_backlog{subscription="…"}` and `hermesmq_messages_published_total{topic="…"}` samples with `# TYPE` lines

#### Scenario: Edge case — label values are escaped
- **GIVEN** an id containing a character that must be escaped in a Prometheus label (e.g. a backslash or quote)
- **WHEN** `/metrics` is scraped
- **THEN** the label value is escaped so the exposition remains valid

#### Scenario: Edge case — no data yields a valid, sample-free exposition
- **GIVEN** no subscriptions or topics with activity
- **WHEN** `/metrics` is scraped
- **THEN** the response is `200` with `# TYPE` metadata and no samples (never a 5xx)
