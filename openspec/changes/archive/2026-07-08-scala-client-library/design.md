## Context

Everything currently lives in one sbt module. A real client library must ship independently — a consumer should depend on it without pulling in pekko-persistence/projection or the HTTP server. The user chose a **REST client** (gRPC later) and a **multi-module** build. This feature restructures the build into `domain`/`server`/`client` and adds `HermesClient`, a typed async wrapper over the REST API. No server runtime behavior changes.

## Goals / Non-Goals

**Goals:**
- Split into `domain` (pure, shared), `server` (current service + Docker), `client` (new library).
- `HermesClient`: typed `Future` methods for topics, publish, subscriptions, pull, ack over REST.
- Dependency-light `client` (domain + pekko-http client only).
- Publish `domain` + `client` artifacts on release; keep the server image.
- Tests that exercise the client against an in-process API stub; whole build green.

**Non-Goals:**
- gRPC / streaming consume (later).
- Retries/backoff/circuit-breaking/auth beyond pekko-http defaults.
- A Java client or other language SDKs.
- Changing server behavior or the REST contract.

## Decisions

**Module split: `domain`, `server`, `client`.**
`domain` holds the pure value types and aggregates (no IO deps) — both other modules depend on it. `server` is the current code (config/http/persistence/delivery/`Main`) plus the Docker/native-packager settings. `client` depends on `domain` + pekko-http client, nothing server-side. Package names stay `me.cference.hermesmq.*`. *Alternative:* keep one module — rejected: consumers would inherit the server's heavy deps.

**What goes in `domain`.**
Move the whole `me.cference.hermesmq.domain` package (identifiers, `Message`, `AckDeadline`, `ValidationError`, `Rejection`, commands/events, aggregates). It is already pure Scala with no dependencies, so it is a safe shared base. The client uses a subset (value types); the aggregates coming along are harmless. *Alternative:* a thinner `shared` module of only value types — more surgical but splits the domain awkwardly; move the whole package for simplicity.

**Client transport: pekko-http client + spray-json.**
`HermesClient` uses `Http().singleRequest` with spray-json (un)marshalling, mirroring the server's request/response models. Reuses `domain` value types for ids/message. Needs an `ActorSystem` (typed) and an `ExecutionContext`. *Alternative:* sttp/http4s — another dependency; pekko-http keeps us on one stack and is already used server-side.

**Error model: `Future` failures for errors, `Option` for normal not-found.**
2xx → success; a `getTopic` 404 → `Future.successful(None)` (not-found is a normal outcome for a read); other 4xx/5xx → `Future.failed(HermesClientException(status, body))`. Publish/pull on a missing target → failed future with the not-found status (these are error conditions, not normal reads). *Alternative:* return `Either` everywhere — heavier ergonomics; failed futures are idiomatic for I/O and let callers use recover.

**Client API surface.**
`createTopic(id, labels)`, `getTopic(id): Future[Option[TopicView]]`, `updateTopic(id, labels)`, `deleteTopic(id)`, `publish(topicId, payload, attributes): Future[MessageId]`, `createSubscription(subId, topicId)`, `pull(subId, max): Future[List[ReceivedMessage]]`, `ack(subId, ackIds)`. Payloads are UTF-8 text on the wire (matches the REST surface). `ReceivedMessage(ackId, payload, attributes, publishTime)`.

**Testing the client.**
Spin up the **real server routes** (`TopicAdminRoutes` + `PubSubRoutes`) with **stub services** bound to an ephemeral port in-process, point `HermesClient` at it, and assert round-trips + error mapping. This tests the client's HTTP/JSON wiring against the true contract without a database. *Alternative:* mock the HTTP layer — less faithful; the in-process bind is cheap and real.

**Publishing.**
`sbt-dynver` versions all modules together. Release publishes `domain` and `client` jars to GitHub Packages (each `publish`), and the `server` image to Docker Hub (unchanged). `Docker`/native-packager settings scope to the `server` module so `publish` on `domain`/`client` produces plain library jars. CI runs `sbt test` (aggregated across modules).

## Risks / Trade-offs

- **Large mechanical move** (files relocate to module dirs) → do it in one focused step, keep package names, run the full suite to confirm nothing broke before adding the client.
- **Root/aggregate publishing** → ensure the root aggregate doesn't itself publish an empty artifact; `publish / skip := true` on root, publish only `domain`/`client`.
- **Docker settings on the wrong module** → move `enablePlugins(JavaAppPackaging, DockerPlugin)` + docker settings to `server`; `sbt Docker/publishLocal` must be run in the server module.
- **Client/server DTO drift** → the client mirrors the server's JSON models; keep the request/response shapes in sync (covered by the in-process round-trip tests against the real routes).
- **Version skew between library and server** → same dynver version across modules keeps them aligned per release.
- **CI time** → building three modules is a little slower; acceptable.

## Migration Plan

1. Restructure `build.sbt` into `domain`/`server`/`client` projects + a root aggregate (`publish/skip`); move sources into module dirs (keep packages). Move Docker/native-packager settings to `server`. Run full `sbt test` — everything green (no behavior change).
2. `client`: request/response models + JSON; implement `HermesClient` (topics) — tests against in-process routes → then publish/consume methods → tests.
3. Error mapping (typed failures / `Option` for not-found) — tests for the edge cases.
4. Release/CI: publish `domain`+`client` on release; verify library jars build (`sbt domain/publishLocal client/publishLocal`).
5. README: client dependency coordinates + a Scala publish/consume example.

Rollback: the module split is structural; reverting restores the single module. No data/runtime impact.

## Open Questions

- **Artifact names**: `hermesmq-domain` / `hermesmq-client` (assumed via `name`), same `organization` `me.cference.hermesmq`. Good?
- **Client `ActorSystem` ownership**: the client takes an existing `ActorSystem` (caller-owned) vs creates its own. Assumption: caller provides one (library shouldn't own a system) — with a convenience constructor if helpful.
- **Publish server library too?** The server currently publishes a jar; with the split, do we still publish a `server` jar or only the image? Assumption: only the image for `server`; libraries are `domain`+`client`.
