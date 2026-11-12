# dead-letter-operations Specification

## Purpose
Defines the operator-facing lifecycle of a message *after* it has exhausted delivery: inspecting a
subscription's dead-lettered messages without consuming them, and redriving them back to the topic
they originally came from. Where `redelivery-timers` owns how a message *becomes* dead-lettered, this
capability owns what an operator can then do about it.

## Requirements

### Requirement: Browse a subscription's dead-letters non-destructively

The service SHALL expose `GET /v1/subscriptions/{id}/dead-letters` returning a bounded page of the
messages dead-lettered from that subscription, each with its original message id, origin topic,
payload, attributes, delivery-attempt count, and dead-letter time. The response SHALL be an object
carrying the page and, when more results exist, a cursor for the next one. The read SHALL be
non-destructive: it SHALL NOT lease, acknowledge, consume, or count a delivery attempt, and SHALL NOT
affect any consumer of the dead-letter topic. Results SHALL be scoped to the caller's tenant.

#### Scenario: Browsing returns the subscription's dead-letters
- **GIVEN** two messages have been dead-lettered from subscription `s1`
- **WHEN** a client calls `GET /v1/subscriptions/s1/dead-letters`
- **THEN** the response is `200` and its `deadLetters` lists both, each with its original message id, origin topic, payload, attempt count, and dead-letter time

#### Scenario: A page is bounded by the requested limit and offers a cursor
- **GIVEN** a subscription with three dead-letters
- **WHEN** a client calls the browse endpoint with `limit=2`
- **THEN** `deadLetters` holds two entries and `nextCursor` is present

#### Scenario: Following the cursor returns the remaining entries without repeats
- **GIVEN** the first page of a three-entry subscription was fetched with `limit=2`
- **WHEN** the client calls the endpoint again with that `nextCursor`
- **THEN** the response holds the third entry, repeats neither of the first two, and omits `nextCursor`

#### Scenario: Paging is stable when new dead-letters arrive mid-walk
- **GIVEN** a client has fetched the first page of a subscription's dead-letters
- **WHEN** a newer message is dead-lettered before the client follows the cursor
- **THEN** the next page continues from the cursor's position and does not skip or duplicate an entry the client had already seen

#### Scenario: Edge case — an invalid or malformed cursor is rejected
- **GIVEN** a client supplies a cursor that is not one the service issued
- **WHEN** it calls the browse endpoint
- **THEN** the response is `400`, not a silently reset walk from the newest entry

#### Scenario: Edge case — a limit above the maximum is clamped, not rejected
- **GIVEN** a client requests a limit larger than the service's maximum page size
- **WHEN** the service handles it
- **THEN** it returns at most the maximum page size rather than failing the request

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
- **THEN** the response is `200` with an empty `deadLetters` and no cursor, not an error

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
dead-letters, and MAY request that entries already marked redriven be published again. The response
SHALL report which ids were redriven and which were unknown. Payload bytes and original attributes
SHALL be preserved.

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

#### Scenario: A forced redrive republishes an entry already marked redriven
- **GIVEN** a dead-letter whose claim was taken but whose publish never happened, so it is marked redriven
- **WHEN** an operator redrives it with the force directive
- **THEN** it is republished to its origin topic and reported redriven, rather than reported as already redriven

#### Scenario: Edge case — an unknown message id is reported, not fatal
- **GIVEN** a redrive request listing one valid and one unknown message id
- **WHEN** the broker processes it
- **THEN** the valid one is redriven and the unknown one is reported in the response's unknown list

#### Scenario: Edge case — redriving when the origin topic has been deleted
- **GIVEN** a dead-lettered message whose origin topic no longer exists
- **WHEN** an operator redrives it
- **THEN** that id is reported as failed with a reason, and other ids in the same request still succeed

### Requirement: Redrive is idempotent under retry

A redrive SHALL NOT duplicate a message when the same redrive is issued more than once, and this SHALL
NOT depend on any configurable dedup window. The broker SHALL claim each entry atomically before
publishing it, so that exactly one of several concurrent redrives of the same entry publishes and the
rest report it as already redriven. A publish that fails SHALL release the claim so the redrive can be
retried.

#### Scenario: Repeating a redrive does not duplicate the message
- **GIVEN** a dead-lettered message that has just been redriven to `orders`
- **WHEN** the same redrive request is issued again
- **THEN** no second copy is published and the response reports the message as already redriven

#### Scenario: Concurrent duplicate redrives collapse to one publish
- **GIVEN** two identical redrive requests for the same message id arrive concurrently
- **WHEN** the broker processes both
- **THEN** exactly one message is published to the origin topic and the other reports it as already redriven

#### Scenario: Idempotency holds with the dedup window disabled
- **GIVEN** the broker is configured with its dedup window off, the default
- **WHEN** an operator redrives the same entry twice
- **THEN** exactly one message is published — idempotency does not depend on that setting

#### Scenario: A failed publish leaves the entry redrivable
- **GIVEN** a redrive whose republish to the origin topic fails
- **WHEN** the operator retries the redrive after fixing the cause
- **THEN** the entry is published, rather than being permanently reported as already redriven

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
