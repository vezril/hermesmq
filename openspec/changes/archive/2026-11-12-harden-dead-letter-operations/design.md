# Design

## Context

See `proposal.md` — Why. What constrains the approach:

- `subscription_dead_letters` is keyed `(subscription_id, original_message_id)` with
  `subscription_dead_letters_sub_idx` on `(subscription_id, dead_lettered_at DESC)`. That index is
  exactly the keyset-pagination order, so no schema change is needed.
- `dead_lettered_at` is the journal envelope's timestamp, so two entries from the same envelope batch
  can share a timestamp. A cursor on time alone is therefore not unique.
- `markRedriven` today is an unconditional `UPDATE ... SET redriven_at = ?`, run *after* a successful
  publish. Nothing about it is a claim: two concurrent redrives both read `redrivenAt = None`, both
  publish, both mark.
- Redrive already passes an idempotency key to the Topic aggregate. That machinery stays — it is a
  useful second line of defence — but it is bounded by `HERMESMQ_DEDUP_WINDOW`, default `0s`, so it
  cannot be the primary guarantee.

## Goals / Non-Goals

**Goals:**

- Bounded browse responses, with stable paging under concurrent inserts.
- Redrive idempotency that holds on a default install, with no configuration.
- A way out when a claim is taken but the publish never happens.

**Non-Goals:**

- **Total ordering across subscriptions.** Paging is per-subscription, which is all the endpoint
  exposes.
- **A count of total dead-letters.** `COUNT(*)` on every page is a cost with no caller yet; the
  `deadLetteredTotal` metric already answers "how bad is it".
- **Backward compatibility of the browse response.** Deliberately broken now, while the capability is
  one release old and has no known consumer. Documented as BREAKING.
- **Reworking the publish-dedup machinery.** Left as-is beneath the new claim.

## Decisions

### Keyset pagination on `(dead_lettered_at, original_message_id)`, not offset

The cursor carries both the timestamp and the message id of the last row on the page, and the next
query asks for rows ordered `(dead_lettered_at DESC, original_message_id DESC)` strictly *after* that
pair. The message id breaks ties, which matters because entries from one envelope batch can share a
timestamp.

*Why over the alternatives:*

- **`OFFSET n`** shifts when a newer dead-letter is inserted mid-walk — the client silently re-reads
  or skips an entry. Dead-letters arrive precisely when an operator is paging through them, so this is
  the common case, not the edge case.
- **Timestamp-only cursor** drops entries that share a timestamp with the page boundary.

The cursor is an opaque base64 of `{t, id}`. Opaque because clients must not construct one: a
hand-made cursor would couple callers to the sort key and prevent ever changing it. An
undecodable cursor is a `400`, not a silent restart from the newest entry — a client paging with a
stale cursor should learn it, not quietly re-read the whole list.

### Claim-before-publish via a conditional UPDATE

`claimForRedrive` replaces `markRedriven`:

```sql
UPDATE subscription_dead_letters SET redriven_at = ?
WHERE subscription_id = ? AND original_message_id = ? AND redriven_at IS NULL
```

The affected-row count *is* the claim: `1` means this caller won and may publish, `0` means someone
else already has it. Postgres row-level locking makes this atomic without an explicit transaction, so
two concurrent redrives of the same entry cannot both win.

*Why over the alternatives:*

- **Read-then-write** (what ships today) is a lost-update race: both callers read `NULL`, both publish.
- **Relying on publish-dedup** depends on `HERMESMQ_DEDUP_WINDOW` being non-zero, and it defaults to
  zero. A guarantee that is off by default is not a guarantee.
- **A distributed lock** would add a dependency for something one SQL statement already does.

Ordering is claim-then-publish rather than publish-then-mark because the failure modes are not
symmetric. Publish-then-mark risks a *duplicate message* (published twice, marked once) — unbounded
harm in a message broker. Claim-then-publish risks a *stuck entry* (claimed, never published) —
recoverable, visible in the response, and now explicitly fixable with `force`.

### A failed publish releases the claim

If the republish fails, the claim is reset to `NULL` so a retry can take it. That leaves a narrow
window — crash between claim and release — where an entry stays claimed but unpublished. Hence:

### `force` to override a stale claim

`{"messageIds": [...], "force": true}` claims unconditionally, ignoring an existing `redriven_at`.
Without it, the crash window above is unrecoverable through the API, and the operator's only route
would be editing the read model by hand. `force` is deliberately explicit: the default path stays
safe, and bypassing the guard is something the caller has to ask for.

### `limit` defaults to 50, clamps at 500

A clamp rather than a `400`, because an over-large `limit` is a client being optimistic, not
malformed. Rejecting it would make a paging client fail on a value it could simply have been given
less of.

## Risks / Trade-offs

- **The browse response shape is breaking** → Accepted, and the point of doing it now. One release of
  exposure, no known consumer. Called out in the proposal, the README, and the release notes.
- **A claimed-but-unpublished entry needs `force`** → The operator sees `alreadyRedriven` with a
  `redrivenAt` timestamp, which is the signal. Documented on the endpoint. The alternative ordering
  trades this for duplicate publishes, which is worse.
- **`force` can produce duplicates if misused** → That is its purpose, and it requires `admin` scope
  like every redrive. Named `force` rather than something softer so the caller cannot misread it.
- **Paging walks a read model that is still being written** → A dead-letter arriving mid-walk appears
  on no page if it sorts above the cursor. Inherent to descending keyset paging over a live table;
  the client sees it on the next walk. Noted in the spec as the stability scenario.
- **Cursor opacity hides the sort key** → Intentional, but it means a client cannot resume from an
  arbitrary point, only from a cursor the service issued. Acceptable for an operator console.

## Migration Plan

1. Deploy. No schema change, no config change.
2. Any caller of the browse endpoint must read `deadLetters` instead of the top-level array. No such
   caller is known; the clients do not wrap this endpoint.
3. Existing `redrivenAt` values keep their meaning — an entry already marked stays claimed, and
   `force` reaches it if needed.

**Rollback:** revert the deploy. The read model is unchanged, so the prior version reads it fine.
