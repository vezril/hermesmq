## Why

The README has grown feature-by-feature into an accurate but utilitarian document, and the project has no license — which legally means "all rights reserved" and blocks any use or contribution. Before HermesMQ is presented as a real open-source connector, it needs a proper front page (what it is, live CI/CD status, how to deploy and configure it), an honest disclosure of how AI was used to build it, and an explicit open-source license.

## What Changes

- Add a clear **project description / overview** at the top of the README: what HermesMQ is (single-node, event-sourced Pub/Sub broker on Pekko), its core guarantees, and its current capability set, with an at-a-glance feature/architecture summary.
- Add **CI/CD status badges** near the title: CI workflow status, latest release/version, license, and the Docker Hub image.
- Add an **AI Usage Disclaimer** framed as an "AI SDLC team": disclose that the project was built with AI assistance acting across software-development-lifecycle roles (product/spec, architecture/design, implementation, testing, review), that a human directs and is accountable, and the spec-driven (OpenSpec) workflow used.
- Add a **deployment example**: a copy-pasteable Docker + PostgreSQL deployment (docker-compose / `docker run`) showing the service and its database running together, with the health check.
- Add a **configuration example**: a consolidated, realistic environment-variable configuration block for a deployment.
- Add an **MIT `LICENSE`** file (correct holder/year), reference it from the README, and declare the license in `build.sbt` metadata.

Scope note: documentation and licensing only — no application code or workflow behavior changes. Badge/image URLs assume the existing `vezril/hermesmq` GitHub repo and Docker Hub image.

## Capabilities

### New Capabilities
- `project-documentation`: A complete README front page — project description, CI/CD badges, AI usage disclaimer, and deployment + configuration examples.
- `licensing`: The project's open-source license — an MIT `LICENSE` file, referenced from the README and declared in build metadata.

### Modified Capabilities
<!-- None. Prior specs describe behavior that is unchanged; this only adds docs and a license. -->

## Impact

- **New files**: `LICENSE` (MIT).
- **Edited files**: `README.md` (new description, badges, AI disclaimer, deployment + config examples, license reference); `build.sbt` (`licenses`/`homepage`/`developers` metadata).
- **External assumptions**: shields.io-style badge URLs for the GitHub Actions CI status, GitHub release, license, and Docker Hub image under `vezril/hermesmq`.
- **Legal**: switches the project from implicit "all rights reserved" to MIT — permissive reuse with attribution.
- **No runtime/behavior change**: purely documentation and metadata.
