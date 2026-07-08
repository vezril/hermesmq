package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

/** Tests the pure gRPC config parser: defaults, overrides, and fail-fast validation. */
final class GrpcConfigSpec extends AnyFunSuite:

  test("defaults load 0.0.0.0:8081 from application.conf") {
    assert(GrpcConfig.from(ConfigFactory.load()) == Right(GrpcConfig("0.0.0.0", 8081)))
  }

  test("host and port overrides are honored") {
    val cfg = ConfigFactory
      .parseString("""hermesmq.grpc { host = "127.0.0.1", port = 9091 }""")
      .withFallback(ConfigFactory.load())
    assert(GrpcConfig.from(cfg) == Right(GrpcConfig("127.0.0.1", 9091)))
  }

  test("out-of-range port yields a config error") {
    val cfg = ConfigFactory.parseString("hermesmq.grpc.port = 70000").withFallback(ConfigFactory.load())
    assert(GrpcConfig.from(cfg).isLeft)
  }

  test("non-numeric port yields a config error, not an exception") {
    val cfg = ConfigFactory.parseString("""hermesmq.grpc.port = "nope"""").withFallback(ConfigFactory.load())
    assert(GrpcConfig.from(cfg).isLeft)
  }
