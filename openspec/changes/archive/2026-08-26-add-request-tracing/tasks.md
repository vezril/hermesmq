# Tasks — add-request-tracing (HermesMQ)

## 1. Dependency
- [x] Pin the-lexicon version that carries `Message.correlation_id` / `PublishRequest.correlation_id` + the shared name constants (`CorrelationNames`). Blocks the rest.

## 2. Async propagation primitive
- [x] Add an MDC-propagating `ExecutionContext` (snapshot at submit / restore on worker / symmetric — no leak). Mirror Apollo's `MdcPropagatingExecutionContext`.

## 3. Edge hat — boundary mint-or-adopt
- [x] gRPC: boundary decorator reads `x-correlation-id` from request `Metadata` (mint if absent), sets MDC, echoes on responses incl. trailers-only errors; per-RPC entry wrapper re-reads metadata → MDC and runs the body on the propagating EC.
- [x] REST: request wrapper adopts `X-Correlation-Id` (mint if absent), echoes it on the response (2xx/4xx/5xx), sets MDC.
- [x] Access log: INFO on receipt + completion (subject, status, duration) bearing the id.

## 4. Bus hat — carry the id through the message
- [x] `MessagePublished` event: add optional `correlationId` (default empty). Confirm back-compat replay of pre-change events (empty id, no failure).
- [x] Publish (gRPC + REST): adopt `PublishRequest.correlation_id` / `X-Correlation-Id` → set on the `MessagePublished` event. Never overwrite a supplied id.
- [x] Delivery (`Pull`, `StreamMessages`, `Consume`): read the stored `correlationId` → set `Message.correlation_id` on every delivery (verbatim, never stripped).
- [x] Delivery projection + ack/redelivery handling: set `correlationId` = the message's id in the MDC (async work joins the originating trace) via the propagating EC.

## 5. structured-logging (MODIFIED)
- [x] Confirm `correlationId` surfaces as a top-level JSON field (LogstashEncoder promotes MDC → fields — no encoder change, just ensure it's in the MDC).

## 6. Verify
- [x] Round-trip test: publish with a `correlation_id`, pull/consume, assert the delivered `Message.correlation_id` matches — across a simulated restart (journaled, survives replay).
- [x] Log test: a publish + its delivery emit JSON lines both carrying the same `correlationId`.
- [x] Boundary test: an inbound RPC with no correlation id gets a minted one on its logs + echoed response; one with `x-correlation-id` set adopts it.
- [x] No sensitive data in logs (no payloads/tokens), even at TRACE.
- [x] `openspec validate add-request-tracing --strict`.

## 7. Clients (added during apply — postdates the original handoff)
- [x] REST delivery now carries `correlationId` + publish adopts `X-Correlation-Id` → per the
  update-all-three rule, the Scala/Python/JS clients gained a publish `correlationId` option
  (sent as the header) and `correlationId` on received messages, with stub tests; conformance
  checklist updated.
