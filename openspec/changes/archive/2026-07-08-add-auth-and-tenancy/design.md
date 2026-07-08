## Context

REST routes are assembled in `Main` (`TopicAdminRoutes ~ PubSubRoutes ~ ObservabilityRoutes`) with `HealthRoutes` prepended by `HttpServer`; all delegate to `TopicService`/`SubscriptionService` (sharded entity fronts) keyed by raw `TopicId`/`SubscriptionId`. gRPC handlers implement generated service traits whose methods take only the request (no metadata). Ids flow unqualified straight to persistence ids (`Topic|<id>`, `Subscription|<id>`) and every read model. There is no notion of a caller. This change inserts an auth+tenant boundary in front of both surfaces without touching the domain.

## Goals / Non-Goals

**Goals:**
- Authenticate REST/gRPC requests from a configured, hashed API key → `Principal(tenantId, scopes)`.
- Isolate tenants by transparently qualifying resource ids per tenant, so cross-tenant access/listing is impossible.
- Coarse authorization (admin scope for topic admin ops).
- Keep `/health*` and `/metrics` open; fail-fast config; a default-tenant/disabled mode for dev and journal compatibility.
- TDD; no change to `decide`/`evolve`, delivery, retention, or event/stat schemas.

**Non-Goals:**
- External IdP / JWT / OIDC, mTLS, rate limiting, per-message ACLs, key-rotation APIs, fine-grained per-resource permissions, tenant-scoped `/metrics`.

## Decisions

- **Keys as salted hashes.** Config holds `{ tenant, salt, hash, scopes }` per key, where `hash = base64(SHA-256(salt-bytes ++ token-bytes))`. The `Authenticator` recomputes the hash for a presented token against each key and compares **constant-time** (`MessageDigest.isEqual`); a match yields that key's `Principal`. Tokens are never stored or logged. A README snippet documents generating salt+hash.
- **`Principal(tenantId: TenantId, scopes: Set[String])`.** Scopes are coarse; `admin` gates topic create/update/delete. Publish/consume/list require only a valid principal (its own tenant).
- **Tenant namespacing = isolation.** A `TenantScope` helper qualifies an external id to an internal one: `qualify(tenant, id)` = `id` for the **default tenant** (empty prefix → unqualified, so existing single-tenant journals stay valid) and `s"$tenant~$id"` otherwise; `unqualify`/`belongsTo` invert it for listings. The separator `~` is reserved: external ids and tenant ids containing it are rejected (`400`/`INVALID_ARGUMENT`), so a tenant cannot forge another's namespace. All entity and read-model access uses the qualified id, so isolation holds everywhere (delivery index, leases, stats) without per-call filtering — except listings, which filter read-model rows by `belongsTo(tenant, _)` and return unqualified ids.
- **REST boundary.** An `authenticate` directive extracts `Authorization: Bearer <token>` (or `X-API-Key`), authenticates (else `401` with `WWW-Authenticate`), and provides the `Principal` to the `/v1` routes; those qualify ids before calling the services and unqualify on the way out. `/health*` and `/metrics` are assembled **outside** the directive (so `ObservabilityRoutes` splits into a public `metrics` route and authenticated `listings`).
- **gRPC boundary.** Enable pekko-grpc server power APIs (`pekkoGrpcCodeGeneratorSettings += "server_power_apis"`) so the services implement the metadata-aware `*PowerApi` traits; each method reads the `authorization`/`x-api-key` metadata entry, authenticates (else fail `UNAUTHENTICATED`), then qualifies ids by tenant. Reuses the same `Authenticator`/`TenantScope`.
- **Disabled/default mode.** `hermesmq.auth.enabled = false` (default) skips authentication and treats every request as the configured `default-tenant` (empty-prefix → unqualified ids) — preserving today's behavior and journals. Enabling auth with no keys is a fail-fast config error.

## Risks / Trade-offs

- **gRPC power-API build change.** Turning on `server_power_apis` regenerates handlers and requires the two gRPC services to move to the `*PowerApi` traits; validated during apply (codegen + compile). If it proves troublesome, a transport-layer wrapper that authenticates and rejects (but cannot inject tenant into the non-power handler) is a weaker fallback — noted, not preferred.
- **Separator injection.** Namespacing by string prefix is only safe if the separator is truly reserved; the `TenantScope` guard rejects any id/tenant containing `~`. This is the load-bearing isolation invariant and is covered by explicit edge-case tests.
- **`/metrics` is open.** Backlog/throughput per tenant is scrapeable without auth — acceptable for an internal Prometheus target behind the deployment perimeter, and called out in the README (gating it is a future option).
- **Config ergonomics.** Salted-hash-in-config is dependency-light but manual; the README documents generation. JWT/OIDC remains a documented future direction.
- **Default-tenant compatibility vs. true isolation.** The empty-prefix default tenant keeps old journals working but means "default" shares the unprefixed namespace; a deployment that wants strict isolation everywhere sets `enabled = true` and issues named-tenant keys (no unprefixed access). Documented.
