## 1. Domain — delete, update, labels (TDD)

- [x] 1.1 RED: extend `TopicSpec` — `CreateTopic(id, labels)` carries labels; `DeleteTopic` on active → `TopicDeleted` + evolve marks deleted; `UpdateTopic(labels)` on active → `TopicLabelsUpdated` + evolve replaces labels; confirm red
- [x] 1.2 GREEN: add `labels` + `deleted` to `TopicState`; add `DeleteTopic`/`UpdateTopic` to `TopicCommand` and `TopicDeleted`/`TopicLabelsUpdated` to `TopicEvent` (and labels on `TopicCreated`); extend `Topic.decide`/`evolve`; pass
- [x] 1.3 Edge cases: re-create a deleted topic → `TopicAlreadyExists`; publish/update/delete on deleted or non-existent → `TopicNotFound`; delete already-deleted → `TopicNotFound`; all green
- [x] 1.4 REFACTOR: tidy active-topic guard helper; re-run green

## 2. Event serialization (TDD)

- [x] 2.1 RED: extend `EventSerializationSpec` — round-trip `TopicCreated(labels)`, `TopicDeleted`, `TopicLabelsUpdated`; and a backward-compat case where a `TopicCreated` JSON without a labels field deserializes to empty labels; confirm red
- [x] 2.2 GREEN: extend `JsonFormats`/`DomainEventSerializer` for the new events and the labels field (default missing labels to empty); pass

## 3. Topic entity — query & new events (TDD)

- [x] 3.1 RED: extend `TopicEntitySpec` — `DeleteTopic`/`UpdateTopic` persist the right events and reply `Accepted`; a `GetTopic` query replies with current state (id, labels, deleted) without persisting; confirm red
- [x] 3.2 GREEN: add a `GetTopic(replyTo)` query command + reply type to the entity (`Effect.none.thenReply`); ensure new write commands flow through the existing handler; pass
- [x] 3.3 Edge: rejected commands (delete/update on missing) reply `Rejected(...)` and persist nothing; green

## 4. Topic registry — one writer per id (TDD)

- [x] 4.1 RED: `TopicRegistrySpec` — resolving the same id twice yields the same entity (one writer); different ids yield different entities; a command for a new id is handled on demand; confirm red
- [x] 4.2 GREEN: implement `TopicRegistry` (get-or-spawn owner actor keyed by `TopicId`, forwarding/resolving `TopicEntityCommand`); pass
- [x] 4.3 REFACTOR: tidy resolve-or-spawn; re-run green

## 5. REST admin API (TDD)

- [x] 5.1 RED: `TopicAdminRoutesSpec` (pekko-http-testkit) — `POST /v1/topics` → 201; `GET /v1/topics/{id}` → 200 with labels; `PATCH /v1/topics/{id}` → 200 (read reflects new labels); `DELETE /v1/topics/{id}` → 204; confirm red
- [x] 5.2 GREEN: implement `TopicAdminRoutes` + JSON request/response models; route via the registry (ask pattern, bounded timeout); map `CommandReply`/query → status codes; pass
- [x] 5.3 Edge: duplicate create → 409; GET/PATCH/DELETE on missing → 404; malformed/blank-id body → 400; green
- [x] 5.4 REFACTOR: extract reply→status mapping; re-run green

## 6. Wiring & documentation

- [x] 6.1 Start the `TopicRegistry` in `Main` and serve `HealthRoutes ~ TopicAdminRoutes` via `HttpServer`; boot check that `/v1/topics` is reachable
- [x] 6.2 Manual run check: `sbt run`, create → get → patch → get → delete → get(404) over curl against a live Postgres
- [x] 6.3 Update `README.md`: Topic Admin API section (endpoints, examples, status codes); capability table (topic management → done)

## 7. Final verification

- [x] 7.1 Full `sbt test` green (in-memory path, no DB)
- [x] 7.2 Confirm every scenario in the `topic-lifecycle` delta and `topic-admin-api` maps to a verified test
- [x] 7.3 Run `openspec validate topic-management`
