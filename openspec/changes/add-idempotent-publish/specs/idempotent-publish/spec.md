## ADDED Requirements

### Requirement: Producer idempotency key on publish

The publish surface SHALL accept an optional producer-supplied idempotency key on both the gRPC `PublishRequest` (`idempotency_key`) and the REST publish body. An absent key, or an empty-string key, SHALL be treated as no key and reproduce the pre-existing publish behaviour (a fresh message is always published).

#### Scenario: Publishing with an idempotency key carries it into the message
- **GIVEN** an active topic and dedup enabled
- **WHEN** a producer publishes with `idempotency_key = "abc"`
- **THEN** the message is published and its idempotency key is retained for dedup purposes

#### Scenario: Publishing without a key is never deduplicated
- **GIVEN** an active topic and dedup enabled
- **WHEN** a producer publishes twice with no idempotency key
- **THEN** two distinct messages are published, each with its own `messageId`

#### Scenario: Edge case — an empty-string key is treated as absent
- **GIVEN** an active topic and dedup enabled
- **WHEN** a producer publishes twice with `idempotency_key = ""`
- **THEN** both publishes are accepted as distinct messages, exactly as if no key were supplied

### Requirement: Duplicate publish within the window collapses to the original message

When dedup is enabled, a `Publish` carrying an idempotency key already seen for that topic within the configured window SHALL NOT publish a second message; it SHALL return the original message's `messageId` and signal `deduplicated = true` on both the gRPC and REST responses. A first publish for a key SHALL return `deduplicated = false`.

#### Scenario: A retry within the window returns the original messageId and does not re-publish
- **GIVEN** an active topic with dedup enabled and a message previously published with key `"abc"` returning `messageId = M`
- **WHEN** the producer publishes again with key `"abc"` inside the window
- **THEN** no new message is published, the response `messageId` is `M`, and `deduplicated` is `true`

#### Scenario: Edge case — the same key after the window has elapsed is a new publish
- **GIVEN** a message published with key `"abc"` whose window has since elapsed
- **WHEN** the producer publishes again with key `"abc"`
- **THEN** a new message with a new `messageId` is published and `deduplicated` is `false`

#### Scenario: Edge case — different keys publish independently
- **GIVEN** an active topic with dedup enabled
- **WHEN** a producer publishes with key `"abc"` and then with key `"def"` inside the window
- **THEN** two distinct messages are published, each with `deduplicated = false`

### Requirement: The dedup window is configurable and opt-in

The broker SHALL read `hermesmq.dedup.window` as a duration where `0` disables deduplication (the default), and a negative value SHALL fail fast at startup. When the window is `0`, an idempotency key SHALL be ignored and every publish SHALL produce a new message.

#### Scenario: A window of zero disables deduplication
- **GIVEN** `hermesmq.dedup.window = 0`
- **WHEN** a producer publishes twice with the same key `"abc"`
- **THEN** two distinct messages are published and neither response is marked `deduplicated`

#### Scenario: A positive window enables within-window deduplication
- **GIVEN** `hermesmq.dedup.window` set to a positive duration
- **WHEN** a producer publishes twice with the same key inside the window
- **THEN** the second publish is deduplicated to the first message's `messageId`

#### Scenario: Edge case — a negative window fails fast
- **GIVEN** `hermesmq.dedup.window` set to a negative duration
- **WHEN** the broker configuration is loaded
- **THEN** startup fails with a clear configuration error rather than starting with dedup misconfigured

### Requirement: Deduplication survives entity recovery

Deduplication SHALL be strongly consistent for a single topic within the window across entity passivation, recovery, and snapshotting: the seen-key set SHALL be rebuilt from the event journal on replay and preserved in snapshots, so a duplicate arriving after the topic entity restarts is still recognised while within the window.

#### Scenario: A duplicate after entity restart is still deduplicated within the window
- **GIVEN** a topic entity that published key `"abc"` and was then passivated and recovered
- **WHEN** the producer publishes again with key `"abc"` inside the window
- **THEN** the publish is deduplicated to the original `messageId`

#### Scenario: Edge case — a pre-existing snapshot without a seen-set recovers as an empty window
- **GIVEN** a topic snapshot written before this feature existed (no seen-set)
- **WHEN** the entity recovers from that snapshot
- **THEN** recovery succeeds with an empty seen-set and subsequent publishes behave normally
