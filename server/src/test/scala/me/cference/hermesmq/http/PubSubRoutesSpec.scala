package me.cference.hermesmq.http

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.*
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Route tests for the pub/sub API, backed by stub services. */
final class PubSubRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  private val ackId   = AckId.from("ack-1").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hello".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  private def topicStub(reply: CommandReply = CommandReply.Accepted): TopicService = new TopicService:
    // Publish replies Published; a default Accepted becomes Published(id,false),
    // while an explicit Rejected still flows through.
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] = Future.successful((command, reply) match
      case (TopicCommand.Publish(m), CommandReply.Accepted) => CommandReply.Published(m.id, deduplicated = false)
      case _                                                => reply)
    def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)

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

  /** Captures the message of the last Publish command, to inspect TTL. */
  private final class CapturingTopics extends TopicService:
    @volatile var lastPublished: Option[Message] = None
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
      command match
        case TopicCommand.Publish(m) => lastPublished = Some(m); Future.successful(CommandReply.Published(m.id, deduplicated = false))
        case _                       => Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)

  "TTL on publish" should {
    "set expireTime from ttlSeconds" in {
      val cap = CapturingTopics()
      Post("/v1/topics/orders/messages", json("""{"payload":"hi","ttlSeconds":60}""")) ~>
        Route.seal(PubSubRoutes(cap, subStub(), me.cference.hermesmq.config.TtlConfig.Default).routes) ~> check {
          val _ = status shouldBe StatusCodes.Accepted
          val m = cap.lastPublished.getOrElse(fail("no message published"))
          val _ = m.expireTime.isDefined shouldBe true
          m.expireTime.get.isAfter(m.publishTime) shouldBe true
        }
    }

    "apply the configured default TTL when no ttlSeconds is given" in {
      val cap = CapturingTopics()
      Post("/v1/topics/orders/messages", json("""{"payload":"hi"}""")) ~>
        Route.seal(PubSubRoutes(cap, subStub(), me.cference.hermesmq.config.TtlConfig(30.seconds)).routes) ~> check {
          cap.lastPublished.flatMap(_.expireTime).isDefined shouldBe true
        }
    }

    "leave expireTime unset when neither ttlSeconds nor a default is set" in {
      val cap = CapturingTopics()
      Post("/v1/topics/orders/messages", json("""{"payload":"hi"}""")) ~>
        Route.seal(PubSubRoutes(cap, subStub(), me.cference.hermesmq.config.TtlConfig.Default).routes) ~> check {
          cap.lastPublished.flatMap(_.expireTime) shouldBe None
        }
    }
  }

  "POST /v1/topics/{id}/messages" should {
    "accept a publish and return 202 with a messageId" in {
      Post("/v1/topics/orders/messages", json("""{"payload":"hello"}""")) ~> routes() ~> check {
        val _ = status shouldBe StatusCodes.Accepted
        val o = responseAs[String].parseJson.asJsObject
        val _ = o.fields("messageId").convertTo[String] should not be empty
        o.fields("deduplicated").convertTo[Boolean] shouldBe false
      }
    }

    "carry the idempotencyKey into the published message" in {
      val cap = CapturingTopics()
      Post("/v1/topics/orders/messages", json("""{"payload":"hi","idempotencyKey":"abc"}""")) ~>
        Route.seal(PubSubRoutes(cap, subStub()).routes) ~> check {
          val _ = status shouldBe StatusCodes.Accepted
          cap.lastPublished.flatMap(_.idempotencyKey) shouldBe Some("abc")
        }
    }

    "report deduplicated=true and the original messageId when the aggregate reports a duplicate" in {
      val original = MessageId.from("orig-1").toOption.get
      Post("/v1/topics/orders/messages", json("""{"payload":"hi","idempotencyKey":"abc"}""")) ~>
        routes(topics = topicStub(CommandReply.Published(original, deduplicated = true))) ~> check {
          val _ = status shouldBe StatusCodes.Accepted
          val o = responseAs[String].parseJson.asJsObject
          val _ = o.fields("messageId").convertTo[String] shouldBe "orig-1"
          o.fields("deduplicated").convertTo[Boolean] shouldBe true
        }
    }
    "increment the dedup counter on a deduplicated publish" in {
      val original = MessageId.from("orig-1").toOption.get
      val counter  = me.cference.hermesmq.observability.DedupCounter()
      Post("/v1/topics/orders/messages", json("""{"payload":"hi","idempotencyKey":"abc"}""")) ~>
        Route.seal(PubSubRoutes(topicStub(CommandReply.Published(original, deduplicated = true)), subStub(), dedup = counter).routes) ~> check {
          val _ = status shouldBe StatusCodes.Accepted
          counter.counts shouldBe Map(TopicId.from("orders").toOption.get -> 1L)
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
          val _ = status shouldBe StatusCodes.OK
          val arr = responseAs[String].parseJson.asJsObject.fields("messages").convertTo[JsArray]
          val _ = arr.elements.size shouldBe 1
          val _ = arr.elements.head.asJsObject.fields("ackId").convertTo[String] shouldBe "ack-1"
          arr.elements.head.asJsObject.fields("payload").convertTo[String] shouldBe "hello"
        }
    }
    "return 404 when the subscription does not exist" in {
      Post("/v1/subscriptions/ghost/pull", json("""{"max":10}""")) ~>
        routes(subs = subStub(pullReply = None)) ~> check {
          status shouldBe StatusCodes.NotFound
        }
    }
    "record a named consumer as active on pull" in {
      val reg = me.cference.hermesmq.observability.ConsumerRegistry(1.minute)
      Post("/v1/subscriptions/s1/pull", json("""{"max":10,"consumerId":"worker-3"}""")) ~>
        Route.seal(PubSubRoutes(topicStub(), subStub(), consumers = reg).routes) ~> check {
          val _ = status shouldBe StatusCodes.OK
          reg.activeCount(SubscriptionId.from("s1").toOption.get, Instant.now()) shouldBe 1
        }
    }
  }

  "POST /v1/subscriptions/{id}/ack" should {
    "acknowledge messages and return 200" in {
      Post("/v1/subscriptions/s1/ack", json("""{"ackIds":["ack-1"]}""")) ~> routes() ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("acknowledged").convertTo[List[String]] shouldBe List("ack-1")
      }
    }
    "report an unknown ackId without failing the batch" in {
      Post("/v1/subscriptions/s1/ack", json("""{"ackIds":["ack-1"]}""")) ~>
        routes(subs = subStub(submitReply = CommandReply.Rejected(Rejection.UnknownAckId(ackId)))) ~> check {
          val _ = status shouldBe StatusCodes.OK
          responseAs[String].parseJson.asJsObject.fields("unknown").convertTo[List[String]] shouldBe List("ack-1")
        }
    }
  }

  "POST /v1/subscriptions/{id}/modifyAckDeadline" should {
    "extend a lease and report the ackId as modified (200)" in {
      Post("/v1/subscriptions/s1/modifyAckDeadline", json("""{"ackIds":["ack-1"],"ackDeadlineSeconds":60}""")) ~>
        routes(subs = subStub()) ~> check {
          val _ = status shouldBe StatusCodes.OK
          responseAs[String].parseJson.asJsObject.fields("modified").convertTo[List[String]] shouldBe List("ack-1")
        }
    }
    "accept a zero deadline as a nack (200, modified)" in {
      Post("/v1/subscriptions/s1/modifyAckDeadline", json("""{"ackIds":["ack-1"],"ackDeadlineSeconds":0}""")) ~>
        routes(subs = subStub()) ~> check {
          val _ = status shouldBe StatusCodes.OK
          responseAs[String].parseJson.asJsObject.fields("modified").convertTo[List[String]] shouldBe List("ack-1")
        }
    }
    "report an unknown ackId without failing the batch (200)" in {
      Post("/v1/subscriptions/s1/modifyAckDeadline", json("""{"ackIds":["ack-1"],"ackDeadlineSeconds":60}""")) ~>
        routes(subs = subStub(submitReply = CommandReply.Rejected(Rejection.UnknownAckId(ackId)))) ~> check {
          val _ = status shouldBe StatusCodes.OK
          responseAs[String].parseJson.asJsObject.fields("unknown").convertTo[List[String]] shouldBe List("ack-1")
        }
    }
    "return 404 when the subscription does not exist" in {
      Post("/v1/subscriptions/ghost/modifyAckDeadline", json("""{"ackIds":["ack-1"],"ackDeadlineSeconds":60}""")) ~>
        routes(subs = subStub(pullReply = None)) ~> check {
          status shouldBe StatusCodes.NotFound
        }
    }
  }
