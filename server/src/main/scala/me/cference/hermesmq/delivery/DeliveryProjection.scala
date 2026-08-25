package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
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

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Pekko Projection that tails tagged topic `MessagePublished` events and fans
  * each out to the subscriptions on its topic, at least once. Offsets are stored
  * in PostgreSQL so delivery resumes after a restart.
  */
object DeliveryProjection:

  private val ProjectionName = "topic-message-delivery"

  def apply(system: ActorSystem[?], dbConfig: DbConfig, handler: DeliveryHandler) =
    val sourceProvider: SourceProvider[Offset, EventEnvelope[TopicEvent]] =
      EventSourcedProvider.eventsByTag[TopicEvent](
        system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = TopicEntity.MessageTag
      )

    JdbcProjection.atLeastOnce(
      projectionId = ProjectionId(ProjectionName, TopicEntity.MessageTag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new HermesJdbcSession(dbConfig),
      handler = () => new Handler(handler)
    )(system)

  /** Extract the topic id from a `Topic|<id>` persistence id. */
  private def topicIdOf(persistenceId: String): Option[TopicId] =
    persistenceId.split('|') match
      case Array("Topic", id) => TopicId.from(id).toOption
      case _                  => None

  /** Delivers each `MessagePublished` synchronously (the JDBC projection runs on
    * a blocking dispatcher), so the offset is only advanced after delivery.
    */
  private final class Handler(delivery: DeliveryHandler)
      extends JdbcHandler[EventEnvelope[TopicEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[TopicEvent]): Unit =
      envelope.event match
        case TopicEvent.MessagePublished(message) =>
          topicIdOf(envelope.persistenceId).foreach { topicId =>
            Await.result(delivery.deliver(topicId, message), 10.seconds)
          }
        case _ => ()
