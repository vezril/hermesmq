# Add polyglot clients (Scala parity + Python 3 + JavaScript)

## Why

Every constellation service that talks to HermesMQ currently hand-rolls the same REST calls.
Demeter reconstructed publish/pull/ack from scratch for its deal-alert sink; Artemis provisioned
`media.*` topics and `artemis.media.*` subscriptions with bespoke code; the next integrator would do
it again. Meanwhile the repo's own Scala client (`client/` module) has drifted behind the broker: it
predates dedup (`idempotencyKey`), TTL, producer identity (v1.10.0), named consumers,
`modifyAckDeadline`, DELETE subscription (v1.11.0), listings, and auth headers — so even Scala
services can't fully use it. One maintained client per major constellation language (Scala, Python 3,
JavaScript/TypeScript-typed) ends the copy-paste and gives new integrations a five-minute start.

## What Changes

- **Scala client parity** (`client/` module, existing): close the drift — publish options
  (`ttlSeconds`, `idempotencyKey`, `producerId`) and `deduplicated` in the result; `consumerId` on
  pull; `ack` returns `acknowledged`/`unknown`; new `modifyAckDeadline`, `deleteSubscription`,
  `listTopics`, `listSubscriptions`, `health`; optional bearer/`X-API-Key` auth header on every call.
- **Python 3 client** (new, `clients/python/`): `hermesmq-client` package — sync (`httpx`-based)
  client with the same surface, typed with dataclasses, py3.12, uv-managed, pytest suite against a
  stub server. Installable via `pip install git+https://github.com/vezril/hermesmq#subdirectory=clients/python`.
- **JavaScript client** (new, `clients/js/`): `@hermesmq/client` package — zero-dependency
  (global `fetch`, Node ≥ 18 or browser), ESM + types via JSDoc/`.d.ts`, vitest suite against a stub
  server. Installable via `npm install github:vezril/hermesmq#path:clients/js` or vendoring.
- All three expose the same conceptual API: topics CRUD + list, publish (with options),
  subscriptions create/delete/list, pull (with `consumerId`), ack, modifyAckDeadline, health; the
  same error model (typed error carrying the HTTP status + body; 404-on-read = empty result, not an
  error); and optional auth.
- CI: new jobs exercising the Python (uv + pytest) and JS (node + vitest) suites; Scala parity is
  covered by the existing `Compile & Test` job.

## Capabilities

### New Capabilities
- `python-client`: the typed Python 3 client for the HermesMQ REST API (surface, error model, auth,
  packaging).
- `js-client`: the typed JavaScript/ESM client for the HermesMQ REST API (surface, error model,
  auth, packaging).

### Modified Capabilities
- `scala-client`: parity with the current REST surface — publish options + dedup result, named
  consumers on pull, ack detail, modifyAckDeadline, deleteSubscription, listings, health, auth
  headers.

## Impact

- **Code**: `client/` (Scala, extended); `clients/python/` and `clients/js/` (new, self-contained —
  no change to `server/`/`domain/`).
- **CI**: two added jobs (non-required initially); release flow unchanged (clients ship with the
  repo; no PyPI/npm publishing in this change — git-based installs keep it credential-free).
- **Docs**: a `clients/README.md` matrix + per-client READMEs with quick-starts.
- **Consumers**: Demeter/Artemis/future services can replace hand-rolled calls at their own pace;
  nothing breaks if they don't.

## Non-goals

- Publishing to PyPI/npm/Maven Central (needs accounts + secrets; git installs suffice for the
  homelab — revisit if a client gains external users).
- Streaming/SSE tap support (a console concern; services use pull/ack).
- An async Python variant or a gRPC client (the Lexicon already carries the gRPC contract for
  Scala; add later if a consumer needs it).
