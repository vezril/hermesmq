## Context

The Topic aggregate (pure `decide`/`evolve`) and its persistent `TopicEntity` exist, but there is no network API and no delete/update. Feature 7 adds topic management over a **REST API on the existing Pekko HTTP server** (the user chose REST now, gRPC as a later feature), plus the domain extensions and a single-node registry to route requests to entities. The health endpoints and persistence are unchanged.

## Goals / Non-Goals

**Goals:**
- Domain: `DeleteTopic`, `UpdateTopic(labels)`, labels on create, a `deleted` state; total `decide`/`evolve` with rejections.
- Serialization: journal the new events (extend the existing spray-json serializer).
- A single-writer topic **registry** (get-or-spawn) so exactly one entity per id.
- REST admin endpoints (`/v1/topics…`) mapping outcomes to HTTP status codes.
- Full TDD (domain, entity, registry, routes) + README docs.

**Non-Goals:**
- gRPC (a later feature, before/with publish-consume).
- Publish/consume over the API (Feature 8).
- Listing all topics (a read-model/projection concern — later).
- Retention/policy config (labels only now); clustering.

## Decisions

**"Update" = labels (`Map[String, String]`).**
The updatable config is a labels/metadata map; `UpdateTopic(labels)` **replaces** the set (simple, predictable). Label keys must be non-blank. *Alternative:* merge semantics or a retention policy — merge is surprising for a PUT-like update; retention belongs with the later delivery/purging work.

**Delete = soft delete (a terminal `deleted` state).**
`TopicDeleted` marks the topic deleted; a deleted topic rejects publish/update/delete and its id cannot be re-created. Event sourcing keeps the history; "delete" is a state transition, not a journal erase. *Alternative:* allow re-create after delete — ambiguous identity; deferred. Hard journal deletion is a retention/purging concern (later).

**Rejections: reuse `TopicNotFound` for deleted-topic operations.**
Operations on a deleted topic reject as `TopicNotFound` (it is not an active topic); re-create rejects as `TopicAlreadyExists` (the id is taken). This avoids a new rejection type and maps cleanly to HTTP 404/409. *Alternative:* a distinct `TopicDeleted` rejection — more precise but more surface; revisit if clients need to distinguish.

**Registry: a get-or-spawn owner actor (not Cluster Sharding).**
Single-node is a project non-goal for clustering, so a `TopicRegistry` actor holds `Map[TopicId, ActorRef[TopicEntityCommand]]` and spawns a child entity on first use, guaranteeing one writer per id. Routing a command = ask the registry to resolve the ref (or forward). *Alternative:* Pekko Cluster Sharding — the idiomatic multi-node answer, but requires forming a cluster even for one node; unnecessary weight here and easy to swap in later behind the same routing seam.

**Read path: a non-persisted `GetTopic` query on the entity.**
`GetTopic(replyTo)` replies with a view of current state (id, labels, deleted) via `Effect.none.thenReply` — no event written. Keeps GET consistent with the write path (same entity, same recovery) without a separate read model. *Alternative:* a projection/read model — that is the Feature-for-later query side; overkill for single-topic GET.

**REST shape and status mapping.**
`POST /v1/topics` (body `{topicId, labels?}`) → 201 / 409 / 400; `GET /v1/topics/{id}` → 200 / 404; `PATCH /v1/topics/{id}` (body `{labels}`) → 200 / 404 / 400; `DELETE /v1/topics/{id}` → 204 / 404. Map `CommandReply`/query results: `Accepted`→2xx, `Rejected(TopicAlreadyExists)`→409, `Rejected(TopicNotFound)`→404; invalid id/body (a `ValidationError`)→400. Routes use the ask pattern to the registry with a bounded timeout. *Alternative:* PUT for update — PATCH better signals a partial/labels update.

**Wiring: combine admin routes with health routes.**
`Main` starts the registry (a top-level actor) and passes it to `TopicAdminRoutes`; `HttpServer` serves `HealthRoutes ~ TopicAdminRoutes`. Reuses the existing bind/readiness/shutdown machinery.

**Serialization: extend the existing `DomainEventSerializer`.**
Add JSON formats + manifest handling for `TopicDeleted`, `TopicLabelsUpdated`, and the labels field on `TopicCreated`. Round-trip tested. Java serialization stays off.

## Risks / Trade-offs

- **Registry as a single actor could bottleneck** → it only does get-or-spawn/resolve (cheap); actual work is in the per-topic entities. Fine for single-node; swappable for sharding later.
- **`GetTopic` reads entity state, not a read model** → eventually-consistent listing/aggregation is out of scope; single GET is strongly consistent via the entity, which is what we want.
- **Event schema change (`TopicCreated` gains labels)** → older journaled `TopicCreated` (from v0.2.0, no labels) must still deserialize; the format SHALL default missing labels to empty so existing journals replay. Covered by a serialization test.
- **Ask timeouts on routes** → use a sensible timeout and map failures to 503/500 so a slow/unavailable entity doesn't hang the request.
- **Soft-deleted ids are permanently taken** → acceptable for now; documented. Re-create/purge is a later concern.
- **One writer per id under concurrency** → the registry must resolve-or-spawn atomically (single-actor state), avoiding a race that double-spawns an entity (two writers → journal conflict).

## Migration Plan

Additive; TDD order:
1. Domain: extend `TopicCommand`/`TopicEvent`/`TopicState` (labels, deleted); RED tests for delete/update/labels + rejections → implement `decide`/`evolve`.
2. Serialization: RED round-trip tests for new events + labels + backward-compat (missing labels → empty) → extend serializer/formats.
3. Entity: `GetTopic` query reply; entity tests for new events persisting + the query.
4. Registry: RED test for one-writer-per-id / on-demand resolve → implement `TopicRegistry`.
5. REST: RED route tests (pekko-http-testkit) for each endpoint + status codes → implement `TopicAdminRoutes`; map replies.
6. Wire registry + routes into `Main`/`HttpServer`; a boot/integration check.
7. README: Topic Admin API section; capability table update.

Rollback: additive files + enum cases; reverting removes the API and the delete/update behavior. Journals written with the new events would need those events to still deserialize if rolled back — note in the PR.

## Open Questions

- **API version prefix**: `/v1/topics` assumed. Good?
- **Label value constraints**: keys non-blank; any limit on count/size? (Assumption: non-blank keys, otherwise unconstrained for now.)
- **GET response fields**: id + labels + `deleted` flag? (Assumption: return id and labels; include `deleted` only if useful — leaning id+labels, 404 for deleted.)
