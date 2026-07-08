## MODIFIED Requirements

### Requirement: Buildable sbt project skeleton

The project SHALL provide a multi-module sbt build — a `domain` module (pure value types and aggregates, no IO dependencies), a `server` module (the service, which owns the app/Docker packaging), and a `client` module (the client library) — that compiles cleanly and runs its test suite across all modules from a clean checkout without additional manual setup, using a pinned Scala version and JDK (Temurin 21). The `server` and `client` modules SHALL depend on `domain`.

#### Scenario: Clean compile from fresh checkout
- **GIVEN** a fresh clone of the repository with sbt and JDK 21 installed
- **WHEN** a developer runs `sbt compile`
- **THEN** all modules resolve their declared dependencies and compile with no errors

#### Scenario: Test suite runs green across modules
- **GIVEN** a fresh clone of the repository
- **WHEN** a developer runs `sbt test`
- **THEN** the tests for every module execute and the suite reports success (exit code 0)

#### Scenario: Modules share the domain without cyclic or server leakage
- **GIVEN** the module dependency graph
- **WHEN** it is inspected
- **THEN** `server` and `client` both depend on `domain`, `client` does not depend on `server`, and there are no cyclic dependencies

#### Scenario: Edge case — build fails fast on unresolvable dependency
- **GIVEN** a build configuration referencing a dependency coordinate that cannot be resolved
- **WHEN** `sbt compile` is run
- **THEN** the build terminates with a non-zero exit code and a resolution error naming the missing coordinate, rather than hanging or silently skipping it

#### Scenario: Edge case — incompatible JDK is rejected
- **GIVEN** an environment running a JDK older than the pinned minimum (e.g. JDK 11)
- **WHEN** the build is invoked
- **THEN** the build fails with a clear message indicating the required JDK version rather than producing bytecode for the wrong target
