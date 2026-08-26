package me.cference.hermesmq.client

import me.cference.hermesmq.domain.AckId
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.StatusCode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpecLike

/** Tests the client's HTTP/JSON wiring against a self-contained stub server, so
  * the client stays fully decoupled from the server module. The stub echoes
  * request evidence (bodies, auth headers) back through canned responses so the
  * tests can assert exact request shapes.
  */
final class HermesClientSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with ScalaFutures:

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(50, Millis))

  private def tid(s: String) = TopicId.from(s).toOption.get
  private def sid(s: String) = SubscriptionId.from(s).toOption.get
  private def jsonOk(body: String) = complete(HttpEntity(ContentTypes.`application/json`, body))
  private def jsonStatus(status: StatusCode, body: String) =
    complete((status, HttpEntity(ContentTypes.`application/json`, body)))

  // The stub records the last publish/pull request body + auth headers so tests
  // can assert the wire shape (volatile: written on the server dispatcher, read
  // on the test thread after the Future completes).
  @volatile private var lastPublishBody: String     = ""
  @volatile private var lastPullBody: String        = ""
  @volatile private var lastAuthHeader: String      = ""
  @volatile private var lastApiKeyHeader: String    = ""
  @volatile private var lastCorrelationHeader: String = ""

  private val stub: Route = extractRequest { req =>
    lastAuthHeader = req.headers.find(_.is("authorization")).map(_.value).getOrElse("")
    lastApiKeyHeader = req.headers.find(_.is("x-api-key")).map(_.value).getOrElse("")
    lastCorrelationHeader = req.headers.find(_.is("x-correlation-id")).map(_.value).getOrElse("")
    concat(
      pathPrefix("v1" / "topics") {
        concat(
          pathEndOrSingleSlash {
            concat(
              post {
                entity(as[String]) { body =>
                  if body.contains("\"dup\"") then complete(StatusCodes.Conflict) else complete(StatusCodes.Created)
                }
              },
              get {
                jsonOk("""[{"topicId":"orders","publishedTotal":42,"deleted":false}]""")
              }
            )
          },
          path(Segment / "messages") { id =>
            post {
              entity(as[String]) { body =>
                lastPublishBody = body
                if id == "ghost" then complete(StatusCodes.NotFound)
                else if id == "plaintype" then
                  // 2xx with JSON body but a non-JSON content-type label — the
                  // Demeter trap: delivery must be judged by status, not label.
                  complete(
                    (
                      StatusCodes.Accepted,
                      HttpEntity(ContentTypes.`text/plain(UTF-8)`, """{"messageId":"m-plain","deduplicated":false}""")
                    )
                  )
                else if body.contains("\"idem-1\"") then
                  jsonStatus(StatusCodes.Accepted, """{"messageId":"m-orig","deduplicated":true}""")
                else jsonStatus(StatusCodes.Accepted, """{"messageId":"m-123","deduplicated":false}""")
              }
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
            concat(
              post {
                entity(as[String]) { body =>
                  if body.contains("\"dupsub\"") then complete(StatusCodes.Conflict) else complete(StatusCodes.Created)
                }
              },
              get {
                jsonOk(
                  """[{"subscriptionId":"s1","topicId":"orders","backlog":3,"oldestUnackedAgeSeconds":7,"redeliveredTotal":1,"deadLetteredTotal":0}]"""
                )
              }
            )
          },
          path(Segment / "pull") { id =>
            post {
              entity(as[String]) { body =>
                lastPullBody = body
                if id == "ghost" then complete(StatusCodes.NotFound)
                else if id == "corr-sub" then
                  jsonOk(
                    """{"messages":[{"ackId":"a1","payload":"hello","attributes":{},"publishTime":"2026-07-08T00:00:00Z","correlationId":"corr-42"}]}"""
                  )
                else
                  jsonOk(
                    """{"messages":[{"ackId":"a1","payload":"hello","attributes":{"k":"v"},"publishTime":"2026-07-08T00:00:00Z"}]}"""
                  )
              }
            }
          },
          path(Segment / "ack") { _ =>
            post { jsonOk("""{"acknowledged":["a1"],"unknown":["a-stale"]}""") }
          },
          path(Segment / "modifyAckDeadline") { _ =>
            post { jsonOk("""{"modified":["a1"],"unknown":["a-stale"]}""") }
          },
          path(Segment) { id =>
            delete {
              if id == "s1" then complete(StatusCodes.NoContent) else complete(StatusCodes.NotFound)
            }
          }
        )
      },
      path("health") {
        get { jsonOk("""{"status":"UP","service":"hermesmq","version":"1.11.0"}""") }
      }
    )
  }

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
    "list topics with their published totals" in {
      val topics = client.listTopics().futureValue
      val _      = topics.map(_.topicId.value) shouldBe List("orders")
      topics.head.publishedTotal shouldBe 42L
    }
  }

  "HermesClient publish & consume" should {
    "publish a message and return its id with the dedup flag" in {
      val result = client.publish(tid("orders"), "hello", Map("k" -> "v")).futureValue
      val _      = result.messageId.value shouldBe "m-123"
      result.deduplicated shouldBe false
    }
    "forward publish options in the request body" in {
      val _ = client
        .publish(
          tid("orders"),
          "hello",
          ttlSeconds = Some(60),
          idempotencyKey = Some("key-9"),
          producerId = Some("ingest-1")
        )
        .futureValue
      val _ = lastPublishBody should include("\"ttlSeconds\":60")
      val _ = lastPublishBody should include("\"idempotencyKey\":\"key-9\"")
      lastPublishBody should include("\"producerId\":\"ingest-1\"")
    }
    "surface a deduplicated publish" in {
      val result = client.publish(tid("orders"), "hello", idempotencyKey = Some("idem-1")).futureValue
      val _      = result.messageId.value shouldBe "m-orig"
      result.deduplicated shouldBe true
    }
    "count a 2xx publish as delivered even when the content type is not JSON" in {
      val result = client.publish(tid("plaintype"), "hello").futureValue
      val _      = result.messageId.value shouldBe "m-plain"
      result.deduplicated shouldBe false
    }
    "create a subscription (201 → success)" in {
      client.createSubscription(sid("s1"), tid("orders")).futureValue
      succeed
    }
    "delete a subscription (204 → success)" in {
      client.deleteSubscription(sid("s1")).futureValue
      succeed
    }
    "fail deleting a missing subscription with the status attached" in {
      client.deleteSubscription(sid("ghost")).failed.futureValue match
        case ex: HermesClientException => ex.status shouldBe StatusCodes.NotFound
        case other                     => fail(s"expected HermesClientException, got $other")
    }
    "pull messages with their ack ids and payloads" in {
      val msgs = client.pull(sid("s1"), 10).futureValue
      val _    = msgs.map(_.ackId.value) shouldBe List("a1")
      val _    = msgs.head.payload shouldBe "hello"
      msgs.head.attributes shouldBe Map("k" -> "v")
    }
    "send the correlation id as the X-Correlation-Id header on publish" in {
      val _ = client.publish(tid("orders"), "hello", correlationId = Some("corr-42")).futureValue
      lastCorrelationHeader shouldBe "corr-42"
    }
    "surface the delivered correlationId on pull (and None when absent)" in {
      val correlated = client.pull(sid("corr-sub")).futureValue
      val _          = correlated.head.correlationId shouldBe Some("corr-42")
      val plain = client.pull(sid("s1")).futureValue
      plain.head.correlationId shouldBe None
    }
    "forward the consumer id on pull" in {
      val _ = client.pull(sid("s1"), 5, consumerId = Some("worker-1")).futureValue
      lastPullBody should include("\"consumerId\":\"worker-1\"")
    }
    "return acknowledged and unknown ids from ack" in {
      val result = client.ack(sid("s1"), List(AckId.from("a1").toOption.get)).futureValue
      val _      = result.acknowledged shouldBe List("a1")
      result.unknown shouldBe List("a-stale")
    }
    "modify ack deadlines and report modified/unknown" in {
      val result =
        client.modifyAckDeadline(sid("s1"), List(AckId.from("a1").toOption.get), ackDeadlineSeconds = 30).futureValue
      val _ = result.modified shouldBe List("a1")
      result.unknown shouldBe List("a-stale")
    }
    "list subscriptions with their stats" in {
      val subs = client.listSubscriptions().futureValue
      val _    = subs.map(_.subscriptionId.value) shouldBe List("s1")
      subs.head.backlog shouldBe 3
    }
    "surface an unreachable listing as an error and never as an empty result" in {
      // "couldn't ask" must stay distinguishable from "nobody is listening":
      // a caller alarming on zero subscribers must not see unknown as 0.
      val broken = HermesClient(s"http://localhost:${binding.localAddress.getPort}/nope")(using system)
      broken.listSubscriptions().failed.futureValue shouldBe a[HermesClientException]
    }
    "fail when publishing to a missing topic" in {
      client.publish(tid("ghost"), "x").failed.futureValue shouldBe a[HermesClientException]
    }
    "fail when pulling from a missing subscription" in {
      client.pull(sid("ghost")).failed.futureValue shouldBe a[HermesClientException]
    }
  }

  "HermesClient observability & auth" should {
    "report broker health" in {
      client.health().futureValue.status shouldBe "UP"
    }
    "send no auth headers by default" in {
      val _ = client.health().futureValue
      val _ = lastAuthHeader shouldBe ""
      lastApiKeyHeader shouldBe ""
    }
    "send a bearer token when configured" in {
      val authed = HermesClient(s"http://localhost:${binding.localAddress.getPort}", token = Some("t1"))(using system)
      val _ = authed.health().futureValue
      lastAuthHeader shouldBe "Bearer t1"
    }
    "send an api key when configured" in {
      val keyed = HermesClient(s"http://localhost:${binding.localAddress.getPort}", apiKey = Some("k1"))(using system)
      val _ = keyed.health().futureValue
      lastApiKeyHeader shouldBe "k1"
    }
  }
