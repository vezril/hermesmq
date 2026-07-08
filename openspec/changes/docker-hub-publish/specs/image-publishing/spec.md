## ADDED Requirements

### Requirement: Release image published to Docker Hub

On a `vX.Y.Z` release, the pipeline SHALL build the Docker image and push it to Docker Hub under `docker.io/vezril/hermesmq`, tagged with the exact release version `X.Y.Z` and also `latest`, authenticating with the `DOCKER_USERNAME` / `DOCKER_TOKEN` secrets.

#### Scenario: Release pushes versioned and latest tags
- **GIVEN** an annotated tag `v1.4.0` is pushed and the release workflow's tests pass
- **WHEN** the image publish step runs
- **THEN** `vezril/hermesmq:1.4.0` and `vezril/hermesmq:latest` are pushed to Docker Hub and are pullable

#### Scenario: Image publish is gated behind a green build
- **GIVEN** a release tag on a commit whose tests fail
- **WHEN** the release workflow runs
- **THEN** no image is pushed to Docker Hub (the publish step never runs)

#### Scenario: Edge case — missing Docker Hub credentials fail the publish
- **GIVEN** the `DOCKER_USERNAME` or `DOCKER_TOKEN` secret is absent or invalid
- **WHEN** the image publish step runs
- **THEN** the docker login/push fails with an authentication error and the workflow reports failure rather than silently skipping the push

#### Scenario: Edge case — image tag matches the release version exactly
- **GIVEN** a release `v1.4.0`
- **WHEN** the image is published
- **THEN** the version tag is exactly `1.4.0` (no `+`/build-metadata characters), so the tag is a valid, predictable Docker reference

### Requirement: Development snapshot image published

On a push to `development`, the pipeline SHALL build and push a snapshot-tagged image to Docker Hub (the dynver-derived version with `+` sanitized to `-`), and SHALL NOT move the `latest` tag.

#### Scenario: Development push publishes a snapshot image without latest
- **GIVEN** a commit pushed to `development` with passing tests
- **WHEN** the image publish step runs
- **THEN** a uniquely snapshot-tagged `vezril/hermesmq` image is pushed and `latest` is unchanged

#### Scenario: Edge case — pull requests do not publish images
- **GIVEN** a pull request build (not a push to `development` or a release tag)
- **WHEN** CI runs
- **THEN** no image is pushed to Docker Hub (publishing is restricted to `development` pushes and release tags)
