# Proposal

## Why

Dead-lettering works, but it is only observable as a **count**: `GET /v1/subscriptions` reports
`deadLetteredTotal` and nothing else. An operator who sees that number climb has no first-class way to
see *which* messages died or to put them back. Today the only route is to create a subscription on the
global dead-letter topic, `pull` (which **leases** — a side-effecting read), filter client-side on the
`x-dead-letter-subscription` attribute, join against `GET /v1/subscriptions` to recover the origin
topic, and re-publish by hand. The Zeus operator console is building exactly that client-side
reassembly today ([#57](https://github.com/vezril/hermesmq/issues/57)); this change makes it a
supported broker capability instead.

## What Changes

- **Non-destructive browse.** New `GET /v1/subscriptions/{id}/dead-letters` returns a subscription's
  dead-lettered messages with their metadata. It **peeks** — it does not lease, does not count a
  delivery attempt, and does not compete with a live consumer on the dead-letter topic.
- **Self-contained replay metadata.** Dead-letter routing gains an `x-original-topic` attribute
  alongside the existing `x-dead-letter-subscription`, `x-delivery-attempts`, and
  `x-original-message-id`. Replay then needs no join against the subscriptions listing.
- **A redrive primitive.** New `POST /v1/subscriptions/{id}/dead-letters/redrive` re-publishes
  dead-letters back to their origin topic — by explicit message ids or all of them — and reports what
  was redriven. Redrive reuses the existing idempotent-publish dedup path keyed on the original
  message id, so a retried or double-clicked redrive does not fan out duplicates.
- Browse is served from a **durable read model** maintained by a projection over the *subscription*
  journal's `MessageDeadLettered` events — which already carry the full original message — indexed by
  subscription. This is the established pattern here (off the hot delivery path, eventually
  consistent), and it is not a scan of the dead-letter topic per request. A useful consequence: the
  read model records dead-letters **even when no dead-letter topic is configured**, where today the
  message is dropped with only a warning log. Operators stop losing that evidence.
- REST only, matching the precedent that operator/admin listings (`GET /v1/subscriptions`,
  `GET /v1/topics`) are REST-only while the pub/sub data plane is dual REST + gRPC.
- Auth and tenancy behave exactly as for the existing `/v1` surface: both endpoints require a valid
  key for the caller's tenant, and redrive — a mutating operator action — requires the `admin` scope.

**Not in this change:** per-subscription dead-letter topic configuration (the optional fourth item in
#57). Today's single global dead-letter topic is a config and routing change with its own migration
story, and the three items above deliver the operator experience without it. Called out as a non-goal
in `design.md` rather than silently dropped.

## Capabilities

### New Capabilities
- `dead-letter-operations`: Browsing and redriving dead-lettered messages — the operator-facing
  lifecycle of a message *after* it has exhausted delivery. Owns the dead-letter read model, the
  non-destructive browse, and the redrive semantics (origin-topic routing, idempotency, partial
  failure). Distinct from `redelivery-timers`, which owns how a message *becomes* dead-lettered.

### Modified Capabilities
- `redelivery-timers`: the **Dead-letter topic routing** requirement changes — republishing SHALL also
  add an `x-original-topic` attribute. Additive; existing attributes and payload-byte preservation are
  unchanged.

## Impact

- **APIs:** two new REST endpoints under `/v1/subscriptions/{id}/dead-letters`. No change to any
  existing endpoint's request or response shape. No gRPC contract change, so no Lexicon bump.
- **Code:** `server/.../http` (routes, JSON codecs), `server/.../delivery` (`DeadLetterRouter` gains the
  new attribute; a new projection + repository for the read model alongside
  `SubscriptionIndexProjection` / `TopicSubscriptionsRepository`), and the redrive path reusing the
  Topic aggregate's idempotency-key dedup.
- **Schema:** one new table for the dead-letter read model, added to
  `server/src/main/resources/schema/postgres.sql` — additive and `IF NOT EXISTS`, so boot-time
  self-migration covers it with no operator action.
- **Compatibility:** no breaking changes. `x-original-topic` is additive; messages dead-lettered
  *before* this change lack it, so browse and redrive must tolerate its absence and fall back to the
  subscription→topic join. Dead-letters that predate the read model will not appear in browse — the
  projection is maintained forward, consistent with the other read models here.
- **Operators:** strictly more visibility. With no dead-letter topic configured, dead-letters are
  today dropped with a warning log; after this change they are still recorded in the read model, so
  browse and redrive work regardless of that setting.
