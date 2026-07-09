## Context

HermesMQ generates its gRPC locally: `server/src/main/protobuf/hermes.proto` (`package hermesmq.v1`, `java_package me.cference.hermesmq.grpc`) → `PekkoGrpcPlugin` (`server_power_apis`, pekko-grpc `1.1.1`) → `TopicAdminServicePowerApi`/`PubSubServicePowerApi` traits, handlers, clients. `PubSubGrpcService`, `TopicAdminGrpcService`, the `*PowerApi` impls, `GrpcServer`, and the gRPC tests all import `me.cference.hermesmq.grpc.*`. Lexicon's `add-hermes-grpc-contract` hosts the same proto (package preserved) and publishes `io.codex %% lexicon-hermes-grpc` built against Pekko `1.2.0`. Apollo proved the consumer side: depend on the jar, delete the local proto, compile with zero source changes. HermesMQ differs in one way — it has **no other local proto**, so it can drop `PekkoGrpcPlugin` entirely; but it also runs Pekko `1.1.3`, so it must bump to `1.2.0` to consume the jar.

## Goals / Non-Goals

**Goals:**
- Source the Hermes gRPC stubs from the pinned Lexicon artifact; delete the local `.proto` and its codegen.
- Bump Pekko to `1.2.0` (mixed-version classpaths are forbidden) with no behaviour change.
- Prove the move is surface-identical: existing gRPC suites pass unchanged.

**Non-Goals:**
- Changing the gRPC API surface; the Python client; the REST surface; the Lexicon's producer work.

## Decisions

- **Depend on `io.codex %% lexicon-hermes-grpc` at a pinned version**, resolved from `maven.pkg.github.com/vezril/the-lexicon` with `GITHUB_TOKEN` credentials (mirrors the existing GitHub Packages publish config). Pin exactly; a mismatch is a build error.
- **Drop `PekkoGrpcPlugin` from `server`.** Unlike Apollo (which kept a vendored `grpc.health.v1` proto), HermesMQ has only `hermes.proto` — once deleted, there is nothing to generate, so `PekkoGrpcPlugin`, `pekko-grpc-sbt-plugin` (in `project/plugins.sbt`), the `server_power_apis` codegen setting, and the `-Wconf:src=.*src_managed.*:silent` carve-out all go.
- **Bump the whole Pekko stack to `1.2.0`.** `pekkoVersion 1.1.3 → 1.2.0`, `pekkoHttpVersion 1.1.0 → 1.2.0`, and align `pekko-persistence-jdbc`/`pekko-projection`/`pekko-discovery`/testkits to their `1.2.x`-compatible versions. Rationale: the Lexicon jar's transitive Pekko is `1.2.0`, and `ManifestInfo` fails a mixed-version classpath at startup. This is the load-bearing risk; the full unit + Postgres-IT suites are the check that no API shifted.
- **Imports don't move.** Because Lexicon preserves `me.cference.hermesmq.grpc`, the 14 importing files are untouched — the diff is `build.sbt`/`plugins.sbt`, the proto deletion, and any fallout from the Pekko bump.
- **Safety net = the existing gRPC suites.** `GrpcAuthSpec`, `PubSubGrpcServiceSpec`, `MessageStreamSpec`, and the in-process `GrpcApiIntegrationSpec` (real HTTP/2 with the generated client) must pass unchanged against the Lexicon stubs — that is the proof the surface is identical.

## Risks / Trade-offs

- **The Pekko `1.2.0` bump is the real work and the real risk.** It touches persistence-jdbc, projection, cluster-sharding, http, and grpc-runtime — any minor API change surfaces at compile or in the suites (e.g. a projection/streaming signature, an HTTP/2 setting). Mitigation: bump in one commit, lean on `-Werror` + the full suite (incl. `-Dit=true` Postgres) + Docker stage; treat any behavioural delta as a blocker, not a workaround.
- **Journal/serialization compatibility.** The Pekko bump must not change the journal or serializer wire format; the explicit spray-json serializer is ours (unaffected), and pekko-persistence-jdbc `1.2.x` keeps the same schema — verified by the recovery + Postgres-IT tests.
- **GitHub Packages auth in CI.** Resolving `lexicon-hermes-grpc` needs a `read:packages` token even for a public package (a Maven-on-GH-Packages quirk Apollo hit); add the CI secret and document the local PAT.
- **Gating & the empty window.** Until Lexicon publishes, this change cannot compile. Apply strictly after the Lexicon release; keep the pinned version explicit so the two repos stay coordinated.
- **Reversibility.** If the bump proves too costly, the fallback is to keep local codegen but still bump Pekko to `1.2.0` first (decoupling the two) — noted, not preferred; the point of the change is to stop owning the contract.
