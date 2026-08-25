package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

/** Tests the pure dedup config parser: default off, positive window, fail-fast validation. */
final class DedupConfigSpec extends AnyFunSuite:

  private def load(overrides: String = "") =
    DedupConfig.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("default is off (0)") {
    val _ = assert(load() == Right(DedupConfig(Duration.Zero)))
    assert(load().toOption.get.enabled == false)
  }

  test("a positive window is honored and enabled") {
    val cfg = load("hermesmq.dedup.window = 10m").toOption.get
    assert(cfg.window == 10.minutes && cfg.enabled)
  }

  test("a negative window fails fast") {
    assert(load("hermesmq.dedup.window = -1s").isLeft)
  }
