# Design

## Context

See `proposal.md` — Why, and the replay hazard it describes. The existing machinery this builds on:

- Topic events are journaled under persistence id `Topic|<topicId>`, so a topic's full retained
  history is readable with one `currentEventsByPersistenceId` query.
- `SubscriptionIndexProjection` already consumes `SubscriptionCreated` on `SubscriptionEntity.CreatedTag`
  as a single cluster-wide instance — the natural trigger, already wired and supervised.
- `DeliveryHandler.ackIdFor(subscription, message)` is pure, so a back-delivered message gets the same
  key live delivery would give it.
- `Message.expired(now)` already exists for the TTL check.
- `Subscription.decide` no-ops a `RecordDelivery` whose `ackId` is already outstanding.

## Goals / Non-Goals

**Goals:**

- Opt-in, defaulting to today's behaviour, so no existing deployment changes.
- At most one back-delivery per subscription, guarded by a durable record rather than by hoping
  delivery is idempotent.
- Back-delivered messages indistinguishable from live ones.
- Honest about the horizon in docs, at the endpoint.

**Non-Goals:**

- **gRPC parity.** Blocked on a Lexicon change (see the proposal). REST-only here.
- **A timestamp or offset start position.** `earliest` and `now` cover the asked-for case; an
  arbitrary "from time T" invites questions about what T means across a purged journal.
- **Changing an existing subscription's start position.** Creation-time only; re-creation is already
  rejected.
- **Back-delivering to all subscriptions on demand** (a "replay topic" admin action). Different
  feature, different blast radius.

## Decisions

### Trigger from the subscription-index projection, not the creation request

The back-delivery runs off `SubscriptionCreated` in the cluster-wide projection that already handles
that event, not inline in the REST handler.

*Why over the alternatives:*

- **Inline in the request** would make subscription creation's latency proportional to the topic's
  history, and would lose the backfill entirely if the node died mid-request. The decision is
  journaled; the work should follow the journal.
- **A new dedicated projection** would duplicate a subscription of the same tag for no benefit; the
  index projection is already the single cluster-wide consumer of this event.

A consequence: back-delivery is asynchronous, so a client that creates an `earliest` subscription and
immediately pulls may get nothing yet. Stated in the spec and documented — consistent with every other
read-model-mediated behaviour here.

### A durable marker, not delivery idempotency, is the guard

A `subscription_backfills` row is written when a backfill completes, and checked before starting one.

*Why:* this is the hazard in the proposal. `RecordDelivery` de-duplicates only while the message is
still outstanding. A consumer that drains the backfill empties the outstanding set, so a reprocessed
`SubscriptionCreated` would re-deliver everything with nothing to collide against. The marker is the
only thing that makes "at most once" true.

The marker is written **after** the deliveries, not before. Ordering again: marking first risks a
subscription that never gets its backfill (marked, then crashed) — silent data loss of the exact kind
this feature exists to fix. Marking last risks a partial backfill running again, which the outstanding
check mostly absorbs and which at worst re-delivers a message the consumer had already acked. A
duplicate is recoverable; a silent miss is not.

### Read the journal directly rather than through a read model

Back-delivery queries `currentEventsByPersistenceId("Topic|<id>", 0, Long.MaxValue)` and filters
`MessagePublished`.

*Why:* the topic journal *is* the record of what was published. Building a separate "published
messages" read model to serve an uncommon operation would double the storage of every payload, and it
would be subject to the same retention horizon anyway. `current*` rather than the live stream because
this is a bounded catch-up with a defined end, not a subscription.

### `startFrom` on the event, with a lenient decoder

`SubscriptionCreated` gains the field, and the spray-json decoder defaults it to `now` when absent.

*Why:* existing journals contain events without it. A strict decoder would make this change unable to
recover any pre-existing subscription — a catastrophic upgrade. The default is chosen so an old event
means exactly what it meant when written.

## Risks / Trade-offs

- **A long topic history makes one slow backfill** → It runs in the projection, so it delays that
  projection's progress, which also serves the subscription index. Mitigated by processing as a stream
  rather than accumulating, and bounded by retention. A topic with a very long retained history and a
  burst of `earliest` subscriptions is the worst case; noted as a limit rather than solved.
- **`earliest` over-promises to a reader** → It means "earliest retained". Documented at the endpoint
  and in the spec, because the wrong reading is the natural one.
- **Marking after delivery can re-run a partial backfill** → Deliberate, per the ordering argument
  above. The outstanding check absorbs the common case.
- **Event-schema change** → Additive with a defaulting decoder, and the round-trip is covered by a
  serialization test against an event written without the field.
- **Asynchronous backfill surprises a test or a client** → A create-then-immediately-pull sequence may
  see nothing. Inherent to doing the work off the journal; documented.

## Migration Plan

1. Deploy. Boot-time migration creates the marker table.
2. Existing subscriptions are unaffected: no marker, but also no `earliest` on their journaled events,
   so nothing triggers.
3. Old `SubscriptionCreated` events deserialize as `now`.

**Rollback:** revert. The marker table is additive and unread by the prior version; `startFrom` on new
events is ignored by a decoder that does not know the field.

## Open Questions

- Should the backfill report progress anywhere (a metric, or a field on `GET /v1/subscriptions`)? Not
  needed for correctness, and addable later without touching these specs.
