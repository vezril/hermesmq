package me.cference.hermesmq.domain

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.concurrent.duration.*

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
    val _ = assert(events == Right(List(TopicCreated(topicId))))
    val state = events.toOption.get.foldLeft(Topic.empty)((s, e) => Topic.evolve(s, e))
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
    List(TopicCreated(topicId), TopicDeleted(topicId)).foldLeft(Topic.empty)((s, e) => Topic.evolve(s, e))

  test("creating a topic carries its labels") {
    val events = Topic.decide(Topic.empty, CreateTopic(topicId, labels))
    val _ = assert(events == Right(List(TopicCreated(topicId, labels))))
    assert(events.toOption.get.foldLeft(Topic.empty)((s, e) => Topic.evolve(s, e)).labels == labels)
  }

  test("deleting an active topic emits TopicDeleted and marks it deleted") {
    val events = Topic.decide(active(), DeleteTopic)
    val _ = assert(events == Right(List(TopicDeleted(topicId))))
    assert(events.toOption.get.foldLeft(active())((s, e) => Topic.evolve(s, e)).deleted)
  }

  test("updating labels on an active topic emits TopicLabelsUpdated and replaces them") {
    val newLabels = Map("team" -> "core")
    val events = Topic.decide(active(labels), UpdateTopic(newLabels))
    val _ = assert(events == Right(List(TopicLabelsUpdated(topicId, newLabels))))
    assert(events.toOption.get.foldLeft(active(labels))((s, e) => Topic.evolve(s, e)).labels == newLabels)
  }

  test("re-creating a deleted topic is rejected") {
    assert(Topic.decide(deleted, CreateTopic(topicId)) == Left(Rejection.TopicAlreadyExists))
  }

  test("publish/update/delete on a deleted topic are rejected as not found") {
    val _ = assert(Topic.decide(deleted, Publish(message)) == Left(Rejection.TopicNotFound))
    val _ = assert(Topic.decide(deleted, UpdateTopic(labels)) == Left(Rejection.TopicNotFound))
    assert(Topic.decide(deleted, DeleteTopic) == Left(Rejection.TopicNotFound))
  }

  test("delete/update on a non-existent topic are rejected") {
    val _ = assert(Topic.decide(Topic.empty, DeleteTopic) == Left(Rejection.TopicNotFound))
    assert(Topic.decide(Topic.empty, UpdateTopic(labels)) == Left(Rejection.TopicNotFound))
  }

  // ---- Idempotent publish (deduplication) ----------------------------------

  private val t0 = Instant.parse("2026-07-07T00:00:00Z")
  private val window = 10.minutes

  private def keyed(id: String, key: Option[String], at: Instant): Message =
    Message.from(MessageId.from(id).toOption.get, "x".getBytes, Map.empty, at, idempotencyKey = key).toOption.get

  /** Active topic that has already published `m-1` with key `abc` at `t0`. */
  private def seenAbc: TopicState =
    Topic.evolve(active(), MessagePublished(keyed("m-1", Some("abc"), t0)), window)

  test("publishing a new idempotency key emits MessagePublished and records it in seen") {
    val m = keyed("m-1", Some("abc"), t0)
    val _ = assert(Topic.decide(active(), Publish(m), window) == Right(List(MessagePublished(m))))
    val after = Topic.evolve(active(), MessagePublished(m), window)
    assert(after.seen.get("abc") == Some(SeenPublish(m.id, t0)))
  }

  test("a duplicate key within the window emits no event and duplicateOf returns the original id") {
    val original = MessageId.from("m-1").toOption.get
    val retry = keyed("m-2", Some("abc"), t0.plusSeconds(60)) // within 10m, different message id
    val _ = assert(Topic.decide(seenAbc, Publish(retry), window) == Right(Nil))
    assert(Topic.duplicateOf(seenAbc, retry, window) == Some(original))
  }

  test("a key seen longer ago than the window is treated as a new publish") {
    val late = keyed("m-2", Some("abc"), t0.plusSeconds(11 * 60)) // 11m > 10m window
    val _ = assert(Topic.decide(seenAbc, Publish(late), window) == Right(List(MessagePublished(late))))
    assert(Topic.duplicateOf(seenAbc, late, window).isEmpty)
  }

  test("different idempotency keys publish independently") {
    val other = keyed("m-2", Some("def"), t0.plusSeconds(60))
    val _ = assert(Topic.decide(seenAbc, Publish(other), window) == Right(List(MessagePublished(other))))
    assert(Topic.duplicateOf(seenAbc, other, window).isEmpty)
  }

  test("a publish without an idempotency key is never deduplicated") {
    val noKey = keyed("m-2", None, t0.plusSeconds(60))
    val _ = assert(Topic.decide(seenAbc, Publish(noKey), window) == Right(List(MessagePublished(noKey))))
    assert(Topic.duplicateOf(seenAbc, noKey, window).isEmpty)
  }

  test("with dedup disabled (window 0) a repeated key still publishes and is not tracked") {
    val m = keyed("m-1", Some("abc"), t0)
    val _ = assert(Topic.decide(active(), Publish(m), Duration.Zero) == Right(List(MessagePublished(m))))
    val after = Topic.evolve(active(), MessagePublished(m), Duration.Zero)
    val _ = assert(after.seen.isEmpty)
    assert(Topic.duplicateOf(active(), m, Duration.Zero).isEmpty)
  }

  test("evolve prunes seen entries older than publishTime minus the window") {
    val start = Topic.evolve(active(), MessagePublished(keyed("m-old", Some("old"), t0)), window)
    val mid   = Topic.evolve(start, MessagePublished(keyed("m-recent", Some("recent"), t0.plusSeconds(9 * 60))), window)
    // New publish at t0+11m; cutoff = t0+1m. "old"(t0) pruned, "recent"(t0+9m) kept, "new" added.
    val after = Topic.evolve(mid, MessagePublished(keyed("m-new", Some("new"), t0.plusSeconds(11 * 60))), window)
    assert(after.seen.keySet == Set("recent", "new"))
  }
