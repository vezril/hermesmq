package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.domain.TopicId
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

/** Tests the pure redelivery/ack-deadline config parser: defaults, overrides,
  * and fail-fast validation.
  */
final class RedeliveryConfigSpec extends AnyFunSuite:

  private def load(overrides: String = "") =
    RedeliveryConfig.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("defaults: 30s ack deadline, 5 max attempts, 5s sweep interval, no dead-letter topic") {
    assert(load() == Right(RedeliveryConfig(30.seconds, 5, 5.seconds, None)))
  }

  test("overrides are honored") {
    val cfg = load("""hermesmq.redelivery {
                     |  ack-deadline = 10s
                     |  max-delivery-attempts = 3
                     |  sweep-interval = 1s
                     |  dead-letter-topic = "dead-letters"
                     |}""".stripMargin)
    assert(cfg == Right(RedeliveryConfig(10.seconds, 3, 1.seconds, Some(TopicId.from("dead-letters").toOption.get))))
  }

  test("zero max-delivery-attempts is allowed (unlimited)") {
    assert(load("hermesmq.redelivery.max-delivery-attempts = 0").map(_.maxDeliveryAttempts) == Right(0))
  }

  test("non-positive ack deadline fails fast") {
    assert(load("hermesmq.redelivery.ack-deadline = 0s").isLeft)
  }

  test("non-positive sweep interval fails fast") {
    assert(load("hermesmq.redelivery.sweep-interval = 0s").isLeft)
  }

  test("negative max-delivery-attempts fails fast") {
    assert(load("hermesmq.redelivery.max-delivery-attempts = -1").isLeft)
  }

  test("a blank dead-letter topic means none (drop exhausted messages)") {
    assert(load("""hermesmq.redelivery.dead-letter-topic = "   """").map(_.deadLetterTopic) == Right(None))
  }
