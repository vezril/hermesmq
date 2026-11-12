# Spec Delta

## Purpose

Defines the operator-facing lifecycle of a message *after* it has exhausted delivery: inspecting a
subscription's dead-lettered messages without consuming them, and redriving them back to the topic
they originally came from. Where `redelivery-timers` owns how a message *becomes* dead-lettered, this
capability owns what an operator can then do about it.

## ADDED Requirements

### Requirement: Browse a subscription's dead-letters non-destructively

The service SHALL expose `GET /v1/subscriptions/{id}/dead-letters` returning the messages
dead-lettered from that subscription, each with its original message id, origin topic, payload,
attributes, delivery-attempt count, and dead-letter time. The read SHALL be non-destructive: it SHALL
NOT lease, acknowledge, consume, or count a delivery attempt, and SHALL NOT affect any consumer of the
dead-letter topic. Results SHALL be scoped to the caller's tenant.

#### Scenario: Browsing returns the subscription's dead-letters
- **GIVEN** two messages have been dead-lettered from subscription `s1`
- **WHEN** a client calls `GET /v1/subscriptions/s1/dead-letters`
- **THEN** the response is `200` and lists both, each with its original message id, origin topic, payload, attempt count, and dead-letter time

#### Scenario: Browsing is non-destructive and repeatable
- **GIVEN** a subscription with one dead-lettered message
- **WHEN** a client calls the browse endpoint twice
- **THEN** both calls return the same message, and its delivery-attempt count is unchanged

#### Scenario: Browsing does not compete with a dead-letter-topic consumer
- **GIVEN** a consumer is pulling from the dead-letter topic
- **WHEN** an operator browses a subscription's dead-letters
- **THEN** the browse returns the messages and the consumer's leases are unaffected

#### Scenario: Only the caller's tenant's dead-letters are visible
- **GIVEN** two tenants each have a subscription that has dead-lettered messages
- **WHEN** one tenant browses its own subscription's dead-letters
- **THEN** only that tenant's dead-letters are returned

#### Scenario: Edge case — a subscription with no dead-letters returns an empty list
- **GIVEN** a subscription that has never dead-lettered a message
- **WHEN** a client browses its dead-letters
- **THEN** the response is `200` with an empty list, not an error

#### Scenario: Dead-letters remain visible when no dead-letter topic is configured
- **GIVEN** the broker has no dead-letter topic configured, so dead-lettered messages are dropped rather than republished
- **WHEN** a message exhausts its attempts and an operator browses that subscription's dead-letters
- **THEN** the message is still listed, with its payload and attempt count — the evidence is not lost to a warning log

#### Scenario: Edge case — an unknown subscription is not found
- **GIVEN** no subscription `nope` exists for the caller's tenant
- **WHEN** a client calls `GET /v1/subscriptions/nope/dead-letters`
- **THEN** the response is `404`

### Requirement: Redrive dead-letters to their origin topic

The service SHALL expose `POST /v1/subscriptions/{id}/dead-letters/redrive`, re-publishing
dead-lettered messages to the topic they were originally published to. The request SHALL accept either
an explicit list of original message ids or a directive to redrive all of the subscription's
dead-letters. The response SHALL report which ids were redriven and which were unknown. Payload bytes
and original attributes SHALL be preserved.

#### Scenario: Redriving by id republishes to the origin topic
- **GIVEN** a message published to `orders` and dead-lettered from subscription `s1`
- **WHEN** an operator redrives that message id
- **THEN** the message is published to `orders` again with byte-identical payload, and the response reports it redriven

#### Scenario: Redriving all empties the subscription's dead-letters
- **GIVEN** subscription `s1` has three dead-lettered messages from topic `orders`
- **WHEN** an operator redrives all of them
- **THEN** all three are republished to `orders` and the response reports three redriven

#### Scenario: Redriven messages are delivered to current subscriptions
- **GIVEN** a redriven message's origin topic has an active subscription
- **WHEN** the redrive republishes it
- **THEN** it is fanned out and delivered like any newly published message

#### Scenario: Edge case — an unknown message id is reported, not fatal
- **GIVEN** a redrive request listing one valid and one unknown message id
- **WHEN** the broker processes it
- **THEN** the valid one is redriven and the unknown one is reported in the response's unknown list

#### Scenario: Edge case — redriving when the origin topic has been deleted
- **GIVEN** a dead-lettered message whose origin topic no longer exists
- **WHEN** an operator redrives it
- **THEN** that id is reported as failed with a reason, and other ids in the same request still succeed

### Requirement: Redrive is idempotent under retry

A redrive SHALL NOT duplicate a message when the same redrive is issued more than once. The broker
SHALL key redrive republishing on the original message id so that a repeated redrive of an already
redriven message is collapsed rather than fanned out again, within the broker's configured dedup
window. A redrive issued after that window MAY republish again.

#### Scenario: Repeating a redrive does not duplicate the message
- **GIVEN** a dead-lettered message that has just been redriven to `orders`
- **WHEN** the same redrive request is issued again within the dedup window
- **THEN** no second copy is published and the response reports the message as already redriven

#### Scenario: Concurrent duplicate redrives collapse to one publish
- **GIVEN** two identical redrive requests for the same message id arrive concurrently
- **WHEN** the broker processes both
- **THEN** exactly one message is published to the origin topic

### Requirement: Dead-letter operations are authenticated and scope-gated

Both dead-letter endpoints SHALL require a valid API key for the caller's tenant when auth is enabled.
Browsing SHALL require any valid key; redriving, as a mutating operator action, SHALL require the
`admin` scope. Tenant isolation SHALL apply exactly as it does to the rest of the `/v1` surface.

#### Scenario: Browsing requires only a valid key
- **GIVEN** auth is enabled and a client holds a valid non-admin key
- **WHEN** it browses its own subscription's dead-letters
- **THEN** the request succeeds

#### Scenario: Redriving without the admin scope is denied
- **GIVEN** auth is enabled and a client holds a valid key without the `admin` scope
- **WHEN** it calls the redrive endpoint
- **THEN** the response is `403`

#### Scenario: Edge case — a missing or invalid key is unauthenticated
- **GIVEN** auth is enabled
- **WHEN** a client calls either dead-letter endpoint with no or an invalid key
- **THEN** the response is `401`

#### Scenario: Edge case — redriving another tenant's subscription is not found
- **GIVEN** two tenants, one holding a valid admin key
- **WHEN** that tenant redrives a subscription id belonging to the other tenant
- **THEN** the response is `404`, revealing nothing about the other tenant

### Requirement: Dead-letters predating the read model degrade gracefully

The dead-letter read model SHALL be maintained forward from the point it is introduced, consistent with
the service's other read models. Messages dead-lettered before it existed, or lacking the
`x-original-topic` attribute, SHALL NOT cause browse or redrive to fail. Where the origin topic is
absent from a message's attributes, the broker SHALL resolve it from the subscription's topic.

#### Scenario: A message without x-original-topic still redrives
- **GIVEN** a dead-lettered message carrying `x-dead-letter-subscription` but no `x-original-topic`
- **WHEN** an operator redrives it
- **THEN** the broker resolves the origin topic from the subscription and republishes successfully

#### Scenario: Edge case — browse omits pre-read-model dead-letters without erroring
- **GIVEN** messages were dead-lettered before the read model was introduced
- **WHEN** an operator browses that subscription's dead-letters
- **THEN** the response is `200` and lists only the dead-letters the read model has recorded
