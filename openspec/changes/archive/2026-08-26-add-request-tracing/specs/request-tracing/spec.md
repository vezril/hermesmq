# request-tracing

HermesMQ carries request correlation across the constellation. It correlates its own API boundary
(mint-or-adopt) and, as the bus, carries a message's `correlation_id` end-to-end — adopted on
publish, journaled, delivered verbatim, never stripped.

## ADDED Requirements

### Requirement: Correlate the broker's request boundary

Every inbound request to the broker (gRPC RPC or REST call) SHALL run under a correlation id: the
broker SHALL **adopt** an inbound id when present (`x-correlation-id` gRPC metadata / `X-Correlation-Id`
HTTP header — its callers are trusted internal services) and **mint** one when absent. That id SHALL
be on every log line emitted while handling the request (via MDC) and SHALL be **echoed** back to the
caller (gRPC response metadata incl. trailers-only errors; HTTP response header). Names come from the
shared Lexicon constants.

#### Scenario: An inbound correlation id is adopted
- **GIVEN** a client calls an RPC with `x-correlation-id: abc123`
- **WHEN** the broker handles it
- **THEN** the broker's log lines for that request carry `correlationId=abc123` and the response echoes `abc123`

#### Scenario: A request without a correlation id gets one minted
- **GIVEN** a request arrives with no correlation id
- **WHEN** the broker handles it
- **THEN** the broker mints an id, logs its handling under it, and returns it to the caller

#### Scenario: Edge case — an error response still carries the id
- **GIVEN** a request that fails (e.g. publish to a missing topic)
- **WHEN** the broker returns the error
- **THEN** the correlation id is still echoed (gRPC trailers-only / HTTP error response) and appears on the error log line

### Requirement: Carry message correlation across the bus

The broker SHALL treat `correlation_id` as a first-class, durable part of a message. On `Publish` it
SHALL **adopt** the producer-supplied id (`PublishRequest.correlation_id` or REST `X-Correlation-Id`)
and journal it with the message (in the `MessagePublished` event, so it survives replay, restart, and
cross-node delivery). On every delivery (`Pull`, `StreamMessages`, `Consume`) it SHALL return that id
verbatim on `Message.correlation_id`. The broker SHALL NOT strip, mint, or overwrite a message's
correlation id — the producer owns it; an empty id stays empty.

#### Scenario: A published correlation id is delivered unchanged
- **GIVEN** a producer publishes to `orders` with `correlation_id = req-42`
- **WHEN** a consumer pulls the message
- **THEN** the delivered `Message.correlation_id` is `req-42` — the bus carried it, unaltered

#### Scenario: The id survives a broker restart (journaled, not in-flight)
- **GIVEN** a message published with `correlation_id = req-42` before a broker restart
- **WHEN** the delivery projection processes it after the restart
- **THEN** the delivered message still carries `req-42` (it was journaled in the event, not held in request memory)

#### Scenario: The broker never strips or overwrites the id
- **GIVEN** a message with a correlation id set by the producer
- **WHEN** it is fanned out to multiple subscriptions
- **THEN** every delivery carries the original id — the broker does not replace it with one of its own

#### Scenario: Edge case — a message published without a correlation id delivers empty
- **GIVEN** a producer publishes with no correlation id
- **WHEN** a consumer receives the message
- **THEN** `Message.correlation_id` is empty (the broker minted nothing) — the consumer decides whether to mint

### Requirement: Correlate async broker work by the message id

The broker SHALL log its off-request work on a message — the delivery projection, ack handling, and
redelivery on ack-deadline expiry — under the message's correlation id, set in the MDC and propagated
across the async boundary, so that work joins the originating request's trace rather than appearing
uncorrelated.

#### Scenario: A delivery log line carries the message's correlation id
- **GIVEN** a message with `correlation_id = req-42`
- **WHEN** the delivery projection delivers it to a subscription
- **THEN** the projection's log line for that delivery carries `correlationId=req-42`

#### Scenario: No sensitive data is logged
- **GIVEN** any correlated log line (even at TRACE)
- **WHEN** it is emitted
- **THEN** it contains no message payload, token, or secret — only the correlation id and non-sensitive fields
