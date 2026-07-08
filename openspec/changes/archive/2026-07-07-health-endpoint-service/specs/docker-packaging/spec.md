## ADDED Requirements

### Requirement: Buildable Docker image

The project SHALL produce a runnable Docker image of the service via `sbt-native-packager` (e.g. `sbt Docker/publishLocal`), based on a slim JRE base image, without requiring a hand-maintained Dockerfile.

#### Scenario: Image builds locally
- **GIVEN** a working checkout with Docker available
- **WHEN** `sbt Docker/publishLocal` is run
- **THEN** a tagged `hermesmq` image is created whose version matches the sbt-dynver project version

#### Scenario: Edge case — image tag tracks the project version
- **GIVEN** the project version is a dynver snapshot (off-tag) or a clean release (on `vX.Y.Z`)
- **WHEN** the image is built
- **THEN** the image tag reflects that exact version, so snapshots and releases produce distinctly tagged images

### Requirement: Container runs and serves health

A container started from the image SHALL launch the service and serve the health endpoint on the exposed port, using the container's default configuration.

#### Scenario: Health reachable inside a running container
- **GIVEN** the image has been built
- **WHEN** the container is run with its HTTP port published to the host
- **THEN** `GET /health` against the published port returns `200 OK` with the status body

#### Scenario: Port configurable at container run time
- **GIVEN** the container is started with `HERMESMQ_HTTP_PORT` set to a non-default value and that port exposed/published
- **WHEN** the service starts inside the container
- **THEN** the health endpoint is served on the configured port

#### Scenario: Edge case — container stops cleanly on SIGTERM
- **GIVEN** a running container
- **WHEN** `docker stop` sends SIGTERM
- **THEN** the service shuts down gracefully and the container exits with a clean status within the stop timeout rather than being force-killed

#### Scenario: Edge case — image runs as a non-root user
- **GIVEN** the built image
- **WHEN** its default user is inspected
- **THEN** the service runs as a non-root user, so the container follows least-privilege practice
