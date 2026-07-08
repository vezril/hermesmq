## Why

The architecture promises that "recovery after a restart is a replay of events **bounded by snapshots**" and that "acknowledged messages are **eventually purged from the journal** rather than retained indefinitely." Today neither holds: the Topic and Subscription entities snapshot nothing, so every recovery replays the entity's entire event history and the journal grows without bound. For a long-lived broker — especially subscriptions that accrue delivery/lease/ack/expiry events per message — this means ever-slower recovery and unbounded storage. Snapshots with event retention fix both, and are the last core durability guarantee from the architecture that is unimplemented.

## What Changes

- Enable periodic **snapshots** on the Topic and Subscription `EventSourcedBehavior`s (snapshot every N events, keep M snapshots) so recovery restores the latest snapshot and replays only the events after it.
- Enable **event deletion on snapshot** (retention) so journaled events older than a retained snapshot are purged once safely captured — capping journal growth.
- Add **explicit snapshot serialization** for `TopicState` and `SubscriptionState` (Java serialization stays off), reusing the existing spray-json approach.
- Make the retention parameters **configurable** (`hermesmq.retention`) with sane defaults, env overrides, and fail-fast validation.
- Keep event schemas, `decide`/`evolve`, and all externally observable behavior **unchanged** — snapshots are a transparent internal optimization; a snapshot restore + replay must yield exactly the state a full replay would.

## Capabilities

### New Capabilities
- `snapshot-retention`: Periodic state snapshots and journal event-retention for the event-sourced aggregates — snapshot cadence and retention, snapshot serialization of aggregate state, transparent snapshot-bounded recovery, and configurable, validated retention parameters.

### Modified Capabilities
<!-- None: event schemas and decide/evolve are unchanged. Snapshotting is additive durability behavior over the existing aggregates. -->

## Impact

- **Entities:** `TopicEntity` and `SubscriptionEntity` gain `.withRetention(RetentionCriteria.snapshotEvery(n).withKeepNSnapshots(m).withDeleteEventsOnSnapshot)` (wired from config).
- **Serialization:** extend `JsonFormats` with `TopicState`/`SubscriptionState` formats and bind them (via the existing `DomainEventSerializer` or a sibling snapshot serializer) in `application.conf` `serialization-bindings` — so snapshots serialize explicitly, never via Java.
- **Config:** new `RetentionConfig(snapshotEveryEvents, keepNSnapshots)` parser mirroring the existing config style, `HERMESMQ_SNAPSHOT_*` overrides, and a `hermesmq.retention` block; threaded through `Main` into the entities.
- **Schema/runtime:** uses the already-present `snapshot` table and `jdbc-snapshot-store`; event deletion uses the journal's existing delete support. No new tables.
- **Docs:** README capability row for snapshots/retention and new config rows.
- **Out of scope:** changing event formats, `decide`/`evolve`, delivery semantics, or the read-model projections.
