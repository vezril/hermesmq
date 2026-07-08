# pubsub-api Specification

## Purpose

Define the REST endpoints for publishing messages to topics, creating subscriptions, and pulling/acknowledging messages.

## Requirements

### Requirement: Publish a message to a topic

The service SHALL expose `POST /v1/topics/{id}/messages` accepting a JSON payload and optional attributes, which journals the message on the topic and returns the assigned message id.

#### Scenario: Publish to an existing topic
- **GIVEN** a topic `orders` exists
- **WHEN** a client sends `POST /v1/topics/orders/messages` with `{ "payload": "…", "attributes": { "k": "v" } }`
- **THEN** the response is `202 Accepted` (or `201`) with a generated `messageId`, and the message is durably journaled

#### Scenario: Edge case — publishing to a missing topic returns 404
- **GIVEN** no topic `ghost` exists
- **WHEN** a client publishes to `ghost`
- **THEN** the response is `404 Not Found` and nothing is journaled

#### Scenario: Edge case — an empty payload returns 400
- **WHEN** a client publishes a message with an empty payload
- **THEN** the response is `400 Bad Request`

### Requirement: Create a subscription

The service SHALL expose `POST /v1/subscriptions` to create a subscription bound to a topic.

#### Scenario: Create a subscription
- **WHEN** a client sends `POST /v1/subscriptions` with `{ "subscriptionId": "s1", "topicId": "orders" }`
- **THEN** the subscription is created and the response is `201 Created`

#### Scenario: Edge case — creating a duplicate subscription returns 409
- **GIVEN** subscription `s1` already exists
- **WHEN** a client creates `s1` again
- **THEN** the response is `409 Conflict`

### Requirement: Pull messages from a subscription

The service SHALL expose `POST /v1/subscriptions/{id}/pull` returning up to a requested maximum of the subscription's outstanding messages, each with its `ackId`, payload, attributes, and publish time.

#### Scenario: Pull returns outstanding messages
- **GIVEN** subscription `s1` has outstanding messages
- **WHEN** a client sends `POST /v1/subscriptions/s1/pull` with `{ "max": 10 }`
- **THEN** the response is `200 OK` with a list of messages, each carrying an `ackId` and payload

#### Scenario: Edge case — pull with nothing outstanding returns an empty list
- **GIVEN** subscription `s1` has no outstanding messages
- **WHEN** a client pulls
- **THEN** the response is `200 OK` with an empty list

#### Scenario: Edge case — pull on a missing subscription returns 404
- **GIVEN** no subscription `ghost` exists
- **WHEN** a client pulls from `ghost`
- **THEN** the response is `404 Not Found`

### Requirement: Acknowledge messages

The service SHALL expose `POST /v1/subscriptions/{id}/ack` accepting a list of `ackId`s to acknowledge, removing them from the subscription's outstanding set.

#### Scenario: Acknowledge removes messages from outstanding
- **GIVEN** subscription `s1` has outstanding messages with known `ackId`s
- **WHEN** a client sends `POST /v1/subscriptions/s1/ack` with `{ "ackIds": ["…"] }`
- **THEN** the response is `200 OK` (or `204`) and those messages are no longer returned by a subsequent pull

#### Scenario: Edge case — acknowledging an unknown ackId is reported without failing the batch
- **GIVEN** subscription `s1` and an `ackId` that is not outstanding
- **WHEN** a client acks a batch containing that `ackId`
- **THEN** the unknown ack is reported as not-acknowledged (or ignored) and any valid acks in the batch still succeed, without a 5xx error

### Requirement: Modify ack deadline over REST

The API SHALL expose `POST /v1/subscriptions/{id}/modifyAckDeadline` accepting `{"ackIds":[…],"ackDeadlineSeconds":N}`, which for each currently-outstanding LEASED `AckId` sets its deadline to `now + N` seconds. `ackDeadlineSeconds: 0` SHALL make the referenced messages immediately available for redelivery (a nack). The response SHALL report which `AckId`s were modified and which were unknown/not-leased. A request for an unknown subscription SHALL return `404`.

#### Scenario: Extending a lease pushes back its deadline
- **GIVEN** a subscription with a LEASED `ackId`
- **WHEN** `POST /v1/subscriptions/{id}/modifyAckDeadline` is called with that `ackId` and `ackDeadlineSeconds: 60`
- **THEN** the response is `200`, the `ackId` is reported modified, and its deadline becomes `now + 60s`

#### Scenario: Edge case — deadline of 0 nacks for immediate redelivery
- **GIVEN** a subscription with a LEASED `ackId`
- **WHEN** `modifyAckDeadline` is called with that `ackId` and `ackDeadlineSeconds: 0`
- **THEN** the message becomes available for redelivery on the next pull (does not wait for the sweep interval)

#### Scenario: Edge case — unknown ackId is reported, not fatal
- **GIVEN** a subscription that does not have `ackId` outstanding
- **WHEN** `modifyAckDeadline` is called with that `ackId`
- **THEN** the response is `200` and the `ackId` is reported as unknown/not-modified (the whole request is not rejected)
