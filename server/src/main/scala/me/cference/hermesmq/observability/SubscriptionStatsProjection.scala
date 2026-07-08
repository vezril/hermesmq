package me.cference.hermesmq.observability

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.delivery.HermesJdbcSession
import me.cference.hermesmq.domain.{SubscriptionEvent, SubscriptionId}
import me.cference.hermesmq.persistence.SubscriptionEntity
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.projection.ProjectionId
import org.apache.pekko.projection.eventsourced.EventEnvelope
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.jdbc.scaladsl.{JdbcHandler, JdbcProjection}
import org.apache.pekko.projection.scaladsl.SourceProvider

import java.sql.Connection

/** Pekko Projection maintaining the per-subscription stats read model (backlog,
  * oldest-unacked-age, redelivery/dead-letter counts) by folding tagged
  * subscription events. Uses `exactlyOnce` so the read-model write and the offset
  * advance commit in one transaction — counter increments never double-count on
  * replay. Runs as a single cluster-wide instance.
  */
object SubscriptionStatsProjection:

  private val ProjectionName = "subscription-stats-projection"

  private def subscriptionIdOf(persistenceId: String): Option[SubscriptionId] =
    persistenceId.split('|') match
      case Array("Subscription", id) => SubscriptionId.from(id).toOption
      case _                         => None

  def apply(system: ActorSystem[?], dbConfig: DbConfig) =
    val sourceProvider: SourceProvider[Offset, EventEnvelope[SubscriptionEvent]] =
      EventSourcedProvider.eventsByTag[SubscriptionEvent](
        system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = SubscriptionEntity.StatsTag
      )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(ProjectionName, SubscriptionEntity.StatsTag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new HermesJdbcSession(dbConfig),
      handler = () => new Handler
    )(system)

  private final class Handler extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:
    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      subscriptionIdOf(envelope.persistenceId).foreach { subscriptionId =>
        session.withConnection { (conn: Connection) =>
          SubscriptionStatsFold(new JdbcSubscriptionStatsSink(conn), subscriptionId, envelope.event)
        }
      }
