## Why

Feature 3 gave us pure `decide`/`evolve` aggregates, but nothing is durable — a restart loses all state. HermesMQ's core guarantee is that accepted messages survive crashes and unacknowledged messages resume delivery exactly where they left off. This feature makes that real: wrap the Topic and Subscription aggregates in Pekko Persistence `EventSourcedBehavior`s backed by a **configurable database (PostgreSQL by default)**, so the journaled event log becomes the source of truth and a publish is only acknowledged once its event is durably written.

## What Changes

- Wrap `Topic` and `Subscription` as supervised `EventSourcedBehavior` actors whose command handler runs the pure `decide` (persisting the resulting events, or replying with the `Rejection`) and whose event handler runs the pure `evolve`. Replies are sent **after** the event is durably persisted (`persist(...).thenReply`), so an accepted publish/ack is never lost.
- Add a **configurable persistence backend** via `pekko-persistence-jdbc`:
  - Journal + snapshot store selected by config; **PostgreSQL** is the default profile.
  - Connection settings (host, port, database, user, password) sourced from `application.conf` with `HERMESMQ_DB_*` environment overrides.
  - A separate **in-memory** journal profile for fast unit tests (no database required).
- Register **event serialization** for the domain events (JSON) so they can be journaled and replayed across restarts and code changes.
- Provide the **database schema** (pekko-persistence-jdbc DDL) and document how to apply it.
- Verify **recovery**: after a simulated restart, replayed events reconstruct aggregate state, and unacknowledged messages remain outstanding.
- Extend readiness so the service is ready only when persistence is reachable (deferred detail — see design), and update the README with DB configuration and how to run Postgres locally (docker-compose snippet).

Scope note: this is **basic persistence** — durable journaling + recovery for the existing aggregates. Snapshots are configured but tuning, event adapters/schema-evolution tooling, projections/read models (CQRS query side), and journal purging of acked messages are later features.

## Capabilities

### New Capabilities
- `event-sourced-aggregates`: The Topic and Subscription persistent actors — `EventSourcedBehavior` wrapping `decide`/`evolve`, durable-write-before-reply, and recovery-by-replay.
- `persistence-backend`: The configurable database backend — JDBC journal/snapshot selection (PostgreSQL default, in-memory for tests), connection configuration, event serialization, and schema.

### Modified Capabilities
- `health-endpoint`: Readiness is extended to also reflect persistence availability (the service is not "ready" if it cannot reach its journal). Liveness is unchanged.

## Impact

- **New dependencies**: `pekko-persistence-typed`, `pekko-persistence-jdbc`, `pekko-serialization-jackson` (or spray-json binding), a PostgreSQL JDBC driver, a connection pool (HikariCP via the plugin), and `pekko-persistence-testkit` + an in-memory/`testcontainers-postgresql` for tests.
- **New source**: `TopicEntity`/`SubscriptionEntity` behaviors, a persistence config module, event serializers, and DDL resources. Command/reply protocol types (replies carrying `Either[Rejection, Ack]`).
- **Config**: new `hermesmq.db` section + `HERMESMQ_DB_*` env overrides; Pekko persistence journal/snapshot plugin config with a Postgres and an in-memory profile.
- **Infra**: requires a reachable PostgreSQL to run the service (a `docker-compose.yml` for local Postgres is added); CI unit tests use the in-memory profile so they need no database.
- **Guarantee**: with this in place, accepted events survive restart; this is the write-side durability the broker depends on.
