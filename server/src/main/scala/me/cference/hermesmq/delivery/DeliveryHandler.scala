package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.SubscriptionService

import scala.concurrent.{ExecutionContext, Future}

/** Fans a published message out to every subscription on its topic by issuing
  * `RecordDelivery` to each. Subscriptions are looked up from the durable,
  * cluster-shared read model, so delivery reaches subscriptions created on any
  * node. The `ackId` is deterministic per `(subscription, message)`, so a
  * projection replay re-issues the same delivery and the subscription treats it
  * as an idempotent no-op — at-least-once delivery without duplicates in the
  * common case.
  */
final class DeliveryHandler(
    subscriptions: TopicSubscriptionsRepository,
    deliverTo: SubscriptionService,
    ackDeadline: AckDeadline
)(using ExecutionContext):

  def deliver(topicId: TopicId, message: Message): Future[Unit] =
    subscriptions.subscriptionsFor(topicId).flatMap { targets =>
      Future
        .traverse(targets) { subscriptionId =>
          val ackId = DeliveryHandler.ackIdFor(subscriptionId, message.id)
          deliverTo.submit(subscriptionId, SubscriptionCommand.RecordDelivery(ackId, message, ackDeadline))
        }
        .map(_ => ())
    }

object DeliveryHandler:
  /** Stable ack id for a `(subscription, message)` pair. */
  def ackIdFor(subscriptionId: SubscriptionId, messageId: MessageId): AckId =
    AckId.from(s"${subscriptionId.value}:${messageId.value}").toOption.get
