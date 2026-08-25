package me.cference.hermesmq.auth

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.Base64

/** Tests the salted-hash authenticator: valid tokens resolve a principal, others
  * resolve to `None`, and comparison is constant-time.
  */
final class AuthenticatorSpec extends AnyWordSpec with Matchers:

  private val tenant = TenantId.from("acme").toOption.get
  private val salt   = Base64.getEncoder.encodeToString("some-random-salt".getBytes)
  private val token  = "s3cret-token"
  private val acmeKey = AuthKey(tenant, salt, Authenticator.computeHash(salt, token), Set("admin"))
  private val auth   = Authenticator(List(acmeKey))

  "Authenticator" should {
    "resolve the principal for a token whose salted hash matches a key" in {
      auth.authenticate(token) shouldBe Some(Principal(tenant, Set("admin")))
    }

    "resolve None for a token matching no key" in {
      auth.authenticate("wrong-token") shouldBe None
    }

    "resolve None for an empty or blank token" in {
      val _ = auth.authenticate("") shouldBe None
      auth.authenticate("   ") shouldBe None
    }

    "reject wrong tokens that differ early vs late in their bytes (constant-time compare)" in {
      // Behavioural proxy for timing-safety: both are rejected regardless of where they diverge.
      val _ = auth.authenticate("X3cret-token") shouldBe None // differs at the first byte
      auth.authenticate("s3cret-tokeX") shouldBe None // differs at the last byte
    }

    "pick the matching key among several" in {
      val other = AuthKey(TenantId.from("beta").toOption.get, salt, Authenticator.computeHash(salt, "beta-token"), Set.empty)
      val multi = Authenticator(List(other, acmeKey))
      val _ = multi.authenticate("beta-token").map(_.tenant.value) shouldBe Some("beta")
      multi.authenticate(token).map(_.tenant.value) shouldBe Some("acme")
    }
  }

  "TenantId" should {
    "reject a blank tenant or one containing the reserved separator" in {
      val _ = TenantId.from("").isLeft shouldBe true
      val _ = TenantId.from(s"a${TenantScope.Separator}b").isLeft shouldBe true
      TenantId.from("acme").isRight shouldBe true
    }
  }
