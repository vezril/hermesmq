package me.cference.hermesmq.domain

/** Commands accepted by the Subscription aggregate's write side. */
enum SubscriptionCommand:
  case CreateSubscription(subscriptionId: SubscriptionId, topicId: TopicId)
  case RecordDelivery(ackId: AckId, messageId: MessageId, deadline: AckDeadline)
  case Acknowledge(ackId: AckId)
  case ModifyAckDeadline(ackId: AckId, deadline: AckDeadline)

/** Events emitted by the Subscription aggregate. */
enum SubscriptionEvent:
  case SubscriptionCreated(subscriptionId: SubscriptionId, topicId: TopicId)
  case MessageDelivered(ackId: AckId, messageId: MessageId, deadline: AckDeadline)
  case MessageAcknowledged(ackId: AckId)
  case AckDeadlineModified(ackId: AckId, deadline: AckDeadline)

/** A delivered-but-unacknowledged message tracked by a subscription. */
final case class Outstanding(messageId: MessageId, deadline: AckDeadline)

/** In-memory Subscription state: whether it exists, the topic it is bound to,
  * and the set of outstanding (delivered, unacknowledged) messages by ack id.
  */
final case class SubscriptionState(
    subscriptionId: Option[SubscriptionId],
    topicId: Option[TopicId],
    outstanding: Map[AckId, Outstanding]
):
  def exists: Boolean = subscriptionId.isDefined

/** Pure Subscription aggregate. `decide` and `evolve` are total: every command
  * yields either events or a `Rejection`, and every event has a defined fold.
  */
object Subscription:

  val empty: SubscriptionState =
    SubscriptionState(subscriptionId = None, topicId = None, outstanding = Map.empty)

  def decide(state: SubscriptionState, command: SubscriptionCommand): Either[Rejection, List[SubscriptionEvent]] =
    command match
      case SubscriptionCommand.CreateSubscription(subscriptionId, topicId) =>
        if state.exists then Left(Rejection.SubscriptionAlreadyExists)
        else Right(List(SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId)))

      case SubscriptionCommand.RecordDelivery(ackId, messageId, deadline) =>
        if !state.exists then Left(Rejection.SubscriptionNotFound)
        else Right(List(SubscriptionEvent.MessageDelivered(ackId, messageId, deadline)))

      case SubscriptionCommand.Acknowledge(ackId) =>
        ifOutstanding(state, ackId)(SubscriptionEvent.MessageAcknowledged(ackId))

      case SubscriptionCommand.ModifyAckDeadline(ackId, deadline) =>
        ifOutstanding(state, ackId)(SubscriptionEvent.AckDeadlineModified(ackId, deadline))

  def evolve(state: SubscriptionState, event: SubscriptionEvent): SubscriptionState =
    event match
      case SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId) =>
        state.copy(subscriptionId = Some(subscriptionId), topicId = Some(topicId))

      case SubscriptionEvent.MessageDelivered(ackId, messageId, deadline) =>
        state.copy(outstanding = state.outstanding.updated(ackId, Outstanding(messageId, deadline)))

      case SubscriptionEvent.MessageAcknowledged(ackId) =>
        state.copy(outstanding = state.outstanding.removed(ackId))

      case SubscriptionEvent.AckDeadlineModified(ackId, deadline) =>
        state.outstanding.get(ackId) match
          case Some(o) => state.copy(outstanding = state.outstanding.updated(ackId, o.copy(deadline = deadline)))
          case None    => state

  /** Emit `event` when `ackId` is outstanding, otherwise reject as unknown. */
  private def ifOutstanding(state: SubscriptionState, ackId: AckId)(
      event: => SubscriptionEvent
  ): Either[Rejection, List[SubscriptionEvent]] =
    if state.outstanding.contains(ackId) then Right(List(event))
    else Left(Rejection.UnknownAckId(ackId))
