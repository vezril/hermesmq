# ci-cd-pipeline Specification

## Purpose

Define the GitHub Actions CI/CD pipeline: continuous integration on pull requests and protected branches, Git-tag-driven semantic versioning, automated releases from `main`, and the branch and contribution strategy that governs how work flows to release.

## Requirements

### Requirement: Continuous integration on pull requests and protected branches

The CI pipeline SHALL run via GitHub Actions on every pull request and on pushes to `development` and `main`, compiling the project and executing the full test suite, and SHALL surface a pass/fail status usable as a required merge check.

#### Scenario: CI passes on a green pull request
- **GIVEN** a pull request whose branch compiles and whose tests pass
- **WHEN** the CI workflow runs
- **THEN** compile and test steps complete successfully and the check reports success

#### Scenario: CI blocks a red pull request
- **GIVEN** a pull request that introduces a failing test
- **WHEN** the CI workflow runs
- **THEN** the workflow exits non-zero and the required check is marked failed, preventing merge under branch protection

#### Scenario: Edge case — compilation failure fails CI before tests
- **GIVEN** a pull request that does not compile
- **WHEN** the CI workflow runs
- **THEN** the compile step fails, the test step is skipped, and the overall check is marked failed with the compiler error visible in the logs

#### Scenario: Edge case — dependency cache miss still yields correct result
- **GIVEN** a CI run with a cold or invalidated dependency cache
- **WHEN** the workflow runs
- **THEN** dependencies are resolved from scratch and the pass/fail outcome is identical to a warm-cache run (caching affects speed only, never correctness)

### Requirement: Semantic versioning driven by Git tags

Releases SHALL follow Semantic Versioning 2.0.0 (`MAJOR.MINOR.PATCH`), with the published artifact version derived from an annotated `vX.Y.Z` Git tag. Builds not on a release tag SHALL carry a pre-release / snapshot version.

#### Scenario: Tag determines release version
- **GIVEN** the commit on `main` is tagged `v1.4.0`
- **WHEN** the release workflow builds the artifact
- **THEN** the produced package is versioned `1.4.0`

#### Scenario: Untagged development build is a snapshot
- **GIVEN** a build on `development` with no release tag at HEAD
- **WHEN** the build computes its version
- **THEN** the version is a pre-release/snapshot identifier (e.g. `1.4.0-SNAPSHOT` or a commit-derived pre-release) distinct from any stable release

#### Scenario: Development push auto-publishes a snapshot package
- **GIVEN** a commit is pushed to `development` with no release tag at HEAD
- **WHEN** the CI workflow runs and tests pass
- **THEN** a uniquely-versioned snapshot package is published to GitHub Packages, and because each commit derives a distinct version it does not collide with any previously published snapshot

#### Scenario: Edge case — malformed tag is rejected
- **GIVEN** a tag that does not match the `vMAJOR.MINOR.PATCH` pattern (e.g. `v1.2` or `release-1`)
- **WHEN** it is pushed
- **THEN** the release workflow does not treat it as a release, or fails with a clear versioning error, rather than publishing an artifact with an invalid version

#### Scenario: Edge case — re-tagging an existing version does not overwrite a published release
- **GIVEN** version `1.4.0` has already been published
- **WHEN** a tag `v1.4.0` is pushed again
- **THEN** the release workflow refuses to overwrite the existing published artifact and reports the conflict

### Requirement: Release automation from main

A release workflow SHALL trigger on `vX.Y.Z` tags, build and verify the project, publish the versioned package to the configured registry, publish the Docker image to Docker Hub (version + `latest`), and create a corresponding GitHub Release. Image and package publishing SHALL be gated behind a passing test run.

#### Scenario: Tagged release publishes package, image, and GitHub Release
- **GIVEN** an annotated tag `v1.4.0` is pushed to a commit on `main`
- **WHEN** the release workflow runs
- **THEN** the test suite passes, the `1.4.0` package is published to the registry, the `calvinference/hermesmq:1.4.0` and `:latest` images are pushed to Docker Hub, and a GitHub Release for `v1.4.0` is created

#### Scenario: Failing tests abort the release before publishing
- **GIVEN** a release tag on a commit whose tests fail
- **WHEN** the release workflow runs
- **THEN** package and image publishing are skipped, nothing is pushed to any registry, and the workflow reports failure

#### Scenario: Edge case — publish credentials missing
- **GIVEN** the package registry or Docker Hub authentication secret is absent or invalid
- **WHEN** the release workflow reaches the corresponding publish step
- **THEN** the workflow fails at that step with an authentication error and does not leave a GitHub Release without its published artifacts

### Requirement: Branch and contribution strategy

The repository SHALL use `main` as the stable release line, `development` as the integration line for experimental builds, and short-lived `feature/*` branches merged into `development` via pull request; this strategy SHALL be documented in the README.

#### Scenario: Feature work flows through development
- **GIVEN** a developer starts new work
- **WHEN** they branch from `development` as `feature/<topic>` and open a pull request back into `development`
- **THEN** CI runs on the pull request and merge is allowed only after the required check passes

#### Scenario: Releases are promoted from main
- **GIVEN** `development` holds changes ready to ship
- **WHEN** those changes are merged to `main` and a `vX.Y.Z` tag is pushed
- **THEN** the release workflow produces the stable published package

#### Scenario: Edge case — direct push to a protected branch is rejected
- **GIVEN** branch protection is configured on `main` and `development`
- **WHEN** a contributor attempts to push commits directly to `main` bypassing a pull request
- **THEN** the push is rejected and the change must go through a reviewed, CI-checked pull request
