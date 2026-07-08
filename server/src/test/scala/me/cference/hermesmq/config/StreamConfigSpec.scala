package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

/** Tests the pure stream config parser: defaults, overrides, fail-fast validation. */
final class StreamConfigSpec extends AnyFunSuite:

  private def load(overrides: String = "") =
    StreamConfig.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("defaults: 1s poll interval, batch size 100") {
    assert(load() == Right(StreamConfig(1.second, 100)))
  }

  test("overrides are honored") {
    assert(load("""hermesmq.grpc.stream { poll-interval = 250ms, batch-size = 10 }""") == Right(StreamConfig(250.millis, 10)))
  }

  test("a non-positive poll interval fails fast") {
    assert(load("hermesmq.grpc.stream.poll-interval = 0s").isLeft)
  }

  test("a non-positive batch size fails fast") {
    assert(load("hermesmq.grpc.stream.batch-size = 0").isLeft)
  }
