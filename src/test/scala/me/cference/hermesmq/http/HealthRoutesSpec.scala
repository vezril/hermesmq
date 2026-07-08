package me.cference.hermesmq.http

import org.apache.pekko.http.scaladsl.model.{ContentTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import spray.json.*

import java.util.concurrent.atomic.AtomicBoolean

/** Route-level tests for the health endpoints, run in-memory via the Pekko HTTP
  * testkit — no socket is opened.
  */
final class HealthRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  /** Build routes with a controllable readiness flag for the tests. */
  private def build(ready: Boolean = true): (AtomicBoolean, HealthRoutes) =
    val flag = new AtomicBoolean(ready)
    (flag, HealthRoutes(version = "1.2.3-test", readiness = () => flag.get()))

  private val routes = build()._2.routes

  "GET /health" should {
    "return 200 with a JSON status body" in {
      Get("/health") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`application/json`
        val json = responseAs[String].parseJson.asJsObject
        json.fields("status").convertTo[String] shouldBe "UP"
        json.fields("service").convertTo[String] shouldBe "hermesmq"
        json.fields("version").convertTo[String] shouldBe "1.2.3-test"
      }
    }

    "respond 200 with no body to HEAD" in {
      Head("/health") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe empty
      }
    }
  }

  "An unmapped path" should {
    "not be handled (404)" in {
      Get("/healthz") ~> routes ~> check {
        handled shouldBe false
      }
    }
  }

  "GET /health/ready" should {
    "return 200 when readiness is true" in {
      Get("/health/ready") ~> build(ready = true)._2.routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    "return 503 when readiness is false" in {
      Get("/health/ready") ~> build(ready = false)._2.routes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }

    "report 503 on readiness while liveness still returns 200 during drain" in {
      val (flag, health) = build(ready = true)
      flag.set(false) // simulate shutdown draining
      Get("/health/ready") ~> health.routes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
      Get("/health") ~> health.routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    "report 503 when persistence is unreachable while liveness stays 200" in {
      val readiness = Readiness(persistenceHealthy = () => false)
      readiness.markBound() // bound, but persistence is down
      val routes = HealthRoutes(version = "1.2.3-test", readiness = () => readiness.isReady).routes
      Get("/health/ready") ~> routes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
      Get("/health") ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }
  }
