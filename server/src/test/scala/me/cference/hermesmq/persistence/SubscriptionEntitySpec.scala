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

  private val subId       = SubscriptionId.from("sub-1").toOption.get
  private val topicId     = TopicId.from("orders").toOption.get
  private val ackId       = AckId.from("ack-1").toOption.get
  private val msgId       = MessageId.from("m-1").toOption.get
  private val message     = Message.from(msgId, "hi".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z")).toOption.get
  private val now         = Instant.parse("2026-07-07T12:00:00Z")
  private val ackDeadline = 30.seconds

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

  private def pull(max: Int) =
    kit.runCommand[Option[List[PulledMessage]]](replyTo => SubscriptionEntityCommand.Pull(max, ackDeadline, now, replyTo))

  "SubscriptionEntity" should {
    "persist SubscriptionCreated and reply Accepted on create" in {
      val result = send(CreateSubscription(subId, topicId))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe SubscriptionCreated(subId, topicId)
    }

    "persist MessageDelivered and add an available outstanding message" in {
      send(CreateSubscription(subId, topicId))
      val result = send(RecordDelivery(ackId, message))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe MessageDelivered(ackId, message)
      result.state.outstanding(ackId).available shouldBe true
    }

    "lease an available message on pull, persisting MessageLeased with a deadline" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message))
      val result = pull(10)
      result.reply shouldBe Some(List(PulledMessage(ackId, message)))
      result.event shouldBe MessageLeased(List(ackId), now.plusMillis(ackDeadline.toMillis))
      result.state.outstanding(ackId).lease shouldBe LeaseState.Leased(now.plusMillis(ackDeadline.toMillis))
    }

    "not re-lease a message that is already leased (invisible until the deadline)" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message))
      pull(10)
      pull(10).reply shouldBe Some(Nil)
    }

    "reply to a pull on an empty subscription with nothing, without persisting" in {
      send(CreateSubscription(subId, topicId))
      val result = pull(10)
      result.reply shouldBe Some(Nil)
      result.hasNoEvents shouldBe true
    }

    "respect the requested max on a pull" in {
      val ackId2 = AckId.from("ack-2").toOption.get
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message))
      send(RecordDelivery(ackId2, message))
      pull(1).reply.get.size shouldBe 1
    }

    "persist MessageAcknowledged and remove the outstanding message" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message))
      val result = send(Acknowledge(ackId))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe MessageAcknowledged(ackId)
      result.state.outstanding.contains(ackId) shouldBe false
    }

    "persist AckDeadlineModified and re-lease with the new deadline" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message))
      val result = send(ModifyAckDeadline(ackId, 60.seconds, now))
      result.reply shouldBe CommandReply.Accepted
      result.event shouldBe AckDeadlineModified(ackId, now.plusMillis(60.seconds.toMillis))
      result.state.outstanding(ackId).lease shouldBe LeaseState.Leased(now.plusMillis(60.seconds.toMillis))
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
