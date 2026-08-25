package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Tests the pure event→read-model effect that keeps the outstanding-lease view
  * in sync, independent of the JDBC projection plumbing.
  */
final class LeaseProjectionSpec extends AnyWordSpec with Matchers:

  private val sub     = SubscriptionId.from("sub-1").toOption.get
  private val ack1    = AckId.from("ack-1").toOption.get
  private val ack2    = AckId.from("ack-2").toOption.get
  private val t0      = Instant.parse("2026-07-07T12:00:00Z")
  private val later   = t0.plusSeconds(60)

  private def await[A](f: => scala.concurrent.Future[A]): A = Await.result(f, 3.seconds)

  private def index(repo: OutstandingLeaseRepository, event: SubscriptionEvent): Unit =
    await(LeaseProjection.indexEvent(repo, sub, event))

  "LeaseProjection.indexEvent" should {
    "record a lease for every leased ackId with the shared deadline" in {
      val repo = InMemoryOutstandingLeaseRepository()
      index(repo, SubscriptionEvent.MessageLeased(List(ack1, ack2), t0))
      await(repo.overdue(t0)).map(_.ackId).toSet shouldBe Set(ack1, ack2)
    }

    "clear a lease on AckDeadlineExpired (message returned to AVAILABLE)" in {
      val repo = InMemoryOutstandingLeaseRepository()
      index(repo, SubscriptionEvent.MessageLeased(List(ack1), t0))
      index(repo, SubscriptionEvent.AckDeadlineExpired(ack1, 1))
      await(repo.overdue(later)) shouldBe Nil
    }

    "clear a lease on MessageAcknowledged" in {
      val repo = InMemoryOutstandingLeaseRepository()
      index(repo, SubscriptionEvent.MessageLeased(List(ack1), t0))
      index(repo, SubscriptionEvent.MessageAcknowledged(ack1))
      await(repo.overdue(later)) shouldBe Nil
    }

    "clear a lease on MessageDeadLettered" in {
      val repo    = InMemoryOutstandingLeaseRepository()
      val message = Message.from(MessageId.from("m-1").toOption.get, "x".getBytes, Map.empty, t0).toOption.get
      index(repo, SubscriptionEvent.MessageLeased(List(ack1), t0))
      index(repo, SubscriptionEvent.MessageDeadLettered(ack1, message, 5))
      await(repo.overdue(later)) shouldBe Nil
    }

    "update the deadline on AckDeadlineModified" in {
      val repo = InMemoryOutstandingLeaseRepository()
      index(repo, SubscriptionEvent.MessageLeased(List(ack1), t0))
      index(repo, SubscriptionEvent.AckDeadlineModified(ack1, later))
      val _ = await(repo.overdue(t0)) shouldBe Nil
      await(repo.overdue(later)).map(_.deadline) shouldBe List(later)
    }

    "ignore MessageDelivered (no lease yet)" in {
      val repo    = InMemoryOutstandingLeaseRepository()
      val message = Message.from(MessageId.from("m-1").toOption.get, "x".getBytes, Map.empty, t0).toOption.get
      index(repo, SubscriptionEvent.MessageDelivered(ack1, message))
      await(repo.overdue(later)) shouldBe Nil
    }
  }
