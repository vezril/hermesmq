## 1. Dedup counter (pure, TDD)

- [x] 1.1 Add a failing `DedupCounterSpec`: `increment(topic)` accumulates per topic; `counts` returns the per-topic totals; distinct topics count independently; an untouched counter is empty
- [x] 1.2 Implement `observability/DedupCounter.scala` (`Map[TopicId, Long]` via an atomic swap, `increment(topic)`, `counts`); make the tests green

## 2. Metric rendering

- [x] 2.1 Add failing `PrometheusText` cases: given dedup counts, the exposition includes `hermesmq_publish_deduplicated_total{topic="…"} N` with `# HELP`/`# TYPE … counter`, and emits no samples (but keeps `# TYPE`) when there are none
- [x] 2.2 Extend `PrometheusText.render` to take the dedup counts and emit the counter; wire `ObservabilityRoutes` to source them from the `DedupCounter` at scrape time; make section-2 tests green

## 3. Publish handlers increment (TDD)

- [x] 3.1 Add a failing `PubSubGrpcServiceSpec` case: a publish whose aggregate reply is `Published(_, deduplicated = true)` increments the dedup counter for that topic; a non-duplicate publish does not
- [x] 3.2 Add a failing `PubSubRoutesSpec` case: a REST publish that the aggregate reports as deduplicated increments the counter
- [x] 3.3 Increment the `DedupCounter` in the gRPC and REST publish handlers on the `Published(_, deduplicated = true)` branch; make section-3 tests green

## 4. Wiring, regression & docs

- [x] 4.1 Wire a shared `DedupCounter` through `Main` into `PubSubGrpcService`, `PubSubRoutes`, and `ObservabilityRoutes`
- [x] 4.2 Run the full suite (`sbt test`) and confirm no regressions (the non-dedup publish path and existing metrics are unchanged)
- [x] 4.3 Add `hermesmq_publish_deduplicated_total` to the README metrics table with the per-node caveat
