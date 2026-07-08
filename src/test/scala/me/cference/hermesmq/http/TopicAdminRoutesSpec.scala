package me.cference.hermesmq.http

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.{CommandReply, TopicService, TopicSnapshot}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

import scala.concurrent.Future

/** Route tests for the topic admin API, backed by a stub `TopicService` so no
  * actor system or journal is needed.
  */
final class TopicAdminRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  private val topicId = TopicId.from("orders").toOption.get

  private def jsonEntity(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  private def stub(
      submitReply: CommandReply = CommandReply.Accepted,
      queryReply: Option[TopicSnapshot] = None
  ): TopicService = new TopicService:
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply]   = Future.successful(submitReply)
    def query(id: TopicId): Future[Option[TopicSnapshot]]                  = Future.successful(queryReply)

  // Seal the route so rejections (e.g. malformed body) map to responses (400),
  // as the running server does at its boundary.
  private def routes(service: TopicService) = Route.seal(TopicAdminRoutes(service).routes)

  "POST /v1/topics" should {
    "create a topic and return 201" in {
      val body = """{"topicId":"orders","labels":{"team":"payments"}}"""
      Post("/v1/topics", jsonEntity(body)) ~> routes(stub()) ~> check {
        status shouldBe StatusCodes.Created
      }
    }
    "return 409 when the topic already exists" in {
      val body = """{"topicId":"orders"}"""
      Post("/v1/topics", jsonEntity(body)) ~> routes(stub(CommandReply.Rejected(Rejection.TopicAlreadyExists))) ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }
    "return 400 for a blank topic id" in {
      Post("/v1/topics", jsonEntity("""{"topicId":"  "}""")) ~> routes(stub()) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
    "return 400 for a body missing topicId" in {
      Post("/v1/topics", jsonEntity("""{"labels":{}}""")) ~> routes(stub()) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "GET /v1/topics/{id}" should {
    "return 200 with the topic labels when it exists" in {
      Get("/v1/topics/orders") ~> routes(stub(queryReply = Some(TopicSnapshot(topicId, Map("team" -> "payments"))))) ~> check {
        status shouldBe StatusCodes.OK
        val json = responseAs[String].parseJson.asJsObject
        json.fields("topicId").convertTo[String] shouldBe "orders"
        json.fields("labels").convertTo[Map[String, String]] shouldBe Map("team" -> "payments")
      }
    }
    "return 404 when the topic does not exist" in {
      Get("/v1/topics/ghost") ~> routes(stub(queryReply = None)) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }

  "PATCH /v1/topics/{id}" should {
    "update labels and return 200" in {
      Patch("/v1/topics/orders", jsonEntity("""{"labels":{"team":"core"}}""")) ~> routes(stub()) ~> check {
        status shouldBe StatusCodes.OK
      }
    }
    "return 404 when the topic does not exist" in {
      Patch("/v1/topics/ghost", jsonEntity("""{"labels":{}}""")) ~> routes(stub(CommandReply.Rejected(Rejection.TopicNotFound))) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }

  "DELETE /v1/topics/{id}" should {
    "delete a topic and return 204" in {
      Delete("/v1/topics/orders") ~> routes(stub()) ~> check {
        status shouldBe StatusCodes.NoContent
      }
    }
    "return 404 when the topic does not exist" in {
      Delete("/v1/topics/ghost") ~> routes(stub(CommandReply.Rejected(Rejection.TopicNotFound))) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }
  }
