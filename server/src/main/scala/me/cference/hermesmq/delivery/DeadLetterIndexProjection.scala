package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.SubscriptionEvent
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
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

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Pekko Projection that maintains the durable dead-letter read model by
  * consuming `MessageDeadLettered` events. Runs as a single cluster-wide
  * instance; offsets are stored in PostgreSQL so it resumes after restart.
  *
  * Deliberately separate from [[DeadLetterProjection]] even though both read the
  * same tag: that one republishes to the dead-letter topic and can fail
  * persistently (a deleted dead-letter topic, an unreachable aggregate), and an
  * at-least-once projection retries the whole envelope. Sharing one projection
  * would let a republish failure block recording the evidence indefinitely --
  * exactly when an operator most needs to see it. Separate offsets keep the two
  * effects independent.
  *
  * Because the event is journaled whether or not a dead-letter topic is
  * configured, this records dead-letters even when the runtime drops them.
  */
object DeadLetterIndexProjection:

  private val ProjectionName = "dead-letter-index"

  /** The per-event effect: record a dead-letter, ignore everything else.
    *
    * `deadLetteredAt` is the journal envelope's timestamp rather than the wall
    * clock, so reprocessing an envelope yields the same row it did the first
    * time instead of drifting forward on every replay.
    */
  def indexEvent(
      repository: DeadLetterRepository,
      subscriptionId: SubscriptionId,
      originTopic: Option[TopicId],
      deadLetteredAt: Instant,
      event: SubscriptionEvent
  )(using ExecutionContext): Future[Unit] =
    event match
      case SubscriptionEvent.MessageDeadLettered(_, message, attempt) =>
        repository.record(
          DeadLetterEntry(
            subscriptionId = subscriptionId,
            originalMessageId = message.id,
            originTopic = originTopic,
            payload = message.payload,
            attributes = message.attributes,
            deliveryAttempts = attempt,
            deadLetteredAt = deadLetteredAt,
            redrivenAt = None
          )
        )
      case _ => Future.unit

  private def subscriptionIdOf(persistenceId: String): Option[SubscriptionId] =
    persistenceId.split('|') match
      case Array("Subscription", id) => SubscriptionId.from(id).toOption
      case _                         => None

  def apply(
      system: ActorSystem[?],
      dbConfig: DbConfig,
      repository: DeadLetterRepository,
      subscriptions: TopicSubscriptionsRepository
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
      handler = () => new Handler(repository, subscriptions)(using system.executionContext)
    )(system)

  private final class Handler(repository: DeadLetterRepository, subscriptions: TopicSubscriptionsRepository)(using
      ExecutionContext
  ) extends JdbcHandler[EventEnvelope[SubscriptionEvent], HermesJdbcSession]:

    override def process(session: HermesJdbcSession, envelope: EventEnvelope[SubscriptionEvent]): Unit =
      subscriptionIdOf(envelope.persistenceId).foreach { subscriptionId =>
        val originTopic = Await.result(subscriptions.topicFor(subscriptionId), 10.seconds)
        val _ = Await.result(
          indexEvent(
            repository,
            subscriptionId,
            originTopic,
            Instant.ofEpochMilli(envelope.timestamp),
            envelope.event
          ),
          10.seconds
        )
      }
