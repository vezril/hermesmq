# python-client Delta Specification

## ADDED Requirements

### Requirement: Typed Python client surface

The Python 3 client SHALL provide a synchronous `HermesClient` class covering the full REST surface — `create_topic`, `get_topic`, `update_topic`, `delete_topic`, `list_topics`, `publish`, `create_subscription`, `delete_subscription`, `list_subscriptions`, `pull`, `ack`, `modify_ack_deadline`, and `health` — with results as typed dataclasses.

#### Scenario: Publish and pull round-trip
- **WHEN** `publish("orders", payload, attributes)` is called and the broker accepts it, then `pull("orders-sub", max=10)` returns delivered messages
- **THEN** publish returns a `PublishResult(message_id, deduplicated)` and pull returns `ReceivedMessage` items carrying `ack_id`, `payload`, `attributes`, and `publish_time`

#### Scenario: Publish options are forwarded
- **WHEN** `publish` is called with `ttl_seconds`, `idempotency_key`, and `producer_id`
- **THEN** the request body carries `ttlSeconds`, `idempotencyKey`, and `producerId`, and a deduplicated publish returns `deduplicated=True`

#### Scenario: Delete a subscription
- **WHEN** `delete_subscription("old-sub")` is called and the broker returns 204
- **THEN** the call returns normally, and a second delete (broker 404) raises the client error carrying status 404

### Requirement: Python client error model

The Python client SHALL raise a single `HermesClientError` (carrying the HTTP status code and response body) for non-2xx responses, except `get_topic` on 404 which SHALL return `None`.

#### Scenario: Conflict surfaces as a typed error
- **WHEN** `create_topic` hits an already-existing id and the broker returns 409
- **THEN** `HermesClientError` is raised with `status == 409` and the broker's body attached

#### Scenario: Missing topic reads as absent
- **WHEN** `get_topic("nope")` receives a 404
- **THEN** the method returns `None` without raising

### Requirement: Python client auth headers

The Python client SHALL send `Authorization: Bearer <token>` when constructed with a token and `X-API-Key: <key>` when constructed with an api key, and SHALL send neither by default.

#### Scenario: Token attached when configured
- **WHEN** the client is constructed with `token="t1"` and any method is called
- **THEN** the outgoing request carries `Authorization: Bearer t1`

### Requirement: Python client packaging

The Python client SHALL be an installable package under `clients/python/` (py3.12, uv-managed, `httpx` as its only runtime dependency) with a pytest suite that runs against an in-process stub server and no live broker.

#### Scenario: Test suite is hermetic
- **WHEN** `pytest` runs in `clients/python/` with no broker available
- **THEN** the suite passes using the in-process stub server
