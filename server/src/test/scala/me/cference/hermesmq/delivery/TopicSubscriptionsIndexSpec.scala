package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.{SubscriptionId, TopicId}
import org.scalatest.funsuite.AnyFunSuite

/** Tests the in-memory topic→subscriptions index used by delivery fan-out. */
final class TopicSubscriptionsIndexSpec extends AnyFunSuite:

  private def tid(s: String) = TopicId.from(s).toOption.get
  private def sid(s: String) = SubscriptionId.from(s).toOption.get

  test("a created subscription is indexed under its topic") {
    val index = TopicSubscriptionsIndex()
    index.add(tid("orders"), sid("s1"))
    assert(index.subscriptionsFor(tid("orders")) == Set(sid("s1")))
  }

  test("subscriptions on different topics are isolated") {
    val index = TopicSubscriptionsIndex()
    index.add(tid("orders"), sid("s1"))
    index.add(tid("billing"), sid("s2"))
    assert(index.subscriptionsFor(tid("orders")) == Set(sid("s1")))
    assert(index.subscriptionsFor(tid("billing")) == Set(sid("s2")))
  }

  test("multiple subscriptions accumulate under one topic") {
    val index = TopicSubscriptionsIndex()
    index.add(tid("orders"), sid("s1"))
    index.add(tid("orders"), sid("s2"))
    assert(index.subscriptionsFor(tid("orders")) == Set(sid("s1"), sid("s2")))
  }

  test("an unknown topic has no subscriptions") {
    assert(TopicSubscriptionsIndex().subscriptionsFor(tid("ghost")).isEmpty)
  }
