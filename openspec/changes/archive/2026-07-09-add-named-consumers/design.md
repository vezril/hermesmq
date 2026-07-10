## Context

HermesMQ's subscription already delivers each message to exactly one of its concurrent consumers (exclusive lease + visibility timeout, serialized through the single-writer `SubscriptionEntity`). What's missing is *identity*: `Pull`/`StreamMessages`/`Consume` read only `subscriptionId` + `max` (no consumer field), so there is no way to count or name the workers attached to a subscription. Metrics today come from durable read models rendered by `observability/PrometheusText.render(subscriptions, topics, now)` and served at `ObservabilityRoutes` `/metrics`. Structured JSON logging (v1.7.0) already promotes MDC entries to top-level fields, so an MDC `consumer` value would appear in logs for free. The gRPC contract lives in the external `lexicon-hermes-grpc` artifact.

## Goals / Non-Goals

**Goals:**
- Let a consumer optionally name itself on every consume call; surface a per-subscription **active named-consumer count** on `/metrics`.
- Make consume-path logs attributable per worker via an MDC `consumer` field.
- Keep it off the event-sourced hot path and non-breaking (anonymous = today).

**Non-Goals:**
- Changing delivery/lease semantics or fairness — competing consumers already work; this is identity + observability only.
- Per-consumer lease attribution (which consumer holds which message) — that would put ephemeral identity into the aggregate/`MessageLeased`; explicitly out of scope.
- Cluster-wide consumer aggregation — the registry is per-node (exact under the single-replica deploy; a durable/gossip-based cross-node count is a later concern).
- Consumer-group-as-copy semantics (multiple named groups each getting the full stream) — that's what separate subscriptions already provide.

## Decisions

**1. Consumer id as a typed proto field, not gRPC metadata.**
Add `consumer_id` to `PullRequest`, `StreamRequest`, and `ConsumeStart` in the Hermes proto (the-lexicon), read as `in.consumerId` in the handlers. Chosen over a metadata header (which would avoid a Lexicon release but is undiscoverable and would mean poking the auth/PowerApi metadata plumbing): a typed field is trivial to read, self-documenting, and consistent with how `ttl_seconds` and `idempotency_key` were added. The cost is one more `lexicon-hermes-grpc` release (a proven ~minutes step). REST pull carries it as an optional body/query field. Empty string normalises to "no consumer".

**2. In-memory `ConsumerRegistry`, not a read model or aggregate state.**
`ConsumerRegistry` holds `Map[SubscriptionId, Map[ConsumerId, Instant]]` (last-seen). `touch(sub, consumer, now)` on each consume call carrying an id; `activeCount(sub, now)` counts entries newer than `now - activityWindow`; stale entries are pruned lazily on read/touch. Chosen over (a) a durable read model — consumer liveness is ephemeral and high-churn; DB writes per pull would tax the hot path for a best-effort signal; and (b) aggregate state — the same reason plus it would pollute the event journal with transient identity. The registry is a plain concurrent map guarded for the single JVM.

**3. Render the gauge from the registry inside `PrometheusText`.**
Extend the metrics exposition to include `hermesmq_subscription_consumers{subscription="…"} N`, sourced by asking the registry for each known subscription's active count at render time (`now` is already threaded into `render`). Chosen over a separate metrics endpoint or a background counter: keeps one Prometheus surface and computes the count lazily at scrape time, so there's no counter to keep consistent.

**4. MDC `consumer` set around the consume call only.**
The handler sets `MDC.put("consumer", id)` before serving and clears it after, so log lines emitted during that consume carry a top-level `consumer` field (v1.7.0 promotes MDC). For streaming/bidi, set it per demand-driven pull batch. Best-effort and scoped; no correlation-id framework (that's a later rung).

**5. Config `hermesmq.consumers.activity-window` (default 60s, `0` disables).**
Bounds how long a silent consumer still counts as active and disables the registry/metric when `0`. Parsed by a small `ConsumersConfig`, negative fails fast — mirroring `TtlConfig`/`DedupConfig`.

## Risks / Trade-offs

- **Per-node count under-reports in a multi-node cluster.** → Documented; exact under the single-replica k3s deploy. A future durable/gossip aggregation can replace the in-memory view without changing the metric's name or meaning.
- **Registry memory grows with distinct consumer ids per subscription.** → Bounded by active-window pruning; a pathological producer of unique ids self-prunes. The map is small (ids × subscriptions within one window).
- **Activity window is a heuristic** — a consumer that pulls less often than the window looks "gone". → Operator sizes the window to the expected pull cadence; default 60s suits typical workers.
- **MDC hygiene** — a leaked MDC key would mislabel unrelated logs. → Set/clear in a `try/finally` around the consume call; scoped to the request thread.

## Migration Plan

1. the-lexicon: add `consumer_id` to the three consume messages, release `lexicon-hermes-grpc` (next SemVer), bump `lexiconVersion`.
2. Ship the server change; the metric appears (reading 0) and consume stays anonymous until clients start sending ids. Fully backward-compatible.
3. Rollback: set `activity-window = 0` (registry/metric inert) or redeploy the prior image; no persistent state to undo.

## Open Questions

- Whether to also emit a per-consumer last-seen admin listing (like the subscription listing). Leaning no for now — the gauge covers the "how many workers" question; a listing is a later add if operators want per-consumer detail.
