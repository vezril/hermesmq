# Proposal

## Why

The README roadmap promises "exactly-once delivery" as future work. **That promise cannot be kept.** A
broker cannot make a consumer's side effects atomic with its own ack: the consumer may crash after
writing to its database and before the ack lands, and no protocol the broker controls can undo the
write or know it happened. Exactly-once delivery is not a feature HermesMQ is missing — it is a
guarantee no broker of this shape can offer.

What HermesMQ *can* offer, and in fact already implements, is **effectively-once consumption**:
at-least-once delivery plus a stable key the consumer deduplicates on. The pieces are all present —
the `ackId` is deterministic per `(subscription, message)`, projection replay is idempotent, and
publish-side dedup shipped with idempotent publish — but nothing states the contract, nothing tests
it, and no documentation tells a consumer author what to do. So operators get the guarantee by
accident, if at all.

This change replaces an unkeepable promise with a stated, tested one.

## What Changes

- **A specified dedup contract.** The `ackId` is normatively stable across redeliveries of the same
  message to the same subscription, and documented as the key a consumer deduplicates on. Today that
  stability is an implementation property noted in a scenario about projection replay; it becomes a
  requirement in its own right, which is what a consumer author needs in order to rely on it.
- **Conformance tests** proving the property that matters: a message redelivered after an ack-deadline
  lapse, after a nack, and after a projection replay carries the *same* `ackId` every time.
- **The effectively-once recipe documented** — how to combine `idempotencyKey` on publish with `ackId`
  on consume to get end-to-end once-only processing, and precisely where the guarantee stops.
- **The roadmap row renamed**, not deleted: "exactly-once delivery" becomes an explicit statement that
  it is not offered and why, pointing at the effectively-once pattern instead. A roadmap that lists
  impossible work is worse than one that is honest about the boundary.

**No behaviour changes.** No endpoint, payload, schema, or config changes. This is a specification and
documentation change plus the tests that hold the existing behaviour in place.

## Capabilities

### New Capabilities
*(none — this states an existing guarantee)*

### Modified Capabilities
- `message-delivery`: gains a requirement that the delivery key is stable across redeliveries and is
  the consumer's deduplication key. The existing at-least-once requirement is unchanged; this names
  the property that makes at-least-once *usable*.

## Impact

- **Code:** none in production paths. The guarantee already holds; this change tests it.
- **Tests:** new conformance cases in the delivery and redelivery suites covering ackId stability
  across deadline expiry, nack, and replay.
- **APIs:** unchanged.
- **Docs:** a new README section on delivery semantics and the effectively-once recipe; the roadmap row
  rewritten from a promise into a boundary statement.
- **Risk:** the main risk is that a future change breaks ackId stability without noticing. That is
  exactly what the new tests prevent, and is the reason to land them even though nothing is broken
  today.
