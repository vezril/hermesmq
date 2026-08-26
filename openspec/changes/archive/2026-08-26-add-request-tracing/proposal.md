# Change: add-request-tracing

> **The keystone of constellation-wide tracing.** HermesMQ is the *bus* — the vehicle that carries a
> request's `correlationId` across service hops (Apollo publish → Hermes → Muses/Argus consume). This
> makes the broker (1) mint-or-adopt at its own API boundary so its request logs are correlated, and
> (2) **carry `correlation_id` end-to-end through the message** — adopted on publish, journaled in the
> event, delivered verbatim, never stripped. This is the cross-service propagation Apollo's v1 deferred.

## Why

The constellation request-tracing standard (Apollo, reference impl) says every service attaches a
correlation id, logs it, and **propagates** it — so one id stitches a logical operation across
services and back. The `structured-logging` change already anticipated this ("correlatable across
services by ids"); this change supplies the id on the bus.

HermesMQ is special: it is not just another edge service, it's the **channel** other services
correlate *through*. A producer sets a correlation id when publishing; a consumer must receive that
same id on delivery and adopt it. If the broker drops it, the trace breaks at every async hop — which
is most of the constellation's interesting flows. So the broker must treat `correlation_id` as a
first-class, durable part of the message, not incidental metadata.

Depends on **the-lexicon `add-request-tracing`**: `correlation_id` added to the `Message` envelope +
`PublishRequest` (field the broker carries), and the shared name constants (`correlationId` /
`X-Correlation-Id` / `x-correlation-id` / `correlation_id`). This change consumes that pinned version.

## What Changes

- **request-tracing** (new): the bus tracing contract, in two parts —
  - **Boundary (edge pattern):** every inbound gRPC/REST request mints-or-adopts a correlation id
    (trusted internal hop → adopt inbound `x-correlation-id` / `X-Correlation-Id`, mint if absent),
    puts it on every log line for that request (MDC), and echoes it back to the caller.
  - **Bus (carry pattern):** `Publish` adopts the message's `correlation_id` and **journals it in the
    `MessagePublished` event** (durable → survives replay + restart); every delivery (`Pull`,
    `StreamMessages`, `Consume`) returns it verbatim on `Message.correlation_id`. The broker never
    strips or overwrites a message's correlation id. The async delivery projection + ack/redelivery
    work log under the *message's* id, so broker activity joins the originating request's trace.
- **structured-logging** (MODIFIED): `correlationId` is a first-class MDC field in the broker's JSON
  log schema (it already promotes MDC → fields; this names the field and requires it be set).

Implementation (JVM/Scala+Pekko — mirrors Apollo's reference impl):

- MDC-propagating `ExecutionContext` (snapshot at submit, restore on worker) so the id survives
  `Future`/projection async boundaries with no signature churn.
- gRPC boundary decorator (sibling of the metrics/consumer-id one): reads `x-correlation-id` from
  request `Metadata` (mint if absent), stamps it into the handler's MDC, echoes it on responses incl.
  trailers-only errors. Pekko gRPC's internal dispatch means the handler re-reads from metadata.
- Publish path: adopt `PublishRequest.correlation_id` (or REST `X-Correlation-Id`) → set on the
  `MessagePublished` event. Delivery path: read it off the stored message → `Message.correlation_id`
  + MDC for the projection.

## Impact

- Affected specs: `request-tracing` **ADDED**; `structured-logging` **MODIFIED**.
- **Event schema**: `MessagePublished` gains an optional `correlationId` — additive/back-compatible
  under the existing `schema-migration` discipline (old events replay with an empty id).
- **Producers** (Apollo, Artemis, Hephaestus): once this ships, a correlation id set on publish is
  carried to consumers — enables Apollo's outbound-propagation follow-on.
- **Consumers** (Muses/Argus, Artemis): adopt the delivered `Message.correlation_id` as their context
  (their own per-service changes).
- Out of scope: OTel spans/sampling/backend; changing delivery/ack semantics (tracing rides along the
  existing hot path, no behavioural change to fan-out); minting *policy* for external ingress (the
  broker's callers are trusted internal services → adopt).
