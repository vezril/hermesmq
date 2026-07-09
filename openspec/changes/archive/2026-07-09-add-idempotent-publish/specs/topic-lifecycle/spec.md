## MODIFIED Requirements

### Requirement: Publish a message to a topic

For a `Publish` command carrying a fully-formed `Message`, `decide` SHALL return a `MessagePublished` event only when the topic is active (created and not deleted), and SHALL reject publishing to a non-existent or deleted topic. When the message carries an idempotency key and deduplication is enabled, `decide` SHALL, on an active topic, deduplicate against a windowed seen-key set held in `TopicState`: a key already seen within the window SHALL produce no event and yield the original message's `messageId` (a duplicate), while a first or window-expired key SHALL emit `MessagePublished`. `evolve` SHALL, on `MessagePublished`, record the key with the message's `messageId` and `publishTime` and prune entries older than the message's `publishTime` minus the window, keeping `evolve` a pure function of the event.

#### Scenario: Publishing to an active topic emits MessagePublished
- **GIVEN** an active (created, not deleted) topic
- **WHEN** `decide` handles `Publish(message)`
- **THEN** it returns `Right(List(MessagePublished(message)))`

#### Scenario: Edge case — publishing to a non-existent topic is rejected
- **GIVEN** an empty (non-existent) topic state
- **WHEN** `decide` handles `Publish(message)`
- **THEN** it returns a `Left(Rejection)` indicating the topic does not exist, and no event is produced

#### Scenario: Edge case — publishing to a deleted topic is rejected
- **GIVEN** a topic that has been deleted
- **WHEN** `decide` handles `Publish(message)`
- **THEN** it returns a `Left(Rejection)` (not found), and no event is produced

#### Scenario: A duplicate key within the window produces no event
- **GIVEN** an active topic whose state has already seen key `"abc"` (mapped to `messageId M`) within the window
- **WHEN** `decide` handles `Publish(message)` where `message` carries key `"abc"` within the window
- **THEN** it returns `Right(Nil)` (no event) and the publish result reports `messageId M` with `deduplicated = true`

#### Scenario: Edge case — a window-expired key publishes again
- **GIVEN** an active topic whose seen entry for key `"abc"` is older than the window relative to the new message's `publishTime`
- **WHEN** `decide` handles `Publish(message)` carrying key `"abc"`
- **THEN** it returns `Right(List(MessagePublished(message)))`, and `evolve` replaces the seen entry and prunes the expired one

#### Scenario: Edge case — evolve prunes expired keys deterministically from the event
- **GIVEN** a `TopicState` holding several seen keys with varying `publishTime`s
- **WHEN** `evolve` applies `MessagePublished(message)`
- **THEN** the resulting state contains the new key and drops exactly those entries older than `message.publishTime` minus the window, using no wall-clock time
