## Why

HermesMQ is now feature-complete against the architecture, but every endpoint is wide open: anyone who can reach the port can create/delete topics, publish, and read any subscription's backlog. Before it can run anywhere shared, it needs **authentication** (who is calling) and **tenant isolation** (callers can only see their own topics/subscriptions). This is the production-hardening step that makes a shared deployment safe, and it must not disturb the delivery correctness already in place.

## What Changes

- **Authenticate** every REST and gRPC request from a bearer token / API key. Keys are configured as salted hashes (never plaintext) and resolve to a `Principal(tenantId, scopes)`. Missing/invalid credentials are rejected — REST `401`, gRPC `UNAUTHENTICATED`.
- **Isolate tenants** by transparently namespacing resource ids at the API boundary: the external `topicId`/`subscriptionId` a tenant uses is qualified to a tenant-scoped internal id, so a tenant can only act on and list its own resources — cross-tenant access is impossible by construction. The domain `decide`/`evolve`, delivery, retention, and event schemas are **unchanged**.
- **Coarse authorization:** admin operations (create/update/delete topic) require an `admin` scope; publish/consume require any authenticated principal.
- Keep **`/health*` and `/metrics` unauthenticated** so liveness/readiness probes and Prometheus scraping keep working (metrics can be separately gated later).
- **Configurable and fail-fast**, with an auth-disabled/default-tenant mode for local dev and backward compatibility with existing single-tenant journals.

## Capabilities

### New Capabilities
- `auth-tenancy`: Authentication of REST/gRPC requests via configured salted-hash API keys resolving to a tenant principal, transparent per-tenant namespacing of resource ids for isolation, coarse scope-based authorization, and unauthenticated health/metrics probes — configurable and fail-fast.

### Modified Capabilities
<!-- None at the spec level: existing topic-admin, pub/sub, and observability behavior is unchanged for an authorized caller. Auth/tenancy is a boundary layer, not a change to those requirements. -->

## Impact

- **Config:** new `hermesmq.auth` block — `enabled` (default `false` for dev/compat), a list of keys `{ tenant, salt, hash, scopes }`, and a `default-tenant` used when auth is disabled. New `AuthConfig` parser with fail-fast validation (`HERMESMQ_AUTH_*`).
- **Auth core:** `Principal(tenantId, scopes)`, an `Authenticator` (constant-time salted-hash check `token → Option[Principal]`), and a `TenantScope` id-namespacing helper (`qualify`/`unqualify`, rejecting ids that contain the separator).
- **REST:** an authentication directive on the `/v1` routes producing a `Principal`; route handlers qualify ids by tenant and strip the prefix from listing responses. Health/metrics routes stay open.
- **gRPC:** a metadata-aware handler (pekko-grpc power API) reading the `authorization`/`x-api-key` metadata, authenticating, and qualifying ids by tenant; unauthenticated calls fail `UNAUTHENTICATED`.
- **Docs:** README auth/tenancy section (key generation, config, curl/grpc examples, probe exemptions) and the migration note (default tenant = unprefixed ids, so existing journals stay valid); JWT/OIDC noted as a future option.
- **Out of scope:** external IdP / JWT / OIDC, per-message ACLs, rate limiting, mTLS, dynamic key rotation APIs, and fine-grained per-resource permissions.
