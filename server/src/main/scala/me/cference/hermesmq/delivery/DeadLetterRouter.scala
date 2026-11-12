package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.TopicService

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** The outcome of routing one subscription event to the dead-letter path. */
enum DeadLetterOutcome:
  case Published(topic: TopicId, messageId: MessageId)
  case Dropped(subscriptionId: SubscriptionId, messageId: MessageId)
  case Ignored

/** Routes exhausted (dead-lettered) messages onto a configured dead-letter topic.
  *
  * On `MessageDeadLettered`, the original payload is republished to the
  * dead-letter topic with provenance headers; with no topic configured the
  * message is dropped (the caller warns). The republished message id is
  * deterministic per `(subscription, original message)`, so an at-least-once
  * projection replay republishes the same id and downstream delivery stays
  * idempotent.
  */
object DeadLetterRouter:

  val SubscriptionHeader  = "x-dead-letter-subscription"
  val AttemptsHeader      = "x-delivery-attempts"
  val OriginalIdHeader    = "x-original-message-id"
  val OriginalTopicHeader = "x-original-topic"

  /** Build the dead-letter envelope for an exhausted original message.
    *
    * `originTopic` is the topic the message was originally published to. It is
    * recorded as [[OriginalTopicHeader]] so a redrive can republish without
    * joining back through the subscription listing. It is an `Option` because a
    * subscription whose topic cannot be resolved (for example, its index row has
    * not been projected yet) must still dead-letter rather than lose the
    * message -- the attribute is then omitted and the redrive path falls back to
    * resolving the topic from the subscription.
    */
  def deadLetterMessage(
      subscriptionId: SubscriptionId,
      originTopic: Option[TopicId],
      original: Message,
      attempt: Int
  ): Either[ValidationError, Message] =
    for
      id <- MessageId.from(s"dead-letter:${subscriptionId.value}:${original.id.value}")
      msg <- Message.from(
        id,
        original.payload.toArray,
        original.attributes ++ Map(
          SubscriptionHeader -> subscriptionId.value,
          AttemptsHeader     -> attempt.toString,
          OriginalIdHeader   -> original.id.value
        ) ++ originTopic.map(t => OriginalTopicHeader -> t.value),
        original.publishTime
      )
    yield msg

  /** React to one subscription event: republish an exhausted message to the
    * dead-letter topic, or drop it when none is configured.
    */
  def route(
      topics: TopicService,
      deadLetterTopic: Option[TopicId],
      subscriptionId: SubscriptionId,
      originTopic: Option[TopicId],
      event: SubscriptionEvent
  )(using ExecutionContext): Future[DeadLetterOutcome] =
    event match
      case SubscriptionEvent.MessageDeadLettered(_, message, attempt) =>
        deadLetterTopic match
          case Some(topic) =>
            deadLetterMessage(subscriptionId, originTopic, message, attempt) match
              case Right(dl) => topics.submit(topic, TopicCommand.Publish(dl)).map(_ => DeadLetterOutcome.Published(topic, dl.id))
              case Left(_)   => Future.successful(DeadLetterOutcome.Dropped(subscriptionId, message.id))
          case None => Future.successful(DeadLetterOutcome.Dropped(subscriptionId, message.id))
      case _ => Future.successful(DeadLetterOutcome.Ignored)
