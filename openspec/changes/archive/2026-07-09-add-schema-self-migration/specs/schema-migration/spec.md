## ADDED Requirements

### Requirement: Apply the bundled schema on startup

The service SHALL apply the bundled PostgreSQL schema (`schema/postgres.sql`) against the configured database at startup, before it starts any projection or binds any endpoint, so the journal, snapshot, projection, and read-model tables always exist before they are used. The apply SHALL be idempotent — re-applying it against a database that already has the schema SHALL succeed as a no-op — and SHALL run the whole script atomically so a partial failure leaves no half-created schema.

#### Scenario: A fresh database is provisioned before the service serves
- **GIVEN** a reachable but empty PostgreSQL database and `migrate-on-start` enabled
- **WHEN** the service starts
- **THEN** the schema is applied and all required tables exist before any projection runs or readiness reports ready

#### Scenario: Re-applying over an existing schema is a no-op
- **GIVEN** a database that already has the schema (e.g. provisioned by a prior boot or initdb)
- **WHEN** the service starts and applies the schema again
- **THEN** startup succeeds without error and the existing tables and data are unchanged

#### Scenario: Edge case — a failing apply aborts startup rather than serving on a partial schema
- **WHEN** applying the schema raises a database error partway through
- **THEN** the whole apply is rolled back, startup fails with a non-zero exit and a clear error, and no endpoint is bound

### Requirement: Wait for the database with a bounded retry

Startup SHALL retry connecting to the database at a fixed interval until it becomes reachable or `hermesmq.db.migrate-max-wait` elapses, applying the schema once connected. If the database does not become reachable within that window, startup SHALL fail fast with a clear error rather than hanging indefinitely or crash-looping immediately.

#### Scenario: A briefly-unready database is tolerated
- **GIVEN** a database that becomes reachable a few seconds after the service starts, within `migrate-max-wait`
- **WHEN** the service starts
- **THEN** it waits, connects, applies the schema, and proceeds to serve

#### Scenario: Edge case — an unreachable database fails fast at the limit
- **GIVEN** a database that stays unreachable
- **WHEN** `migrate-max-wait` elapses
- **THEN** startup exits non-zero with an error identifying the database connection failure, not a generic one

### Requirement: Self-migration is opt-out via configuration

The broker SHALL read `hermesmq.db.migrate-on-start` (default `true`) to decide whether to self-apply the schema, and `hermesmq.db.migrate-max-wait` (a duration) for the wait-for-database window; a negative `migrate-max-wait` SHALL fail fast at configuration load. When `migrate-on-start` is `false`, the service SHALL NOT apply the schema and SHALL rely on externally-provisioned tables.

#### Scenario: Enabled by default
- **GIVEN** no `migrate-on-start` override
- **WHEN** configuration is loaded
- **THEN** self-migration is enabled

#### Scenario: Disabled skips the apply
- **GIVEN** `hermesmq.db.migrate-on-start = false`
- **WHEN** the service starts against a database
- **THEN** it does not apply the schema and starts assuming the tables already exist

#### Scenario: Edge case — a negative wait window fails fast
- **GIVEN** `hermesmq.db.migrate-max-wait` set to a negative duration
- **WHEN** configuration is loaded
- **THEN** startup fails with a clear configuration error rather than starting misconfigured
