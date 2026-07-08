## 1. Repository & sbt skeleton

- [x] 1.1 Run `git init`; add `.gitignore` covering `target/`, `.bsp/`, `project/project/`, `project/target/`, IDE metadata (`.idea/`, `.metals/`, `.bloop/`), and `*.env`/credential files
- [x] 1.2 Create `project/build.properties` pinning the sbt version
- [x] 1.3 Create `build.sbt` with `organization`, Scala 3 (3.3 LTS), Temurin JDK 21 target, and empty dependency stubs so `sbt compile` succeeds on an empty project
- [x] 1.4 Add `project/plugins.sbt` declaring `sbt-dynver`
- [x] 1.5 Verify `sbt compile` and `sbt test` run green on the empty skeleton (baseline)

## 2. Pekko smoke test — TDD (Red → Green → Refactor)

- [x] 2.1 RED: write a failing smoke test under `src/test/scala` that starts a typed actor via `ActorTestKit`, sends it a message, and asserts the reply
- [x] 2.2 Run `sbt test` and confirm it fails (test compiles-and-fails or fails to compile against the missing dependency/behavior) — capture the red
- [x] 2.3 GREEN: add `pekko-actor-typed` and `pekko-actor-testkit-typed` (test scope) to `build.sbt`; implement the minimal typed behavior under `src/main/scala` to make the test pass
- [x] 2.4 Run `sbt test` and confirm the smoke test passes (green)
- [x] 2.5 Add teardown so the `ActorTestKit`/actor system is shut down after the test (edge case: no leaked dispatcher threads); re-run `sbt test` green
- [x] 2.6 REFACTOR: tidy naming, package structure (`io.hermesmq`), and remove duplication; re-run `sbt test` to confirm still green

## 3. Edge-case coverage for the build

- [x] 3.1 Add/verify a check for the unresolvable-dependency edge case (documented reproduction: a bad coordinate yields a non-zero `sbt compile` with a resolution error) — record expected behavior in README or a build note
- [x] 3.2 Confirm sources are discovered by convention (a source in `src/main/scala` and test in `src/test/scala` are compiled without extra config) — assert via a second trivial passing test
- [x] 3.3 Verify `.gitignore` prevents staging of `target/` and a sample `.env` (git status shows them ignored)

> **Note:** The repo is live at github.com/vezril/hermesmq. All CI, publishing,
> branch-protection, and release tasks were verified against real GitHub Actions
> runs. The two exceptions are 5.5 and 5.7 (release-abort-on-red, missing-creds /
> re-tag): these are left unchecked because triggering them live would push
> deliberately-broken releases / duplicate publishes onto the public repo. They
> are verified by construction — the release workflow runs Test *before* Publish
> (proven in the successful v0.1.0 run), `sbt test` returns non-zero on a red
> commit (proven locally), and GitHub Packages versions are immutable so a
> re-published version is rejected by the platform.

## 4. Continuous integration workflow

- [x] 4.1 Add `.github/workflows/ci.yml` triggering on `pull_request` and `push` to `development` and `main`
- [x] 4.2 Configure steps: checkout, `actions/setup-java` (Temurin 21) with sbt dependency caching, `sbt compile`, `sbt test`
- [x] 4.3 Verify a green branch produces a passing CI check (push branch / open PR)
- [x] 4.4 Verify the red-PR edge case: a branch with a failing test produces a failing, merge-blocking check
- [x] 4.5 Verify the compile-failure edge case: a non-compiling branch fails at compile, skips tests, and reports failure
- [x] 4.6 On `push` to `development`, add a publish step that publishes the dynver-derived snapshot package to GitHub Packages (owner from `github.repository_owner`, auth via `GITHUB_TOKEN`) after tests pass

## 5. Versioning & release workflow

- [x] 5.1 Confirm `sbt-dynver` derives `X.Y.Z` on a `vX.Y.Z` tag and a pre-release/snapshot version off-tag (`sbt version` on a tagged vs untagged commit)
- [x] 5.2 Add `.github/workflows/release.yml` triggering on tags matching `v*.*.*`
- [x] 5.3 Configure release steps: checkout (full history for dynver), setup-java, run tests as a gate, `sbt publish` to GitHub Packages using `GITHUB_TOKEN`, then create a GitHub Release
- [x] 5.4 Validate the happy path: push `v0.1.0` on `main` → tests pass → `0.1.0` package published → GitHub Release created
- [ ] 5.5 Verify the failing-tests edge case: a release tag on a red commit aborts before publish (no artifact pushed)
- [x] 5.6 Verify the malformed-tag edge case: a tag like `v1.2` / `release-1` does not trigger a release
- [ ] 5.7 Verify the missing-credentials and re-tag edge cases: absent token fails at publish with an auth error; re-pushing `v0.1.0` does not overwrite the published package

## 6. Branch strategy, protection & documentation

- [x] 6.1 Create the `development` branch from `main` and push both to GitHub
- [x] 6.2 Configure branch protection on `main` and `development`: require the CI status check, require PRs, disallow direct pushes
- [x] 6.3 Verify the direct-push edge case: a direct push to a protected branch is rejected
- [x] 6.4 Write root `README.md`: prerequisites (JDK 21, sbt), how to build (`sbt compile`), how to run tests (`sbt test`), the `main`/`development`/`feature/*` branch model, and the tag-driven SemVer release process
- [x] 6.5 Document the required GitHub repository settings (branch protection, package registry) in the README so the setup is reproducible

## 7. Final verification

- [x] 7.1 Fresh-clone check: from a clean clone, `sbt compile` and `sbt test` both succeed with no manual setup
- [x] 7.2 Confirm all spec scenarios in `project-scaffolding` and `ci-cd-pipeline` have a corresponding verified task, then run `openspec validate` for the change
