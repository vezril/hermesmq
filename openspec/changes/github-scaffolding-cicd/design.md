## Context

HermesMQ is a greenfield single-node message broker to be built in Scala on Pekko. There is currently no Git repository, no build tooling, and no CI. TDD is a non-negotiable constraint for the project, so the first change must deliver a working red/green loop and a reproducible release path before any domain logic exists. Feature 1 is intentionally scoped to scaffolding and CI/CD only; the actor system introduced here is a smoke test, not the broker.

The initial spec proposed `main = latest major release` and `development = latest dev changes`, and explicitly invited a refined strategy. This design formalizes that into a tag-driven release model.

## Goals / Non-Goals

**Goals:**
- A clone-and-run sbt project (`sbt compile`, `sbt test`) with a passing Pekko smoke test.
- GitHub Actions CI that gates pull requests on compile + test.
- A tag-driven Semantic Versioning release workflow that publishes a versioned package and a GitHub Release.
- Documented `main` / `development` / `feature/*` branch strategy with branch protection.
- A README covering build, run, test, and contribution flow.

**Non-Goals:**
- Any broker functionality (topics, subscriptions, persistence, gRPC) — later features.
- Multi-module sbt structure, Docker packaging, or a health endpoint — deferred to Feature 2.
- Cross-platform release matrices or publishing to Maven Central — GitHub Packages is sufficient for now.
- Automated version bumping / conventional-commit release bots — versioning is explicit via Git tags in this iteration.

## Decisions

**Build tool: sbt (single module).**
sbt is the idiomatic Scala build tool and the natural fit for Pekko. A single-module layout keeps Feature 1 minimal; the module can later be split (core / grpc / persistence) without churn. *Alternative considered:* Mill — leaner but less ubiquitous; sbt has broader team/CI familiarity and first-class GitHub Actions support.

**Language/runtime pins: Scala 3 (latest stable in the 3.3 LTS line) on Temurin JDK 21.**
Scala 3 for modern FP ergonomics (given/using, enums, opaque types) which the constraints favor. JDK 21 LTS for long-term support. Versions are pinned in `build.sbt` and `.github` workflows to keep local and CI environments identical. *Alternative:* Scala 2.13 — more third-party examples, but Scala 3 is the better long-term bet for a new codebase.

**Test framework: ScalaTest + pekko-actor-testkit-typed.**
ScalaTest's `AnyWordSpec`/`AnyFunSuite` styles read well for Given/When/Then scenarios that mirror the spec. The Pekko typed testkit gives a `BehaviorTestKit`/`ActorTestKit` for the smoke test. *Alternative:* MUnit — lighter, but ScalaTest integrates more smoothly with the Pekko testkit examples.

**Branch strategy: GitFlow-lite.**
`feature/*` → PR → `development` (integration, experimental snapshot builds) → merge to `main` → tag `vX.Y.Z` → release. This honors the requested `main`/`development` semantics while adding reviewed, CI-gated feature branches. Branch protection on `main` and `development` requires the CI check and forbids direct pushes. *Alternative:* full GitFlow (release/hotfix branches) — heavier than a single-node early-stage project warrants; trunk-based on a single branch — loses the requested experimental `development` line.

**Versioning: explicit Git tags via sbt-dynver.**
`sbt-dynver` derives the artifact version from the nearest annotated `vX.Y.Z` tag: on a tag it yields the clean `X.Y.Z`; off a tag it yields a commit/distance-derived pre-release. This satisfies the "tag determines version, otherwise snapshot" requirement with no manual version file to drift. *Alternative:* hand-maintained `version in build.sbt` — simple but error-prone and easy to forget to bump; semantic-release-style bots — more automation than needed now.

**CI/CD: two GitHub Actions workflows.**
- `ci.yml` — triggers on `pull_request` and `push` to `development`/`main`; steps: checkout, setup-java (Temurin 21) with sbt dependency caching, `sbt compile`, `sbt test`. Serves as the required status check.
- `release.yml` — triggers on `push` of tags matching `v*.*.*`; steps: checkout, setup-java, run tests (gate), `sbt publish` to GitHub Packages using `GITHUB_TOKEN`, then create a GitHub Release. Tag-pattern filtering plus dynver rejects malformed tags implicitly (a non-`v*.*.*` tag never triggers release).

**Registry: GitHub Packages.**
Zero extra account setup, authenticates with the built-in `GITHUB_TOKEN`, and lives beside the repo. *Alternative:* Sonatype/Maven Central — appropriate for public libraries but requires signing keys and account provisioning that are out of scope for now.

## Risks / Trade-offs

- **Re-publishing an existing version could overwrite a release** → GitHub Packages rejects overwriting an existing immutable version; the release workflow surfaces that failure rather than masking it, satisfying the "no silent overwrite" scenario.
- **Malformed or partial tags (`v1.2`, `release-1`)** → the `v*.*.*` trigger pattern means such tags never start a release; dynver additionally will not derive a clean version from them.
- **Missing/invalid publish credentials** → the publish step fails loudly with an auth error; because tests run before publish and Release creation is the last step, a failed publish leaves no GitHub Release without a package.
- **Cold dependency cache slows CI** → caching is keyed on build files and affects speed only; a cache miss re-resolves dependencies and produces an identical pass/fail result.
- **Scala 3 + Pekko version skew** → pin compatible, released versions in `build.sbt` and let CI catch resolution failures fast (fail-fast on unresolvable dependency is itself a tested scenario).
- **Branch protection is configured in GitHub settings, not in code** → document the required settings in the README so the repository can be reproduced; note these are applied manually via repo admin.

## Migration Plan

Greenfield — no existing system to migrate. Rollout order:
1. `git init`, create the sbt skeleton + smoke test, verify `sbt test` green locally.
2. Commit to `main`, branch `development` from it, push both to GitHub.
3. Add `ci.yml`, open a PR to confirm CI runs and gates.
4. Add `release.yml`; cut an initial `v0.1.0` tag to validate the publish + Release path.
5. Apply branch protection on `main`/`development` (require CI check, disallow direct pushes).

Rollback: workflows and skeleton are additive files; reverting the commits restores the prior (empty) state with no external side effects beyond any test package published under `v0.1.0`, which can be deleted from GitHub Packages.

## Resolved Questions

- **GitHub owner / package namespace**: `cference` (personal account owns the repo). Release and snapshot packages publish to GitHub Packages at `https://maven.pkg.github.com/cference/hermesmq`; the workflow derives the owner dynamically from `github.repository_owner` so it stays correct if forked/renamed.
- **`organization` / `groupId`**: `me.cference.hermesmq` in `build.sbt`.
- **`development` publishing**: `development` **auto-publishes** snapshot packages on every push. Because `sbt-dynver` yields a unique commit-derived version off-tag, each snapshot has a distinct version and does not collide with immutable GitHub Packages entries. Release tags on `main` publish the clean `X.Y.Z`.
