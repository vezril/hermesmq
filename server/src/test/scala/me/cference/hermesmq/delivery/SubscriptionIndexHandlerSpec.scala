package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Tests the per-event logic of the subscription-index projection. */
final class SubscriptionIndexHandlerSpec extends AnyFunSuite with Matchers with ScalaFutures:

  import scala.concurrent.ExecutionContext.Implicits.global

  private def tid(s: String) = TopicId.from(s).toOption.get
  private def sid(s: String) = SubscriptionId.from(s).toOption.get

  test("a SubscriptionCreated event is indexed under its topic") {
    val repo = InMemoryTopicSubscriptionsRepository()
    SubscriptionIndexProjection.indexEvent(repo, SubscriptionEvent.SubscriptionCreated(sid("s1"), tid("orders"))).futureValue
    repo.subscriptionsFor(tid("orders")).futureValue shouldBe Set(sid("s1"))
  }

  test("non-created subscription events are ignored") {
    val repo = InMemoryTopicSubscriptionsRepository()
    val ackId = AckId.from("a1").toOption.get
    SubscriptionIndexProjection.indexEvent(repo, SubscriptionEvent.MessageAcknowledged(ackId)).futureValue
    repo.subscriptionsFor(tid("orders")).futureValue shouldBe empty
  }
