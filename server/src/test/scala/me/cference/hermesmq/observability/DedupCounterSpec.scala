package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.TopicId
import org.scalatest.funsuite.AnyFunSuite

/** Tests the in-memory per-topic dedup counter. */
final class DedupCounterSpec extends AnyFunSuite:

  private val orders = TopicId.from("orders").toOption.get
  private val events = TopicId.from("events").toOption.get

  test("an untouched counter is empty") {
    assert(DedupCounter().counts.isEmpty)
  }

  test("increment accumulates per topic and counts topics independently") {
    val c = DedupCounter()
    c.increment(orders)
    c.increment(orders)
    c.increment(events)
    assert(c.counts == Map(orders -> 2L, events -> 1L))
  }
