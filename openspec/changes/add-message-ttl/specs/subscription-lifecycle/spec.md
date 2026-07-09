# subscription-lifecycle

Deltas for message TTL: `Lease` never returns an expired message, and an expired
outstanding message is purged (dropped) rather than redelivered or dead-lettered.

## MODIFIED Requirements

### Requirement: Pull outstanding messages

Pull SHALL be a **persisting, leasing** operation: it returns up to a requested maximum of the subscription's **AVAILABLE** outstanding messages (each with its `AckId`, payload, attributes, and publish time) and, for every message returned, SHALL lease it by setting its ack deadline to `now + ackDeadline` (the configured default, overridable per pull) and marking it **LEASED**, emitting `MessageLeased(ackIds, deadline)`. A message that is currently LEASED with an unexpired deadline SHALL NOT be returned by a subsequent pull (visibility timeout). A message whose **`expireTime` has passed SHALL NOT be returned** (it is left for the sweeper to purge). Pull SHALL never return more than the requested maximum, and a pull with no available messages SHALL return empty (not an error).

#### Scenario: Pull returns and leases available messages
- **GIVEN** a subscription with two AVAILABLE outstanding messages
- **WHEN** a pull for up to 10 is issued with ack deadline `D`
- **THEN** both messages are returned with their `AckId`/payload, `MessageLeased` is emitted, and evolving marks both LEASED with deadline `now + D`

#### Scenario: A leased message is invisible to an immediate second pull
- **GIVEN** a subscription whose only message was just leased with an unexpired deadline
- **WHEN** a second pull is issued before the deadline passes
- **THEN** the message is not returned (an empty result)

#### Scenario: Pull respects the requested maximum
- **GIVEN** a subscription with three AVAILABLE messages
- **WHEN** a pull for up to 2 is issued
- **THEN** at most 2 messages are returned and leased

#### Scenario: Edge case — a message whose lease expired is available to pull again
- **GIVEN** a leased message whose deadline has passed and which the sweeper returned to AVAILABLE
- **WHEN** a pull is issued
- **THEN** the message is returned and re-leased with a fresh deadline

#### Scenario: Edge case — an expired (TTL) available message is not returned
- **GIVEN** an AVAILABLE message whose `expireTime` has already passed
- **WHEN** a pull is issued at a time after `expireTime`
- **THEN** the message is not returned (it is skipped, awaiting purge)

#### Scenario: Edge case — pull on a subscription with no available messages returns nothing
- **GIVEN** a subscription with no AVAILABLE messages (empty, or all LEASED within deadline)
- **WHEN** a pull is issued
- **THEN** an empty result is returned (not an error)

## ADDED Requirements

### Requirement: Expire an outstanding message past its TTL

An `ExpireMessage` command referencing an outstanding `AckId` SHALL emit `MessageExpired(ackId)` when that message has an `expireTime` at or before `now`, and `evolve` SHALL remove it from the outstanding set (dropped — not returned to AVAILABLE, not redelivered, not dead-lettered). Expiring a message that is not outstanding, or whose `expireTime` has not passed (or has none), SHALL be a no-op. TTL expiry is independent of the delivery-attempt count.

#### Scenario: An outstanding message past its expireTime is expired and removed
- **GIVEN** an outstanding message whose `expireTime` is before `now` (in any lease state, any attempt count)
- **WHEN** `decide` handles `ExpireMessage(ackId, now)`
- **THEN** it emits `MessageExpired(ackId)` and evolving removes the message from outstanding

#### Scenario: Edge case — expiring a not-yet-expired message is a no-op
- **GIVEN** an outstanding message whose `expireTime` is after `now` (or has no `expireTime`)
- **WHEN** `decide` handles `ExpireMessage(ackId, now)`
- **THEN** it returns `Right(Nil)` and the message stays outstanding

#### Scenario: Edge case — expiring an unknown/gone ackId is a no-op
- **GIVEN** an `ackId` that is not outstanding (acked, dead-lettered, or already expired)
- **WHEN** `decide` handles `ExpireMessage(ackId, now)`
- **THEN** it returns `Right(Nil)` with no event and no resurrection
