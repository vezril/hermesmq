# topic-lifecycle Specification

## Purpose

Define the Topic aggregate's pure write-side logic: creating a topic (with labels), publishing messages, deleting a topic, and updating its labels — expressed as total `decide`/`evolve` functions with explicit rejections.

## Requirements

### Requirement: Create a topic

The Topic aggregate SHALL provide a pure `decide(state, command)` that, for a `CreateTopic` command on a not-yet-existing topic, returns a single `TopicCreated` event carrying the topic's initial labels (possibly empty); `evolve(state, event)` SHALL then mark the topic as existing with those labels. Creating a topic whose id has already been used — whether currently active or deleted — SHALL be rejected.

#### Scenario: Creating a new topic emits TopicCreated with labels
- **GIVEN** an empty (non-existent) topic state
- **WHEN** `decide` handles `CreateTopic(topicId, labels)`
- **THEN** it returns `Right(List(TopicCreated(topicId, labels)))`, and evolving that event yields a state marked existing with those labels

#### Scenario: Edge case — creating an existing topic is rejected
- **GIVEN** a topic state that already exists
- **WHEN** `decide` handles `CreateTopic` again
- **THEN** it returns a `Left(Rejection)` indicating the topic already exists, and no event is produced

#### Scenario: Edge case — re-creating a deleted topic is rejected
- **GIVEN** a topic that was created and then deleted
- **WHEN** `decide` handles `CreateTopic` for the same id
- **THEN** it returns a `Left(Rejection)` (the id has been used) and no event is produced

#### Scenario: Edge case — decide is a total function (no exceptions)
- **GIVEN** any command applied to any state
- **WHEN** `decide` is invoked
- **THEN** it returns an `Either` (Right events or Left rejection) and never throws

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

### Requirement: Delete a topic

For a `DeleteTopic` command on an active topic, `decide` SHALL return a `TopicDeleted` event and `evolve` SHALL mark the topic deleted; a deleted topic SHALL reject all further commands (publish, update, delete). Deleting a non-existent or already-deleted topic SHALL be rejected.

#### Scenario: Deleting an active topic emits TopicDeleted
- **GIVEN** an active topic
- **WHEN** `decide` handles `DeleteTopic`
- **THEN** it returns `Right(List(TopicDeleted(topicId)))`, and evolving marks the topic deleted

#### Scenario: Edge case — deleting a non-existent topic is rejected
- **GIVEN** an empty (non-existent) topic state
- **WHEN** `decide` handles `DeleteTopic`
- **THEN** it returns a `Left(Rejection)` (not found) and no event is produced

#### Scenario: Edge case — deleting an already-deleted topic is rejected
- **GIVEN** a topic that has already been deleted
- **WHEN** `decide` handles `DeleteTopic`
- **THEN** it returns a `Left(Rejection)` (not found) and no event is produced

### Requirement: Update topic labels

For an `UpdateTopic(labels)` command on an active topic, `decide` SHALL return a `TopicLabelsUpdated` event and `evolve` SHALL replace the topic's labels with the new set. Updating a non-existent or deleted topic SHALL be rejected.

#### Scenario: Updating an active topic replaces its labels
- **GIVEN** an active topic with some labels
- **WHEN** `decide` handles `UpdateTopic(newLabels)`
- **THEN** it returns `Right(List(TopicLabelsUpdated(topicId, newLabels)))`, and evolving replaces the stored labels with `newLabels`

#### Scenario: Edge case — updating a non-existent topic is rejected
- **GIVEN** an empty (non-existent) topic state
- **WHEN** `decide` handles `UpdateTopic(labels)`
- **THEN** it returns a `Left(Rejection)` (not found) and no event is produced

#### Scenario: Edge case — updating a deleted topic is rejected
- **GIVEN** a topic that has been deleted
- **WHEN** `decide` handles `UpdateTopic(labels)`
- **THEN** it returns a `Left(Rejection)` (not found) and no event is produced
