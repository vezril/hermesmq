package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.duration.*

/** Tests the persistent Subscription entity via the in-memory journal. */
final class SubscriptionEntitySpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterEach:

  import SubscriptionCommand.*
  import SubscriptionEvent.*

  private val subId    = SubscriptionId.from("sub-1").toOption.get
  private val topicId  = TopicId.from("orders").toOption.get
  private val ackId    = AckId.from("ack-1").toOption.get
  private val msgId    = MessageId.from("m-1").toOption.get
  private val message  = Message.from(msgId, "hi".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z")).toOption.get
  private val deadline = AckDeadline.from(30.seconds).toOption.get
  private val extended = AckDeadline.from(60.seconds).toOption.get

  private val kit =
    EventSourcedBehaviorTestKit[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState](
      system,
      SubscriptionEntity(subId),
      SerializationSettings.disabled
    )

  override protected def beforeEach(): Unit =
    super.beforeEach()
    kit.clear()

  private def send(command: SubscriptionCommand) =
    kit.runCommand[CommandReply](replyTo => SubscriptionEntityCommand.Submit(command, replyTo))

  "SubscriptionEntity" should {
    "persist SubscriptionCreated and reply Accepted on create" in {
      val result = send(CreateSubscription(subId, topicId))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe SubscriptionCreated(subId, topicId)
    }

    "persist MessageDelivered and add an outstanding message" in {
      send(CreateSubscription(subId, topicId))
      val result = send(RecordDelivery(ackId, message, deadline))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe MessageDelivered(ackId, message, deadline)
      result.state.outstanding.contains(ackId) shouldBe true
    }

    "reply to a pull with outstanding messages, without persisting" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message, deadline))
      val result = kit.runCommand[Option[List[PulledMessage]]](replyTo => SubscriptionEntityCommand.Pull(10, replyTo))
      result.reply shouldBe Some(List(PulledMessage(ackId, message)))
      result.hasNoEvents shouldBe true
    }

    "reply to a pull on an empty subscription with nothing" in {
      send(CreateSubscription(subId, topicId))
      kit.runCommand[Option[List[PulledMessage]]](replyTo => SubscriptionEntityCommand.Pull(10, replyTo)).reply shouldBe Some(Nil)
    }

    "respect the requested max on a pull" in {
      val ackId2 = AckId.from("ack-2").toOption.get
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message, deadline))
      send(RecordDelivery(ackId2, message, deadline))
      val result = kit.runCommand[Option[List[PulledMessage]]](replyTo => SubscriptionEntityCommand.Pull(1, replyTo))
      result.reply.get.size shouldBe 1
    }

    "persist MessageAcknowledged and remove the outstanding message" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message, deadline))
      val result = send(Acknowledge(ackId))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe MessageAcknowledged(ackId)
      result.state.outstanding.contains(ackId) shouldBe false
    }

    "persist AckDeadlineModified and update the deadline" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message, deadline))
      val result = send(ModifyAckDeadline(ackId, extended))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe AckDeadlineModified(ackId, extended)
      result.state.outstanding(ackId).deadline shouldBe extended
    }

    "reply Rejected and persist nothing on duplicate create" in {
      send(CreateSubscription(subId, topicId))
      val result = send(CreateSubscription(subId, topicId))
      result.reply shouldBe CommandReply.Rejected(Rejection.SubscriptionAlreadyExists)
      result.hasNoEvents shouldBe true
    }

    "reply Rejected and persist nothing when acknowledging an unknown ackId" in {
      send(CreateSubscription(subId, topicId))
      val result = send(Acknowledge(ackId))
      result.reply shouldBe CommandReply.Rejected(Rejection.UnknownAckId(ackId))
      result.hasNoEvents shouldBe true
    }
  }
