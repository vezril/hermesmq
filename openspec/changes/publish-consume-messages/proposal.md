## Why

Topics can be managed but no message ever moves through the broker: there is no way to publish a message, no fan-out to subscriptions, and no way to consume. This feature wires the hot path end to end — publish a message to a topic, deliver it durably to every subscription on that topic, and let consumers pull and acknowledge messages — delivering HermesMQ's core Pub/Sub value and its at-least-once guarantee.

## What Changes

- **Publish (REST)**: `POST /v1/topics/{id}/messages` accepts a payload + optional attributes, mints a `MessageId`/publish time, and journals `MessagePublished` on the topic. Topic `MessagePublished` events are tagged so a projection can consume them.
- **Subscriptions (REST + registry)**: `POST /v1/subscriptions` creates a subscription bound to a topic; a single-writer `SubscriptionRegistry` (mirroring the topic registry) routes commands to the owning entity. A topic→subscriptions index (maintained as subscriptions are created) tells the delivery projection where to fan out.
- **Delivery (projection-driven, at-least-once)**: a Pekko Projection tails tagged topic events; for each `MessagePublished`, it issues `RecordDelivery` to every subscription on that topic. Fan-out is decoupled from publish latency and survives restarts (replays re-deliver; delivery is idempotent per `(subscription, message)`).
- **Subscription domain change**: `RecordDelivery` now carries the full `Message` (so consume returns payloads); `outstanding` stores the message; re-delivering an already-outstanding message is an idempotent no-op.
- **Consume + Ack (REST)**: `POST /v1/subscriptions/{id}/pull` returns up to N outstanding messages (`ackId` + payload + attributes + publish time) via a non-persisting query; `POST /v1/subscriptions/{id}/ack` acknowledges a list of `ackId`s, removing them from `outstanding`.
- Document the publish/consume flow and the delivery guarantee; update the capability summary.

Scope note (basic, honest): **single-node, at-least-once, pull-based REST.** A message may be redelivered after a crash (dedup on the `outstanding` set); hardened redelivery/expiry, message ordering guarantees, gRPC streaming consume, and read-model backlog metrics are later features. `ackId` is deterministic per `(subscription, message)` so projection replays don't duplicate outstanding entries.

## Capabilities

### New Capabilities
- `message-delivery`: The projection-driven fan-out — the topic→subscriptions index and the Pekko Projection that delivers each published message to every subscription of its topic, at least once.
- `pubsub-api`: The REST endpoints for publishing to a topic, creating subscriptions, and pulling/acknowledging messages.

### Modified Capabilities
- `subscription-lifecycle`: `RecordDelivery` carries the full `Message` and is idempotent for an already-outstanding `ackId`; `outstanding` stores the delivered message so it can be returned on pull.

## Impact

- **New dependencies**: `pekko-projection-eventsourced` + `pekko-projection-jdbc` (offset store) and `pekko-projection-testkit` (Test). Topic events tagged via a tagging adapter.
- **Domain**: `SubscriptionCommand.RecordDelivery`/`SubscriptionEvent.MessageDelivered` carry `Message`; `Outstanding` stores `Message`; `decide` makes re-delivery idempotent. Serializer/formats updated (backward-compatible where feasible; note journal-format change for `MessageDelivered`).
- **New source**: `SubscriptionRegistry`, `SubscriptionService`; a `TopicSubscriptionsIndex`; the `DeliveryProjection` + handler; publish/subscription/pull/ack request-response models and routes; wiring in `Main` (start the projection).
- **Persistence**: topic events tagged; a projection offset store (JDBC) added to the schema/DDL.
- **Guarantee**: at-least-once delivery; a published-and-journaled message will be delivered to every current subscription of its topic, surviving restarts.
- **Docs**: README publish/consume section; capability table (publish/consume → done).
