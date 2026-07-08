package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

/** Tests the pure config parser: defaults, overrides, and fail-fast validation.
  * No side effects and no server — just `Config` in, `Either` out.
  */
final class ServiceConfigSpec extends AnyFunSuite:

  test("defaults load 0.0.0.0:8080 from application.conf") {
    val result = ServiceConfig.from(ConfigFactory.load())
    assert(result == Right(ServiceConfig("0.0.0.0", 8080)))
  }

  test("port override is honored") {
    val cfg = ConfigFactory
      .parseString("""hermesmq.http { host = "127.0.0.1", port = 9090 }""")
      .withFallback(ConfigFactory.load())
    assert(ServiceConfig.from(cfg) == Right(ServiceConfig("127.0.0.1", 9090)))
  }

  test("out-of-range port yields a config error") {
    val cfg = ConfigFactory
      .parseString("hermesmq.http.port = 70000")
      .withFallback(ConfigFactory.load())
    assert(ServiceConfig.from(cfg).isLeft)
  }

  test("non-numeric port yields a config error, not an exception") {
    val cfg = ConfigFactory
      .parseString("""hermesmq.http.port = "not-a-number"""")
      .withFallback(ConfigFactory.load())
    assert(ServiceConfig.from(cfg).isLeft)
  }
