## ADDED Requirements

### Requirement: Create a topic

The Topic aggregate SHALL provide a pure `decide(state, command)` that, for a `CreateTopic` command on a not-yet-existing topic, returns a single `TopicCreated` event; `evolve(state, event)` SHALL then mark the topic as existing. Creating a topic that already exists SHALL be rejected.

#### Scenario: Creating a new topic emits TopicCreated
- **GIVEN** an empty (non-existent) topic state
- **WHEN** `decide` handles `CreateTopic(topicId)`
- **THEN** it returns `Right(List(TopicCreated(topicId)))`, and evolving that event yields a state marked as existing

#### Scenario: Edge case — creating an existing topic is rejected
- **GIVEN** a topic state that already exists
- **WHEN** `decide` handles `CreateTopic` again
- **THEN** it returns a `Left(Rejection)` indicating the topic already exists, and no event is produced

#### Scenario: Edge case — decide is a total function (no exceptions)
- **GIVEN** any command applied to any state
- **WHEN** `decide` is invoked
- **THEN** it returns an `Either` (Right events or Left rejection) and never throws

### Requirement: Publish a message to a topic

For a `Publish` command carrying a fully-formed `Message`, `decide` SHALL return a `MessagePublished` event when the topic exists, and reject publishing to a non-existent topic.

#### Scenario: Publishing to an existing topic emits MessagePublished
- **GIVEN** an existing topic
- **WHEN** `decide` handles `Publish(message)`
- **THEN** it returns `Right(List(MessagePublished(message)))`

#### Scenario: Edge case — publishing to a non-existent topic is rejected
- **GIVEN** an empty (non-existent) topic state
- **WHEN** `decide` handles `Publish(message)`
- **THEN** it returns a `Left(Rejection)` indicating the topic does not exist, and no event is produced

#### Scenario: Edge case — replaying a duplicate publish event is idempotent in state shape
- **GIVEN** an existing topic
- **WHEN** the same `MessagePublished` event is applied to state via `evolve`
- **THEN** `evolve` remains total and does not throw (state transition is well-defined for every event)
