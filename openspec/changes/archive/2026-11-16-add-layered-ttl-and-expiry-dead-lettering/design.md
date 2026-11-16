# Design

## Context

See `proposal.md` — Why, and the shortest-wins rationale. What the existing code gives us:

- `TtlConfig.expireAt(publishTime, ttlSeconds)` already resolves per-publish-else-global and stamps
  `Message.expireTime` at publish.
- `expiring_messages` is keyed `(subscription_id, ack_id)` with its own `expire_time`, so a
  per-subscription expiry is already representable with no schema change.
- `TtlSweeper` reads that model and issues `ExpireMessage`; `Subscription.decide` emits
  `MessageExpired` when `o.message.expired(now)`.
- `DeadLetterRouter` already republishes with provenance attributes and already handles the
  no-dead-letter-topic case by dropping with a warning.

Two things that constrain the shape:

- The expiry decision currently reads `o.message.expired(now)` — the *message's* expiry. A
  per-subscription TTL means the outstanding entry needs its own resolved expiry, because the same
  message can expire at different times for different subscriptions.
- A topic's TTL is known at publish, a subscription's only at delivery. So resolution happens in two
  places, which is exactly why the rule has to compose — `min` does; "most specific wins" would not
  without extra state.

## Goals / Non-Goals

**Goals:**

- One stated rule for TTL resolution that holds in both places it is applied.
- Expiry that leaves a trace: dead-letter when asked, and always a counter.
- Defaults that change nothing for an existing deployment.

**Non-Goals:**

- **gRPC parity.** Same Lexicon constraint as `startFrom`: topic and subscription TTL are REST-only
  until the contract moves. gRPC publish's own `ttl_seconds` is unaffected.
- **Retroactively re-expiring already-published messages** when a topic's TTL changes. A message's
  expiry is fixed at publish; changing a topic's TTL is forward-looking. Stated in the spec because
  the other reading is plausible.
- **A per-subscription dead-letter topic for expiries.** Still the one global topic; that is a
  separate roadmap row.

## Decisions

### Resolve to a per-subscription expiry on the outstanding entry

`Outstanding` gains `expireTime: Option[Instant]`, computed at `RecordDelivery` as the earlier of the
message's own expiry and `publishTime + subscriptionTtl`. `ExpireMessage` then tests that value rather
than `o.message.expired(now)`.

*Why:* it is the only place that knows both sides. Putting the resolved value on the entry also makes
the expiry decision a pure local check, keeps the sweeper unchanged, and means the read model row the
`ExpiringMessageProjection` writes is simply that value.

*Alternative rejected:* resolving at sweep time by looking up the subscription's TTL. That makes
expiry depend on current configuration rather than what was agreed at delivery, so changing a
subscription's TTL would retroactively move existing messages' expiry — the same trap the spec
forbids for topics.

### Shortest wins, as a plain `min`

Already argued in the proposal. Implementation-wise it matters that this is associative and
commutative: publish-time resolution (`per-publish` vs `topic` vs `global`) and delivery-time
resolution (`that` vs `subscription`) compose to the same answer regardless of order, so there is no
hidden dependency on which layer is applied first.

### Dead-letter-on-expiry reuses the dead-letter router

`MessageExpired` gains a route through `DeadLetterRouter`, with an `x-expired-at` attribute instead of
the attempt-count attributes.

*Why:* an expired message that lands in the dead-letter topic is immediately browsable and redrivable
by the operator endpoints shipped in v1.14.0 — the feature is mostly wiring because that machinery
already exists. Reusing the router also means the no-topic-configured case is already handled.

*Consequence worth documenting:* the dead-letter topic is a topic, so if it has its own TTL the
dead-letter can itself expire. Not special-cased — a special case here would be surprising in the
other direction.

### Lenient decoders again

`TopicCreated`, `TopicLabelsUpdated` and `SubscriptionCreated` gain TTL fields, decoded as absent-means-none.

*Why:* same reasoning as `startFrom`. A strict decoder makes existing journals unreadable, which is a
catastrophic upgrade for a feature nobody asked to be risky.

## Risks / Trade-offs

- **Three event-schema changes in one change** → Each is additive with a defaulting decoder and each
  gets a round-trip test plus a legacy-payload test. The risk is concentrated in the decoders, which
  is where the tests are aimed.
- **Shortest-wins will surprise someone** → A caller who sets a long subscription TTL expecting it to
  win gets the topic's shorter one. Mitigated by documenting the rule as a single sentence at the
  endpoint, and by it failing safe: the surprise is earlier expiry, not unexpected retention.
- **`Outstanding` grows a field** → It is in snapshots, so snapshot serialization needs the same
  leniency as the events. Covered by a snapshot round-trip test.
- **Dead-lettering expiries can flood the dead-letter topic** → A topic with a short TTL and no
  consumer would now dead-letter everything instead of dropping it. That is the point, but it is a
  volume change an operator should opt into — hence default off.

## Migration Plan

1. Deploy. No schema change, no config change required.
2. Existing topics and subscriptions have no TTL; old events decode as such.
3. `HERMESMQ_DEAD_LETTER_ON_EXPIRY` defaults false, so expiry behaves exactly as before until enabled.

**Rollback:** revert. New TTL fields on events are ignored by the prior version's decoders, and
resolved expiries already written to `expiring_messages` remain valid values.
