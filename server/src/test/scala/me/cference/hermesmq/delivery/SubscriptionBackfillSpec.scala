package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.PulledMessage
import me.cference.hermesmq.persistence.SubscriptionService
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

/** Tests the back-delivery effect: which messages a newly created `Earliest`
  * subscription receives, and the at-most-once guard around it.
  */
final class SubscriptionBackfillSpec extends AnyWordSpec with Matchers with ScalaFutures:

  import scala.concurrent.ExecutionContext.Implicits.global

  private val sub   = SubscriptionId.from("s1").toOption.get
  private val topic = TopicId.from("orders").toOption.get
  private val now   = Instant.parse("2026-07-07T12:00:00Z")

  private def msg(id: String, expireTime: Option[Instant] = None) =
    Message
      .from(MessageId.from(id).toOption.get, s"body-$id".getBytes, Map.empty, now.minusSeconds(600), expireTime)
      .toOption
      .get

  /** Captures the deliveries the backfill issues. */
  private final class CapturingSubs extends SubscriptionService:
    val recorded = new ConcurrentLinkedQueue[(SubscriptionId, AckId, MessageId)]()
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      command match
        case SubscriptionCommand.RecordDelivery(ackId, message) =>
          recorded.add((id, ackId, message.id))
          Future.successful(CommandReply.Accepted)
        case _ => Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] = Future.successful(Some(Nil))

  private def history(messages: Message*): SubscriptionBackfill.HistoryReader =
    _ => Future.successful(messages.toList)

  private def created(startFrom: StartPosition) =
    SubscriptionEvent.SubscriptionCreated(sub, topic, startFrom)

  "back-delivery" should {

    "deliver every retained message to an Earliest subscription, in publish order" in {
      val subs    = CapturingSubs()
      val markers = InMemoryBackfillMarkerRepository()
      val count = SubscriptionBackfill
        .backfill(created(StartPosition.Earliest), history(msg("m-1"), msg("m-2"), msg("m-3")), subs, markers, now)
        .futureValue

      val _ = count shouldBe 3
      val _ = subs.recorded.asScala.toList.map(_._3.value) shouldBe List("m-1", "m-2", "m-3")
      // Keys match live delivery exactly, so a back-delivered message is
      // indistinguishable from a normally delivered one.
      subs.recorded.asScala.toList.map(_._2) shouldBe
        List("m-1", "m-2", "m-3").map(id => DeliveryHandler.ackIdFor(sub, MessageId.from(id).toOption.get))
    }

    "deliver nothing for a Now subscription" in {
      val subs    = CapturingSubs()
      val markers = InMemoryBackfillMarkerRepository()
      val count   = SubscriptionBackfill.backfill(created(StartPosition.Now), history(msg("m-1")), subs, markers, now).futureValue
      val _       = count shouldBe 0
      val _       = subs.recorded.asScala shouldBe empty
      // And it is not marked, so opting in later is still possible.
      markers.isComplete(sub).futureValue shouldBe false
    }

    "skip a message whose TTL has already lapsed" in {
      val subs    = CapturingSubs()
      val markers = InMemoryBackfillMarkerRepository()
      val expired = msg("m-old", expireTime = Some(now.minusSeconds(60)))
      val valid   = msg("m-new", expireTime = Some(now.plusSeconds(600)))
      val count   = SubscriptionBackfill.backfill(created(StartPosition.Earliest), history(expired, valid), subs, markers, now).futureValue
      val _       = count shouldBe 1
      subs.recorded.asScala.toList.map(_._3.value) shouldBe List("m-new")
    }

    "mark the subscription complete after delivering" in {
      val markers = InMemoryBackfillMarkerRepository()
      val _ = SubscriptionBackfill
        .backfill(created(StartPosition.Earliest), history(msg("m-1")), CapturingSubs(), markers, now)
        .futureValue
      markers.isComplete(sub).futureValue shouldBe true
    }

    "not re-deliver when the backfill has already completed" in {
      val subs    = CapturingSubs()
      val markers = InMemoryBackfillMarkerRepository()
      val h       = history(msg("m-1"), msg("m-2"))
      val first   = SubscriptionBackfill.backfill(created(StartPosition.Earliest), h, subs, markers, now).futureValue
      val second  = SubscriptionBackfill.backfill(created(StartPosition.Earliest), h, subs, markers, now).futureValue
      val _       = first shouldBe 2
      // THE hazard this guard exists for: an at-least-once projection reprocessing
      // SubscriptionCreated after the consumer drained the backfill would
      // otherwise re-deliver everything, since an acked message leaves no
      // outstanding entry to collide with.
      val _ = second shouldBe 0
      subs.recorded.asScala.size shouldBe 2
    }

    "deliver nothing, without error, for a topic with no retained messages" in {
      val subs    = CapturingSubs()
      val markers = InMemoryBackfillMarkerRepository()
      val count   = SubscriptionBackfill.backfill(created(StartPosition.Earliest), history(), subs, markers, now).futureValue
      val _       = count shouldBe 0
      val _       = subs.recorded.asScala shouldBe empty
      // Still marked: there was nothing to deliver, and that question is settled.
      markers.isComplete(sub).futureValue shouldBe true
    }

    "ignore events other than SubscriptionCreated" in {
      val subs  = CapturingSubs()
      val ackId = AckId.from("ack-1").toOption.get
      val count = SubscriptionBackfill
        .backfill(
          SubscriptionEvent.MessageAcknowledged(ackId),
          history(msg("m-1")),
          subs,
          InMemoryBackfillMarkerRepository(),
          now
        )
        .futureValue
      val _ = count shouldBe 0
      subs.recorded.asScala shouldBe empty
    }
  }

  "the backfill marker" should {

    "report incomplete before, complete after, and tolerate a repeated mark" in {
      val markers = InMemoryBackfillMarkerRepository()
      val _       = markers.isComplete(sub).futureValue shouldBe false
      markers.markComplete(sub, now, 3).futureValue
      val _ = markers.isComplete(sub).futureValue shouldBe true
      markers.markComplete(sub, now.plusSeconds(60), 99).futureValue
      markers.isComplete(sub).futureValue shouldBe true
    }

    "keep markers per subscription" in {
      val markers = InMemoryBackfillMarkerRepository()
      val other   = SubscriptionId.from("s2").toOption.get
      markers.markComplete(sub, now, 1).futureValue
      val _ = markers.isComplete(sub).futureValue shouldBe true
      markers.isComplete(other).futureValue shouldBe false
    }
  }

  "the end-to-end back-delivery flow" should {

    "make every retained message pullable on a new Earliest subscription" in {
      // Drives the real aggregate rather than a stub, so the deliveries have to
      // survive decide/evolve and land in `outstanding` as leasable messages.
      val markers = InMemoryBackfillMarkerRepository()
      val history = List(msg("e-1"), msg("e-2"), msg("e-3"))

      var state = Subscription
        .decide(Subscription.empty, SubscriptionCommand.CreateSubscription(sub, topic, StartPosition.Earliest))
        .toOption
        .get
        .foldLeft(Subscription.empty)(Subscription.evolve)

      val aggregate: SubscriptionService = new SubscriptionService:
        def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
          Subscription.decide(state, command) match
            case Right(events) =>
              state = events.foldLeft(state)(Subscription.evolve)
              Future.successful(CommandReply.Accepted)
            case Left(rejection) => Future.successful(CommandReply.Rejected(rejection))
        def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] = Future.successful(Some(Nil))

      val delivered = SubscriptionBackfill
        .backfill(created(StartPosition.Earliest), _ => Future.successful(history), aggregate, markers, now)
        .futureValue

      val _ = delivered shouldBe 3
      val _ = state.outstanding.size shouldBe 3
      // All available, i.e. actually pullable, under live-delivery keys.
      val _ = state.availableMessages.size shouldBe 3
      state.outstanding.keySet shouldBe history.map(m => DeliveryHandler.ackIdFor(sub, m.id)).toSet
    }

    "deliver nothing to a Now subscription on the same topic" in {
      val markers = InMemoryBackfillMarkerRepository()
      val subs    = CapturingSubs()
      val _ = SubscriptionBackfill
        .backfill(created(StartPosition.Now), history(msg("e-1"), msg("e-2")), subs, markers, now)
        .futureValue
      subs.recorded.asScala shouldBe empty
    }
  }
