package me.cference.hermesmq.grpc

import io.grpc.Status
import me.cference.hermesmq.domain.{AckId, Rejection, ValidationError}
import org.scalatest.funsuite.AnyFunSuite

/** Tests the pure mapping of domain rejections/validation/failure to gRPC statuses. */
final class GrpcErrorsSpec extends AnyFunSuite:

  private val ackId = AckId.from("ack-1").toOption.get

  test("not-found rejections map to NOT_FOUND") {
    assert(GrpcErrors.statusOf(Rejection.TopicNotFound).getCode == Status.Code.NOT_FOUND)
    assert(GrpcErrors.statusOf(Rejection.SubscriptionNotFound).getCode == Status.Code.NOT_FOUND)
    assert(GrpcErrors.statusOf(Rejection.UnknownAckId(ackId)).getCode == Status.Code.NOT_FOUND)
  }

  test("already-exists rejections map to ALREADY_EXISTS") {
    assert(GrpcErrors.statusOf(Rejection.TopicAlreadyExists).getCode == Status.Code.ALREADY_EXISTS)
    assert(GrpcErrors.statusOf(Rejection.SubscriptionAlreadyExists).getCode == Status.Code.ALREADY_EXISTS)
  }

  test("a validation error maps to INVALID_ARGUMENT") {
    assert(GrpcErrors.invalid(ValidationError("bad")).status.getCode == Status.Code.INVALID_ARGUMENT)
  }

  test("a rejection exception carries the mapped status and a description") {
    val ex = GrpcErrors.rejected(Rejection.TopicNotFound)
    assert(ex.status.getCode == Status.Code.NOT_FOUND)
    assert(Option(ex.status.getDescription).exists(_.nonEmpty))
  }

  test("a backend failure maps to UNAVAILABLE") {
    assert(GrpcErrors.unavailable(new RuntimeException("boom")).status.getCode == Status.Code.UNAVAILABLE)
  }
