package me.cference.hermesmq.http

import me.cference.hermesmq.auth.Principal
import me.cference.hermesmq.auth.TenantScope
import me.cference.hermesmq.delivery.DeadLetterIndexProjection
import me.cference.hermesmq.delivery.DeadLetterRouter
import me.cference.hermesmq.delivery.InMemoryDeadLetterRepository
import me.cference.hermesmq.delivery.InMemoryTopicSubscriptionsRepository
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
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Integration across the pieces this capability adds: a dead-lettered message
  * flows through the index projection's effect into the read model, is visible to
  * the browse endpoint, and is redriven back to its origin topic.
  *
  * Uses the in-memory repositories, so it needs no database -- it exercises the
  * seams between projection, read model and routes, which the per-component specs
  * individually do not.
  */
final class DeadLetterFlowSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  private val tenant = TenantScope.DefaultTenant
  private val scope  = new TenantScope(tenant)
  private val admin  = Principal(tenant, Set("admin"))

  private val sub    = SubscriptionId.from("s1").toOption.get
  private val origin = TopicId.from("orders").toOption.get
  private val ackId  = AckId.from("ack-1").toOption.get
  private val msgId  = MessageId.from("m-1").toOption.get
  private val t0     = Instant.parse("2026-07-07T12:00:00Z")
  private val deadAt = t0.plusSeconds(300)

  private val original =
    Message.from(msgId, "charge-42".getBytes, Map("k" -> "v"), t0).toOption.get

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)
  private def await[A](f: => Future[A]): A = Await.result(f, 3.seconds)

  private final class CapturingTopics extends TopicService:
    val published = new ConcurrentLinkedQueue[(TopicId, Message)]()
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
      command match
        case TopicCommand.Publish(m) =>
          published.add((id, m))
          Future.successful(CommandReply.Published(m.id, deduplicated = false))
        case _ => Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)

  private val subService: SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
      Future.successful(Some(Nil))

  "the dead-letter flow" should {
    "carry a dead-lettered message from the journal event through browse to a redrive on its origin topic" in {
      val deadLetters = InMemoryDeadLetterRepository()
      val index       = InMemoryTopicSubscriptionsRepository()
      val topics      = CapturingTopics()
      await(index.add(origin, sub))

      // 1. The subscription exhausts its attempts. Both effects of that event
      //    run: routing to the dead-letter topic, and recording the evidence.
      val event = SubscriptionEvent.MessageDeadLettered(ackId, original, 5)
      val dlTopic = TopicId.from("dead-letters").toOption.get
      val routed  = await(DeadLetterRouter.route(topics, Some(dlTopic), sub, await(index.topicFor(sub)), event))
      val _       = routed shouldBe a[me.cference.hermesmq.delivery.DeadLetterOutcome.Published]

      // The dead-letter envelope records where the message came from.
      val (_, envelope) = topics.published.asScala.head
      val _             = envelope.attributes("x-original-topic") shouldBe origin.value

      await(DeadLetterIndexProjection.indexEvent(deadLetters, sub, await(index.topicFor(sub)), deadAt, event))

      val routes = Route.seal(DeadLetterRoutes(deadLetters, subService, topics, index, admin, scope).routes)

      // 2. The operator sees it, without consuming it.
      val _ = Get("/v1/subscriptions/s1/dead-letters") ~> routes ~> check {
        val _     = status shouldBe StatusCodes.OK
        val items = responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]]
        val _     = items.size shouldBe 1
        val _     = items.head.fields("originalMessageId").convertTo[String] shouldBe "m-1"
        val _     = items.head.fields("originTopic").convertTo[String] shouldBe "orders"
        val _     = items.head.fields("payload").convertTo[String] shouldBe "charge-42"
        items.head.fields("deliveryAttempts").convertTo[Int] shouldBe 5
      }

      // 3. The operator puts it back on the topic it came from.
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> routes ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]] shouldBe List("m-1")
      }

      val redriven = topics.published.asScala.toList.filter(_._1 == origin)
      val _        = redriven.size shouldBe 1
      val _        = new String(redriven.head._2.payload.toArray) shouldBe "charge-42"
      val _        = redriven.head._2.idempotencyKey shouldBe Some("redrive:s1:m-1")

      // 4. The entry is marked, so a second redrive reports it rather than
      //    publishing a duplicate.
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> routes ~> check {
        val _ = status shouldBe StatusCodes.OK
        val body = responseAs[String].parseJson.asJsObject
        val _    = body.fields("alreadyRedriven").convertTo[List[String]] shouldBe List("m-1")
        body.fields("redriven").convertTo[List[String]] shouldBe empty
      }
      topics.published.asScala.toList.count(_._1 == origin) shouldBe 1
    }

    "walk a multi-page browse and redrive from the last page" in {
      val deadLetters = InMemoryDeadLetterRepository()
      val index       = InMemoryTopicSubscriptionsRepository()
      val topics      = CapturingTopics()
      await(index.add(origin, sub))

      // Five dead-letters recorded through the projection effect, as the runtime would.
      (1 to 5).foreach { i =>
        val m = Message.from(MessageId.from(s"f-$i").toOption.get, s"body-$i".getBytes, Map.empty, t0).toOption.get
        await(
          DeadLetterIndexProjection.indexEvent(
            deadLetters,
            sub,
            Some(origin),
            deadAt.plusSeconds(i.toLong),
            SubscriptionEvent.MessageDeadLettered(ackId, m, 5)
          )
        )
      }

      val routes = Route.seal(DeadLetterRoutes(deadLetters, subService, topics, index, admin, scope).routes)

      def page(cursor: Option[String]) =
        val q = cursor.fold("?limit=2")(c => s"?limit=2&cursor=$c")
        var ids: List[String]    = Nil
        var next: Option[String] = None
        Get(s"/v1/subscriptions/s1/dead-letters$q") ~> routes ~> check {
          val body = responseAs[String].parseJson.asJsObject
          ids = body.fields("deadLetters").convertTo[List[JsObject]].map(_.fields("originalMessageId").convertTo[String])
          next = body.fields.get("nextCursor").map(_.convertTo[String])
        }
        (ids, next)

      val (p1, c1) = page(None)
      val (p2, c2) = page(c1)
      val (p3, c3) = page(c2)
      val walked   = p1 ++ p2 ++ p3
      val _        = walked.size shouldBe 5
      val _        = walked.distinct.size shouldBe 5
      val _        = c3 shouldBe None

      // Redrive the oldest entry, which only the last page surfaced.
      val oldest = walked.last
      val _ = Post(
        "/v1/subscriptions/s1/dead-letters/redrive",
        json(s"""{"messageIds":["$oldest"]}""")
      ) ~> routes ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]] shouldBe List(oldest)
      }
      topics.published.asScala.toList.map(_._1) shouldBe List(origin)
    }

    "publish exactly once when two redrives of the same entry race" in {
      val deadLetters = InMemoryDeadLetterRepository()
      val index       = InMemoryTopicSubscriptionsRepository()
      val topics      = CapturingTopics()
      await(index.add(origin, sub))
      await(
        DeadLetterIndexProjection.indexEvent(
          deadLetters,
          sub,
          Some(origin),
          deadAt,
          SubscriptionEvent.MessageDeadLettered(ackId, original, 5)
        )
      )

      val routes = Route.seal(DeadLetterRoutes(deadLetters, subService, topics, index, admin, scope).routes)

      // Two sequential requests stand in for the race the claim serialises; the
      // repository-level concurrency test covers the genuinely parallel case.
      val outcomes = (1 to 2).map { _ =>
        var redriven: List[String] = Nil
        Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> routes ~> check {
          redriven = responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]]
        }
        redriven
      }
      val _ = outcomes.count(_.nonEmpty) shouldBe 1
      topics.published.asScala.size shouldBe 1
    }

    "record and redrive a dead-letter even when no dead-letter topic is configured" in {
      val deadLetters = InMemoryDeadLetterRepository()
      val index       = InMemoryTopicSubscriptionsRepository()
      val topics      = CapturingTopics()
      await(index.add(origin, sub))

      val event = SubscriptionEvent.MessageDeadLettered(ackId, original, 5)

      // Routing drops it -- today that is a warning log and nothing else.
      val routed = await(DeadLetterRouter.route(topics, None, sub, Some(origin), event))
      val _      = routed shouldBe a[me.cference.hermesmq.delivery.DeadLetterOutcome.Dropped]
      val _      = topics.published.asScala shouldBe empty

      // The index still recorded it, so the evidence survives.
      await(DeadLetterIndexProjection.indexEvent(deadLetters, sub, Some(origin), deadAt, event))

      val routes = Route.seal(DeadLetterRoutes(deadLetters, subService, topics, index, admin, scope).routes)

      val _ = Get("/v1/subscriptions/s1/dead-letters") ~> routes ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]].size shouldBe 1
      }

      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> routes ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]] shouldBe List("m-1")
      }
      topics.published.asScala.toList.map(_._1) shouldBe List(origin)
    }
  }
