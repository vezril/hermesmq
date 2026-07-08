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

  private val labels = Map("team" -> "payments")

  private def active(ls: Map[String, String] = Map.empty): TopicState =
    Topic.evolve(Topic.empty, TopicCreated(topicId, ls))

  private def deleted: TopicState =
    List(TopicCreated(topicId), TopicDeleted(topicId)).foldLeft(Topic.empty)(Topic.evolve)

  test("creating a topic carries its labels") {
    val events = Topic.decide(Topic.empty, CreateTopic(topicId, labels))
    assert(events == Right(List(TopicCreated(topicId, labels))))
    assert(events.toOption.get.foldLeft(Topic.empty)(Topic.evolve).labels == labels)
  }

  test("deleting an active topic emits TopicDeleted and marks it deleted") {
    val events = Topic.decide(active(), DeleteTopic)
    assert(events == Right(List(TopicDeleted(topicId))))
    assert(events.toOption.get.foldLeft(active())(Topic.evolve).deleted)
  }

  test("updating labels on an active topic emits TopicLabelsUpdated and replaces them") {
    val newLabels = Map("team" -> "core")
    val events = Topic.decide(active(labels), UpdateTopic(newLabels))
    assert(events == Right(List(TopicLabelsUpdated(topicId, newLabels))))
    assert(events.toOption.get.foldLeft(active(labels))(Topic.evolve).labels == newLabels)
  }

  test("re-creating a deleted topic is rejected") {
    assert(Topic.decide(deleted, CreateTopic(topicId)) == Left(Rejection.TopicAlreadyExists))
  }

  test("publish/update/delete on a deleted topic are rejected as not found") {
    assert(Topic.decide(deleted, Publish(message)) == Left(Rejection.TopicNotFound))
    assert(Topic.decide(deleted, UpdateTopic(labels)) == Left(Rejection.TopicNotFound))
    assert(Topic.decide(deleted, DeleteTopic) == Left(Rejection.TopicNotFound))
  }

  test("delete/update on a non-existent topic are rejected") {
    assert(Topic.decide(Topic.empty, DeleteTopic) == Left(Rejection.TopicNotFound))
    assert(Topic.decide(Topic.empty, UpdateTopic(labels)) == Left(Rejection.TopicNotFound))
  }
