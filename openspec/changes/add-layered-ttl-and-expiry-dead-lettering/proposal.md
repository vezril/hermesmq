# Proposal

## Why

Two gaps, from the same roadmap row:

1. **TTL has one knob for the whole broker.** `HERMESMQ_TTL_DEFAULT` plus a per-publish
   `ttlSeconds` override. A topic that wants 30-second freshness and a topic that wants seven days
   cannot coexist, and a slow consumer cannot ask for a shorter window than its topic's default.
2. **Expiry is silent.** A message past its TTL is purged with no record anywhere — no dead-letter, no
   metric, nothing in a read model. An operator asking "what did we lose overnight?" has no answer at
   all. This is the worse of the two: TTL is a deliberate data-loss mechanism, and the broker
   currently performs it without leaving a trace.

The dead-letter machinery to fix (2) already exists — browse and redrive shipped in v1.14.0, so an
expired message routed there is immediately inspectable and replayable.

## What Changes

- **Per-topic TTL.** `POST /v1/topics` and `PATCH /v1/topics/{id}` accept `ttlSeconds`. Applied at
  publish, so it stamps the message's `expireTime` exactly as the global default does today.
- **Per-subscription TTL.** `POST /v1/subscriptions` accepts `ttlSeconds`, applied when a delivery is
  recorded for that subscription.
- **Dead-letter-on-expiry.** A new `HERMESMQ_DEAD_LETTER_ON_EXPIRY` (default `false`, today's
  behaviour) routes an expired message to the dead-letter topic instead of dropping it, carrying an
  `x-expired-at` attribute so an operator can tell expiry from attempt-exhaustion.
- **An expiry metric**, `hermesmq_messages_expired_total{subscription}`, so the silent case is at
  least countable even for operators who leave dead-lettering off.

## The precedence rule: shortest wins

Four layers can now specify a TTL. The rule is one sentence: **any layer may shorten a message's
lifetime; none may extend it.** The effective expiry is the earliest of whatever applies.

This was chosen over the conventional "most specific wins" deliberately. Most-specific-wins would let
a subscription *extend* retention past what the topic owner set, which inverts ownership — the topic
owner is the one who declared the data stale. Enforcing that under most-specific-wins would need a
new "was this TTL explicitly set?" flag on `Message` to distinguish an inherited value from a
deliberate one, which means another event-schema change and a rule nobody remembers. Shortest-wins
needs no flag, is a plain `min`, and cannot surprise anyone in the dangerous direction.

Concretely: at publish, `expireTime` is stamped from per-publish `ttlSeconds`, else the topic's TTL,
else the global default. At delivery, a subscription's TTL shortens that if it is earlier. The
per-subscription expiry is already representable — `expiring_messages` is keyed
`(subscription_id, ack_id)` with its own `expire_time` — so this needs no schema change on that side.

## Capabilities

### New Capabilities
*(none — this extends existing capabilities)*

### Modified Capabilities
- `message-ttl`: TTL is resolved from four layers by shortest-wins rather than a single global default
  plus a per-publish override; expiry may dead-letter rather than drop; expiry is counted.
- `topic-lifecycle`: a topic carries an optional TTL, settable at creation and updatable.
- `subscription-lifecycle`: a subscription carries an optional TTL, set at creation.

## Impact

- **APIs:** `ttlSeconds` added to topic create/update and subscription create. All additive and
  optional; omitting everything is today's behaviour exactly. REST only — the gRPC contract is in the
  pinned Lexicon artifact, same constraint as `startFrom`.
- **Domain:** `TopicCommand.CreateTopic`/`UpdateTopic` gain a TTL, `TopicEvent.TopicCreated` gains one
  and a new `TopicTtlUpdated` event is added (rather than overloading `TopicLabelsUpdated`, whose name
  would then mislead every future reader of the journal); `SubscriptionEvent.MessageExpired` gains the
  expired message, because dead-lettering an expiry needs the payload and the event did not carry it; `SubscriptionCommand.CreateSubscription`/`SubscriptionEvent.SubscriptionCreated` gain
  one; `Outstanding` gains the resolved per-subscription expiry. **Event-schema changes** — all
  decoders must read older events, which lack these fields, as "no TTL".
- **Config:** one new variable, `HERMESMQ_DEAD_LETTER_ON_EXPIRY`.
- **Schema:** one additive column. `expiring_messages` already carries a per-subscription `expire_time`,
  so expiry resolution needs nothing — but the expiry counter does:
  `subscription_stats` gains `expired_total` via `ADD COLUMN IF NOT EXISTS`, which keeps the
  boot-time migrator idempotent. (This corrects an earlier claim of no schema change in this
  proposal: the counter was overlooked.)
- **Interaction worth stating:** with dead-lettering on, an expired message is republished to the
  dead-letter topic, which is itself a topic — and if *that* topic has a TTL, the dead-letter can
  expire too. Documented rather than special-cased.
