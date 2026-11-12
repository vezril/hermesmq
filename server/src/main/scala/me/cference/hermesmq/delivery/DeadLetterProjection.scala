package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.SubscriptionEvent
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import me.cference.hermesmq.persistence.SubscriptionEntity
import me.cference.hermesmq.persistence.TopicService
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.projection.ProjectionId
import org.apache.pekko.projection.eventsourced.EventEnvelope
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.jdbc.scaladsl.JdbcHandler
import org.apache.pekko.projection.jdbc.scaladsl.JdbcProjection
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.slf4j.LoggerFactory

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
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

  def apply(
      system: ActorSystem[?],
      dbConfig: DbConfig,
      topics: TopicService,
      subscriptions: TopicSubscriptionsRepository,
      deadLetterTopic: Option[TopicId]
  ) =
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
      handler = () => new Handler(topics, subscriptions, deadLetterTopic)(using system.executionContext)
    )(system)

  private final class Handler(
      topics: TopicService,
      subscriptions: TopicSubscriptionsRepository,
      deadLetterTopic: Option[TopicId]
  )(using ExecutionContext)
      extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      subscriptionIdOf(envelope.persistenceId).foreach { subscriptionId =>
        // The origin topic is recorded on the dead-letter envelope so a redrive
        // is self-contained. An unresolved topic must not block dead-lettering,
        // so this degrades to None rather than failing the envelope.
        val originTopic = Await.result(subscriptions.topicFor(subscriptionId), 10.seconds)
        val outcome =
          Await.result(DeadLetterRouter.route(topics, deadLetterTopic, subscriptionId, originTopic, envelope.event), 10.seconds)
        outcome match
          case DeadLetterOutcome.Published(topic, messageId) =>
            log.info("Dead-lettered message {} from {} republished to topic {}", messageId.value, subscriptionId.value, topic.value)
          case DeadLetterOutcome.Dropped(sid, messageId) =>
            log.warn("Message {} from {} exhausted delivery attempts but no dead-letter topic is configured; dropping", messageId.value, sid.value)
          case DeadLetterOutcome.Ignored => ()
      }
