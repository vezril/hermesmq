package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Tests the in-memory expiring-message read model and the pure event fold. */
final class ExpiringMessageProjectionSpec extends AnyWordSpec with Matchers:

  private val sub   = SubscriptionId.from("s1").toOption.get
  private val ack1  = AckId.from("ack-1").toOption.get
  private val t0    = Instant.parse("2026-07-07T12:00:00Z")
  private val later = t0.plusSeconds(60)
  private def msg(expire: Option[Instant]) =
    Message.from(MessageId.from("m").toOption.get, "x".getBytes, Map.empty, t0, expireTime = expire).toOption.get

  private def await[A](f: => scala.concurrent.Future[A]): A = Await.result(f, 3.seconds)
  private def index(repo: ExpiringMessageRepository, event: SubscriptionEvent): Unit =
    await(ExpiringMessageProjection.indexEvent(repo, sub, event))

  "ExpiringMessageProjection.indexEvent" should {
    "track a delivered message that has an expireTime" in {
      val repo = InMemoryExpiringMessageRepository()
      index(repo, SubscriptionEvent.MessageDelivered(ack1, msg(Some(later))))
      val _ = await(repo.expired(later)).map(_.ackId) shouldBe List(ack1)
      await(repo.expired(t0)) shouldBe Nil // not yet expired
    }

    "not track a delivered message with no expireTime" in {
      val repo = InMemoryExpiringMessageRepository()
      index(repo, SubscriptionEvent.MessageDelivered(ack1, msg(None)))
      await(repo.expired(later.plusSeconds(10_000))) shouldBe Nil
    }

    "remove a message on ack, dead-letter, or expiry" in {
      for ev <- List(
          SubscriptionEvent.MessageAcknowledged(ack1),
          SubscriptionEvent.MessageDeadLettered(ack1, msg(Some(later)), 5),
          SubscriptionEvent.MessageExpired(ack1)
        )
      do
        val repo = InMemoryExpiringMessageRepository()
        index(repo, SubscriptionEvent.MessageDelivered(ack1, msg(Some(later))))
        index(repo, ev)
        await(repo.expired(later)) shouldBe Nil
    }
  }
