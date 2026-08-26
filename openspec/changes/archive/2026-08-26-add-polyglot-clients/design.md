# Design — add-polyglot-clients

## Context

The broker's REST surface (v1.11.0) is:

| Endpoint | Body → Result |
|---|---|
| `POST /v1/topics` | `{topicId, labels?}` → 201 |
| `GET /v1/topics` | → stats listing `[{topicId, publishedTotal}]` |
| `GET /v1/topics/{id}` | → `{topicId, labels}` \| 404 |
| `PATCH /v1/topics/{id}` | `{labels}` → 200 |
| `DELETE /v1/topics/{id}` | → 204 |
| `POST /v1/topics/{id}/messages` | `{payload, attributes?, ttlSeconds?, idempotencyKey?, producerId?}` → `{messageId, deduplicated}` |
| `POST /v1/subscriptions` | `{subscriptionId, topicId}` → 201 |
| `GET /v1/subscriptions` | → stats listing |
| `DELETE /v1/subscriptions/{id}` | → 204 \| 404 (id stays reserved) |
| `POST /v1/subscriptions/{id}/pull` | `{max?, consumerId?}` → `{messages: [{ackId, payload, attributes, publishTime}]}` |
| `POST /v1/subscriptions/{id}/ack` | `{ackIds}` → `{acknowledged, unknown}` |
| `POST /v1/subscriptions/{id}/modifyAckDeadline` | `{ackIds, ackDeadlineSeconds}` → `{modified, unknown}` |
| `GET /health` | → `{status: "UP", …}` |

Auth (when enabled): `Authorization: Bearer <token>` or `X-API-Key`. The existing Scala client
covers roughly half of this; Python/JS clients don't exist.

## Goals / Non-Goals

**Goals:** one client per constellation language with the *same conceptual surface, error model,
and naming*; dependency-light; testable without a running broker; versioned with the repo.

**Non-Goals:** registry publishing (PyPI/npm/Maven); SSE tap; async-Python; gRPC.

## Decisions

1. **Clients live in the hermesmq repo** — Scala stays the `client/` sbt module (precedent);
   Python/JS under `clients/python/` and `clients/js/`. Versioned with the broker: a repo tag pins
   broker + clients together, and a client gap is visible in the same PR that changes a route.
2. **Common surface, idiomatic per language.** Method names are shared
   (`createTopic`/`create_topic`, `publish`, `pull`, `ack`, `modifyAckDeadline`/`modify_ack_deadline`,
   `deleteSubscription`/`delete_subscription`, `listTopics`/`list_topics`, …); shapes are idiomatic
   (case classes / dataclasses / plain objects with `.d.ts` types).
3. **Shared error model:** one exception/error type carrying HTTP status + response body
   (`HermesClientException` / `HermesClientError`). `getTopic` on 404 → `None`/`null` (reads of
   missing things are normal); every other non-2xx → raise/reject. Publish accepts an options
   bag; `deduplicated` is surfaced in the result.
4. **Auth is a constructor option** (optional token → `Authorization: Bearer`; optional apiKey →
   `X-API-Key`). Off by default, matching today's open broker.
5. **Dependencies:** Scala = pekko-http (already), Python = `httpx` only, JS = **zero** (global
   `fetch`; Node ≥ 18). Keeps the JS client vendorable into any service including Next.js BFFs.
6. **Testing without a broker:** each suite spins a local stub HTTP server in-process (Python:
   `http.server` thread; JS: `node:http`; Scala: existing pekko-http test pattern) asserting
   request shape and canned responses — fast, hermetic, CI-friendly. (Scala tests already work
   this way; mirror it.)
7. **Distribution = git installs**, documented per client (pip `#subdirectory=`, npm from a git
   subdir via `prepare`/vendoring, sbt module dependency). No secrets, no registries.

## Risks / Trade-offs

- **Drift risk across three clients** — mitigated by colocation (same repo/PR), a shared
  conformance checklist in `clients/README.md`, and CI running all suites.
- **npm-from-git-subdirectory is awkward** (npm can't install a bare subdir without a `prepare`
  trick) — mitigated: the JS client is a single dependency-free file + types, so vendoring is a
  first-class, documented option.
- **Sync-only Python** may not fit an async consumer — accepted; `httpx` makes an `AsyncClient`
  variant a small follow-up if a consumer needs it.
