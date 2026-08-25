package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.TopicId
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.concurrent.duration.*

/** Tests the in-memory active-producer registry: activity-window counting,
  * expiry, and the disabled (window = 0) case.
  */
final class ProducerRegistrySpec extends AnyFunSuite:

  private val orders = TopicId.from("orders").toOption.get
  private val t0     = Instant.parse("2026-07-07T00:00:00Z")

  test("a touched producer is counted active within the window") {
    val reg = ProducerRegistry(1.minute)
    reg.touch(orders, "ingest-1", t0)
    assert(reg.activeCount(orders, t0.plusSeconds(30)) == 1)
  }

  test("distinct producers on one topic are counted") {
    val reg = ProducerRegistry(1.minute)
    reg.touch(orders, "ingest-1", t0)
    reg.touch(orders, "ingest-2", t0.plusSeconds(10))
    assert(reg.activeCount(orders, t0.plusSeconds(20)) == 2)
  }

  test("a producer silent beyond the window is not counted") {
    val reg = ProducerRegistry(1.minute)
    reg.touch(orders, "ingest-1", t0)
    assert(reg.activeCount(orders, t0.plusSeconds(61)) == 0)
  }

  test("an empty producer id is ignored") {
    val reg = ProducerRegistry(1.minute)
    reg.touch(orders, "", t0)
    assert(reg.activeCount(orders, t0) == 0)
  }

  test("a zero window disables tracking") {
    val reg = ProducerRegistry(Duration.Zero)
    reg.touch(orders, "ingest-1", t0)
    val _ = assert(!reg.enabled)
    assert(reg.activeCount(orders, t0) == 0)
  }

  test("activeCountsByTopic lists only topics with active producers") {
    val reg    = ProducerRegistry(1.minute)
    val events = TopicId.from("events").toOption.get
    reg.touch(orders, "ingest-1", t0.plusSeconds(30)) // active at the query time
    reg.touch(events, "ingest-2", t0)                 // 70s old at the query time → expired
    val counts = reg.activeCountsByTopic(t0.plusSeconds(70))
    assert(counts == Map(orders -> 1))
  }
