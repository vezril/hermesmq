## MODIFIED Requirements

### Requirement: Database schema provided

The project SHALL provide the `pekko-persistence-jdbc` schema (journal and snapshot tables) for PostgreSQL and, by default, SHALL apply it automatically on startup (see the `schema-migration` capability) so a fresh database needs no external init step. The project SHALL also document how to apply the schema manually for operators who disable self-migration (`hermesmq.db.migrate-on-start = false`).

#### Scenario: Schema DDL is available and creates the journal tables
- **GIVEN** a PostgreSQL database
- **WHEN** the provided schema DDL is applied
- **THEN** the journal and snapshot tables required by the JDBC plugin exist

#### Scenario: A fresh database is provisioned automatically on startup
- **GIVEN** an empty PostgreSQL database and default configuration
- **WHEN** the service starts
- **THEN** it applies the provided schema itself and the journal, snapshot, projection, and read-model tables exist before it serves

#### Scenario: Edge case — with self-migration disabled, a missing schema fails clearly
- **GIVEN** `hermesmq.db.migrate-on-start = false` and a PostgreSQL database missing the journal tables
- **WHEN** an aggregate attempts to persist
- **THEN** the error clearly indicates the missing schema rather than a generic failure
