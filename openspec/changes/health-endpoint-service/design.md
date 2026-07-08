## Context

HermesMQ has a buildable sbt/Scala 3 skeleton on Pekko with CI/CD (Feature 1), but no runnable process. Feature 2 adds the "walking skeleton": a bootable Pekko HTTP service exposing health endpoints, packaged as a Docker image — deliberately **without persistence, gRPC, or any broker domain logic**. TDD is mandatory, and the codebase favors functional style. The service must be probe-friendly (liveness/readiness) so a later orchestrator/Docker Hub publish (Feature 5) has something real to run.

## Goals / Non-Goals

**Goals:**
- A `Main` that boots a typed `ActorSystem`, binds Pekko HTTP on a configurable host/port, and shuts down gracefully.
- `GET /health` (liveness) and `GET /health/ready` (readiness) with JSON bodies, route-tested first (Red→Green→Refactor).
- Typed, env-overridable configuration (`HERMESMQ_HTTP_HOST/PORT`) that fails fast on invalid input.
- A locally buildable, runnable Docker image (non-root, versioned tag) serving `/health`.
- README updates for run/build/config.

**Non-Goals:**
- Persistence, event sourcing, projections, gRPC, or any broker commands/events (Features 3+).
- Publishing the image to Docker Hub or any registry (Feature 5).
- Metrics/tracing endpoints, auth, TLS (later).
- Multi-stage orchestration, compose files beyond what's needed to demonstrate a run.

## Decisions

**HTTP layer: Pekko HTTP.**
It's the Pekko-native HTTP toolkit and the intended API substrate; its routing DSL is a good fit for small, testable health routes, and `pekko-http-testkit` allows route tests without opening a socket. *Alternative:* a bare `pekko-http` low-level API or a non-Pekko server (http4s/Tapir) — rejected to stay within one runtime/ecosystem and avoid effect-system interop this early.

**JSON: pekko-http spray-json support.**
Small, first-party, no extra ecosystem. The health payload is trivial (three fields), so a heavier codec library (circe) isn't warranted yet. *Alternative:* circe via a third-party integration — more power than needed now; revisit when domain models arrive.

**Health model: separate liveness and readiness.**
`/health` = process is alive (always `200` once routes are up). `/health/ready` = ready to serve, driven by an `AtomicReference`/`@volatile` readiness flag flipped to `true` after a successful bind and back to `false` at shutdown start. This lets an orchestrator drain traffic before exit. *Alternative:* a single `/health` — simpler but conflates "alive" with "ready", which breaks graceful rollout.

**Configuration: Typesafe Config (`application.conf`) with env overrides, parsed into a typed case class.**
`application.conf` uses `${?HERMESMQ_HTTP_PORT}` style optional substitutions; a small pure function reads the `Config` into a `ServiceConfig(host: String, port: Int)` and validates (`port in 1..65535`), returning an error/throwing a clear exception before any binding is attempted. Functional: parsing is a total function from `Config` to `Either[ConfigError, ServiceConfig]` (or validated), no side effects. *Alternative:* PureConfig — nice, but one more dependency for two fields; hand-rolled typed read is clean enough here.

**Boot & shutdown: `Main` binds, registers a JVM shutdown hook / Pekko `CoordinatedShutdown`.**
On boot: load config → start `ActorSystem` → `Http().newServerAt(host, port).bind(routes)` → on success flip readiness true. On SIGTERM: flip readiness false → `serverBinding.unbind()` → `system.terminate()`. Pekko's `CoordinatedShutdown` already hooks SIGTERM; we add an unbind phase. Bind failure (port in use / invalid) completes the bind future with a failure → log and `exit(1)`. *Alternative:* manual signal handling — redundant given CoordinatedShutdown.

**Docker: `sbt-native-packager` `DockerPlugin` + `JavaAppPackaging`.**
Generates the image (and its Dockerfile) from the build, layered for cache efficiency, integrates cleanly with the later Docker Hub publish (Feature 5) via the same settings. Configure: slim Temurin JRE base (`eclipse-temurin:21-jre`), `dockerExposedPorts := Seq(8080)`, non-root user (native-packager's `Docker / daemonUser`), tag = project version. *Alternative:* hand-written Dockerfile + sbt-assembly fat jar — more control but more to maintain and diverges from the packager path we want for Feature 5.

**Base image / runtime JDK: Temurin 21 JRE.**
Matches the CI/build pin (Temurin 21) so runtime and build agree; JRE (not JDK) keeps the image slim. Note the local dev machine runs a newer JDK, which is fine — the image pins 21.

## Risks / Trade-offs

- **Port-in-use or invalid config hangs/masks failure** → bind returns a failed future and typed config validation runs before bind; both paths log clearly and exit non-zero (covered by edge-case scenarios).
- **Readiness flag races with shutdown** → use a thread-safe flag (`AtomicBoolean`) flipped false as the first shutdown step, before unbind, so probes see "not ready" promptly.
- **`sbt Docker/publishLocal` requires a Docker daemon** → the image build/run scenarios are gated on Docker availability; route/boot behavior is fully covered by socket-free `pekko-http-testkit` tests so the core logic is verifiable without Docker (important for CI runners without a daemon).
- **SIGTERM not forwarded to the JVM in the container** → native-packager's bin script `exec`s the JVM as PID 1's child appropriately; verify `docker stop` exits within the grace period (edge-case scenario).
- **New dependency surface (pekko-http)** → pin versions compatible with the pinned Pekko core; CI's fail-fast resolution catches mismatches.
- **spray-json boilerplate** → acceptable for one tiny payload; if JSON needs grow, revisit codec choice in a later change.

## Migration Plan

Additive feature on a green baseline; TDD order:
1. Add `pekko-http` + testkit deps; RED route test for `/health` → implement route → GREEN.
2. RED readiness test → implement readiness flag + `/health/ready` → GREEN.
3. Add typed config + env override; RED config tests (override, invalid) → implement → GREEN.
4. Implement `Main` boot/shutdown; RED boot test (bind on ephemeral port, hit `/health`, clean unbind) → GREEN.
5. Add native-packager Docker settings; build image locally; verify container serves `/health`, non-root, clean `docker stop`.
6. README updates.

Rollback: all changes are additive files + build settings; reverting the change restores the Feature-1 skeleton. No data or external state involved.

## Open Questions

- Default port: `8080` assumed. Confirm no preference for another (e.g. `8558`, Pekko management's default).
- Readiness semantics for later features: readiness currently keys only on HTTP bind; when persistence lands (Feature 4) it should also gate on journal availability — out of scope now but the flag is designed to extend.
- JSON library: staying with spray-json for now; flag if circe is preferred project-wide before domain models arrive.
