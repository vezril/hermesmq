package me.cference.hermesmq.domain

import java.time.Instant
import scala.concurrent.duration.FiniteDuration

/** Commands accepted by the Subscription aggregate's write side. */
enum SubscriptionCommand:
  case CreateSubscription(subscriptionId: SubscriptionId, topicId: TopicId)
  case RecordDelivery(ackId: AckId, message: Message)
  case Lease(max: Int, ackDeadline: FiniteDuration, now: Instant)
  case Acknowledge(ackId: AckId)
  case ModifyAckDeadline(ackId: AckId, ackDeadline: FiniteDuration, now: Instant)
  case ExpireAckDeadline(ackId: AckId, now: Instant, maxAttempts: Int)
  case ExpireMessage(ackId: AckId, now: Instant)
  case DeleteSubscription

/** Events emitted by the Subscription aggregate. */
enum SubscriptionEvent:
  case SubscriptionCreated(subscriptionId: SubscriptionId, topicId: TopicId)
  case MessageDelivered(ackId: AckId, message: Message)
  case MessageLeased(ackIds: List[AckId], deadline: Instant)
  case MessageAcknowledged(ackId: AckId)
  case AckDeadlineModified(ackId: AckId, deadline: Instant)
  case AckDeadlineExpired(ackId: AckId, attempt: Int)
  case MessageDeadLettered(ackId: AckId, message: Message, attempt: Int)
  case MessageExpired(ackId: AckId)
  /** Carries the topic as well as the id: the routing index is keyed by topic,
    * and a consumer of this event should not have to go and ask what it was.
    */
  case SubscriptionDeleted(subscriptionId: SubscriptionId, topicId: TopicId)

/** The lease state of an outstanding message: AVAILABLE (pullable) or LEASED
  * with an ack deadline (invisible until the deadline passes).
  */
enum LeaseState:
  case Available
  case Leased(deadline: Instant)

/** A delivered-but-unacknowledged message: the payload, its lease state, and the
  * number of delivery attempts that have expired (folded from events).
  */
final case class Outstanding(message: Message, lease: LeaseState, attempts: Int):
  def available: Boolean = lease == LeaseState.Available

final case class SubscriptionState(
    subscriptionId: Option[SubscriptionId],
    topicId: Option[TopicId],
    outstanding: Map[AckId, Outstanding],
    deleted: Boolean = false
):
  /** The id has been used at some point, whether or not it was later deleted.
    * Mirrors TopicState: an id is never reusable, because the journal for it
    * still exists and recreating would resurrect the old outstanding messages.
    */
  def created: Boolean = subscriptionId.isDefined

  /** Created and not deleted — the operable state. Everything that acts on a
    * subscription checks this, so a deleted one behaves as absent rather than
    * as an empty one still accepting deliveries.
    */
  def exists: Boolean = created && !deleted

  /** Outstanding messages that are AVAILABLE (leasable/pullable). */
  def availableMessages: List[(AckId, Outstanding)] =
    outstanding.iterator.filter(_._2.available).toList

/** Pure Subscription aggregate. `decide` and `evolve` are total: every command
  * yields either events or a `Rejection`, and every event has a defined fold.
  * Determinism: `Lease`/`ModifyAckDeadline`/`ExpireAckDeadline` carry `now` so
  * the deadline logic is reproducible (the actor supplies the clock).
  */
object Subscription:

  val empty: SubscriptionState =
    SubscriptionState(subscriptionId = None, topicId = None, outstanding = Map.empty)

  def decide(state: SubscriptionState, command: SubscriptionCommand): Either[Rejection, List[SubscriptionEvent]] =
    command match
      case SubscriptionCommand.CreateSubscription(subscriptionId, topicId) =>
        // `created`, not `exists`: once an id has been used it cannot be taken
        // again, even after deletion, exactly as for topics.
        if state.created then Left(Rejection.SubscriptionAlreadyExists)
        else Right(List(SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId)))

      case SubscriptionCommand.RecordDelivery(ackId, message) =>
        if !state.exists then Left(Rejection.SubscriptionNotFound)
        else if state.outstanding.contains(ackId) then Right(Nil) // idempotent replay
        else Right(List(SubscriptionEvent.MessageDelivered(ackId, message)))

      case SubscriptionCommand.Lease(max, ackDeadline, now) =>
        if !state.exists then Left(Rejection.SubscriptionNotFound)
        else
          // Expired messages are never leased/returned; the sweeper purges them.
          val toLease = state.availableMessages.filterNot(_._2.message.expired(now)).take(math.max(0, max)).map(_._1)
          if toLease.isEmpty then Right(Nil)
          else Right(List(SubscriptionEvent.MessageLeased(toLease, now.plusMillis(ackDeadline.toMillis))))

      case SubscriptionCommand.Acknowledge(ackId) =>
        ifOutstanding(state, ackId)(SubscriptionEvent.MessageAcknowledged(ackId))

      case SubscriptionCommand.ModifyAckDeadline(ackId, ackDeadline, now) =>
        // Extend the lease; a zero deadline nacks (returns to AVAILABLE now).
        if !state.outstanding.contains(ackId) then Left(Rejection.UnknownAckId(ackId))
        else if ackDeadline.toMillis <= 0 then Right(List(SubscriptionEvent.AckDeadlineExpired(ackId, 0)))
        else Right(List(SubscriptionEvent.AckDeadlineModified(ackId, now.plusMillis(ackDeadline.toMillis))))

      case SubscriptionCommand.ExpireAckDeadline(ackId, now, maxAttempts) =>
        state.outstanding.get(ackId) match
          case Some(Outstanding(message, LeaseState.Leased(deadline), attempts)) if !deadline.isAfter(now) =>
            val attempt = attempts + 1
            if maxAttempts > 0 && attempt >= maxAttempts then
              Right(List(SubscriptionEvent.MessageDeadLettered(ackId, message, attempt)))
            else Right(List(SubscriptionEvent.AckDeadlineExpired(ackId, attempt)))
          case _ => Right(Nil) // not leased, not overdue, or gone → no-op

      case SubscriptionCommand.ExpireMessage(ackId, now) =>
        // Wall-clock TTL expiry: drop an outstanding message past its expireTime,
        // in any lease state and regardless of attempt count.
        state.outstanding.get(ackId) match
          case Some(o) if o.message.expired(now) => Right(List(SubscriptionEvent.MessageExpired(ackId)))
          case _                                 => Right(Nil) // not outstanding, or not yet expired → no-op

      case SubscriptionCommand.DeleteSubscription =>
        // Deleting twice is a rejection rather than a silent success, so a
        // caller can tell "I removed it" from "it was already gone".
        if !state.exists then Left(Rejection.SubscriptionNotFound)
        else
          // Both are defined whenever `exists` holds; they are written together
          // by SubscriptionCreated and never cleared.
          (state.subscriptionId, state.topicId) match
            case (Some(id), Some(topic)) => Right(List(SubscriptionEvent.SubscriptionDeleted(id, topic)))
            case _                       => Left(Rejection.SubscriptionNotFound)

  def evolve(state: SubscriptionState, event: SubscriptionEvent): SubscriptionState =
    event match
      case SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId) =>
        state.copy(subscriptionId = Some(subscriptionId), topicId = Some(topicId))

      case SubscriptionEvent.MessageDelivered(ackId, message) =>
        state.copy(outstanding = state.outstanding.updated(ackId, Outstanding(message, LeaseState.Available, 0)))

      case SubscriptionEvent.MessageLeased(ackIds, deadline) =>
        val updated = ackIds.foldLeft(state.outstanding) { (m, id) =>
          m.get(id).fold(m)(o => m.updated(id, o.copy(lease = LeaseState.Leased(deadline))))
        }
        state.copy(outstanding = updated)

      case SubscriptionEvent.MessageAcknowledged(ackId) =>
        state.copy(outstanding = state.outstanding.removed(ackId))

      case SubscriptionEvent.AckDeadlineModified(ackId, deadline) =>
        updateLease(state, ackId, LeaseState.Leased(deadline))

      case SubscriptionEvent.AckDeadlineExpired(ackId, attempt) =>
        state.outstanding.get(ackId) match
          case Some(o) => state.copy(outstanding = state.outstanding.updated(ackId, o.copy(lease = LeaseState.Available, attempts = attempt)))
          case None    => state

      case SubscriptionEvent.MessageDeadLettered(ackId, _, _) =>
        state.copy(outstanding = state.outstanding.removed(ackId))

      case SubscriptionEvent.MessageExpired(ackId) =>
        state.copy(outstanding = state.outstanding.removed(ackId))

      case SubscriptionEvent.SubscriptionDeleted(_, _) =>
        // Outstanding messages go with it. They were owned by this subscription
        // alone, and keeping them would leave a backlog nothing can ever ack.
        state.copy(deleted = true, outstanding = Map.empty)

  private def updateLease(state: SubscriptionState, ackId: AckId, lease: LeaseState): SubscriptionState =
    state.outstanding.get(ackId).fold(state)(o => state.copy(outstanding = state.outstanding.updated(ackId, o.copy(lease = lease))))

  /** Emit `event` when `ackId` is outstanding, otherwise reject as unknown. */
  private def ifOutstanding(state: SubscriptionState, ackId: AckId)(
      event: => SubscriptionEvent
  ): Either[Rejection, List[SubscriptionEvent]] =
    if state.outstanding.contains(ackId) then Right(List(event))
    else Left(Rejection.UnknownAckId(ackId))
