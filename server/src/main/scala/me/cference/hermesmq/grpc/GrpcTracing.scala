package me.cference.hermesmq.grpc

import me.cference.hermesmq.tracing.Correlation
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/** Correlation + access logging for the gRPC handler (request-tracing), the
  * gRPC counterpart of [[me.cference.hermesmq.http.RequestTracing]]. Wraps the
  * HTTP/2 handler function: adopts an inbound `x-correlation-id` (trusted
  * internal callers) or mints one, stamps it onto the request's headers so the
  * power-API handlers re-read it from `Metadata`, access-logs entry and
  * completion, and adds the header to the response — which, at the HTTP level,
  * covers trailers-only error responses too.
  */
object GrpcTracing:

  private val log = LoggerFactory.getLogger("me.cference.hermesmq.grpc.access")

  def instrument(
      inner: HttpRequest => Future[HttpResponse]
  )(using ec: ExecutionContext): HttpRequest => Future[HttpResponse] =
    request =>
      val inbound = request.headers.find(_.is(Correlation.MetadataKey)).map(_.value)
      val id      = Correlation.adoptOrMint(inbound)
      val method  = methodLabel(request.uri.path.toString)
      // Re-stamp so the handler's Metadata read sees exactly the effective id
      // (adopted verbatim, or the minted one when the caller sent none).
      val tagged =
        request.removeHeader(Correlation.MetadataKey).addHeader(RawHeader(Correlation.MetadataKey, id))
      withMdc(id)(log.info(s"→ gRPC $method"))
      val startNanos = System.nanoTime()
      inner(tagged).transform {
        case Success(response) =>
          logDone(id, method, response.status.intValue.toString, startNanos)
          Success(response.addHeader(RawHeader(Correlation.MetadataKey, id)))
        case Failure(error) =>
          logDone(id, method, "ERROR", startNanos)
          Failure(error)
      }

  private def logDone(id: String, method: String, status: String, startNanos: Long): Unit =
    val millis = (System.nanoTime() - startNanos) / 1000000L
    withMdc(id)(log.info(s"← gRPC $method $status ${millis}ms"))

  /** Run `body` with the id in the MDC — symmetric put/remove, nothing leaks. */
  private def withMdc[A](id: String)(body: => A): A =
    MDC.put(Correlation.MdcKey, id)
    try body
    finally MDC.remove(Correlation.MdcKey)

  /** The RPC name = the last path segment; empty/odd paths fall back to "unknown". */
  private def methodLabel(path: String): String =
    path.split('/').filter(_.nonEmpty).lastOption.getOrElse("unknown")
