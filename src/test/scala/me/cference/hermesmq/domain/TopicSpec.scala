package me.cference.hermesmq.domain

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant

/** Tests the pure Topic aggregate: `decide` (command → events or rejection) and
  * `evolve` (state ⊕ event → state).
  */
final class TopicSpec extends AnyFunSuite:

  import TopicCommand.*
  import TopicEvent.*

  private val topicId = TopicId.from("orders").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  test("creating a new topic emits TopicCreated and evolves to existing") {
    val events = Topic.decide(Topic.empty, CreateTopic(topicId))
    assert(events == Right(List(TopicCreated(topicId))))
    val state = events.toOption.get.foldLeft(Topic.empty)(Topic.evolve)
    assert(state.exists)
  }

  test("publishing to an existing topic emits MessagePublished") {
    val existing = Topic.evolve(Topic.empty, TopicCreated(topicId))
    assert(Topic.decide(existing, Publish(message)) == Right(List(MessagePublished(message))))
  }

  test("creating an existing topic is rejected") {
    val existing = Topic.evolve(Topic.empty, TopicCreated(topicId))
    assert(Topic.decide(existing, CreateTopic(topicId)) == Left(Rejection.TopicAlreadyExists))
  }

  test("publishing to a non-existent topic is rejected") {
    assert(Topic.decide(Topic.empty, Publish(message)) == Left(Rejection.TopicNotFound))
  }

  test("evolve is total: applying MessagePublished never throws and leaves existence intact") {
    val existing = Topic.evolve(Topic.empty, TopicCreated(topicId))
    val after = Topic.evolve(existing, MessagePublished(message))
    assert(after.exists)
  }
