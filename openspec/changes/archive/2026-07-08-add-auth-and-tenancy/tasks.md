# Tasks: add-auth-and-tenancy

TDD throughout — for each behavior write the failing test first (Red), implement to green,
then refactor. Run `sbt test` after each step. Order is dependency-first: auth core →
tenant scoping → config → REST boundary → gRPC boundary → wiring → integration & docs.
The domain `decide`/`evolve`, delivery, retention, and event/stat schemas stay unchanged.

## 1. Auth core (principal + authenticator)

- [x] 1.1 (test) Salted-hash authenticator: a token whose `SHA-256(salt ++ token)` matches a configured key resolves to its `Principal(tenant, scopes)`; a non-matching token resolves to `None`
- [x] 1.2 (test) Edge: comparison is constant-time (uses `MessageDigest.isEqual`); an empty/blank token resolves to `None`
- [x] 1.3 (impl) `TenantId`, `Principal`, `AuthKey`, and `Authenticator` (constant-time salted-hash check); a helper to compute a salt+hash for docs/tests

## 2. Tenant scoping (id namespacing)

- [x] 2.1 (test) `qualify(default, id) == id`; `qualify(tenant, id) == s"$tenant~$id"`; `unqualify` inverts; `belongsTo` is true only for the owning tenant
- [x] 2.2 (test) Edge: an id or tenant containing the reserved separator is rejected; default-tenant listings exclude other tenants' (prefixed) ids
- [x] 2.3 (impl) `TenantScope` (qualify / unqualify / belongsTo, reserved-separator guard)

## 3. Configuration (fail-fast)

- [x] 3.1 (test) Defaults: `enabled = false`, `default-tenant` present; keys parse into `AuthKey`s with tenant/salt/hash/scopes
- [x] 3.2 (test) Edge: `enabled = true` with no keys fails fast; a malformed key entry (missing tenant/hash) yields a `ConfigError`
- [x] 3.3 (impl) `AuthConfig` parser + `hermesmq.auth` block in `application.conf` (`HERMESMQ_AUTH_*`)

## 4. REST boundary

- [x] 4.1 (test) An `authenticate` directive: valid token → principal; missing/invalid → `401` with `WWW-Authenticate`; disabled → default tenant
- [x] 4.2 (test) Tenanted routes qualify ids and unqualify in responses; two tenants with the same external id are isolated; a non-admin is `403` on topic admin; reserved-separator id → `400`
- [x] 4.3 (impl) Authentication directive + a tenant-qualifying wrapper over `TopicService`/`SubscriptionService`; split `ObservabilityRoutes` into public `metrics` and authenticated `listings` (filtered + unqualified by tenant)
- [x] 4.4 (test) `/health*` and `/metrics` require no credential even when auth is enabled

## 5. gRPC boundary

- [x] 5.1 (impl) Enable pekko-grpc server power APIs (`pekkoGrpcCodeGeneratorSettings += "server_power_apis"`); verify codegen + compile
- [x] 5.2 (test) gRPC handlers authenticate from metadata (`authorization`/`x-api-key`): valid → tenant-scoped op; missing/invalid → `UNAUTHENTICATED`; non-admin topic admin → `PERMISSION_DENIED`
- [x] 5.3 (impl) Move `TopicAdminGrpcService`/`PubSubGrpcService` to the `*PowerApi` traits; authenticate + qualify ids by tenant, reusing `Authenticator`/`TenantScope`

## 6. Runtime wiring

- [x] 6.1 (impl) Build the `Authenticator` from `AuthConfig` in `Main`; apply the REST directive to `/v1` routes (health/metrics outside it); bind the gRPC power-API handlers
- [x] 6.2 (impl) Thread the default-tenant/disabled mode through both surfaces; confirm existing behavior is unchanged when auth is off

## 7. Integration & docs

- [x] 7.1 (test) End-to-end (testcontainers Postgres): with auth on, tenant `acme` and tenant `beta` each create `orders`, publish, and consume — each sees only its own data; an unauthenticated call is `401`; `/metrics` is open
- [x] 7.2 (docs) README: auth/tenancy section (generating salt+hash, config, bearer/`X-API-Key` + gRPC metadata examples, probe exemptions), capability row (✅), migration note (default tenant = unprefixed), JWT/OIDC as future
- [x] 7.3 (refactor) Final pass; `sbt test` green; `openspec validate add-auth-and-tenancy --strict` clean
