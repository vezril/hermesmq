## 1. Dependencies & config scaffolding

- [x] 1.1 Add `pekko-http`, `pekko-http-spray-json`, and `pekko-http-testkit` (Test) to `build.sbt`, pinned compatible with the existing Pekko version
- [x] 1.2 Add `src/main/resources/application.conf` with an `hermesmq.http { host, port }` section using `${?HERMESMQ_HTTP_HOST}` / `${?HERMESMQ_HTTP_PORT}` overrides, defaulting to `0.0.0.0:8080`
- [x] 1.3 Add `src/main/resources/logback.xml` (INFO root) for the running service
- [x] 1.4 Verify `sbt compile` still green after dependency additions

## 2. Typed configuration — TDD (Red → Green → Refactor)

- [x] 2.1 RED: write `ServiceConfigSpec` asserting (a) defaults load `0.0.0.0:8080`, (b) `HERMESMQ_HTTP_PORT` override is honored, (c) an invalid/out-of-range port yields a config error — run and confirm red
- [x] 2.2 GREEN: implement `ServiceConfig(host, port)` with a pure `from(config): Either[ConfigError, ServiceConfig]` (validates `port in 1..65535`); make tests pass
- [x] 2.3 REFACTOR: tidy error type/messages; re-run `sbt test` green

## 3. Liveness endpoint — TDD

- [x] 3.1 RED: write `HealthRoutesSpec` (pekko-http-testkit) asserting `GET /health` → `200`, JSON body with `status="UP"`, `service="hermesmq"`, `version` present — run, confirm red
- [x] 3.2 GREEN: implement a `HealthRoutes` module and the `/health` route returning the JSON payload (version from `AppInfo`/dynver); make it pass
- [x] 3.3 Edge case: add tests for `HEAD /health` → `200` no body, and an unmapped path (`GET /healthz`) → `404`; implement/adjust routing until green
- [x] 3.4 REFACTOR: extract the JSON model + marshaller cleanly; re-run green

## 4. Readiness endpoint — TDD

- [x] 4.1 RED: extend `HealthRoutesSpec` — `GET /health/ready` → `200` when the readiness flag is true, `503` when false — confirm red
- [x] 4.2 GREEN: implement a thread-safe readiness flag (`AtomicBoolean`) injected into the routes and the `/health/ready` route; make tests pass
- [x] 4.3 Edge case: test that toggling readiness to false yields `503` on readiness while `/health` still returns `200` (liveness vs readiness during drain); green
- [x] 4.4 REFACTOR: clean up the readiness abstraction; re-run green

## 5. Service bootstrap & graceful shutdown — TDD

- [x] 5.1 RED: write `MainBootSpec` that starts the binding on an ephemeral port (port `0`), issues a real `GET /health` (200), then unbinds and asserts the port is released — confirm red
- [x] 5.2 GREEN: implement `Main` — load `ServiceConfig`, start typed `ActorSystem`, bind Pekko HTTP, flip readiness true on success; wire `CoordinatedShutdown` to flip readiness false → unbind → terminate; make the boot test pass
- [x] 5.3 Edge case: test invalid port (config validation) aborts before bind with a clear error / non-zero path; and a bind-failure (port already bound) is surfaced as a failed bind, not a hang — green
- [x] 5.4 REFACTOR: separate pure wiring (routes, config) from effectful boot; re-run `sbt test` green
- [x] 5.5 Manual run check: `sbt run`, `curl localhost:8080/health` → 200 and `/health/ready` → 200; Ctrl-C shuts down cleanly and frees the port

## 6. Docker packaging

- [x] 6.1 Add `sbt-native-packager` to `project/plugins.sbt`; enable `JavaAppPackaging` + `DockerPlugin` in `build.sbt`
- [x] 6.2 Configure Docker settings: base `eclipse-temurin:21-jre`, `dockerExposedPorts := Seq(8080)`, non-root `daemonUser`, image name `hermesmq`, tag = project version
- [x] 6.3 Build the image: `sbt Docker/publishLocal`; verify an image tagged with the dynver version exists
- [x] 6.4 Run the container publishing the port; `curl` the mapped `/health` → `200` with status body
- [x] 6.5 Edge case: run with `HERMESMQ_HTTP_PORT` overridden and the port exposed; verify `/health` served on the configured port
- [x] 6.6 Edge case: `docker stop` the container; verify SIGTERM triggers graceful shutdown and a clean exit within the stop timeout (not force-killed)
- [x] 6.7 Edge case: inspect the image and confirm the service runs as a non-root user

## 7. Documentation & final verification

- [x] 7.1 Update `README.md`: how to run locally (`sbt run`), the health/readiness endpoints and JSON shape, configurable env vars, and how to build/run the Docker image
- [x] 7.2 Full `sbt test` green; confirm every scenario in `health-endpoint` and `docker-packaging` specs maps to a verified task
- [x] 7.3 Run `openspec validate health-endpoint-service`
