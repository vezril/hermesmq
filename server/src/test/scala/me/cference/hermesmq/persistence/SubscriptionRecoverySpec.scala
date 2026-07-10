package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/** Verifies recovery-by-replay: after a restart, state is rebuilt from the
  * journal so accepted state (and outstanding messages) survive a crash.
  */
final class SubscriptionRecoverySpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterEach:

  import SubscriptionCommand.*

  private val subId    = SubscriptionId.from("sub-1").toOption.get
  private val topicId  = TopicId.from("orders").toOption.get
  private val ackId    = AckId.from("ack-1").toOption.get
  private val msgId    = MessageId.from("m-1").toOption.get
  private val message  = Message.from(msgId, "hi".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z")).toOption.get

  private val kit =
    EventSourcedBehaviorTestKit[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState](
      system,
      SubscriptionEntity(subId),
      SerializationSettings.disabled
    )

  override protected def beforeEach(): Unit =
    super.beforeEach()
    kit.clear()

  private def send(command: SubscriptionCommand): Unit =
    val _ = kit.runCommand[CommandReply](replyTo => SubscriptionEntityCommand.Submit(command, replyTo))

  "Subscription recovery" should {
    "rebuild an outstanding message from the journal after restart" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message))

      val recovered = kit.restart().state
      val _ = recovered.exists shouldBe true
      recovered.outstanding.contains(ackId) shouldBe true
    }

    "not resurrect an acknowledged message after restart" in {
      send(CreateSubscription(subId, topicId))
      send(RecordDelivery(ackId, message))
      send(Acknowledge(ackId))

      val recovered = kit.restart().state
      recovered.outstanding.contains(ackId) shouldBe false
    }

    "recover a fresh persistence id to empty state and accept a create" in {
      val recovered = kit.restart().state
      val _ = recovered.exists shouldBe false

      val result = kit.runCommand[CommandReply](replyTo => SubscriptionEntityCommand.Submit(CreateSubscription(subId, topicId), replyTo))
      result.reply shouldBe CommandReply.Accepted
    }
  }
