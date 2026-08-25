package me.cference.hermesmq.http

import me.cference.hermesmq.auth.*
import me.cference.hermesmq.config.AuthConfig
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.observability.InMemorySubscriptionStatsRepository
import me.cference.hermesmq.observability.InMemoryTopicStatsRepository
import me.cference.hermesmq.observability.ObservabilityRoutes
import me.cference.hermesmq.persistence.*
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json.*

import java.util.Base64
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future

/** End-to-end REST auth + tenancy: the real directive, tenant-scoped services, and
  * routes over a stateful in-memory backing — two tenants using the same external
  * id stay isolated; unauthenticated calls are 401; /metrics is open.
  */
final class AuthTenancyIntegrationSpec extends AnyWordSpec with Matchers with ScalatestRouteTest with DefaultJsonProtocol:

  private val salt      = Base64.getEncoder.encodeToString("salt".getBytes)
  private def key(t: String, tok: String, scopes: Set[String]) =
    AuthKey(TenantId.from(t).toOption.get, salt, Authenticator.computeHash(salt, tok), scopes)
  private val acmeKey = key("acme", "acme-tok", Set("admin"))
  private val betaKey = key("beta", "beta-tok", Set("admin"))
  private val auth    = Authenticator(List(acmeKey, betaKey))
  private val config  = AuthConfig(enabled = true, TenantScope.DefaultTenant, List(acmeKey, betaKey))
  private val scope   = new TenantScope(TenantScope.DefaultTenant)

  /** Stateful in-memory topic store keyed by the (qualified) entity id. */
  private final class MemTopics extends TopicService:
    val store = TrieMap.empty[String, Map[String, String]]
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] = command match
      case TopicCommand.CreateTopic(tid, labels) =>
        if store.contains(tid.value) then Future.successful(CommandReply.Rejected(Rejection.TopicAlreadyExists))
        else { val _ = store.put(tid.value, labels); Future.successful(CommandReply.Accepted) }
      case _ => Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]] =
      Future.successful(store.get(id.value).map(l => TopicSnapshot(id, l)))

  private val subs: SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] = Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]]         = Future.successful(Some(Nil))

  private def routes(topics: TopicService): Route =
    val obs = ObservabilityRoutes(InMemorySubscriptionStatsRepository()(using system.dispatcher), InMemoryTopicStatsRepository()(using system.dispatcher))
    Route.seal(
      obs.metricsRoute ~
        Auth.authenticate(auth, config) { principal =>
          val st = TenantScopedTopicService(topics, scope, principal.tenant)(using system.dispatcher)
          val ss = TenantScopedSubscriptionService(subs, scope, principal.tenant)
          TopicAdminRoutes(st, principal).routes ~ PubSubRoutes(st, ss)(using system.dispatcher).routes ~ obs.listings(principal, scope)
        }
    )

  private def json(b: String) = HttpEntity(ContentTypes.`application/json`, b)
  private def as(tenantTok: String) = RawHeader("Authorization", s"Bearer $tenantTok")

  "REST auth + tenancy" should {
    "isolate two tenants that use the same external topic id" in {
      val topics = MemTopics()
      val r      = routes(topics)

      // Both tenants create "orders" with distinct labels.
      val _ = Post("/v1/topics", json("""{"topicId":"orders","labels":{"team":"acme"}}""")).addHeader(as("acme-tok")) ~> r ~> check {
        status shouldBe StatusCodes.Created
      }
      val _ = Post("/v1/topics", json("""{"topicId":"orders","labels":{"team":"beta"}}""")).addHeader(as("beta-tok")) ~> r ~> check {
        status shouldBe StatusCodes.Created
      }

      // Each reads back its own "orders" — isolated, external id shown.
      val _ = Get("/v1/topics/orders").addHeader(as("acme-tok")) ~> r ~> check {
        val _ = status shouldBe StatusCodes.OK
        val o = responseAs[String].parseJson.asJsObject
        val _ = o.fields("topicId").convertTo[String] shouldBe "orders"
        o.fields("labels").convertTo[Map[String, String]] shouldBe Map("team" -> "acme")
      }
      val _ = Get("/v1/topics/orders").addHeader(as("beta-tok")) ~> r ~> check {
        responseAs[String].parseJson.asJsObject.fields("labels").convertTo[Map[String, String]] shouldBe Map("team" -> "beta")
      }

      // Internally the two are distinct entities.
      topics.store.keySet shouldBe Set("acme~orders", "beta~orders")
    }

    "reject an unauthenticated /v1 request with 401" in {
      Get("/v1/topics/orders") ~> routes(MemTopics()) ~> check { status shouldBe StatusCodes.Unauthorized }
    }

    "leave /metrics open (no credential)" in {
      Get("/metrics") ~> routes(MemTopics()) ~> check { status shouldBe StatusCodes.OK }
    }
  }
