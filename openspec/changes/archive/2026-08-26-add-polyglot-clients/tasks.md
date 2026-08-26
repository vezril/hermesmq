# Tasks — add-polyglot-clients

## 1. Scala client parity (`client/` module)

- [x] 1.1 Publish options: `ttlSeconds`/`idempotencyKey`/`producerId` params; return `PublishResult(messageId, deduplicated)` (tests first: request-body shape + dedup flag surfaced)
- [x] 1.2 `deleteSubscription` (204 ok; non-204 → `HermesClientException`) + tests
- [x] 1.3 `pull` gains optional `consumerId`; `ack` returns `AckResult(acknowledged, unknown)`; new `modifyAckDeadline` returning `ModifyAckDeadlineResult(modified, unknown)` + tests
- [x] 1.4 `listTopics` / `listSubscriptions` (stats listings) + `health` + tests
- [x] 1.5 Optional auth (bearer token / api key constructor params → headers on every request) + tests
- [x] 1.6 Full sbt gate green (`compile`, `scalafixAll --check`, `test`)

## 2. Python client (`clients/python/`, new)

- [x] 2.1 Scaffold: uv project `hermesmq-client` (py3.12, deps: httpx; dev: pytest, ruff, mypy), package `hermesmq_client`
- [x] 2.2 Tests first: stub-server pytest suite covering surface, request shapes, error model (409 → `HermesClientError`, get_topic 404 → None), auth headers, hermetic run
- [x] 2.3 Implement `HermesClient` (sync, httpx) + dataclasses (`PublishResult`, `ReceivedMessage`, `AckResult`, `ModifyAckDeadlineResult`, `TopicInfo`, listings)
- [x] 2.4 Gate green: pytest + ruff + mypy
- [x] 2.5 README: quick-start + git install (`pip install "git+https://github.com/vezril/hermesmq#subdirectory=clients/python"`)

## 3. JavaScript client (`clients/js/`, new)

- [x] 3.1 Scaffold: ESM package `@hermesmq/client` (zero runtime deps; dev: vitest), `index.js` + `index.d.ts`
- [x] 3.2 Tests first: vitest suite against a `node:http` stub covering surface, request shapes, error model (409 rejects typed, getTopic 404 → null), auth headers, hermetic run
- [x] 3.3 Implement `HermesClient` (global fetch) + `HermesClientError`; hand-written `.d.ts`
- [x] 3.4 Gate green: vitest
- [x] 3.5 README: quick-start + install options (git / vendoring)

## 4. Cross-cutting

- [x] 4.1 `clients/README.md`: language matrix + conformance checklist (surface, error model, auth) + pointers
- [x] 4.2 CI: add `python-client` (uv sync + pytest/ruff/mypy) and `js-client` (npm ci + vitest) jobs to ci.yml (non-required initially), path-filtered to their dirs
- [x] 4.3 Repo README: mention the three clients + link `clients/README.md`
