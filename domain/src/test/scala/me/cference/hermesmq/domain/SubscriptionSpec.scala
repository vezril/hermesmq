package me.cference.hermesmq.domain

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.concurrent.duration.*

/** Tests the pure Subscription aggregate: delivery adds AVAILABLE messages, pull
  * leases them (visibility timeout), overdue leases expire into redelivery, and
  * the attempt limit dead-letters — all as total `decide`/`evolve`.
  */
final class SubscriptionSpec extends AnyFunSuite:

  import SubscriptionCommand.*
  import SubscriptionEvent.*

  private val subId   = SubscriptionId.from("sub-1").toOption.get
  private val topicId = TopicId.from("orders").toOption.get
  private val ackId   = AckId.from("ack-1").toOption.get
  private val t0      = Instant.parse("2026-07-08T00:00:00Z")
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map("k" -> "v"), t0)
    .toOption
    .get

  private def created: SubscriptionState =
    Subscription.evolve(Subscription.empty, SubscriptionCreated(subId, topicId))
  private def evolveAll(events: List[SubscriptionEvent], from: SubscriptionState = created): SubscriptionState =
    events.foldLeft(from)(Subscription.evolve)
  private def withAvailable: SubscriptionState =
    Subscription.evolve(created, MessageDelivered(ackId, message))

  test("creating a subscription evolves to existing with no outstanding") {
    val state = evolveAll(Subscription.decide(Subscription.empty, CreateSubscription(subId, topicId)).toOption.get, Subscription.empty)
    assert(state.exists && state.outstanding.isEmpty)
  }

  test("recording a delivery adds an AVAILABLE message with no deadline") {
    val events = Subscription.decide(created, RecordDelivery(ackId, message))
    assert(events == Right(List(MessageDelivered(ackId, message))))
    val o = evolveAll(events.toOption.get).outstanding(ackId)
    assert(o.available && o.attempts == 0)
  }

  test("re-delivering an outstanding ackId is an idempotent no-op") {
    assert(Subscription.decide(withAvailable, RecordDelivery(ackId, message)) == Right(Nil))
  }

  test("recording delivery on a non-existent subscription is rejected") {
    assert(Subscription.decide(Subscription.empty, RecordDelivery(ackId, message)) == Left(Rejection.SubscriptionNotFound))
  }

  test("pull leases available messages and sets a deadline of now + ackDeadline") {
    val events = Subscription.decide(withAvailable, Lease(max = 10, ackDeadline = 30.seconds, now = t0))
    assert(events == Right(List(MessageLeased(List(ackId), t0.plusSeconds(30)))))
    val o = evolveAll(events.toOption.get, withAvailable).outstanding(ackId)
    assert(o.lease == LeaseState.Leased(t0.plusSeconds(30)))
  }

  test("a leased message within its deadline is not leased again") {
    val leased = evolveAll(Subscription.decide(withAvailable, Lease(10, 30.seconds, t0)).toOption.get, withAvailable)
    assert(Subscription.decide(leased, Lease(10, 30.seconds, t0.plusSeconds(5))) == Right(Nil))
  }

  test("pull respects the requested maximum") {
    val ack2 = AckId.from("ack-2").toOption.get
    val two  = Subscription.evolve(withAvailable, MessageDelivered(ack2, message))
    val leased = Subscription.decide(two, Lease(max = 1, ackDeadline = 30.seconds, now = t0)).toOption.get
    val ids = leased.collect { case MessageLeased(l, _) => l }.flatten
    assert(ids.size == 1)
  }

  test("pull on a subscription with no available messages returns nothing") {
    assert(Subscription.decide(created, Lease(10, 30.seconds, t0)) == Right(Nil))
  }

  test("expiring an overdue leased message returns it to AVAILABLE and increments attempts") {
    val leased = evolveAll(Subscription.decide(withAvailable, Lease(10, 30.seconds, t0)).toOption.get, withAvailable)
    val events = Subscription.decide(leased, ExpireAckDeadline(ackId, now = t0.plusSeconds(31), maxAttempts = 5))
    assert(events == Right(List(AckDeadlineExpired(ackId, 1))))
    val o = evolveAll(events.toOption.get, leased).outstanding(ackId)
    assert(o.available && o.attempts == 1)
  }

  test("expiring a within-deadline lease is a no-op") {
    val leased = evolveAll(Subscription.decide(withAvailable, Lease(10, 30.seconds, t0)).toOption.get, withAvailable)
    assert(Subscription.decide(leased, ExpireAckDeadline(ackId, now = t0.plusSeconds(5), maxAttempts = 5)) == Right(Nil))
  }

  test("expiring an acknowledged (gone) message is a no-op") {
    val acked = Subscription.evolve(withAvailable, MessageAcknowledged(ackId))
    assert(Subscription.decide(acked, ExpireAckDeadline(ackId, t0.plusSeconds(31), 5)) == Right(Nil))
  }

  test("reaching maxDeliveryAttempts dead-letters instead of redelivering") {
    val base = evolveAll(
      List(MessageDelivered(ackId, message), AckDeadlineExpired(ackId, 1), AckDeadlineExpired(ackId, 2), MessageLeased(List(ackId), t0.plusSeconds(30)))
    )
    val events = Subscription.decide(base, ExpireAckDeadline(ackId, now = t0.plusSeconds(31), maxAttempts = 3))
    assert(events == Right(List(MessageDeadLettered(ackId, message, 3))))
    assert(!evolveAll(events.toOption.get, base).outstanding.contains(ackId))
  }

  test("maxDeliveryAttempts = 0 means unlimited (never dead-letters)") {
    val base = evolveAll(List(MessageDelivered(ackId, message), AckDeadlineExpired(ackId, 9), MessageLeased(List(ackId), t0.plusSeconds(30))))
    val events = Subscription.decide(base, ExpireAckDeadline(ackId, t0.plusSeconds(31), maxAttempts = 0))
    assert(events == Right(List(AckDeadlineExpired(ackId, 10))))
  }

  test("attempt count rebuilds from journaled AckDeadlineExpired events across recovery") {
    val recovered = evolveAll(List(MessageDelivered(ackId, message), AckDeadlineExpired(ackId, 1), AckDeadlineExpired(ackId, 2)))
    assert(recovered.outstanding(ackId).attempts == 2)
    val leased = Subscription.evolve(recovered, MessageLeased(List(ackId), t0.plusSeconds(30)))
    assert(Subscription.decide(leased, ExpireAckDeadline(ackId, t0.plusSeconds(31), 3)) == Right(List(MessageDeadLettered(ackId, message, 3))))
  }

  test("acknowledging an outstanding message removes it") {
    val events = Subscription.decide(withAvailable, Acknowledge(ackId))
    assert(events == Right(List(MessageAcknowledged(ackId))))
    assert(!evolveAll(events.toOption.get, withAvailable).outstanding.contains(ackId))
  }

  test("acknowledging an unknown ackId is rejected") {
    assert(Subscription.decide(created, Acknowledge(ackId)) == Left(Rejection.UnknownAckId(ackId)))
  }

  // --- Message TTL --------------------------------------------------------

  private val expireAt = t0.plusSeconds(60)
  private val ttlMessage = Message
    .from(MessageId.from("m-ttl").toOption.get, "hi".getBytes, Map.empty, t0, expireTime = Some(expireAt))
    .toOption
    .get
  private def withExpiring: SubscriptionState = Subscription.evolve(created, MessageDelivered(ackId, ttlMessage))

  test("Lease does not return an available message whose TTL has passed") {
    assert(Subscription.decide(withExpiring, Lease(10, 30.seconds, now = expireAt)) == Right(Nil))
    assert(Subscription.decide(withExpiring, Lease(10, 30.seconds, now = expireAt.plusSeconds(1))) == Right(Nil))
  }

  test("Lease still returns a message before its TTL passes") {
    val events = Subscription.decide(withExpiring, Lease(10, 30.seconds, now = t0))
    assert(events == Right(List(MessageLeased(List(ackId), t0.plusSeconds(30)))))
  }

  test("ExpireMessage on an outstanding expired message emits MessageExpired and removes it") {
    val events = Subscription.decide(withExpiring, ExpireMessage(ackId, now = expireAt))
    assert(events == Right(List(MessageExpired(ackId))))
    assert(!evolveAll(events.toOption.get, withExpiring).outstanding.contains(ackId))
  }

  test("ExpireMessage expires regardless of attempt count or lease state") {
    // Message leased and previously redelivered, but past its TTL.
    val base = evolveAll(List(MessageDelivered(ackId, ttlMessage), AckDeadlineExpired(ackId, 3), MessageLeased(List(ackId), expireAt.plusSeconds(30))))
    assert(Subscription.decide(base, ExpireMessage(ackId, now = expireAt.plusSeconds(1))) == Right(List(MessageExpired(ackId))))
  }

  test("ExpireMessage before the TTL passes is a no-op") {
    assert(Subscription.decide(withExpiring, ExpireMessage(ackId, now = t0)) == Right(Nil))
  }

  test("ExpireMessage on a message with no TTL is a no-op") {
    assert(Subscription.decide(withAvailable, ExpireMessage(ackId, now = t0.plusSeconds(100_000))) == Right(Nil))
  }

  test("ExpireMessage on an unknown/gone ackId is a no-op") {
    assert(Subscription.decide(created, ExpireMessage(ackId, now = expireAt)) == Right(Nil))
  }
