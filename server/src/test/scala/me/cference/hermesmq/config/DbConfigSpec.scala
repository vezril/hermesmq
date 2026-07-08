package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

/** Tests the typed PostgreSQL connection config parser. */
final class DbConfigSpec extends AnyFunSuite:

  test("defaults load from application.conf") {
    val result = DbConfig.from(ConfigFactory.load())
    assert(result.map(_.database) == Right("hermesmq"))
    assert(result.map(_.port) == Right(5432))
  }

  test("connection settings are overridable") {
    val cfg = ConfigFactory
      .parseString("""hermesmq.db { host = "db.internal", port = 6000, database = "prod" }""")
      .withFallback(ConfigFactory.load())
    val result = DbConfig.from(cfg)
    assert(result.map(_.host) == Right("db.internal"))
    assert(result.map(_.port) == Right(6000))
    assert(result.map(_.database) == Right("prod"))
  }

  test("a blank database name fails fast") {
    val cfg = ConfigFactory
      .parseString("""hermesmq.db.database = ""  """)
      .withFallback(ConfigFactory.load())
    assert(DbConfig.from(cfg).isLeft)
  }

  test("an out-of-range port fails fast") {
    val cfg = ConfigFactory
      .parseString("hermesmq.db.port = 70000")
      .withFallback(ConfigFactory.load())
    assert(DbConfig.from(cfg).isLeft)
  }
