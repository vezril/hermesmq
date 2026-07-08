# health-endpoint Specification

## Purpose

Define the bootable Pekko HTTP service and its liveness/readiness health endpoints: a configurable HTTP binding, JSON health payloads, distinct liveness vs readiness semantics, and graceful startup/shutdown — with no persistence dependency.

## Requirements

### Requirement: Liveness health endpoint

The service SHALL expose `GET /health` returning `200 OK` with a JSON body containing at least `status`, `service`, and `version` fields, indicating the process is alive. The endpoint SHALL NOT depend on any external system (there is no persistence in this feature).

#### Scenario: Health returns 200 with status body
- **GIVEN** the service is running
- **WHEN** a client sends `GET /health`
- **THEN** the response is `200 OK` with `Content-Type: application/json` and a body where `status` is `"UP"`, `service` is `"hermesmq"`, and `version` is present

#### Scenario: Edge case — health responds under HEAD without a body
- **GIVEN** the service is running
- **WHEN** a client sends `HEAD /health`
- **THEN** the response status is `200` with no body, so lightweight probes that use HEAD succeed

#### Scenario: Edge case — unknown path returns 404, not 200
- **GIVEN** the service is running
- **WHEN** a client sends `GET /healthz` or any unmapped path
- **THEN** the response is `404 Not Found`, so probes cannot be fooled by a catch-all route

### Requirement: Readiness endpoint reflects bind state

The service SHALL expose `GET /health/ready` returning `200` only once the HTTP server binding is established AND the persistence backend is reachable, and `503 Service Unavailable` otherwise. Liveness (`GET /health`) SHALL remain independent of persistence.

#### Scenario: Ready returns 200 after binding
- **GIVEN** the HTTP server has successfully bound to its port and persistence is reachable
- **WHEN** a client sends `GET /health/ready`
- **THEN** the response is `200 OK`

#### Scenario: Edge case — readiness distinct from liveness during shutdown
- **GIVEN** the service has begun graceful shutdown (binding unbinding)
- **WHEN** a readiness probe is sent
- **THEN** readiness reports `503` while liveness may still succeed, so an orchestrator stops routing traffic before the process exits

#### Scenario: Edge case — readiness reports 503 when persistence is unreachable
- **GIVEN** the HTTP server is bound but the configured database is unreachable
- **WHEN** a readiness probe is sent
- **THEN** readiness reports `503` while liveness (`GET /health`) still returns `200`, so traffic is not routed to a node that cannot durably persist

### Requirement: Configurable HTTP binding

The service SHALL read its HTTP host and port from configuration (`application.conf`) with environment-variable overrides (`HERMESMQ_HTTP_HOST`, `HERMESMQ_HTTP_PORT`), defaulting to `0.0.0.0:8080`. Configuration loading SHALL be typed and fail fast on invalid values.

#### Scenario: Port override via environment variable
- **GIVEN** `HERMESMQ_HTTP_PORT=9090` is set in the environment
- **WHEN** the service starts
- **THEN** it binds to port `9090` and `GET /health` succeeds there

#### Scenario: Edge case — invalid port fails fast at startup
- **GIVEN** `HERMESMQ_HTTP_PORT=not-a-number` (or a value outside 1–65535)
- **WHEN** the service starts
- **THEN** startup aborts with a clear configuration error and a non-zero exit code, rather than binding to a wrong or default port silently

#### Scenario: Edge case — port already in use surfaces a clear error
- **GIVEN** the configured port is already bound by another process
- **WHEN** the service attempts to start
- **THEN** the bind failure is logged clearly and the process exits non-zero instead of hanging

### Requirement: Graceful startup and shutdown

The service SHALL start an actor system and HTTP binding on boot, and on receiving a termination signal SHALL unbind the HTTP server and terminate the actor system, releasing the port.

#### Scenario: Clean shutdown releases the port
- **GIVEN** the service is running and bound to a port
- **WHEN** the process receives SIGTERM
- **THEN** the HTTP server unbinds, the actor system terminates, and the port is free for immediate rebinding
