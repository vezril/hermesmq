# topic-admin-api Specification

## Purpose

Define the REST admin API for managing topics (create, read, update labels, delete) and the single-node registry that routes each request to the persistent entity owning the topic id.

## Requirements

### Requirement: Single-writer topic registry

The service SHALL route topic commands to the persistent `TopicEntity` that owns each topic id via **Cluster Sharding**, ensuring **exactly one entity instance (one writer) per topic id across the cluster**. Entities SHALL be created on demand (on the owning node) and recover their own state from the shared journal; commands SHALL be routable from any node.

#### Scenario: Commands for the same topic reach one entity
- **GIVEN** two commands for the same topic id arrive (from any node)
- **WHEN** sharding routes them
- **THEN** both are handled by the same entity instance that owns that id in the cluster (no second, competing writer)

#### Scenario: A new topic id is resolved on demand
- **GIVEN** no entity is currently running for a topic id
- **WHEN** a command for that id arrives
- **THEN** sharding starts the entity on its owning node and the command is handled

#### Scenario: Edge case — commands for different topics are isolated
- **GIVEN** commands for two different topic ids
- **WHEN** sharding routes them
- **THEN** they are handled by separate entity instances (possibly on different nodes) and do not interfere

### Requirement: REST endpoints for topic management

The service SHALL expose REST endpoints on the HTTP server to create, delete, update (labels), and read topics, mapping domain outcomes to HTTP status codes. Request/response bodies SHALL be JSON.

#### Scenario: Create a topic
- **WHEN** a client sends `POST /v1/topics` with `{ "topicId": "orders", "labels": { "team": "payments" } }`
- **THEN** the topic is created and the response is `201 Created`

#### Scenario: Read a topic
- **GIVEN** a topic `orders` exists with labels
- **WHEN** a client sends `GET /v1/topics/orders`
- **THEN** the response is `200 OK` with the topic id and its current labels

#### Scenario: Update a topic's labels
- **GIVEN** a topic `orders` exists
- **WHEN** a client sends `PATCH /v1/topics/orders` with `{ "labels": { "team": "core" } }`
- **THEN** the response is `200 OK` and a subsequent read reflects the new labels

#### Scenario: Delete a topic
- **GIVEN** a topic `orders` exists
- **WHEN** a client sends `DELETE /v1/topics/orders`
- **THEN** the response is `204 No Content`

#### Scenario: Edge case — creating a duplicate topic returns 409
- **GIVEN** a topic `orders` already exists
- **WHEN** a client sends `POST /v1/topics` for `orders`
- **THEN** the response is `409 Conflict` and no change is made

#### Scenario: Edge case — operating on a missing topic returns 404
- **GIVEN** no topic `ghost` exists
- **WHEN** a client sends `GET`, `PATCH`, or `DELETE` for `ghost`
- **THEN** the response is `404 Not Found`

#### Scenario: Edge case — a malformed request body returns 400
- **WHEN** a client sends `POST /v1/topics` with a body missing `topicId` or with a blank id
- **THEN** the response is `400 Bad Request` and no topic is created
