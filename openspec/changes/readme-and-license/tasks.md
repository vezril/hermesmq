## 1. MIT license

- [x] 1.1 Add an `MIT` `LICENSE` file at the repo root with holder "Calvin Ference" and year 2026 (standard MIT text, no placeholders)
- [x] 1.2 Declare `licenses`, `homepage`, and `developers` metadata in `build.sbt`
- [x] 1.3 Verify `sbt compile` is green and `sbt makePom` emits the MIT license in the POM

## 2. README — description & badges

- [x] 2.1 Rework the top of `README.md` with a clear project description/overview (single-node event-sourced Pub/Sub broker on Pekko) and an at-a-glance capability summary, marking unimplemented features (gRPC, delivery) as planned
- [x] 2.2 Add CI/CD status badges near the title (CI workflow, release/version, license, Docker Hub), each linking to its source and referencing `vezril/hermesmq`

## 3. README — AI usage disclaimer

- [x] 3.1 Add an "AI Usage Disclaimer" section framed as an AI SDLC team: AI (Claude) assisting across spec/design/implementation/testing/review, the spec-driven OpenSpec + TDD workflow, and explicit human direction/accountability — honest, no overstated autonomy

## 4. README — deployment & configuration examples

- [x] 4.1 Add a copy-pasteable deployment example (docker-compose running the service + PostgreSQL, wired via `HERMESMQ_DB_*`, with a `/health` check) using the `vezril/hermesmq` image and port `8080`
- [x] 4.2 Add a consolidated configuration example (single env block of `HERMESMQ_HTTP_*` and `HERMESMQ_DB_*` with realistic values)
- [x] 4.3 Add a Licensing section stating MIT and linking the `LICENSE` file

## 5. Verification

- [x] 5.1 Confirm `LICENSE`, README (description, badges, AI disclaimer, deployment, config, licensing), and `build.sbt` all agree on MIT with the same holder and no placeholder/"all rights reserved" text
- [x] 5.2 Confirm all badge and link URLs reference `vezril/hermesmq`; internal links (LICENSE, sections) resolve
- [x] 5.3 Confirm the capability summary matches implemented features (no unimplemented claims stated as done)
- [x] 5.4 `sbt test` still green (no behavior change) and run `openspec validate readme-and-license`
