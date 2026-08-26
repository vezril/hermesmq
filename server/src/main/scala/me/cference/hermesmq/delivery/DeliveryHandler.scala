package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.SubscriptionService
import me.cference.hermesmq.tracing.Correlation
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

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
    deliverTo: SubscriptionService
)(using ExecutionContext):

  private val log = LoggerFactory.getLogger(classOf[DeliveryHandler])

  def deliver(topicId: TopicId, message: Message): Future[Unit] =
    // Async broker work joins the ORIGINATING request's trace: the fan-out runs
    // (minutes/nodes/restarts later) under the message's journaled correlation
    // id, not a fresh one. Symmetric put/remove on the projection thread; the
    // MDC-propagating EC carries it into the Future continuations.
    withMessageMdc(message) {
      log.debug("delivering message {} on topic {}", message.id.value, topicId.value)
      subscriptions.subscriptionsFor(topicId).flatMap { targets =>
        Future
          .traverse(targets) { subscriptionId =>
            val ackId = DeliveryHandler.ackIdFor(subscriptionId, message.id)
            deliverTo.submit(subscriptionId, SubscriptionCommand.RecordDelivery(ackId, message))
          }
          .map(_ => ())
      }
    }

  private def withMessageMdc[A](message: Message)(body: => A): A =
    message.correlationId match
      case Some(id) =>
        MDC.put(Correlation.MdcKey, id)
        try body
        finally MDC.remove(Correlation.MdcKey)
      case None => body

object DeliveryHandler:
  /** Stable ack id for a `(subscription, message)` pair. */
  def ackIdFor(subscriptionId: SubscriptionId, messageId: MessageId): AckId =
    AckId.from(s"${subscriptionId.value}:${messageId.value}").toOption.get
