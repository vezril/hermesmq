package me.cference.hermesmq.observability

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.*

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

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
    now: () => Instant
)(using ExecutionContext):
  import ObservabilityJson.given
  import SprayJsonSupport.*

  val routes: Route =
    concat(
      path("metrics") {
        get {
          onSuccess(metricsBody) { body =>
            complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, body))
          }
        }
      },
      path("v1" / "subscriptions") {
        get {
          val at = now()
          onSuccess(subscriptions.list()) { stats =>
            complete(JsArray(stats.map(s => toJson(at)(s).toJson).toVector))
          }
        }
      },
      path("v1" / "topics") {
        get {
          onSuccess(topics.list())(stats => complete(JsArray(stats.map(t => toJson(t).toJson).toVector)))
        }
      }
    )

  private def metricsBody: Future[String] =
    val at = now()
    for
      s <- subscriptions.list()
      t <- topics.list()
    yield PrometheusText.render(s, t, at)

  private def toJson(at: Instant)(s: SubscriptionStats): SubscriptionStatsJson =
    SubscriptionStatsJson(
      s.subscriptionId.value,
      s.topicId.value,
      s.backlog,
      s.oldestUnackedAgeSeconds(at),
      s.redeliveredTotal,
      s.deadLetteredTotal
    )

  private def toJson(t: TopicStats): TopicStatsJson =
    TopicStatsJson(t.topicId.value, t.publishedTotal, t.deleted)

object ObservabilityRoutes:
  def apply(
      subscriptions: SubscriptionStatsRepository,
      topics: TopicStatsRepository,
      now: () => Instant = () => Instant.now()
  )(using ExecutionContext): ObservabilityRoutes =
    new ObservabilityRoutes(subscriptions, topics, now)
