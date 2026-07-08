package me.cference.hermesmq

import org.scalatest.funsuite.AnyFunSuite

/** Trivial pure test with no Pekko involvement — its presence proves that a
  * source under `src/main/scala` and a test under `src/test/scala` are picked
  * up by sbt's conventional layout with no extra path configuration.
  */
final class AppInfoSpec extends AnyFunSuite:
  test("service name is hermesmq") {
    assert(AppInfo.Name == "hermesmq")
  }
