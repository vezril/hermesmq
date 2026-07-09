## Why

A producer that publishes, times out, and retries currently creates a **second, distinct message** — HermesMQ mints a fresh server-side `MessageId` on every `Publish`, so at-least-once producers cause duplicate delivery. Giving producers an optional **idempotency key** lets the broker recognise a retry and collapse it to the original publish, providing exactly-once *publish* semantics within a bounded window — the standard safety net offered by Kafka's idempotent producer and GCP/AWS Pub/Sub.

## What Changes

- Add an optional producer-supplied **idempotency key** to the publish surface (gRPC `PublishRequest.idempotency_key` and the REST publish body). Empty/absent key = today's behaviour (no dedup), so the change is **non-breaking**.
- The **Topic aggregate** maintains a windowed set of recently-seen keys (`key → (messageId, publishTime)`) in its state. On `Publish` with a key already seen inside the window, it **persists no new message** and replies with the **original** `messageId` plus a `deduplicated` flag. Because each topic is a single-writer sharded entity, this is a strong guarantee within the window (not best-effort), rebuilt on replay and preserved across snapshots.
- The publish **reply becomes aggregate-driven**: the effective `messageId` and `deduplicated` flag come from the entity decision rather than the handler echoing its own freshly-generated id.
- New config `hermesmq.dedup.window` (Duration): `0` disables dedup (default, opt-in like TTL), negative fails fast. The window bounds both how long a key is remembered and the size of the seen-set (pruned on write against the newest publish time).
- Extend `Message` with `idempotencyKey: Option[String]` and tolerant (absent → `None`) JSON serialization, and include the seen-set in the `TopicState` snapshot format, so existing journals/snapshots stay valid.
- Prerequisite: add `idempotency_key` to the Hermes proto in **the-lexicon** and cut a new pinned `lexiconVersion` (the gRPC stubs are consumed as a published artifact, not generated locally).

## Capabilities

### New Capabilities
- `idempotent-publish`: producer idempotency-key dedup — key semantics, the dedup window and its config, duplicate-collapses-to-original-messageId with a `deduplicated` response flag, and the publish-surface field on both gRPC and REST.

### Modified Capabilities
- `topic-lifecycle`: the Topic aggregate gains a windowed seen-key set in `TopicState`; its `decide`/`evolve` for `Publish` now dedups (echo original id, persist nothing) and prunes expired keys, and the publish reply carries the effective `messageId` + `deduplicated` flag.

## Impact

- **Domain** (`domain/.../Message.scala`, `Topic.scala`): new `Message.idempotencyKey`; `TopicState` gains `seen`; dedup + prune logic in `Topic.decide`/`evolve`; new publish reply value.
- **Server** (`persistence/JsonFormats.scala`, `TopicEntity.scala`, `CommandReply.scala`/`EntityEffects.scala`, `grpc/PubSubGrpcService.scala`, `http/PubSubRoutes.scala`, new `config/DedupConfig.scala`): serialization, aggregate wiring, data-carrying publish reply, both publish surfaces read the key, config load + fail-fast.
- **Contract** (the-lexicon): `PublishRequest.idempotency_key`; new `lexicon-hermes-grpc` release; bump `lexiconVersion` in `build.sbt`.
- **Config/docs**: `hermesmq.dedup.window` in reference config and README; `application.conf`.
- **No breaking changes**: dedup is off by default and inert without a key; old messages/journals/snapshots deserialize unchanged.
