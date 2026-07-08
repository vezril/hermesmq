## Context

The pure `Topic`/`Subscription` aggregates (`decide`/`evolve`) exist but hold no durable state. Pekko Persistence's `EventSourcedBehavior` takes exactly a command handler and an event handler — the shape Feature 3 was built to. This feature wraps the aggregates as persistent actors on a **configurable JDBC backend (PostgreSQL default)**, delivering the broker's core durability guarantee: an accepted publish/ack is journaled before it is acknowledged, and a restart replays the journal to resume exactly where it left off. TDD and FP remain constraints; the pure core stays untouched and unit-testable, with persistence tested via the in-memory journal.

## Goals / Non-Goals

**Goals:**
- `TopicEntity` / `SubscriptionEntity` as `EventSourcedBehavior`s that delegate to `decide`/`evolve`, persisting before replying.
- Configurable backend: PostgreSQL via `pekko-persistence-jdbc` (default) and an in-memory profile for tests, selected by config, connection via `HERMESMQ_DB_*`.
- Explicit JSON event serialization (no Java serialization).
- Recovery verified: replay reconstructs state; unacked stays outstanding; acked does not reappear.
- Schema DDL + local Postgres (`docker-compose`) + README docs.
- Readiness gated on persistence reachability.

**Non-Goals:**
- Projections / read models / CQRS query side (later).
- Journal purging of acknowledged messages, snapshot tuning, event/schema-evolution adapters (later).
- Delivery/redelivery scheduling and topic→subscription fan-out (later).
- gRPC API exposure of these entities (later).
- Clustering / sharding across nodes (explicit project non-goal).

## Decisions

**Persistent actors wrap the pure aggregate; handlers are thin.**
`commandHandler = (state, cmd) => Topic.decide(state, cmd) match { Right(evts) => Effect.persist(evts).thenReply(replyTo)(_ => Ack); Left(rej) => Effect.reply(replyTo)(Rejected(rej)) }` and `eventHandler = Topic.evolve`. All business logic stays in the tested pure functions; the entity only adds persistence, reply protocol, and id. *Alternative:* logic in the actor — rejected in Feature 3 for exactly this reason.

**Reply-after-persist for durability.**
Use `Effect.persist(events).thenReply(replyTo)(...)` so the success reply is emitted only after the journal write completes. This is the "a publish is acknowledged only once its event is durably written" guarantee. Rejections use `Effect.reply` (no persist). *Alternative:* reply-then-persist — rejected: would ack writes that could still be lost.

**Reply protocol: commands carry `replyTo`, replies are `Either`-shaped.**
Add a small reply ADT (e.g. `sealed trait CommandReply { case Accepted; case Rejected(Rejection) }`) and make entity commands carry `replyTo: ActorRef[CommandReply]`. The Feature-3 pure commands are wrapped (or extended) with the reply address at the entity boundary, keeping `decide` reply-agnostic. *Alternative:* throw on rejection — rejected: rejections are normal domain outcomes, not failures.

**Backend selection via a persistence profile in config.**
`hermesmq.persistence.profile = postgres | in-memory` chooses which Pekko journal/snapshot plugin is active (`pekko.persistence.journal.plugin`). Postgres profile wires `pekko-persistence-jdbc` + a Postgres `slick` datasource from `hermesmq.db.*` (`HERMESMQ_DB_*` env overrides). In-memory profile uses `pekko.persistence.testkit` / inmem journal for tests. *Alternative:* separate `application-test.conf` only — the profile switch is cleaner and lets the same binary run either way.

**Persistence backend: `pekko-persistence-jdbc` + PostgreSQL + HikariCP (via slick).**
JDBC/Postgres is the requested default and the pragmatic single-node choice. The plugin manages journal/snapshot tables; we ship its Postgres DDL. *Alternative:* `pekko-persistence-r2dbc` (reactive) — heavier/newer; JDBC is simpler and well-trodden for a single node.

**Serialization: `pekko-serialization-jackson` (JSON) with Java serialization disabled.**
Register domain events for Jackson JSON binding; keep `pekko.actor.allow-java-serialization = off` so an unregistered event fails fast rather than silently using Java serialization. Scala 3 + Jackson needs the Scala module; events are simple case classes/enums. *Alternative:* spray-json (already a dep) via a custom `SerializerWithStringManifest` — viable, but Jackson-json is the idiomatic Pekko persistence path with less hand-rolled manifest code. Flag as an open question.

**Testing: in-memory journal for unit tests; Testcontainers Postgres for one integration test.**
`EventSourcedBehaviorTestKit` on the in-memory profile drives persist/recovery/reply assertions with no database, so **CI needs no Postgres**. A single Testcontainers-backed integration test exercises the real Postgres profile + DDL; it is tagged and skipped when Docker is unavailable. *Alternative:* only in-memory — misses real-driver/schema issues; only Testcontainers — slow and Docker-dependent for every test.

**Readiness gated on persistence.**
Extend the readiness probe to also check journal reachability (a lightweight DB ping / plugin health check), so a node that cannot durably persist reports `503` and is not routed traffic. Liveness stays independent. Ties into the existing `HealthRoutes` readiness flag.

## Risks / Trade-offs

- **Reply-after-persist latency** → acceptable and required for correctness; batching is a later optimization.
- **Jackson + Scala 3 enums serialization quirks** → cover with explicit round-trip serialization tests per event; if Jackson friction is high, fall back to a spray-json `SerializerWithStringManifest` (open question).
- **Testcontainers needs Docker in CI** → the integration test is opt-in/tagged; the default CI path uses in-memory, so the pipeline stays green without Docker. Document how to run the integration test locally.
- **Schema drift / missing tables** → ship the plugin DDL and a `docker-compose` that applies it; surface "missing schema" errors clearly (edge-case scenario).
- **Readiness DB check could flap or add load** → use a cheap, cached check with a short interval rather than a per-request query.
- **`persistenceId` design** → `"Topic|<topicId>"`, `"Subscription|<subscriptionId>"`; entity ids must be stable and collision-free. Document the scheme.
- **Secrets (DB password) in config** → read from env (`HERMESMQ_DB_PASSWORD`), never commit; `.gitignore` already excludes env/credentials files.

## Migration Plan

Additive; TDD order (unit tests on the in-memory profile first, real Postgres last):
1. Add persistence deps; define the reply protocol + entity command wrappers.
2. `TopicEntity` on in-memory journal — RED tests for persist-then-reply + rejection → implement.
3. `SubscriptionEntity` on in-memory journal — create/record/ack/modify persist + reply.
4. Recovery tests (restart via `EventSourcedBehaviorTestKit.restart`) — unacked stays outstanding, acked does not reappear, fresh id → empty.
5. Event serialization: register serializer, round-trip tests, Java serialization off.
6. Postgres profile config + `HERMESMQ_DB_*` + DDL + `docker-compose`; one Testcontainers integration test (tagged, Docker-gated).
7. Readiness: persistence-reachability check wired into `HealthRoutes`.
8. README: DB config, run Postgres locally, apply schema, run the integration test.

Rollback: additive files + config; reverting removes the entities/backend and returns to the stateless service. Any local Postgres data is disposable.

## Open Questions

- **Serialization library**: `pekko-serialization-jackson` (proposed) vs a spray-json `SerializerWithStringManifest` reusing the existing dep. Which does the project prefer before events proliferate?
- **Where entities are instantiated**: this feature builds and tests the entities but does not yet expose them via the API. Should `Main` spin up a sample/registry now, or wait for the gRPC feature? (Assumption: wire persistence config + serialization + readiness, but leave API exposure to a later feature.)
- **Snapshotting**: enable `snapshotWhen`/retention now (basic) or defer tuning? (Assumption: configure a simple snapshot-every-N; no aggressive tuning.)
- **CI Postgres**: keep the integration test Docker-gated/opt-in (proposed), or add a Postgres service to the CI workflow so it always runs? (Assumption: opt-in now; revisit in the Docker-Hub/CI feature.)
