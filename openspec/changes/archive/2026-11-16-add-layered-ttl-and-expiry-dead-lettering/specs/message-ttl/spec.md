# Spec Delta

## ADDED Requirements

### Requirement: TTL resolves across layers by shortest-wins

Four layers MAY specify a time-to-live: the per-publish `ttlSeconds`, the message's topic, the
receiving subscription, and the global default. The effective expiry SHALL be the **earliest** that
any applicable layer implies: a layer MAY shorten a message's lifetime and SHALL NOT extend it. A
subscription's TTL therefore SHALL NOT keep a message alive past the expiry its topic or publisher
set.

#### Scenario: A subscription TTL shorter than the topic's wins
- **GIVEN** topic `orders` with a one-hour TTL and subscription `s1` on it with a five-minute TTL
- **WHEN** a message is published and delivered to `s1`
- **THEN** that subscription's copy expires five minutes after publish

#### Scenario: A subscription TTL longer than the topic's does not extend the message
- **GIVEN** topic `orders` with a five-minute TTL and subscription `s1` on it with a one-hour TTL
- **WHEN** a message is published and delivered to `s1`
- **THEN** it still expires five minutes after publish — the subscription cannot outlive the topic's declaration

#### Scenario: A per-publish ttlSeconds shorter than the topic's wins
- **GIVEN** topic `orders` with a one-hour TTL
- **WHEN** a message is published to it with `ttlSeconds` of 60
- **THEN** the message expires 60 seconds after publish

#### Scenario: The topic's TTL applies when a publish names none
- **GIVEN** topic `orders` with a one-hour TTL and no global default
- **WHEN** a message is published without `ttlSeconds`
- **THEN** it expires one hour after publish

#### Scenario: Two subscriptions on one topic can expire at different times
- **GIVEN** topic `orders` with no TTL, subscription `s1` with a one-minute TTL, and `s2` with none
- **WHEN** a message is published and fanned out to both
- **THEN** `s1`'s copy expires a minute after publish while `s2`'s does not expire

#### Scenario: Edge case — no layer specifies a TTL
- **GIVEN** no global default, no topic TTL, no subscription TTL, and a publish without `ttlSeconds`
- **WHEN** a message is published
- **THEN** it has no expiry and never appears in the expiring-message read model

### Requirement: Expiry may dead-letter instead of dropping

The service SHALL support routing an expired message to the configured dead-letter topic rather than
dropping it, controlled by configuration defaulting to dropping. When enabled, the republished message
SHALL carry an attribute recording when it expired, so an operator can distinguish expiry from
delivery-attempt exhaustion. When disabled, or when no dead-letter topic is configured, expiry SHALL
drop the message as before.

#### Scenario: An expired message is dead-lettered when enabled
- **GIVEN** dead-letter-on-expiry enabled and a dead-letter topic configured
- **WHEN** a message expires and the sweeper purges it
- **THEN** it is republished to the dead-letter topic, carrying an attribute recording its expiry time

#### Scenario: An expired dead-letter is distinguishable from an exhausted one
- **GIVEN** one message dead-lettered by expiry and one by exhausting its delivery attempts
- **WHEN** an operator browses the dead-letters
- **THEN** the expired one carries the expiry attribute and the exhausted one carries the delivery-attempt attributes

#### Scenario: Expiry drops when dead-lettering on expiry is disabled
- **GIVEN** dead-letter-on-expiry disabled, the default
- **WHEN** a message expires
- **THEN** it is dropped exactly as before and nothing is republished

#### Scenario: Edge case — enabled but no dead-letter topic configured
- **GIVEN** dead-letter-on-expiry enabled and no dead-letter topic configured
- **WHEN** a message expires
- **THEN** it is dropped with a warning rather than failing the sweep

### Requirement: Expiry is counted

The service SHALL expose a counter of messages expired per subscription, so the volume of
TTL-driven loss is observable even when dead-lettering on expiry is disabled.

#### Scenario: Expiring a message increments the counter
- **GIVEN** a subscription with an expired outstanding message
- **WHEN** the sweeper expires it
- **THEN** the expiry counter for that subscription increases by one

#### Scenario: Edge case — a dropped expiry is still counted
- **GIVEN** dead-letter-on-expiry disabled, so the message is dropped
- **WHEN** it expires
- **THEN** the counter still increases — the drop is silent in the data path but not in the metrics

## MODIFIED Requirements

### Requirement: TTL is configurable and reaches both publish surfaces

The global default TTL SHALL be configurable via HOCON with an environment override and a
default of `0` (off); a negative value SHALL fail fast at startup. REST publish and gRPC
publish SHALL both accept an optional `ttlSeconds` that overrides the default for that message. A
topic and a subscription MAY each carry their own optional TTL, which participates in resolution by
shortest-wins; a negative TTL on either SHALL be rejected rather than silently treated as off.

#### Scenario: REST publish honors ttlSeconds
- **GIVEN** the service running
- **WHEN** a client publishes over REST with `ttlSeconds`
- **THEN** the resulting message's `expireTime` reflects that ttl

#### Scenario: gRPC publish honors ttlSeconds
- **GIVEN** the service running
- **WHEN** a client publishes over gRPC with `ttlSeconds`
- **THEN** the resulting message's `expireTime` reflects that ttl

#### Scenario: A negative topic or subscription TTL is rejected
- **GIVEN** a request creating a topic or subscription with a negative `ttlSeconds`
- **WHEN** the service handles it
- **THEN** it is rejected with a bad-request rather than accepted as "no TTL"

#### Scenario: Edge case — a negative default TTL fails fast
- **GIVEN** `hermesmq.ttl.default` configured as a negative duration
- **WHEN** the service starts
- **THEN** it exits non-zero naming the invalid setting
