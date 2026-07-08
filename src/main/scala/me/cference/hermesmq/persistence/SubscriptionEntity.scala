package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.{Subscription, SubscriptionEvent, SubscriptionId, SubscriptionState}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior

/** The persistent Subscription aggregate — a thin `EventSourcedBehavior` over
  * the pure [[Subscription]] `decide`/`evolve`, persisting before replying.
  */
object SubscriptionEntity:

  def persistenceId(subscriptionId: SubscriptionId): PersistenceId =
    PersistenceId.ofUniqueId(s"Subscription|${subscriptionId.value}")

  def apply(subscriptionId: SubscriptionId): Behavior[SubscriptionEntityCommand] =
    EventSourcedBehavior[SubscriptionEntityCommand, SubscriptionEvent, SubscriptionState](
      persistenceId = persistenceId(subscriptionId),
      emptyState = Subscription.empty,
      commandHandler = (state, envelope) =>
        EntityEffects.persistOrReject(Subscription.decide(state, envelope.command), envelope.replyTo),
      eventHandler = Subscription.evolve
    )
