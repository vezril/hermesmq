package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.{AckId, SubscriptionId}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Tests the in-memory outstanding-lease read model used for overdue discovery. */
final class OutstandingLeaseRepositorySpec extends AnyWordSpec with Matchers:

  private val sub   = SubscriptionId.from("sub-1").toOption.get
  private val ack1  = AckId.from("ack-1").toOption.get
  private val ack2  = AckId.from("ack-2").toOption.get
  private val t0    = Instant.parse("2026-07-07T12:00:00Z")
  private val later = t0.plusSeconds(60)

  private def await[A](f: => scala.concurrent.Future[A]): A = Await.result(f, 3.seconds)

  "OutstandingLeaseRepository (in-memory)" should {
    "list a lease whose deadline is at or before now as overdue" in {
      val repo = InMemoryOutstandingLeaseRepository()
      await(repo.leased(sub, ack1, t0))
      await(repo.overdue(t0)) shouldBe List(OutstandingLease(sub, ack1, t0))
      await(repo.overdue(later)) shouldBe List(OutstandingLease(sub, ack1, t0))
    }

    "not list a lease whose deadline is still in the future" in {
      val repo = InMemoryOutstandingLeaseRepository()
      await(repo.leased(sub, ack1, later))
      await(repo.overdue(t0)) shouldBe Nil
    }

    "clear a lease so it is no longer discoverable" in {
      val repo = InMemoryOutstandingLeaseRepository()
      await(repo.leased(sub, ack1, t0))
      await(repo.cleared(sub, ack1))
      await(repo.overdue(later)) shouldBe Nil
    }

    "update the deadline when the same lease is recorded again" in {
      val repo = InMemoryOutstandingLeaseRepository()
      await(repo.leased(sub, ack1, t0))
      await(repo.leased(sub, ack1, later))
      await(repo.overdue(t0)) shouldBe Nil
      await(repo.overdue(later)) shouldBe List(OutstandingLease(sub, ack1, later))
    }

    "list every overdue lease across ackIds" in {
      val repo = InMemoryOutstandingLeaseRepository()
      await(repo.leased(sub, ack1, t0))
      await(repo.leased(sub, ack2, t0))
      await(repo.overdue(t0)).map(_.ackId).toSet shouldBe Set(ack1, ack2)
    }
  }
