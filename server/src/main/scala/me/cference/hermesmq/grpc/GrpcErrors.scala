package me.cference.hermesmq.grpc

import io.grpc.Status
import me.cference.hermesmq.domain.Rejection
import me.cference.hermesmq.domain.ValidationError
import org.apache.pekko.grpc.GrpcServiceException

/** Maps domain outcomes to gRPC statuses so the two API surfaces report failures
  * consistently: a REST `404`/`409`/`400` and a gRPC `NOT_FOUND`/`ALREADY_EXISTS`/
  * `INVALID_ARGUMENT` mean the same thing.
  */
object GrpcErrors:

  /** The gRPC status for a domain rejection. */
  def statusOf(rejection: Rejection): Status =
    rejection match
      case Rejection.TopicNotFound            => Status.NOT_FOUND.withDescription("topic not found")
      case Rejection.SubscriptionNotFound     => Status.NOT_FOUND.withDescription("subscription not found")
      case Rejection.UnknownAckId(ackId)      => Status.NOT_FOUND.withDescription(s"unknown ackId: ${ackId.value}")
      case Rejection.TopicAlreadyExists       => Status.ALREADY_EXISTS.withDescription("topic already exists")
      case Rejection.SubscriptionAlreadyExists => Status.ALREADY_EXISTS.withDescription("subscription already exists")

  /** Exception carrying the mapped status for a rejection. */
  def rejected(rejection: Rejection): GrpcServiceException =
    new GrpcServiceException(statusOf(rejection))

  /** Exception for a failure to build a value type → INVALID_ARGUMENT. */
  def invalid(error: ValidationError): GrpcServiceException =
    new GrpcServiceException(Status.INVALID_ARGUMENT.withDescription(error.message))

  /** Exception for a backend/service failure → UNAVAILABLE. */
  def unavailable(cause: Throwable): GrpcServiceException =
    new GrpcServiceException(Status.UNAVAILABLE.withDescription("service unavailable").withCause(cause))

  /** Exception for a missing/invalid credential → UNAUTHENTICATED. */
  def unauthenticated: GrpcServiceException =
    new GrpcServiceException(Status.UNAUTHENTICATED.withDescription("missing or invalid credentials"))

  /** Exception for a principal lacking a required scope → PERMISSION_DENIED. */
  def permissionDenied: GrpcServiceException =
    new GrpcServiceException(Status.PERMISSION_DENIED.withDescription("admin scope required"))
