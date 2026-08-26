# HermesMQ clients

One maintained client per constellation language, so services stop hand-rolling the same REST
calls. All target the broker's `/v1` REST API and are versioned with this repo — a route change and
its client updates land in the same PR.

| Language | Package | Where | Runtime deps | Tests |
|---|---|---|---|---|
| Scala 3 | `hermesmq-client` (sbt module) | [`../client/`](../client/) | pekko-http + spray-json | scalatest vs in-process pekko stub |
| Python 3.12+ | `hermesmq-client` | [`python/`](python/) | `httpx` | pytest vs in-process `http.server` stub |
| JavaScript (Node ≥ 18 / browser) | `@hermesmq/client` | [`js/`](js/) | **none** (global `fetch`) | vitest vs in-process `node:http` stub |

Scala install: add the repo as a module dependency (or `publishLocal` the `client` module).
Python install: `pip install "hermesmq-client @ git+https://github.com/vezril/hermesmq#subdirectory=clients/python"`.
JS install: vendor `index.js` + `index.d.ts`, or a `file:` dependency — see [`js/README.md`](js/README.md).

## Conformance checklist

Every client provides the same conceptual surface and semantics. When the broker's API changes,
update **all three** (and each client's stub-server tests) in the same PR:

- **Topics**: create (labels), get, update labels, delete, list (stats: `publishedTotal`, `deleted`).
- **Publish**: payload + attributes, optional `ttlSeconds` / `idempotencyKey` / `producerId` /
  `correlationId` (request-tracing — sent as the `X-Correlation-Id` header, journaled with the
  message, delivered to consumers verbatim); result carries `messageId` **and** `deduplicated`.
- **Delivered messages** carry the producer's `correlationId` (empty/absent when none was set) —
  consumers adopt it as their logging context to join the originating trace.
- **Subscriptions**: create, delete (204; the id stays **reserved** afterwards — recreate is 409),
  list (stats: backlog, oldest-unacked age, redelivered, dead-lettered).
- **Consume**: pull (`max`, optional `consumerId`), ack (returns `acknowledged`/`unknown`),
  modifyAckDeadline (returns `modified`/`unknown`).
- **Observability**: health (`status`/`service`/`version`).
- **Error model**: one typed error carrying HTTP status + body; *get-topic on 404 returns
  empty/None/null* (a missing read is normal); everything else non-2xx raises/rejects.
- **Status decides success, not the body's content-type label**: a 2xx whose JSON body arrives
  under an unexpected `Content-Type` still parses and counts as delivered — never surfaced as a
  transport failure (misreading a delivered publish as failed causes retries/duplicate alerts;
  found in the wild by demeter-service). Each suite has an explicit test for this.
- **Auth**: optional constructor token → `Authorization: Bearer`; optional api key → `X-API-Key`;
  neither sent by default.
- **Unknown is never zero**: a listing/count that cannot be determined surfaces as the typed
  error — never as an empty list or `0`. "I couldn't ask" and "nobody is listening" demand opposite
  responses from callers (a drift alarm must fire on a definite zero audience and hold on unknown —
  demeter-service's rule). Each suite has an explicit test that a failed listing raises rather than
  returning empty.
- **Tests**: hermetic (in-process stub server, no live broker), asserting request wire-shapes
  (bodies, headers) as well as response parsing.
