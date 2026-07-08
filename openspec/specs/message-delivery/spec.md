# message-delivery Specification

## Purpose

Define projection-driven, at-least-once fan-out: the topic→subscriptions index and the Pekko Projection that delivers each published message to every subscription of its topic.

## Requirements

### Requirement: Topic→subscriptions index

The service SHALL determine, per topic, the set of subscriptions bound to it from the **durable, cluster-shared subscription read model** (not a per-node in-memory map), so a published message can be fanned out to the right subscriptions regardless of which node created them.

#### Scenario: A created subscription is found under its topic
- **GIVEN** a subscription `s1` is created bound to topic `orders`
- **WHEN** the subscriptions for `orders` are looked up
- **THEN** the result contains `s1`

#### Scenario: A subscription created on another node is still found
- **GIVEN** subscription `s1` on `orders` was created via a different node
- **WHEN** the delivery node looks up the subscriptions for `orders`
- **THEN** the result contains `s1` (the lookup uses the shared read model)

#### Scenario: Edge case — subscriptions on different topics are isolated
- **GIVEN** subscription `s1` on `orders` and `s2` on `billing`
- **WHEN** the subscriptions for `orders` are looked up
- **THEN** the result contains `s1` and not `s2`

### Requirement: Projection-driven at-least-once delivery

A Pekko Projection SHALL consume tagged topic `MessagePublished` events and, for each, issue `RecordDelivery` to every subscription bound to that message's topic — **found via the shared subscription read model, so delivery reaches subscriptions on any node** — delivering each message **at least once**. In a cluster the projection SHALL run as exactly one instance (a sharded daemon process); delivery SHALL survive restarts and node loss (resume from the stored offset; replays are idempotent per `(subscription, message)`).

#### Scenario: A published message reaches every subscription of its topic across the cluster
- **GIVEN** topic `orders` has subscriptions `s1` (created on node A) and `s2` (created on node B)
- **WHEN** a message is published to `orders` and the projection processes the event
- **THEN** both `s1` and `s2` receive a `RecordDelivery` for that message (fan-out is not limited to one node's subscriptions)

#### Scenario: Exactly one projection instance runs in the cluster
- **GIVEN** a cluster of two nodes
- **WHEN** the delivery projection is started
- **THEN** only one instance is active cluster-wide, so a message is not delivered twice by multiple projection instances

#### Scenario: Edge case — deterministic ackId makes replay idempotent
- **GIVEN** a message already delivered to subscription `s1`
- **WHEN** the projection re-processes the same `MessagePublished`
- **THEN** the delivery uses the same `(subscription, message)`-derived `AckId`, so re-delivery is a no-op

#### Scenario: Edge case — a topic with no subscriptions delivers nowhere
- **GIVEN** topic `orders` has no subscriptions in the read model
- **WHEN** a message is published to `orders`
- **THEN** the event is processed without error and no delivery is issued

