package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.MessageId
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant

/** Tests the dead-letter read model via the in-memory implementation. */
final class DeadLetterRepositorySpec extends AnyFunSuite with Matchers with ScalaFutures:

  import scala.concurrent.ExecutionContext.Implicits.global

  private def tid(s: String) = TopicId.from(s).toOption.get
  private def sid(s: String) = SubscriptionId.from(s).toOption.get
  private def mid(s: String) = MessageId.from(s).toOption.get

  private val t0 = Instant.parse("2026-07-07T12:00:00Z")

  private def entry(
      sub: String,
      msg: String,
      origin: Option[String] = Some("orders"),
      attempts: Int = 5,
      at: Instant = t0
  ) = DeadLetterEntry(
    subscriptionId = sid(sub),
    originalMessageId = mid(msg),
    originTopic = origin.map(tid),
    payload = s"body-$msg".getBytes.toVector,
    attributes = Map("k" -> "v"),
    deliveryAttempts = attempts,
    deadLetteredAt = at,
    redrivenAt = None
  )

  test("record then list returns the dead-letter") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    val listed = repo.listFor(sid("s1")).futureValue
    val _      = listed.map(_.originalMessageId) shouldBe List(mid("m-1"))
    val _      = listed.head.originTopic shouldBe Some(tid("orders"))
    val _      = listed.head.deliveryAttempts shouldBe 5
    new String(listed.head.payload.toArray) shouldBe "body-m-1"
  }

  test("dead-letters are isolated per subscription") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    repo.record(entry("s2", "m-2")).futureValue
    val _ = repo.listFor(sid("s1")).futureValue.map(_.originalMessageId) shouldBe List(mid("m-1"))
    repo.listFor(sid("s2")).futureValue.map(_.originalMessageId) shouldBe List(mid("m-2"))
  }

  test("a subscription with no dead-letters lists empty, not an error") {
    InMemoryDeadLetterRepository().listFor(sid("ghost")).futureValue shouldBe empty
  }

  test("recording the same (subscription, message) twice is idempotent") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    repo.record(entry("s1", "m-1", attempts = 99)).futureValue
    val listed = repo.listFor(sid("s1")).futureValue
    val _      = listed.size shouldBe 1
    // First write wins: reprocessing an envelope must not mutate the record.
    listed.head.deliveryAttempts shouldBe 5
  }

  test("find returns a single dead-letter by original message id") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    val _ = repo.find(sid("s1"), mid("m-1")).futureValue.map(_.originalMessageId) shouldBe Some(mid("m-1"))
    repo.find(sid("s1"), mid("nope")).futureValue shouldBe None
  }

  test("find does not cross subscription boundaries") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    repo.find(sid("s2"), mid("m-1")).futureValue shouldBe None
  }

  test("markRedriven stamps the entry and is reflected in find and list") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    val at = Instant.parse("2026-07-07T13:00:00Z")
    repo.markRedriven(sid("s1"), mid("m-1"), at).futureValue
    val _ = repo.find(sid("s1"), mid("m-1")).futureValue.flatMap(_.redrivenAt) shouldBe Some(at)
    repo.listFor(sid("s1")).futureValue.head.redrivenAt shouldBe Some(at)
  }

  test("markRedriven on an unknown entry is a no-op, not an error") {
    val repo = InMemoryDeadLetterRepository()
    repo.markRedriven(sid("s1"), mid("ghost"), t0).futureValue
    repo.listFor(sid("s1")).futureValue shouldBe empty
  }

  test("a dead-letter recorded without an origin topic round-trips as None") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1", origin = None)).futureValue
    repo.find(sid("s1"), mid("m-1")).futureValue.flatMap(_.originTopic) shouldBe None
  }

  test("listFor returns the most recently dead-lettered first") {
    val repo  = InMemoryDeadLetterRepository()
    val older = t0
    val newer = t0.plusSeconds(60)
    repo.record(entry("s1", "m-old", at = older)).futureValue
    repo.record(entry("s1", "m-new", at = newer)).futureValue
    repo.listFor(sid("s1")).futureValue.map(_.originalMessageId) shouldBe List(mid("m-new"), mid("m-old"))
  }
