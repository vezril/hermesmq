# auth-tenancy Specification

## Purpose

Authentication and multi-tenant isolation for the REST and gRPC surfaces: hashed API
keys resolving to a tenant principal, transparent per-tenant namespacing of resource ids,
coarse scope-based authorization, and open health/metrics probes — configurable and
fail-fast. The domain, delivery, retention, and event schemas are unchanged.

## Requirements

### Requirement: Authenticate API requests

The service SHALL authenticate every REST and gRPC request (when auth is enabled) from a
bearer token / API key presented in the `Authorization` header (or `X-API-Key` / gRPC
metadata), validated against configured keys stored as salted hashes and compared in
constant time. A valid key SHALL resolve to a `Principal(tenantId, scopes)`; a missing or
invalid credential SHALL be rejected with REST `401` (with `WWW-Authenticate`) or gRPC
`UNAUTHENTICATED`, and tokens SHALL never be logged or stored in plaintext.

#### Scenario: A valid key authenticates and resolves a principal
- **GIVEN** a configured key whose salted hash matches token `T`, mapped to tenant `acme`
- **WHEN** a request presents `Authorization: Bearer T`
- **THEN** the request is authenticated as principal `acme` and proceeds

#### Scenario: A missing credential is rejected
- **GIVEN** auth is enabled
- **WHEN** a REST request arrives with no `Authorization` header
- **THEN** the response is `401` (and the equivalent gRPC call fails `UNAUTHENTICATED`)

#### Scenario: Edge case — an invalid token is rejected
- **GIVEN** auth is enabled
- **WHEN** a request presents a token that matches no configured key's hash
- **THEN** it is rejected `401`/`UNAUTHENTICATED` and no operation is performed

#### Scenario: Edge case — a wrong token is not distinguishable by timing
- **GIVEN** two tokens that differ early vs late in their bytes
- **WHEN** each is validated against a configured key
- **THEN** the comparison is constant-time (both rejected without leaking match length)

### Requirement: Isolate tenants by namespacing resource ids

The service SHALL qualify every external resource id (`topicId`, `subscriptionId`) to a
tenant-scoped internal id at the API boundary, so a principal can only create, access, and
list resources within its own tenant — a resource created by one tenant SHALL be invisible
and inaccessible to another, even when they use the same external id. The qualification
SHALL be transparent (responses show the external id) and SHALL NOT change the domain
`decide`/`evolve`, delivery, or event schemas.

#### Scenario: Two tenants using the same external id get isolated resources
- **GIVEN** tenants `acme` and `beta` each authenticated
- **WHEN** both create topic `orders` and each publishes
- **THEN** each sees only its own `orders` topic and its own messages; neither can read the other's

#### Scenario: Listings show only the caller's tenant with external ids
- **GIVEN** tenant `acme` with topics/subscriptions and other tenants with their own
- **WHEN** `acme` calls `GET /v1/topics` or `GET /v1/subscriptions`
- **THEN** only `acme`'s resources are returned, each with its unqualified external id

#### Scenario: Edge case — accessing another tenant's id behaves as not-found
- **GIVEN** tenant `beta` has topic `orders` and tenant `acme` does not
- **WHEN** `acme` gets/publishes to `orders`
- **THEN** `acme` sees its own (absent) `orders` — never `beta`'s — (e.g. `404`/create-anew), never `beta`'s data

#### Scenario: Edge case — an id containing the reserved separator is rejected
- **GIVEN** the tenant-namespace separator is reserved
- **WHEN** a request uses an external id (or a tenant id is configured) containing that separator
- **THEN** it is rejected (`400` / `INVALID_ARGUMENT`), so no tenant can forge another's namespace

### Requirement: Coarse scope-based authorization

The service SHALL gate topic administration (create, update, delete) behind an `admin`
scope on the principal; publish, consume (pull/ack/modify), and listing SHALL require only
a valid principal for the caller's own tenant. A principal lacking a required scope SHALL
be rejected with REST `403` / gRPC `PERMISSION_DENIED`.

#### Scenario: An admin-scoped principal can manage topics
- **GIVEN** a principal with scope `admin`
- **WHEN** it creates or deletes a topic
- **THEN** the operation succeeds

#### Scenario: A data-plane principal can publish and consume but not administer
- **GIVEN** a principal without the `admin` scope
- **WHEN** it publishes and pulls
- **THEN** those succeed

#### Scenario: Edge case — a non-admin principal is denied topic administration
- **GIVEN** a principal without the `admin` scope
- **WHEN** it attempts to create or delete a topic
- **THEN** the request is rejected `403` / `PERMISSION_DENIED` and no event is persisted

### Requirement: Open probes and configurable, fail-fast auth

The service SHALL leave `/health*` and `/metrics` unauthenticated for liveness/readiness
probes and Prometheus scraping. Authentication SHALL be configurable via HOCON with
environment overrides: an `enabled` flag (default off), a set of `{tenant, salt, hash,
scopes}` keys, and a `default-tenant` applied when auth is disabled (empty-prefix,
unqualified ids — preserving existing single-tenant journals). Enabling auth with no keys
SHALL fail fast at startup with a non-zero exit.

#### Scenario: Health and metrics require no credential
- **GIVEN** auth is enabled
- **WHEN** `/health`, `/health/ready`, and `/metrics` are requested without a token
- **THEN** each responds normally (`200`/`503` per readiness), never `401`

#### Scenario: Disabled auth maps requests to the default tenant
- **GIVEN** `hermesmq.auth.enabled = false`
- **WHEN** any request arrives without a credential
- **THEN** it is served as the configured default tenant with unqualified ids (today's behavior)

#### Scenario: Edge case — enabling auth with no keys fails fast
- **GIVEN** `hermesmq.auth.enabled = true` and no keys configured
- **WHEN** the service starts
- **THEN** it exits non-zero with a clear configuration error

#### Scenario: Edge case — a malformed key entry fails fast
- **GIVEN** a key entry missing its tenant or hash
- **WHEN** the config is parsed
- **THEN** it yields a configuration error rather than starting with a broken key
