package me.cference.hermesmq.grpc

import me.cference.hermesmq.tracing.Correlation
import org.apache.pekko.http.scaladsl.model.HttpRequest
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The gRPC boundary of request-tracing: adopt-or-mint, re-stamp request
  * metadata for the handlers, echo the id on responses.
  */
final class GrpcTracingSpec extends AnyWordSpec with Matchers with ScalaFutures:

  private given ExecutionContext = ExecutionContext.global

  /** A stub handler that records the request it saw and answers 200. */
  private final class Recording:
    @volatile var seen: Option[HttpRequest] = None
    val handler: HttpRequest => Future[HttpResponse] = req =>
      seen = Some(req)
      Future.successful(HttpResponse(StatusCodes.OK))

  private def request(headers: List[RawHeader] = Nil): HttpRequest =
    HttpRequest(uri = Uri("/hermesmq.v1.PubSubService/Publish")).withHeaders(headers)

  "GrpcTracing.instrument" should {

    "adopt an inbound x-correlation-id, restamp the request, and echo it on the response" in {
      val stub     = Recording()
      val response = GrpcTracing.instrument(stub.handler)(request(List(RawHeader(Correlation.MetadataKey, "up-1")))).futureValue
      val _        = response.headers.find(_.is(Correlation.MetadataKey)).map(_.value) shouldBe Some("up-1")
      stub.seen.flatMap(_.headers.find(_.is(Correlation.MetadataKey)).map(_.value)) shouldBe Some("up-1")
    }

    "mint an id when the request carries none and stamp it for the handler" in {
      val stub     = Recording()
      val response = GrpcTracing.instrument(stub.handler)(request()).futureValue
      val echoed   = response.headers.find(_.is(Correlation.MetadataKey)).map(_.value).getOrElse("")
      val _        = echoed should fullyMatch regex "[0-9a-z]{12}"
      // The handler saw the SAME minted id the caller was given.
      stub.seen.flatMap(_.headers.find(_.is(Correlation.MetadataKey)).map(_.value)) shouldBe Some(echoed)
    }

    "carry the id on a trailers-only style error response" in {
      val failing: HttpRequest => Future[HttpResponse] =
        _ => Future.successful(HttpResponse(StatusCodes.OK)) // gRPC errors are 200 + trailers; header add is status-independent
      val response =
        GrpcTracing.instrument(failing)(request(List(RawHeader(Correlation.MetadataKey, "err-1")))).futureValue
      response.headers.find(_.is(Correlation.MetadataKey)).map(_.value) shouldBe Some("err-1")
    }
  }
