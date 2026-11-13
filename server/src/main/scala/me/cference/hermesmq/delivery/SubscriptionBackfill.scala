package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.Message
import me.cference.hermesmq.domain.StartPosition
import me.cference.hermesmq.domain.SubscriptionCommand
import me.cference.hermesmq.domain.SubscriptionEvent
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import me.cference.hermesmq.persistence.SubscriptionService
import org.slf4j.LoggerFactory

import java.time.Instant
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** Reads a topic's retained history so a subscription created with
  * [[StartPosition.Earliest]] receives messages published before it existed.
  *
  * The source of truth is the topic's own journal: the published messages are
  * already there, and building a separate read model to serve an uncommon
  * operation would duplicate every payload and still be bound by the same
  * retention horizon.
  *
  * **Horizon.** `Earliest` means the oldest event *still retained*. Snapshot
  * retention deletes events behind the retained snapshots, so on a long-lived
  * topic this is not "everything ever published". That is a documented limit, not
  * a failure -- purged events are simply absent.
  *
  * **At most once.** Guarded by [[BackfillMarkerRepository]], checked before and
  * written after. The marker is written *after* the deliveries on purpose:
  * marking first would risk a subscription that is recorded as backfilled but
  * never received anything (marked, then crashed), which is the silent miss this
  * feature exists to prevent. Marking last risks re-running a partial backfill,
  * which the aggregate's outstanding check absorbs for anything not yet acked. A
  * duplicate is recoverable; a silent miss is not.
  */
object SubscriptionBackfill:

  private val log = LoggerFactory.getLogger(getClass)

  /** Reads a topic's retained `MessagePublished` payloads, oldest first. */
  type HistoryReader = TopicId => Future[List[Message]]

  /** Back-deliver to a newly created subscription, if it asked for it.
    *
    * Returns the number of messages delivered. Ignores events for subscriptions
    * that did not ask for back-delivery, and subscriptions already backfilled.
    */
  def backfill(
      event: SubscriptionEvent,
      history: HistoryReader,
      subscriptions: SubscriptionService,
      markers: BackfillMarkerRepository,
      now: Instant
  )(using ExecutionContext): Future[Int] =
    event match
      case SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId, StartPosition.Earliest, _) =>
        markers.isComplete(subscriptionId).flatMap {
          case true =>
            // Already done. Re-running would re-deliver anything the consumer has
            // since acknowledged, because an acked message leaves no outstanding
            // entry to collide with.
            Future.successful(0)
          case false => run(subscriptionId, topicId, history, subscriptions, markers, now)
        }
      case _ => Future.successful(0)

  private def run(
      subscriptionId: SubscriptionId,
      topicId: TopicId,
      history: HistoryReader,
      subscriptions: SubscriptionService,
      markers: BackfillMarkerRepository,
      now: Instant
  )(using ExecutionContext): Future[Int] =
    history(topicId).flatMap { messages =>
      // An expired message must not be back-delivered: a TTL that lapsed while
      // the message sat in the journal is still a lapsed TTL.
      val deliverable = messages.filterNot(_.expired(now))
      // Sequentially, so deliveries land in publish order rather than whatever
      // order the futures happen to complete in.
      deliverable
        .foldLeft(Future.successful(0)) { (acc, message) =>
          acc.flatMap { delivered =>
            val ackId = DeliveryHandler.ackIdFor(subscriptionId, message.id)
            subscriptions
              .submit(subscriptionId, SubscriptionCommand.RecordDelivery(ackId, message))
              .map(_ => delivered + 1)
          }
        }
        .flatMap { delivered =>
          markers.markComplete(subscriptionId, now, delivered).map { _ =>
            log.info(
              "Back-delivered {} message(s) from topic {} to subscription {} ({} skipped as expired)",
              delivered,
              topicId.value,
              subscriptionId.value,
              messages.size - deliverable.size
            )
            delivered
          }
        }
    }
