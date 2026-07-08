package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.SubscriptionEvent
import me.cference.hermesmq.persistence.SubscriptionEntity
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.projection.ProjectionId
import org.apache.pekko.projection.eventsourced.EventEnvelope
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.jdbc.scaladsl.{JdbcHandler, JdbcProjection}
import org.apache.pekko.projection.scaladsl.SourceProvider

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Pekko Projection that maintains the durable topic→subscriptions read model by
  * consuming tagged `SubscriptionCreated` events. Runs as a single cluster-wide
  * instance; offsets are stored in PostgreSQL so it resumes after restart.
  */
object SubscriptionIndexProjection:

  private val ProjectionName = "subscription-index"

  /** The per-event effect: index `SubscriptionCreated`, ignore the rest. */
  def indexEvent(repository: TopicSubscriptionsRepository, event: SubscriptionEvent)(using ExecutionContext): Future[Unit] =
    event match
      case SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId) => repository.add(topicId, subscriptionId)
      case _                                                              => Future.unit

  def apply(system: ActorSystem[?], dbConfig: DbConfig, repository: TopicSubscriptionsRepository) =
    val sourceProvider: SourceProvider[Offset, EventEnvelope[SubscriptionEvent]] =
      EventSourcedProvider.eventsByTag[SubscriptionEvent](
        system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = SubscriptionEntity.CreatedTag
      )

    JdbcProjection.atLeastOnce(
      projectionId = ProjectionId(ProjectionName, SubscriptionEntity.CreatedTag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new HermesJdbcSession(dbConfig),
      handler = () => new Handler(repository)(using system.executionContext)
    )(system)

  private final class Handler(repository: TopicSubscriptionsRepository)(using ExecutionContext)
      extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      Await.result(indexEvent(repository, envelope.event), 10.seconds)
