# Tasks: add-snapshots-and-retention

TDD throughout — for each behavior write the failing test first (Red), implement to green,
then refactor. Run `sbt test` after each step. Order is dependency-first: snapshot
serialization → config → snapshot cadence & recovery → event retention → integration & docs.
Event schemas and `decide`/`evolve` stay unchanged the entire time.

## 1. Snapshot serialization for aggregate state

- [x] 1.1 (test) `SubscriptionState` round-trips via JSON — including outstanding messages in AVAILABLE and LEASED states with non-zero attempt counts
- [x] 1.2 (test) `TopicState` (with labels) round-trips via JSON
- [x] 1.3 (impl) Add `JsonFormats` for `LeaseState`, `Outstanding`, `SubscriptionState`, `TopicState` (reusing `Message`/`Instant` formats); tolerant reads for absent fields
- [x] 1.4 (test) The snapshot serializer handles the two state types by manifest and refuses unbound types
- [x] 1.5 (impl) Extend `DomainEventSerializer` (manifests `TopicState`/`SubscriptionState`, `toBinary`/`fromBinary`); add `serialization-bindings` for both state types in `application.conf`
- [x] 1.6 (test) Java serialization stays disabled: a state snapshot is serialized by the explicit serializer, an unbound Serializable is refused

## 2. Retention configuration (fail-fast)

- [x] 2.1 (test) Defaults: `snapshotEveryEvents = 100`, `keepNSnapshots = 2`; env overrides `HERMESMQ_SNAPSHOT_EVERY`/`HERMESMQ_SNAPSHOT_KEEP` parsed
- [x] 2.2 (test) A non-positive `snapshotEveryEvents` or `keepNSnapshots` yields a `ConfigError` (fail-fast), not an exception
- [x] 2.3 (impl) Add `RetentionConfig(snapshotEveryEvents, keepNSnapshots)` parser mirroring `ServiceConfig`; add the `hermesmq.retention` block to `application.conf`

## 3. Snapshot cadence & bounded recovery

- [x] 3.1 (test) After `snapshotEveryEvents` persisted events an aggregate stores a snapshot (via `EventSourcedBehaviorTestKit`)
- [x] 3.2 (test) A restart recovers state from the latest snapshot plus later events, equal to a full fold; recovery is transparent (same state with or without snapshots)
- [x] 3.3 (impl) Add `.withRetention(RetentionCriteria.snapshotEvery(n).withKeepNSnapshots(m).withDeleteEventsOnSnapshot)` to `TopicEntity`/`SubscriptionEntity`; thread `RetentionConfig` through their `apply` and `Main`
- [x] 3.4 (test) Edge: an aggregate with fewer than N events (no snapshot) still recovers correct state by full replay

## 4. Journal event retention

- [x] 4.1 (test) Crossing a snapshot boundary deletes journal events older than the retained window, and the entity still recovers correct current state
- [x] 4.2 (test) Edge: after deletion, recovery of current state is unchanged (retained snapshot + surviving events suffice)
- [x] 4.3 (test) Edge: a subscription that delivered/leased/acked many messages recovers with no outstanding messages and a bounded journal
- [x] 4.4 (refactor) Confirm retention wiring is shared/consistent across both entities

## 5. Integration & docs

- [x] 5.1 (test) End-to-end (testcontainers Postgres): drive an entity past a snapshot boundary, restart, and assert recovered state equals the pre-restart state with the journal bounded
- [x] 5.2 (docs) README: capability row for snapshots/retention (✅), `hermesmq.retention` config rows (`HERMESMQ_SNAPSHOT_EVERY`, `HERMESMQ_SNAPSHOT_KEEP`), and a note on the event-retention trade-off for read-model rebuilds
- [x] 5.3 (refactor) Final pass; `sbt test` green; `openspec validate add-snapshots-and-retention --strict` clean
