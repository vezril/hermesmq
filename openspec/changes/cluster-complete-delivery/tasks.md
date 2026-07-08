## 1. Schema & subscription-event tagging

- [ ] 1.1 Add a `topic_subscriptions(topic_id, subscription_id, PRIMARY KEY(topic_id, subscription_id))` table and the subscription-index projection offset to `server/src/main/resources/schema/postgres.sql`
- [ ] 1.2 Tag `SubscriptionCreated` events (add a `withTagger` to `SubscriptionEntity`, e.g. tag `"subscription-created"`), mirroring the topic tagger; verify existing entity/serialization tests stay green

## 2. TopicSubscriptionsRepository (TDD)

- [ ] 2.1 RED: `TopicSubscriptionsRepositorySpec` — an in-memory repo impl: `add(topic, sub)` then `subscriptionsFor(topic)` returns it; different topics isolated; `add` is idempotent (no duplicate); confirm red
- [ ] 2.2 GREEN: define the `TopicSubscriptionsRepository` trait (`add`, `subscriptionsFor` → `Future`) + an in-memory impl for tests; pass
- [ ] 2.3 GREEN: implement the JDBC impl (`INSERT … ON CONFLICT DO NOTHING`, `SELECT … WHERE topic_id = ?`) against the `topic_subscriptions` table (verified via the Testcontainers integration path)

## 3. SubscriptionIndexProjection (TDD)

- [ ] 3.1 RED: `SubscriptionIndexHandlerSpec` — given a `SubscriptionCreated(sub, topic)` envelope + a stub repository, the handler upserts `(topic, sub)`; confirm red
- [ ] 3.2 GREEN: implement the index-projection handler (extract sub/topic from the event; `repository.add`); pass
- [ ] 3.3 Wire the projection (`eventsByTag` over `SubscriptionCreated`) with a JDBC offset store, run as `ShardedDaemonProcess(1)` with its own `projectionId`

## 4. Delivery via the shared index (TDD)

- [ ] 4.1 RED: update `DeliveryHandlerSpec` — the handler takes a `TopicSubscriptionsRepository`; a message is delivered to every subscription the repo returns (including ones "created on another node"); confirm red
- [ ] 4.2 GREEN: change `DeliveryHandler` to look up subscriptions from the repository (async) before fan-out; pass
- [ ] 4.3 Remove the in-memory `TopicSubscriptionsIndex` and the `index.add(...)` call in the create-subscription route; update wiring

## 5. Wiring, run & docs

- [ ] 5.1 `Main`: build the JDBC repository, start the `SubscriptionIndexProjection` (`ShardedDaemonProcess(1)`), and pass the repository to the `DeliveryHandler`; the pub/sub routes no longer take an index
- [ ] 5.2 Manual smoke (two-node compose): create a subscription on node A, publish on node B, pull on node A returns the message (multi-node delivery is now complete). Single-node regression check too
- [ ] 5.3 Update `README.md`: multi-node delivery is complete; remove the 10a "delivery caveat"; note the durable subscriptions read model

## 6. Final verification

- [ ] 6.1 Full `sbt test` green across modules (in-memory repo path; no DB required)
- [ ] 6.2 Confirm every scenario in `subscription-index` and the `message-delivery` delta maps to a verified test (or a documented manual/integration check)
- [ ] 6.3 Run `openspec validate cluster-complete-delivery`
