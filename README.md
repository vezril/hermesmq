# HermesMQ

[![CI](https://github.com/vezril/hermesmq/actions/workflows/ci.yml/badge.svg)](https://github.com/vezril/hermesmq/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/vezril/hermesmq?sort=semver)](https://github.com/vezril/hermesmq/releases)
[![Docker Hub](https://img.shields.io/docker/v/calvinference/hermesmq?label=docker&sort=semver)](https://hub.docker.com/r/calvinference/hermesmq)
[![License: MIT](https://img.shields.io/github/license/vezril/hermesmq)](LICENSE)

A single-node, **event-sourced Pub/Sub message broker** built in Scala on
[Apache Pekko](https://pekko.apache.org/).

HermesMQ models topics and subscriptions as event-sourced aggregates: every state
change is a journaled domain event, and that event log is the source of truth. A
publish is acknowledged to the producer **only once its event is durably
written**, and a restart replays the journal so accepted messages survive crashes
and unacknowledged messages resume exactly where they left off. It targets
single-node operation and is honest about its guarantees within that constraint —
clustering and horizontal scaling are non-goals.

> **Status:** early development — the pieces below are being built feature by
> feature via a spec-driven, test-first workflow (see the
> [AI Usage Disclaimer](#ai-usage-disclaimer)).

## Capabilities

| Capability | Status |
|------------|--------|
| Build tooling, CI/CD, semantic-versioned releases | ✅ Done |
| Runnable Pekko HTTP service with liveness/readiness health endpoints | ✅ Done |
| Core domain: Topic/Subscription aggregates (commands, events, `decide`/`evolve`) | ✅ Done |
| Durable persistence on a configurable database (PostgreSQL) | ✅ Done |
| Docker image published to Docker Hub | ✅ Done |
| Topic management (create/delete/update) over a REST admin API | ✅ Done |
| Publish & consume messages (at-least-once, projection-driven delivery, pull-based) | ✅ Done |
| Native Scala client library (typed REST wrapper) | ✅ Done |
| Multi-node cluster: sharded entities + single cluster-wide projection (write side) | ✅ Done |
| Cluster-complete delivery (distributed subscriptions index) | 🚧 Planned (10b) |
| Redelivery timers and ack-deadline expiry | 🚧 Planned |
| Query side: projections / read models (backlog, throughput, admin) | 🚧 Planned |
| gRPC service API | 🚧 Planned |

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

## Topic Admin API

Manage topics over REST (JSON). Topics carry a labels/metadata map that can be
updated; delete is a soft delete (a deleted id cannot be re-created).

| Method & path            | Body                                  | Success | Errors |
|--------------------------|---------------------------------------|---------|--------|
| `POST /v1/topics`        | `{"topicId":"orders","labels":{…}}`   | `201`   | `409` exists · `400` bad body |
| `GET /v1/topics/{id}`    | —                                     | `200` `{topicId, labels}` | `404` |
| `PATCH /v1/topics/{id}`  | `{"labels":{…}}`                      | `200`   | `404` |
| `DELETE /v1/topics/{id}` | —                                     | `204`   | `404` |

```bash
curl -X POST localhost:8080/v1/topics \
  -H 'Content-Type: application/json' \
  -d '{"topicId":"orders","labels":{"team":"payments"}}'      # 201

curl localhost:8080/v1/topics/orders                          # 200 {"topicId":"orders","labels":{…}}

curl -X PATCH localhost:8080/v1/topics/orders \
  -H 'Content-Type: application/json' -d '{"labels":{"team":"core"}}'   # 200

curl -X DELETE localhost:8080/v1/topics/orders                # 204
```

> A gRPC API (per the architecture) lands in a later feature; topic management
> and publish/consume are available over REST today.

## Publish & Consume

Publish messages to a topic and consume them from subscriptions (pull-based).
Published messages are delivered **at least once** to every subscription on the
topic by a Pekko Projection that tails the topic journal — delivery is durable
and survives restarts.

| Method & path                          | Body                                   | Result |
|-----------------------------------------|----------------------------------------|--------|
| `POST /v1/topics/{id}/messages`         | `{"payload":"…","attributes":{…}}`     | `202` `{messageId}` · `404` · `400` |
| `POST /v1/subscriptions`                | `{"subscriptionId":"s1","topicId":"orders"}` | `201` · `409` |
| `POST /v1/subscriptions/{id}/pull`      | `{"max":10}`                           | `200` `{messages:[{ackId,payload,attributes,publishTime}]}` · `404` |
| `POST /v1/subscriptions/{id}/ack`       | `{"ackIds":["…"]}`                     | `200` `{acknowledged,unknown}` |

```bash
curl -X POST localhost:8080/v1/subscriptions \
  -H 'Content-Type: application/json' -d '{"subscriptionId":"s1","topicId":"orders"}'   # 201

curl -X POST localhost:8080/v1/topics/orders/messages \
  -H 'Content-Type: application/json' -d '{"payload":"hello","attributes":{"k":"v"}}'   # 202 {messageId}

curl -X POST localhost:8080/v1/subscriptions/s1/pull \
  -H 'Content-Type: application/json' -d '{"max":10}'    # 200 {messages:[{ackId,payload,…}]}

curl -X POST localhost:8080/v1/subscriptions/s1/ack \
  -H 'Content-Type: application/json' -d '{"ackIds":["s1:<messageId>"]}'   # 200
```

**Delivery guarantee (and current limits):** at-least-once. The `ackId` is
deterministic per `(subscription, message)`, so a projection replay re-delivers
idempotently and does not duplicate an outstanding message. Not yet implemented
(planned): ack-deadline expiry / redelivery timers, exactly-once, back-delivery
of messages published before a subscription existed, and rebuilding the in-memory
topic→subscriptions index from the journal on restart. REST payloads are treated
as UTF-8 text.

## Scala client library

A native, typed Scala client wraps the REST API so applications don't hand-roll
HTTP. It ships as its own lightweight artifact (`hermesmq-client`) that depends
only on the shared `hermesmq-domain` types and pekko-http — **not** on the
server's persistence/projection stack.

```scala
libraryDependencies += "me.cference.hermesmq" %% "hermesmq-client" % "0.3.0"
```

```scala
import me.cference.hermesmq.client.HermesClient
import me.cference.hermesmq.domain.{TopicId, SubscriptionId, AckId}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

given system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "app")
val client = HermesClient("http://localhost:8080")

for
  _   <- client.createTopic(TopicId.from("orders").toOption.get)
  _   <- client.createSubscription(SubscriptionId.from("s1").toOption.get, TopicId.from("orders").toOption.get)
  id  <- client.publish(TopicId.from("orders").toOption.get, "hello", Map("k" -> "v"))
  msgs <- client.pull(SubscriptionId.from("s1").toOption.get, max = 10)   // after delivery
  _   <- client.ack(SubscriptionId.from("s1").toOption.get, msgs.map(_.ackId))
yield ()
```

Methods return `Future`s; error statuses fail the future with a
`HermesClientException`, while a not-found on a read (`getTopic`) is an empty
result. The caller owns the `ActorSystem`.

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
[`server/src/main/resources/schema/postgres.sql`](server/src/main/resources/schema/postgres.sql);
apply it to any fresh database before first use. If the database is unreachable
or the schema is missing, persistence fails loudly (the operation is never
silently accepted-and-lost) and `GET /health/ready` reports `503`.

Unit tests use an in-memory journal and need **no database**. The one PostgreSQL
integration test is excluded from the default run; run it (with Docker) via:

```bash
sbt -Dit=true "testOnly *PostgresPersistenceIntegrationSpec"
```

## Clustering

The service runs as a **Pekko cluster**. Topic and subscription entities are
distributed with **Cluster Sharding** — exactly one writer per id across the
cluster — over a shared PostgreSQL journal, and the delivery projection runs as a
single cluster-wide instance (a `ShardedDaemonProcess`). A single node forms a
one-node cluster and behaves exactly as a standalone service.

| Variable                 | Default                              | Description                       |
|--------------------------|--------------------------------------|-----------------------------------|
| `HERMESMQ_CLUSTER_HOST`  | `127.0.0.1`                          | Artery canonical hostname         |
| `HERMESMQ_CLUSTER_PORT`  | `25520`                              | Artery remoting port              |
| `HERMESMQ_CLUSTER_SEEDS` | `pekko://hermesmq@127.0.0.1:25520`   | Comma-separated seed-node list    |

Run a two-node cluster with the provided example (both nodes share one Postgres
and the same seed list):

```bash
docker compose -f docker-compose.cluster.yml up -d
curl localhost:8081/health/ready   # node 1
curl localhost:8082/health/ready   # node 2
```

A split-brain resolver (downing provider) keeps membership safe under partitions.

> **Scope note (Feature 10a):** clustering covers the **write/command side** —
> topic/subscription CRUD, publish, and recovery all work cluster-wide with
> correct single-writer semantics, and the projection runs once cluster-wide.
> **Multi-node delivery *completeness* is not yet done**: the topic→subscriptions
> index is still per-node in memory, so with more than one node a message only
> fans out to subscriptions known to the projection's node. A distributed index
> (Feature 10b) closes this. Single-node delivery is fully correct.

## Docker

The service is packaged into a container image with `sbt-native-packager`
(slim `eclipse-temurin:21-jre` base, non-root user, port `8080` exposed, image
tag tracking the project version):

```bash
sbt Docker/publishLocal                                    # build image locally
docker run -p 8080:8080 calvinference/hermesmq:latest      # run it
curl localhost:8080/health                                 # -> 200

# override the port at run time
docker run -e HERMESMQ_HTTP_PORT=9091 -p 9091:9091 calvinference/hermesmq:latest
```

`docker stop` sends `SIGTERM`, so the container shuts down gracefully within the
stop grace period.

### Published images (Docker Hub)

Released images are published to
[`docker.io/calvinference/hermesmq`](https://hub.docker.com/r/calvinference/hermesmq):

```bash
docker pull calvinference/hermesmq:latest   # newest release
docker pull calvinference/hermesmq:1.4.0    # a specific release
```

Tagging scheme (single-platform `linux/amd64` for now):

| Trigger              | Tags pushed                          | Moves `latest`? |
|----------------------|--------------------------------------|-----------------|
| Release (`vX.Y.Z`)   | `X.Y.Z` **and** `latest`             | Yes             |
| Push to `development` | snapshot tag (dynver, `+`→`-`)       | No              |
| Pull request         | *(nothing — images never pushed)*    | No              |

**Required secrets** — set these once in the repository settings so the pipeline
can push:

| Secret            | Purpose                                   |
|-------------------|-------------------------------------------|
| `DOCKER_USERNAME` | Docker Hub username (namespace `calvinference`) |
| `DOCKER_TOKEN`    | Docker Hub access token                   |

A `calvinference/hermesmq` Docker Hub repository must exist. If the secrets are
missing or invalid, the publish step fails loudly (it is never silently skipped).

## Deployment example

Run the service together with its PostgreSQL database using Docker Compose. Save
this as `deploy-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: hermesmq
      POSTGRES_USER: hermes
      POSTGRES_PASSWORD: change-me
    volumes:
      # applies the journal/snapshot schema on first start
      - ./server/src/main/resources/schema/postgres.sql:/docker-entrypoint-initdb.d/10-hermesmq.sql:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U hermes -d hermesmq"]
      interval: 5s
      timeout: 5s
      retries: 5

  hermesmq:
    image: calvinference/hermesmq:latest
    depends_on:
      postgres:
        condition: service_healthy
    environment:
      HERMESMQ_HTTP_PORT: 8080
      HERMESMQ_DB_HOST: postgres
      HERMESMQ_DB_PORT: 5432
      HERMESMQ_DB_NAME: hermesmq
      HERMESMQ_DB_USER: hermes
      HERMESMQ_DB_PASSWORD: change-me
    ports:
      - "8080:8080"
```

```bash
docker compose -f deploy-compose.yml up -d
curl localhost:8080/health         # -> 200 {"status":"UP",...}
curl -i localhost:8080/health/ready # -> 200 once bound and the DB is reachable
```

### Configuration example

All settings are environment variables (see the tables above). A typical
deployment configuration:

```bash
# HTTP
export HERMESMQ_HTTP_HOST=0.0.0.0
export HERMESMQ_HTTP_PORT=8080

# PostgreSQL persistence
export HERMESMQ_DB_HOST=postgres.internal
export HERMESMQ_DB_PORT=5432
export HERMESMQ_DB_NAME=hermesmq
export HERMESMQ_DB_USER=hermes
export HERMESMQ_DB_PASSWORD=…        # keep out of version control
```

## Project layout

```
build.sbt                    # multi-module build (domain / server / client)
project/                     # pinned sbt version + plugins (dynver, native-packager)
domain/                      # pure value types & aggregates (shared, no IO deps)
  src/…/me/cference/hermesmq/domain/
server/                      # the service (config, http, persistence, delivery, cluster, Main) + Docker
  src/…/me/cference/hermesmq/{config,http,persistence,delivery,cluster}
  src/main/resources/        # application.conf, logback.xml, schema/postgres.sql
client/                      # the Scala client library (typed REST wrapper)
  src/…/me/cference/hermesmq/client/HermesClient.scala
.github/workflows/           # CI and release automation
```

The modules: `server` and `client` both depend on `domain`; `client` does not
depend on `server`. Release publishes the `domain` and `client` library jars to
GitHub Packages and the `server` image to Docker Hub.

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

## AI Usage Disclaimer

HermesMQ is built with AI assistance. [Claude](https://www.anthropic.com/claude)
(Anthropic's Claude Code) acts as an **AI-assisted SDLC team**, contributing
across the software-development lifecycle:

- **Product / specs** — turning feature requests into acceptance criteria and specifications
- **Architecture / design** — proposing designs, trade-offs, and decision records
- **Implementation** — writing the Scala/Pekko code
- **Testing** — test-first (Red → Green → Refactor); every behavior is covered by a failing test before it is implemented
- **Review** — reviewing diffs and edge cases before changes are merged

The work follows a **spec-driven workflow** ([OpenSpec](https://github.com/Fission-AI/OpenSpec)):
each feature is proposed, designed, specified, broken into tasks, and implemented
under test — one reviewed pull request per feature.

**A human directs and is accountable.** All scope, decisions, and merges are made
under human direction and review; the AI is an assistant, not an autonomous owner.
Nothing here is presented as unreviewed or fully autonomous output. Use the
software under the terms of its [license](#license) and review it for your own
use case as you would any dependency.

## License

Released under the [MIT License](LICENSE) — © 2026 Calvin Ference. Permissive
reuse with attribution; see [`LICENSE`](LICENSE) for the full text.
