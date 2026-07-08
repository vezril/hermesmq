# HermesMQ

A single-node, event-sourced message broker built in Scala on [Apache Pekko](https://pekko.apache.org/).

> **Status:** early scaffolding. This repository currently contains the build
> tooling, CI/CD pipeline, and a Pekko runtime smoke test. Broker functionality
> (topics, subscriptions, persistence, gRPC) lands in later features.

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| JDK  | Temurin 21 | The CI/release pipeline pins Temurin 21. Newer JDKs work locally. |
| sbt  | 1.10.7 | Pinned in [`project/build.properties`](project/build.properties); the launcher fetches it automatically. |

Scala itself (3.3 LTS) is resolved by sbt — no separate install needed.

## Build

```bash
sbt compile
```

## Run the tests

```bash
sbt test
```

The suite includes a **Pekko smoke test** (`PingPongSpec`) that starts a typed
actor and asserts it replies, proving the runtime is wired correctly, plus a
plain unit test (`AppInfoSpec`). The test kit shuts its actor system down after
the suite, so no dispatcher threads are left running.

## Project layout

```
build.sbt                    # build definition (deps, versioning, publishing)
project/
  build.properties           # pinned sbt version
  plugins.sbt                # sbt-dynver (tag-driven versioning)
src/
  main/scala/me/cference/hermesmq/   # application sources
  test/scala/me/cference/hermesmq/   # tests
.github/workflows/           # CI and release automation
```

## Branching model

We use a **GitFlow-lite** strategy:

| Branch        | Purpose                                                        |
|---------------|---------------------------------------------------------------|
| `main`        | Stable release line. Annotated `vX.Y.Z` tags cut releases.    |
| `development` | Integration line. Every push publishes an experimental snapshot. |
| `feature/*`   | Short-lived work branches, merged into `development` via PR.  |

Flow: branch `feature/<topic>` off `development` → open a PR into `development`
(CI must pass) → merge `development` into `main` when ready to ship → push a
`vX.Y.Z` tag on `main` to release.

Direct pushes to `main` and `development` are disallowed by branch protection
(see [Required GitHub settings](#required-github-settings)); all changes go
through a reviewed, CI-checked pull request.

## Versioning

Versions follow [Semantic Versioning 2.0.0](https://semver.org/) and are derived
from Git tags by [`sbt-dynver`](https://github.com/sbt/sbt-dynver):

- On an annotated tag `vX.Y.Z` → clean release version `X.Y.Z`.
- Off-tag (e.g. on `development`) → a unique commit-derived pre-release version,
  so each snapshot stays distinct in the (immutable) package registry.

Check the current version with `sbt version`.

## CI/CD

Two GitHub Actions workflows drive the pipeline:

- **[`ci.yml`](.github/workflows/ci.yml)** — on every pull request and on pushes
  to `development`/`main`: compile, then run the full test suite. This is the
  required status check for merges. Pushes to `development` additionally publish
  a snapshot package to GitHub Packages.
- **[`release.yml`](.github/workflows/release.yml)** — on `vX.Y.Z` tags: run the
  tests as a gate, publish the versioned package to GitHub Packages, then create
  a GitHub Release. Malformed tags (e.g. `v1.2`, `release-1`) do not match the
  trigger pattern and never start a release.

Packages publish to `https://maven.pkg.github.com/<owner>/hermesmq`, authenticated
with the workflow's built-in `GITHUB_TOKEN`.

### Cutting a release

```bash
git checkout main
git merge development           # promote integrated changes
git tag -a v0.1.0 -m "v0.1.0"   # annotated SemVer tag
git push origin main --follow-tags
```

## Required GitHub settings

These are configured once in the repository settings so the pipeline is
reproducible:

1. **Branch protection** on `main` and `development`:
   - Require the `CI / Compile & Test` status check to pass before merging.
   - Require pull requests before merging (no direct pushes).
2. **GitHub Packages** is used as the Maven registry; no extra secret is needed —
   the workflows use the automatically provided `GITHUB_TOKEN`.

## Build troubleshooting

- **Unresolvable dependency:** if a dependency coordinate in `build.sbt` cannot
  be resolved, `sbt compile` fails fast with a non-zero exit code and a
  resolution error naming the missing coordinate — it does not hang or silently
  skip it. Fix the coordinate/version and re-run.
- **Wrong JDK:** building on a JDK older than the pinned minimum fails with a
  clear version error rather than producing bytecode for the wrong target.
