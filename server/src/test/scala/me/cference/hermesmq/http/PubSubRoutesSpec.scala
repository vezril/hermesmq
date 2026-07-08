package me.cference.hermesmq.http

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

import java.time.Instant
import scala.concurrent.Future

/** Route tests for the pub/sub API, backed by stub services. */
final class PubSubRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  private val ackId   = AckId.from("ack-1").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hello".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  private def topicStub(reply: CommandReply = CommandReply.Accepted): TopicService = new TopicService:
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] = Future.successful(reply)
    def query(id: TopicId): Future[Option[TopicSnapshot]]                = Future.successful(None)

  private def subStub(
      submitReply: CommandReply = CommandReply.Accepted,
      pullReply: Option[List[PulledMessage]] = Some(Nil)
  ): SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] = Future.successful(submitReply)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]]         = Future.successful(pullReply)

  private def routes(
      topics: TopicService = topicStub(),
      subs: SubscriptionService = subStub()
  ) = Route.seal(PubSubRoutes(topics, subs).routes)

  "POST /v1/topics/{id}/messages" should {
    "accept a publish and return 202 with a messageId" in {
      Post("/v1/topics/orders/messages", json("""{"payload":"hello"}""")) ~> routes() ~> check {
        status shouldBe StatusCodes.Accepted
        responseAs[String].parseJson.asJsObject.fields("messageId").convertTo[String] should not be empty
      }
    }
    "return 404 when the topic does not exist" in {
      Post("/v1/topics/ghost/messages", json("""{"payload":"hi"}""")) ~>
        routes(topics = topicStub(CommandReply.Rejected(Rejection.TopicNotFound))) ~> check {
          status shouldBe StatusCodes.NotFound
        }
    }
    "return 400 for an empty payload" in {
      Post("/v1/topics/orders/messages", json("""{"payload":""}""")) ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "POST /v1/subscriptions" should {
    "create a subscription (201)" in {
      Post("/v1/subscriptions", json("""{"subscriptionId":"s1","topicId":"orders"}""")) ~>
        routes(subs = subStub()) ~> check {
          status shouldBe StatusCodes.Created
        }
    }
    "return 409 for a duplicate subscription" in {
      Post("/v1/subscriptions", json("""{"subscriptionId":"s1","topicId":"orders"}""")) ~>
        routes(subs = subStub(CommandReply.Rejected(Rejection.SubscriptionAlreadyExists))) ~> check {
          status shouldBe StatusCodes.Conflict
        }
    }
  }

  "POST /v1/subscriptions/{id}/pull" should {
    "return 200 with outstanding messages" in {
      Post("/v1/subscriptions/s1/pull", json("""{"max":10}""")) ~>
        routes(subs = subStub(pullReply = Some(List(PulledMessage(ackId, message))))) ~> check {
          status shouldBe StatusCodes.OK
          val arr = responseAs[String].parseJson.asJsObject.fields("messages").convertTo[JsArray]
          arr.elements.size shouldBe 1
          arr.elements.head.asJsObject.fields("ackId").convertTo[String] shouldBe "ack-1"
          arr.elements.head.asJsObject.fields("payload").convertTo[String] shouldBe "hello"
        }
    }
    "return 404 when the subscription does not exist" in {
      Post("/v1/subscriptions/ghost/pull", json("""{"max":10}""")) ~>
        routes(subs = subStub(pullReply = None)) ~> check {
          status shouldBe StatusCodes.NotFound
        }
    }
  }

  "POST /v1/subscriptions/{id}/ack" should {
    "acknowledge messages and return 200" in {
      Post("/v1/subscriptions/s1/ack", json("""{"ackIds":["ack-1"]}""")) ~> routes() ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("acknowledged").convertTo[List[String]] shouldBe List("ack-1")
      }
    }
    "report an unknown ackId without failing the batch" in {
      Post("/v1/subscriptions/s1/ack", json("""{"ackIds":["ack-1"]}""")) ~>
        routes(subs = subStub(submitReply = CommandReply.Rejected(Rejection.UnknownAckId(ackId)))) ~> check {
          status shouldBe StatusCodes.OK
          responseAs[String].parseJson.asJsObject.fields("unknown").convertTo[List[String]] shouldBe List("ack-1")
        }
    }
  }
