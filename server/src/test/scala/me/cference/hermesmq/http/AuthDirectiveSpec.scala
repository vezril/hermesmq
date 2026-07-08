package me.cference.hermesmq.http

import me.cference.hermesmq.auth.{AuthKey, Authenticator, Principal, TenantId, TenantScope}
import me.cference.hermesmq.config.AuthConfig
import me.cference.hermesmq.domain.{TopicCommand, TopicId}
import me.cference.hermesmq.persistence.{CommandReply, TopicService, TopicSnapshot}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.Base64
import scala.concurrent.Future

/** Tests the authentication directive and the admin/separator guards on the routes. */
final class AuthDirectiveSpec extends AnyWordSpec with Matchers with ScalatestRouteTest:

  private val tenant = TenantId.from("acme").toOption.get
  private val salt   = Base64.getEncoder.encodeToString("salt".getBytes)
  private val token  = "good-token"
  private val auth   = Authenticator(List(AuthKey(tenant, salt, Authenticator.computeHash(salt, token), Set("admin"))))
  private def cfg(enabled: Boolean) = AuthConfig(enabled, TenantScope.DefaultTenant, Nil)

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)
  private def topicStub(reply: CommandReply = CommandReply.Accepted): TopicService = new TopicService:
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] = Future.successful(reply)
    def query(id: TopicId): Future[Option[TopicSnapshot]]                = Future.successful(None)

  "The authenticate directive" should {
    val protectedRoute = (c: AuthConfig) => Route.seal(Auth.authenticate(auth, c)(p => complete(p.tenant.value)))

    "accept a valid bearer token and expose the principal" in {
      Get("/").addHeader(org.apache.pekko.http.scaladsl.model.headers.RawHeader("Authorization", s"Bearer $token")) ~>
        protectedRoute(cfg(true)) ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String] shouldBe "acme"
        }
    }

    "accept a valid X-API-Key" in {
      Get("/").addHeader(org.apache.pekko.http.scaladsl.model.headers.RawHeader("X-API-Key", token)) ~>
        protectedRoute(cfg(true)) ~> check { status shouldBe StatusCodes.OK }
    }

    "reject a missing credential with 401" in {
      Get("/") ~> protectedRoute(cfg(true)) ~> check { status shouldBe StatusCodes.Unauthorized }
    }

    "reject an invalid token with 401" in {
      Get("/").addHeader(org.apache.pekko.http.scaladsl.model.headers.RawHeader("Authorization", "Bearer nope")) ~>
        protectedRoute(cfg(true)) ~> check { status shouldBe StatusCodes.Unauthorized }
    }

    "map to the default tenant when auth is disabled" in {
      Get("/") ~> protectedRoute(cfg(false)) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe TenantScope.DefaultTenant.value
      }
    }
  }

  "Topic admin authorization" should {
    "allow an admin principal to create a topic" in {
      Post("/v1/topics", json("""{"topicId":"orders"}""")) ~>
        Route.seal(TopicAdminRoutes(topicStub(), Principal(tenant, Set("admin"))).routes) ~> check {
          status shouldBe StatusCodes.Created
        }
    }

    "forbid a non-admin principal from creating a topic (403)" in {
      Post("/v1/topics", json("""{"topicId":"orders"}""")) ~>
        Route.seal(TopicAdminRoutes(topicStub(), Principal(tenant, Set.empty)).routes) ~> check {
          status shouldBe StatusCodes.Forbidden
        }
    }

    "still allow a non-admin principal to read a topic" in {
      Get("/v1/topics/orders") ~>
        Route.seal(TopicAdminRoutes(topicStub(), Principal(tenant, Set.empty)).routes) ~> check {
          status shouldBe StatusCodes.NotFound // query stub returns None → 404, i.e. not 403
        }
    }
  }

  "Reserved-separator ids" should {
    "be rejected with 400 on topic create" in {
      Post("/v1/topics", json("""{"topicId":"a~b"}""")) ~>
        Route.seal(TopicAdminRoutes(topicStub(), Principal(tenant, Set("admin"))).routes) ~> check {
          status shouldBe StatusCodes.BadRequest
        }
    }
  }
