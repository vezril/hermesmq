# scala-client Specification

## Purpose

Define the native Scala client library — a typed, async API over the REST endpoints, packaged as an independent, dependency-light artifact.

## Requirements

### Requirement: Typed topic management client

The client SHALL provide typed, async (`Future`-returning) methods to create, read, update (labels), and delete topics over the REST API.

#### Scenario: Create and read a topic
- **GIVEN** a `HermesClient` pointed at a running broker
- **WHEN** `createTopic(topicId, labels)` succeeds and then `getTopic(topicId)` is called
- **THEN** create completes successfully and get returns the topic's labels

#### Scenario: Update and delete a topic
- **GIVEN** an existing topic
- **WHEN** `updateTopic(topicId, newLabels)` then `deleteTopic(topicId)` are called
- **THEN** both complete successfully and a subsequent `getTopic` reports the topic is gone

#### Scenario: Edge case — creating a duplicate topic fails with a typed error
- **GIVEN** a topic that already exists
- **WHEN** `createTopic` is called again
- **THEN** the returned `Future` fails with a client error carrying the HTTP conflict status, not a generic exception

#### Scenario: Edge case — getting a missing topic returns empty, not an error
- **GIVEN** no such topic
- **WHEN** `getTopic(topicId)` is called
- **THEN** it completes with an empty result (a 404 is a normal "not found", not a failure)

### Requirement: Typed publish and consume client

The client SHALL provide typed methods to publish a message to a topic (returning the assigned message id) and to create subscriptions, pull messages, and acknowledge them.

#### Scenario: Publish, pull, and acknowledge round-trip
- **GIVEN** a topic and a subscription bound to it
- **WHEN** `publish(topicId, payload, attributes)` is called, then (after delivery) `pull(subscriptionId, max)` and `ack(subscriptionId, ackIds)`
- **THEN** publish returns a `MessageId`, pull returns the message with its ack id and payload, and ack completes successfully so a subsequent pull no longer returns it

#### Scenario: Edge case — publishing to a missing topic fails with a typed error
- **GIVEN** no such topic
- **WHEN** `publish` is called
- **THEN** the `Future` fails with a client error carrying the not-found status

#### Scenario: Edge case — pulling from a missing subscription fails with a typed error
- **GIVEN** no such subscription
- **WHEN** `pull` is called
- **THEN** the `Future` fails with a client error carrying the not-found status

### Requirement: Dependency-light client artifact

The client SHALL be published as an independent artifact that depends only on the shared `domain` types and a lightweight HTTP client — not on the server's persistence, projection, or HTTP-server modules.

#### Scenario: Client artifact excludes server dependencies
- **WHEN** the `client` module's dependency graph is inspected
- **THEN** it includes the `domain` module and an HTTP client, and does not include pekko-persistence, pekko-projection, or the server module

#### Scenario: Edge case — a consumer can use the client without a database
- **GIVEN** a Scala application that depends only on `hermesmq-client`
- **WHEN** it constructs a `HermesClient` and calls it against a running broker
- **THEN** it compiles and runs without any persistence/database dependency on the classpath
