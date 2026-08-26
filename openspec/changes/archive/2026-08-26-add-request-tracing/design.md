# Design — add-request-tracing (HermesMQ, the keystone)

## Two hats: HermesMQ is both an edge service and the bus

Most services in the standard are edges (mint-or-adopt, log, propagate). HermesMQ is that **and** the
bus everyone correlates *through*. So the change has two distinct concerns:

| Hat | Boundary | Behaviour |
|-----|----------|-----------|
| **Edge** | its own gRPC/REST API (Publish, Pull, admin RPCs are inbound *requests to the broker*) | mint-or-adopt per request → MDC → echo. Correlates the broker's own request-handling logs. |
| **Bus** | the message itself | adopt the producer's `correlation_id` onto the message, journal it, deliver it verbatim. Correlates *across* services. |

These are independent. A `CreateTopic` RPC uses only the edge hat (no message). A `Publish` uses both:
the edge hat correlates the publish-request handling; the bus hat stamps the id onto the message that
will outlive the request.

## Why the id must be journaled, not just attached in flight

HermesMQ is event-sourced. A message is a `MessagePublished` event; delivery is a projection that may
run **minutes later, on another node, after a restart**. If `correlation_id` lived only on the
in-flight request, it would be gone by delivery time. So it must be part of the **persisted event** —
then the delivery projection reads it off the stored message and both delivers it (`Message.correlation_id`)
and logs under it. This is the crux of the bus role.

### Schema evolution

`MessagePublished` gains an optional `correlationId: String` (default empty). Additive and
back-compatible under the existing `schema-migration` capability: events journaled before this change
replay with an empty correlation id (→ "none supplied"), never a failure. No snapshot break.

## Adopt, don't mint, on the message; mint-or-adopt on the boundary

- **Message correlation** (bus): the broker **adopts** whatever the producer set (`PublishRequest.correlation_id`
  or REST `X-Correlation-Id`) and carries it. It does **not** mint a message correlation id and does
  **not** overwrite one — the producer owns it. Empty stays empty (the consumer decides to mint).
- **Boundary correlation** (edge): for the broker's *own* request logs, an inbound request with no
  correlation id gets one **minted** so the broker's handling is always traceable. The broker's
  callers are trusted internal services, so an inbound id is adopted (not ignored) — no external
  ingress here to defend against.

Subtlety: on `Publish` the boundary id and the message id are usually the same (the producer sets the
metadata/header and the field together). If a producer set only the metadata, the broker adopts it for
the boundary log; the *message* carries `PublishRequest.correlation_id` as given (possibly empty). We
do not silently copy boundary→message — the field is the producer's explicit propagation choice.

## Async propagation

The MDC-propagating `ExecutionContext` (Apollo's `MdcPropagatingExecutionContext`, symmetric
snapshot/restore, no leak) carries the id through `Future` chains and the delivery projection, so deep
TRACE logging stays correlated without threading an id through every signature.

## Non-goals

W3C Trace Context / OTel spans + sampling / a trace backend; any change to delivery, ack, redelivery,
or fan-out semantics (tracing rides the existing path); minting policy for untrusted ingress.
