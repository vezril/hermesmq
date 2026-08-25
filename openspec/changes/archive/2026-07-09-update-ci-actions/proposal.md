## Why

Every CI and release run now logs a GitHub deprecation warning: `actions/checkout@v4`, `actions/setup-java@v4`, and `docker/login-action@v3` "target Node.js 20 but are being forced to run on Node.js 24." Node 20 is being retired from the runners; today it's a noisy warning, but when GitHub removes the Node 20 runtime these pinned majors will stop working. Bumping to the current majors (which ship a Node 24 runtime) silences the noise now and keeps the pipeline working through the removal.

## What Changes

- Bump the deprecated Node-20 actions to their current Node-24 majors in both workflows (`.github/workflows/ci.yml` and `release.yml`): `actions/checkout`, `actions/setup-java`, and `docker/login-action`. (`sbt/setup-sbt@v1` is not flagged and stays.)
- Verify a CI run is warning-free; note the release-workflow bump is exercised end-to-end only on the next release tag.
- No product, API, or behavioural change — CI/CD semantics (what runs, when, and the required check) are unchanged.

## Capabilities

### New Capabilities
_None._

### Modified Capabilities
- `ci-cd-pipeline`: add a requirement that the workflows pin actions to versions on a currently-supported Node.js runtime (no deprecated-runtime warnings), so the pipeline keeps working when GitHub removes the old runtime.

## Impact

- **Workflows only**: `.github/workflows/ci.yml`, `.github/workflows/release.yml`. No source, dependency, or config change.
- **Risk**: a new action major could change inputs/behaviour — mitigated by the CI run itself (the required `Compile & Test` check must stay green). The release-workflow change can only be fully validated when a release tag runs, so it's a low-risk same-action major bump verified on the next release.
- **No version bump / no release required** for this change on its own — it can ride along with the next feature release.
