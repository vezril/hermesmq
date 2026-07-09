## Why

HermesMQ expects its PostgreSQL schema to be **pre-created** — today the docker-compose stack mounts `schema/postgres.sql` into `docker-entrypoint-initdb.d`, and starting against a fresh database without it fails at first persist. On k3s (and any fresh Postgres) that means duplicating the schema into an external init step (a ConfigMap in the Codex deploy repo) that drifts from this repo. Applying the bundled, already-idempotent schema **on boot** removes the external dependency: k8s, compose, CI, and a bare Postgres all "just work" from one source of truth.

## What Changes

- On startup, before the actor system binds any endpoint, the service applies the bundled `schema/postgres.sql` against `HERMESMQ_DB_*` and only proceeds once it succeeds — so the schema always exists before projections run or readiness reports ready.
- The schema is **already idempotent** (every table/index uses `IF NOT EXISTS`), so applying it over an initdb'd or previously-migrated database is a harmless no-op — **non-breaking** for the existing compose setup.
- A **bounded wait-for-database** retry tolerates a Postgres that is briefly unready at boot (k8s ordering) instead of crash-looping; if the database stays unreachable past the limit, startup fails fast with a clear error (mirroring config-error exit).
- New config `hermesmq.db.migrate-on-start` (default **true**) lets operators who prefer externally-managed migrations (initdb, Flyway, a DBA) opt out; and `hermesmq.db.migrate-max-wait` bounds the wait-for-database window.
- The whole script is applied in a single JDBC execution (Postgres runs multiple `;`-separated DDL statements atomically), avoiding fragile SQL splitting.

## Capabilities

### New Capabilities
- `schema-migration`: apply the bundled Postgres schema idempotently at startup before serving — the boot ordering guarantee, the wait-for-database retry, fail-fast on error, and the opt-out toggle.

### Modified Capabilities
- `persistence-backend`: the provided schema is now **applied automatically on startup by default** (previously documented as a manual pre-start step); the manual path remains when `migrate-on-start = false`.

## Impact

- **Server** (new `persistence/SchemaMigrator.scala`; `config/DbConfig.scala` gains `migrateOnStart` + `migrateMaxWait`; `Main.scala` runs the migrator after config load and before `Behaviors.setup`, exiting non-zero on failure): the boot sequence gains one synchronous, idempotent step.
- **Config/docs** (`application.conf` `hermesmq.db.migrate-on-start` / `migrate-max-wait` + `HERMESMQ_DB_*` env; README): document self-migration and the opt-out.
- **Deployment**: the Codex `charts/pg-service` `initdbConfigMap` can stay empty (no schema copy, no drift); docker-compose's initdb mount becomes redundant but harmless (self-migration no-ops over it).
- **No breaking changes**: default-on is safe because the schema is idempotent; existing deployments with the schema already present are unaffected.
