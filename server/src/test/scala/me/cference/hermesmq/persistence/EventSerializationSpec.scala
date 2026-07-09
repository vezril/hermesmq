package me.cference.hermesmq.persistence

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.serialization.{SerializationExtension, Serializers}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant

/** Verifies domain events serialize with the explicit JSON serializer and that
  * Java serialization is disabled. Loads the real `application.conf` so the
  * serializer bindings are in effect.
  */
final class EventSerializationSpec
    extends ScalaTestWithActorTestKit(ConfigFactory.load())
    with AnyWordSpecLike
    with Matchers:

  private val serialization = SerializationExtension(system.classicSystem)

  private def roundTrip[T <: AnyRef](obj: T): T =
    val serializer = serialization.findSerializerFor(obj)
    val manifest   = Serializers.manifestFor(serializer, obj)
    val bytes      = serialization.serialize(obj).get
    serialization.deserialize(bytes, serializer.identifier, manifest).get.asInstanceOf[T]

  private val topicId  = TopicId.from("orders").toOption.get
  private val subId    = SubscriptionId.from("sub-1").toOption.get
  private val ackId    = AckId.from("ack-1").toOption.get
  private val msgId    = MessageId.from("m-1").toOption.get
  private val deadline = Instant.parse("2026-07-07T12:00:30Z")
  private val message = Message
    .from(msgId, "hi".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  "Domain events" should {
    "round-trip TopicCreated" in {
      roundTrip[TopicEvent](TopicEvent.TopicCreated(topicId)) shouldBe TopicEvent.TopicCreated(topicId)
    }
    "round-trip TopicCreated with labels" in {
      val e = TopicEvent.TopicCreated(topicId, Map("team" -> "payments"))
      roundTrip[TopicEvent](e) shouldBe e
    }
    "round-trip TopicDeleted" in {
      roundTrip[TopicEvent](TopicEvent.TopicDeleted(topicId)) shouldBe TopicEvent.TopicDeleted(topicId)
    }
    "round-trip TopicLabelsUpdated" in {
      val e = TopicEvent.TopicLabelsUpdated(topicId, Map("team" -> "core"))
      roundTrip[TopicEvent](e) shouldBe e
    }
    "deserialize a legacy TopicCreated (no labels field) with empty labels" in {
      import JsonFormats.given
      import spray.json.*
      """{"type":"TopicCreated","topicId":"orders"}""".parseJson.convertTo[TopicEvent] shouldBe
        TopicEvent.TopicCreated(topicId, Map.empty)
    }
    "round-trip MessagePublished" in {
      roundTrip[TopicEvent](TopicEvent.MessagePublished(message)) shouldBe TopicEvent.MessagePublished(message)
    }
    "round-trip MessagePublished carrying an idempotency key" in {
      val keyed = Message
        .from(msgId, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"), idempotencyKey = Some("abc"))
        .toOption
        .get
      roundTrip[TopicEvent](TopicEvent.MessagePublished(keyed)) shouldBe TopicEvent.MessagePublished(keyed)
    }
    "omit idempotencyKey when absent and read a legacy message (no key) as None" in {
      import JsonFormats.given
      import spray.json.*
      message.toJson.asJsObject.fields.contains("idempotencyKey") shouldBe false
      """{"id":"m-1","payload":"aGk=","attributes":{},"publishTime":"2026-07-07T00:00:00Z"}""".parseJson
        .convertTo[Message]
        .idempotencyKey shouldBe None
    }
    "round-trip SubscriptionCreated" in {
      roundTrip[SubscriptionEvent](SubscriptionEvent.SubscriptionCreated(subId, topicId)) shouldBe
        SubscriptionEvent.SubscriptionCreated(subId, topicId)
    }
    "round-trip MessageDelivered" in {
      roundTrip[SubscriptionEvent](SubscriptionEvent.MessageDelivered(ackId, message)) shouldBe
        SubscriptionEvent.MessageDelivered(ackId, message)
    }
    "round-trip MessageAcknowledged" in {
      roundTrip[SubscriptionEvent](SubscriptionEvent.MessageAcknowledged(ackId)) shouldBe
        SubscriptionEvent.MessageAcknowledged(ackId)
    }
    "round-trip AckDeadlineModified" in {
      roundTrip[SubscriptionEvent](SubscriptionEvent.AckDeadlineModified(ackId, deadline)) shouldBe
        SubscriptionEvent.AckDeadlineModified(ackId, deadline)
    }
    "round-trip MessageLeased" in {
      val e = SubscriptionEvent.MessageLeased(List(ackId), deadline)
      roundTrip[SubscriptionEvent](e) shouldBe e
    }
    "round-trip AckDeadlineExpired" in {
      val e = SubscriptionEvent.AckDeadlineExpired(ackId, 2)
      roundTrip[SubscriptionEvent](e) shouldBe e
    }
    "round-trip MessageDeadLettered" in {
      val e = SubscriptionEvent.MessageDeadLettered(ackId, message, 5)
      roundTrip[SubscriptionEvent](e) shouldBe e
    }
    "round-trip MessageExpired" in {
      val e = SubscriptionEvent.MessageExpired(ackId)
      roundTrip[SubscriptionEvent](e) shouldBe e
    }
    "round-trip a MessageDelivered whose message carries an expireTime" in {
      val ttlMsg = Message.from(msgId, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"), expireTime = Some(deadline)).toOption.get
      val e      = SubscriptionEvent.MessageDelivered(ackId, ttlMsg)
      val out    = roundTrip[SubscriptionEvent](e)
      out shouldBe e
      out.asInstanceOf[SubscriptionEvent.MessageDelivered].message.expireTime shouldBe Some(deadline)
    }
    "deserialize a message with no expireTime field to no expiry" in {
      import JsonFormats.given
      import spray.json.*
      val json = """{"type":"MessageDelivered","ackId":"ack-1","message":{"id":"m-1","payload":"aGk=","attributes":{},"publishTime":"2026-07-07T00:00:00Z"}}"""
      val ev   = json.parseJson.convertTo[SubscriptionEvent].asInstanceOf[SubscriptionEvent.MessageDelivered]
      ev.message.expireTime shouldBe None
    }
  }

  "Java serialization" should {
    "be disabled: an unbound Serializable is refused rather than Java-serialized" in {
      // A plain serializable with no binding must not fall back to Java serialization.
      val unbound = new java.io.Serializable {}
      serialization.serialize(unbound).isFailure shouldBe true
    }
  }
