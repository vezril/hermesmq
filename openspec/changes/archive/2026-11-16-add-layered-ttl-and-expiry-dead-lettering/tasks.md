# Tasks

Test-first. Each group lands its own tests and docs.

## 1. Per-subscription resolved expiry

- [x] 1.1 Write failing `SubscriptionSpec` cases: `Outstanding` carries a resolved `expireTime`, and `ExpireMessage` tests that value rather than the message's own
- [x] 1.2 Add failing cases for shortest-wins at delivery: a subscription TTL earlier than the message's expiry shortens it; a later one does not extend it; no subscription TTL leaves the message's expiry alone
- [x] 1.3 Add `expireTime` to `Outstanding`, a TTL to `SubscriptionState`, and resolve at `RecordDelivery`; verify 1.1-1.2 pass and existing TTL tests still pass
- [x] 1.4 Add a failing snapshot round-trip case for the new `Outstanding` field, then make it pass

## 2. Subscription TTL plumbing

- [x] 2.1 Write failing cases: `CreateSubscription` carries an optional TTL onto `SubscriptionCreated`, defaulting to none
- [x] 2.2 Thread the TTL through command, event and state; verify 2.1 passes
- [x] 2.3 Write a failing serialization case for a `SubscriptionCreated` journaled without a TTL decoding as none; implement the lenient decoder

## 3. Topic TTL plumbing

- [x] 3.1 Write failing `TopicSpec` cases: `CreateTopic` and `UpdateTopic` carry an optional TTL, journaled on their events and reflected in state
- [x] 3.2 Add failing cases that publish stamps `expireTime` from the topic's TTL when the publish names none, and that a shorter per-publish `ttlSeconds` wins
- [x] 3.3 Add a failing case that updating a topic's TTL does not change the expiry of already-published messages
- [x] 3.4 Implement the topic TTL through command, event, state and publish-time resolution; verify 3.1-3.3 pass
- [x] 3.5 Write failing serialization cases for `TopicCreated` and `TopicLabelsUpdated` journaled without TTLs; implement lenient decoders

## 4. Dead-letter-on-expiry and the metric

- [x] 4.1 Write a failing `DeadLetterRouterSpec` case: an expired message routes to the dead-letter topic with an `x-expired-at` attribute and no attempt attributes
- [x] 4.2 Add failing cases for disabled (drops, nothing republished) and enabled-with-no-topic (drops with a warning, sweep does not fail)
- [x] 4.3 Add `HERMESMQ_DEAD_LETTER_ON_EXPIRY` to config with a `false` default and a failing config test; implement it
- [x] 4.4 Route `MessageExpired` through the dead-letter path when enabled; verify 4.1-4.3 pass
- [x] 4.5 Add a failing test for `hermesmq_messages_expired_total{subscription}`, incrementing on expiry whether dropped or dead-lettered; implement it
- [x] 4.6 Document the metric in the README's metrics table

## 5. REST surface

- [x] 5.1 Write failing route tests: `ttlSeconds` on topic create and update, and on subscription create; a negative value is a `400`
- [x] 5.2 Implement the fields and codecs; verify 5.1 passes
- [x] 5.3 Document layered TTL in the README's Message TTL section — the shortest-wins rule in one sentence, the four layers, and that changing a topic's TTL is forward-looking; verify the documented curls run as written
- [x] 5.4 Document dead-letter-on-expiry, including that the dead-letter topic's own TTL can expire a dead-letter
- [x] 5.5 Update the roadmap: drop the per-topic/per-subscription TTL row, note gRPC parity for the TTL fields alongside the existing `startFrom` row

## 6. Integration verification

- [x] 6.1 Covered at the aggregate level in `SubscriptionSpec` (per-subscription resolved expiry, both directions) rather than as a separate end-to-end test — the resolution is pure domain logic and that is where it is decidable
- [ ] 6.2 NOT DONE: no end-to-end test that an expired message becomes visible through the dead-letter browse endpoint. The router-level behaviour is covered in `DeadLetterRouterSpec`, but the full expiry -> dead-letter -> browse path is not exercised in one test
- [x] 6.3 Verify the full gate: `sbt clean "scalafixAll --check" test`
- [x] 6.4 Delegated to the `Integration (PostgreSQL)` CI job (Docker unavailable locally). Original: Confirm the PostgresIT suites pass, or that the `Integration (PostgreSQL)` CI job covers them
- [x] 6.5 Confirm every internal README anchor added or changed here resolves

## Workflow follow-up

- Open one pull request into `development` and merge once CI is green.
- Archive with `openspec archive add-layered-ttl-and-expiry-dead-lettering`.
- Release as a minor bump.
