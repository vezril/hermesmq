package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.*
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Tests the per-event effect of the dead-letter index projection. */
final class DeadLetterIndexHandlerSpec extends AnyFunSuite with Matchers with ScalaFutures:

  import scala.concurrent.ExecutionContext.Implicits.global

  private val sub      = SubscriptionId.from("s1").toOption.get
  private val ackId    = AckId.from("ack-1").toOption.get
  private val origin   = TopicId.from("orders").toOption.get
  private val origId   = MessageId.from("m-1").toOption.get
  private val t0       = Instant.parse("2026-07-07T12:00:00Z")
  private val message  = Message.from(origId, "body".getBytes, Map("k" -> "v"), t0).toOption.get
  private val deadAt   = Instant.parse("2026-07-07T12:05:00Z")

  private def index(repo: DeadLetterRepository, event: SubscriptionEvent, originTopic: Option[TopicId] = Some(origin)) =
    DeadLetterIndexProjection.indexEvent(repo, sub, originTopic, deadAt, event).futureValue

  test("a dead-lettered message is recorded with payload, attempts and origin topic") {
    val repo = InMemoryDeadLetterRepository()
    index(repo, SubscriptionEvent.MessageDeadLettered(ackId, message, 5))

    val listed = repo.listFor(sub).futureValue
    val _      = listed.size shouldBe 1
    val _      = listed.head.originalMessageId shouldBe origId
    val _      = listed.head.originTopic shouldBe Some(origin)
    val _      = listed.head.deliveryAttempts shouldBe 5
    val _      = listed.head.deadLetteredAt shouldBe deadAt
    val _      = listed.head.attributes shouldBe Map("k" -> "v")
    new String(listed.head.payload.toArray) shouldBe "body"
  }

  test("recording is idempotent under at-least-once reprocessing") {
    val repo = InMemoryDeadLetterRepository()
    index(repo, SubscriptionEvent.MessageDeadLettered(ackId, message, 5))
    index(repo, SubscriptionEvent.MessageDeadLettered(ackId, message, 5))
    repo.listFor(sub).futureValue.size shouldBe 1
  }

  test("a dead-letter is recorded even when the origin topic is unresolved") {
    val repo = InMemoryDeadLetterRepository()
    index(repo, SubscriptionEvent.MessageDeadLettered(ackId, message, 5), originTopic = None)

    val listed = repo.listFor(sub).futureValue
    val _      = listed.size shouldBe 1
    listed.head.originTopic shouldBe None
  }

  test("non-dead-letter events are ignored") {
    val repo = InMemoryDeadLetterRepository()
    index(repo, SubscriptionEvent.MessageAcknowledged(ackId))
    repo.listFor(sub).futureValue shouldBe empty
  }
