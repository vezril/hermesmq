package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/** Tests the persistent Topic entity via the in-memory journal, asserting
  * persist-then-reply and rejection behavior. Serialization is exercised
  * separately (see `EventSerializationSpec`).
  */
final class TopicEntitySpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterEach:

  import TopicCommand.*
  import TopicEvent.*

  private val topicId = TopicId.from("orders").toOption.get
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

  override protected def beforeEach(): Unit =
    super.beforeEach()
    kit.clear()

  "TopicEntity" should {
    "persist TopicCreated and reply Accepted when creating a new topic" in {
      val result = kit.runCommand[CommandReply](replyTo => TopicEntityCommand(CreateTopic(topicId), replyTo))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe TopicCreated(topicId)
      result.state.exists shouldBe true
    }

    "persist MessagePublished and reply Accepted when publishing to an existing topic" in {
      kit.runCommand[CommandReply](replyTo => TopicEntityCommand(CreateTopic(topicId), replyTo))
      val result = kit.runCommand[CommandReply](replyTo => TopicEntityCommand(Publish(message), replyTo))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe MessagePublished(message)
    }

    "reply Rejected and persist nothing when creating an existing topic" in {
      kit.runCommand[CommandReply](replyTo => TopicEntityCommand(CreateTopic(topicId), replyTo))
      val result = kit.runCommand[CommandReply](replyTo => TopicEntityCommand(CreateTopic(topicId), replyTo))
      result.reply shouldBe CommandReply.Rejected(Rejection.TopicAlreadyExists)
      result.hasNoEvents shouldBe true
    }

    "reply Rejected and persist nothing when publishing to a non-existent topic" in {
      val result = kit.runCommand[CommandReply](replyTo => TopicEntityCommand(Publish(message), replyTo))
      result.reply shouldBe CommandReply.Rejected(Rejection.TopicNotFound)
      result.hasNoEvents shouldBe true
    }
  }
