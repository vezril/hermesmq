package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.{CommandReply, PulledMessage, SubscriptionService}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tests the sweeper's per-tick logic: which entities receive `ExpireAckDeadline`
  * for a given `now`, independent of any timer.
  */
final class RedeliverySweeperSpec extends AnyWordSpec with Matchers:

  private val sub  = SubscriptionId.from("sub-1").toOption.get
  private val ack1 = AckId.from("ack-1").toOption.get
  private val ack2 = AckId.from("ack-2").toOption.get
  private val t0   = Instant.parse("2026-07-07T12:00:00Z")

  private def await[A](f: => Future[A]): A = Await.result(f, 3.seconds)

  /** Captures every command submitted, so we can assert what the sweep dispatched. */
  private final class CapturingService extends SubscriptionService:
    val submitted = new ConcurrentLinkedQueue[(SubscriptionId, SubscriptionCommand)]()
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      submitted.add((id, command))
      Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] = Future.successful(Some(Nil))

  "RedeliverySweeper.sweepOnce" should {
    "issue ExpireAckDeadline for every overdue lease at now, with the max attempts" in {
      val repo    = InMemoryOutstandingLeaseRepository()
      await(repo.leased(sub, ack1, t0.minusSeconds(1)))
      await(repo.leased(sub, ack2, t0.minusSeconds(1)))
      val service = CapturingService()

      await(RedeliverySweeper.sweepOnce(repo, service, t0, maxAttempts = 5))

      service.submitted.asScala.toSet shouldBe Set(
        sub -> SubscriptionCommand.ExpireAckDeadline(ack1, t0, 5),
        sub -> SubscriptionCommand.ExpireAckDeadline(ack2, t0, 5)
      )
    }

    "leave within-deadline leases untouched" in {
      val repo    = InMemoryOutstandingLeaseRepository()
      await(repo.leased(sub, ack1, t0.plusSeconds(60)))
      val service = CapturingService()

      await(RedeliverySweeper.sweepOnce(repo, service, t0, maxAttempts = 5))

      service.submitted.asScala shouldBe empty
    }

    "be a no-op when there are no overdue leases" in {
      val repo    = InMemoryOutstandingLeaseRepository()
      val service = CapturingService()
      await(RedeliverySweeper.sweepOnce(repo, service, t0, maxAttempts = 5))
      service.submitted.asScala shouldBe empty
    }
  }
