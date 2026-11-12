# Tasks

Test-first throughout, per the project's workflow: each group writes its failing tests before the
production code, and lands the tests and documentation its own work calls for.

## 1. Record the origin topic on dead-letter routing

- [x] 1.1 Add a failing `DeadLetterRouterSpec` case asserting the dead-letter envelope carries `x-original-topic` set to the origin topic, alongside the three existing `x-*` attributes; verify it fails for the right reason before touching production code
- [x] 1.2 Add `OriginalTopicHeader` to `DeadLetterRouter` and thread the origin topic into `deadLetterMessage`, resolving it from the subscription; verify 1.1 passes and the existing payload-byte and attribute-preservation cases still pass
- [x] 1.3 Write the `redelivery-timers` delta's new scenario as a test (origin topic recorded for self-contained replay) and verify `sbt "testOnly *DeadLetterRouterSpec"` is green

## 2. Dead-letter read model: schema and repository

- [x] 2.1 Add `subscription_dead_letters` (subscription id, original message id, origin topic, payload, attributes, attempt count, dead-lettered-at, redriven-at) to `server/src/main/resources/schema/postgres.sql` with `IF NOT EXISTS`; verify `SchemaMigrationIntegrationSpec` still passes against a fresh database via `sbt -Dit=true "testOnly *SchemaMigrationIntegrationSpec"`
- [x] 2.2 Write a failing repository spec covering record, list-by-subscription, lookup-by-id, mark-redriven, and the `ON CONFLICT DO NOTHING` re-record case; model it on the existing `TopicSubscriptionsRepository` tests
- [x] 2.3 Implement `DeadLetterRepository` with an in-memory and a JDBC implementation, mirroring the `TopicSubscriptionsRepository` split so unit tests need no database; verify 2.2 passes

## 3. Dead-letter read model: projection

- [x] 3.1 Write a failing projection spec asserting a `MessageDeadLettered` event on `SubscriptionEntity.LeaseTag` produces exactly one read-model row with payload, attempts and origin topic, and that reprocessing the same envelope is idempotent
- [x] 3.2 Add a failing case asserting a row is recorded **even when no dead-letter topic is configured** (the visibility improvement the spec requires)
- [x] 3.3 Implement `DeadLetterIndexProjection` over `LeaseTag`, modelled on `SubscriptionIndexProjection`, and register it as a cluster-wide singleton in `Main` alongside the existing projections; verify 3.1 and 3.2 pass
- [x] 3.4 N/A as written: this repo tests the event→read-model effect independent of the JDBC projection plumbing (see `LeaseProjectionSpec`), and there is no per-projection restart test to extend. Offset resume is Pekko Projection's own behaviour and is exercised by the PostgreSQL integration suite in 7.3

## 4. Browse endpoint

- [x] 4.1 Write failing route tests for `GET /v1/subscriptions/{id}/dead-letters` covering the populated list, the empty list as `200`, and `404` for an unknown subscription
- [x] 4.2 Add a failing test asserting browse is non-destructive and repeatable — two calls return the same message with an unchanged attempt count, and no lease is taken
- [x] 4.3 Add failing tenancy tests: only the caller's tenant's dead-letters are listed, and another tenant's subscription id is `404`
- [x] 4.4 Implement the route and its JSON codecs reading the repository; verify 4.1–4.3 pass
- [x] 4.5 Document the endpoint in the README's Observability section — request/response shape, eventual consistency, and that it works with no dead-letter topic configured; verify the documented curl runs as written against a local service

## 5. Redrive endpoint

- [x] 5.1 Write failing route tests for `POST /v1/subscriptions/{id}/dead-letters/redrive` covering redrive-by-ids and redrive-all, asserting republish to the origin topic with byte-identical payload
- [x] 5.2 Add failing tests for the per-id outcome partition: redriven, already-redriven, unknown, and failed-with-reason when the origin topic has been deleted — asserting one bad id does not fail the others
- [x] 5.3 Add a failing test that a redriven message is fanned out to the origin topic's current subscriptions like any new publish
- [x] 5.4 Add a failing test for the origin-topic fallback: a row lacking `x-original-topic` resolves the topic from the subscription and redrives successfully
- [x] 5.5 Implement the route, publishing through the Topic aggregate with idempotency key `redrive:<subscription>:<originalId>` and marking `redriven_at`; verify 5.1–5.4 pass
- [x] 5.6 Add failing tests for redrive idempotency: a repeat inside the dedup window publishes once and reports already-redriven, and two concurrent identical redrives collapse to one publish; verify they pass
- [x] 5.7 Document the endpoint in the README — request shape, the outcome partition, and explicitly that idempotency depends on `HERMESMQ_DEDUP_WINDOW` being non-zero; verify the documented curl runs as written

## 6. Auth and scope gating

- [x] 6.1 Write failing auth tests: browse succeeds with any valid tenant key, redrive requires the `admin` scope (`403` without it), and both return `401` with a missing or invalid key
- [x] 6.2 Wire both routes into the existing auth/scope directives so redrive sits behind `admin`; verify 6.1 passes
- [x] 6.3 Update the README's Authentication section's scope description to name redrive as an `admin`-scoped operation; verify the table matches the implemented behaviour

## 7. Integration verification

- [x] 7.1 Add an end-to-end test: publish, exhaust attempts to dead-letter, browse the result, redrive it, and assert it is delivered again to a live subscription on the origin topic
- [x] 7.2 Verify the full gate is green — `sbt clean "scalafixAll --check" test` — with no new `-Werror` warnings or `DisableSyntax` violations
- [x] 7.3 Closed differently than written: Docker was unavailable locally, so instead the suites now run in CI (`Integration (PostgreSQL)`, added in #116) and this change adds `DeadLetterRepositoryIntegrationSpec` plus a `subscription_dead_letters` assertion in `SchemaMigrationIntegrationSpec`, so the DDL and every JDBC statement are verified against a real PostgreSQL on every PR rather than by hand
- [x] 7.4 Update the README capability table with a dead-letter operations row and confirm every internal anchor added in this change resolves

## Workflow follow-up

- Open one pull request into `development` and merge once CI is green.
- Archive the change with `openspec archive add-dlq-operator-endpoints` after review, promoting the `dead-letter-operations` spec to canonical and folding the `redelivery-timers` delta into its main spec.
- Close issue #57 referencing the shipped endpoints, and note the two deferred items (per-subscription dead-letter topic config, browse pagination) as follow-ups.
