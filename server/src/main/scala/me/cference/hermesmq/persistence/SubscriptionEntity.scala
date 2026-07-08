package me.cference.hermesmq.persistence

import me.cference.hermesmq.config.RetentionConfig
import me.cference.hermesmq.domain.{Subscription, SubscriptionCommand, SubscriptionEvent, SubscriptionId, SubscriptionState}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** The persistent Subscription aggregate — a thin `EventSourcedBehavior` over
  * the pure [[Subscription]] `decide`/`evolve`, persisting before replying, plus
  * a non-persisting pull of outstanding messages.
  */
object SubscriptionEntity:

  /** Tag applied to `SubscriptionCreated` events so the subscription-index
    * projection can consume them via `eventsByTag`.
    */
  val CreatedTag = "subscription-created"

  /** Tag applied to lease-lifecycle events (lease, ack, modify, expiry,
    * dead-letter) so the lease read-model and dead-letter projections can
    * consume them via `eventsByTag`. `MessageDelivered` is untagged — an
    * available message has no lease to track.
    */
  val LeaseTag = "subscription-lease"

  def persistenceId(subscriptionId: SubscriptionId): PersistenceId =
    PersistenceId.ofUniqueId(s"Subscription|${subscriptionId.value}")

  def apply(
      subscriptionId: SubscriptionId,
      retention: RetentionConfig = RetentionConfig.Default
  ): Behavior[SubscriptionEntityCommand] =
    EventSourcedBehavior[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState](
      persistenceId = persistenceId(subscriptionId),
      emptyState = Subscription.empty,
      commandHandler = (state, command) =>
        command match
          case SubscriptionEntityCommand.Submit(cmd, replyTo) =>
            EntityEffects.persistOrReject(Subscription.decide(state, cmd), replyTo)
          case SubscriptionEntityCommand.Pull(max, ackDeadline, now, replyTo) =>
            lease(state, max, ackDeadline, now, replyTo),
      eventHandler = Subscription.evolve
    ).withTagger {
      case _: SubscriptionEvent.SubscriptionCreated => Set(CreatedTag)
      case _: SubscriptionEvent.MessageDelivered    => Set.empty
      case _                                        => Set(LeaseTag)
    }.withRetention(
      RetentionCriteria
        .snapshotEvery(numberOfEvents = retention.snapshotEveryEvents, keepNSnapshots = retention.keepNSnapshots)
        .withDeleteEventsOnSnapshot
    )

  /** Lease up to `max` AVAILABLE messages: persist `MessageLeased`, then reply
    * with those messages. `None` when the subscription does not exist.
    */
  private def lease(
      state: SubscriptionState,
      max: Int,
      ackDeadline: FiniteDuration,
      now: Instant,
      replyTo: ActorRef[Option[List[PulledMessage]]]
  ): Effect[SubscriptionEvent, SubscriptionState] =
    if !state.exists then Effect.none.thenReply(replyTo)(_ => None)
    else
      Subscription.decide(state, SubscriptionCommand.Lease(max, ackDeadline, now)) match
        case Right(events @ List(SubscriptionEvent.MessageLeased(ackIds, _))) =>
          val leased = ackIds.flatMap(id => state.outstanding.get(id).map(o => PulledMessage(id, o.message)))
          Effect.persist[SubscriptionEvent, SubscriptionState](events).thenReply(replyTo)(_ => Some(leased))
        case _ =>
          Effect.none.thenReply(replyTo)(_ => Some(Nil))
