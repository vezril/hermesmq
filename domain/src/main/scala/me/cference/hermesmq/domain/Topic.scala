package me.cference.hermesmq.domain

import java.time.Instant
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

/** Commands accepted by the Topic aggregate's write side. */
enum TopicCommand:
  case CreateTopic(topicId: TopicId, labels: Map[String, String] = Map.empty)
  case Publish(message: Message)
  case DeleteTopic
  case UpdateTopic(labels: Map[String, String])

/** Events emitted by the Topic aggregate. */
enum TopicEvent:
  case TopicCreated(topicId: TopicId, labels: Map[String, String] = Map.empty)
  case MessagePublished(message: Message)
  case TopicDeleted(topicId: TopicId)
  case TopicLabelsUpdated(topicId: TopicId, labels: Map[String, String])

/** A prior publish remembered for deduplication: the id assigned to the first
  * message that used a given idempotency key, and when it was published.
  */
final case class SeenPublish(messageId: MessageId, publishTime: Instant)

/** In-memory Topic state. A topic id, once used, stays recorded; `deleted`
  * marks a soft delete. `active` (created and not deleted) is the state in which
  * publish/update/delete are accepted. Published messages are not retained here.
  * `seen` maps a recently-used idempotency key to its first publish, kept only
  * while dedup is enabled and pruned to roughly one window of traffic.
  */
final case class TopicState(
    topicId: Option[TopicId],
    labels: Map[String, String],
    deleted: Boolean,
    seen: Map[String, SeenPublish] = Map.empty
):
  /** The id has been used at some point (active or deleted). */
  def created: Boolean = topicId.isDefined

  /** Created and not deleted — the operable state. */
  def active: Boolean = created && !deleted

  /** Retained for callers/tests: a topic "exists" when it is active. */
  def exists: Boolean = active

/** Pure Topic aggregate: the write-side decision and state-evolution functions.
  * Both are total — `decide` returns a `Left(Rejection)` rather than throwing,
  * and `evolve` is defined for every event. `dedupWindow` (default off) enables
  * idempotency-key deduplication: within the window a repeated key is collapsed
  * to the original publish. Deduplication is off when the window is `<= 0`.
  */
object Topic:

  val empty: TopicState = TopicState(topicId = None, labels = Map.empty, deleted = false)

  def decide(
      state: TopicState,
      command: TopicCommand,
      dedupWindow: FiniteDuration = Duration.Zero
  ): Either[Rejection, List[TopicEvent]] =
    command match
      case TopicCommand.CreateTopic(topicId, labels) =>
        // Once an id is used it cannot be re-created, even after deletion.
        if state.created then Left(Rejection.TopicAlreadyExists)
        else Right(List(TopicEvent.TopicCreated(topicId, labels)))

      case TopicCommand.Publish(message) =>
        // A duplicate within the window persists nothing (the reply still echoes
        // the original id — see TopicEntity); otherwise publish as usual.
        onActive(state) { _ =>
          if duplicateOf(state, message, dedupWindow).isDefined then Nil
          else List(TopicEvent.MessagePublished(message))
        }

      case TopicCommand.DeleteTopic =>
        onActive(state)(id => List(TopicEvent.TopicDeleted(id)))

      case TopicCommand.UpdateTopic(labels) =>
        onActive(state)(id => List(TopicEvent.TopicLabelsUpdated(id, labels)))

  def evolve(
      state: TopicState,
      event: TopicEvent,
      dedupWindow: FiniteDuration = Duration.Zero
  ): TopicState =
    event match
      case TopicEvent.TopicCreated(topicId, labels) =>
        state.copy(topicId = Some(topicId), labels = labels, deleted = false)
      case TopicEvent.MessagePublished(message) => recordSeen(state, message, dedupWindow)
      case TopicEvent.TopicDeleted(_)           => state.copy(deleted = true)
      case TopicEvent.TopicLabelsUpdated(_, labels) => state.copy(labels = labels)

  /** The original message id for `message`'s idempotency key when that key was
    * already seen within `window` (a duplicate), or `None` when it is a fresh
    * publish, has no key, or dedup is disabled (`window <= 0`).
    */
  def duplicateOf(state: TopicState, message: Message, window: FiniteDuration): Option[MessageId] =
    if window <= Duration.Zero then None
    else
      message.idempotencyKey.flatMap { key =>
        state.seen.get(key).filter(sp => withinWindow(sp.publishTime, message.publishTime, window)).map(_.messageId)
      }

  /** Record a keyed publish in `seen` and prune entries older than one window
    * relative to this message's publish time. Deterministic in the event (no
    * wall-clock). A no-op when dedup is disabled or the message carries no key.
    */
  private def recordSeen(state: TopicState, message: Message, window: FiniteDuration): TopicState =
    if window <= Duration.Zero then state
    else
      message.idempotencyKey match
        case None => state
        case Some(key) =>
          val kept = state.seen.filter { case (_, sp) => withinWindow(sp.publishTime, message.publishTime, window) }
          state.copy(seen = kept.updated(key, SeenPublish(message.id, message.publishTime)))

  /** True when `seenAt` is no older than `window` relative to `now` — i.e.
    * `seenAt >= now - window`. Boundary is inclusive.
    */
  private def withinWindow(seenAt: Instant, now: Instant, window: FiniteDuration): Boolean =
    !seenAt.isBefore(now.minusNanos(window.toNanos))

  /** Emit events derived from the active topic's id, or reject as not-found. */
  private def onActive(state: TopicState)(
      events: TopicId => List[TopicEvent]
  ): Either[Rejection, List[TopicEvent]] =
    state.topicId.filter(_ => state.active) match
      case Some(id) => Right(events(id))
      case None     => Left(Rejection.TopicNotFound)
