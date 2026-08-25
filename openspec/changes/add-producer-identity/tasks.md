## 1. Contract (the-lexicon prerequisite)

- [x] 1.1 Add optional `producer_id` (string) to `PublishRequest` in the Hermes proto in `the-lexicon`, preserving package/API compatibility
- [x] 1.2 Release a new `lexicon-hermes-grpc` SemVer version from `the-lexicon` (tag-driven publish to GitHub Packages)
- [x] 1.3 Bump `lexiconVersion` in HermesMQ `build.sbt` and confirm `sbt compile` resolves the updated stubs

## 2. Producers configuration

- [x] 2.1 Add a failing `ProducersConfigSpec`: `activity-window` defaults to `60s`, honours an override, `0` disables (`enabled == false`), and a negative value fails fast
- [x] 2.2 Implement `config/ProducersConfig.scala` reading `hermesmq.producers.activity-window` (mirror `ConsumersConfig`); add it to `application.conf` with a `HERMESMQ_*` env override; make the tests green

## 3. Producer registry (pure, TDD)

- [x] 3.1 Add a failing `ProducerRegistrySpec`: `touch` then `activeCount` counts distinct ids seen within the window per topic; an id last seen beyond the window is not counted; two ids on one topic → 2; a `0`/disabled window tracks nothing; `activeCountsByTopic` lists only topics with active producers
- [x] 3.2 Implement `observability/ProducerRegistry.scala` (`Map[TopicId, Map[String, Instant]]`, `touch`, `activeCount(now)`, `activeCountsByTopic(now)`, lazy prune); make the tests green

## 4. Metric rendering

- [x] 4.1 Add failing `PrometheusText` cases: given producer counts, the exposition includes `hermesmq_topic_producers{topic="…"} N` with `# HELP`/`# TYPE … gauge`, and emits no samples (but keeps `# TYPE`) when there are none
- [x] 4.2 Extend `PrometheusText.render` to take the producer counts and emit the gauge; wire `ObservabilityRoutes` to source them from the `ProducerRegistry` at scrape time; make section-4 tests green

## 5. Publish surface + MDC (TDD)

- [x] 5.1 Add a failing `PubSubGrpcServiceSpec` case: a publish carrying `producer_id` touches the registry for that topic (assert the topic's active count reflects the producer), and an empty id is treated as anonymous (no touch)
- [x] 5.2 Add a failing `PubSubRoutesSpec` case: a REST publish carrying `producerId` touches the registry
- [x] 5.3 Thread `producer_id` through the gRPC + REST publish handlers to `registry.touch`, and set/clear the `producer` MDC key around each publish call (`try/finally`, asserted set during / cleared after); make section-5 tests green

## 6. Wiring, regression & docs

- [x] 6.1 Wire `ProducersConfig` + a shared `ProducerRegistry` through `Main` into `PubSubGrpcService`, `PubSubRoutes`, and `ObservabilityRoutes`
- [x] 6.2 Run the full suite (`sbt test`) and confirm the anonymous publish path and existing metrics are unchanged (no regressions)
- [x] 6.3 Document producer identity in the README: the optional producer id on publish, the `hermesmq_topic_producers` gauge, `HERMESMQ_*` activity-window config, the `producer` MDC/log field, and the per-node registry caveat
