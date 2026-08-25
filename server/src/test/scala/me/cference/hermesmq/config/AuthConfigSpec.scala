package me.cference.hermesmq.config

import com.typesafe.config.ConfigFactory
import org.scalatest.funsuite.AnyFunSuite

/** Tests the pure auth config parser: defaults, key parsing, and fail-fast validation. */
final class AuthConfigSpec extends AnyFunSuite:

  private def load(overrides: String = "") =
    AuthConfig.from(ConfigFactory.parseString(overrides).withFallback(ConfigFactory.load()))

  test("defaults: auth disabled, default tenant present, no keys") {
    val cfg = load().toOption.get
    val _ = assert(!cfg.enabled)
    val _ = assert(cfg.defaultTenant.value == "default")
    assert(cfg.keys.isEmpty)
  }

  test("keys parse into AuthKeys with tenant, salt, hash, and scopes") {
    val cfg = load("""hermesmq.auth {
                     |  enabled = true
                     |  keys = [ { tenant = "acme", salt = "c2FsdA==", hash = "aGFzaA==", scopes = ["admin"] } ]
                     |}""".stripMargin).toOption.get
    val _ = assert(cfg.enabled)
    val _ = assert(cfg.keys.size == 1)
    val k = cfg.keys.head
    val _ = assert(k.tenant.value == "acme")
    val _ = assert(k.salt == "c2FsdA==" && k.hash == "aGFzaA==")
    assert(k.scopes == Set("admin"))
  }

  test("scopes default to empty when omitted") {
    val cfg = load("""hermesmq.auth { enabled = true, keys = [ { tenant = "acme", salt = "c2FsdA==", hash = "aGFzaA==" } ] }""").toOption.get
    assert(cfg.keys.head.scopes.isEmpty)
  }

  test("enabling auth with no keys fails fast") {
    assert(load("hermesmq.auth.enabled = true").isLeft)
  }

  test("a key missing its hash yields a config error") {
    assert(load("""hermesmq.auth { enabled = true, keys = [ { tenant = "acme", salt = "c2FsdA==" } ] }""").isLeft)
  }

  test("a key with a blank tenant yields a config error") {
    assert(load("""hermesmq.auth { enabled = true, keys = [ { tenant = "", salt = "c2FsdA==", hash = "aGFzaA==" } ] }""").isLeft)
  }
