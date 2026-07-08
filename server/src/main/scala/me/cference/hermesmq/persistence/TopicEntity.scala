package me.cference.hermesmq.persistence

import me.cference.hermesmq.config.RetentionConfig
import me.cference.hermesmq.domain.{Topic, TopicEvent, TopicId, TopicState}
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}

/** The persistent Topic aggregate. A thin `EventSourcedBehavior` that delegates
  * all business logic to the pure [[Topic]] `decide`/`evolve`, persisting events
  * before replying so an accepted command is never acknowledged before it is
  * durably journaled.
  */
object TopicEntity:

  /** Tag applied to `MessagePublished` events so the delivery projection can
    * consume them via `eventsByTag`.
    */
  val MessageTag = "topic-message"

  /** Persistence id scheme for a topic. */
  def persistenceId(topicId: TopicId): PersistenceId =
    PersistenceId.ofUniqueId(s"Topic|${topicId.value}")

  def apply(topicId: TopicId, retention: RetentionConfig = RetentionConfig.Default): Behavior[TopicEntityCommand] =
    EventSourcedBehavior[TopicEntityCommand, TopicEvent, TopicState](
      persistenceId = persistenceId(topicId),
      emptyState = Topic.empty,
      commandHandler = (state, command) =>
        command match
          case TopicEntityCommand.Submit(cmd, replyTo) =>
            EntityEffects.persistOrReject(Topic.decide(state, cmd), replyTo)
          case TopicEntityCommand.Query(replyTo) =>
            Effect.none.thenReply(replyTo)(_ => snapshot(state)),
      eventHandler = Topic.evolve
    ).withTagger {
      case _: TopicEvent.MessagePublished => Set(MessageTag)
      case _                              => Set.empty
    }.withRetention(
      RetentionCriteria
        .snapshotEvery(numberOfEvents = retention.snapshotEveryEvents, keepNSnapshots = retention.keepNSnapshots)
        .withDeleteEventsOnSnapshot
    )

  /** A read view of an active topic; `None` for a non-existent or deleted one. */
  private def snapshot(state: TopicState): Option[TopicSnapshot] =
    state.topicId.filter(_ => state.active).map(id => TopicSnapshot(id, state.labels))
