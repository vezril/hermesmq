## Context

10a shards entities and runs a single delivery projection cluster-wide, but the topic→subscriptions index is per-node in-memory (populated by the API node that created each subscription). The single projection therefore only sees subscriptions created on its own node, so multi-node fan-out is incomplete. 10b makes the index a **durable, cluster-shared read model** in the existing Postgres, populated by a projection over `SubscriptionCreated` — closing the gap. No change to the delivery guarantee (still at-least-once) or the event shapes.

## Goals / Non-Goals

**Goals:**
- Tag `SubscriptionCreated`; a projection maintains a `topic_subscriptions` table.
- Query the table for a topic's subscriptions; the delivery handler uses it.
- Cluster-shared, durable, rebuildable-from-journal, single projection instance.
- Multi-node delivery reaches every subscription of a topic; single-node unchanged.

**Non-Goals:**
- Ack-deadline expiry / redelivery timers (separate feature).
- Caching the lookup (query per delivery is fine for now).
- Removing subscriptions from the index on delete (topics can be deleted; subscription deletion isn't a feature yet — out of scope).
- Back-delivery to subscriptions created after a message was published.

## Decisions

**Durable read model in Postgres over Distributed Data.**
A `topic_subscriptions(topic_id, subscription_id)` table maintained by a projection over `SubscriptionCreated`. Chosen over Pekko Distributed Data (ddata) because the DB model is **durable and rebuildable** (a full-cluster restart replays from the journal offset / the table persists), strongly readable by every node, and reuses the persistence we already have. *Alternative:* ddata `ORMultiMap` — in-memory/gossip-replicated, faster, but loses contents on full-cluster restart unless re-seeded, and adds cluster-messaging complexity. DB is the simpler, more robust "basic" choice.

**A second projection, `SubscriptionIndexProjection`.**
Consumes tagged `SubscriptionCreated` events (add a tagger to `SubscriptionEntity`, mirroring the topic tagger) and upserts `(topic_id, subscription_id)` into the table (idempotent — `ON CONFLICT DO NOTHING`). Runs as `ShardedDaemonProcess(1)`, exactly one instance cluster-wide, its own `projectionId`/offset. *Alternative:* have the API write the row on create — not rebuildable and not the event-sourced way; the projection is the source-of-truth-derived path.

**`TopicSubscriptionsRepository` replaces the in-memory index.**
An async `subscriptionsFor(topicId): Future[Set[SubscriptionId]]` backed by a JDBC query (`SELECT subscription_id FROM topic_subscriptions WHERE topic_id = ?`). The `DeliveryHandler` takes the repository and awaits/flatMaps the lookup before fanning out. *Alternative:* keep the sync in-memory interface — can't be cluster-shared.

**Delivery handler becomes fully async.**
`deliver(topicId, message)` now: `repository.subscriptionsFor(topicId).flatMap(subs => Future.traverse(subs)(RecordDelivery...))`. The projection handler already `Await`s delivery on its blocking dispatcher, so an extra async hop is fine.

**Route no longer touches an index.**
The pub/sub create-subscription route drops the `index.add(...)` call; the projection now owns index maintenance. Subscription creation stays the same API-wise.

## Risks / Trade-offs

- **Read-model lag** → the index projection is asynchronous, so a subscription created microseconds before a publish might miss that message (eventual consistency). Acceptable and consistent with the existing "no back-delivery" semantics; documented.
- **Query-per-delivery DB load** → one indexed lookup per published message on the single projection node; fine for now, cacheable later. The table is small and keyed by `topic_id`.
- **Two projections now** → delivery + subscription-index, each `ShardedDaemonProcess(1)` with distinct `projectionId`s and offset rows. Ensure offset rows don't collide (distinct names).
- **Full-restart rebuild** → the table persists; if it were ever truncated, the projection replays from offset — but offsets persist too, so a truncate without offset reset wouldn't rebuild. Document that the table is the durable model; a rebuild means resetting the projection offset.
- **Subscription/topic deletion not reflected** → deletion isn't a delivery feature yet; the index only grows on create. Noted as out of scope.

## Migration Plan

TDD (repository/handler unit-testable; projection wiring + Postgres verified via the integration path/manual run):
1. Add `topic_subscriptions` table + the index-projection offset to `postgres.sql`.
2. Tag `SubscriptionCreated` (tagger on `SubscriptionEntity`); RED serialization/tag unaffected.
3. `TopicSubscriptionsRepository` (JDBC) — RED query/upsert tests (Testcontainers or a lightweight in-memory impl for unit tests) → implement.
4. `SubscriptionIndexProjection` handler — RED test (a `SubscriptionCreated` envelope upserts the row) → implement; wire as `ShardedDaemonProcess(1)`.
5. `DeliveryHandler` takes the repository (async); RED handler test (stub repo returns subs on any "node") → implement; remove the in-memory `TopicSubscriptionsIndex` and the route's `index.add`.
6. `Main`: start the index projection; delivery uses the repository. Manual smoke: two-node compose — create a subscription on node A, publish on node B, pull on node A gets the message.
7. README: multi-node delivery is now complete; drop the 10a caveat.

Rollback: additive table + projection + a repository swap; reverting restores the in-memory index (single-node-correct). Journals/events unchanged.

## Open Questions

- **Repository unit tests without Docker**: provide an in-memory `TopicSubscriptionsRepository` impl for unit tests and cover the real JDBC one in the Testcontainers path? Assumption: yes — trait + in-memory stub for handler/delivery tests, JDBC impl exercised by the integration test.
- **Two-node automated test**: still heavy; assumption is the multi-node completeness is verified by the two-node compose manual smoke, with unit tests proving the repository-backed fan-out logic.
