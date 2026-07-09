package me.cference.hermesmq.persistence

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/** Verifies aggregate-state snapshots serialize with the explicit JSON serializer
  * (never Java) and round-trip losslessly. Loads the real `application.conf` so
  * the serializer bindings are in effect.
  */
final class SnapshotSerializationSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyWordSpecLike
    with Matchers:

  private val serialization = SerializationExtension(system.classicSystem)

  private def roundTrip[T <: AnyRef](obj: T): T =
    val serializer = serialization.findSerializerFor(obj)
    val manifest   = Serializers.manifestFor(serializer, obj)
    val bytes      = serialization.serialize(obj).get
    serialization.deserialize(bytes, serializer.identifier, manifest).get.asInstanceOf[T]

  private val topicId = TopicId.from("orders").toOption.get
  private val subId   = SubscriptionId.from("s1").toOption.get
  private val ackA    = AckId.from("ack-a").toOption.get
  private val ackB    = AckId.from("ack-b").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  "TopicState snapshots" should {
    "round-trip a topic with labels" in {
      val s = TopicState(Some(topicId), Map("team" -> "pay"), deleted = false)
      roundTrip[TopicState](s) shouldBe s
    }
    "round-trip a deleted topic" in {
      val s = TopicState(Some(topicId), Map.empty, deleted = true)
      roundTrip[TopicState](s) shouldBe s
    }
    "round-trip a topic state carrying a seen dedup set" in {
      val s = TopicState(
        Some(topicId),
        Map.empty,
        deleted = false,
        seen = Map("abc" -> SeenPublish(MessageId.from("m-1").toOption.get, Instant.parse("2026-07-07T00:00:00Z")))
      )
      roundTrip[TopicState](s) shouldBe s
    }
    "read a legacy topic snapshot (no seen field) as an empty seen set" in {
      import JsonFormats.given
      import spray.json.*
      """{"topicId":"orders","labels":{},"deleted":false}""".parseJson.convertTo[TopicState].seen shouldBe empty
    }
    "round-trip the empty topic state" in {
      roundTrip[TopicState](Topic.empty) shouldBe Topic.empty
    }
  }

  "SubscriptionState snapshots" should {
    "round-trip outstanding messages in AVAILABLE and LEASED states with attempt counts" in {
      val s = SubscriptionState(
        subscriptionId = Some(subId),
        topicId = Some(topicId),
        outstanding = Map(
          ackA -> Outstanding(message, LeaseState.Available, attempts = 0),
          ackB -> Outstanding(message, LeaseState.Leased(Instant.parse("2026-07-07T12:00:30Z")), attempts = 2)
        )
      )
      roundTrip[SubscriptionState](s) shouldBe s
    }
    "round-trip the empty subscription state" in {
      roundTrip[SubscriptionState](Subscription.empty) shouldBe Subscription.empty
    }
  }

  "The snapshot serializer" should {
    "bind both state types to the explicit JSON serializer" in {
      serialization.serializerFor(classOf[TopicState]).getClass shouldBe classOf[DomainEventSerializer]
      serialization.serializerFor(classOf[SubscriptionState]).getClass shouldBe classOf[DomainEventSerializer]
    }
    "leave Java serialization disabled (an unbound Serializable is refused)" in {
      serialization.serialize(new java.io.Serializable {}).isFailure shouldBe true
    }
  }
