# Tasks

Test-first. Each group lands the tests and documentation its own work calls for.

## 1. Start position in the domain

- [x] 1.1 Write failing `SubscriptionSpec` cases: `CreateSubscription` emits `SubscriptionCreated` carrying the start position, and defaults to `now` when none is given
- [x] 1.2 Add a `StartPosition` enum (`Now`, `Earliest`) to the domain, thread it through `CreateSubscription` and `SubscriptionCreated` with a `Now` default; verify 1.1 passes and existing subscription tests still pass
- [x] 1.3 Write a failing serialization case: a `SubscriptionCreated` JSON payload with no start-position field deserializes as `Now` (an event journaled before this change)
- [x] 1.4 Implement the lenient decoder in `JsonFormats`; verify 1.3 passes and round-tripping a new event preserves `Earliest`

## 2. Backfill marker

- [x] 2.1 Add `subscription_backfills` to `schema/postgres.sql` with `IF NOT EXISTS`, and assert it in `SchemaMigrationIntegrationSpec`
- [x] 2.2 Write a failing repository spec: mark-complete, is-complete, and marking twice being harmless
- [x] 2.3 Implement `BackfillMarkerRepository` with in-memory and JDBC implementations, mirroring the existing repository split; verify 2.2 passes
- [x] 2.4 Covered by the `Integration (PostgreSQL)` CI job via the schema assertion; a dedicated marker PostgresIT case was NOT added (Docker unavailable locally, and the JDBC marker mirrors the already-covered repository pattern). Noted as the one thinner spot

## 3. The backfill itself

- [x] 3.1 Write a failing spec for the backfill effect: given a topic journal holding three `MessagePublished` events, it issues `RecordDelivery` for each to the new subscription, with keys matching `DeliveryHandler.ackIdFor`
- [x] 3.2 Add failing cases for the skips — an expired message is not delivered, and an empty or absent journal delivers nothing without error
- [x] 3.3 Add a failing case that a completed backfill is not re-run, and one that a subscription whose consumer has acked everything receives nothing on a repeat trigger (the hazard this guard exists for)
- [x] 3.4 Implement the backfill reader over `currentEventsByPersistenceId`, filtering `MessagePublished`, skipping expired messages, marking complete afterwards; verify 3.1–3.3 pass
- [x] 3.5 Wire it into `SubscriptionIndexProjection`'s handling of `SubscriptionCreated`, triggered only for `Earliest`; verify the index behaviour is unchanged for `Now`

## 4. REST surface

- [x] 4.1 Write failing route tests: `POST /v1/subscriptions` accepts `startFrom: "earliest"`, accepts `"now"`, defaults to `now` when absent, and rejects an unrecognised value with `400`
- [x] 4.2 Implement the field in `PubSubRoutes` and its JSON codec; verify 4.1 passes
- [x] 4.3 Document the option in the README's Publish & Consume section — including that `earliest` means *earliest still retained*, and that the backfill is asynchronous; verify the documented curl runs as written
- [x] 4.4 Update the roadmap: remove the back-delivery row, and note gRPC parity as pending a Lexicon change

## 5. Integration verification

- [x] 5.1 Add an end-to-end test: publish three messages, create an `earliest` subscription, and assert all three become pullable with correct payloads
- [x] 5.2 Add an end-to-end test that a `now` subscription created against the same topic receives none of them
- [x] 5.3 Verify the full gate: `sbt clean "scalafixAll --check" test` green with no new `-Werror` or `DisableSyntax` violations
- [x] 5.4 Delegated to the `Integration (PostgreSQL)` CI job (Docker unavailable locally). Original: Confirm the PostgresIT suites pass, or that the `Integration (PostgreSQL)` CI job covers them if Docker is unavailable locally
- [x] 5.5 Confirm every internal README anchor added or changed here resolves

## Workflow follow-up

- Open one pull request into `development` and merge once CI is green.
- Archive with `openspec archive add-subscription-back-delivery`.
- Open a follow-up issue for gRPC parity: a `start_from` field on `CreateSubscriptionRequest` in the Lexicon, then a pin bump here.
- Release as a minor bump.
