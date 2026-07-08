package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

/** Tests the pure retention config parser: defaults, overrides, and fail-fast validation. */
final class RetentionConfigSpec extends AnyFunSuite:

  private def load(overrides: String = "") =
    RetentionConfig.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("defaults: snapshot every 100 events, keep 2 snapshots") {
    assert(load() == Right(RetentionConfig(100, 2)))
  }

  test("overrides are honored") {
    val cfg = load("""hermesmq.retention { snapshot-every-events = 500, keep-n-snapshots = 5 }""")
    assert(cfg == Right(RetentionConfig(500, 5)))
  }

  test("a non-positive snapshot interval fails fast") {
    assert(load("hermesmq.retention.snapshot-every-events = 0").isLeft)
    assert(load("hermesmq.retention.snapshot-every-events = -1").isLeft)
  }

  test("a non-positive keep-count fails fast") {
    assert(load("hermesmq.retention.keep-n-snapshots = 0").isLeft)
  }

  test("a non-numeric value yields a config error, not an exception") {
    assert(load("""hermesmq.retention.snapshot-every-events = "lots"""").isLeft)
  }
