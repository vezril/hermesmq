## 1. Migration configuration

- [x] 1.1 Add failing `DbConfigSpec` cases: `migrate-on-start` defaults to `true` and is overridable; `migrate-max-wait` parses a duration and a negative value fails fast
- [x] 1.2 Extend `DbConfig` with `migrateOnStart: Boolean` and `migrateMaxWait: FiniteDuration`, read from `hermesmq.db.migrate-on-start` / `hermesmq.db.migrate-max-wait` (fail fast on a negative wait); add both to `application.conf` with `HERMESMQ_DB_*` env overrides; make the tests green

## 2. Schema migrator

- [x] 2.1 Add failing `SchemaMigratorSpec` unit cases: the bundled `/schema/postgres.sql` resource loads as non-empty DDL containing the expected `event_journal` and read-model tables (guards against a missing/renamed resource)
- [x] 2.2 Implement `persistence/SchemaMigrator.scala`: load the classpath resource, wait for database reachability at a fixed interval up to `migrateMaxWait`, then apply the whole script in a single `Statement.execute`; return `Either[MigrationError, Unit]` (reachability timeout and SQL errors as `Left`). Make the unit tests green

## 3. Boot wiring

- [x] 3.1 In `Main`, after the config `Right(...)` match and before `Behaviors.setup`, run `SchemaMigrator` when `dbConfig.migrateOnStart` is true; on `Left`, print the error and `sys.exit(1)` (mirroring the config-error path); skip cleanly when disabled
- [x] 3.2 Confirm the full existing suite still compiles and passes (`sbt test`) with the boot change in place (no regressions; migration is inert in tests, which use the in-memory profile)

## 4. Integration (Postgres)

- [x] 4.1 Add a Postgres integration case (tagged `PostgresIT`, opt-in) that runs `SchemaMigrator` against a fresh container database with **no** init script, then asserts the journal + read-model tables exist and that a second `migrate` run succeeds as a no-op (idempotent)
- [x] 4.2 Add a Postgres integration case asserting a topic entity can create + publish end-to-end against a database provisioned only by `SchemaMigrator` (no initdb)

## 5. Documentation & deployment

- [x] 5.1 Document self-migration in the README: default-on behaviour, `HERMESMQ_DB_MIGRATE_ON_START` / `HERMESMQ_DB_MIGRATE_MAX_WAIT`, the opt-out for externally-managed migrations, and that k3s/`pg-service` needs no schema ConfigMap
- [x] 5.2 Note in the README/compose docs that the docker-compose `docker-entrypoint-initdb.d` schema mount is now redundant (self-migration provisions a fresh DB); leave the mount in place as a harmless belt-and-suspenders
