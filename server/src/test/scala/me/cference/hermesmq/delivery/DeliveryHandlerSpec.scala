package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.PulledMessage
import me.cference.hermesmq.persistence.SubscriptionService
import me.cference.hermesmq.tracing.Correlation
import me.cference.hermesmq.tracing.MdcPropagatingExecutionContext
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.slf4j.MDC

import java.time.Instant
import scala.collection.mutable
import scala.concurrent.Future

/** Tests the delivery fan-out logic with a capturing stub subscription service. */
final class DeliveryHandlerSpec extends AnyFunSuite with Matchers with ScalaFutures:

  import scala.concurrent.ExecutionContext.Implicits.global

  private def tid(s: String) = TopicId.from(s).toOption.get
  private def sid(s: String) = SubscriptionId.from(s).toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  private class CapturingService extends SubscriptionService:
    val calls = mutable.ListBuffer[(SubscriptionId, SubscriptionCommand)]()
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      calls += ((id, command)); Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] = Future.successful(None)

  /** Records the correlation id visible in the MDC at the moment of fan-out —
    * the observable proof that async delivery runs under the MESSAGE's id.
    */
  private class MdcRecordingService extends SubscriptionService:
    val seen = mutable.ListBuffer[Option[String]]()
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      seen += Option(MDC.get(Correlation.MdcKey)); Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] = Future.successful(None)

  private def correlated(id: String) = Message
    .from(
      MessageId.from("m-c").toOption.get,
      "hi".getBytes,
      Map.empty,
      Instant.parse("2026-07-07T00:00:00Z"),
      correlationId = Some(id)
    )
    .toOption
    .get

  test("fan-out runs under the message's correlation id, so async delivery joins the originating trace") {
    // Production wires the propagating EC (Main) precisely so the id survives
    // the Future hop between the repo lookup and the submit — use it here too.
    given ec: scala.concurrent.ExecutionContext =
      MdcPropagatingExecutionContext(scala.concurrent.ExecutionContext.Implicits.global)
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.add(tid("orders"), sid("s2")).futureValue
    val service = MdcRecordingService()

    DeliveryHandler(repo, service).deliver(tid("orders"), correlated("corr-77")).futureValue

    val _ = service.seen.size shouldBe 2
    service.seen.toSet shouldBe Set(Some("corr-77")) // every fan-out saw the id
  }

  test("an uncorrelated message sets no correlation id on the delivery thread") {
    given ec: scala.concurrent.ExecutionContext =
      MdcPropagatingExecutionContext(scala.concurrent.ExecutionContext.Implicits.global)
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    val service = MdcRecordingService()

    DeliveryHandler(repo, service).deliver(tid("orders"), message).futureValue

    service.seen.toList shouldBe List(None)
  }

  test("delivery does not leak the correlation id onto the calling thread afterwards") {
    given ec: scala.concurrent.ExecutionContext =
      MdcPropagatingExecutionContext(scala.concurrent.ExecutionContext.Implicits.global)
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    DeliveryHandler(repo, MdcRecordingService()).deliver(tid("orders"), correlated("corr-88")).futureValue
    Option(MDC.get(Correlation.MdcKey)) shouldBe None
  }

  test("delivers a message to every subscription the read model returns (incl. other nodes')") {
    val repo = InMemoryTopicSubscriptionsRepository()
    // s1 and s2 are in the shared read model — as if created on different nodes.
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.add(tid("orders"), sid("s2")).futureValue
    val service = CapturingService()
    val handler = DeliveryHandler(repo, service)

    handler.deliver(tid("orders"), message).futureValue

    val ids = service.calls.map(_._1).toSet
    val _ = ids shouldBe Set(sid("s1"), sid("s2"))
    service.calls.foreach { case (subId, cmd) =>
      cmd shouldBe SubscriptionCommand.RecordDelivery(DeliveryHandler.ackIdFor(subId, message.id), message)
    }
  }

  test("delivers nowhere when the topic has no subscriptions") {
    val service = CapturingService()
    val handler = DeliveryHandler(InMemoryTopicSubscriptionsRepository(), service)
    handler.deliver(tid("orders"), message).futureValue
    service.calls shouldBe empty
  }

  test("uses a deterministic ackId per (subscription, message) so replays are idempotent") {
    val _ = DeliveryHandler.ackIdFor(sid("s1"), message.id) shouldBe DeliveryHandler.ackIdFor(sid("s1"), message.id)
    DeliveryHandler.ackIdFor(sid("s1"), message.id) should not be DeliveryHandler.ackIdFor(sid("s2"), message.id)
  }
