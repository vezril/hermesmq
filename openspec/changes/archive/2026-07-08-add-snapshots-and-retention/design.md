## Context

`TopicEntity` and `SubscriptionEntity` are `EventSourcedBehavior`s that persist events via `jdbc-journal` and already declare `jdbc-snapshot-store` (with a `snapshot` table), but never snapshot. Events serialize explicitly through `DomainEventSerializer` (a `SerializerWithStringManifest` over `TopicEvent`/`SubscriptionEvent`); Java serialization is off. So recovery replays a full history and the journal grows forever — and a subscription accrues delivery/lease/expire/ack events per message. `decide`/`evolve` are pure, so the folded state is a faithful checkpoint.

## Goals / Non-Goals

**Goals:**
- Periodic snapshots on both aggregates so recovery restores the latest snapshot and replays only later events.
- Journal event retention (delete-on-snapshot) so old events are purged once captured.
- Explicit snapshot serialization for `TopicState`/`SubscriptionState` (Java stays off), round-trip tested.
- Configurable, validated retention parameters with sane defaults.
- Transparency: a snapshot-restore + replay yields exactly the state a full replay would.

**Non-Goals:**
- Changing event schemas, `decide`/`evolve`, or any externally observable behavior.
- Snapshotting the read-model projections (they keep their own JDBC offsets).
- Rebuilding read models from a fully-retained journal (see risk below) or tuning per-entity retention.
- Compaction/vacuum of the underlying DB.

## Decisions

- **Retention criteria.** Both entities gain `.withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = N, keepNSnapshots = M).withDeleteEventsOnSnapshot)`, `N`/`M` from config. This snapshots every `N` persisted events, keeps the last `M` snapshots, and deletes journal events older than the retained window.
- **Snapshot serialization reuses the explicit path.** Extend `DomainEventSerializer` (or a sibling with the same JSON approach) to also handle `TopicState` and `SubscriptionState` — new manifests `TopicState`/`SubscriptionState`, `toBinary` via spray-json, `fromBinary` via `convertTo`. Add `serialization-bindings` for both state types. This needs new `JsonFormats` for `TopicState`, `SubscriptionState`, `Outstanding`, and `LeaseState` (reusing the existing `Message`/`Instant` formats). Reads are tolerant (defaulting absent fields) so a state shape can evolve without orphaning snapshots.
- **Config.** `RetentionConfig(snapshotEveryEvents: Int, keepNSnapshots: Int)` parsed in the existing `ConfigError` fail-fast style; both must be positive. Env `HERMESMQ_SNAPSHOT_EVERY` (default `100`) and `HERMESMQ_SNAPSHOT_KEEP` (default `2`); a `hermesmq.retention` block. Threaded through `Main` into `TopicEntity`/`SubscriptionEntity` (their `apply` gains a `RetentionConfig` parameter).
- **Transparency by construction.** The snapshot IS the folded `evolve` state, so restore + replay-of-later-events is identical to full replay. Verified with `EventSourcedBehaviorTestKit` (snapshot-then-restart) and a Postgres integration test.
- **Defaults chosen conservatively** (`every=100`, `keep=2`) so deletion lags well behind the projection head, mitigating the read-side risk below.

## Risks / Trade-offs

- **Event deletion vs. CQRS replay.** The delivery, subscription-index, lease, and dead-letter projections consume events by tag. `withDeleteEventsOnSnapshot` removes events older than the retained snapshots; if a projection were far behind (a long outage exceeding `N` events on a hot entity), purged events could be missed, and a from-zero read-model rebuild is no longer possible once events are retained-away. Mitigation: projections run continuously and stay near the head; conservative defaults keep deletion well behind; the snapshot still preserves entity (write-side) state fully, which is what delivery correctness depends on. Documented as a known limitation — the journal is not an infinite audit log.
- **Snapshot deserialization is load-bearing after deletion.** Once events are purged, a snapshot that fails to deserialize (e.g., a bad state-schema change) can't fall back to full replay. Mitigation: explicit, tolerant JSON formats; `keepNSnapshots ≥ 2`; round-trip and legacy-tolerance tests; state schema changes treated with the same care as event schema changes.
- **Snapshot serialization surface.** `SubscriptionState` nests `Map[AckId, Outstanding(Message, LeaseState, attempts)]`; its format must round-trip every lease state. Mitigation: dedicated formats with round-trip tests including LEASED/AVAILABLE and non-empty attempt counts.
- **Extra write amplification.** Snapshotting every `N` events adds a periodic snapshot write and a delete. Negligible at `N=100`; configurable if it ever matters.
