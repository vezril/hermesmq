## 1. Contract (the-lexicon prerequisite)

- [x] 1.1 Add optional `consumer_id` (string) to `PullRequest`, `StreamRequest`, and `ConsumeStart` in the Hermes proto in `the-lexicon`, preserving package/API compatibility
- [x] 1.2 Release a new `lexicon-hermes-grpc` SemVer version from `the-lexicon` (tag-driven publish to GitHub Packages)
- [x] 1.3 Bump `lexiconVersion` in HermesMQ `build.sbt` and confirm `sbt compile` resolves the updated stubs

## 2. Consumers configuration

- [x] 2.1 Add a failing `ConsumersConfigSpec`: `activity-window` defaults to `60s`, honours an override, `0` disables (`enabled == false`), and a negative value fails fast
- [x] 2.2 Implement `config/ConsumersConfig.scala` reading `hermesmq.consumers.activity-window` (mirror `TtlConfig`/`DedupConfig`: `0` = off, negative fails fast); add it to `application.conf` with a `HERMESMQ_*` env override; make the tests green

## 3. Consumer registry (pure, TDD)

- [x] 3.1 Add a failing `ConsumerRegistrySpec`: `touch` then `activeCount` counts distinct ids seen within the window; an id last seen beyond the window is not counted; two ids on one subscription → 2; a `0`/disabled window tracks nothing; `subscriptions` lists only those with active consumers
- [x] 3.2 Implement `observability/ConsumerRegistry.scala` (`Map[SubscriptionId, Map[String, Instant]]`, `touch`, `activeCount(now)`, `activeCountsBySubscription(now)`, lazy prune); make the tests green

## 4. Metric rendering

- [x] 4.1 Add failing `PrometheusText` cases: given active-consumer counts, the exposition includes `hermesmq_subscription_consumers{subscription="…"} N` with `# HELP`/`# TYPE`, escapes label values, and emits no samples (but keeps `# TYPE`) when there are none
- [x] 4.2 Extend `PrometheusText.render` to take the active-consumer counts and emit the gauge; wire `ObservabilityRoutes` to source them from the registry at scrape time; make section-4 tests green

## 5. Consume surfaces + MDC (TDD)

- [x] 5.1 Add failing `PubSubGrpcServiceSpec` cases: `pull`, `streamMessages`, and `consume` read `consumer_id`, touch the registry (assert the subscription's active count reflects the consumer), and an empty id is treated as anonymous (no touch)
- [x] 5.2 Add a failing `PubSubRoutesSpec` case: the REST pull accepts a consumer id and touches the registry
- [x] 5.3 Thread `consumer_id` through the gRPC + REST consume handlers to `registry.touch`, and set/clear the `consumer` MDC key around each consume call (`try/finally`, asserted set during / cleared after); make section-5 tests green

## 6. Wiring, regression & docs

- [x] 6.1 Wire `ConsumersConfig` + a shared `ConsumerRegistry` through `Main` into `PubSubGrpcService`, `PubSubRoutes`, and `ObservabilityRoutes`
- [x] 6.2 Run the full suite (`sbt test`) and confirm the anonymous consume path and existing metrics are unchanged (no regressions)
- [x] 6.3 Document named consumers in the README: the optional consumer id on consume, the `hermesmq_subscription_consumers` gauge, `HERMESMQ_*` activity-window config, the `consumer` MDC/log field, and the per-node registry caveat
