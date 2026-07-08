package me.cference.hermesmq.domain

import org.scalatest.funsuite.AnyFunSuite

import java.time.Instant
import scala.concurrent.duration.*

/** Tests the pure Subscription aggregate: create, record-delivery (carrying the
  * message, idempotent), acknowledge, and modify-deadline, plus rejections.
  */
final class SubscriptionSpec extends AnyFunSuite:

  import SubscriptionCommand.*
  import SubscriptionEvent.*

  private val subId    = SubscriptionId.from("sub-1").toOption.get
  private val topicId  = TopicId.from("orders").toOption.get
  private val ackId    = AckId.from("ack-1").toOption.get
  private val deadline = AckDeadline.from(30.seconds).toOption.get
  private val extended = AckDeadline.from(60.seconds).toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  /** A created subscription with one outstanding message. */
  private def withOutstanding: SubscriptionState =
    List(SubscriptionCreated(subId, topicId), MessageDelivered(ackId, message, deadline))
      .foldLeft(Subscription.empty)(Subscription.evolve)

  test("creating a subscription emits SubscriptionCreated and evolves to existing with no outstanding") {
    val events = Subscription.decide(Subscription.empty, CreateSubscription(subId, topicId))
    assert(events == Right(List(SubscriptionCreated(subId, topicId))))
    val state = events.toOption.get.foldLeft(Subscription.empty)(Subscription.evolve)
    assert(state.exists)
    assert(state.outstanding.isEmpty)
  }

  test("creating an existing subscription is rejected") {
    val existing = Subscription.evolve(Subscription.empty, SubscriptionCreated(subId, topicId))
    assert(Subscription.decide(existing, CreateSubscription(subId, topicId)) == Left(Rejection.SubscriptionAlreadyExists))
  }

  test("recording a delivery stores the message as outstanding") {
    val existing = Subscription.evolve(Subscription.empty, SubscriptionCreated(subId, topicId))
    val events   = Subscription.decide(existing, RecordDelivery(ackId, message, deadline))
    assert(events == Right(List(MessageDelivered(ackId, message, deadline))))
    val state = events.toOption.get.foldLeft(existing)(Subscription.evolve)
    assert(state.outstanding.get(ackId).map(_.message) == Some(message))
  }

  test("re-delivering an already-outstanding ackId is an idempotent no-op") {
    val result = Subscription.decide(withOutstanding, RecordDelivery(ackId, message, deadline))
    assert(result == Right(Nil))
    // evolving Nil leaves state unchanged
    assert(withOutstanding.outstanding.keySet == Set(ackId))
  }

  test("recording a delivery on a non-existent subscription is rejected") {
    assert(Subscription.decide(Subscription.empty, RecordDelivery(ackId, message, deadline)) == Left(Rejection.SubscriptionNotFound))
  }

  test("acknowledging an outstanding message removes it") {
    val events = Subscription.decide(withOutstanding, Acknowledge(ackId))
    assert(events == Right(List(MessageAcknowledged(ackId))))
    val state = events.toOption.get.foldLeft(withOutstanding)(Subscription.evolve)
    assert(!state.outstanding.contains(ackId))
  }

  test("acknowledging an unknown ackId is rejected") {
    val existing = Subscription.evolve(Subscription.empty, SubscriptionCreated(subId, topicId))
    assert(Subscription.decide(existing, Acknowledge(ackId)) == Left(Rejection.UnknownAckId(ackId)))
  }

  test("double acknowledge is rejected") {
    val acked = Subscription.evolve(withOutstanding, MessageAcknowledged(ackId))
    assert(Subscription.decide(acked, Acknowledge(ackId)) == Left(Rejection.UnknownAckId(ackId)))
  }

  test("modifying the deadline of an outstanding message updates it") {
    val events = Subscription.decide(withOutstanding, ModifyAckDeadline(ackId, extended))
    assert(events == Right(List(AckDeadlineModified(ackId, extended))))
    val state = events.toOption.get.foldLeft(withOutstanding)(Subscription.evolve)
    assert(state.outstanding(ackId).deadline == extended)
  }

  test("modifying the deadline of an unknown ackId is rejected") {
    val existing = Subscription.evolve(Subscription.empty, SubscriptionCreated(subId, topicId))
    assert(Subscription.decide(existing, ModifyAckDeadline(ackId, extended)) == Left(Rejection.UnknownAckId(ackId)))
  }
