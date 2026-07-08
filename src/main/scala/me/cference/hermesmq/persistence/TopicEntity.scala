package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.{Topic, TopicEvent, TopicId, TopicState}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.EventSourcedBehavior

/** The persistent Topic aggregate. A thin `EventSourcedBehavior` that delegates
  * all business logic to the pure [[Topic]] `decide`/`evolve`, persisting events
  * before replying so an accepted command is never acknowledged before it is
  * durably journaled.
  */
object TopicEntity:

  /** Persistence id scheme for a topic. */
  def persistenceId(topicId: TopicId): PersistenceId =
    PersistenceId.ofUniqueId(s"Topic|${topicId.value}")

  def apply(topicId: TopicId): Behavior[TopicEntityCommand] =
    EventSourcedBehavior[TopicEntityCommand, TopicEvent, TopicState](
      persistenceId = persistenceId(topicId),
      emptyState = Topic.empty,
      commandHandler = (state, envelope) =>
        EntityEffects.persistOrReject(Topic.decide(state, envelope.command), envelope.replyTo),
      eventHandler = Topic.evolve
    )
