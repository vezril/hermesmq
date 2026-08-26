# scala-client Delta Specification

## ADDED Requirements

### Requirement: Publish options and dedup result

The Scala client's `publish` SHALL accept optional `ttlSeconds`, `idempotencyKey`, and `producerId` parameters, forward them in the request body, and return a result carrying both the assigned message id and the broker's `deduplicated` flag.

#### Scenario: Deduplicated publish is surfaced
- **WHEN** `publish` is called twice with the same `idempotencyKey` on a dedup-enabled broker
- **THEN** the second call's result has `deduplicated == true` and carries the original message id

### Requirement: Subscription deletion from the client

The Scala client SHALL provide `deleteSubscription(subscriptionId)` completing normally on 204 and failing with the typed client error (carrying the status) on any other response.

#### Scenario: Delete then delete again
- **WHEN** `deleteSubscription` is called for an existing subscription and then repeated
- **THEN** the first call succeeds and the second fails with a `HermesClientException` whose status is 404

### Requirement: Named consumers and ack deadline management

The Scala client SHALL accept an optional `consumerId` on `pull`, SHALL return the broker's `acknowledged`/`unknown` detail from `ack`, and SHALL provide `modifyAckDeadline(subscriptionId, ackIds, ackDeadlineSeconds)` returning the broker's `modified`/`unknown` detail.

#### Scenario: Consumer identity on pull
- **WHEN** `pull(sub, max, consumerId = Some("worker-1"))` is called
- **THEN** the pull request body carries `consumerId: "worker-1"`

#### Scenario: Ack reports unknown ids
- **WHEN** `ack` is called with one valid and one stale ack id
- **THEN** the result lists the valid id under `acknowledged` and the stale id under `unknown`

### Requirement: Listings and health from the client

The Scala client SHALL provide `listTopics` and `listSubscriptions` returning the broker's stats listings, and `health` returning the broker's health status.

#### Scenario: Topic listing
- **WHEN** `listTopics()` is called
- **THEN** it returns each topic's id and published-total from the stats projection

### Requirement: Client auth headers

The Scala client SHALL send `Authorization: Bearer <token>` when constructed with a token and `X-API-Key` when constructed with an api key, and SHALL send neither by default.

#### Scenario: Token attached when configured
- **WHEN** a client constructed with a bearer token issues any request
- **THEN** the request carries `Authorization: Bearer <token>`
