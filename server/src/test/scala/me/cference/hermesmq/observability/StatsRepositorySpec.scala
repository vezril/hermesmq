package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Tests the in-memory stats read models and the pure event folds. */
final class StatsRepositorySpec extends AnyWordSpec with Matchers:

  private val sub    = SubscriptionId.from("s1").toOption.get
  private val topic  = TopicId.from("orders").toOption.get
  private def ack(i: Int) = AckId.from(s"ack-$i").toOption.get
  private val t0     = Instant.parse("2026-07-07T12:00:00Z")
  private def msgAt(at: Instant) =
    Message.from(MessageId.from("m").toOption.get, "x".getBytes, Map.empty, at).toOption.get

  private def await[A](f: => scala.concurrent.Future[A]): A = Await.result(f, 3.seconds)

  "Subscription stats" should {
    "count backlog as delivered minus acked/dead-lettered" in {
      val repo = InMemorySubscriptionStatsRepository()
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.SubscriptionCreated(sub, topic))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDelivered(ack(1), msgAt(t0)))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDelivered(ack(2), msgAt(t0)))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDelivered(ack(3), msgAt(t0)))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageAcknowledged(ack(1)))

      await(repo.list()).head.backlog shouldBe 2
    }

    "report the oldest outstanding message's delivery time and derive its age" in {
      val repo = InMemorySubscriptionStatsRepository()
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.SubscriptionCreated(sub, topic))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDelivered(ack(1), msgAt(t0)))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDelivered(ack(2), msgAt(t0.plusSeconds(30))))

      val s = await(repo.list()).head
      val _ = s.oldestUnackedAt shouldBe Some(t0)
      s.oldestUnackedAgeSeconds(t0.plusSeconds(90)) shouldBe 90
    }

    "accrue redelivery and dead-letter counts without redelivery changing the backlog" in {
      val repo = InMemorySubscriptionStatsRepository()
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.SubscriptionCreated(sub, topic))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDelivered(ack(1), msgAt(t0)))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.AckDeadlineExpired(ack(1), 1)) // redelivery, still outstanding
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDeadLettered(ack(1), msgAt(t0), 5))

      val s = await(repo.list()).head
      val _ = s.redeliveredTotal shouldBe 1
      val _ = s.deadLetteredTotal shouldBe 1
      s.backlog shouldBe 0 // dead-letter removed it
    }

    "edge: an empty backlog reports zero age" in {
      val repo = InMemorySubscriptionStatsRepository()
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.SubscriptionCreated(sub, topic))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageDelivered(ack(1), msgAt(t0)))
      SubscriptionStatsFold(repo, sub, SubscriptionEvent.MessageAcknowledged(ack(1)))

      val s = await(repo.list()).head
      val _ = s.backlog shouldBe 0
      val _ = s.oldestUnackedAt shouldBe None
      s.oldestUnackedAgeSeconds(t0.plusSeconds(100)) shouldBe 0
    }
  }

  "Topic stats" should {
    "register a topic with zero count on creation and increment on publish" in {
      val repo = InMemoryTopicStatsRepository()
      TopicStatsFold(repo, topic, TopicEvent.TopicCreated(topic))
      val _ = await(repo.list()).head shouldBe TopicStats(topic, 0, deleted = false)

      TopicStatsFold(repo, topic, TopicEvent.MessagePublished(msgAt(t0)))
      TopicStatsFold(repo, topic, TopicEvent.MessagePublished(msgAt(t0)))
      await(repo.list()).head.publishedTotal shouldBe 2
    }

    "mark a topic deleted" in {
      val repo = InMemoryTopicStatsRepository()
      TopicStatsFold(repo, topic, TopicEvent.TopicCreated(topic))
      TopicStatsFold(repo, topic, TopicEvent.TopicDeleted(topic))
      await(repo.list()).head.deleted shouldBe true
    }

    "edge: list is empty when no topics exist" in {
      await(InMemoryTopicStatsRepository().list()) shouldBe Nil
    }
  }
