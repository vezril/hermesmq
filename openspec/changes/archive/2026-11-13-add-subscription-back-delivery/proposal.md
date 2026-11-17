# Proposal

## Why

A subscription only ever receives messages published **after** it was created. Messages already in the
topic's journal are invisible to it, permanently. A consumer whose subscription is created during a
deploy silently misses that window, and there is no way to recover it — the messages are *right there*
in the journal, and the broker refuses to look.

This is the last of the three delivery-semantics gaps in the roadmap, and the only one that was ever
genuinely buildable. (Exactly-once turned out to be impossible; the subscription-index rebuild is
blocked by retention.) Here the events exist, so the only questions are who asks for them and how far
back "back" goes.

## What Changes

- **`startFrom` on subscription creation.** `POST /v1/subscriptions` accepts
  `"startFrom": "now" | "earliest"`. `now` is the default and is exactly today's behaviour, so nothing
  changes for anyone who does not opt in.
- **A one-shot backfill** for `startFrom: earliest`: the broker reads the topic's journal from its
  oldest retained event and delivers each still-valid `MessagePublished` to the new subscription, then
  marks the subscription backfilled.
- **Backfill is journaled on the event.** `SubscriptionCreated` carries the `startFrom` choice, so the
  decision survives restart and replay rather than living in the request that made it.
- **A durable backfill marker**, so the backfill runs at most once per subscription. This is not
  belt-and-braces: see the hazard below.
- **TTL and deleted topics are respected.** An expired message is not back-delivered, and a backfill
  against a deleted topic is a no-op rather than an error.

**Not in this change:** gRPC parity. `CreateSubscriptionRequest` comes from the pinned
`lexicon-hermes-grpc` artifact in [the Lexicon](https://github.com/vezril/the-lexicon), so a
`start_from` field cannot be added from this repository. REST gets the capability now; gRPC needs a
Lexicon change and a pin bump, tracked as a follow-up. The `startFrom` default means gRPC-created
subscriptions keep working unchanged.

## The replay hazard this has to solve

The obvious implementation — react to `SubscriptionCreated` in a projection and replay the topic
journal — is wrong in a way worth stating, because the usual safety argument does not apply.

`RecordDelivery` is idempotent per `ackId` *only while the message is still outstanding*: the
aggregate checks `state.outstanding.contains(ackId)` and no-ops. Once a consumer acknowledges, the
entry is removed. So an at-least-once projection that reprocesses `SubscriptionCreated` after the
consumer has drained the backfill would deliver every one of those messages **again** — the
deterministic `ackId` does not help, because there is nothing left to collide with.

Hence the durable marker: the backfill is guarded by a committed record of having run, not by the
idempotency of its effects.

## Capabilities

### New Capabilities
- `subscription-back-delivery`: a subscription's ability to receive messages published before it
  existed — the opt-in, its horizon, the at-most-once-per-subscription guarantee, and how it interacts
  with retention, TTL, and topic deletion.

### Modified Capabilities
- `subscription-lifecycle`: subscription creation accepts a start position, and `SubscriptionCreated`
  carries it.

## Impact

- **APIs:** `POST /v1/subscriptions` gains an optional `startFrom`. Additive; omitting it is today's
  behaviour. No gRPC change (see above).
- **Domain:** `SubscriptionCommand.CreateSubscription` and `SubscriptionEvent.SubscriptionCreated` gain
  a start-position field. **Event-schema change** — the JSON serializer must read old
  `SubscriptionCreated` events, which have no such field, as `now`.
- **Code:** `server/.../delivery` (a backfill reader over the topic journal, plus its marker
  repository), `server/.../http/PubSubRoutes.scala`, and the subscription aggregate.
- **Schema:** one additive `IF NOT EXISTS` table for the backfill marker.
- **Honest limit:** the horizon is whatever the journal still holds. Snapshot retention
  (`HERMESMQ_SNAPSHOT_KEEP`) purges older events, so `earliest` means "earliest still retained", not
  "earliest ever". This must be documented at the endpoint, not buried — an operator who reads
  `earliest` as "all history" will be wrong on a long-lived topic.
