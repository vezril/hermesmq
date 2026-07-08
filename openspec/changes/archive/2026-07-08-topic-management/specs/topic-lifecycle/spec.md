## MODIFIED Requirements

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

### Requirement: Publish a message to a topic

For a `Publish` command carrying a fully-formed `Message`, `decide` SHALL return a `MessagePublished` event only when the topic is active (created and not deleted), and SHALL reject publishing to a non-existent or deleted topic.

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

## ADDED Requirements

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
