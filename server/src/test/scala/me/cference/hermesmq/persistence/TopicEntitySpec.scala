package me.cference.hermesmq.persistence

import me.cference.hermesmq.config.DedupConfig
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.duration.*

/** Tests the persistent Topic entity via the in-memory journal: persist-then-
  * reply for writes, the non-persisted query, and rejections.
  */
final class TopicEntitySpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterEach:

  import TopicCommand.*
  import TopicEvent.*

  private val topicId = TopicId.from("orders").toOption.get
  private val labels  = Map("team" -> "payments")
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  private val kit =
    EventSourcedBehaviorTestKit[TopicEntityCommand, TopicEvent, TopicState](
      system,
      TopicEntity(topicId),
      SerializationSettings.disabled
    )

  // A separate entity (distinct persistence id) with dedup enabled.
  private val t0           = Instant.parse("2026-07-07T00:00:00Z")
  private val dedupTopicId = TopicId.from("orders-dedup").toOption.get
  private val dedupKit =
    EventSourcedBehaviorTestKit[TopicEntityCommand, TopicEvent, TopicState](
      system,
      TopicEntity(dedupTopicId, dedup = DedupConfig(1.hour)),
      SerializationSettings.disabled
    )

  override protected def beforeEach(): Unit =
    super.beforeEach()
    kit.clear()
    dedupKit.clear()

  private def submit(command: TopicCommand) =
    kit.runCommand[CommandReply](replyTo => TopicEntityCommand.Submit(command, replyTo))

  private def dedupSubmit(command: TopicCommand) =
    dedupKit.runCommand[CommandReply](replyTo => TopicEntityCommand.Submit(command, replyTo))

  private def keyed(id: String, key: String, at: Instant) =
    Message.from(MessageId.from(id).toOption.get, "x".getBytes, Map.empty, at, idempotencyKey = Some(key)).toOption.get

  private def get() =
    kit.runCommand[Option[TopicSnapshot]](replyTo => TopicEntityCommand.Query(replyTo))

  "TopicEntity" should {
    "persist TopicCreated with labels and reply Accepted on create" in {
      val r = submit(CreateTopic(topicId, labels))
      r.reply shouldBe CommandReply.Accepted
      r.event shouldBe TopicCreated(topicId, labels)
    }

    "persist MessagePublished when publishing to an existing topic" in {
      submit(CreateTopic(topicId))
      val r = submit(Publish(message))
      r.reply shouldBe CommandReply.Published(message.id, deduplicated = false)
      r.event shouldBe MessagePublished(message)
    }

    "reply Published(newId, deduplicated=false) and persist a fresh keyed publish" in {
      dedupSubmit(CreateTopic(dedupTopicId))
      val m = keyed("k-1", "abc", t0)
      val r = dedupSubmit(Publish(m))
      r.reply shouldBe CommandReply.Published(m.id, deduplicated = false)
      r.event shouldBe MessagePublished(m)
    }

    "reply Published(originalId, deduplicated=true) and persist nothing on a retry within the window" in {
      dedupSubmit(CreateTopic(dedupTopicId))
      val first = keyed("k-1", "abc", t0)
      dedupSubmit(Publish(first))
      val retry = keyed("k-2", "abc", t0.plusSeconds(60)) // within the 1h window
      val r     = dedupSubmit(Publish(retry))
      r.reply shouldBe CommandReply.Published(first.id, deduplicated = true)
      r.hasNoEvents shouldBe true
    }

    "persist TopicDeleted on delete" in {
      submit(CreateTopic(topicId))
      val r = submit(DeleteTopic)
      r.reply shouldBe CommandReply.Accepted
      r.event shouldBe TopicDeleted(topicId)
    }

    "persist TopicLabelsUpdated on update" in {
      submit(CreateTopic(topicId))
      val r = submit(UpdateTopic(labels))
      r.reply shouldBe CommandReply.Accepted
      r.event shouldBe TopicLabelsUpdated(topicId, labels)
    }

    "reply to a query with the current snapshot without persisting" in {
      submit(CreateTopic(topicId, labels))
      val r = get()
      r.reply shouldBe Some(TopicSnapshot(topicId, labels))
      r.hasNoEvents shouldBe true
    }

    "reply None to a query for a non-existent topic" in {
      get().reply shouldBe None
    }

    "reply None to a query for a deleted topic" in {
      submit(CreateTopic(topicId))
      submit(DeleteTopic)
      get().reply shouldBe None
    }

    "reply Rejected and persist nothing when creating an existing topic" in {
      submit(CreateTopic(topicId))
      val r = submit(CreateTopic(topicId))
      r.reply shouldBe CommandReply.Rejected(Rejection.TopicAlreadyExists)
      r.hasNoEvents shouldBe true
    }

    "reply Rejected and persist nothing when deleting a non-existent topic" in {
      val r = submit(DeleteTopic)
      r.reply shouldBe CommandReply.Rejected(Rejection.TopicNotFound)
      r.hasNoEvents shouldBe true
    }
  }
