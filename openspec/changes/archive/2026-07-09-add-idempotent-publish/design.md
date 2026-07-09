## Context

Every `Publish` mints a fresh server-side `MessageId` (`buildMessage` in `PubSubGrpcService`/`PubSubRoutes`), so an at-least-once producer that retries a timed-out publish creates a second, independently-delivered message. The Topic aggregate (`domain/Topic.scala`) is an event-sourced `decide`/`evolve` pair whose `Publish` path today only checks the topic is active and emits `MessagePublished(message)`; `TopicState` holds no per-message data. Under Cluster Sharding there is exactly one live entity per `topicId`, so all publishes to a topic are serialised through a single writer — the property that makes hot-path dedup correct rather than best-effort.

This mirrors, but is deliberately *not* built like, message TTL (v1.3.0). TTL is a delivery-time **drop** and was implemented off the hot path (a durable `expiring_messages` read model + `TtlSweeper` + the Subscription aggregate filtering leases). Dedup is a publish-time **accept/reject** and cannot be eventually-consistent: two retries arriving milliseconds apart must be resolved against the same authoritative state, which only the aggregate provides.

## Goals / Non-Goals

**Goals:**
- Optional producer idempotency key; a retry with the same key inside a bounded window is collapsed to the original publish (no second `MessagePublished`, no second delivery) and returns the **original** `messageId`.
- Strong dedup *within one topic, within the window* — survives entity passivation/recovery (replay rebuilds the seen-set) and snapshotting.
- Non-breaking and opt-in: absent key or `window = 0` reproduces today's behaviour exactly; existing journals/snapshots deserialize unchanged.

**Non-Goals:**
- Cross-topic or global dedup (keys are scoped per topic).
- Dedup beyond the window (a key seen longer ago than the window is treated as new — this is a memory/guarantee trade, not exactly-once forever).
- Consumer-side / end-to-end exactly-once *delivery* (that is redelivery + ack territory, unchanged here).
- A per-publish window override (the window is a broker retention policy, not a per-message concern).

## Decisions

**1. Dedup on the aggregate hot path, in `TopicState` — not a read model.**
`TopicState` gains `seen: Map[String, SeenPublish]` (`key → (messageId, publishTime)`). `Topic.decide` for `Publish`, after the existing active-topic check, consults `seen`; `evolve` maintains it. Chosen over a TTL-style durable read model + sweeper because the accept/reject must be strongly consistent against concurrent retries — an eventually-consistent index would let a near-simultaneous duplicate slip through. The single-writer sharded entity makes the in-state check race-free.

**2. Duplicate ⇒ no event; `evolve` prunes the window deterministically.**
On a hit, `decide` returns `Right(Nil)` (persist nothing) — the cheapest possible duplicate path. On a miss, it returns `MessagePublished(message)` as today; `evolve` then inserts `key → (message.id, message.publishTime)` **and drops every entry older than `message.publishTime − window`**. Pruning keys off the incoming event's `publishTime` keeps `evolve` a pure, wall-clock-free function (required for deterministic replay) while bounding the set to roughly one window of traffic. Alternative — a periodic prune command/sweeper — was rejected as unnecessary machinery for a self-pruning structure.

**3. The publish reply becomes aggregate-driven: `PublishResult(messageId, deduplicated)`.**
Today the handler discards the entity reply and echoes its own generated id. To honour the idempotent contract (a retry returns the *same* id it got the first time), the effective id must come from the decision: `(message.id, false)` on accept, `(seen(key).messageId, true)` on a hit. This extends the `Publish` command's reply plumbing (`CommandReply`/`EntityEffects`) to carry a value; gRPC `PublishResponse` and the REST body surface `deduplicated`. Alternative — keep echoing the retry's id — was rejected: it returns an id that maps to no delivered message, defeating idempotency.

**4. Idempotency key is a first-class field, added to the proto in the-lexicon.**
`Message` gains `idempotencyKey: Option[String]`; `PublishRequest` gains `idempotency_key` in the Hermes proto (the-lexicon), released as a new `lexicon-hermes-grpc` version and pinned via `lexiconVersion`. Chosen over a reserved `attributes` entry (e.g. `x-idempotency-key`) for discoverability and type-clarity, consistent with treating the Lexicon as the wire contract. Trade-off: one more cross-repo release, now a well-trodden path. An empty string is normalised to `None` (no key).

**5. Config `hermesmq.dedup.window` (Duration), default `0` = disabled.**
Mirrors `TtlConfig` precisely: `0` disables (feature inert, key ignored), negative fails fast at startup. `DedupConfig(window: FiniteDuration)`. No default-on behaviour, so upgrading changes nothing until an operator opts in.

**6. Serialization stays backward-compatible.**
`Message`'s JSON format writes `idempotencyKey` only when present and reads it tolerantly (absent → `None`), exactly as `expireTime` was added. The `TopicState` snapshot format includes `seen` tolerantly (absent → empty), so pre-existing snapshots recover as an empty window.

## Risks / Trade-offs

- **Seen-set memory & snapshot size grow with `window × publish-rate`.** → On-write pruning bounds it to ~one window of keys; document the sizing relationship and keep the default window `0`. Operators pick a window that fits their retry horizon (seconds–minutes), not hours.
- **`publishTime` is server clock time and only near-monotonic.** A key's window boundary is therefore approximate under clock adjustment. → Acceptable: the window is a best-effort retention bound, not a hard SLA at the millisecond edge; the *within-window* guarantee (the common retry case) is unaffected because it compares two recent timestamps on the same entity.
- **Unbounded distinct keys within a window are all retained.** → Bounded by the window; a pathological producer minting unique keys just fills and prunes normally. Note in docs.
- **Aggregate-reply plumbing change touches the publish path for all publishes.** → Contained to the `Publish` reply type; covered by existing `TopicEntitySpec`/publish-surface tests plus new dedup cases, and the non-key/disabled path is asserted identical to today.
- **Another the-lexicon release couples this change to the contract repo.** → Sequence it first (proto + release + `lexiconVersion` bump) exactly like `adopt-lexicon-grpc-contracts`; the server work then builds against the pinned version.

## Migration Plan

1. the-lexicon: add `idempotency_key` to `PublishRequest`, release `lexicon-hermes-grpc` (next SemVer), then bump `lexiconVersion` in HermesMQ `build.sbt`.
2. Ship the server change with `hermesmq.dedup.window = 0` (default). Behaviour is unchanged until an operator sets a positive window.
3. Rollback: set the window back to `0` (runtime-inert) or redeploy the prior image — old journals/snapshots and messages without keys remain fully compatible in both directions.

## Open Questions

- Should duplicate collapses emit an observability counter (e.g. `hermes_publish_deduplicated_total`)? Leaning yes as a small optional add under the existing metrics capability, but not required for the core feature.
