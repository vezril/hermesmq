package me.cference.hermesmq.domain

import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

/** Tests the `AckDeadline` value type. */
final class AckDeadlineSpec extends AnyFunSuite:

  test("a non-negative deadline is accepted") {
    assert(AckDeadline.from(30.seconds).map(_.duration) == Right(30.seconds))
  }

  test("a zero deadline is accepted") {
    assert(AckDeadline.from(0.seconds).isRight)
  }

  test("a negative deadline is rejected") {
    assert(AckDeadline.from(-1.second).isLeft)
  }
