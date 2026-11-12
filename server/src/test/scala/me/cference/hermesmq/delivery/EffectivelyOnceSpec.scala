package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.duration.*

/** Conformance tests for the delivery-key stability contract that makes
  * effectively-once consumption possible (see the `message-delivery` spec).
  *
  * These assert a property of UNMODIFIED production code: the `ackId` is a pure
  * function of `(subscription, message)`, so every redelivery of a pair carries
  * the same key and a consumer can deduplicate on it. They exist so that a
  * future refactor which mints a per-attempt key fails here loudly, rather than
  * silently removing a guarantee consumers were told to rely on.
  *
  * Redelivery reaches a consumer through three distinct mechanisms -- an
  * ack-deadline lapse, a nack, and a projection replay -- so each is covered
  * separately; one test through one path would leave the other two free to drift.
  */
final class EffectivelyOnceSpec extends AnyWordSpec with Matchers:

  private val sub      = SubscriptionId.from("s1").toOption.get
  private val otherSub = SubscriptionId.from("s2").toOption.get
  private val topic    = TopicId.from("orders").toOption.get
  private val msgId    = MessageId.from("m-1").toOption.get
  private val t0       = Instant.parse("2026-07-07T12:00:00Z")
  private val message  = Message.from(msgId, "charge-42".getBytes, Map.empty, t0).toOption.get

  private val ackId = DeliveryHandler.ackIdFor(sub, msgId)

  /** A subscription that has been created and had `message` delivered once. */
  private def delivered: SubscriptionState =
    val created = Subscription
      .decide(Subscription.empty, SubscriptionCommand.CreateSubscription(sub, topic))
      .toOption
      .get
      .foldLeft(Subscription.empty)(Subscription.evolve)
    Subscription
      .decide(created, SubscriptionCommand.RecordDelivery(ackId, message))
      .toOption
      .get
      .foldLeft(created)(Subscription.evolve)

  private def apply(state: SubscriptionState, command: SubscriptionCommand): SubscriptionState =
    Subscription.decide(state, command).toOption.get.foldLeft(state)(Subscription.evolve)

  "the delivery key" should {

    "stay the same when a message is redelivered after its ack deadline lapses" in {
      val leased  = apply(delivered, SubscriptionCommand.Lease(max = 10, ackDeadline = 30.seconds, now = t0))
      val _       = leased.outstanding.keySet shouldBe Set(ackId)
      // The deadline lapses unacknowledged: the sweeper expires the lease.
      val expired = apply(leased, SubscriptionCommand.ExpireAckDeadline(ackId, t0.plusSeconds(31), maxAttempts = 5))
      val _       = expired.availableMessages.map(_._1) shouldBe List(ackId)
      // Re-leasing hands out the same key, not a fresh one per attempt.
      val relased = apply(expired, SubscriptionCommand.Lease(max = 10, ackDeadline = 30.seconds, now = t0.plusSeconds(32)))
      relased.outstanding.keySet shouldBe Set(ackId)
    }

    "stay the same when a message is redelivered after a nack" in {
      val leased = apply(delivered, SubscriptionCommand.Lease(max = 10, ackDeadline = 30.seconds, now = t0))
      // A nack is modifyAckDeadline with a zero deadline.
      val nacked = apply(
        leased,
        SubscriptionCommand.ModifyAckDeadline(ackId, ackDeadline = 0.seconds, now = t0.plusSeconds(1))
      )
      val expired =
        apply(nacked, SubscriptionCommand.ExpireAckDeadline(ackId, t0.plusSeconds(2), maxAttempts = 5))
      expired.availableMessages.map(_._1) shouldBe List(ackId)
    }

    "stay the same when the delivery projection replays the publish" in {
      // The handler's key is a pure function, so a replay recomputes it identically.
      val _ = DeliveryHandler.ackIdFor(sub, msgId) shouldBe ackId
      // And the aggregate treats the repeat as a no-op rather than a new message.
      val replayed = Subscription.decide(delivered, SubscriptionCommand.RecordDelivery(ackId, message))
      val _        = replayed shouldBe Right(Nil)
      delivered.outstanding.keySet shouldBe Set(ackId)
    }

    "differ between two subscriptions receiving the same message" in {
      // Otherwise one subscription's dedup state could suppress another's delivery.
      DeliveryHandler.ackIdFor(sub, msgId) should not be DeliveryHandler.ackIdFor(otherSub, msgId)
    }

    "differ between two messages on the same subscription" in {
      val other = MessageId.from("m-2").toOption.get
      DeliveryHandler.ackIdFor(sub, msgId) should not be DeliveryHandler.ackIdFor(sub, other)
    }
  }

  "a consumer deduplicating on the delivery key" should {

    "process a redelivered message exactly once" in {
      // The documented effectively-once recipe, exercised: the consumer keeps a
      // processed set and skips keys it has already handled.
      var processed: Set[AckId] = Set.empty
      var sideEffects           = 0

      def consume(ackIds: List[AckId]): Unit =
        ackIds.foreach { id =>
          if !processed.contains(id) then
            sideEffects += 1
            processed += id
        }

      // First delivery: leased, processed, but the ack is lost (consumer crashes
      // after its side effect -- the exact case that makes exactly-once impossible).
      val leased = apply(delivered, SubscriptionCommand.Lease(max = 10, ackDeadline = 30.seconds, now = t0))
      consume(leased.outstanding.keys.toList)
      val _ = sideEffects shouldBe 1

      // The deadline lapses and the message comes back under the same key.
      val expired  = apply(leased, SubscriptionCommand.ExpireAckDeadline(ackId, t0.plusSeconds(31), maxAttempts = 5))
      val redeliver = apply(expired, SubscriptionCommand.Lease(max = 10, ackDeadline = 30.seconds, now = t0.plusSeconds(32)))
      consume(redeliver.outstanding.keys.toList)

      // At-least-once delivery, once-only processing.
      val _ = sideEffects shouldBe 1
      processed shouldBe Set(ackId)
    }
  }
