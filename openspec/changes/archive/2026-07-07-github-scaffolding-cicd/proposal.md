## Why

HermesMQ currently has no build tooling, repository conventions, or automated pipeline — there is nowhere to run tests or produce a shippable artifact, which blocks every subsequent feature. Establishing the project skeleton and CI/CD first gives us a red/green test loop and a reproducible release path before any broker logic is written, which is a hard prerequisite for the mandated TDD workflow.

## What Changes

- Initialize a Git repository and an sbt-based Scala project skeleton (build definition, directory layout, `.gitignore`, base dependencies for Pekko) that compiles and runs tests out of the box.
- Adopt and document a branch + versioning strategy:
  - `main` — stable release line; annotated `vX.Y.Z` tags cut releases and publish the main package.
  - `development` — integration line for the latest dev changes; produces experimental (pre-release / `-SNAPSHOT`) builds.
  - Short-lived `feature/*` branches merge into `development` via pull request.
  - Versioning follows [Semantic Versioning 2.0.0](https://semver.org/) (`MAJOR.MINOR.PATCH`), with releases driven by Git tags.
- Add GitHub Actions workflows:
  - **CI** — on pull requests and pushes to `development`/`main`: compile, run the full test suite, and report status (required check for merges).
  - **Release** — on `vX.Y.Z` tags pushed to `main`: build, verify, and publish the versioned package plus a GitHub Release.
- Add a root `README.md` documenting how to build, run, and test the project and the branching/versioning conventions.
- Refine the spec: the requested `main = latest major release` / `development = latest dev` model is kept, formalized as tag-driven releases from `main` and experimental builds from `development`.

## Capabilities

### New Capabilities
- `project-scaffolding`: The buildable Scala/sbt project skeleton — directory layout, build definition, base Pekko dependencies, ignore rules, and a passing smoke test — that establishes the TDD-ready foundation.
- `ci-cd-pipeline`: The GitHub Actions continuous-integration and release automation, the semantic-versioning scheme, and the `main`/`development`/`feature` branch strategy that governs how builds are verified and shipped.

### Modified Capabilities
<!-- None — this is the first change; no existing specs. -->

## Impact

- **New files/systems**: Git repository, `build.sbt` + `project/` sbt config, `src/main` & `src/test` layout, `.github/workflows/*.yml`, `.gitignore`, `README.md`.
- **Dependencies**: sbt, a JDK (Temurin 21), Scala, Pekko (actor + testkit for the smoke test), a test framework (ScalaTest/MUnit).
- **External systems**: GitHub repository settings (branch protection on `main`/`development`, required CI check), GitHub Actions, and a package registry (GitHub Packages) for release publishing.
- **Downstream**: All later features (Pekko service, commands/events, persistence) build on this skeleton and are gated by this CI pipeline.
