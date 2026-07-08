Architecturally, HermesMQ is built on event sourcing and CQRS, implemented through Pekko's actor model. Every state change in the broker — topic and subscription lifecycle, message publication, delivery, acknowledgment, deadline extension, redelivery — is captured as an immutable domain event journaled via Pekko Persistence with a local journal backend. Topics and subscriptions are modeled as supervised EventSourcedBehavior actors: the write side validates incoming commands (Publish, Acknowledge, ModifyAckDeadline, CreateSubscription, …) against current in-memory state and emits events, and that journaled event log is the source of truth. Recovery after a restart is a replay of events (bounded by snapshots), so accepted messages survive crashes and unacknowledged messages resume delivery exactly where they left off. A publish is only acknowledged to the producer once its event is durably written. On the query side, Pekko Projection consumes the event journal to maintain read models — subscription backlog and oldest-unacked-age, per-topic throughput, redelivery counts, admin listings — which serve the admin API and metrics without ever touching the hot delivery path. Read models are eventually consistent by design, which is acceptable for observability and administration; delivery correctness depends only on the write-side journal. Acknowledged messages are eventually purged from the journal rather than retained indefinitely. Pekko gRPC exposes the service API. The system targets single-node operation — clustering and horizontal scaling are non-goals — but is honest about its delivery guarantees and failure behavior within that constraint.

Tech stack:
- Backend: Scala + Pekko + Pekko Persistence

TDD is REQUIRED (non-negotiable):
- Follow Red–Green–Refactor.
- For every behavior in the specs, write tests FIRST (failing), then implement.
- The task list must explicitly sequence: tests → implementation → refactor.
- Always RUN tests after each implementation to ensure they pass.

Include a comprehensive README.md file in the root of the project that explains how to run the application and how to run the tests.

Documentation
- https://claude.com/docs/connectors/building
- https://api.ynab.com/

Extra
- Use the repo located on the local filesystem `/Users/cference/Code/claude-toolkit` for relevant skills and agents.

Features (minimal, more features to be added later, this is just to get started). Features will be described as Acceptance Criteria, use these for your TDD tests, additionally, think of at least two edge cases for the tests:
1. Basic Github Project Scaffolding with CICD on Github using Github Actions
    - Version Control should follow semantic versioning schema
    - `main` branch has the latest major release (this builds and ships the main package via CICD)
    - `development` branch has the latest dev changes (experimental builds)
    - Feel free to propose a different strategy (and update this spec as needed)
2. Basic Pekko (No persistence) that can be built into a docker container exposing an health endpoint
3. Basic Commands, Events, and Data Models Necessary 
4. Basic Persistence with configurable database (use postgresql for now)
5. Docker Container and publish artifact to docker hub
6. Basic README.md with description of the project, CICD banners, AI Usage Disclaimer using an SDLC Team, deployment example, and configuration example. Also add MIT license.
7. A Client Library to be able to consume the service natively in Scala

More features to come.

Constraints / non-goals:
- This is a connector, so no UI
- Provide Given/When/Then acceptance criteria with at least 2 edge cases per feature.
- Functional Programming highly desired over imperative style
- Scala Best practices encouraged
- Clean Code is a must
