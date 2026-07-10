## Context

Two workflows drive the pipeline: `ci.yml` (the required `Compile & Test` check plus the non-required dev-snapshot publish) and `release.yml` (tag-triggered publish + GitHub Release). Between them they pin `actions/checkout@v4` (×4), `actions/setup-java@v4` (×3), and `docker/login-action@v3` (×2); `sbt/setup-sbt@v1` is not flagged. GitHub's runners force Node-20 actions onto Node 24 and log a deprecation warning on every run, and will eventually drop the Node-20 shim. The pins use floating major tags (e.g. `@v4`), matching the repo's existing convention.

## Goals / Non-Goals

**Goals:**
- Remove the Node-20 deprecation warnings by moving the three flagged actions to their current Node-24 majors.
- Keep the pipeline behaviour identical — same jobs, triggers, required check, and release steps.

**Non-Goals:**
- Pinning actions to commit SHAs (a supply-chain hardening step that's a separate decision; the repo pins by major tag today and this change keeps that convention).
- Touching `sbt/setup-sbt@v1` (not flagged) or restructuring the workflows.
- Cutting a release for this change on its own.

## Decisions

**1. Bump to the current major tag, not a pinned SHA.**
Move `actions/checkout` → v7, `actions/setup-java` → v5, `docker/login-action` → v4 (the current majors, all on a Node-24 runtime — verified against each action's latest release at apply time), keeping the floating-major style the repo already uses. Chosen over SHA-pinning to stay consistent with the existing workflows and keep the diff minimal; SHA-pinning is a distinct hardening change if wanted later. Exact target majors are verified at apply time (they must exist and the CI run must stay green).

**2. Change both workflows together, but treat their validation differently.**
`ci.yml` is validated directly: the PR's own `Compile & Test` run exercises the bumped `checkout`/`setup-java` and (on the non-required publish job) `docker/login-action`, and the run must be warning-free. `release.yml` uses the same actions but only runs on a tag, so it cannot be exercised by the PR — its bump is a same-action major change reviewed by inspection and validated on the next real release. This asymmetry is called out rather than hidden.

## Risks / Trade-offs

- **A new action major changes an input or default.** → The `Compile & Test` required check gates the CI-side bump; if a bumped action breaks setup, the PR goes red and the exact version is corrected before merge.
- **The release-workflow bump isn't exercised until a tag is pushed.** → Low risk (same actions, next major); mitigated by keeping the change small and inspecting the diff. If a future release ever failed on it, the fix is a one-line version correction, and nothing publishes before the (token-gated) test step.
- **A target major might not exist yet.** → Verified at apply time; CI fails fast on an unknown action ref, so a wrong pin can't merge.

## Open Questions

- Whether to also pin actions to SHAs for supply-chain hardening. Out of scope here (keeps the existing major-tag convention); worth a separate change if desired.
