package me.cference.hermesmq
import org.scalatest.funsuite.AnyFunSuite
final class RedProbeSpec extends AnyFunSuite:
  test("intentionally failing test to prove CI blocks the PR") { assert(1 == 2) }
