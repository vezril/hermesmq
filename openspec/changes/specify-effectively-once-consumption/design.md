# Design

## Context

See `proposal.md` — Why. The relevant existing behaviour:

- `DeliveryHandler` builds the key as `AckId.from(s"${subscriptionId.value}:${messageId.value}")` — a
  pure function of the pair, with no attempt counter, timestamp, or nonce.
- Redelivery goes through `RedeliverySweeper`, which makes an existing lease available again; it does
  not re-issue a delivery, so the key cannot change.
- A nack is `modifyAckDeadline` with `ackDeadlineSeconds: 0`, which expires the same lease.
- `message-delivery` already notes key determinism, but inside a scenario about *projection replay*.
  A consumer author reading the spec for a dedup contract would not find it there.

So the property holds in three independent paths and is stated in none of them as a contract.

## Goals / Non-Goals

**Goals:**

- State the dedup contract where a consumer author will look for it.
- Test it on each path that could break it, so a future refactor fails loudly rather than silently
  removing a guarantee someone is relying on.
- Say plainly, in the spec and the README, that exactly-once is not offered.

**Non-Goals:**

- **Any behaviour change.** If this change alters delivery, it has failed.
- **A broker-side dedup store.** Remembering processed keys for the consumer is a real feature and a
  separate decision; it is also still not exactly-once. Out of scope here.
- **A transactional consume protocol.** The only construction that gets closer to exactly-once, and a
  far larger change to the ack protocol.
- **Client-side dedup helpers.** Worth adding to the three clients later; the contract has to exist
  first.

## Decisions

### State it as its own requirement, not an amended one

The existing at-least-once requirement is left untouched and a new requirement is added beside it.

*Why:* the two say different things to different readers. At-least-once is an *operator's* statement
about delivery effort; key stability is a *consumer author's* statement about how to build correctly on
top of it. Folding the second into the first would bury the contract in a paragraph about projections
and sharded daemon processes. It also keeps the delta additive, so archiving cannot accidentally
rewrite the delivery guarantee itself.

### Test the three redelivery paths separately

Deadline lapse, nack, and projection replay reach redelivery through different code. A single test
through one path would leave the other two free to drift.

*Why over a property test:* the paths are few, named, and each is a distinct mechanism; three explicit
tests read as documentation of *why* the key is stable, which a generated property would not.

### Name the boundary in the spec, not only the README

The requirement carries "The broker SHALL NOT claim exactly-once delivery" as normative text.

*Why:* the README is where a user reads it, but the spec is where a future change proposing
"exactly-once" would be checked against. Putting the boundary in the spec makes it an explicit
decision to overturn rather than an omission to fill.

## Risks / Trade-offs

- **The change looks like it does nothing** → It adds no behaviour, which is the point: it converts an
  accidental property into a guaranteed one and an impossible roadmap item into an honest boundary. The
  tests are the deliverable.
- **A consumer may read "effectively-once" as "exactly-once"** → Mitigated by documenting precisely
  where the guarantee stops, including the crash-after-write case that makes exactly-once impossible.
  Stated in both the spec and the README rather than hinted at.
- **Dedup state is the consumer's problem, unbounded in principle** → True, and named in the README:
  a consumer must decide how long to retain processed keys, and the right horizon is its redelivery
  window, not forever. A broker-side store would move that problem rather than remove it.

## Migration Plan

None. No behaviour, API, schema, or configuration changes; nothing to deploy differently and nothing
to roll back beyond the commit.
