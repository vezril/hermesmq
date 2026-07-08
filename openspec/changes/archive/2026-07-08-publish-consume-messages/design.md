## Context

Topics are manageable and the Subscription aggregate exists (create/record-delivery/acknowledge/modify), but nothing connects publish to consumption. Feature 8 delivers the hot path: publish → durable journal → **projection-driven at-least-once fan-out** to subscriptions → **REST pull** consume → ack. The user chose REST pull (gRPC later) and projection-driven delivery. Single-node; clustering is a non-goal.

## Goals / Non-Goals

**Goals:**
- Publish a message to a topic over REST (journaled `MessagePublished`, tagged for projection).
- Create subscriptions (REST + registry) and maintain a topic→subscriptions index.
- A Pekko Projection that delivers each published message to every subscription of its topic, at least once, idempotently across replays.
- Subscription stores the delivered `Message`; a non-persisting pull query; ack over REST.
- Full TDD; README docs; honest guarantees.

**Non-Goals:**
- gRPC streaming consume (later).
- Redelivery timers / ack-deadline expiry / dead-lettering (later).
- Read-model backlog/throughput metrics, journal purging of acked messages (later).
- Ordering guarantees beyond per-topic journal order; historical replay to new subscribers.
- Exactly-once delivery (we provide at-least-once with idempotent re-delivery).

## Decisions

**Delivery is write-side and projection-driven (at-least-once).**
A Pekko Projection consumes topic `MessagePublished` events and issues `RecordDelivery` to each subscription of the message's topic. This decouples fan-out from publish latency and is crash-safe (resumes from a stored offset). It is at-least-once because the offset commit and the actor command are not one transaction; replays re-deliver. *Alternative:* synchronous inline fan-out on publish — simpler but couples latency to subscriber count and can drop deliveries on crash (rejected per the design question).

**Idempotency via a deterministic `ackId = hash(subscriptionId, messageId)`.**
Because replays re-issue `RecordDelivery`, the `ackId` is derived deterministically from `(subscription, message)`. `RecordDelivery` is a **no-op when the `ackId` is already outstanding** (returns `Right(Nil)`), so replays don't duplicate. *Known limitation (basic):* an ackId already acknowledged (removed from outstanding) could be re-delivered by a replay, since we don't retain an acked-id set (that needs purging, a later feature). Documented; acceptable for a first at-least-once cut.

**Subscription stores the full `Message`.**
`RecordDelivery` carries the `Message` (not just `MessageId`) and `outstanding: Map[AckId, Outstanding(message, deadline)]`, so **pull returns payloads** without a cross-aggregate join. This changes the `MessageDelivered` journal format — noted; older journals from v0.2.0 predate delivery so there is nothing to migrate in practice.

**Topic→subscriptions index: in-memory, single-node.**
A `TopicSubscriptionsIndex` (an actor or concurrent map) maps `topicId → Set[subscriptionId]`, updated when a subscription is created. The delivery projection reads it to fan out. On restart it is rebuilt from a projection over `SubscriptionCreated` events (or eagerly from the subscription registry). *Alternative:* a persistent read model — heavier; in-memory is fine single-node and rebuildable. Delivery follows subscriptions **present when the event is processed** (no back-delivery to later subscribers) — documented.

**Tagging topic events.**
Topic `MessagePublished` events are tagged (e.g. `"topic-message"`) via an event-tagging adapter so the projection can stream them with `eventsByTag`. The projection extracts the `topicId` from the persistence id (`Topic|<id>`).

**Consume = non-persisting pull query on the subscription.**
`POST /pull` issues a `PullMessages(max, replyTo)` query (`Effect.none.thenReply`) returning up to `max` outstanding messages. Ack (`POST /ack`) issues `Acknowledge` per id; unknown acks are reported, not fatal. *Alternative:* push/streaming — that's the gRPC feature.

**Projection offset store: JDBC.**
`pekko-projection-jdbc` stores offsets in the same Postgres; add its offset table to the schema DDL. Tests use the projection testkit / in-memory offset and test the handler logic directly (given an event + a stub index + probe subscriptions, the right `RecordDelivery`s are issued) so unit tests need no DB.

**Registries mirror the topic side.**
A `SubscriptionRegistry` (get-or-spawn, one writer per subscription id) + a `SubscriptionService` seam, exactly like topics, so routes stay unit-testable.

## Risks / Trade-offs

- **At-least-once → duplicates on replay** → deterministic ackId + outstanding-dedup covers the common case; acked-then-redelivered is a documented limitation until purging/redelivery hardening lands.
- **In-memory index lost on restart** → rebuild from `SubscriptionCreated` on startup before the delivery projection runs, or the projection tolerates an empty index warming up; document the ordering.
- **Projection lag** → delivery is asynchronous; publish returns before delivery. This is the intended decoupling; consumers pull when ready.
- **`MessageDelivered` format change** → carrying `Message` changes the event; covered by serialization tests. No pre-existing delivery journals to migrate.
- **New dependency surface (pekko-projection)** → pin versions compatible with Pekko; keep the projection handler thin and unit-test it directly to limit runtime-dependent tests.
- **Unbounded `outstanding`** → without purge/expiry, a slow/absent consumer grows memory; acceptable for basic, flagged for the redelivery/expiry feature.

## Migration Plan

TDD, bottom-up (domain/handler unit-tested without DB; projection runtime + Postgres last):
1. Subscription domain: `RecordDelivery(message)`, `outstanding` stores `Message`, idempotent re-delivery, `PullMessages` query — RED tests → implement.
2. Serialization: `MessageDelivered` carries `Message` — round-trip tests → extend serializer.
3. Subscription entity + registry + service (mirror topics); pull query on the entity.
4. Topic→subscriptions index — tests for indexing/isolation → implement; wire subscription creation to update it.
5. Delivery handler — RED tests (event + stub index + probe subs → expected `RecordDelivery`s, idempotent replay) → implement; then the Pekko Projection wiring + tagging.
6. REST: publish, create-subscription, pull, ack routes + models → route tests → implement; wire into `Main` and start the projection.
7. Schema: add the projection offset table to the DDL. Manual run: publish → pull → ack against live Postgres.
8. README: publish/consume flow + guarantees; capability table.

Rollback: additive files + one modified domain command/event; reverting removes the delivery path. `MessageDelivered` journal-format change means rolled-back code couldn't read new delivery events — note in the PR.

## Open Questions

- **Publish response**: `202 Accepted` (async delivery) vs `201`. Assumption: `202` with the `messageId`, since delivery is asynchronous.
- **Pull semantics**: does pull extend/lease the ack deadline, or just read? Assumption: read-only for now (no lease); redelivery/lease is a later feature.
- **Index rebuild timing**: rebuild the topic→subscriptions index from `SubscriptionCreated` on startup before the projection starts? Assumption: yes, warm it first.
- **Ack response shape**: `200` with per-id results vs `204`. Assumption: `200` with `{acknowledged, unknown}` lists.
