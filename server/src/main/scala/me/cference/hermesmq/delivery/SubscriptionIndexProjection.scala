package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.Message
import me.cference.hermesmq.domain.SubscriptionEvent
import me.cference.hermesmq.domain.TopicEvent
import me.cference.hermesmq.persistence.SubscriptionEntity
import me.cference.hermesmq.persistence.SubscriptionService
import me.cference.hermesmq.persistence.TopicEntity
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.jdbc.query.scaladsl.JdbcReadJournal
import org.apache.pekko.persistence.query.Offset
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.CurrentEventsByPersistenceIdQuery
import org.apache.pekko.projection.ProjectionId
import org.apache.pekko.projection.eventsourced.EventEnvelope
import org.apache.pekko.projection.eventsourced.scaladsl.EventSourcedProvider
import org.apache.pekko.projection.jdbc.scaladsl.JdbcHandler
import org.apache.pekko.projection.jdbc.scaladsl.JdbcProjection
import org.apache.pekko.projection.scaladsl.SourceProvider
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration

/** Pekko Projection that maintains the durable topic→subscriptions read model by
  * consuming tagged `SubscriptionCreated` events. Runs as a single cluster-wide
  * instance; offsets are stored in PostgreSQL so it resumes after restart.
  */
/** What back-delivery needs, kept out of the projection's own signature so the
  * index behaviour stays testable without it. `None` disables back-delivery
  * entirely -- the projection then does exactly what it did before.
  *
  * The timeout is generous: a backfill is proportional to a topic's retained
  * history, which can legitimately take a while, and failing it halfway is worse
  * than waiting.
  */
final case class BackfillDeps(
    history: SubscriptionBackfill.HistoryReader,
    subscriptions: SubscriptionService,
    markers: BackfillMarkerRepository,
    timeout: FiniteDuration = 5.minutes
)

object SubscriptionIndexProjection:

  private val ProjectionName = "subscription-index"

  /** The per-event effect: index a creation, un-index a deletion, ignore the
    * rest. Both ends matter -- an index that only ever grows keeps handing
    * messages to a subscription that can no longer acknowledge them.
    */
  def indexEvent(repository: TopicSubscriptionsRepository, event: SubscriptionEvent)(using ExecutionContext): Future[Unit] =
    event match
      case SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId, _, _) => repository.add(topicId, subscriptionId)
      case SubscriptionEvent.SubscriptionDeleted(subscriptionId, topicId) => repository.remove(topicId, subscriptionId)
      case _                                                              => Future.unit

  /** Reads a topic's retained `MessagePublished` payloads, oldest first, straight
    * from the topic's own journal. `current*` rather than the live stream: this is
    * a bounded catch-up with a defined end, not a subscription.
    */
  def historyReader(system: ActorSystem[?]): SubscriptionBackfill.HistoryReader =
    val readJournal = PersistenceQuery(system).readJournalFor[CurrentEventsByPersistenceIdQuery](JdbcReadJournal.Identifier)
    topicId =>
      readJournal
        .currentEventsByPersistenceId(TopicEntity.persistenceId(topicId).id, 0L, Long.MaxValue)
        .map(_.event)
        .collect { case TopicEvent.MessagePublished(message) => message }
        .runWith(Sink.seq[Message])(using Materializer(system))
        .map(_.toList)(using system.executionContext)

  def apply(
      system: ActorSystem[?],
      dbConfig: DbConfig,
      repository: TopicSubscriptionsRepository,
      backfill: Option[BackfillDeps] = None
  ) =
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
      handler = () => new Handler(repository, backfill)(using system.executionContext)
    )(system)

  private final class Handler(repository: TopicSubscriptionsRepository, backfill: Option[BackfillDeps])(using
      ExecutionContext
  ) extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      // Index first: delivery fan-out depends on it, and a back-delivery that
      // failed must not leave the subscription unroutable for live messages too.
      val _ = Await.result(indexEvent(repository, envelope.event), 10.seconds)
      backfill.foreach { deps =>
        val _ = Await.result(
          SubscriptionBackfill.backfill(envelope.event, deps.history, deps.subscriptions, deps.markers, Instant.now()),
          deps.timeout
        )
      }
