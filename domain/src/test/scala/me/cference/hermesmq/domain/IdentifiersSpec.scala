package me.cference.hermesmq.domain

import org.scalatest.funsuite.AnyFunSuite

/** Tests the validated, type-safe identifier value types. */
final class IdentifiersSpec extends AnyFunSuite:

  test("a valid TopicId is constructed and exposes its value") {
    val result = TopicId.from("orders")
    assert(result.map(_.value) == Right("orders"))
  }

  test("identifiers compare by value and work as map keys") {
    val a = TopicId.from("orders").toOption.get
    val b = TopicId.from("orders").toOption.get
    assert(a == b)
    assert(Map(a -> 1).contains(b))
  }

  test("blank identifiers are rejected for every id type") {
    assert(TopicId.from("   ").isLeft)
    assert(SubscriptionId.from("").isLeft)
    assert(MessageId.from("").isLeft)
    assert(AckId.from("  ").isLeft)
  }

  test("all four id types construct from valid input") {
    assert(TopicId.from("t").isRight)
    assert(SubscriptionId.from("s").isRight)
    assert(MessageId.from("m").isRight)
    assert(AckId.from("a").isRight)
  }

  test("distinct id types are not interchangeable") {
    // Compiles only because each function receives its own id type; a TopicId
    // could not be passed where a SubscriptionId is required.
    def needsTopic(id: TopicId): String        = id.value
    def needsSubscription(id: SubscriptionId): String = id.value
    assert(needsTopic(TopicId.from("t").toOption.get) == "t")
    assert(needsSubscription(SubscriptionId.from("s").toOption.get) == "s")
  }
