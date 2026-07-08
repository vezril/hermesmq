package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

/** Tests the pure event→tags mapping: the new stats tags are additive and the
  * existing index/lease/message tags are unchanged.
  */
final class EventTaggingSpec extends AnyWordSpec with Matchers:

  private val topic = TopicId.from("orders").toOption.get
  private val sub   = SubscriptionId.from("s1").toOption.get
  private val ackId = AckId.from("ack-1").toOption.get
  private val msg   = Message.from(MessageId.from("m").toOption.get, "x".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z")).toOption.get

  "Subscription event tags" should {
    "tag every event with the stats tag" in {
      val events: List[SubscriptionEvent] = List(
        SubscriptionEvent.SubscriptionCreated(sub, topic),
        SubscriptionEvent.MessageDelivered(ackId, msg),
        SubscriptionEvent.MessageLeased(List(ackId), Instant.parse("2026-07-07T12:00:00Z")),
        SubscriptionEvent.MessageAcknowledged(ackId),
        SubscriptionEvent.AckDeadlineExpired(ackId, 1),
        SubscriptionEvent.MessageDeadLettered(ackId, msg, 5)
      )
      events.foreach(e => SubscriptionEntity.tagsFor(e) should contain(SubscriptionEntity.StatsTag))
    }

    "keep the created tag only on SubscriptionCreated and the lease tag on lease-lifecycle events" in {
      SubscriptionEntity.tagsFor(SubscriptionEvent.SubscriptionCreated(sub, topic)) shouldBe
        Set(SubscriptionEntity.CreatedTag, SubscriptionEntity.StatsTag)
      SubscriptionEntity.tagsFor(SubscriptionEvent.MessageDelivered(ackId, msg)) shouldBe Set(SubscriptionEntity.StatsTag)
      SubscriptionEntity.tagsFor(SubscriptionEvent.MessageAcknowledged(ackId)) shouldBe
        Set(SubscriptionEntity.LeaseTag, SubscriptionEntity.StatsTag)
    }
  }

  "Topic event tags" should {
    "tag create/publish/delete with the stats tag, and keep the message tag on publish" in {
      TopicEntity.tagsFor(TopicEvent.MessagePublished(msg)) shouldBe Set(TopicEntity.MessageTag, TopicEntity.StatsTag)
      TopicEntity.tagsFor(TopicEvent.TopicCreated(topic)) shouldBe Set(TopicEntity.StatsTag)
      TopicEntity.tagsFor(TopicEvent.TopicDeleted(topic)) shouldBe Set(TopicEntity.StatsTag)
    }

    "not tag TopicLabelsUpdated (no stats impact)" in {
      TopicEntity.tagsFor(TopicEvent.TopicLabelsUpdated(topic, Map("k" -> "v"))) shouldBe Set.empty
    }
  }
