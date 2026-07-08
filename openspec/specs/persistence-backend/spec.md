# persistence-backend Specification

## Purpose

Define the configurable persistence backend: a JDBC journal/snapshot store selectable by configuration (PostgreSQL by default, in-memory for tests), explicit JSON event serialization, and a provided database schema.

## Requirements

### Requirement: Configurable database backend

The persistence backend SHALL be selectable by configuration. PostgreSQL (via `pekko-persistence-jdbc`) SHALL be the default profile, with connection settings (host, port, database, user, password) read from `application.conf` and overridable by `HERMESMQ_DB_*` environment variables. A separate in-memory profile SHALL be available for tests without a database.

#### Scenario: PostgreSQL is the default and honors env overrides
- **GIVEN** the default configuration with `HERMESMQ_DB_HOST` and `HERMESMQ_DB_PORT` set
- **WHEN** the persistence configuration is loaded
- **THEN** the JDBC journal targets PostgreSQL at the overridden host and port

#### Scenario: In-memory profile requires no database
- **GIVEN** the in-memory persistence profile is selected
- **WHEN** an aggregate persists and recovers events
- **THEN** it works without any external database connection (used by unit tests)

#### Scenario: Edge case — an unreachable database surfaces a clear failure
- **GIVEN** the PostgreSQL profile pointed at an unreachable host
- **WHEN** the service starts or an aggregate attempts its first persist
- **THEN** the failure is logged clearly and the service does not silently accept-and-lose writes (readiness reports not-ready; see health-endpoint)

#### Scenario: Edge case — missing required DB setting fails fast
- **GIVEN** the PostgreSQL profile with no database name configured
- **WHEN** persistence configuration is loaded
- **THEN** startup aborts with a clear configuration error rather than connecting to a wrong default

### Requirement: Explicit event serialization

Domain events SHALL be serialized with an explicit, registered serializer (JSON) — never Java serialization — so journaled events are portable and replayable.

#### Scenario: Domain events round-trip through the serializer
- **GIVEN** a registered serializer for the domain events
- **WHEN** a `MessagePublished` (and other events) is serialized and deserialized
- **THEN** the result equals the original event

#### Scenario: Edge case — Java serialization is disabled
- **GIVEN** the persistence configuration
- **WHEN** an event without an explicit binding would be serialized
- **THEN** serialization fails fast rather than silently falling back to Java serialization

### Requirement: Database schema provided

The project SHALL provide the `pekko-persistence-jdbc` schema (journal and snapshot tables) for PostgreSQL and document how to apply it.

#### Scenario: Schema DDL is available and creates the journal tables
- **GIVEN** a PostgreSQL database
- **WHEN** the provided schema DDL is applied
- **THEN** the journal and snapshot tables required by the JDBC plugin exist

#### Scenario: Edge case — running against a database without the schema fails clearly
- **GIVEN** a PostgreSQL database missing the journal tables
- **WHEN** an aggregate attempts to persist
- **THEN** the error clearly indicates the missing schema rather than a generic failure
