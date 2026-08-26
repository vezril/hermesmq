# js-client Specification

## Purpose

Define the JavaScript client library — a typed, zero-dependency ESM API over the REST endpoints (global fetch, Node 18+ or browser), packaged under clients/js.

## Requirements

### Requirement: Typed JavaScript client surface

The JavaScript client SHALL provide an ESM `HermesClient` class using only the global `fetch` (zero runtime dependencies, Node 18+ or browser) covering the full REST surface — `createTopic`, `getTopic`, `updateTopic`, `deleteTopic`, `listTopics`, `publish`, `createSubscription`, `deleteSubscription`, `listSubscriptions`, `pull`, `ack`, `modifyAckDeadline`, and `health` — with TypeScript type declarations shipped alongside.

#### Scenario: Publish and pull round-trip
- **WHEN** `publish("orders", payload, {attributes})` resolves and `pull("orders-sub", {max: 10})` is called
- **THEN** publish resolves to `{messageId, deduplicated}` and pull resolves to messages carrying `ackId`, `payload`, `attributes`, and `publishTime`

#### Scenario: Publish options are forwarded
- **WHEN** `publish` is called with `ttlSeconds`, `idempotencyKey`, and `producerId` options
- **THEN** the request body carries those fields and a deduplicated publish resolves with `deduplicated: true`

#### Scenario: Named consumer on pull
- **WHEN** `pull` is called with a `consumerId` option
- **THEN** the pull request body carries `consumerId`

### Requirement: JavaScript client error model

The JavaScript client SHALL reject with a single `HermesClientError` (carrying `status` and the response body) for non-2xx responses, except `getTopic` on 404 which SHALL resolve to `null`.

#### Scenario: Conflict rejects with a typed error
- **WHEN** `createSubscription` hits a reserved id and the broker returns 409
- **THEN** the promise rejects with `HermesClientError` whose `status` is 409

#### Scenario: Missing topic reads as absent
- **WHEN** `getTopic("nope")` receives a 404
- **THEN** the promise resolves to `null`

### Requirement: JavaScript client auth headers

The JavaScript client SHALL send `Authorization: Bearer <token>` when constructed with a token and `X-API-Key` when constructed with an api key, and SHALL send neither by default.

#### Scenario: Token attached when configured
- **WHEN** the client is constructed with `{token: "t1"}` and any method is called
- **THEN** the outgoing request carries `Authorization: Bearer t1`

### Requirement: JavaScript client packaging

The JavaScript client SHALL live under `clients/js/` as an ESM package with `.d.ts` types and a vitest suite that runs against an in-process `node:http` stub server, and its README SHALL document both git-based install and vendoring.

#### Scenario: Test suite is hermetic
- **WHEN** the vitest suite runs in `clients/js/` with no broker available
- **THEN** the suite passes using the in-process stub server
