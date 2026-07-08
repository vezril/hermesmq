## Context

Five features in, HermesMQ works but presents as a bare technical README with no license. This change is documentation + licensing only — no code or workflow behavior changes. It assumes the existing `github.com/vezril/hermesmq` repo and the `vezril/hermesmq` Docker Hub image (Feature 5). The "AI Usage Disclaimer using an SDLC Team" is an explicit ask: disclose, honestly, that the whole build was AI-assisted across SDLC roles under human direction.

## Goals / Non-Goals

**Goals:**
- A README front page: description/overview, badges, AI usage disclaimer, deployment + configuration examples.
- An MIT `LICENSE` and matching `build.sbt` metadata + README reference.
- Keep every claim accurate to what is actually implemented.

**Non-Goals:**
- Any code, test, or workflow behavior change.
- CONTRIBUTING/CODE_OF_CONDUCT/issue templates (later, if wanted).
- Choosing a different license or dual-licensing.
- Restructuring existing accurate sections (run/test/persistence/docker) beyond light edits.

## Decisions

**License: MIT.**
Permissive, ubiquitous, minimal friction for a connector meant to be reused. Holder: "Calvin Ference"; year 2026 (from the session date). *Alternative:* Apache-2.0 (adds patent grant/NOTICE overhead) — MIT is simpler and was requested.

**Badges: shields.io + native GitHub endpoints, linked to sources.**
- CI: `https://github.com/vezril/hermesmq/actions/workflows/ci.yml/badge.svg` → the Actions page.
- Release: shields.io `github/v/release` (or tag) → releases page.
- License: shields.io `github/license/vezril/hermesmq` → `LICENSE`.
- Docker: shields.io `docker/v/vezril/hermesmq` (or image size) → Docker Hub.
Badges that depend on not-yet-existing data (no release/tag, no Docker Hub repo until secrets are set) will render as "no data"/"invalid" until those exist — acceptable and self-correcting. *Alternative:* omit until data exists — but wiring them now means they light up automatically.

**AI Usage Disclaimer framed as an "AI SDLC team".**
A short section that: (1) states the project was built with AI assistance (Claude) acting across SDLC roles — requirements/spec authoring, architecture/design, implementation, testing, and review; (2) names the spec-driven OpenSpec workflow (propose → design → specs → tasks → apply, TDD throughout); (3) states a human directs the work, reviews output, and is accountable; (4) avoids overstating autonomy. Honesty is the design constraint — no implication that code was unreviewed or fully autonomous.

**Deployment example: docker-compose (service + Postgres).**
Reuse the existing Postgres `docker-compose.yml` shape and add the service container alongside it, wired via `HERMESMQ_DB_*`, with a `/health` check. Copy-pasteable and consistent with the Docker/Persistence sections. *Alternative:* `docker run` pair — shown as a secondary snippet; compose is the primary because the service needs a database.

**Configuration example: one consolidated env block.**
A single fenced block listing `HERMESMQ_HTTP_*` and `HERMESMQ_DB_*` with realistic values, cross-referenced to the existing per-section tables rather than duplicating them wholesale.

**build.sbt metadata: `licenses`, `homepage`, `developers`.**
Declare `licenses := Seq("MIT" -> url(".../LICENSE"))`, `homepage`, and a developer entry so published artifacts carry correct POM metadata. Low-risk additions verified by `sbt makePom`.

## Risks / Trade-offs

- **Badges show "invalid/no data" until a release/Docker Hub image exists** → expected; they self-correct once Feature-5 secrets are set and a release is cut. Documented.
- **README drift vs reality** → keep the capability summary tied to the archived specs; mark unimplemented features (gRPC, delivery) as "planned".
- **AI disclaimer tone** → err toward understatement and human accountability; avoid marketing-y "autonomous agent" language.
- **License holder correctness** → use the repo owner's name; if the user prefers a different holder/entity, it's a one-line change.
- **`build.sbt` metadata typos breaking POM** → verify with `sbt makePom` / compile.

## Migration Plan

Docs/metadata only:
1. Add `LICENSE` (MIT, holder + year).
2. Add `licenses`/`homepage`/`developers` to `build.sbt`; verify `sbt compile`/`makePom`.
3. Rework the README top: overview + badges; add AI Usage Disclaimer, deployment example, configuration example, and a Licensing section linking `LICENSE`.
4. Verify all badge/link URLs reference `vezril/hermesmq`; check internal links resolve.

Rollback: revert the doc/metadata commits; removing `LICENSE` returns to unlicensed (not desired, but clean).

## Open Questions

- **Copyright holder**: assumed "Calvin Ference". Confirm the exact name/entity for the MIT notice.
- **Badge set**: CI + release + license + Docker Hub assumed; add code-coverage or Scala-version badges too?
- **AI disclaimer specifics**: name the assistant (Claude/Claude Code) explicitly? (Assumption: yes, named, with human-accountability framing.)
