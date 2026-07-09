## 1. Contract (the-lexicon prerequisite)

- [x] 1.1 Add optional `idempotency_key` (string) to `PublishRequest` in the Hermes proto in `the-lexicon` (`hermes-grpc/.../hermesmq/v1/hermes.proto`), preserving package and API compatibility
- [x] 1.2 Release a new `lexicon-hermes-grpc` SemVer version from `the-lexicon` (tag-driven publish to GitHub Packages)
- [x] 1.3 Bump `lexiconVersion` in HermesMQ `build.sbt` to the new release and confirm `sbt compile` resolves the updated stubs

## 2. Message model

- [x] 2.1 Add failing `MessageSpec` cases: `idempotencyKey: Option[String]` round-trips via `Message.from`, and an empty-string key normalises to `None`
- [x] 2.2 Add `idempotencyKey: Option[String]` to `Message` (default `None`) and normalise empty → `None` in `Message.from`; make the tests green with `-Werror` clean

## 3. Dedup configuration

- [x] 3.1 Add failing `DedupConfigSpec`: `0` → disabled, positive → that window, negative → load failure
- [x] 3.2 Implement `config/DedupConfig.scala` reading `hermesmq.dedup.window` (mirror `TtlConfig`: `0` = off, negative fails fast); add `hermesmq.dedup.window = 0` to `reference.conf`/`application.conf`

## 4. Topic aggregate deduplication

- [x] 4.1 Add failing `TopicSpec` cases: first key emits `MessagePublished`; duplicate key within window emits no event and yields the original `messageId` with `deduplicated = true`; window-expired key re-publishes; two different keys both publish; no-key always publishes; `decide` stays total
- [x] 4.2 Add failing `TopicSpec` `evolve` cases: `MessagePublished` records `key → (messageId, publishTime)` in `TopicState.seen` and prunes entries older than `publishTime − window`, using no wall-clock
- [x] 4.3 Add `seen: Map[String, SeenPublish]` to `TopicState`; implement the dedup decision in `Topic.decide` (after the active-topic check) and the record-and-prune in `Topic.evolve`, parameterised by the window; make section-4 tests green

## 5. Aggregate-driven publish reply

- [x] 5.1 Add failing `TopicEntitySpec` cases: publishing a fresh key persists `MessagePublished` and replies `PublishResult(id, deduplicated = false)`; a duplicate within window persists nothing (`hasNoEvents`) and replies `PublishResult(originalId, deduplicated = true)`
- [x] 5.2 Extend the `Publish` command reply plumbing (`CommandReply`/`EntityEffects`) to carry `PublishResult(messageId, deduplicated)`; wire `TopicEntity` to pass the configured window into `decide`/`evolve`; make section-5 tests green

## 6. Serialization & snapshot compatibility

- [x] 6.1 Add failing serialization cases: `Message` JSON writes `idempotencyKey` only when present and reads it tolerantly (absent → `None`); `TopicState` snapshot includes `seen` and a snapshot without it recovers as empty
- [x] 6.2 Update `JsonFormats` for `Message.idempotencyKey` and the `TopicState` snapshot format (tolerant reads); make the tests green and confirm old-journal/old-snapshot compatibility

## 7. Publish surfaces (gRPC + REST)

- [x] 7.1 Add failing `PubSubGrpcServiceSpec` cases: `publish` forwards `idempotency_key` into the message and returns `deduplicated` from the aggregate result (duplicate → original `messageId`)
- [x] 7.2 Add failing `PubSubRoutesSpec` cases: the REST publish body accepts the key and the response reports `deduplicated`
- [x] 7.3 Thread the key through `buildMessage` on both surfaces and surface `deduplicated` (gRPC `PublishResponse`, REST body) from `PublishResult`; make section-7 tests green

## 8. Integration & regression

- [x] 8.1 Add a Postgres integration case (tagged, opt-in) asserting a duplicate key is deduplicated across topic-entity passivation/recovery within the window
- [x] 8.2 Run the full suite (`sbt test`) and confirm the no-key / `window = 0` path is byte-for-byte identical to prior behaviour (no regressions)

## 9. Documentation

- [x] 9.1 Document `hermesmq.dedup.window` and the idempotency-key publish semantics (guarantee scope, window sizing) in the README/config reference

## 10. Optional — observability

- [ ] 10.1 (Optional) Add a `hermes_publish_deduplicated_total` counter incremented on a duplicate collapse, under the existing observability capability
