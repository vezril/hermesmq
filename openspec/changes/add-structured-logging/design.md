## Context

HermesMQ logs via SLF4J → Logback (`pekko-slf4j` + `logback-classic`), configured by `server/src/main/resources/logback.xml` — today a single `ConsoleAppender` with a human-readable `%d [%thread] %-5level %logger - %msg%n` pattern. The Docker image is built by sbt-native-packager (`DockerPlugin`) from `build.sbt`. The constellation's observability stack (Loki) and the `self-healing-loop` design (rung L0) need machine-parseable logs with a **shared field schema across services**, so a query by `service`/`level` resolves identically everywhere. This is a log-*format* change only — no log call sites, levels, or behaviour change, and existing tests (which don't assert log format) are unaffected. Codex authored the proposal + spec and supplied the constellation-standard recipe.

## Goals / Non-Goals

**Goals:**
- Emit one JSON object per log event in the container (`LOG_FORMAT=json`), with the shared schema (`@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `service`, `stack_trace` on error, MDC as top-level fields) and `service = hermesmq`.
- Keep local `sbt run` output human-readable (no `LOG_FORMAT` set → text).
- Match the constellation field schema exactly so logs don't drift between services.

**Non-Goals:**
- Shipping logs anywhere (the collector/agent's job) or any error-tracker SDK.
- Correlation-id propagation / MDC plumbing at call sites (the encoder passes through whatever MDC exists; populating MDC is a later concern).
- Changing log levels, call sites, or any runtime behaviour.

## Decisions

**1. `logstash-logback-encoder` for the JSON encoding.**
Add `net.logstash.logback % logstash-logback-encoder % 8.0` and use its `LogstashEncoder`, which emits exactly the shared schema (`@timestamp`, `level`, `logger_name`, `thread_name`, `message`, `stack_trace` on a throwable, and MDC entries as top-level fields) out of the box, with `service` injected via `<customFields>{"service":"hermesmq"}</customFields>`. Chosen over a hand-rolled JSON `PatternLayout` (would reproduce the schema by hand and mis-handle exceptions/MDC) — the encoder is also what the rest of the constellation uses, guaranteeing no drift.

**2. Env-selected appender via `LOG_FORMAT`, defaulting to text.**
`logback.xml` defines two appenders — `json` (LogstashEncoder) and `text` (the existing console pattern) — and selects one with `<property name="LOG_APPENDER" value="${LOG_FORMAT:-text}"/>` then `<root><appender-ref ref="${LOG_APPENDER}"/></root>`. Default `text` keeps local development readable; the container sets `LOG_FORMAT=json`. Chosen over separate config files or a `-D` system property: a single env toggle is the constellation template and needs no launch-flag wiring.

**3. `LOG_FORMAT=json` as a Docker image env default only.**
Set `dockerEnvVars := Map("LOG_FORMAT" -> "json")` in the server module's Docker settings, so the shipped image logs JSON while `sbt run` (no env) stays text. Chosen over baking `json` as the logback default (would make local dev JSON too) — the split falls exactly on "containerized vs local".

**4. Test the encoder output and the toggle directly, not global logging.**
Unit tests (a) render a real `ILoggingEvent` (ERROR + throwable + an MDC entry) through a `LogstashEncoder` configured like the `json` appender and assert the parsed JSON carries the required fields, and (b) load `logback.xml` into a *fresh* `LoggerContext` with `LOG_FORMAT` set/unset and assert the active root appender is `json`/`text`. This exercises the two spec requirements without reconfiguring the shared test logger.

## Risks / Trade-offs

- **Encoder dependency weight / transitive Jackson.** → `logstash-logback-encoder` is small and standard; it pulls Jackson, already a common transitive. No Pekko version interaction (it's a logging lib).
- **A wrong `LOG_FORMAT` value would resolve to a missing appender ref.** → `${LOG_FORMAT:-text}` only substitutes; an unknown value (e.g. `LOG_FORMAT=jsonn`) would reference a non-existent appender. Acceptable: the two supported values are `json`/`text`, documented; logback logs a config warning and falls back rather than crashing. (Could harden later with an explicit `if`/`then` config, out of scope.)
- **Field-name drift from other services.** → Mitigated by using the same encoder + the agreed schema; the cross-service scenario is asserted at the field-name level.

## Migration Plan

1. Additive: new dependency + `logback.xml` rewrite + one Docker env default. No data, API, or behavioural change.
2. Rollout: the shipped image logs JSON immediately; operators who want text in-container set `LOG_FORMAT=text`.
3. Rollback: unset the Docker `LOG_FORMAT` default (or redeploy the prior image) — logging reverts to text; nothing else is affected.

## Open Questions

- None. Correlation-id/MDC population and log shipping are separate, later rungs.
