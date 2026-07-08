## Why

Feature 1 delivered a buildable skeleton and CI/CD but nothing that actually runs. Before any broker logic exists, HermesMQ needs a bootable service process and a health endpoint so the container can be started, liveness/readiness can be probed by an orchestrator, and later features have a running HTTP surface to attach to. This is the "walking skeleton": a real process, packaged as a real image, observable via a real endpoint — with **no persistence yet**.

## What Changes

- Add a runnable Pekko HTTP service (`Main`) that boots a typed `ActorSystem`, binds an HTTP server on a configurable host/port, and shuts down gracefully on SIGTERM/SIGINT.
- Expose health endpoints:
  - `GET /health` (liveness) → `200 OK` with a small JSON body (`status`, `service`, `version`).
  - `GET /health/ready` (readiness) → `200` once the HTTP binding is established, `503` before/while unavailable.
- Make the bind host/port and related settings configurable via `application.conf` with environment-variable overrides (e.g. `HERMESMQ_HTTP_HOST`, `HERMESMQ_HTTP_PORT`), following typed, functional config loading.
- Add Docker packaging (via `sbt-native-packager`'s `DockerPlugin`) that produces a runnable image from the service, exposing the HTTP port, with the health endpoint reachable inside the container. Image **build/run is local** in this feature; publishing to Docker Hub is deferred to Feature 5.
- Update `README.md` with how to run the service locally, how to build/run the Docker image, and the health/config surface.

## Capabilities

### New Capabilities
- `health-endpoint`: The bootable Pekko HTTP service and its liveness/readiness health endpoints, including configurable binding and graceful startup/shutdown.
- `docker-packaging`: Building the service into a Docker image that starts the process and serves the health endpoint, with a configurable exposed port.

### Modified Capabilities
<!-- None. Feature 1's project-scaffolding and ci-cd-pipeline specs are unchanged;
     this feature only adds new dependencies and source, which the existing CI already builds. -->

## Impact

- **New dependencies**: `pekko-http` (+ spray-json or pekko-http JSON support) for the HTTP surface; `pekko-http-testkit` for route tests; `sbt-native-packager` (build plugin) for Docker image creation.
- **New files**: `src/main/scala/me/cference/hermesmq/Main.scala`, a health route module, `src/main/resources/application.conf` and `logback.xml`, `project/plugins.sbt` (add native-packager), Docker settings in `build.sbt`, and route/boot tests under `src/test`.
- **Existing systems**: the Feature-1 CI already compiles and tests this; no workflow changes required. A later feature (5) will add the Docker Hub publish step.
- **Runtime**: introduces a long-running server process and a listening socket; graceful shutdown must release the port cleanly.
