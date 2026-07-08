package me.cference.hermesmq.client

import me.cference.hermesmq.domain.{AckId, SubscriptionId, TopicId}
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike

/** Tests the client's HTTP/JSON wiring against a self-contained stub server, so
  * the client stays fully decoupled from the server module.
  */
final class HermesClientSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with ScalaFutures:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  private def tid(s: String) = TopicId.from(s).toOption.get
  private def sid(s: String) = SubscriptionId.from(s).toOption.get
  private def jsonOk(body: String) = complete(HttpEntity(ContentTypes.`application/json`, body))
  private def jsonStatus(status: StatusCode, body: String) =
    complete((status, HttpEntity(ContentTypes.`application/json`, body)))

  private val stub: Route = concat(
    pathPrefix("v1" / "topics") {
      concat(
        pathEndOrSingleSlash {
          post {
            entity(as[String]) { body =>
              if body.contains("\"dup\"") then complete(StatusCodes.Conflict) else complete(StatusCodes.Created)
            }
          }
        },
        path(Segment / "messages") { id =>
          post {
            if id == "ghost" then complete(StatusCodes.NotFound)
            else jsonStatus(StatusCodes.Accepted, """{"messageId":"m-123"}""")
          }
        },
        path(Segment) { id =>
          concat(
            get {
              if id == "orders" then jsonOk("""{"topicId":"orders","labels":{"team":"payments"}}""")
              else complete(StatusCodes.NotFound)
            },
            patch { if id == "orders" then complete(StatusCodes.OK) else complete(StatusCodes.NotFound) },
            delete { if id == "orders" then complete(StatusCodes.NoContent) else complete(StatusCodes.NotFound) }
          )
        }
      )
    },
    pathPrefix("v1" / "subscriptions") {
      concat(
        pathEndOrSingleSlash {
          post {
            entity(as[String]) { body =>
              if body.contains("\"dupsub\"") then complete(StatusCodes.Conflict) else complete(StatusCodes.Created)
            }
          }
        },
        path(Segment / "pull") { id =>
          post {
            if id == "ghost" then complete(StatusCodes.NotFound)
            else jsonOk("""{"messages":[{"ackId":"a1","payload":"hello","attributes":{"k":"v"},"publishTime":"2026-07-08T00:00:00Z"}]}""")
          }
        },
        path(Segment / "ack") { _ =>
          post { jsonOk("""{"acknowledged":["a1"],"unknown":[]}""") }
        }
      )
    }
  )

  private lazy val binding: ServerBinding =
    Http()(system).newServerAt("localhost", 0).bind(stub).futureValue

  private lazy val client: HermesClient =
    HermesClient(s"http://localhost:${binding.localAddress.getPort}")(using system)

  "HermesClient topic management" should {
    "create a topic (201 → success)" in {
      client.createTopic(tid("orders"), Map("team" -> "payments")).futureValue
      succeed
    }
    "read a topic's labels" in {
      client.getTopic(tid("orders")).futureValue shouldBe Some(TopicInfo(tid("orders"), Map("team" -> "payments")))
    }
    "return None for a missing topic" in {
      client.getTopic(tid("ghost")).futureValue shouldBe None
    }
    "update a topic (200 → success)" in {
      client.updateTopic(tid("orders"), Map("team" -> "core")).futureValue
      succeed
    }
    "delete a topic (204 → success)" in {
      client.deleteTopic(tid("orders")).futureValue
      succeed
    }
    "fail with a typed error when creating a duplicate topic (409)" in {
      client.createTopic(tid("dup")).failed.futureValue shouldBe a[HermesClientException]
    }
  }

  "HermesClient publish & consume" should {
    "publish a message and return its id" in {
      client.publish(tid("orders"), "hello", Map("k" -> "v")).futureValue.value shouldBe "m-123"
    }
    "create a subscription (201 → success)" in {
      client.createSubscription(sid("s1"), tid("orders")).futureValue
      succeed
    }
    "pull messages with their ack ids and payloads" in {
      val msgs = client.pull(sid("s1"), 10).futureValue
      msgs.map(_.ackId.value) shouldBe List("a1")
      msgs.head.payload shouldBe "hello"
      msgs.head.attributes shouldBe Map("k" -> "v")
    }
    "acknowledge messages (200 → success)" in {
      client.ack(sid("s1"), List(AckId.from("a1").toOption.get)).futureValue
      succeed
    }
    "fail when publishing to a missing topic" in {
      client.publish(tid("ghost"), "x").failed.futureValue shouldBe a[HermesClientException]
    }
    "fail when pulling from a missing subscription" in {
      client.pull(sid("ghost")).failed.futureValue shouldBe a[HermesClientException]
    }
  }
