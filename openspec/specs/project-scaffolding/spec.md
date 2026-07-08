# project-scaffolding Specification

## Purpose

Define the buildable sbt/Scala project skeleton: a clean-compiling build with a pinned Scala version and JDK, a conventional project layout with ignore rules, and a Pekko baseline dependency proven by a smoke test.

## Requirements

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

### Requirement: Standard project layout and ignore rules

The project SHALL follow the conventional sbt/Scala directory layout (`src/main/scala`, `src/test/scala`, `project/`) and SHALL exclude build output and local tooling artifacts from version control via `.gitignore`.

#### Scenario: Sources are discovered by convention
- **GIVEN** a Scala source placed under `src/main/scala` and a test under `src/test/scala`
- **WHEN** the build compiles and tests
- **THEN** both files are picked up automatically without explicit path configuration

#### Scenario: Build artifacts are not tracked
- **GIVEN** a completed build that produced `target/` output
- **WHEN** the working tree status is inspected
- **THEN** `target/`, `.bsp/`, and IDE metadata are ignored and do not appear as untracked changes

#### Scenario: Edge case — secrets and env files never enter version control
- **GIVEN** a developer creates a local `.env` or credentials file in the working tree
- **WHEN** they stage all changes
- **THEN** the ignore rules prevent the secret file from being staged or committed

### Requirement: Pekko baseline dependency and smoke test

The project SHALL declare the Pekko actor dependency and include a smoke test that constructs an actor system (or typed behavior) and asserts basic operation, proving the runtime is wired correctly.

#### Scenario: Actor system smoke test passes
- **GIVEN** the Pekko actor and testkit dependencies are declared
- **WHEN** the smoke test spins up a minimal actor and sends it a message
- **THEN** the actor responds as expected and the test asserts success

#### Scenario: Edge case — actor system shuts down without leaking threads
- **GIVEN** the smoke test has started an actor system
- **WHEN** the test completes
- **THEN** the actor system is terminated in teardown so no lingering dispatcher threads keep the JVM alive
