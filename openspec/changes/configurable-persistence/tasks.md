## 1. Dependencies & reply protocol

- [ ] 1.1 Add `pekko-persistence-typed`, `pekko-persistence-jdbc`, `pekko-serialization-jackson`, the PostgreSQL JDBC driver, `pekko-persistence-testkit` (Test), and Testcontainers PostgreSQL (Test) to `build.sbt`, pinned compatible with the Pekko version
- [ ] 1.2 Verify `sbt compile` still green after dependency additions
- [ ] 1.3 Define the entity reply protocol (`CommandReply` = `Accepted` | `Rejected(Rejection)`) and the entity command wrappers that carry `replyTo`

## 2. Persistence configuration profiles

- [ ] 2.1 Add an `in-memory` persistence profile (`pekko.persistence.testkit`/inmem journal + snapshot) in test config
- [ ] 2.2 Add a `postgres` profile: `pekko-persistence-jdbc` journal/snapshot with a Postgres slick datasource reading `hermesmq.db.*` + `HERMESMQ_DB_*` overrides; disable Java serialization (`pekko.actor.allow-java-serialization = off`)
- [ ] 2.3 Implement typed loading/validation of `hermesmq.db` settings; RED test for env override + missing-required-setting failure → GREEN

## 3. Topic entity — TDD (in-memory journal)

- [ ] 3.1 RED: `TopicEntitySpec` (EventSourcedBehaviorTestKit) — `Publish` on existing persists `MessagePublished` and replies `Accepted`; confirm red
- [ ] 3.2 GREEN: implement `TopicEntity` (`EventSourcedBehavior` delegating to `Topic.decide`/`evolve`, `persist(...).thenReply`); pass
- [ ] 3.3 Edge: `CreateTopic` on existing and `Publish` on non-existent reply `Rejected(...)` and persist nothing; green
- [ ] 3.4 REFACTOR: extract shared command-handler shape if it helps; re-run green

## 4. Subscription entity — TDD (in-memory journal)

- [ ] 4.1 RED: `SubscriptionEntitySpec` — create/record-delivery/acknowledge/modify persist the right events and reply `Accepted`; confirm red
- [ ] 4.2 GREEN: implement `SubscriptionEntity`; pass
- [ ] 4.3 Edge: unknown-ackId acknowledge/modify and duplicate create reply `Rejected(...)`, persist nothing; green
- [ ] 4.4 REFACTOR: tidy; re-run green

## 5. Recovery — TDD

- [ ] 5.1 RED: recovery test — after `restart`, a Subscription that persisted create + delivery recovers with the message outstanding; confirm red (or assert against the yet-unimplemented behavior)
- [ ] 5.2 GREEN: ensure recovery works (event handler wiring); pass
- [ ] 5.3 Edge: acked message does not reappear after restart; fresh persistence id recovers to empty and accepts create; green

## 6. Event serialization — TDD

- [ ] 6.1 RED: `EventSerializationSpec` — round-trip each Topic/Subscription event through the registered serializer equals the original; confirm red
- [ ] 6.2 GREEN: implement + register the JSON (Jackson) serializer/bindings for domain events; pass
- [ ] 6.3 Edge: confirm Java serialization is disabled (an unregistered/unbound type fails fast rather than falling back); green

## 7. PostgreSQL backend & integration

- [ ] 7.1 Add the `pekko-persistence-jdbc` PostgreSQL schema DDL as a resource and a `docker-compose.yml` for local Postgres that applies it
- [ ] 7.2 RED/integration: a Testcontainers-backed test (tagged, skipped without Docker) runs an entity end-to-end against real Postgres — persist then recover across a restart
- [ ] 7.3 GREEN: make the Postgres profile + DDL work so the integration test passes locally with Docker
- [ ] 7.4 Edge: verify a missing-schema / unreachable-DB run surfaces a clear error (documented + asserted where feasible)

## 8. Readiness gated on persistence — TDD

- [ ] 8.1 RED: extend `HealthRoutesSpec` — readiness reports `503` when a persistence-health check is down while liveness stays `200`; confirm red
- [ ] 8.2 GREEN: add a persistence-reachability check feeding the readiness flag; wire into `HttpServer`/`Main`; pass
- [ ] 8.3 Edge: readiness `200` only when both bound and persistence reachable; green

## 9. Documentation & final verification

- [ ] 9.1 Update `README.md`: `HERMESMQ_DB_*` config, run Postgres via `docker-compose`, apply the schema, persistence profiles, and how to run the Docker-gated integration test
- [ ] 9.2 Full `sbt test` green (in-memory path, no DB required); integration test green locally with Docker
- [ ] 9.3 Confirm every scenario in `event-sourced-aggregates`, `persistence-backend`, and the modified `health-endpoint` readiness maps to a verified test
- [ ] 9.4 Run `openspec validate configurable-persistence`
