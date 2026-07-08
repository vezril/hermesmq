# HermesMQ

A single-node, event-sourced message broker built in Scala on [Apache Pekko](https://pekko.apache.org/).

> **Status:** early development. This repository contains the build tooling and
> CI/CD pipeline, a runnable Pekko HTTP service with health endpoints, the core
> event-sourced domain (Topic/Subscription aggregates), and durable persistence
> on a configurable database (PostgreSQL). The delivery path, projections/read
> models, and the gRPC API land in later features.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | Temurin 21 | The CI/release pipeline pins Temurin 21. Newer JDKs work locally. |
| sbt  | 1.10.7 | Pinned in [`project/build.properties`](project/build.properties); the launcher fetches it automatically. |
| PostgreSQL | 16 | Required to **run** the service (see [Persistence](#persistence)); not needed to build or run the tests. |
| Docker | any | Only for building the container image and the PostgreSQL integration test. |

Scala itself (3.3 LTS) is resolved by sbt — no separate install needed.

## Build

```bash
sbt compile
```

## Run the tests

```bash
sbt test
```

The suite includes a **Pekko smoke test** (`PingPongSpec`), route tests for the
health endpoints via `pekko-http-testkit` (`HealthRoutesSpec`), a real-socket
boot/shutdown test (`HttpServerSpec`), and config parsing tests
(`ServiceConfigSpec`). Actor systems are shut down after each suite, so no
dispatcher threads are left running.

## Run the service

```bash
sbt run
```

The service starts a Pekko HTTP server (default `0.0.0.0:8080`) and exposes:

| Endpoint            | Purpose    | Response |
|---------------------|------------|----------|
| `GET /health`       | Liveness   | `200` with `{"status":"UP","service":"hermesmq","version":"…"}` |
| `HEAD /health`      | Liveness   | `200`, no body (cheap probe) |
| `GET /health/ready` | Readiness  | `200` once bound **and** persistence is reachable; `503` otherwise |

```bash
curl localhost:8080/health
curl -i localhost:8080/health/ready
```

Press `Ctrl-C` (or send `SIGTERM`) to shut down gracefully — readiness flips to
`503`, the HTTP server unbinds, and the port is released via Pekko
`CoordinatedShutdown`.

### Configuration

Settings live in [`application.conf`](src/main/resources/application.conf) and
can be overridden by environment variables:

| Variable               | Default     | Description                  |
|------------------------|-------------|------------------------------|
| `HERMESMQ_HTTP_HOST`   | `0.0.0.0`   | HTTP bind host               |
| `HERMESMQ_HTTP_PORT`   | `8080`      | HTTP bind port (1–65535)     |
| `HERMESMQ_DB_HOST`     | `localhost` | PostgreSQL host              |
| `HERMESMQ_DB_PORT`     | `5432`      | PostgreSQL port              |
| `HERMESMQ_DB_NAME`     | `hermesmq`  | Database name                |
| `HERMESMQ_DB_USER`     | `hermes`    | Database user                |
| `HERMESMQ_DB_PASSWORD` | `hermes`    | Database password            |

An invalid or out-of-range port fails fast at startup with a clear error and a
non-zero exit code.

## Persistence

HermesMQ is event-sourced: Topic and Subscription aggregates are
`EventSourcedBehavior` actors whose events are journaled via
[pekko-persistence-jdbc](https://pekko.apache.org/docs/pekko-persistence-jdbc/current/).
An accepted command is acknowledged **only after** its event is durably written,
and a restart replays the journal to resume exactly where it left off. Events are
journaled as explicit JSON (Java serialization is disabled).

**PostgreSQL is the default backend.** Start one locally with the provided
compose file (it applies the schema automatically):

```bash
docker compose up -d                 # Postgres on :5432 with the schema applied
HERMESMQ_DB_PASSWORD=hermes sbt run   # service connects to it
```

The schema DDL lives at
[`src/main/resources/schema/postgres.sql`](src/main/resources/schema/postgres.sql);
apply it to any fresh database before first use. If the database is unreachable
or the schema is missing, persistence fails loudly (the operation is never
silently accepted-and-lost) and `GET /health/ready` reports `503`.

Unit tests use an in-memory journal and need **no database**. The one PostgreSQL
integration test is excluded from the default run; run it (with Docker) via:

```bash
sbt -Dit=true "testOnly *PostgresPersistenceIntegrationSpec"
```

## Docker

The service is packaged into a container image with `sbt-native-packager`
(slim `eclipse-temurin:21-jre` base, non-root user, port `8080` exposed, image
tag tracking the project version):

```bash
sbt Docker/publishLocal                      # build image locally
docker run -p 8080:8080 hermesmq:latest      # run it
curl localhost:8080/health                   # -> 200

# override the port at run time
docker run -e HERMESMQ_HTTP_PORT=9091 -p 9091:9091 hermesmq:latest
```

`docker stop` sends `SIGTERM`, so the container shuts down gracefully within the
stop grace period. (Publishing the image to a registry is a later feature.)

## Project layout

```
build.sbt                    # build definition (deps, versioning, Docker, publishing)
project/
  build.properties           # pinned sbt version
  plugins.sbt                # sbt-dynver + sbt-native-packager
src/
  main/scala/me/cference/hermesmq/
    Main.scala               # entry point: config -> actor system -> HTTP bind
    AppInfo.scala            # service name/version metadata
    config/ServiceConfig.scala   # typed, validated config parsing
    http/HealthRoutes.scala      # /health and /health/ready routes
    http/HttpServer.scala        # bind + readiness + graceful unbind
  main/resources/            # application.conf, logback.xml
  test/scala/me/cference/hermesmq/   # tests
.github/workflows/           # CI and release automation
```

## Branching model

We use a **GitFlow-lite** strategy:

| Branch        | Purpose                                                        |
|---------------|---------------------------------------------------------------|
| `main`        | Stable release line. Annotated `vX.Y.Z` tags cut releases.    |
| `development` | Integration line. Every push publishes an experimental snapshot. |
| `feature/*`   | Short-lived work branches, merged into `development` via PR.  |

Flow: branch `feature/<topic>` off `development` → open a PR into `development`
(CI must pass) → merge `development` into `main` when ready to ship → push a
`vX.Y.Z` tag on `main` to release.

Direct pushes to `main` and `development` are disallowed by branch protection
(see [Required GitHub settings](#required-github-settings)); all changes go
through a reviewed, CI-checked pull request.

## Versioning

Versions follow [Semantic Versioning 2.0.0](https://semver.org/) and are derived
from Git tags by [`sbt-dynver`](https://github.com/sbt/sbt-dynver):

- On an annotated tag `vX.Y.Z` → clean release version `X.Y.Z`.
- Off-tag (e.g. on `development`) → a unique commit-derived pre-release version,
  so each snapshot stays distinct in the (immutable) package registry.

Check the current version with `sbt version`.

## CI/CD

Two GitHub Actions workflows drive the pipeline:

- **[`ci.yml`](.github/workflows/ci.yml)** — on every pull request and on pushes
  to `development`/`main`: compile, then run the full test suite. This is the
  required status check for merges. Pushes to `development` additionally publish
  a snapshot package to GitHub Packages.
- **[`release.yml`](.github/workflows/release.yml)** — on `vX.Y.Z` tags: run the
  tests as a gate, publish the versioned package to GitHub Packages, then create
  a GitHub Release. Malformed tags (e.g. `v1.2`, `release-1`) do not match the
  trigger pattern and never start a release.

Packages publish to `https://maven.pkg.github.com/vezril/hermesmq`, authenticated
with the workflow's built-in `GITHUB_TOKEN`. (The workflows derive the owner from
`github.repository_owner`, so this stays correct across forks/renames.)

### Cutting a release

```bash
git checkout main
git merge development           # promote integrated changes
git tag -a v0.1.0 -m "v0.1.0"   # annotated SemVer tag
git push origin main --follow-tags
```

## Required GitHub settings

These are configured once so the pipeline is reproducible:

0. **Remote** — the canonical repository is `github.com/vezril/hermesmq`:
   ```bash
   git remote add origin https://github.com/vezril/hermesmq.git
   git push -u origin main development --follow-tags
   ```
1. **Branch protection** on `main` and `development`:
   - Require the `CI / Compile & Test` status check to pass before merging.
   - Require pull requests before merging (no direct pushes).
2. **GitHub Packages** is used as the Maven registry; no extra secret is needed —
   the workflows use the automatically provided `GITHUB_TOKEN`.

## Build troubleshooting

- **Unresolvable dependency:** if a dependency coordinate in `build.sbt` cannot
  be resolved, `sbt compile` fails fast with a non-zero exit code and a
  resolution error naming the missing coordinate — it does not hang or silently
  skip it. Fix the coordinate/version and re-run.
- **Wrong JDK:** building on a JDK older than the pinned minimum fails with a
  clear version error rather than producing bytecode for the wrong target.
