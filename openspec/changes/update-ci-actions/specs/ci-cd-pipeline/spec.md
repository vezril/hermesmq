## ADDED Requirements

### Requirement: Actions pinned to supported runtimes

The CI and release workflows SHALL pin their third-party GitHub Actions to versions that run on a currently-supported Node.js runtime, so runs do not emit deprecated-runtime warnings and the pipeline keeps functioning when GitHub removes the old runtime. The CI and release workflows SHALL use the same supported major of a shared action (e.g. checkout, Java setup, Docker login) so the two do not drift.

#### Scenario: A CI run emits no deprecated-runtime warning
- **GIVEN** the CI workflow with its pinned actions
- **WHEN** a run executes on the GitHub-hosted runner
- **THEN** no action logs a "forced to run on Node.js" deprecated-runtime warning

#### Scenario: CI and release pin the same supported action majors
- **GIVEN** a GitHub Action used by both `ci.yml` and `release.yml` (e.g. `actions/checkout`)
- **WHEN** the workflows are compared
- **THEN** both reference the same supported major, so they do not diverge

#### Scenario: Edge case — an unknown action version fails fast
- **GIVEN** an action pinned to a version that does not exist
- **WHEN** a workflow run starts
- **THEN** the run fails immediately on resolving the action, so a bad pin cannot merge behind a green check
