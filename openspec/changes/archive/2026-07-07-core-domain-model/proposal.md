## Why

HermesMQ has a running service shell but no domain. Before persistence (Feature 4) can journal anything, the broker needs its core vocabulary — the immutable data models, the commands the write side accepts, and the events it emits — plus the pure state-transition logic that decides events from commands and evolves state from events. Defining this now, as plain functional code with no Pekko Persistence, lets us test all delivery/lifecycle rules exhaustively and gives Feature 4 a ready decide/evolve core to wrap in `EventSourcedBehavior`.

## What Changes

- Introduce immutable **data models** as type-safe value objects: identifiers (`TopicId`, `SubscriptionId`, `MessageId`, `AckId`) as opaque/newtype wrappers with validation, a `Message` envelope (id, payload, attributes, publish time), and supporting types (`AckDeadline`, timestamps).
- Define the **command** and **event** algebras (sealed ADTs) for the two write-side aggregates:
  - **Topic**: `CreateTopic`, `Publish` → `TopicCreated`, `MessagePublished`.
  - **Subscription**: `CreateSubscription`, `Acknowledge`, `ModifyAckDeadline` → `SubscriptionCreated`, `MessageAcknowledged`, `AckDeadlineModified`.
- Implement the **pure aggregate logic** for each: a total `decide(state, command): Either[Rejection, List[Event]]` (validation + event derivation) and `evolve(state, event): State` (fold), with an explicit `Rejection` type. No side effects, no actors, no journal.
- Cover every rule and rejection with tests (TDD), including at least two edge cases per capability.

Scope note: this is deliberately a **"basic" slice** — Topic + Subscription lifecycle with publish/acknowledge/modify-deadline. Delivery/redelivery/expiry mechanics, projections, and read models are out of scope here and arrive in later features. The decide/evolve signatures are chosen to slot directly into Pekko Persistence in Feature 4.

## Capabilities

### New Capabilities
- `domain-model`: The immutable value types and the command/event ADTs — identifiers, the `Message` envelope, deadlines/timestamps, and the structural invariants they enforce.
- `topic-lifecycle`: The Topic aggregate's pure decision/evolution logic — creating a topic and publishing messages, with the rejections that guard invalid commands.
- `subscription-lifecycle`: The Subscription aggregate's pure decision/evolution logic — creating a subscription and acknowledging / modifying the ack deadline of outstanding messages, with rejections.

### Modified Capabilities
<!-- None. The existing health-endpoint / docker-packaging / scaffolding / ci-cd specs are unchanged. -->

## Impact

- **New source**: a `domain` package (`me.cference.hermesmq.domain`) with value types, `command`/`event` ADTs, `Rejection`, and `Topic`/`Subscription` aggregate objects exposing `decide`/`evolve`. Corresponding test suites.
- **Dependencies**: none new expected — this is pure Scala on the existing stack. (A tiny value-equality/UUID helper may use `java.util`.)
- **No runtime wiring**: `Main`/HTTP are untouched; the domain is not yet reachable over the API. Feature 4 wires these aggregates into persistent actors; a later feature exposes them via gRPC.
- **Foundation**: `decide`/`evolve` become the command/event handlers of the Feature-4 `EventSourcedBehavior`s, so their shape is a deliberate contract.
