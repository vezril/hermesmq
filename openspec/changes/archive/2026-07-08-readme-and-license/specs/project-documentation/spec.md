## ADDED Requirements

### Requirement: Project description and overview

The README SHALL open with a concise description of HermesMQ — a single-node, event-sourced Pub/Sub message broker on Apache Pekko — its core durability guarantee, and an at-a-glance summary of current capabilities.

#### Scenario: README states what the project is
- **WHEN** a reader opens the README
- **THEN** within the first screen it explains that HermesMQ is a single-node, event-sourced message broker on Pekko and summarizes its current capabilities

#### Scenario: Edge case — description matches actual capabilities
- **GIVEN** the current implemented features (health service, domain aggregates, durable persistence, Docker publishing)
- **WHEN** the overview lists what the project does
- **THEN** it does not claim unimplemented functionality (e.g. gRPC API, delivery/redelivery) as done, marking future work as such

### Requirement: CI/CD status badges

The README SHALL display status badges near the title for at least: the CI workflow status, the latest release/version, the license, and the Docker Hub image.

#### Scenario: Badges are present and link to their sources
- **WHEN** a reader views the top of the README
- **THEN** CI, release, license, and Docker Hub badges are shown, each linking to the corresponding workflow, release page, license, or image

#### Scenario: Edge case — badge targets reference the correct sources
- **GIVEN** the GitHub repository `vezril/hermesmq` and the Docker Hub image `calvinference/hermesmq`
- **WHEN** the badge and link URLs are resolved
- **THEN** the CI/release/license badges point at `vezril/hermesmq` and the Docker Hub badge points at `calvinference/hermesmq`, not a placeholder or a different target

### Requirement: AI usage disclaimer

The README SHALL include an AI Usage Disclaimer that discloses the project was built with AI assistance acting across SDLC roles (spec, design, implementation, testing, review), states that a human directs and is accountable for the work, and notes the spec-driven workflow used.

#### Scenario: Disclaimer discloses AI-assisted SDLC
- **WHEN** a reader reaches the AI Usage Disclaimer
- **THEN** it clearly states AI was used across the development lifecycle, that a human is responsible, and how the work was driven (spec-driven / OpenSpec)

#### Scenario: Edge case — disclaimer is honest and non-misleading
- **WHEN** the disclaimer describes the "AI SDLC team"
- **THEN** it does not overstate autonomy or imply human review was absent; it frames AI as an assistant under human direction

### Requirement: Deployment and configuration examples

The README SHALL provide a copy-pasteable deployment example (the service plus its PostgreSQL database via Docker) and a consolidated configuration example using the `HERMESMQ_*` environment variables.

#### Scenario: Deployment example runs the service with its database
- **WHEN** a reader follows the deployment example
- **THEN** it shows the service and a PostgreSQL database started together (e.g. docker-compose or `docker run`) and how to verify health at `/health`

#### Scenario: Configuration example uses real variables
- **WHEN** a reader views the configuration example
- **THEN** it uses the actual `HERMESMQ_HTTP_*` and `HERMESMQ_DB_*` variables with sensible values

#### Scenario: Edge case — examples reference the published image and correct ports
- **GIVEN** the published image `calvinference/hermesmq` and the default port `8080`
- **WHEN** the deployment example is read
- **THEN** it uses that image name and exposes/maps port `8080` consistently with the rest of the README
