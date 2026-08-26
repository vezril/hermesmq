package me.cference.hermesmq.http

import me.cference.hermesmq.tracing.Correlation
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/** Correlation + access logging for the HTTP route tree (request-tracing), the
  * HTTP counterpart of [[me.cference.hermesmq.grpc.GrpcTracing]]. Wrapping the
  * routes with [[withCorrelationId]] adopts an inbound `X-Correlation-Id`
  * (trusted internal callers) or mints one, puts it in the MDC for the
  * request's logs, access-logs entry and completion, and adds the header on
  * every response. The inner routes are sealed below the response mapping so
  * rejections/exceptions become responses first — the header is present on
  * 4xx/5xx and 404s too, not only on explicit completions.
  */
object RequestTracing:

  private val log = LoggerFactory.getLogger("me.cference.hermesmq.http.access")

  def withCorrelationId(inner: Route): Route =
    extractRequest { request =>
      // `.is` matches case-insensitively against the lower-cased name, so this
      // sees `X-Correlation-Id` and `x-correlation-id` alike.
      val inbound = request.headers.find(_.is(Correlation.MetadataKey)).map(_.value)
      val id      = Correlation.adoptOrMint(inbound)
      val method  = request.method.value
      val path    = request.uri.path.toString
      val start   = System.nanoTime()
      withMdc(id)(log.info(s"→ HTTP $method $path"))
      mapResponse { response =>
        val millis = (System.nanoTime() - start) / 1000000L
        withMdc(id)(log.info(s"← HTTP $method $path ${response.status.intValue} ${millis}ms"))
        response.addHeader(RawHeader(Correlation.HttpHeader, id))
      } {
        Route.seal(inner)
      }
    }

  /** Run `body` with the id in the MDC — symmetric put/remove, nothing leaks. */
  private def withMdc[A](id: String)(body: => A): A =
    MDC.put(Correlation.MdcKey, id)
    try body
    finally MDC.remove(Correlation.MdcKey)
