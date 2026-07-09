# Tasks: adopt-lexicon-grpc-contracts

**Gating:** the consumer half of Lexicon's `add-hermes-grpc-contract`. Do **not** start section 3
until Lexicon has published `io.codex %% lexicon-hermes-grpc` (server power APIs, `me.cference.hermesmq.grpc`
package preserved). A straight move + a Pekko version bump — no API redesign.

## 1. Upstream precondition

- [x] 1.1 Confirm Lexicon has published `io.codex:lexicon-hermes-grpc_3:<version>` to GitHub Packages, with the `TopicAdminServicePowerApi`/`PubSubServicePowerApi` traits generated via `server_power_apis` and the `me.cference.hermesmq.grpc` package preserved; record the exact pinned coordinates

## 2. Pekko 1.2.0 bump (decoupled, verify first)

- [x] 2.1 Bump `pekkoVersion 1.1.3 → 1.2.0`, `pekkoHttpVersion 1.1.0 → 1.2.0`, and align `pekko-persistence-jdbc`/`pekko-projection`/`pekko-discovery`/testkits to their `1.2.x` versions in `build.sbt`
- [x] 2.2 **Verify** the bump in isolation (before adopting the jar): full `sbt test` + `-Dit=true` Postgres IT + Docker stage green; fix any `1.2.0` API deltas (persistence/projection/http2/grpc-runtime) as real changes, not workarounds

## 3. Build wiring

- [x] 3.1 Add the GitHub Packages resolver + `GITHUB_TOKEN` credentials for `the-lexicon`, and the pinned `io.codex %% lexicon-hermes-grpc` dependency to the `server` module
- [x] 3.2 Add `GITHUB_TOKEN` (read:packages) as a CI secret; document the local PAT in the README build section

## 4. Remove the local contract

- [x] 4.1 Delete `server/src/main/protobuf/hermes.proto`
- [x] 4.2 Remove `PekkoGrpcPlugin` from the `server` module, drop `pekko-grpc-sbt-plugin` from `project/plugins.sbt`, and remove the `server_power_apis` setting + the `-Wconf:src=.*src_managed.*:silent` codegen carve-out (no local proto remains)

## 5. Verify the move (behaviour-identical)

- [x] 5.1 Compile `server` against the Lexicon artifact; the `me.cference.hermesmq.grpc.*` imports in the 14 files SHOULD NOT need changing (package preserved) — fix only build wiring
- [x] 5.2 **Verify:** full unit suite + `-Dit=true` Postgres IT pass unchanged — `GrpcAuthSpec`, `PubSubGrpcServiceSpec`, `MessageStreamSpec`, `GrpcApiIntegrationSpec` prove the surface is identical; `scalafmt`/`-Werror` clean
- [x] 5.3 **Verify:** build the Docker image and run a gRPC smoke (`grpcurl` a method + health/readiness) to confirm the running server serves the same surface from the Lexicon stubs

## 6. Docs

- [x] 6.1 README: note the gRPC contract is defined in the Lexicon (link), the build pins a Lexicon version + the `GITHUB_TOKEN` requirement; remove references to the local `hermes.proto`
