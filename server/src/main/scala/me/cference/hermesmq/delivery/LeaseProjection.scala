package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.SubscriptionEvent
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.persistence.SubscriptionEntity
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
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Pekko Projection that maintains the durable outstanding-lease read model by
  * consuming tagged lease-lifecycle events, so the redelivery sweeper can find
  * overdue leases without scanning the entities. Runs as a single cluster-wide
  * instance; offsets are stored in PostgreSQL so it resumes after restart.
  */
object LeaseProjection:

  private val ProjectionName = "subscription-lease-index"

  /** The per-event effect on the lease read model. Leasing/modification record a
    * deadline; ack, expiry (back to AVAILABLE) and dead-letter clear the lease.
    */
  def indexEvent(repository: OutstandingLeaseRepository, subscriptionId: SubscriptionId, event: SubscriptionEvent)(using
      ExecutionContext
  ): Future[Unit] =
    event match
      case SubscriptionEvent.MessageLeased(ackIds, deadline) =>
        Future.traverse(ackIds)(repository.leased(subscriptionId, _, deadline)).map(_ => ())
      case SubscriptionEvent.AckDeadlineModified(ackId, deadline) => repository.leased(subscriptionId, ackId, deadline)
      case SubscriptionEvent.AckDeadlineExpired(ackId, _)         => repository.cleared(subscriptionId, ackId)
      case SubscriptionEvent.MessageAcknowledged(ackId)          => repository.cleared(subscriptionId, ackId)
      case SubscriptionEvent.MessageDeadLettered(ackId, _, _)    => repository.cleared(subscriptionId, ackId)
      case SubscriptionEvent.MessageExpired(ackId)               => repository.cleared(subscriptionId, ackId)
      case _                                                      => Future.unit

  /** Extract the subscription id from a `Subscription|<id>` persistence id. */
  private def subscriptionIdOf(persistenceId: String): Option[SubscriptionId] =
    persistenceId.split('|') match
      case Array("Subscription", id) => SubscriptionId.from(id).toOption
      case _                         => None

  def apply(system: ActorSystem[?], dbConfig: DbConfig, repository: OutstandingLeaseRepository) =
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
      handler = () => new Handler(repository)(using system.executionContext)
    )(system)

  private final class Handler(repository: OutstandingLeaseRepository)(using ExecutionContext)
      extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      subscriptionIdOf(envelope.persistenceId).foreach { subscriptionId =>
        Await.result(indexEvent(repository, subscriptionId, envelope.event), 10.seconds)
      }
