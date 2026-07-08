## Why

HermesMQ exposes a REST API, but a Scala application that wants to use the broker must hand-roll HTTP calls and JSON. A native client library gives consumers a typed, async Scala API (create topics, publish, subscribe, pull, ack) and a published artifact they can depend on — without dragging in the server's persistence/projection/HTTP-server stack. Delivering this requires splitting the build so the client and server share domain value types but ship independently.

## What Changes

- **Modularize the sbt build** into three modules that share code cleanly:
  - `domain` — the pure value types (`TopicId`, `SubscriptionId`, `MessageId`, `AckId`, `Message`, `AckDeadline`, `ValidationError`) and aggregates; no Pekko/IO dependencies. Depended on by both server and client.
  - `server` — the existing service (persistence, projection, HTTP, `Main`, Docker image). Depends on `domain`.
  - `client` — the new library. Depends on `domain` + a lightweight HTTP client (pekko-http client); no persistence/server deps.
- **Implement a `HermesClient`** — a typed, `Future`-based Scala API over the REST endpoints:
  - Topics: `createTopic`, `getTopic`, `updateTopic`, `deleteTopic`.
  - Messages: `publish` (returns the `MessageId`).
  - Subscriptions: `createSubscription`, `pull` (returns received messages with ack ids + payloads), `ack`.
  - Maps HTTP outcomes to typed results/failures (e.g. a failed `Future` with a `HermesClientException` carrying status + reason; not-found modeled where it is a normal outcome).
- **Publish the library**: on release, publish the `domain` and `client` artifacts to GitHub Packages so Scala apps can depend on them; the `server` continues to ship as the Docker image.
- Document client usage in the README (dependency coordinates + a publish/consume example in Scala).

Scope note: a **REST-based** client (gRPC remains a later feature). The client is a thin, dependency-light wrapper; connection pooling/retries/backpressure beyond pekko-http defaults, auth, and streaming consume are out of scope for this basic cut.

## Capabilities

### New Capabilities
- `scala-client`: The `HermesClient` library — a typed, async Scala API over the REST endpoints, packaged as an independent artifact.

### Modified Capabilities
- `project-scaffolding`: The build becomes a multi-module sbt project (`domain`, `server`, `client`) that still compiles and tests from a clean checkout; the server module owns the app/Docker settings.
- `ci-cd-pipeline`: Release additionally publishes the `domain` and `client` library artifacts (the server still ships as the image); CI builds/tests all modules.

## Impact

- **Build restructure**: `build.sbt` becomes a multi-project build; existing sources move — `domain/**` for value types + aggregates, `server/**` for the rest (config, http, persistence, delivery, `Main`), keeping package names (`me.cference.hermesmq.*`). Docker/native-packager settings move to the `server` module.
- **New module `client`**: `me.cference.hermesmq.client.HermesClient` + client-side request/response models and JSON (reusing `domain` value types); tests that run the client against an in-process pekko-http stub of the API.
- **Dependencies**: `client` adds only pekko-http (client) + spray-json + `domain`; it does **not** depend on persistence/projection.
- **CI/release**: `sbt test` runs across modules; release publishes `domain`/`client` jars to GitHub Packages and the `server` image to Docker Hub. Versioning (sbt-dynver) is shared across modules.
- **Consumers**: a Scala app can `libraryDependencies += "me.cference.hermesmq" %% "hermesmq-client" % "x.y.z"` and talk to a running broker with typed calls.
- **No runtime behavior change** to the server; this is packaging + a new client surface.
