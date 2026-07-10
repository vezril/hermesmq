package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

/** Tests the pure consumers config parser: default window, override, disable, fail-fast. */
final class ConsumersConfigSpec extends AnyFunSuite:

  private def load(overrides: String = "") =
    ConsumersConfig.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("default activity window is 60s and enabled") {
    val cfg = load().toOption.get
    assert(cfg.activityWindow == 60.seconds && cfg.enabled)
  }

  test("a positive window is honored") {
    assert(load("hermesmq.consumers.activity-window = 2m").toOption.get.activityWindow == 2.minutes)
  }

  test("a zero window disables the registry") {
    val cfg = load("hermesmq.consumers.activity-window = 0").toOption.get
    assert(cfg.activityWindow == Duration.Zero && !cfg.enabled)
  }

  test("a negative window fails fast") {
    assert(load("hermesmq.consumers.activity-window = -1s").isLeft)
  }
