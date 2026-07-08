## 1. Subscription domain — carry Message, idempotent delivery, pull (TDD)

- [x] 1.1 RED: extend `SubscriptionSpec` — `RecordDelivery(ackId, message, deadline)` stores the `Message`; re-delivering an outstanding `ackId` → `Right(Nil)` (no-op); confirm red
- [x] 1.2 GREEN: change `RecordDelivery`/`MessageDelivered` to carry `Message`; `Outstanding(message, deadline)`; make re-delivery idempotent in `Subscription.decide`; pass
- [x] 1.3 Edge: ack/modify still work against the message-carrying outstanding; deliver on non-existent → rejected; green
- [x] 1.4 REFACTOR: tidy; re-run green

## 2. Event serialization (TDD)

- [x] 2.1 RED: extend `EventSerializationSpec` — round-trip `MessageDelivered(ackId, message, deadline)` with a payload/attributes; confirm red
- [x] 2.2 GREEN: update `JsonFormats`/`DomainEventSerializer` for the new `MessageDelivered` shape; pass

## 3. Subscription entity, registry & service (TDD)

- [x] 3.1 RED: `SubscriptionEntitySpec` — record/ack/modify persist correctly with the new shape; a `PullMessages(max)` query returns outstanding messages (ackId + payload) without persisting; confirm red
- [x] 3.2 GREEN: add `PullMessages` query to `SubscriptionEntity` (`Effect.none.thenReply`); split entity command into Submit/Query as needed; pass
- [x] 3.3 GREEN: implement `SubscriptionRegistry` (one writer per id) + `SubscriptionService` seam, mirroring topics; registry test for one-writer/on-demand
- [x] 3.4 REFACTOR: share entity-effect/registry helpers with the topic side where clean; green

## 4. Topic→subscriptions index (TDD)

- [x] 4.1 RED: `TopicSubscriptionsIndexSpec` — a created subscription is indexed under its topic; different topics isolated; confirm red
- [x] 4.2 GREEN: implement the in-memory index; update it on subscription creation; rebuildable from `SubscriptionCreated`; pass

## 5. Delivery handler & projection (TDD)

- [x] 5.1 RED: `DeliveryHandlerSpec` — given a `MessagePublished` for a topic with subscriptions (stub index) + probe subscription refs, the handler issues `RecordDelivery(det ackId, message, deadline)` to each; a topic with no subscriptions issues none; replaying the same event is idempotent (same ackId); confirm red
- [x] 5.2 GREEN: implement the delivery handler (deterministic `ackId = hash(subscriptionId, messageId)`) routing via the subscription registry/service; pass
- [x] 5.3 Tag topic `MessagePublished` events (tagging adapter) and wire the Pekko Projection (`eventsByTag`) to the handler; extract `topicId` from the persistence id
- [x] 5.4 Add `pekko-projection-eventsourced` + `pekko-projection-jdbc` deps and the projection offset table to the schema DDL

## 6. REST API — publish, subscribe, pull, ack (TDD)

- [x] 6.1 RED: `PubSubRoutesSpec` (pekko-http-testkit, stub services) — `POST /v1/topics/{id}/messages` → 202 + messageId (404 missing topic, 400 empty payload); `POST /v1/subscriptions` → 201 (409 dup); `POST /v1/subscriptions/{id}/pull` → 200 list (404 missing); `POST /v1/subscriptions/{id}/ack` → 200 (acked/unknown); confirm red
- [x] 6.2 GREEN: implement the routes + JSON models; map domain outcomes to status codes; generate `MessageId`/publish time at the publish boundary; pass
- [x] 6.3 Edge cases green (missing topic/subscription, empty payload, unknown acks, empty pull)
- [x] 6.4 REFACTOR: share reply→status mapping with the topic-admin routes; green

## 7. Wiring, run & docs

- [x] 7.1 Wire the subscription registry, index, delivery projection, and pub/sub routes into `Main`; warm the index before the projection starts
- [x] 7.2 Manual run check against live Postgres: create topic → create subscription → publish → pull (gets the message) → ack → pull (empty)
- [x] 7.3 Update `README.md`: publish/consume flow, at-least-once guarantee + known limitations, endpoint examples; capability table (publish/consume → done)

## 8. Final verification

- [x] 8.1 Full `sbt test` green (in-memory/handler path, no DB)
- [x] 8.2 Confirm every scenario in the `subscription-lifecycle` delta, `message-delivery`, and `pubsub-api` maps to a verified test
- [x] 8.3 Run `openspec validate publish-consume-messages`
