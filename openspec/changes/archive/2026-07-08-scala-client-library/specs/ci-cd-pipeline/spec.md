## MODIFIED Requirements

### Requirement: Release automation from main

A release workflow SHALL trigger on `vX.Y.Z` tags, build and verify all modules, publish the versioned `domain` and `client` **library artifacts** to the package registry, publish the `server` Docker image to Docker Hub (version + `latest`), and create a corresponding GitHub Release. Library and image publishing SHALL be gated behind a passing test run.

#### Scenario: Tagged release publishes library artifacts, image, and GitHub Release
- **GIVEN** an annotated tag `v1.4.0` is pushed to a commit on `main`
- **WHEN** the release workflow runs
- **THEN** the test suite passes, the `1.4.0` `domain` and `client` library artifacts are published to the registry, the `calvinference/hermesmq:1.4.0` and `:latest` server images are pushed to Docker Hub, and a GitHub Release for `v1.4.0` is created

#### Scenario: Failing tests abort the release before publishing
- **GIVEN** a release tag on a commit whose tests fail
- **WHEN** the release workflow runs
- **THEN** library and image publishing are skipped, nothing is pushed to any registry, and the workflow reports failure

#### Scenario: Edge case — publish credentials missing
- **GIVEN** the package registry or Docker Hub authentication secret is absent or invalid
- **WHEN** the release workflow reaches the corresponding publish step
- **THEN** the workflow fails at that step with an authentication error and does not leave a GitHub Release without its published artifacts
