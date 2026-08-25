## Context

The build already enforces scalafmt, `-Wunused:all -Werror`, and has scalafix *configured* (DisableSyntax, OrganizeImports) but not CI-gated. CI is two workflows: `ci.yml` (required `Compile & Test` + non-required dev-snapshot publish) and `release.yml` (tag-driven publish + Docker image). This change adds coverage, a scalafix gate, stricter Scala 3 warnings, and security scanning (secrets, deps, image CVEs) — all native/serverless, no dashboards. It is CI/build-config only: no runtime or behavioural change, existing tests unaffected.

## Goals / Non-Goals

**Goals:**
- Measure statement coverage in CI (report, not gated yet).
- Make `scalafixAll --check` a CI gate (SemanticDB enabled).
- Crank the Scala 3 `-W` set under `-Werror`, **incrementally**, fixing findings rather than suppressing.
- Scan for committed secrets (gitleaks), track vulnerable deps (Dependabot), and scan the shipped image for CVEs (Trivy).

**Non-Goals:**
- SonarQube / any self-hosted dashboard (Java-first, RAM-heavy; deferred).
- Scapegoat (shaky Scala 3 support).
- Gating coverage on a floor yet (suite maturity first — `coverageMinimumStmtTotal` is a later flip).
- Blanket `-Wconf` suppression to force the stricter flags green.

## Decisions

**1. scoverage as a separate, ungated CI invocation.**
Add `sbt-scoverage`; CI runs `sbt coverage test coverageReport` as its own step producing a statement-coverage report. Ungated (no `coverageMinimumStmtTotal`) so an immature suite never reds the build; the floor is a one-line flip later. Run it in a *separate* sbt call from the required `Test` step so coverage instrumentation doesn't interact with the gate.

**2. Enable SemanticDB and gate on `scalafixAll --check`.**
Set `ThisBuild / semanticdbEnabled := true` so the semantic rules (OrganizeImports) can run, then a CI step `sbt "scalafixAll --check"` fails on any violation. Any fixes the check demands are applied once up front with `scalafixAll` and committed, so the gate starts green.

**3. Crank `-W` flags one at a time, under `-Werror`, fixing findings.**
Add flags like `-Wvalue-discard` and `-Wnonunit-statement` **incrementally** — enable one, compile, resolve every finding (assign to `val _`, add an explicit `: Unit`, or restructure; never a blanket suppression), then the next. These flags surface real findings in existing code (discarded `Future`s from `runWith(Sink.ignore)`, `MDC.put` return values, mutable-map ops), so this is a genuine mechanical cleanup, done last so the rest of the change lands first. If a flag proves not worth its churn it is dropped with a note, not force-suppressed.

**4. gitleaks as its own CI job scanning history.**
A separate job (not in the required `Compile & Test`, so a scan hiccup can't block the compile gate) runs gitleaks over the repo and its history, failing on a finding. Secrets have never been committed here (they live in GitHub secrets), so it starts clean and guards against regressions.

**5. Dependabot for sbt + github-actions; Trivy on the release image.**
`.github/dependabot.yml` enables the `sbt` and `github-actions` ecosystems (weekly) for update/CVE PRs. In `release.yml`, after the image is built, Trivy scans it for CVEs. To avoid blocking releases on unfixable base-image noise, Trivy runs with `ignore-unfixed: true` and fails on `CRITICAL` (with `HIGH` surfaced) — a defensible default that can be tightened later, mirroring scoverage's "report first, gate later" posture.

## Risks / Trade-offs

- **The `-W` flags surface a large, unknown number of findings.** → Incremental, one flag at a time, each fully resolved before the next; a flag that isn't worth its churn is dropped (documented), never blanket-suppressed. This group is sequenced last so it can't hold up the security/coverage wins.
- **`scalafixAll --check` may demand fixes.** → Apply `scalafixAll` once and commit before adding the gate, so it starts green.
- **Trivy could red releases on base-image CVEs.** → `ignore-unfixed: true` + fail only on `CRITICAL`; the JRE base image is the lever if criticals appear. Tunable.
- **scoverage slows CI and can perturb bytecode.** → Separate sbt invocation from the required `Test` gate; report-only.

## Migration Plan

1. Additive CI/build config; no product change. Land coverage + scalafix gate + security scans first (they're independent and low-risk), then the `-W` cleanup.
2. Rollback: remove the offending CI step/flag; nothing persistent to undo. Coverage/Trivy are report-or-tunable, not hard gates initially.

## Open Questions

- When to flip `coverageMinimumStmtTotal` on (needs a coverage baseline first) and whether to tighten Trivy to fail on `HIGH`. Both deferred — this change establishes the machinery.
