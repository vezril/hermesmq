package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.{CommandReply, PulledMessage, SubscriptionService}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

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

  test("delivers a message to every subscription the read model returns (incl. other nodes')") {
    val repo = InMemoryTopicSubscriptionsRepository()
    // s1 and s2 are in the shared read model — as if created on different nodes.
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.add(tid("orders"), sid("s2")).futureValue
    val service = CapturingService()
    val handler = DeliveryHandler(repo, service)

    handler.deliver(tid("orders"), message).futureValue

    val ids = service.calls.map(_._1).toSet
    ids shouldBe Set(sid("s1"), sid("s2"))
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
    DeliveryHandler.ackIdFor(sid("s1"), message.id) shouldBe DeliveryHandler.ackIdFor(sid("s1"), message.id)
    DeliveryHandler.ackIdFor(sid("s1"), message.id) should not be DeliveryHandler.ackIdFor(sid("s2"), message.id)
  }
