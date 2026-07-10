package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.PulledMessage
import me.cference.hermesmq.persistence.SubscriptionService
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tests the TTL sweeper's per-tick logic. */
final class TtlSweeperSpec extends AnyWordSpec with Matchers:

  private val sub  = SubscriptionId.from("s1").toOption.get
  private val ack1 = AckId.from("ack-1").toOption.get
  private val ack2 = AckId.from("ack-2").toOption.get
  private val t0   = Instant.parse("2026-07-07T12:00:00Z")

  private def await[A](f: => Future[A]): A = Await.result(f, 3.seconds)

  private final class CapturingService extends SubscriptionService:
    val submitted = new ConcurrentLinkedQueue[(SubscriptionId, SubscriptionCommand)]()
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      submitted.add((id, command)); Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] = Future.successful(Some(Nil))

  "TtlSweeper.sweepOnce" should {
    "issue ExpireMessage for every message past its TTL at now" in {
      val repo = InMemoryExpiringMessageRepository()
      await(repo.add(sub, ack1, t0.minusSeconds(1)))
      await(repo.add(sub, ack2, t0.minusSeconds(1)))
      val service = CapturingService()

      await(TtlSweeper.sweepOnce(repo, service, t0))

      service.submitted.asScala.toSet shouldBe Set(
        sub -> SubscriptionCommand.ExpireMessage(ack1, t0),
        sub -> SubscriptionCommand.ExpireMessage(ack2, t0)
      )
    }

    "leave not-yet-expired messages untouched" in {
      val repo = InMemoryExpiringMessageRepository()
      await(repo.add(sub, ack1, t0.plusSeconds(60)))
      val service = CapturingService()
      await(TtlSweeper.sweepOnce(repo, service, t0))
      service.submitted.asScala shouldBe empty
    }

    "be a no-op when nothing is expired" in {
      val service = CapturingService()
      await(TtlSweeper.sweepOnce(InMemoryExpiringMessageRepository(), service, t0))
      service.submitted.asScala shouldBe empty
    }
  }
