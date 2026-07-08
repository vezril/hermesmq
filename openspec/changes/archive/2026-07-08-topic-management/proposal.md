## Why

The Topic aggregate can be created and published to, but there is no way to **manage** topics over the network and no way to delete or reconfigure one. Feature 7 makes topics manageable: create, delete, and update (labels/metadata), exposed through a REST admin API over the HTTP server that already runs. This is the first slice of the service's external API and the foundation the publish/consume path (Feature 8) and the client library (Feature 9) build on.

## What Changes

- Extend the **Topic domain** with delete and update:
  - Introduce topic **labels** (`Map[String, String]` metadata) as the updatable configuration.
  - New commands `DeleteTopic` and `UpdateTopic(labels)`; `CreateTopic` optionally carries initial labels.
  - New events `TopicDeleted` and `TopicLabelsUpdated`; `TopicCreated` carries labels.
  - `decide`/`evolve` gain a `deleted` state: a deleted topic rejects publish/update/delete (as not-found), and a taken id cannot be re-created.
- Add a **topic registry** that routes commands to the correct persistent `TopicEntity` by id on a single node — a get-or-spawn owner so there is exactly one writer per topic (entities recover their own state from the journal).
- Add a **REST admin API** on the existing Pekko HTTP server:
  - `POST /v1/topics` (create) · `DELETE /v1/topics/{id}` (delete) · `PATCH /v1/topics/{id}` (update labels) · `GET /v1/topics/{id}` (read current state).
  - Map domain outcomes to HTTP status codes (created → 201, already-exists → 409, not-found → 404, updated/read → 200, deleted → 204).
- Wire the registry + admin routes into `Main`, alongside the health routes.
- Document the topic-admin API and update the capability summary.

Scope note: **REST now, gRPC later** — a gRPC API (per the architecture) will be introduced as its own feature, before/with publish-consume. Message publish/consume over the API, listing topics (a read-model concern), retention, and clustering are out of scope here. "Update" changes **labels only** for now.

## Capabilities

### New Capabilities
- `topic-admin-api`: The REST admin endpoints for creating, deleting, updating, and reading topics, and the single-node topic registry that routes each request to the owning persistent entity.

### Modified Capabilities
- `topic-lifecycle`: The Topic aggregate gains delete and update-labels behavior and a deleted state, and topics carry labels — extending the existing create/publish requirements.

## Impact

- **Domain** (`domain` package): `TopicCommand`/`TopicEvent` gain delete/update cases; `TopicState` gains `labels` and `deleted`; `Topic.decide`/`evolve` extended; possibly a `Rejection.TopicDeleted` (or reuse `TopicNotFound`). Label validation (e.g. non-blank keys).
- **Persistence**: `TopicEntity` accepts the new commands (its handler already delegates to `decide`); the new events must be added to the JSON serializer bindings/formats so they journal correctly. A read/query path (`GetTopic`) replies with current state without persisting.
- **New source**: `TopicRegistry` (get-or-spawn routing), `TopicAdminRoutes` (REST), request/response JSON models; wiring in `Main`.
- **Tests**: domain decide/evolve for delete/update/labels + rejections; entity tests for the new events + serialization round-trip; route tests (pekko-http-testkit) for each endpoint and status code; a registry test for one-writer-per-id.
- **API surface**: introduces `/v1/topics...`; the health endpoints are unchanged. No gRPC yet.
- **Docs**: README gains a Topic Admin API section; capability table updates (topic management → done).
