package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Tests the topic→subscriptions read model via the in-memory implementation. */
final class TopicSubscriptionsRepositorySpec extends AnyFunSuite with Matchers with ScalaFutures:

  import scala.concurrent.ExecutionContext.Implicits.global

  private def tid(s: String) = TopicId.from(s).toOption.get
  private def sid(s: String) = SubscriptionId.from(s).toOption.get

  test("add then subscriptionsFor returns the subscription") {
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.subscriptionsFor(tid("orders")).futureValue shouldBe Set(sid("s1"))
  }

  test("subscriptions on different topics are isolated") {
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.add(tid("billing"), sid("s2")).futureValue
    val _ = repo.subscriptionsFor(tid("orders")).futureValue shouldBe Set(sid("s1"))
    repo.subscriptionsFor(tid("billing")).futureValue shouldBe Set(sid("s2"))
  }

  test("add is idempotent (no duplicate)") {
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.subscriptionsFor(tid("orders")).futureValue shouldBe Set(sid("s1"))
  }

  test("an unknown topic has no subscriptions") {
    InMemoryTopicSubscriptionsRepository().subscriptionsFor(tid("ghost")).futureValue shouldBe empty
  }

  // The reverse lookup: dead-letter routing and redrive need a subscription's
  // origin topic, and this read model is the only place that mapping is durable.

  test("topicFor returns the topic a subscription belongs to") {
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.topicFor(sid("s1")).futureValue shouldBe Some(tid("orders"))
  }

  test("topicFor distinguishes subscriptions across topics") {
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.add(tid("billing"), sid("s2")).futureValue
    val _ = repo.topicFor(sid("s1")).futureValue shouldBe Some(tid("orders"))
    repo.topicFor(sid("s2")).futureValue shouldBe Some(tid("billing"))
  }

  test("topicFor on an unknown subscription is None, not an error") {
    InMemoryTopicSubscriptionsRepository().topicFor(sid("ghost")).futureValue shouldBe None
  }

  test("topicFor stops resolving a subscription once it is removed") {
    val repo = InMemoryTopicSubscriptionsRepository()
    repo.add(tid("orders"), sid("s1")).futureValue
    repo.remove(tid("orders"), sid("s1")).futureValue
    repo.topicFor(sid("s1")).futureValue shouldBe None
  }
