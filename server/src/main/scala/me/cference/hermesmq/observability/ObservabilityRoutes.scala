package me.cference.hermesmq.observability

import me.cference.hermesmq.auth.Principal
import me.cference.hermesmq.auth.TenantScope
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.http.scaladsl.model.HttpEntity
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.*

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** JSON views for the admin listing endpoints. */
final case class SubscriptionStatsJson(
    subscriptionId: String,
    topicId: String,
    backlog: Int,
    oldestUnackedAgeSeconds: Long,
    redeliveredTotal: Long,
    deadLetteredTotal: Long
)
final case class TopicStatsJson(topicId: String, publishedTotal: Long, deleted: Boolean)

object ObservabilityJson extends DefaultJsonProtocol:
  given RootJsonFormat[SubscriptionStatsJson] = jsonFormat6(SubscriptionStatsJson.apply)
  given RootJsonFormat[TopicStatsJson]        = jsonFormat3(TopicStatsJson.apply)

/** Observability endpoints, reading the stats read models off the hot path:
  *   - `GET /metrics` — Prometheus text exposition
  *   - `GET /v1/subscriptions` — subscriptions with their stats (JSON)
  *   - `GET /v1/topics` — topics with their published counts (JSON)
  *
  * `now` is injected so age gauges are testable.
  */
final class ObservabilityRoutes(
    subscriptions: SubscriptionStatsRepository,
    topics: TopicStatsRepository,
    now: () => Instant,
    consumers: ConsumerRegistry = ConsumerRegistry(scala.concurrent.duration.Duration.Zero)
)(using ExecutionContext):
  import ObservabilityJson.given
  import SprayJsonSupport.*

  /** Public Prometheus scrape endpoint (global, not tenant-filtered). */
  val metricsRoute: Route =
    path("metrics") {
      get {
        onSuccess(metricsBody) { body =>
          complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, body))
        }
      }
    }

  /** Tenant-scoped admin listings: only the principal's own resources, with
    * external (unqualified) ids.
    */
  def listings(principal: Principal, scope: TenantScope): Route =
    concat(
      path("v1" / "subscriptions") {
        get {
          val at = now()
          onSuccess(subscriptions.list()) { stats =>
            val mine = stats.filter(s => scope.belongsTo(principal.tenant, s.subscriptionId.value))
            complete(JsArray(mine.map(s => toJson(at, scope, principal)(s).toJson).toVector))
          }
        }
      },
      path("v1" / "topics") {
        get {
          onSuccess(topics.list()) { stats =>
            val mine = stats.filter(t => scope.belongsTo(principal.tenant, t.topicId.value))
            complete(JsArray(mine.map(t => toJson(scope, principal)(t).toJson).toVector))
          }
        }
      }
    )

  /** Metrics + listings for the default tenant (used when auth is off / in tests). */
  val routes: Route =
    metricsRoute ~ listings(Principal(TenantScope.DefaultTenant, Set.empty), new TenantScope(TenantScope.DefaultTenant))

  private def metricsBody: Future[String] =
    val at = now()
    for
      s <- subscriptions.list()
      t <- topics.list()
    yield PrometheusText.render(s, t, at, consumers.activeCountsBySubscription(at))

  private def toJson(at: Instant, scope: TenantScope, principal: Principal)(s: SubscriptionStats): SubscriptionStatsJson =
    SubscriptionStatsJson(
      scope.unqualify(principal.tenant, s.subscriptionId.value),
      scope.unqualify(principal.tenant, s.topicId.value),
      s.backlog,
      s.oldestUnackedAgeSeconds(at),
      s.redeliveredTotal,
      s.deadLetteredTotal
    )

  private def toJson(scope: TenantScope, principal: Principal)(t: TopicStats): TopicStatsJson =
    TopicStatsJson(scope.unqualify(principal.tenant, t.topicId.value), t.publishedTotal, t.deleted)

object ObservabilityRoutes:
  def apply(
      subscriptions: SubscriptionStatsRepository,
      topics: TopicStatsRepository,
      now: () => Instant = () => Instant.now(),
      consumers: ConsumerRegistry = ConsumerRegistry(scala.concurrent.duration.Duration.Zero)
  )(using ExecutionContext): ObservabilityRoutes =
    new ObservabilityRoutes(subscriptions, topics, now, consumers)
