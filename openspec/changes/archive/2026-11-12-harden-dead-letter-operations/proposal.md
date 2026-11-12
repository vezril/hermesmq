# Proposal

## Why

The dead-letter endpoints shipped in v1.14.0 with two known soft spots, both recorded in the README
roadmap rather than hidden. Both are the kind an operator hits on the first real incident:

1. **Browse is unpaginated.** It returns every dead-letter the read model holds for a subscription. A
   subscription that has been failing for hours returns one unbounded response.
2. **Redrive idempotency is off by default.** It keys on the publish-dedup path, which is bounded by
   `HERMESMQ_DEDUP_WINDOW` — default `0s`, i.e. disabled. On a default install, an operator who
   double-clicks "redrive all" publishes every message twice. The `redrivenAt` marker is written
   *after* the publish, so it cannot prevent a concurrent duplicate.

## What Changes

- **Paginated browse.** `GET /v1/subscriptions/{id}/dead-letters` accepts `limit` and `cursor` and
  returns an envelope: `{ deadLetters: [...], nextCursor? }`. Keyset pagination over
  `(deadLetteredAt, originalMessageId)` descending — stable under concurrent inserts, unlike offset.
  **BREAKING**: the response was a bare JSON array. The endpoint is one release old and has no known
  consumer yet, so this is the cheapest moment to fix the shape.
- **Redrive idempotency independent of configuration.** Redrive claims each entry with an atomic
  conditional update (`SET redriven_at = now() WHERE ... AND redriven_at IS NULL`) *before*
  publishing. Exactly one caller wins the claim; losers report `alreadyRedriven`. This no longer
  depends on `HERMESMQ_DEDUP_WINDOW`, which stays as a second line of defence rather than the only
  one. A failed publish releases the claim so the redrive can be retried.
- **A `force` flag on redrive**, to re-publish an entry already marked redriven. Needed because the
  claim is durable: without it, an entry claimed by a process that then crashed before publishing
  could never be redriven again.

## Capabilities

### New Capabilities
*(none — this hardens an existing capability)*

### Modified Capabilities
- `dead-letter-operations`: three requirements change. **Browse** gains pagination and a new response
  shape. **Redrive idempotency** becomes unconditional (claim-before-publish) rather than dependent on
  the dedup window. **Redrive** gains `force` to override a stale claim.

## Impact

- **APIs:** `GET .../dead-letters` response shape is **BREAKING** (array → envelope) and gains `limit`
  and `cursor` query parameters. `POST .../dead-letters/redrive` gains an optional `force` field;
  existing request bodies behave unchanged.
- **Code:** `server/.../http/DeadLetterRoutes.scala` (envelope, cursor encode/decode, `force`),
  `server/.../delivery/DeadLetterRepository.scala` (a paged `listFor`, and a `claimForRedrive`
  compare-and-swap replacing the unconditional `markRedriven`).
- **Schema:** no change. The existing `subscription_dead_letters_sub_idx` on
  `(subscription_id, dead_lettered_at DESC)` already supports the keyset scan.
- **Clients:** the Scala, Python and JS clients do not currently wrap these endpoints, so no client
  change is required. Worth adding later, tracked separately.
- **Docs:** README's dead-letter section loses the idempotency caveat and gains pagination; two
  roadmap rows are removed.
- **Release:** a minor bump with a BREAKING note on the browse response, since the capability is one
  release old and semver on a 1.x line permits it with the change called out.
