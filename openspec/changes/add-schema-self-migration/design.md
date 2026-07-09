## Context

The persistence schema (`server/src/main/resources/schema/postgres.sql`) is a flat DDL script — 11 tables + 5 indexes for `pekko-persistence-jdbc`, Pekko Projection, and the durable read models — with **every** `CREATE` guarded by `IF NOT EXISTS` and no functions, triggers, or dollar-quoted bodies. It is bundled on the classpath (in the jar). Today it is applied out-of-band: docker-compose mounts it into `docker-entrypoint-initdb.d`, and `Main` assumes the tables exist when projections and aggregates start persisting. `DbConfig` already exposes `jdbcUrl`/`user`/`password`, and `PersistenceHealth` already opens a plain `DriverManager` connection to probe reachability — the same primitives a migrator needs. `Main` loads config, then (on `Right`) builds `PersistenceHealth`/`Readiness` and enters `Behaviors.setup`, which immediately inits sharding and the projections. There is a natural seam **between config load and `Behaviors.setup`** to run migration synchronously.

## Goals / Non-Goals

**Goals:**
- Apply the bundled schema idempotently on boot so a fresh Postgres (k3s/compose/CI/bare) needs no external init step.
- Guarantee the schema exists **before** any projection runs or readiness reports ready.
- Tolerate a briefly-unready database (bounded wait) without crash-looping; fail fast and clearly if it stays down.
- Stay non-breaking (idempotent no-op over an already-provisioned DB) and opt-out-able.

**Non-Goals:**
- A versioned migration framework (Flyway/Liquibase), migration history table, or down-migrations. This is idempotent apply-the-current-schema, not schema *evolution*. (Future schema changes still edit `postgres.sql`; because statements are `IF NOT EXISTS`, additive changes apply cleanly — destructive/altering changes are out of scope and would need a real migration tool.)
- Per-tenant or multi-database provisioning.
- Changing how the schema is authored or where it lives.

## Decisions

**1. Apply the whole script in one JDBC `Statement.execute`, not statement-by-statement.**
The PostgreSQL JDBC driver's simple-query protocol executes a multi-statement, `;`-separated DDL string as one call, in a single implicit transaction (all-or-nothing). This avoids a hand-rolled SQL splitter that would break on any future dollar-quoted function body. Chosen over split-on-`;` (fragile) and over a migration library (heavyweight for an idempotent single-file apply). The script's `IF NOT EXISTS` guards make re-application a no-op.

**2. Run synchronously between config load and `Behaviors.setup`; fail-fast to exit(1).**
A new `SchemaMigrator.migrate(dbConfig): Either[MigrationError, Unit]` runs in `Main` right after the config `Right(...)` match, before the actor system starts. On `Left`, print the error and `sys.exit(1)` — identical to the existing config-error path. This gives a hard ordering guarantee (tables exist before projections/aggregates touch them) with the simplest possible control flow. Chosen over an async, readiness-gated migration (projections would race the tables) and over a Pekko-extension hook (needs the system already up).

**3. Bounded wait-for-database with fixed-interval retry.**
The migrator retries the initial connection every ~1s until `hermesmq.db.migrate-max-wait` elapses, then applies the script; if the database never becomes reachable in that window it returns `Left`. This absorbs k8s start-ordering (Postgres a few seconds behind the app) without a crash-loop, while still failing fast if the DB is genuinely down. Chosen over immediate-fail-and-let-k8s-restart (noisy CrashLoopBackOff) and over unbounded waiting (a hung boot that never surfaces the problem).

**4. Load the DDL from the classpath resource, not a filesystem path.**
`getClass.getResourceAsStream("/schema/postgres.sql")` reads the exact file bundled in the jar — one source of truth, no external mount required. Chosen over a configurable file path (reintroduces the drift the change is meant to remove).

**5. `hermesmq.db.migrate-on-start` (default true) as the opt-out.**
Default-on is safe precisely because the schema is idempotent — enabling it changes nothing for an existing initdb/compose deployment (the apply is a no-op). Operators using externally-managed migrations set it `false` to skip. Kept under the existing `hermesmq.db` config block alongside the connection settings; parsed by `DbConfig`, with `migrate-max-wait` validated non-negative (fail-fast, mirroring the other config parsers).

## Risks / Trade-offs

- **A future non-idempotent/altering schema change would not be handled** (this applies, it doesn't migrate versions). → Documented as a non-goal; additive `IF NOT EXISTS` changes are fine, and a real migration tool is the answer if/when destructive evolution is needed. The single-transaction apply at least rolls back a partially-failed script.
- **Least-privilege DB users**: applying DDL needs `CREATE` on the schema; a locked-down runtime role might lack it. → The opt-out (`migrate-on-start = false`) covers operators who provision the schema with a privileged role and run the service with a restricted one.
- **Startup latency / wait window**: a misconfigured DB delays boot up to `migrate-max-wait`. → Bounded and configurable; the failure is explicit (exit 1 with the DB error), not a silent hang.
- **Concurrent starts (multi-replica) racing the apply**: two instances could apply simultaneously. → `IF NOT EXISTS` + Postgres DDL locking make concurrent application safe (idempotent); HermesMQ's k8s deploy is single-replica regardless.

## Migration Plan

1. Ship with `migrate-on-start = true`. Existing deployments (schema already present via initdb) are unaffected — the boot apply is a no-op.
2. k3s/pg-service: leave `initdbConfigMap` empty; the service provisions its own schema on first boot.
3. Rollback: set `hermesmq.db.migrate-on-start = false` (revert to external/initdb provisioning) or redeploy the prior image; no data or schema changes to undo.

## Open Questions

- None blocking. A future switch to a real migration framework (if destructive schema evolution is ever needed) would supersede this; the config toggle leaves that door open.
