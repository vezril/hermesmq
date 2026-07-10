package me.cference.hermesmq.observability

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.delivery.HermesJdbcSession
import me.cference.hermesmq.domain.TopicEvent
import me.cference.hermesmq.domain.TopicId
import me.cference.hermesmq.persistence.TopicEntity
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.projection.ProjectionId
import org.apache.pekko.projection.eventsourced.EventEnvelope
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.jdbc.scaladsl.JdbcHandler
import org.apache.pekko.projection.jdbc.scaladsl.JdbcProjection
import org.apache.pekko.projection.scaladsl.SourceProvider

import java.sql.Connection

/** Pekko Projection maintaining the per-topic stats read model (published count,
  * existence, deleted flag) by folding tagged topic events. Uses `exactlyOnce` so
  * the published-count increment and the offset advance commit together. Runs as
  * a single cluster-wide instance.
  */
object TopicStatsProjection:

  private val ProjectionName = "topic-stats-projection"

  private def topicIdOf(persistenceId: String): Option[TopicId] =
    persistenceId.split('|') match
      case Array("Topic", id) => TopicId.from(id).toOption
      case _                  => None

  def apply(system: ActorSystem[?], dbConfig: DbConfig) =
    val sourceProvider: SourceProvider[Offset, EventEnvelope[TopicEvent]] =
      EventSourcedProvider.eventsByTag[TopicEvent](
        system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = TopicEntity.StatsTag
      )

    JdbcProjection.exactlyOnce(
      projectionId = ProjectionId(ProjectionName, TopicEntity.StatsTag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new HermesJdbcSession(dbConfig),
      handler = () => new Handler
    )(system)

  private final class Handler extends JdbcHandler[EventEnvelope[TopicEvent], HermesJdbcSession]:
    override def process(session: HermesJdbcSession, envelope: EventEnvelope[TopicEvent]): Unit =
      topicIdOf(envelope.persistenceId).foreach { topicId =>
        session.withConnection { (conn: Connection) =>
          TopicStatsFold(new JdbcTopicStatsSink(conn), topicId, envelope.event)
        }
      }
