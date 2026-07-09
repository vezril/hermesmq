package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

/** Tests the pure TTL config parser: default off, overrides, fail-fast validation. */
final class TtlConfigSpec extends AnyFunSuite:

  private def load(overrides: String = "") =
    TtlConfig.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("default is off (0)") {
    assert(load() == Right(TtlConfig(Duration.Zero)))
    assert(load().toOption.get.enabled == false)
  }

  test("a positive default is honored and enabled") {
    val cfg = load("hermesmq.ttl.default = 30s").toOption.get
    assert(cfg.defaultTtl == 30.seconds && cfg.enabled)
  }

  test("a negative default fails fast") {
    assert(load("hermesmq.ttl.default = -1s").isLeft)
  }
