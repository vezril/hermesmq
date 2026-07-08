## Why

Feature 10a clustered the write side but left one gap: the topic→subscriptions index is still an **in-memory, per-node** map, populated only when a subscription is created via that node's HTTP. So in a multi-node cluster, the single delivery projection (running on one node) only knows the subscriptions created on *its* node — a message published to a topic is not fanned out to subscriptions created on other nodes. This makes multi-node delivery incomplete. Feature 10b replaces the in-memory index with a **durable, cluster-shared read model**, so delivery reaches every subscription of a topic regardless of which node created it.

## What Changes

- **Tag `SubscriptionCreated` events** so a projection can consume them (mirroring the topic-message tagging).
- **Add a durable subscriptions read model**: a `topic_subscriptions(topic_id, subscription_id)` table in PostgreSQL, maintained by a projection over `SubscriptionCreated` events. It is shared by all nodes, survives full-cluster restart (rebuildable from the journal), and runs as a single cluster-wide instance.
- **Replace the in-memory `TopicSubscriptionsIndex`** with a `TopicSubscriptionsRepository` that queries the table for the subscriptions bound to a topic (async).
- **Point the delivery handler at the repository** so fan-out uses the shared index — a published message is delivered to every subscription of its topic across the cluster.
- **Remove the per-node index update** from the subscription-create route (the projection now owns index maintenance, durably).
- Update the schema DDL and README (multi-node delivery is now complete).

Scope note: still **at-least-once**, and still no back-delivery of messages published before a subscription existed (delivery follows the index as of event processing). This closes the *multi-node completeness* gap; ack-deadline expiry / redelivery timers remain a separate concern.

## Capabilities

### New Capabilities
- `subscription-index`: The durable, cluster-shared topic→subscriptions read model — a projection over `SubscriptionCreated` maintaining a queryable table, replacing the per-node in-memory index.

### Modified Capabilities
- `message-delivery`: Fan-out uses the distributed subscriptions index, so a published message reaches **every** subscription of its topic across the cluster — not only those on the projection's node.

## Impact

- **Schema**: new `topic_subscriptions` table + a projection offset row for the subscription-index projection; added to `postgres.sql`.
- **Events**: `SubscriptionCreated` gains a tag; the `SubscriptionEntity` tagger is added (no event-shape change, journal-compatible).
- **New source**: `SubscriptionIndexProjection` (over tagged `SubscriptionCreated`), `TopicSubscriptionsRepository` (JDBC query), replacing `TopicSubscriptionsIndex`. `DeliveryHandler` takes the repository (async lookup). `Main` starts the second projection (also `ShardedDaemonProcess(1)`), and the pub/sub route no longer touches an in-memory index.
- **Tests**: repository upsert/query (Testcontainers or in-memory), delivery handler against a stub repository (async), projection-handler unit test (SubscriptionCreated → row upserted).
- **Delivery guarantee**: multi-node delivery is now complete — a subscription on any node receives messages for its topic. Single-node behavior is unchanged.
- **Deferred**: ack-deadline expiry / redelivery timers (separate feature); caching the repository lookup (fine to query per delivery for now).
