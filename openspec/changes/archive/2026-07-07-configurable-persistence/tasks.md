## 1. Dependencies & reply protocol

- [x] 1.1 Add `pekko-persistence-typed`, `pekko-persistence-jdbc`, `pekko-serialization-jackson`, the PostgreSQL JDBC driver, `pekko-persistence-testkit` (Test), and Testcontainers PostgreSQL (Test) to `build.sbt`, pinned compatible with the Pekko version
- [x] 1.2 Verify `sbt compile` still green after dependency additions
- [x] 1.3 Define the entity reply protocol (`CommandReply` = `Accepted` | `Rejected(Rejection)`) and the entity command wrappers that carry `replyTo`

## 2. Persistence configuration profiles

- [x] 2.1 Add an `in-memory` persistence profile (`pekko.persistence.testkit`/inmem journal + snapshot) in test config
- [x] 2.2 Add a `postgres` profile: `pekko-persistence-jdbc` journal/snapshot with a Postgres slick datasource reading `hermesmq.db.*` + `HERMESMQ_DB_*` overrides; disable Java serialization (`pekko.actor.allow-java-serialization = off`)
- [x] 2.3 Implement typed loading/validation of `hermesmq.db` settings; RED test for env override + missing-required-setting failure → GREEN

## 3. Topic entity — TDD (in-memory journal)

- [x] 3.1 RED: `TopicEntitySpec` (EventSourcedBehaviorTestKit) — `Publish` on existing persists `MessagePublished` and replies `Accepted`; confirm red
- [x] 3.2 GREEN: implement `TopicEntity` (`EventSourcedBehavior` delegating to `Topic.decide`/`evolve`, `persist(...).thenReply`); pass
- [x] 3.3 Edge: `CreateTopic` on existing and `Publish` on non-existent reply `Rejected(...)` and persist nothing; green
- [x] 3.4 REFACTOR: extract shared command-handler shape if it helps; re-run green

## 4. Subscription entity — TDD (in-memory journal)

- [x] 4.1 RED: `SubscriptionEntitySpec` — create/record-delivery/acknowledge/modify persist the right events and reply `Accepted`; confirm red
- [x] 4.2 GREEN: implement `SubscriptionEntity`; pass
- [x] 4.3 Edge: unknown-ackId acknowledge/modify and duplicate create reply `Rejected(...)`, persist nothing; green
- [x] 4.4 REFACTOR: tidy; re-run green

## 5. Recovery — TDD

- [x] 5.1 RED: recovery test — after `restart`, a Subscription that persisted create + delivery recovers with the message outstanding; confirm red (or assert against the yet-unimplemented behavior)
- [x] 5.2 GREEN: ensure recovery works (event handler wiring); pass
- [x] 5.3 Edge: acked message does not reappear after restart; fresh persistence id recovers to empty and accepts create; green

## 6. Event serialization — TDD

- [x] 6.1 RED: `EventSerializationSpec` — round-trip each Topic/Subscription event through the registered serializer equals the original; confirm red
- [x] 6.2 GREEN: implement + register the JSON (Jackson) serializer/bindings for domain events; pass
- [x] 6.3 Edge: confirm Java serialization is disabled (an unregistered/unbound type fails fast rather than falling back); green

## 7. PostgreSQL backend & integration

- [x] 7.1 Add the `pekko-persistence-jdbc` PostgreSQL schema DDL as a resource and a `docker-compose.yml` for local Postgres that applies it
- [x] 7.2 RED/integration: a Testcontainers-backed test (tagged, skipped without Docker) runs an entity end-to-end against real Postgres — persist then recover across a restart
- [x] 7.3 GREEN: make the Postgres profile + DDL work so the integration test passes locally with Docker
- [x] 7.4 Edge: verify a missing-schema / unreachable-DB run surfaces a clear error (documented + asserted where feasible)

## 8. Readiness gated on persistence — TDD

- [x] 8.1 RED: extend `HealthRoutesSpec` — readiness reports `503` when a persistence-health check is down while liveness stays `200`; confirm red
- [x] 8.2 GREEN: add a persistence-reachability check feeding the readiness flag; wire into `HttpServer`/`Main`; pass
- [x] 8.3 Edge: readiness `200` only when both bound and persistence reachable; green

## 9. Documentation & final verification

- [x] 9.1 Update `README.md`: `HERMESMQ_DB_*` config, run Postgres via `docker-compose`, apply the schema, persistence profiles, and how to run the Docker-gated integration test
- [x] 9.2 Full `sbt test` green (in-memory path, no DB required); integration test green locally with Docker
- [x] 9.3 Confirm every scenario in `event-sourced-aggregates`, `persistence-backend`, and the modified `health-endpoint` readiness maps to a verified test
- [x] 9.4 Run `openspec validate configurable-persistence`
