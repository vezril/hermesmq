package me.cference.hermesmq.persistence

import me.cference.hermesmq.config.RetentionConfig
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.apache.pekko.persistence.testkit.scaladsl.PersistenceTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/** Tests that journal events are deleted (retained) after snapshotting, while
  * recovery of current state stays correct.
  */
final class RetentionDeletionSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with Matchers:

  import SubscriptionCommand.*

  private val subId   = SubscriptionId.from("sub-retain").toOption.get
  private val topicId = TopicId.from("orders").toOption.get
  private def ack(i: Int) = AckId.from(s"ack-$i").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  private val persistenceId = SubscriptionEntity.persistenceId(subId).id

  private def kitWith(retention: RetentionConfig) =
    EventSourcedBehaviorTestKit[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState](
      system,
      SubscriptionEntity(subId, retention),
      SerializationSettings.disabled
    )

  private def send(kit: EventSourcedBehaviorTestKit[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState], cmd: SubscriptionCommand): Unit =
    val _ = kit.runCommand[CommandReply](replyTo => SubscriptionEntityCommand.Submit(cmd, replyTo))

  "Journal retention" should {
    "delete events older than the retained snapshot window while recovery stays correct" in {
      val persistence = PersistenceTestKit(system)
      val kit         = kitWith(RetentionConfig(snapshotEveryEvents = 2, keepNSnapshots = 1))
      kit.clear()

      send(kit, CreateSubscription(subId, topicId))
      val total = 10
      (1 to total).foreach(i => send(kit, RecordDelivery(ack(i), message)))
      val before = kit.getState()

      // Old events have been purged: fewer events remain in the journal than were persisted.
      val _ = persistence.persistedInStorage(persistenceId).size should be < (total + 1)

      // Recovery from the retained snapshot + surviving events still yields the same state.
      kit.restart().state shouldBe before
    }

    "recover a subscription that acked everything to no outstanding messages, with a bounded journal" in {
      val persistence = PersistenceTestKit(system)
      val kit         = kitWith(RetentionConfig(snapshotEveryEvents = 2, keepNSnapshots = 1))
      kit.clear()

      send(kit, CreateSubscription(subId, topicId))
      val n = 8
      (1 to n).foreach { i =>
        send(kit, RecordDelivery(ack(i), message)) // deliver
        send(kit, Acknowledge(ack(i)))             // and ack (removes it)
      }

      val restored = kit.restart().state
      val _ = restored.outstanding shouldBe empty
      // Journal bounded well below the ~17 events that were persisted over the lifetime.
      persistence.persistedInStorage(persistenceId).size should be < (2 * n + 1)
    }
  }
