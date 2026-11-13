package me.cference.hermesmq.domain

import java.time.Instant
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

/** Commands accepted by the Topic aggregate's write side. */
enum TopicCommand:
  case CreateTopic(topicId: TopicId, labels: Map[String, String] = Map.empty, ttl: Option[FiniteDuration] = None)
  case Publish(message: Message)
  case DeleteTopic
  /** `ttl = None` leaves the topic's TTL untouched. Clearing an existing TTL is
    * deliberately not expressible here -- it would need a three-state field
    * (absent / clear / set), which is more surface than this change needs.
    */
  case UpdateTopic(labels: Map[String, String], ttl: Option[FiniteDuration] = None)

/** Events emitted by the Topic aggregate. */
enum TopicEvent:
  case TopicCreated(topicId: TopicId, labels: Map[String, String] = Map.empty, ttl: Option[FiniteDuration] = None)
  case MessagePublished(message: Message)
  case TopicDeleted(topicId: TopicId)
  case TopicLabelsUpdated(topicId: TopicId, labels: Map[String, String])
  /** Separate from [[TopicLabelsUpdated]] rather than a field on it: an event
    * named "labels updated" carrying a TTL would mislead every future reader of
    * the journal.
    */
  case TopicTtlUpdated(topicId: TopicId, ttl: Option[FiniteDuration])

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
    seen: Map[String, SeenPublish] = Map.empty,
    ttl: Option[FiniteDuration] = None
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

  /** Bring a message's expiry forward to the topic's TTL when that is earlier.
    *
    * Shortest wins: a topic declaring its data stale after N cannot be overridden
    * upward by a publisher, and a publisher asking for a shorter life than the
    * topic's keeps it.
    */
  def applyTopicTtl(message: Message, topicTtl: Option[FiniteDuration]): Message =
    topicTtl.filter(_ > Duration.Zero) match
      case None => message
      case Some(ttl) => message.withExpiryNoLaterThan(message.publishTime.plusNanos(ttl.toNanos))

  val empty: TopicState = TopicState(topicId = None, labels = Map.empty, deleted = false)

  def decide(
      state: TopicState,
      command: TopicCommand,
      dedupWindow: FiniteDuration = Duration.Zero
  ): Either[Rejection, List[TopicEvent]] =
    command match
      case TopicCommand.CreateTopic(topicId, labels, ttl) =>
        // Once an id is used it cannot be re-created, even after deletion.
        if state.created then Left(Rejection.TopicAlreadyExists)
        else Right(List(TopicEvent.TopicCreated(topicId, labels, ttl)))

      case TopicCommand.Publish(message) =>
        // A duplicate within the window persists nothing (the reply still echoes
        // the original id — see TopicEntity); otherwise publish as usual.
        onActive(state) { _ =>
          if duplicateOf(state, message, dedupWindow).isDefined then Nil
          else
            // The topic's TTL is applied here rather than at the API edge: the
            // aggregate is the single writer that knows its own TTL, so the
            // alternative would be an extra round-trip on the publish path.
            // Shortest wins -- a topic TTL can bring an expiry forward, never
            // push it out past what the publisher asked for.
            List(TopicEvent.MessagePublished(Topic.applyTopicTtl(message, state.ttl)))
        }

      case TopicCommand.DeleteTopic =>
        onActive(state)(id => List(TopicEvent.TopicDeleted(id)))

      case TopicCommand.UpdateTopic(labels, ttl) =>
        onActive(state) { id =>
          TopicEvent.TopicLabelsUpdated(id, labels) :: ttl.map(t => TopicEvent.TopicTtlUpdated(id, Some(t))).toList
        }

  def evolve(
      state: TopicState,
      event: TopicEvent,
      dedupWindow: FiniteDuration = Duration.Zero
  ): TopicState =
    event match
      case TopicEvent.TopicCreated(topicId, labels, ttl) =>
        state.copy(topicId = Some(topicId), labels = labels, deleted = false, ttl = ttl)
      case TopicEvent.MessagePublished(message) => recordSeen(state, message, dedupWindow)
      case TopicEvent.TopicDeleted(_)           => state.copy(deleted = true)
      case TopicEvent.TopicLabelsUpdated(_, labels) => state.copy(labels = labels)
      case TopicEvent.TopicTtlUpdated(_, ttl)       => state.copy(ttl = ttl)

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
