package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.{Subscription, SubscriptionEvent, SubscriptionId, SubscriptionState}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

/** The persistent Subscription aggregate — a thin `EventSourcedBehavior` over
  * the pure [[Subscription]] `decide`/`evolve`, persisting before replying, plus
  * a non-persisting pull of outstanding messages.
  */
object SubscriptionEntity:

  /** Tag applied to `SubscriptionCreated` events so the subscription-index
    * projection can consume them via `eventsByTag`.
    */
  val CreatedTag = "subscription-created"

  def persistenceId(subscriptionId: SubscriptionId): PersistenceId =
    PersistenceId.ofUniqueId(s"Subscription|${subscriptionId.value}")

  def apply(subscriptionId: SubscriptionId): Behavior[SubscriptionEntityCommand] =
    EventSourcedBehavior[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState](
      persistenceId = persistenceId(subscriptionId),
      emptyState = Subscription.empty,
      commandHandler = (state, command) =>
        command match
          case SubscriptionEntityCommand.Submit(cmd, replyTo) =>
            EntityEffects.persistOrReject(Subscription.decide(state, cmd), replyTo)
          case SubscriptionEntityCommand.Pull(max, replyTo) =>
            Effect.none.thenReply(replyTo)(_ => pull(state, max)),
      eventHandler = Subscription.evolve
    ).withTagger {
      case _: SubscriptionEvent.SubscriptionCreated => Set(CreatedTag)
      case _                                        => Set.empty
    }

  /** `None` if the subscription does not exist; otherwise up to `max`
    * outstanding messages, each with its ack id.
    */
  private def pull(state: SubscriptionState, max: Int): Option[List[PulledMessage]] =
    if !state.exists then None
    else
      Some(
        state.outstanding.iterator
          .take(math.max(0, max))
          .map((ackId, outstanding) => PulledMessage(ackId, outstanding.message))
          .toList
      )
