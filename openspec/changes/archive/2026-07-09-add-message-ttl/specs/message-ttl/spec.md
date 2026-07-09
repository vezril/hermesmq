# message-ttl

Per-message time-to-live: a message expires at `publishTime + TTL`, is never delivered once
expired, and is purged from a subscription's outstanding set by the sweeper. TTL comes from a
per-publish override or a configurable global default; expired messages are dropped. Behavior
is unchanged when no TTL is set.

## ADDED Requirements

### Requirement: A message carries an optional expiry

A message SHALL carry an optional `expireTime`, computed at publish as `publishTime + TTL`,
where the TTL is the per-publish `ttlSeconds` when positive, else the configured global
default when positive, else none. When neither is set the message SHALL have no `expireTime`
and behave exactly as before. The `expireTime` SHALL serialize with the explicit JSON
serializer (never Java) and round-trip losslessly, and a message journaled without the field
SHALL deserialize with no `expireTime`.

#### Scenario: A per-publish ttlSeconds sets expireTime
- **GIVEN** a publish with `ttlSeconds = 60` at publish time `T`
- **WHEN** the message is built
- **THEN** its `expireTime` is `T + 60s`

#### Scenario: The global default applies when no per-publish ttl is given
- **GIVEN** a configured default TTL of `30s` and a publish with no `ttlSeconds`
- **WHEN** the message is built at `T`
- **THEN** its `expireTime` is `T + 30s`

#### Scenario: Edge case — no ttl and no default yields no expiry
- **GIVEN** no default TTL and a publish with no `ttlSeconds`
- **WHEN** the message is built
- **THEN** it has no `expireTime` (unchanged behavior)

#### Scenario: Edge case — a message without an expireTime field deserializes to none
- **GIVEN** a message previously journaled without an `expireTime` field
- **WHEN** it is deserialized
- **THEN** it has no `expireTime` and is never treated as expired

### Requirement: The sweeper purges expired outstanding messages

The service SHALL maintain a durable read model of outstanding messages that have an
`expireTime`, and the periodic sweeper SHALL issue `ExpireMessage` for every such message
whose `expireTime` is at or before the sweep time, purging it off the hot delivery path.
Messages without an `expireTime` SHALL NOT appear in the read model or incur sweep work.

#### Scenario: An expired outstanding message is swept away
- **GIVEN** an outstanding message whose `expireTime` has passed
- **WHEN** the sweeper runs
- **THEN** it issues `ExpireMessage` for that message and the message is removed from the subscription

#### Scenario: Edge case — a not-yet-expired message is left alone
- **GIVEN** an outstanding message whose `expireTime` is still in the future
- **WHEN** the sweeper runs
- **THEN** no `ExpireMessage` is issued for it

#### Scenario: Edge case — an acked message is not swept
- **GIVEN** a message with an `expireTime` that was acknowledged before it expired
- **WHEN** the sweeper runs after `expireTime`
- **THEN** no `ExpireMessage` is issued (it left the read model on ack)

### Requirement: TTL is configurable and reaches both publish surfaces

The global default TTL SHALL be configurable via HOCON with an environment override and a
default of `0` (off); a negative value SHALL fail fast at startup. REST publish and gRPC
publish SHALL both accept an optional `ttlSeconds` that overrides the default for that message.

#### Scenario: REST publish honors ttlSeconds
- **GIVEN** the service running
- **WHEN** a client publishes over REST with `ttlSeconds`
- **THEN** the resulting message's `expireTime` reflects that ttl

#### Scenario: gRPC publish honors ttlSeconds
- **GIVEN** the service running
- **WHEN** a client publishes over gRPC with `ttlSeconds`
- **THEN** the resulting message's `expireTime` reflects that ttl

#### Scenario: Edge case — a negative default TTL fails fast
- **GIVEN** `hermesmq.ttl.default` configured as a negative duration
- **WHEN** the service starts
- **THEN** it exits non-zero with a clear configuration error
