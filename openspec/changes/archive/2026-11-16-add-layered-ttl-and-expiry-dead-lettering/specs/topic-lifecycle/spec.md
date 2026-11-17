# Spec Delta

## ADDED Requirements

### Requirement: A topic carries an optional TTL

A topic SHALL carry an optional time-to-live, settable when it is created and changeable afterwards.
The topic's TTL SHALL apply to messages published to it that do not name a shorter one of their own,
and SHALL be journaled so it survives recovery. Changing a topic's TTL SHALL affect subsequently
published messages and SHALL NOT retroactively change the expiry of messages already published.

#### Scenario: A topic created with a TTL applies it to its messages
- **GIVEN** a topic created with a one-hour TTL
- **WHEN** a message is published to it without its own `ttlSeconds`
- **THEN** the message expires one hour after publish

#### Scenario: Updating a topic's TTL affects only later publishes
- **GIVEN** a topic with a one-hour TTL and a message already published under it
- **WHEN** the topic's TTL is updated to one minute and another message is published
- **THEN** the new message expires a minute after its publish and the earlier message keeps its original expiry

#### Scenario: The TTL survives recovery
- **GIVEN** a topic created with a TTL
- **WHEN** its aggregate is recovered from the journal
- **THEN** the recovered state carries the same TTL

#### Scenario: Edge case — a topic journaled before TTLs existed has none
- **GIVEN** a `TopicCreated` event journaled before topic TTLs existed
- **WHEN** it is deserialized during recovery
- **THEN** the topic has no TTL rather than failing to parse
