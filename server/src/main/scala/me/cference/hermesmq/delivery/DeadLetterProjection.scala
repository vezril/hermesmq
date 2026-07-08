package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.{SubscriptionEvent, SubscriptionId, TopicId}
import me.cference.hermesmq.persistence.{SubscriptionEntity, TopicService}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.projection.ProjectionId
import org.apache.pekko.projection.eventsourced.EventEnvelope
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.jdbc.scaladsl.{JdbcHandler, JdbcProjection}
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.slf4j.LoggerFactory

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*

/** Pekko Projection that reacts to dead-lettered messages by republishing them
  * onto the configured dead-letter topic (or dropping them, with a warning, when
  * none is set). Runs as a single cluster-wide instance; offsets are stored in
  * PostgreSQL so it resumes after restart.
  */
object DeadLetterProjection:

  private val ProjectionName = "subscription-dead-letter"
  private val log            = LoggerFactory.getLogger(getClass)

  private def subscriptionIdOf(persistenceId: String): Option[SubscriptionId] =
    persistenceId.split('|') match
      case Array("Subscription", id) => SubscriptionId.from(id).toOption
      case _                         => None

  def apply(system: ActorSystem[?], dbConfig: DbConfig, topics: TopicService, deadLetterTopic: Option[TopicId]) =
    val sourceProvider: SourceProvider[Offset, EventEnvelope[SubscriptionEvent]] =
      EventSourcedProvider.eventsByTag[SubscriptionEvent](
        system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = SubscriptionEntity.LeaseTag
      )

    JdbcProjection.atLeastOnce(
      projectionId = ProjectionId(ProjectionName, SubscriptionEntity.LeaseTag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new HermesJdbcSession(dbConfig),
      handler = () => new Handler(topics, deadLetterTopic)(using system.executionContext)
    )(system)

  private final class Handler(topics: TopicService, deadLetterTopic: Option[TopicId])(using ExecutionContext)
      extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      subscriptionIdOf(envelope.persistenceId).foreach { subscriptionId =>
        val outcome = Await.result(DeadLetterRouter.route(topics, deadLetterTopic, subscriptionId, envelope.event), 10.seconds)
        outcome match
          case DeadLetterOutcome.Published(topic, messageId) =>
            log.info("Dead-lettered message {} from {} republished to topic {}", messageId.value, subscriptionId.value, topic.value)
          case DeadLetterOutcome.Dropped(sid, messageId) =>
            log.warn("Message {} from {} exhausted delivery attempts but no dead-letter topic is configured; dropping", messageId.value, sid.value)
          case DeadLetterOutcome.Ignored => ()
      }
