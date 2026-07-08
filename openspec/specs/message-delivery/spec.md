# message-delivery Specification

## Purpose

Define projection-driven, at-least-once fan-out: the topic→subscriptions index and the Pekko Projection that delivers each published message to every subscription of its topic.

## Requirements

### Requirement: Topic→subscriptions index

The service SHALL maintain, per topic, the set of subscriptions bound to it, so a published message can be fanned out to the right subscriptions. The index SHALL be updated as subscriptions are created.

#### Scenario: A created subscription is indexed under its topic
- **GIVEN** a subscription `s1` is created bound to topic `orders`
- **WHEN** the index for `orders` is read
- **THEN** it contains `s1`

#### Scenario: Edge case — subscriptions on different topics are isolated
- **GIVEN** subscription `s1` on `orders` and `s2` on `billing`
- **WHEN** the index for `orders` is read
- **THEN** it contains `s1` and not `s2`

### Requirement: Projection-driven at-least-once delivery

A Pekko Projection SHALL consume tagged topic `MessagePublished` events and, for each, issue `RecordDelivery` to every subscription bound to that message's topic, delivering each message **at least once**. In a cluster the projection SHALL run as **exactly one instance** (a sharded daemon process / cluster singleton), so each event is processed once with no duplicate delivery or offset-store contention. Delivery SHALL survive restarts and node loss (the projection resumes from its stored offset; replays re-issue deliveries, which are idempotent per `(subscription, message)`).

#### Scenario: A published message is delivered to every subscription on its topic
- **GIVEN** topic `orders` has subscriptions `s1` and `s2`
- **WHEN** a message is published to `orders` and the projection processes the event
- **THEN** both `s1` and `s2` receive a `RecordDelivery` for that message and it becomes outstanding on each

#### Scenario: Exactly one projection instance runs in the cluster
- **GIVEN** a cluster of two nodes
- **WHEN** the delivery projection is started
- **THEN** only one instance is active cluster-wide, so a published message is not delivered twice due to multiple projection instances

#### Scenario: Edge case — deterministic ackId makes replay idempotent
- **GIVEN** a message already delivered to subscription `s1`
- **WHEN** the projection re-processes the same `MessagePublished` (e.g. after failover before the offset was committed)
- **THEN** the delivery uses the same `(subscription, message)`-derived `AckId`, so re-delivery is a no-op and the message is not duplicated in `s1`'s outstanding set

#### Scenario: Edge case — a topic with no subscriptions delivers nowhere
- **GIVEN** topic `orders` has no subscriptions
- **WHEN** a message is published to `orders`
- **THEN** the event is processed without error and no delivery is issued

