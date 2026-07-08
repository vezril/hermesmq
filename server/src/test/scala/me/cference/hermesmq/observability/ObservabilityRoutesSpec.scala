package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

import java.time.Instant

/** Route tests for the observability endpoints, backed by the in-memory repos. */
final class ObservabilityRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  private val sub   = SubscriptionId.from("s1").toOption.get
  private val topic = TopicId.from("orders").toOption.get
  private val t0    = Instant.parse("2026-07-07T12:00:00Z")
  private val now   = Instant.parse("2026-07-07T12:01:30Z")
  private def msgAt(at: Instant) = Message.from(MessageId.from("m").toOption.get, "x".getBytes, Map.empty, at).toOption.get

  private def populated(): (SubscriptionStatsRepository, TopicStatsRepository) =
    val s = InMemorySubscriptionStatsRepository()
    SubscriptionStatsFold(s, sub, SubscriptionEvent.SubscriptionCreated(sub, topic))
    SubscriptionStatsFold(s, sub, SubscriptionEvent.MessageDelivered(AckId.from("a1").toOption.get, msgAt(t0)))
    SubscriptionStatsFold(s, sub, SubscriptionEvent.AckDeadlineExpired(AckId.from("a1").toOption.get, 1))
    val t = InMemoryTopicStatsRepository()
    TopicStatsFold(t, topic, TopicEvent.TopicCreated(topic))
    TopicStatsFold(t, topic, TopicEvent.MessagePublished(msgAt(t0)))
    (s, t)

  private def routes(s: SubscriptionStatsRepository, t: TopicStatsRepository) =
    Route.seal(ObservabilityRoutes(s, t, () => now).routes)

  "GET /metrics" should {
    "return Prometheus text with the expected samples" in {
      val (s, t) = populated()
      Get("/metrics") ~> routes(s, t) ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        val body = responseAs[String]
        body should include("""hermesmq_subscription_backlog{subscription="s1"} 1""")
        body should include("""hermesmq_subscription_oldest_unacked_age_seconds{subscription="s1"} 90""")
        body should include("""hermesmq_messages_published_total{topic="orders"} 1""")
        body should include("""hermesmq_messages_redelivered_total{subscription="s1"} 1""")
      }
    }
  }

  "GET /v1/subscriptions" should {
    "list subscriptions with their stats" in {
      val (s, t) = populated()
      Get("/v1/subscriptions") ~> routes(s, t) ~> check {
        status shouldBe StatusCodes.OK
        val arr = responseAs[String].parseJson.asInstanceOf[JsArray]
        val o   = arr.elements.head.asJsObject
        o.fields("subscriptionId").convertTo[String] shouldBe "s1"
        o.fields("backlog").convertTo[Int] shouldBe 1
        o.fields("oldestUnackedAgeSeconds").convertTo[Long] shouldBe 90
        o.fields("redeliveredTotal").convertTo[Long] shouldBe 1
      }
    }
  }

  "GET /v1/topics" should {
    "list topics with their published counts" in {
      val (s, t) = populated()
      Get("/v1/topics") ~> routes(s, t) ~> check {
        status shouldBe StatusCodes.OK
        val o = responseAs[String].parseJson.asInstanceOf[JsArray].elements.head.asJsObject
        o.fields("topicId").convertTo[String] shouldBe "orders"
        o.fields("publishedTotal").convertTo[Long] shouldBe 1
      }
    }
  }

  "Empty listings" should {
    "return an empty array, not an error" in {
      val s = InMemorySubscriptionStatsRepository()
      val t = InMemoryTopicStatsRepository()
      Get("/v1/subscriptions") ~> routes(s, t) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String].parseJson shouldBe JsArray()
      }
      Get("/v1/topics") ~> routes(s, t) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String].parseJson shouldBe JsArray()
      }
    }
  }
