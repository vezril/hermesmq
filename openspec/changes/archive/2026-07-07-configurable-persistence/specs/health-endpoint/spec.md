## MODIFIED Requirements

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
