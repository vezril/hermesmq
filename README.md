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
| Multi-node cluster: sharded entities + single cluster-wide projection | ✅ Done |
| Cluster-complete delivery (durable, shared subscriptions read model) | ✅ Done |
| Redelivery timers, ack-deadline expiry, and dead-lettering | ✅ Done |
| gRPC service API (topic admin + pub/sub over HTTP/2) | ✅ Done |
| Snapshots & journal retention (bounded recovery, event purging) | ✅ Done |
| Observability: read models, admin listings & Prometheus metrics | ✅ Done |
| Authentication & multi-tenant isolation (REST + gRPC) | ✅ Done |
| Streaming consume (server-streaming gRPC) | ✅ Done |
| Bidirectional consume (stream messages + ack over one gRPC call) | ✅ Done |
| Message TTL / expiry (drop-on-expiry, sweeper-purged) | ✅ Done |
| Idempotent publish (producer key dedup within a window) | ✅ Done |

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
export GITHUB_TOKEN=<a PAT with read:packages>   # to resolve the Lexicon gRPC stubs
sbt compile
```

The gRPC service stubs come from the **Lexicon** artifact `io.codex %% lexicon-hermes-grpc`
(pinned in `build.sbt`), resolved from `the-lexicon`'s GitHub Packages — so a `GITHUB_TOKEN`
with `read:packages` is required locally and in CI (set `LEXICON_PACKAGES_TOKEN` as the CI
secret if the built-in token lacks cross-repo package read). No gRPC codegen runs in this repo.

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
| `HERMESMQ_HTTP_HOST`   | `0.0.0.0`   | HTTP (REST) bind host        |
| `HERMESMQ_HTTP_PORT`   | `8080`      | HTTP (REST) bind port (1–65535) |
| `HERMESMQ_GRPC_HOST`   | `0.0.0.0`   | gRPC (HTTP/2) bind host      |
| `HERMESMQ_GRPC_PORT`   | `8081`      | gRPC bind port (1–65535)     |
| `HERMESMQ_AUTH_ENABLED`| `false`     | Require API-key auth on `/v1` (REST + gRPC) |
| `HERMESMQ_AUTH_DEFAULT_TENANT` | `default` | Tenant used when auth is disabled (unqualified ids) |
| `HERMESMQ_DB_HOST`     | `localhost` | PostgreSQL host              |
| `HERMESMQ_DB_PORT`     | `5432`      | PostgreSQL port              |
| `HERMESMQ_DB_NAME`     | `hermesmq`  | Database name                |
| `HERMESMQ_DB_USER`     | `hermes`    | Database user                |
| `HERMESMQ_DB_PASSWORD` | `hermes`    | Database password            |

### Redelivery & ack-deadline

| Variable                        | Default | Description                                                        |
|---------------------------------|---------|--------------------------------------------------------------------|
| `HERMESMQ_ACK_DEADLINE`         | `30s`   | How long a pulled message stays leased before it can be redelivered |
| `HERMESMQ_MAX_DELIVERY_ATTEMPTS`| `5`     | Attempts before a message is dead-lettered (`0` = unlimited)        |
| `HERMESMQ_SWEEP_INTERVAL`       | `5s`    | How often the sweeper scans for overdue leases                      |
| `HERMESMQ_DEAD_LETTER_TOPIC`    | *(none)*| Topic exhausted messages are republished to; empty = drop           |

An invalid or out-of-range port — or a non-positive ack-deadline / sweep-interval,
or a negative max-delivery-attempts — fails fast at startup with a clear error and
a non-zero exit code.

### Message TTL

Messages can carry a **time-to-live** so undelivered/unacknowledged messages expire
and are purged instead of accumulating forever. A publish may set `ttlSeconds` (REST
JSON field or gRPC `ttl_seconds`); otherwise the global default `HERMESMQ_TTL_DEFAULT`
applies. A message's `expireTime` is `publishTime + TTL`; once past it, the message is
never delivered (skipped on pull) and the periodic sweeper **drops** it from the
subscription — distinct from ack-deadline redelivery and max-attempts dead-lettering
(TTL is wall-clock expiry regardless of attempts). Expired messages are dropped, not
dead-lettered.

| Variable              | Default | Description                                        |
|-----------------------|---------|----------------------------------------------------|
| `HERMESMQ_TTL_DEFAULT`| `0s`    | Default message TTL; `0` = off. Per-publish `ttlSeconds` overrides it. |

```bash
curl -X POST localhost:8080/v1/topics/orders/messages \
  -H 'Content-Type: application/json' -d '{"payload":"stale-in-60s","ttlSeconds":60}'   # 202
```

> **Future:** per-topic/per-subscription TTL and dead-letter-on-expiry (an audit trail
> for expired messages) are natural next options; today TTL is a global default plus a
> per-publish override, and expiry drops silently.

### Idempotent publish

A producer that retries a timed-out publish would otherwise create a **second**
message (each publish mints a fresh id). Supplying an **idempotency key**
(`idempotencyKey` in the REST body, `idempotency_key` on gRPC `PublishRequest`) lets
the broker recognise the retry: within the configured **dedup window**, a repeated key
for the same topic is collapsed to the original publish — no second message is stored
or delivered, and the response returns the **original** `messageId` with
`deduplicated: true`.

The check runs inside the single-writer Topic aggregate, so it is **strongly
consistent within one topic**: it survives entity recovery (the seen-key set is
rebuilt from the journal and kept in snapshots) rather than being best-effort. It is
**not** cross-topic, and a key seen longer ago than the window is treated as new. An
empty key, or a window of `0`, disables dedup — so upgrading changes nothing until you
opt in.

| Variable              | Default | Description                                        |
|-----------------------|---------|----------------------------------------------------|
| `HERMESMQ_DEDUP_WINDOW`| `0s`   | Dedup window for repeated idempotency keys; `0` = off. Size it to the producer's retry horizon (seconds–minutes). |

```bash
# Two publishes with the same key inside the window → one message, same id returned.
curl -X POST localhost:8080/v1/topics/orders/messages \
  -H 'Content-Type: application/json' -d '{"payload":"charge-42","idempotencyKey":"order-42"}'   # 202 deduplicated:false
curl -X POST localhost:8080/v1/topics/orders/messages \
  -H 'Content-Type: application/json' -d '{"payload":"charge-42","idempotencyKey":"order-42"}'   # 202 deduplicated:true
```

> **Note:** the seen-key set is bounded by `window × publish-rate` (pruned on write to
> ~one window of traffic) and lives in the Topic snapshot; size the window to your
> retry horizon rather than hours. The window is a best-effort retention bound at the
> millisecond edge (server-clock based); the within-window retry case is exact.

### Snapshots & journal retention

The event-sourced Topic and Subscription aggregates snapshot periodically so
recovery replays only the events after the latest snapshot, and delete journaled
events older than the retained snapshots so the journal stays bounded (acknowledged
messages are eventually purged). Snapshots serialize explicitly (Java serialization
stays off) and are transparent — a snapshot-bounded recovery yields exactly the
state a full replay would.

| Variable                  | Default | Description                                                    |
|---------------------------|---------|----------------------------------------------------------------|
| `HERMESMQ_SNAPSHOT_EVERY` | `100`   | Persist a state snapshot every this many events                 |
| `HERMESMQ_SNAPSHOT_KEEP`  | `2`     | Recent snapshots to retain; older events are deleted on snapshot |

A non-positive value for either fails fast at startup.

> **Trade-off:** because events older than the retained snapshots are deleted, the
> journal is not an infinite audit log and read models cannot be rebuilt from a
> from-zero replay once events are purged. The projections are maintained forward
> and stay near the journal head, and each snapshot preserves full write-side entity
> state (which is what delivery correctness depends on). The conservative defaults
> keep deletion well behind the live projection head.

## Authentication & multi-tenancy

Auth is **off by default** — every request is served as the `default` tenant with
unqualified ids (existing single-tenant journals keep working). Enable it with
`HERMESMQ_AUTH_ENABLED=true` and configure API keys; then every `/v1` REST and gRPC
request needs a key resolving to a tenant. `/health*` and `/metrics` stay open for
probes and scraping.

**Isolation** is by tenant-namespacing: the external `topicId`/`subscriptionId` a
tenant uses is qualified internally (`<tenant>~<id>`), so two tenants can both use
`orders` and never see each other's data. External ids may not contain `~`.

**Scopes:** topic administration (create/update/delete) requires the `admin` scope;
publish/consume/list require any valid key for the caller's tenant.

Keys are stored as salted SHA-256 hashes — never the raw token. Generate one:

```bash
SALT=$(head -c16 /dev/urandom | base64)
HASH=$(printf '%s' "$(printf '%s' "$SALT" | base64 -d; printf '%s' "my-secret-token")" | openssl dgst -binary -sha256 | base64)
# Put { tenant, salt=$SALT, hash=$HASH, scopes=[...] } in hermesmq.auth.keys
```

```hocon
hermesmq.auth {
  enabled = true
  keys = [ { tenant = "acme", salt = "…", hash = "…", scopes = ["admin"] } ]
}
```

```bash
curl -H 'Authorization: Bearer my-secret-token' localhost:8080/v1/topics   # REST
# gRPC: send metadata `authorization: Bearer my-secret-token` (or `x-api-key`)
curl localhost:8080/health                                                 # open, no key
```

Missing/invalid credentials return REST `401` / gRPC `UNAUTHENTICATED`; a missing
scope returns `403` / `PERMISSION_DENIED`.

> **Future:** JWT/OIDC and mTLS are out of scope for now (static salted-hash keys
> keep the broker dependency-light); `/metrics` is unauthenticated by design for
> internal scraping.

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

> Topic management and publish/consume are available over **both** REST (below)
> and gRPC (see [gRPC API](#grpc-api)).

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
| `POST /v1/subscriptions/{id}/modifyAckDeadline` | `{"ackIds":["…"],"ackDeadlineSeconds":60}` | `200` `{modified,unknown}` · `404` |

```bash
curl -X POST localhost:8080/v1/subscriptions \
  -H 'Content-Type: application/json' -d '{"subscriptionId":"s1","topicId":"orders"}'   # 201

curl -X POST localhost:8080/v1/topics/orders/messages \
  -H 'Content-Type: application/json' -d '{"payload":"hello","attributes":{"k":"v"}}'   # 202 {messageId}

curl -X POST localhost:8080/v1/subscriptions/s1/pull \
  -H 'Content-Type: application/json' -d '{"max":10}'    # 200 {messages:[{ackId,payload,…}]}

curl -X POST localhost:8080/v1/subscriptions/s1/ack \
  -H 'Content-Type: application/json' -d '{"ackIds":["s1:<messageId>"]}'   # 200

curl -X POST localhost:8080/v1/subscriptions/s1/modifyAckDeadline \
  -H 'Content-Type: application/json' \
  -d '{"ackIds":["s1:<messageId>"],"ackDeadlineSeconds":60}'   # 200 extend the lease
```

**Leased delivery, redelivery & dead-lettering.** A pull *leases* the messages it
returns: each becomes invisible to other pulls until its ack deadline
(`HERMESMQ_ACK_DEADLINE`, default `30s`) passes. Acknowledge before then to remove
it. If the deadline lapses unacknowledged, a periodic sweeper
(`HERMESMQ_SWEEP_INTERVAL`) expires the lease and the message becomes available for
redelivery, counting one delivery attempt. Once attempts reach
`HERMESMQ_MAX_DELIVERY_ATTEMPTS` (`0` = unlimited) the message is **dead-lettered**:
republished to `HERMESMQ_DEAD_LETTER_TOPIC` — carrying `x-dead-letter-subscription`,
`x-delivery-attempts`, and `x-original-message-id` headers — or dropped (with a
warning) if no topic is configured. Consumers can extend a lease with
`modifyAckDeadline`, or nack for immediate redelivery by sending
`ackDeadlineSeconds: 0`.

**Delivery guarantee (and current limits):** at-least-once. The `ackId` is
deterministic per `(subscription, message)`, so a projection replay re-delivers
idempotently and does not duplicate an outstanding message. Not yet implemented
(planned): exactly-once, back-delivery of messages published before a subscription
existed, and rebuilding the in-memory topic→subscriptions index from the journal on
restart. REST payloads are treated as UTF-8 text.

## gRPC API

The same topic-admin and pub/sub operations are exposed over **Pekko gRPC** — the
architecture's primary API — served over cleartext HTTP/2 (h2c) on its own port
(`HERMESMQ_GRPC_PORT`, default `8081`), alongside REST. TLS is expected to be
terminated by a proxy, mirroring the REST surface.

The gRPC contract is defined in the **[Lexicon](https://github.com/vezril/the-lexicon)** —
the constellation's shared source of truth for wire contracts — and this repo generates its
server stubs from the pinned `io.codex %% lexicon-hermes-grpc` artifact rather than a local
`.proto`. Resolving it from the Lexicon's GitHub Packages needs a `GITHUB_TOKEN` with
`read:packages` (see [Build](#build)). The service surface is unchanged by the move:

| Service | RPCs |
|---------|------|
| `TopicAdminService` | `CreateTopic`, `GetTopic`, `UpdateTopic`, `DeleteTopic` |
| `PubSubService`     | `Publish`, `CreateSubscription`, `Pull`, `StreamMessages`, `Consume`, `Ack`, `ModifyAckDeadline` |

`Pull` leases messages exactly as REST pull does; `Ack`/`ModifyAckDeadline` accept a
batch and report which ids were applied vs unknown. Domain rejections map to gRPC
statuses: not-found → `NOT_FOUND`, already-exists → `ALREADY_EXISTS`, bad input →
`INVALID_ARGUMENT`, backend failure → `UNAVAILABLE`.

```scala
import me.cference.hermesmq.grpc.*
import org.apache.pekko.grpc.GrpcClientSettings

val settings = GrpcClientSettings.connectToServiceAt("localhost", 8081).withTls(false)
val topics   = TopicAdminServiceClient(settings)
val pubsub   = PubSubServiceClient(settings)

for
  _   <- topics.createTopic(CreateTopicRequest(topicId = "orders"))
  ack <- pubsub.publish(PublishRequest(topicId = "orders", payload = ByteString.copyFromUtf8("hi")))
yield ack.messageId
```

Any gRPC client works — generate stubs from the Lexicon's
[`hermes.proto`](https://github.com/vezril/the-lexicon/blob/main/hermes-grpc/src/main/protobuf/hermesmq/v1/hermes.proto)
in your language of choice, or consume the published `lexicon-hermes-grpc` (JVM) stubs.

### Streaming consume

`StreamMessages(StreamRequest) returns (stream PulledMessage)` pushes leased messages
to a consumer over a single long-lived call instead of polling `Pull`. The stream is
**demand-driven**: the server leases further messages only as the consumer takes them,
so a slow consumer stops new leases (messages stay AVAILABLE) rather than being
over-leased. An idle stream re-checks for new messages every `HERMESMQ_STREAM_POLL_INTERVAL`
(default `1s`), leasing up to `HERMESMQ_STREAM_BATCH_SIZE` (default `100`) per step.

Acknowledge received messages with the **unary `Ack`** RPC (the stream is push-only);
anything unacked redelivers via the sweeper as usual. Cancelling or disconnecting the
stream stops leasing cleanly — undelivered leases simply lapse and redeliver. An unknown
subscription fails `NOT_FOUND`; auth and tenant-scoping work exactly as for the unary calls.

```scala
pubsub.streamMessages(StreamRequest(subscriptionId = "s1"))
  .runForeach { m => process(m); /* then: pubsub.ack(AckRequest("s1", Seq(m.ackId))) */ }
```

### Bidirectional consume

`Consume(stream ConsumeRequest) returns (stream PulledMessage)` receives messages **and**
acknowledges them over one long-lived call. The client's **first** request must be a
`ConsumeStart{subscription_id, max}`; subsequent requests are `ConsumeAck{ack_ids}`. The
server streams leased messages back (same demand-driven backpressure as `StreamMessages`)
and applies acks as they arrive — acks are processed **independently of outbound demand**,
so a client that reads slowly but keeps acking is never stalled. Acks are fire-and-forget
(no per-ack response; use unary `Ack` if you need confirmation); a non-`ConsumeStart` first
message fails `INVALID_ARGUMENT`, an unknown subscription `NOT_FOUND`, and cancelling the
call stops both directions (unacked messages redeliver after their deadline).

```scala
val inbound = Source.single(ConsumeRequest().withStart(ConsumeStart(subscriptionId = "s1")))
  .concat(acksAsTheyAreProcessed) // ConsumeRequest().withAck(ConsumeAck(Seq(ackId)))
pubsub.consume(inbound).runForeach(process)
```

## Observability

Pekko Projections fold the event journal into durable read models — off the hot
delivery path — that power admin listings and metrics. They are eventually
consistent by design.

| Method & path             | Result |
|---------------------------|--------|
| `GET /v1/subscriptions`   | `200` `[{subscriptionId, topicId, backlog, oldestUnackedAgeSeconds, redeliveredTotal, deadLetteredTotal}]` |
| `GET /v1/topics`          | `200` `[{topicId, publishedTotal, deleted}]` |
| `GET /metrics`            | `200` Prometheus text exposition |

Exposed metrics:

| Metric | Type | Labels | Meaning |
|--------|------|--------|---------|
| `hermesmq_subscription_backlog` | gauge | `subscription` | Outstanding (unacknowledged) messages |
| `hermesmq_subscription_oldest_unacked_age_seconds` | gauge | `subscription` | Age of the oldest unacknowledged message |
| `hermesmq_messages_published_total` | counter | `topic` | Messages published to the topic |
| `hermesmq_messages_redelivered_total` | counter | `subscription` | Redeliveries (ack-deadline expiries) |
| `hermesmq_messages_dead_lettered_total` | counter | `subscription` | Dead-lettered messages |

```bash
curl localhost:8080/v1/subscriptions   # [{"subscriptionId":"s1","backlog":2,...}]
curl localhost:8080/metrics            # hermesmq_subscription_backlog{subscription="s1"} 2 ...
```

The stats projections use exactly-once processing so counters are never
double-counted on replay. Counts are maintained **forward**: because journal
retention purges old events, these read models cannot be rebuilt from a
from-zero replay once events are deleted — the projections run continuously from
first start, so live counts stay accurate.

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

**Delivery is cluster-complete.** The topic→subscriptions mapping is a durable,
cluster-shared read model (a `topic_subscriptions` table maintained by a
projection over `SubscriptionCreated`), so a message published on any node is
fanned out to **every** subscription of its topic regardless of which node
created it. Both the delivery and subscription-index projections run as single
cluster-wide instances. (Still at-least-once, and no back-delivery of messages
published before a subscription existed.)

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
