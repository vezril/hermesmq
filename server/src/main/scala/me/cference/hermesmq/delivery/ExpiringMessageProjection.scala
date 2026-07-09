package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
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

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*

/** Pekko Projection that maintains the durable expiring-message read model by
  * consuming subscription events: a delivered message with an `expireTime` is
  * tracked, and it is removed once acknowledged, dead-lettered, or expired. Runs
  * as a single cluster-wide instance; offsets resume after restart.
  */
object ExpiringMessageProjection:

  private val ProjectionName = "subscription-expiry-index"

  /** The per-event effect on the expiring-message read model. */
  def indexEvent(repository: ExpiringMessageRepository, subscriptionId: SubscriptionId, event: SubscriptionEvent)(using
      ExecutionContext
  ): Future[Unit] =
    event match
      case SubscriptionEvent.MessageDelivered(ackId, message) =>
        message.expireTime.fold(Future.unit)(t => repository.add(subscriptionId, ackId, t))
      case SubscriptionEvent.MessageAcknowledged(ackId)      => repository.removed(subscriptionId, ackId)
      case SubscriptionEvent.MessageDeadLettered(ackId, _, _) => repository.removed(subscriptionId, ackId)
      case SubscriptionEvent.MessageExpired(ackId)           => repository.removed(subscriptionId, ackId)
      case _                                                 => Future.unit

  private def subscriptionIdOf(persistenceId: String): Option[SubscriptionId] =
    persistenceId.split('|') match
      case Array("Subscription", id) => SubscriptionId.from(id).toOption
      case _                         => None

  def apply(system: ActorSystem[?], dbConfig: DbConfig, repository: ExpiringMessageRepository) =
    val sourceProvider: SourceProvider[Offset, EventEnvelope[SubscriptionEvent]] =
      EventSourcedProvider.eventsByTag[SubscriptionEvent](
        system,
        readJournalPluginId = JdbcReadJournal.Identifier,
        tag = SubscriptionEntity.StatsTag
      )

    JdbcProjection.atLeastOnce(
      projectionId = ProjectionId(ProjectionName, SubscriptionEntity.StatsTag),
      sourceProvider = sourceProvider,
      sessionFactory = () => new HermesJdbcSession(dbConfig),
      handler = () => new Handler(repository)(using system.executionContext)
    )(system)

  private final class Handler(repository: ExpiringMessageRepository)(using ExecutionContext)
      extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      subscriptionIdOf(envelope.persistenceId).foreach { subscriptionId =>
        Await.result(indexEvent(repository, subscriptionId, envelope.event), 10.seconds)
      }
