package me.cference.hermesmq.auth

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Tests tenant id namespacing: the default tenant is unqualified, named tenants
  * are prefixed, and the reserved separator is guarded.
  */
final class TenantScopeSpec extends AnyWordSpec with Matchers:

  private val default = TenantScope.DefaultTenant
  private val acme    = TenantId.from("acme").toOption.get
  private val beta    = TenantId.from("beta").toOption.get
  private val scope   = new TenantScope(default)

  "TenantScope" should {
    "leave the default tenant's ids unqualified and prefix named tenants" in {
      scope.qualify(default, "orders") shouldBe "orders"
      scope.qualify(acme, "orders") shouldBe "acme~orders"
    }

    "invert qualification with unqualify" in {
      scope.unqualify(acme, scope.qualify(acme, "orders")) shouldBe "orders"
      scope.unqualify(default, scope.qualify(default, "orders")) shouldBe "orders"
    }

    "recognise ownership only for the owning tenant" in {
      scope.belongsTo(acme, "acme~orders") shouldBe true
      scope.belongsTo(acme, "beta~orders") shouldBe false
      scope.belongsTo(beta, "acme~orders") shouldBe false
    }

    "have the default tenant own exactly the unqualified ids" in {
      scope.belongsTo(default, "orders") shouldBe true
      scope.belongsTo(default, "acme~orders") shouldBe false // excludes other tenants' prefixed ids
    }

    "reject an external id containing the reserved separator" in {
      TenantScope.validateExternalId("a~b").isLeft shouldBe true
      TenantScope.validateExternalId("orders").isRight shouldBe true
    }
  }
