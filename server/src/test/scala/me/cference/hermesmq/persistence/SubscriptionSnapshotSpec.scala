package me.cference.hermesmq.persistence

import me.cference.hermesmq.config.RetentionConfig
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit
import org.apache.pekko.persistence.testkit.scaladsl.EventSourcedBehaviorTestKit.SerializationSettings
import org.apache.pekko.persistence.testkit.scaladsl.SnapshotTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/** Tests snapshot cadence and snapshot-bounded, transparent recovery for the
  * Subscription entity via the in-memory journal + snapshot store.
  */
final class SubscriptionSnapshotSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with Matchers:

  import SubscriptionCommand.*

  private val subId   = SubscriptionId.from("sub-snap").toOption.get
  private val topicId = TopicId.from("orders").toOption.get
  private def ack(i: Int) = AckId.from(s"ack-$i").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  private def kitWith(retention: RetentionConfig) =
    EventSourcedBehaviorTestKit[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState](
      system,
      SubscriptionEntity(subId, retention),
      SerializationSettings.disabled // snapshot serialization is covered by SnapshotSerializationSpec
    )

  private def send(kit: EventSourcedBehaviorTestKit[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState], cmd: SubscriptionCommand): Unit =
    val _ = kit.runCommand[CommandReply](replyTo => SubscriptionEntityCommand.Submit(cmd, replyTo))

  "SubscriptionEntity snapshots" should {
    "persist a snapshot after the configured number of events" in {
      val snapshots = SnapshotTestKit(system)
      val kit       = kitWith(RetentionConfig(snapshotEveryEvents = 2, keepNSnapshots = 2))
      kit.clear()

      send(kit, CreateSubscription(subId, topicId)) // event 1
      send(kit, RecordDelivery(ack(1), message))    // event 2 → snapshot boundary

      snapshots.persistedInStorage(SubscriptionEntity.persistenceId(subId).id) should not be empty
    }

    "recover to the same state after restart (snapshot-bounded, transparent)" in {
      val kit = kitWith(RetentionConfig(snapshotEveryEvents = 2, keepNSnapshots = 2))
      kit.clear()

      send(kit, CreateSubscription(subId, topicId))
      (1 to 5).foreach(i => send(kit, RecordDelivery(ack(i), message))) // crosses several snapshot boundaries
      send(kit, Acknowledge(ack(1)))
      val before = kit.getState()

      kit.restart().state shouldBe before
    }

    "recover correctly with fewer than N events (no snapshot yet, full replay)" in {
      val kit = kitWith(RetentionConfig(snapshotEveryEvents = 100, keepNSnapshots = 2))
      kit.clear()

      send(kit, CreateSubscription(subId, topicId))
      send(kit, RecordDelivery(ack(1), message))
      val before = kit.getState()

      val restored = kit.restart().state
      val _ = restored shouldBe before
      restored.outstanding.contains(ack(1)) shouldBe true
    }
  }
