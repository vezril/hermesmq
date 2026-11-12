# Design

## Context

See `proposal.md` — Why. The constraints that shape the approach:

- `SubscriptionEvent.MessageDeadLettered(ackId, message, attempt)` carries the **full original
  message** — payload, attributes, publish time — not just an id. It is journaled on the subscription's
  own event stream and already tagged (`SubscriptionEntity.LeaseTag`), which `DeadLetterProjection`
  consumes today to republish to the dead-letter topic.
- `DeadLetterRouter.deadLetterMessage` mints a **deterministic** dead-letter id,
  `dead-letter:<subscription>:<originalId>`, and copies the original attributes plus three `x-*`
  metadata attributes.
- The service already has the machinery this change needs: cluster-wide single-instance projections
  (`ShardedDaemonProcess`), a JDBC read-model pattern (`SubscriptionIndexProjection` +
  `TopicSubscriptionsRepository`), idempotent-publish dedup inside the Topic aggregate, and
  additive-`IF NOT EXISTS` boot-time schema migration.
- Journal retention purges events behind the retained snapshots, so no read model here can be rebuilt
  from a from-zero replay. Every existing read model is maintained forward; this one must be too.

## Goals / Non-Goals

**Goals:**

- Browse that is genuinely non-destructive — no lease, no attempt increment, no interaction with the
  dead-letter topic's consumers.
- Redrive that is safe to retry and safe to double-click.
- Reuse the existing projection/read-model/dedup patterns rather than introducing a new mechanism.
- Partial success: one bad id in a redrive batch must not fail the others.

**Non-Goals:**

- **Per-subscription dead-letter topic configuration.** Deferred (see `proposal.md`). It changes
  config shape and routing, and the operator experience lands without it.
- **A gRPC surface.** Operator listings are REST-only here (`GET /v1/subscriptions`, `GET /v1/topics`);
  dead-letter operations follow that precedent. No Lexicon contract change, so no cross-repo bump.
- **Pagination of browse.** A first cut returns the subscription's recorded dead-letters. Deliberately
  flagged as a known limit below rather than designed now.
- **Purging/expiring the read model.** Redriven and resolved entries are marked, not garbage-collected.

## Decisions

### Build the read model from the subscription journal, not the dead-letter topic

The browse index is maintained by a projection over `MessageDeadLettered` on
`SubscriptionEntity.LeaseTag` — the same tag `DeadLetterProjection` already reads — writing one row per
dead-lettered message keyed `(tenant-qualified subscription id, original message id)`.

*Why over the alternatives:*

- **Projection over the dead-letter topic's journal** (what the proposal first sketched): the
  dead-letter topic is one global stream carrying every subscription's failures, so the index would
  have to parse `x-dead-letter-subscription` back out of attributes to regain the grouping the
  subscription stream has natively. It would also index nothing when no dead-letter topic is
  configured.
- **Scan the dead-letter topic per request**: O(topic) per browse, on the hot path, and `pull` leases —
  exactly the friction the issue reports.

A real consequence of this choice: because the event is journaled whether or not a dead-letter topic
exists, **browse and redrive work with no dead-letter topic configured**, where today the message is
dropped with only a warning. That is a visibility improvement the spec now requires.

### Key redrive idempotency on the original message id, through the existing dedup path

Redrive republishes to the origin topic with idempotency key `redrive:<subscription>:<originalId>`,
handed to the Topic aggregate's existing seen-key dedup. Repeats inside `HERMESMQ_DEDUP_WINDOW`
collapse; the aggregate is the single writer for the topic, so this is strongly consistent per topic
and survives entity recovery.

*Why:* the dedup machinery already exists, is already journaled and snapshotted, and is already the
documented answer to "a producer retried". Redrive is that same problem with an operator as the
producer. The alternative — a `redriven_at` marker in the read model, checked before publishing — is
only eventually consistent and races two concurrent redrives of the same id.

*Accepted consequence:* if the operator runs with `HERMESMQ_DEDUP_WINDOW=0` (the default — dedup off),
redrive idempotency is off too. The design records this honestly rather than silently depending on a
non-default setting; the read-model marker below is the mitigation, and the task list includes
surfacing it in the endpoint's documented behaviour.

### Resolve the origin topic from the message, falling back to the subscription

`x-original-topic` is added to the dead-letter envelope so a replay is self-contained, and the read
model stores the origin topic as its own column. For rows recorded before this change — and for
messages whose attributes lack it — the origin topic is resolved from the subscription's own topic,
which the subscription aggregate and `topic_subscriptions` both already know.

*Why:* the issue asks for `x-original-topic` specifically so the Zeus console stops joining against
the subscriptions listing. Storing it as a column rather than re-parsing attributes on read keeps
browse a single select.

### One additive table, provisioned by the existing boot migration

A `subscription_dead_letters` table (subscription id, original message id, origin topic, payload,
attributes, attempt count, dead-letter time, redriven-at) added to `schema/postgres.sql` with
`IF NOT EXISTS`. No operator action: `HERMESMQ_DB_MIGRATE_ON_START` defaults true and the schema is
idempotent.

### Redrive reports per-id outcomes

The response partitions ids into redriven / already-redriven / unknown / failed-with-reason, rather
than failing the request on the first bad id. A deleted origin topic is the motivating case: it is a
per-message failure, not a request-level one.

## Risks / Trade-offs

- **Unbounded browse response** → A subscription that dead-letters heavily returns a large payload.
  Mitigated for now by the read model being cheap to query and the endpoint being operator-facing, not
  hot-path. Pagination is a non-goal here and should land before this is pointed at a high-volume
  deployment.
- **Payloads stored twice** → The read model copies payload bytes that also live in the journal (and,
  when configured, on the dead-letter topic). Accepted: dead-letters are by definition the rare tail,
  and storing the payload is what makes redrive possible after journal retention has purged the
  original event. Worth revisiting if a deployment dead-letters at volume.
- **Redrive idempotency depends on a non-default setting** → With `HERMESMQ_DEDUP_WINDOW=0`, a repeated
  redrive republishes. Mitigated by the read model's `redriven_at` marker as a best-effort second line
  of defence, and by documenting the dependency on the endpoint. Fully closing it would mean dedup
  keyed independently of the global window — larger than this change.
- **Eventually-consistent browse** → A message dead-lettered milliseconds ago may not appear yet.
  Consistent with every other read model here and with how `deadLetteredTotal` already behaves; the
  endpoint documents it.
- **Read model cannot be backfilled** → Dead-letters predating this change are invisible to browse,
  and journal retention means a replay cannot recover them. The spec requires this degrade quietly
  rather than error. Operators upgrading mid-incident should drain the dead-letter topic the old way.
- **New projection to supervise** → One more cluster-wide singleton that can lag or fail. It runs
  alongside the existing ones under the same `ShardedDaemonProcess` supervision, so it inherits their
  restart and offset-resume behaviour rather than adding a new failure mode.

## Migration Plan

1. Deploy. Boot-time migration creates the table; the projection starts from the journal head and
   begins recording new dead-letters.
2. Both endpoints are live immediately and return only what the projection has seen — empty on a fresh
   deploy, which the spec requires be a `200`, not an error.
3. No config change, no restart ordering constraint, no change to existing endpoints.

**Rollback:** revert the deploy. The table is additive and unread by the prior version, so it can be
left in place; the dropped `x-original-topic` attribute is additive on new dead-letters and ignored by
the prior version's consumers.

## Open Questions

- Should a successful redrive **delete** the read-model row or only mark `redriven_at`? Marking is
  assumed (it keeps an audit trail and makes "already redriven" reportable), and either choice
  satisfies the specs as written — so this can be settled during implementation.
- Should browse expose the dead-letter topic's own message id (`dead-letter:<sub>:<id>`) alongside the
  original id, for operators correlating against a dead-letter-topic consumer? Additive to the
  response either way.
