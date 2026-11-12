package me.cference.hermesmq.http

import me.cference.hermesmq.auth.Principal
import me.cference.hermesmq.auth.TenantId
import me.cference.hermesmq.auth.TenantScope
import me.cference.hermesmq.delivery.DeadLetterEntry
import me.cference.hermesmq.delivery.DeadLetterRepository
import me.cference.hermesmq.delivery.InMemoryDeadLetterRepository
import me.cference.hermesmq.delivery.InMemoryTopicSubscriptionsRepository
import me.cference.hermesmq.delivery.TopicSubscriptionsRepository
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

/** Route tests for the dead-letter operator endpoints, backed by stubs. */
final class DeadLetterRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  private val tenant      = TenantScope.DefaultTenant
  private val otherTenant = TenantId.from("acme").toOption.get
  private val scope       = new TenantScope(tenant)
  private val admin       = Principal(tenant, Set("admin"))
  private val reader      = Principal(tenant, Set.empty)

  private val t0 = Instant.parse("2026-07-07T12:00:00Z")

  private def sid(s: String) = SubscriptionId.from(s).toOption.get
  private def tid(s: String) = TopicId.from(s).toOption.get
  private def mid(s: String) = MessageId.from(s).toOption.get

  private def json(body: String) = HttpEntity(ContentTypes.`application/json`, body)

  private def entry(sub: String, msg: String, origin: Option[String] = Some("orders"), at: Instant = t0) =
    DeadLetterEntry(
      subscriptionId = sid(sub),
      originalMessageId = mid(msg),
      originTopic = origin.map(tid),
      payload = s"body-$msg".getBytes.toVector,
      attributes = Map("k" -> "v"),
      deliveryAttempts = 5,
      deadLetteredAt = at,
      redrivenAt = None
    )

  /** A subscription service whose zero-max pull probe reports existence. */
  private def subStub(exists: Boolean = true): SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
      Future.successful(if exists then Some(Nil) else None)

  /** Captures republished messages, and can reject to simulate a deleted topic. */
  private final class CapturingTopics(reject: Boolean = false) extends TopicService:
    val published = new ConcurrentLinkedQueue[(TopicId, Message)]()
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
      command match
        case TopicCommand.Publish(m) =>
          if reject then Future.successful(CommandReply.Rejected(Rejection.TopicNotFound))
          else
            published.add((id, m))
            Future.successful(CommandReply.Published(m.id, deduplicated = false))
        case _ => Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)

  private def repoWith(entries: DeadLetterEntry*): DeadLetterRepository =
    val repo = InMemoryDeadLetterRepository()
    entries.foreach(e => Await.result(repo.record(e), 3.seconds))
    repo

  private def indexWith(pairs: (String, String)*): TopicSubscriptionsRepository =
    val repo = InMemoryTopicSubscriptionsRepository()
    pairs.foreach { case (t, s) => Await.result(repo.add(tid(t), sid(s)), 3.seconds) }
    repo

  private def routes(
      deadLetters: DeadLetterRepository = repoWith(),
      subs: SubscriptionService = subStub(),
      topics: TopicService = CapturingTopics(),
      index: TopicSubscriptionsRepository = indexWith(),
      principal: Principal = admin
  ) = Route.seal(DeadLetterRoutes(deadLetters, subs, topics, index, principal, scope).routes)

  "GET /v1/subscriptions/{id}/dead-letters" should {
    "list the subscription's dead-letters with their metadata" in {
      Get("/v1/subscriptions/s1/dead-letters") ~> routes(deadLetters =
        repoWith(entry("s1", "m-1"), entry("s1", "m-2", at = t0.plusSeconds(60)))
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val items = responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]]
        val _     = items.size shouldBe 2
        // Most recently dead-lettered first.
        val _ = items.head.fields("originalMessageId").convertTo[String] shouldBe "m-2"
        val _ = items.head.fields("originTopic").convertTo[String] shouldBe "orders"
        val _ = items.head.fields("deliveryAttempts").convertTo[Int] shouldBe 5
        val _ = items.head.fields("payload").convertTo[String] shouldBe "body-m-2"
        items.head.fields("attributes").convertTo[Map[String, String]] shouldBe Map("k" -> "v")
      }
    }

    "be repeatable and non-destructive" in {
      val repo  = repoWith(entry("s1", "m-1"))
      val route = routes(deadLetters = repo)
      val _ = Get("/v1/subscriptions/s1/dead-letters") ~> route ~> check { status shouldBe StatusCodes.OK }
      Get("/v1/subscriptions/s1/dead-letters") ~> route ~> check {
        val _ = status shouldBe StatusCodes.OK
        val items = responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]]
        val _     = items.size shouldBe 1
        items.head.fields("deliveryAttempts").convertTo[Int] shouldBe 5
      }
    }

    "return an empty list for a subscription with no dead-letters" in {
      Get("/v1/subscriptions/s1/dead-letters") ~> routes() ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]] shouldBe empty
      }
    }

    "list dead-letters recorded with no origin topic" in {
      Get("/v1/subscriptions/s1/dead-letters") ~> routes(deadLetters = repoWith(entry("s1", "m-1", origin = None))) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val items = responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]]
        items.head.fields.contains("originTopic") shouldBe false
      }
    }

    "404 an unknown subscription" in {
      Get("/v1/subscriptions/nope/dead-letters") ~> routes(subs = subStub(exists = false)) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "400 an id that escapes its tenant namespace" in {
      Get("/v1/subscriptions/acme~s1/dead-letters") ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "succeed for a non-admin principal (browse needs no admin scope)" in {
      Get("/v1/subscriptions/s1/dead-letters") ~> routes(principal = reader) ~> check {
        status shouldBe StatusCodes.OK
      }
    }

    "not expose another tenant's dead-letters under the same external id" in {
      // The entry below is the default tenant's (internal id `s1`). An `acme`
      // caller asking for its own `s1` resolves the internal id `acme~s1`, so it
      // must see nothing rather than the other tenant's message.
      Get("/v1/subscriptions/s1/dead-letters") ~> routes(
        deadLetters = repoWith(entry("s1", "m-1")),
        principal = Principal(otherTenant, Set("admin"))
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]] shouldBe empty
      }
    }
  }

  "GET /v1/subscriptions/{id}/dead-letters pagination" should {
    def entries(n: Int) =
      (1 to n).map(i => entry("s1", s"m-$i", at = t0.plusSeconds(i.toLong * 60))).toList

    "bound the page by limit and offer a cursor when more remain" in {
      Get("/v1/subscriptions/s1/dead-letters?limit=2") ~> routes(deadLetters = repoWith(entries(3)*)) ~> check {
        val _    = status shouldBe StatusCodes.OK
        val body = responseAs[String].parseJson.asJsObject
        val _    = body.fields("deadLetters").convertTo[List[JsObject]].size shouldBe 2
        body.fields.get("nextCursor") shouldBe defined
      }
    }

    "omit the cursor on the last page" in {
      Get("/v1/subscriptions/s1/dead-letters?limit=5") ~> routes(deadLetters = repoWith(entries(3)*)) ~> check {
        val _    = status shouldBe StatusCodes.OK
        val body = responseAs[String].parseJson.asJsObject
        val _    = body.fields("deadLetters").convertTo[List[JsObject]].size shouldBe 3
        body.fields.get("nextCursor") shouldBe None
      }
    }

    "walk every entry exactly once across pages" in {
      val route = routes(deadLetters = repoWith(entries(5)*))
      def page(cursor: Option[String]) =
        val q = cursor.fold("?limit=2")(c => s"?limit=2&cursor=$c")
        var ids: List[String] = Nil
        var next: Option[String] = None
        Get(s"/v1/subscriptions/s1/dead-letters$q") ~> route ~> check {
          val body = responseAs[String].parseJson.asJsObject
          ids = body.fields("deadLetters").convertTo[List[JsObject]].map(_.fields("originalMessageId").convertTo[String])
          next = body.fields.get("nextCursor").map(_.convertTo[String])
        }
        (ids, next)

      val (p1, c1) = page(None)
      val (p2, c2) = page(c1)
      val (p3, c3) = page(c2)
      val all      = p1 ++ p2 ++ p3
      val _        = all.size shouldBe 5
      val _        = all.distinct.size shouldBe 5
      val _        = c3 shouldBe None
      all.head shouldBe "m-5" // newest first
    }

    "clamp an over-large limit rather than rejecting it" in {
      Get("/v1/subscriptions/s1/dead-letters?limit=100000") ~> routes(deadLetters = repoWith(entries(3)*)) ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("deadLetters").convertTo[List[JsObject]].size shouldBe 3
      }
    }

    "400 a cursor the service did not issue" in {
      Get("/v1/subscriptions/s1/dead-letters?cursor=not-a-real-cursor") ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "POST /v1/subscriptions/{id}/dead-letters/redrive" should {
    "redrive by id, republishing to the origin topic with the original payload" in {
      val topics = CapturingTopics()
      val repo   = repoWith(entry("s1", "m-1"))
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> routes(
        deadLetters = repo,
        topics = topics
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val body = responseAs[String].parseJson.asJsObject
        val _    = body.fields("redriven").convertTo[List[String]] shouldBe List("m-1")
        val (topic, msg) = topics.published.asScala.head
        val _            = topic shouldBe tid("orders")
        new String(msg.payload.toArray) shouldBe "body-m-1"
      }
    }

    "redrive all of a subscription's dead-letters" in {
      val topics = CapturingTopics()
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> routes(
        deadLetters = repoWith(entry("s1", "m-1"), entry("s1", "m-2"), entry("s1", "m-3")),
        topics = topics
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val _ = responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]].size shouldBe 3
        topics.published.asScala.size shouldBe 3
      }
    }

    "carry an idempotency key derived from the original message id" in {
      val topics = CapturingTopics()
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> routes(
        deadLetters = repoWith(entry("s1", "m-1")),
        topics = topics
      ) ~> check {
        val (_, msg) = topics.published.asScala.head
        msg.idempotencyKey shouldBe Some("redrive:s1:m-1")
      }
    }

    "stay idempotent with the dedup window at its default (off)" in {
      // Nothing here configures HERMESMQ_DEDUP_WINDOW: the claim, not the publish
      // dedup, is what must prevent the second publish.
      val topics = CapturingTopics()
      val repo   = repoWith(entry("s1", "m-1"))
      val route  = routes(deadLetters = repo, topics = topics)
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> route ~> check {
        val _ = status shouldBe StatusCodes.OK
      }
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> route ~> check {
        val _ = status shouldBe StatusCodes.OK
      }
      topics.published.asScala.size shouldBe 1
    }

    "republish an already-redriven entry when force is set" in {
      val topics = CapturingTopics()
      val repo   = repoWith(entry("s1", "m-1"))
      val route  = routes(deadLetters = repo, topics = topics)
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> route ~> check {
        val _ = status shouldBe StatusCodes.OK
      }
      val _ = Post(
        "/v1/subscriptions/s1/dead-letters/redrive",
        json("""{"messageIds":["m-1"],"force":true}""")
      ) ~> route ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]] shouldBe List("m-1")
      }
      topics.published.asScala.size shouldBe 2
    }

    "release the claim when the publish fails, so a retry republishes" in {
      val repo = repoWith(entry("s1", "m-1"))
      // First attempt: the origin topic rejects, so the claim must be released.
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> routes(
        deadLetters = repo,
        topics = CapturingTopics(reject = true)
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("failed").convertTo[List[JsObject]].size shouldBe 1
      }
      // Retry against a healthy topic: it must publish, not report already-redriven.
      val healthy = CapturingTopics()
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> routes(
        deadLetters = repo,
        topics = healthy
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]] shouldBe List("m-1")
      }
      healthy.published.asScala.size shouldBe 1
    }

    "report an already-redriven message without republishing it" in {
      val topics = CapturingTopics()
      val repo   = repoWith(entry("s1", "m-1"))
      val route  = routes(deadLetters = repo, topics = topics)
      val _ = Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> route ~> check {
        val _ = status shouldBe StatusCodes.OK
      }
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> route ~> check {
        val _ = status shouldBe StatusCodes.OK
        val body = responseAs[String].parseJson.asJsObject
        val _    = body.fields("alreadyRedriven").convertTo[List[String]] shouldBe List("m-1")
        val _    = body.fields("redriven").convertTo[List[String]] shouldBe empty
        topics.published.asScala.size shouldBe 1 // not republished
      }
    }

    "report an unknown id without failing the valid ones" in {
      val topics = CapturingTopics()
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1","ghost"]}""")) ~> routes(
        deadLetters = repoWith(entry("s1", "m-1")),
        topics = topics
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val body = responseAs[String].parseJson.asJsObject
        val _    = body.fields("redriven").convertTo[List[String]] shouldBe List("m-1")
        val _    = body.fields("unknown").convertTo[List[String]] shouldBe List("ghost")
        topics.published.asScala.size shouldBe 1
      }
    }

    "report a failure with a reason when the origin topic rejects the publish" in {
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> routes(
        deadLetters = repoWith(entry("s1", "m-1")),
        topics = CapturingTopics(reject = true)
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val failed = responseAs[String].parseJson.asJsObject.fields("failed").convertTo[List[JsObject]]
        val _      = failed.map(_.fields("messageId").convertTo[String]) shouldBe List("m-1")
        failed.head.fields("reason").convertTo[String] should not be empty
      }
    }

    "fall back to the subscription's topic when the entry has no origin topic" in {
      val topics = CapturingTopics()
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> routes(
        deadLetters = repoWith(entry("s1", "m-1", origin = None)),
        topics = topics,
        index = indexWith("billing" -> "s1")
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val _ = responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]] shouldBe List("m-1")
        topics.published.asScala.head._1 shouldBe tid("billing")
      }
    }

    "fail with a reason when neither the entry nor the index resolves a topic" in {
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"messageIds":["m-1"]}""")) ~> routes(
        deadLetters = repoWith(entry("s1", "m-1", origin = None)),
        index = indexWith()
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val failed = responseAs[String].parseJson.asJsObject.fields("failed").convertTo[List[JsObject]]
        failed.map(_.fields("messageId").convertTo[String]) shouldBe List("m-1")
      }
    }

    "404 an unknown subscription" in {
      Post("/v1/subscriptions/nope/dead-letters/redrive", json("""{"all":true}""")) ~> routes(subs =
        subStub(exists = false)
      ) ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "400 a request naming neither ids nor all" in {
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{}""")) ~> routes() ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "403 a principal without the admin scope" in {
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> routes(principal = reader) ~> check {
        status shouldBe StatusCodes.Forbidden
      }
    }

    "redrive nothing for another tenant's subscription of the same external id" in {
      val topics = CapturingTopics()
      Post("/v1/subscriptions/s1/dead-letters/redrive", json("""{"all":true}""")) ~> routes(
        deadLetters = repoWith(entry("s1", "m-1")),
        topics = topics,
        principal = Principal(otherTenant, Set("admin"))
      ) ~> check {
        val _ = status shouldBe StatusCodes.OK
        val _ = responseAs[String].parseJson.asJsObject.fields("redriven").convertTo[List[String]] shouldBe empty
        topics.published.asScala shouldBe empty
      }
    }
  }
