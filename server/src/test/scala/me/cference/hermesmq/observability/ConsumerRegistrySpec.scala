package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.SubscriptionId
import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.concurrent.duration.*

/** Tests the in-memory active-consumer registry: activity-window counting,
  * expiry, and the disabled (window = 0) case.
  */
final class ConsumerRegistrySpec extends AnyFunSuite:

  private val sub = SubscriptionId.from("orders").toOption.get
  private val t0  = Instant.parse("2026-07-07T00:00:00Z")

  test("a touched consumer is counted active within the window") {
    val reg = ConsumerRegistry(1.minute)
    reg.touch(sub, "worker-1", t0)
    assert(reg.activeCount(sub, t0.plusSeconds(30)) == 1)
  }

  test("distinct consumers on one subscription are counted") {
    val reg = ConsumerRegistry(1.minute)
    reg.touch(sub, "worker-1", t0)
    reg.touch(sub, "worker-2", t0.plusSeconds(10))
    assert(reg.activeCount(sub, t0.plusSeconds(20)) == 2)
  }

  test("a consumer silent beyond the window is not counted") {
    val reg = ConsumerRegistry(1.minute)
    reg.touch(sub, "worker-1", t0)
    assert(reg.activeCount(sub, t0.plusSeconds(61)) == 0)
  }

  test("re-touching refreshes a consumer's activity") {
    val reg = ConsumerRegistry(1.minute)
    reg.touch(sub, "worker-1", t0)
    reg.touch(sub, "worker-1", t0.plusSeconds(50))
    assert(reg.activeCount(sub, t0.plusSeconds(90)) == 1) // still within 60s of the refresh
  }

  test("an empty consumer id is ignored") {
    val reg = ConsumerRegistry(1.minute)
    reg.touch(sub, "", t0)
    assert(reg.activeCount(sub, t0) == 0)
  }

  test("a zero window disables tracking") {
    val reg = ConsumerRegistry(Duration.Zero)
    reg.touch(sub, "worker-1", t0)
    assert(!reg.enabled)
    assert(reg.activeCount(sub, t0) == 0)
  }

  test("activeCountsBySubscription lists only subscriptions with active consumers") {
    val reg  = ConsumerRegistry(1.minute)
    val sub2 = SubscriptionId.from("events").toOption.get
    reg.touch(sub, "worker-1", t0.plusSeconds(30)) // active at the query time
    reg.touch(sub2, "worker-2", t0)                // 70s old at the query time → expired
    val counts = reg.activeCountsBySubscription(t0.plusSeconds(70))
    assert(counts == Map(sub -> 1))
  }
