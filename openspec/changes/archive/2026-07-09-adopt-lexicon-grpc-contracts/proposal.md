## Why

HermesMQ's gRPC contract (`hermes.proto`: `TopicAdminService` + `PubSubService`) lives inside this repo, and every Hermes gRPC client carries its own view of it — the same drift risk the constellation's async messages and Apollo's API had. **Lexicon** is the single source of truth for every wire contract; its `add-hermes-grpc-contract` change moves Hermes's `.proto` in and publishes generated stubs (`io.codex %% lexicon-hermes-grpc`, package preserved). This change is the **HermesMQ-side half**: the server stops owning the `.proto` and generates its service from the pinned Lexicon artifact, so server and clients can no longer disagree on the API shape. Mirrors Apollo's `adopt-lexicon-grpc-contracts`.

## What Changes

- **Remove** `server/src/main/protobuf/hermes.proto` — the Hermes gRPC definition now lives in Lexicon.
- **Depend** on the pinned `io.codex %% lexicon-hermes-grpc` artifact (GitHub Packages), which carries the generated `TopicAdminServicePowerApi`/`PubSubServicePowerApi` traits, handlers, messages, and clients in the **preserved** `me.cference.hermesmq.grpc` package — so `PubSubGrpcService`/`TopicAdminGrpcService`/`GrpcServer`/tests need **no import changes**.
- **Reconfigure the build**: add the GitHub Packages resolver + `GITHUB_TOKEN` credentials; **remove `PekkoGrpcPlugin`** from the `server` module entirely (no other local proto exists), so no gRPC codegen runs here.
- **Bump the Pekko stack to `1.2.0`** (http/discovery/grpc-runtime accordingly): the Lexicon jar is built against Pekko `1.2.0` and Pekko forbids a mixed-version classpath, so the whole repo must move off `1.1.3`. This is the largest part of the change and is mechanical.
- **Preserve the API surface exactly** — a move, not a redesign. The existing gRPC suites (`GrpcAuthSpec`, `PubSubGrpcServiceSpec`, `MessageStreamSpec`, `GrpcApiIntegrationSpec`) pass unchanged against the Lexicon stubs — the migration's safety net.

## Capabilities

### New Capabilities
<!-- none — this is a provenance/build migration, not new behaviour. -->

### Modified Capabilities
- `grpc-api`: adds a requirement that the gRPC service contract is **sourced from the Lexicon** (single versioned source of truth, generated stubs, pinned version) rather than a repo-local `.proto`. The RPC methods, messages, and status mappings are unchanged.

## Impact

- **Build:** `build.sbt` — add the GitHub Packages resolver + credentials, the pinned `lexicon-hermes-grpc` dependency, and the Pekko `1.2.0` bump (`pekkoVersion`/`pekkoHttpVersion`/JDBC/projection alignment); `project/plugins.sbt` drops `pekko-grpc-sbt-plugin`; `server` drops `PekkoGrpcPlugin` and the `-Wconf:src=.*src_managed.*` codegen carve-out.
- **Code:** delete `hermes.proto`; the 14 files importing `me.cference.hermesmq.grpc.*` are unchanged (package preserved). Verify the Pekko `1.2.0` bump doesn't shift any HTTP/2 / gRPC / persistence API (compile + full suite catch it).
- **Dependencies:** adds the Lexicon Scala artifact (pinned SemVer) from GitHub Packages; requires `GITHUB_TOKEN` (read:packages) in local + CI builds.
- **Cross-repo sequencing (gating):** **blocked until Lexicon publishes** `lexicon-hermes-grpc` at a pinned version (its `add-hermes-grpc-contract`). Apply order: Lexicon hosts + publishes `vX.Y.Z` → this change pins and adopts it. The API surface is identical, so it is a coordinated move, not a break.
- **Out of scope:** changing the gRPC API surface; the Python Hermes client; HermesMQ's REST API (stays local); the Lexicon's own codegen/publish work.
