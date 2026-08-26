package me.cference.hermesmq.http

import me.cference.hermesmq.tracing.Correlation
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** The REST boundary of request-tracing: adopt-or-mint + echo on every
  * response, including sealed rejections (404s).
  */
final class RequestTracingSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val inner  = path("ping")(get(complete(StatusCodes.OK)))
  private val routes = RequestTracing.withCorrelationId(inner)

  "RequestTracing.withCorrelationId" should {

    "adopt an inbound correlation id and echo it on the response" in {
      Get("/ping").addHeader(RawHeader(Correlation.HttpHeader, "upstream-7")) ~> routes ~> check {
        val _ = status shouldBe StatusCodes.OK
        header(Correlation.HttpHeader).map(_.value) shouldBe Some("upstream-7")
      }
    }

    "mint an id when the request carries none" in {
      Get("/ping") ~> routes ~> check {
        val _  = status shouldBe StatusCodes.OK
        val id = header(Correlation.HttpHeader).map(_.value).getOrElse("")
        id should fullyMatch regex "[0-9a-z]{12}"
      }
    }

    "adopt the lower-case header spelling too" in {
      Get("/ping").addHeader(RawHeader(Correlation.MetadataKey, "lower-1")) ~> routes ~> check {
        header(Correlation.HttpHeader).map(_.value) shouldBe Some("lower-1")
      }
    }

    "carry the id on an error response (sealed rejection)" in {
      Get("/nope").addHeader(RawHeader(Correlation.HttpHeader, "err-1")) ~> routes ~> check {
        val _ = status shouldBe StatusCodes.NotFound
        header(Correlation.HttpHeader).map(_.value) shouldBe Some("err-1")
      }
    }
  }
