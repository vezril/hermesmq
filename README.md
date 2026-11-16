# HermesMQ

[![CI](https://github.com/vezril/hermesmq/actions/workflows/ci.yml/badge.svg)](https://github.com/vezril/hermesmq/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/vezril/hermesmq?sort=semver)](https://github.com/vezril/hermesmq/releases)
[![Docker Hub](https://img.shields.io/docker/v/calvinference/hermesmq?label=docker&sort=semver)](https://hub.docker.com/r/calvinference/hermesmq)
[![License: MIT](https://img.shields.io/github/license/vezril/hermesmq)](LICENSE)

An **event-sourced Pub/Sub message broker** built in Scala on
[Apache Pekko](https://pekko.apache.org/).

HermesMQ models topics and subscriptions as event-sourced aggregates: every state
change is a journaled domain event, and that event log is the source of truth. A
publish is acknowledged to the producer **only once its event is durably
written**, and a restart replays the journal so accepted messages survive crashes
and unacknowledged messages resume exactly where they left off. It runs as a
single node or as a Pekko cluster over a shared journal, and is honest about its
guarantees either way — see [Delivery guarantees](#delivery-semantics)
and the [Roadmap](#roadmap) for what it deliberately does *not* promise.

> **Status:** feature-complete for its intended scope — every capability below is
> implemented, specified, and covered by tests. It has not yet been run as a
> long-lived production deployment, and the [Roadmap](#roadmap) lists the
> semantics that remain out of scope. Built feature by feature via a spec-driven,
> test-first workflow (see the [AI Usage Disclaimer](#ai-usage-disclaimer)).

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
| Schema self-migration on boot (idempotent, opt-out) | ✅ Done |
| Structured JSON logging (`LOG_FORMAT`, constellation schema) | ✅ Done |
| Named consumers (optional consumer id + active-consumer metric) | ✅ Done |
| Named producers (optional producer id + active-producer metric) | ✅ Done |
| Request tracing (correlation id at the boundary + carried on the bus) | ✅ Done |
| Polyglot clients: Python 3 and JavaScript (ESM) alongside Scala | ✅ Done |
| Dead-letter operations: non-destructive browse + idempotent redrive | ✅ Done |
| Effectively-once consumption: stable `ackId` dedup contract, specified and tested | ✅ Done |
| Back-delivery: opt-in `startFrom: earliest` replays a topic's retained history | ✅ Done |
| Layered TTL (publish / topic / subscription / global, shortest wins) + dead-letter-on-expiry | ✅ Done |

## Roadmap

Everything in the table above is built. These are the known, deliberate gaps —
listed here so the guarantees are legible without reading the whole document:

| Not implemented | Why / what it would take |
|-----------------|--------------------------|
| **Exactly-once delivery** — *not planned, and not possible* | Not a gap to be filled. A broker cannot make a consumer's side effects atomic with its ack, so it cannot know whether redelivery would duplicate work. HermesMQ offers at-least-once delivery with a stable `ackId` to deduplicate on — see [Delivery semantics](#delivery-semantics). |
| **Rebuilding the topic→subscriptions index from the journal on restart** | The cluster-shared index is a durable read model maintained **forward** by a projection; it is not reconstructable from a from-zero replay once retention has purged events. |
| **Cluster-wide aggregation of the consumer/producer gauges** | `hermesmq_subscription_consumers` and `hermesmq_topic_producers` are in-memory and **per-node** — exact on a single replica, partial across a cluster. The metric names and meanings would not change. |
| **JWT/OIDC and mTLS auth** | Auth is static salted-hash API keys, to keep the broker dependency-light. `/metrics` is unauthenticated by design. See [Authentication](#authentication--multi-tenancy). |
| **Versioned schema migrations** | Boot-time migration is idempotent *apply-the-current-schema*; destructive schema evolution would need a real migration tool. See [Schema self-migration](#schema-self-migration). |
| **Per-subscription dead-letter topic** | All subscriptions dead-letter to one global topic (`HERMESMQ_DEAD_LETTER_TOPIC`). Per-subscription routing would buy isolated retention/alerting per subscription; the [browse endpoint](#dead-letter-operations) already gives the per-subscription *view*, so this is marginal. |
| **Multi-platform images** | Released images are `linux/amd64` only. |
| **A coverage floor in CI** | scoverage is reported, not gated. See [Static analysis](#static-analysis). |

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | Temurin 21 | The CI/release pipeline pins Temurin 21. Newer JDKs work locally. |
| sbt  | 1.10.7 | Pinned in [`project/build.properties`](project/build.properties); the launcher fetches it automatically. |
| PostgreSQL | 16 | Required to **run** the service (see [Persistence](#persistence)); not needed to build or run the tests. |
| Docker | any | Only for building the container image and the PostgreSQL integration test. |
| Node | 22+ | Only for the JS client and the `openspec` planning CLI. Pinned in [`.mise.toml`](.mise.toml); `openspec` crashes on Node 18. |

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

Every capability is covered test-first, across ~70 suites: domain aggregate
`decide`/`evolve` laws, REST route tests via `pekko-http-testkit`, gRPC service
tests, event-sourced persistence and serialization, delivery/redelivery/TTL
behaviour, tracing at both the boundary and on the bus, and real-socket
boot/shutdown (`HttpServerSpec`) plus config parsing (`ServiceConfigSpec`). Actor
systems are shut down after each suite, so no dispatcher threads are left running.

Unit tests run against an in-memory journal and need **no database**. The
PostgreSQL integration suites are tagged `PostgresIT` and excluded from the
default run; they provision their own database with Testcontainers, so they need
only Docker:

```bash
sbt -Dit=true test        # the whole suite, including PostgresIT
sbt -Dit=true "testOnly *PostgresPersistenceIntegrationSpec *SchemaMigrationIntegrationSpec *ObservabilityIntegrationSpec"
```

**CI runs them** on every pull request in the `Integration (PostgreSQL)` job, so
the schema DDL and the JDBC repositories are exercised against a real database
rather than only by hand. Note that the suites **self-skip when Docker is
unavailable** — that is why the CI job asserts `docker info` first, so a runner
without Docker fails loudly instead of passing green having tested nothing.

The Python and JavaScript clients have their own suites — see
[`clients/README.md`](clients/README.md).

## Static analysis

CI enforces several gates beyond compile + test:

| Gate | What it does | Failing? |
|------|--------------|----------|
| `-Werror -Wunused:all -Wvalue-discard -Wnonunit-statement` | Strict Scala 3 compiler warnings as errors — no unused symbols, no silently-discarded non-Unit values or statements | Build fails |
| `scalafixAll --check` | Lint rules: `DisableSyntax` (no `throw`/`null`/`while`/`asInstanceOf` — encode errors in return types, use `Option`, iterate with `Iterator`/recursion) and `OrganizeImports` (one import per line, sorted) | CI fails |
| scoverage | Statement-coverage report (`sbt coverage test coverageAggregate`) — **reported, not gated** (no floor yet) | No (advisory) |
| gitleaks | Scans the repo + history for committed secrets | CI fails on a finding |
| Dependabot | Weekly update/CVE PRs for the `sbt` and `github-actions` ecosystems | — |
| Trivy | Scans the released Docker image for CVEs (`ignore-unfixed`, fails on `CRITICAL`) | Release fails |

Run the compiler/lint gates locally with `sbt clean "scalafixAll --check" test`.
A deliberate `asInstanceOf` (e.g. an erased generic round-trip in a test) is
allowed only with a justified `// scalafix:ok DisableSyntax.asInstanceOf`.

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

**Four layers, and one rule: shortest wins.** A TTL may come from the publish
(`ttlSeconds`), the topic, the receiving subscription, or the global default. The
effective expiry is the **earliest** any of them implies — *any layer may shorten
a message's lifetime, none may extend it.*

```bash
# A topic whose data is stale after an hour.
curl -X POST localhost:8080/v1/topics \
  -H 'Content-Type: application/json' -d '{"topicId":"orders","ttlSeconds":3600}'

# A subscription that wants only fresh messages — shortens its own copy to 60s.
curl -X POST localhost:8080/v1/subscriptions \
  -H 'Content-Type: application/json' \
  -d '{"subscriptionId":"s1","topicId":"orders","ttlSeconds":60}'
```

That rule was chosen over the conventional "most specific wins" on purpose:
most-specific would let a subscription keep a message alive past the point its
topic declared the data stale, which inverts ownership. Shortest-wins fails in
the safe direction — the surprise is earlier expiry, never unexpected retention.

Two consequences worth knowing:

- **Changing a topic's TTL is forward-looking.** A message's expiry is fixed when
  it is published; already-published messages keep theirs. Likewise a
  subscription's TTL is resolved at delivery, so changing it does not move the
  expiry of messages it already holds.
- **Two subscriptions on one topic can expire the same message at different
  times**, because each resolves its own copy.

| Variable              | Default | Description                                        |
|-----------------------|---------|----------------------------------------------------|
| `HERMESMQ_DEAD_LETTER_ON_EXPIRY`| `false` | Route expired messages to the dead-letter topic instead of dropping them |

### Dead-letter-on-expiry

TTL is deliberate data loss, and until now the broker performed it without
leaving a trace. Two things change that:

- **`HERMESMQ_DEAD_LETTER_ON_EXPIRY=true`** republishes an expired message to the
  dead-letter topic instead of dropping it, carrying an **`x-expired-at`**
  attribute. It deliberately carries *no* delivery-attempt attributes — the
  message ran out of time, it did not fail delivery, and reporting an attempt
  count would invite the wrong diagnosis. Expired dead-letters are immediately
  visible through [dead-letter browse and redrive](#dead-letter-operations).
- **`hermesmq_messages_expired_total{subscription}`** counts expiries whether
  they are dropped or dead-lettered, so the volume is observable even with
  dead-lettering off.

Default is off: enabling it changes how much lands on your dead-letter topic, so
it is opt-in rather than something to discover. Note also that the dead-letter
topic is itself a topic — if you give *it* a TTL, dead-letters can expire too.
That is not special-cased, because a silent exception there would be more
surprising than the rule.

A negative `ttlSeconds` on a topic or subscription is a `400`, not a silent "off":
a caller sending `-1` is expressing something, and treating it as no-TTL would be
a wrong answer with no signal.

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

How often dedup fires is exposed as the counter `hermesmq_publish_deduplicated_total{topic}`
on `/metrics` (per-node, best-effort — see [Observability](#observability)).

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

**Scopes:** topic administration (create/update/delete) and dead-letter **redrive**
require the `admin` scope; publish/consume/list and dead-letter **browse** require
any valid key for the caller's tenant.

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
| `POST /v1/subscriptions`                | `{"subscriptionId":"s1","topicId":"orders","startFrom":"now"\|"earliest"}` | `201` · `409` · `400` bad `startFrom` |
| `DELETE /v1/subscriptions/{id}`         | —                                      | `204` · `404` |
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
`x-original-topic`, `x-delivery-attempts`, and `x-original-message-id` headers
(see [Dead-letter operations](#dead-letter-operations)) — or dropped (with a
warning) if no topic is configured. Consumers can extend a lease with
`modifyAckDeadline`, or nack for immediate redelivery by sending
`ackDeadlineSeconds: 0`.

### Back-delivery to a new subscription

By default a subscription receives only messages published **after** it was
created — `startFrom: "now"`, which is what every subscription did before this
option existed. Pass `"earliest"` to have the broker deliver the messages already
in the topic's journal:

```bash
curl -X POST localhost:8080/v1/subscriptions \
  -H 'Content-Type: application/json' \
  -d '{"subscriptionId":"s1","topicId":"orders","startFrom":"earliest"}'   # 201
```

Three things to know before relying on it:

- **`earliest` means earliest *still retained*.** Snapshot retention
  (`HERMESMQ_SNAPSHOT_KEEP`) deletes journal events behind the retained
  snapshots, so on a long-lived topic this is not "everything ever published".
  Purged messages are simply absent — no error, no warning.
- **It is asynchronous.** The back-delivery runs off the journal in the
  subscription-index projection, not inside your `POST`. A client that creates an
  `earliest` subscription and immediately pulls may get nothing yet.
- **It happens at most once per subscription**, guarded by a durable marker
  rather than by delivery idempotency. That distinction matters: `RecordDelivery`
  de-duplicates only while a message is still *outstanding*, so once your
  consumer acks a back-delivered message there is nothing left to collide with —
  without the marker, a projection replay would hand you the whole backfill
  again.

Expired messages are skipped (a TTL that lapsed while the message sat in the
journal is still lapsed), and a back-delivered message is otherwise
indistinguishable from a live one: same payload, attributes, correlation id, and
the same `(subscription, message)`-derived `ackId`.

**REST only for now.** The gRPC `CreateSubscriptionRequest` comes from the pinned
Lexicon artifact, so `start_from` needs a change in
[the Lexicon](https://github.com/vezril/the-lexicon) and a pin bump here.
gRPC-created subscriptions behave as `now`, unchanged.

### Delivery semantics

**At-least-once delivery, effectively-once processing.** Those are two different
statements and the distinction is the whole contract, so it is worth being
precise about both.

**What the broker guarantees.** Every published message is delivered to every
subscription on its topic at least once, and each delivery carries an `ackId`
that is a pure function of `(subscription, message)`. The same message
redelivered to the same subscription — after an ack-deadline lapse, after a nack,
or after a projection replay — carries the **identical** `ackId` every time. The
broker never mints a fresh key per attempt.

**What the broker does not guarantee: exactly-once.** It is not a missing
feature; it is not achievable by a broker of this shape. Consider a consumer that
writes to its own database and then crashes before its ack arrives. The broker
cannot observe that the write happened, so it must redeliver — and if it instead
assumed the work was done, it would lose messages. No protocol the broker
controls resolves this, because the consumer's side effects are not in the
broker's transaction. Any system claiming exactly-once is either doing
consumer-side deduplication under a friendlier name, or owns the consumer's
storage (as Kafka does within its own log).

**So: deduplicate on the `ackId`.** That is what makes at-least-once usable:

```scala
// Consumer side. The processed set is yours; its retention horizon is the
// redelivery window you care about, not forever.
val msgs = client.pull(subscriptionId, max = 10).futureValue
msgs.foreach { m =>
  if !alreadyProcessed(m.ackId) then
    handle(m)                 // your side effect
    markProcessed(m.ackId)    // in the SAME transaction as the side effect, ideally
}
client.ack(subscriptionId, msgs.map(_.ackId))
```

Put `markProcessed` in the same transaction as your side effect and repeats cost
nothing. End to end, pair it with `idempotencyKey` on
[publish](#idempotent-publish) so a producer retry does not create a second
message in the first place: dedup at the producer boundary, dedup at the consumer
boundary, at-least-once in between.

REST payloads are treated as UTF-8 text.

Two guarantees HermesMQ genuinely does not offer — back-delivery to subscriptions
created after a publish, and from-zero reconstruction of the subscription index —
are in the [Roadmap](#roadmap) with their rationale.

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
| `hermesmq_subscription_consumers` | gauge | `subscription` | Distinct named consumers active within the window |
| `hermesmq_publish_deduplicated_total` | counter | `topic` | Publishes collapsed as duplicates (per-node, best-effort) |
| `hermesmq_topic_producers` | gauge | `topic` | Distinct named producers active within the window (per-node) |
| `hermesmq_messages_expired_total` | counter | `subscription` | Messages expired under their TTL (counted whether dropped or dead-lettered) |

```bash
curl localhost:8080/v1/subscriptions   # [{"subscriptionId":"s1","backlog":2,...}]
curl localhost:8080/metrics            # hermesmq_subscription_backlog{subscription="s1"} 2 ...
```

The stats projections use Pekko Projection's **exactly-once** mode so counters
are never double-counted on replay — the counter update and the offset commit
land in one transaction. That is a different claim from message delivery, and
achievable for the same reason delivery's isn't: here the broker owns both sides
of the transaction. See [Delivery semantics](#delivery-semantics). Counts are maintained **forward**: because journal
retention purges old events, these read models cannot be rebuilt from a
from-zero replay once events are deleted — the projections run continuously from
first start, so live counts stay accurate.

### Logging

Log format is selected by `LOG_FORMAT`. The **Docker image defaults to
`LOG_FORMAT=json`** — one structured JSON object per event, ready for Loki and
the constellation's error-tracking / self-healing loop — while local `sbt run`
(no env set) stays human-readable **text**. Set `LOG_FORMAT=text` in-container to
override, or `LOG_FORMAT=json` locally to preview.

JSON logs use the constellation-wide field schema (via
[`logstash-logback-encoder`](https://github.com/logfellow/logstash-logback-encoder))
so queries resolve identically across services: `@timestamp`, `level`,
`logger_name`, `thread_name`, `message`, `service` (here `hermesmq`),
`stack_trace` (on error), plus any MDC entries as top-level fields. This is a
format-only concern — log levels and call sites are unchanged.

### Request tracing

A correlation id ties a broker log line back to the request that caused it, and
ties a consumer's work back to the producer that published the message. HermesMQ
plays **two distinct roles**, and the distinction matters:

**At the request boundary — adopt or mint.** Every inbound REST call and gRPC RPC
runs under a correlation id. An inbound id is **adopted** (`X-Correlation-Id` HTTP
header / `x-correlation-id` gRPC metadata — the broker's callers are trusted
internal services); a request arriving without one gets a fresh id **minted**, so
the broker's own handling is always traceable. That id lands in the SLF4J MDC, so
it appears as a top-level `correlationId` field on every JSON log line emitted
while serving the call, and it is **echoed back** to the caller — on the HTTP
response header, and on gRPC response metadata including trailers-only error
responses. A failing request still carries its id.

**On the bus — adopt only, never mint.** A message's correlation id belongs to the
**producer**. `Publish` adopts `PublishRequest.correlation_id` (gRPC) or the
`X-Correlation-Id` header (REST) and **journals it in the `MessagePublished`
event**, so it survives replay, restart, and cross-node delivery rather than
living in request memory. Every delivery — `Pull`, `StreamMessages`, `Consume` —
returns it verbatim on `Message.correlation_id`. The broker never strips,
overwrites, or invents a message's id: fan-out to ten subscriptions delivers the
same id ten times, and a publish with no id delivers an empty one (the consumer
decides whether to mint).

All three names come from the shared
[Lexicon](https://github.com/vezril/the-lexicon) constants, so every service in
the constellation agrees on the vocabulary. All three client libraries expose it:
`correlationId` on the Scala and JS publish options, `correlation_id` on the
Python client, and on the received-message type in each.

```bash
# Adopted at the boundary, echoed back, and carried onto the message.
curl -i -X POST localhost:8080/v1/topics/orders/messages \
  -H 'Content-Type: application/json' -H 'X-Correlation-Id: req-42' \
  -d '{"payload":"charge"}'                      # 202, response carries X-Correlation-Id: req-42

curl -X POST localhost:8080/v1/subscriptions/s1/pull \
  -H 'Content-Type: application/json' -d '{"max":10}'
# -> {"messages":[{"ackId":"…","payload":"charge","correlationId":"req-42"}]}
```

### Dead-letter operations

`deadLetteredTotal` tells you *that* messages died; these two endpoints tell you
**which**, and put them back.

| Method & path | Body / query | Result |
|---|---|---|
| `GET /v1/subscriptions/{id}/dead-letters` | `?limit=50&cursor=…` | `200` `{deadLetters:[{originalMessageId, originTopic, payload, attributes, deliveryAttempts, deadLetteredAt, redrivenAt?}], nextCursor?}` · `400` bad cursor · `404` |
| `POST /v1/subscriptions/{id}/dead-letters/redrive` | `{"messageIds":["…"]}` or `{"all":true}`, optional `"force":true` | `200` `{redriven, alreadyRedriven, unknown, failed:[{messageId,reason}]}` · `400` · `403` · `404` |

**Paging.** Browse returns a bounded page, newest first — `limit` defaults to `50`
and clamps at `500`. When more remain the response carries `nextCursor`; pass it
back to continue, and an absent cursor means the walk is done. Paging is keyset
based on `(deadLetteredAt, originalMessageId)`, so a dead-letter arriving
mid-walk cannot make the next page skip or repeat an entry — which an `OFFSET`
walk would, and dead-letters arrive exactly when you are paging through them. The
cursor is opaque; a cursor the service did not issue is a `400`, not a silent
restart.

**Browse is a peek, not a pull.** It reads a durable read model, so it takes no
lease, counts no delivery attempt, and does not compete with a consumer of the
dead-letter topic. It is safe to poll from an operator console, and repeatable.

**Redrive republishes to the origin topic** — the one recorded as
`x-original-topic` when the message was dead-lettered, falling back to the
subscription's topic for dead-letters written before that attribute existed. Each
id is reported independently, so one failure (a deleted origin topic, say) does
not sink the rest of the batch. A redriven message is fanned out like any new
publish.

```bash
curl 'localhost:8080/v1/subscriptions/s1/dead-letters?limit=2'
# {"deadLetters":[{"originalMessageId":"m-1","originTopic":"orders","payload":"charge",...}],
#  "nextCursor":"MTc2ND..."}

# continue the walk
curl 'localhost:8080/v1/subscriptions/s1/dead-letters?limit=2&cursor=MTc2ND...'

curl -X POST localhost:8080/v1/subscriptions/s1/dead-letters/redrive \
  -H 'Content-Type: application/json' -d '{"messageIds":["m-1"]}'
# {"redriven":["m-1"],"alreadyRedriven":[],"unknown":[],"failed":[]}

curl -X POST localhost:8080/v1/subscriptions/s1/dead-letters/redrive \
  -H 'Content-Type: application/json' -d '{"all":true}'   # redrive everything
```

**Idempotency needs no configuration.** Redrive *claims* each entry with an
atomic compare-and-swap on its `redrivenAt` stamp before publishing, so exactly
one of several concurrent or repeated redrives publishes and the rest report
`alreadyRedriven`. This holds with `HERMESMQ_DEDUP_WINDOW` at its `0s` default —
the publish-dedup key is still passed as a second line of defence, but it is no
longer the guarantee. A failed publish releases the claim, so a plain retry works
once you have fixed the cause.

> **When you need `force`.** Claiming before publishing is deliberate: the
> alternative ordering risks publishing a message twice, while this one risks an
> entry left claimed by a process that died before publishing. That entry reports
> `alreadyRedriven` forever, so `{"force": true}` claims regardless of an existing
> stamp. It is explicit precisely because it can produce duplicates — the safe
> path stays the default.

**Visible even with no dead-letter topic configured.** The read model is built
from the subscription journal's dead-letter events, which are journaled whether or
not `HERMESMQ_DEAD_LETTER_TOPIC` is set. Where the broker would otherwise drop an
exhausted message with only a warning log, browse still shows it and redrive can
still replay it.

Like the other read models here it is **eventually consistent** and maintained
**forward**: a message dead-lettered moments ago may not have landed yet, and
dead-letters that predate this feature are not visible (journal retention means
they cannot be backfilled). Both endpoints return `200` with an empty result
rather than erroring in those cases.

### Named consumers

A subscription is already a **competing-consumer group** — concurrent consumers
of one subscription load-balance, each message leased to exactly one of them. To
make that fan-out observable, a consumer may **name itself**: `Pull`,
`StreamMessages`, and `Consume` carry an optional `consumer_id` (REST pull takes
`consumerId` in the body). It's purely for observability — it does not change
which messages are leased.

Naming a consumer feeds two things: the `hermesmq_subscription_consumers` gauge
(distinct consumers seen within the activity window, per subscription), and a
`consumer` field on the JSON logs emitted while serving that call. An empty/absent
id is anonymous (today's behaviour).

| Variable                            | Default | Description                                                    |
|-------------------------------------|---------|----------------------------------------------------------------|
| `HERMESMQ_CONSUMERS_ACTIVITY_WINDOW`| `60s`   | How long a consumer counts as active since its last consume. `0` = off (registry + metric disabled). |

> **Caveat:** the registry is an in-memory, **per-node**, best-effort view — the
> gauge reflects consumers attached to *this* instance. That is exact under the
> single-replica deployment; cluster-wide aggregation is a future extension (the
> metric name and meaning would not change).

### Named producers

The producer-side twin of named consumers: a publisher may **name itself** with
an optional `producer_id` (gRPC `PublishRequest`) / `producerId` (REST publish
body), so operators can see who's publishing to a topic. Purely observability —
it does not change whether or how a message is published.

Naming a producer feeds the `hermesmq_topic_producers` gauge (distinct producers
seen within the activity window, per topic) and a `producer` field on the JSON
logs emitted while serving that publish. An empty/absent id is anonymous.

| Variable                            | Default | Description                                                    |
|-------------------------------------|---------|----------------------------------------------------------------|
| `HERMESMQ_PRODUCERS_ACTIVITY_WINDOW`| `60s`   | How long a producer counts as active since its last publish. `0` = off (registry + metric disabled). |

> **Caveat:** same as the consumer gauge — in-memory, **per-node**, best-effort;
> exact under the single-replica deployment, cluster-wide aggregation a future
> extension.

## Client libraries

One maintained client per constellation language wraps the REST API so services
don't hand-roll HTTP — **Scala** (the `client` sbt module, below), **Python 3**
([`clients/python`](clients/python)), and **JavaScript**
([`clients/js`](clients/js), zero-dependency ESM). All three share the same
surface, error model, and optional auth; see the matrix and conformance
checklist in [`clients/README.md`](clients/README.md).

### Scala client library

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
  _      <- client.createTopic(TopicId.from("orders").toOption.get)
  _      <- client.createSubscription(SubscriptionId.from("s1").toOption.get, TopicId.from("orders").toOption.get)
  result <- client.publish(
              TopicId.from("orders").toOption.get,
              "hello",
              Map("k" -> "v"),
              idempotencyKey = Some("order-42"),   // broker dedups repeats → result.deduplicated
              producerId = Some("checkout-1")      // shows in hermesmq_topic_producers
            )
  msgs   <- client.pull(SubscriptionId.from("s1").toOption.get, max = 10, consumerId = Some("worker-1"))
  acked  <- client.ack(SubscriptionId.from("s1").toOption.get, msgs.map(_.ackId))
yield ()
```

Methods return `Future`s; error statuses fail the future with a
`HermesClientException` (carrying the HTTP status), while a not-found on a read
(`getTopic`) is an empty result. Also available: `deleteSubscription`,
`modifyAckDeadline`, `listTopics`/`listSubscriptions`, `health`, and optional
auth (`HermesClient(uri, token = Some(...))` → `Authorization: Bearer`, or
`apiKey = Some(...)` → `X-API-Key`). The caller owns the `ActorSystem`.

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
[`server/src/main/resources/schema/postgres.sql`](server/src/main/resources/schema/postgres.sql).
If the database is unreachable, persistence fails loudly (the operation is never
silently accepted-and-lost) and `GET /health/ready` reports `503`.

### Schema self-migration

On startup — before it binds any endpoint — the service **applies the bundled
schema itself** against `HERMESMQ_DB_*`, so a fresh PostgreSQL (k3s, compose, CI,
or a bare instance) needs no external init step. The schema is idempotent
(`IF NOT EXISTS` throughout), so applying it over an already-provisioned database
is a harmless no-op. Startup waits up to `HERMESMQ_DB_MIGRATE_MAX_WAIT` for the
database to become reachable (absorbing k8s start-ordering) and then fails fast
with a clear error if it does not — the schema is guaranteed to exist before any
projection runs or readiness reports ready.

| Variable                    | Default | Description                                                        |
|-----------------------------|---------|--------------------------------------------------------------------|
| `HERMESMQ_DB_MIGRATE_ON_START` | `true`  | Apply `schema/postgres.sql` on boot. `false` = rely on externally-managed migrations. |
| `HERMESMQ_DB_MIGRATE_MAX_WAIT` | `30s`   | How long to wait for the database before boot-time migration gives up. |

This is idempotent *apply-the-current-schema*, not a versioned migration
framework — additive (`IF NOT EXISTS`) schema changes apply cleanly; destructive
evolution would need a dedicated migration tool. Operators who provision the
schema out-of-band (initdb, Flyway, a privileged DBA role) set
`HERMESMQ_DB_MIGRATE_ON_START=false`. On Kubernetes this means the Postgres
chart needs **no schema ConfigMap** — the service provisions itself on first boot.
The docker-compose `docker-entrypoint-initdb.d` mount below is now redundant (the
service would self-migrate anyway) but is left in place as harmless
belt-and-suspenders.

Unit tests use an in-memory journal and need **no database**. The PostgreSQL
integration tests (durable persistence, and schema self-migration against a fresh
database) are excluded from the default run; run them (with Docker) via:

```bash
sbt -Dit=true "testOnly *PostgresPersistenceIntegrationSpec *SchemaMigrationIntegrationSpec"
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
cluster-wide instances. (Still at-least-once, and still no back-delivery to
subscriptions created after a publish — see the [Roadmap](#roadmap).)

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
      # Applies the schema on first start. Redundant now that the service
      # self-migrates on boot (HERMESMQ_DB_MIGRATE_ON_START, default true); kept
      # as harmless belt-and-suspenders.
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
server/                      # the service (config, http, grpc, persistence, delivery, cluster, tracing, Main) + Docker
  src/…/me/cference/hermesmq/{config,http,grpc,persistence,delivery,cluster,tracing}
  src/main/resources/        # application.conf, logback.xml, schema/postgres.sql
client/                      # the Scala client library (typed REST wrapper)
  src/…/me/cference/hermesmq/client/HermesClient.scala
clients/                     # the non-JVM clients (own toolchains + test suites)
  python/                    # hermesmq_client (Python 3)
  js/                        # zero-dependency ESM client
openspec/                    # the spec-driven workflow: canonical specs + archived changes
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

### Back-merging after a release

Merging `development` into `main` creates a merge commit that exists **only** on
`main`. Nothing carries it back, so without intervention the branches drift apart
by one commit per release and "what is unreleased?" stops having a clean answer.

GitHub cannot fast-forward a pull request — the options are merge, squash, and
rebase — and `main` requires a PR with `enforce_admins`, so a direct
fast-forward push is not available either. Removing that gate to avoid a merge
commit would trade a real safeguard for a cosmetic one.

Instead, [`sync-main.yml`](.github/workflows/sync-main.yml) opens a back-merge PR
automatically on every push to `main`, so the sync is impossible to forget rather
than merely remembered. It is a no-op when `development` already contains
everything on `main`, reuses its open PR instead of stacking duplicates, and
**does not auto-merge** — CI runs and a human merges, so `development`'s branch
protection still applies. A merge conflict there is a real signal (`main`
received a commit that never went through `development`) and fails the run rather
than being force-resolved.

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
