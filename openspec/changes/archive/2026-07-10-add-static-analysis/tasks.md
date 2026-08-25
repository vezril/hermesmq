## 1. Coverage (scoverage, ungated)

- [x] 1.1 Add `sbt-scoverage` to `project/plugins.sbt`
- [x] 1.2 Add a CI step in `ci.yml` running `sbt coverage test coverageAggregate` (report only, no floor) as a separate invocation from the required `Test` step; confirm it produces a coverage report and stays green

## 2. Scalafix CI gate

- [x] 2.1 Enable `ThisBuild / semanticdbEnabled := true` in `build.sbt`; run `sbt scalafixAll` once and commit any fixes so the tree is clean
- [x] 2.2 Add a `sbt "scalafixAll --check"` step to `ci.yml`; confirm it passes on the clean tree

## 3. Security scanning

- [x] 3.1 Add a `gitleaks` job to `ci.yml` scanning the repo + history (full `fetch-depth: 0`), failing on a finding; confirm it runs clean
- [x] 3.2 Add `.github/dependabot.yml` enabling the `sbt` and `github-actions` ecosystems (weekly)
- [x] 3.3 Add a Trivy image-scan step to `release.yml` after the Docker publish, scanning the built image with `ignore-unfixed: true`, failing on `CRITICAL` (surfacing `HIGH`)

## 4. Stricter Scala 3 warnings (incremental, under -Werror)

- [x] 4.1 Add `-Wvalue-discard` in `build.sbt`; compile the whole build and resolve every finding (`val _ =`, explicit `: Unit`, or restructure — no blanket suppression); tests green
- [x] 4.2 Add `-Wnonunit-statement`; compile and resolve every finding the same way; tests green
- [x] 4.3 Review any other cheap, high-signal `-W` flags; add each only if its findings are resolved cleanly, else drop it with a one-line note in the change

## 5. Verify & docs

- [x] 5.1 Run the full build locally (`sbt clean scalafixAll --check test`) and confirm green with all new flags/gates; open the PR and confirm CI (compile+test, coverage, scalafix, gitleaks) is green
- [x] 5.2 Document the added static-analysis gates in the README (coverage report, scalafix check, stricter warnings, gitleaks/Dependabot/Trivy) so contributors know what CI enforces
