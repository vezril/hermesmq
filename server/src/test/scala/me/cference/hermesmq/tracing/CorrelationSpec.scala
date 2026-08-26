package me.cference.hermesmq.tracing

import io.codex.lexicon.CorrelationNames
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Minting + adopt-or-mint policy, and the names pinned to the shared Lexicon
  * constants so a rename there can't silently diverge here.
  */
final class CorrelationSpec extends AnyWordSpec with Matchers:

  "Correlation.mint" should {
    "produce a non-empty, log-friendly token" in {
      val id = Correlation.mint()
      val _  = id should not be empty
      id should fullyMatch regex "[0-9a-z]+"
    }
    "produce a distinct value each call" in {
      val ids = Vector.fill(1000)(Correlation.mint())
      ids.distinct.size shouldBe ids.size
    }
  }

  "Correlation.adoptOrMint" should {
    "adopt a non-empty inbound id" in {
      Correlation.adoptOrMint(Some("upstream-1")) shouldBe "upstream-1"
    }
    "mint when absent or empty" in {
      val _ = Correlation.adoptOrMint(None) should fullyMatch regex "[0-9a-z]{12}"
      Correlation.adoptOrMint(Some("")) should fullyMatch regex "[0-9a-z]{12}"
    }
  }

  "Correlation names" should {
    "be the shared Lexicon constants" in {
      val _ = Correlation.MdcKey shouldBe CorrelationNames.LogField
      val _ = Correlation.HttpHeader shouldBe CorrelationNames.HttpHeader
      Correlation.MetadataKey shouldBe CorrelationNames.GrpcMeta
    }
  }
