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
    val listed = repo.listFor(sid("s1"), 100, None).futureValue.entries
    val _      = listed.map(_.originalMessageId) shouldBe List(mid("m-1"))
    val _      = listed.head.originTopic shouldBe Some(tid("orders"))
    val _      = listed.head.deliveryAttempts shouldBe 5
    new String(listed.head.payload.toArray) shouldBe "body-m-1"
  }

  test("dead-letters are isolated per subscription") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    repo.record(entry("s2", "m-2")).futureValue
    val _ = repo.listFor(sid("s1"), 100, None).futureValue.entries.map(_.originalMessageId) shouldBe List(mid("m-1"))
    repo.listFor(sid("s2"), 100, None).futureValue.entries.map(_.originalMessageId) shouldBe List(mid("m-2"))
  }

  test("a subscription with no dead-letters lists empty, not an error") {
    InMemoryDeadLetterRepository().listFor(sid("ghost"), 100, None).futureValue.entries shouldBe empty
  }

  test("recording the same (subscription, message) twice is idempotent") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    repo.record(entry("s1", "m-1", attempts = 99)).futureValue
    val listed = repo.listFor(sid("s1"), 100, None).futureValue.entries
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
    repo.listFor(sid("s1"), 100, None).futureValue.entries.map(_.originalMessageId) shouldBe List(mid("m-new"), mid("m-old"))
  }

  // --- Paging (keyset on (deadLetteredAt DESC, originalMessageId DESC)) ---

  test("listFor bounds the page by limit and reports a cursor when more remain") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1", at = t0)).futureValue
    repo.record(entry("s1", "m-2", at = t0.plusSeconds(60))).futureValue
    repo.record(entry("s1", "m-3", at = t0.plusSeconds(120))).futureValue

    val page = repo.listFor(sid("s1"), limit = 2, cursor = None).futureValue
    val _    = page.entries.map(_.originalMessageId) shouldBe List(mid("m-3"), mid("m-2"))
    page.nextCursor.map(_.originalMessageId) shouldBe Some(mid("m-2"))
  }

  test("following the cursor returns the rest without repeats and ends with no cursor") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1", at = t0)).futureValue
    repo.record(entry("s1", "m-2", at = t0.plusSeconds(60))).futureValue
    repo.record(entry("s1", "m-3", at = t0.plusSeconds(120))).futureValue

    val first  = repo.listFor(sid("s1"), limit = 2, cursor = None).futureValue
    val second = repo.listFor(sid("s1"), limit = 2, cursor = first.nextCursor).futureValue
    val _      = second.entries.map(_.originalMessageId) shouldBe List(mid("m-1"))
    val _      = second.nextCursor shouldBe None
    // Every entry seen exactly once across the walk.
    (first.entries ++ second.entries).map(_.originalMessageId).distinct.size shouldBe 3
  }

  test("entries sharing a dead-letter time are each returned exactly once across pages") {
    val repo = InMemoryDeadLetterRepository()
    // One envelope batch: identical timestamps, so the message id must break the tie.
    repo.record(entry("s1", "m-a", at = t0)).futureValue
    repo.record(entry("s1", "m-b", at = t0)).futureValue
    repo.record(entry("s1", "m-c", at = t0)).futureValue

    val p1  = repo.listFor(sid("s1"), limit = 2, cursor = None).futureValue
    val p2  = repo.listFor(sid("s1"), limit = 2, cursor = p1.nextCursor).futureValue
    val all = (p1.entries ++ p2.entries).map(_.originalMessageId)
    val _   = all.size shouldBe 3
    val _   = all.distinct.size shouldBe 3
    all shouldBe List(mid("m-c"), mid("m-b"), mid("m-a")) // id descending within the tie
  }

  test("a page that exactly exhausts the results reports no cursor") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    repo.record(entry("s1", "m-2", at = t0.plusSeconds(60))).futureValue
    val page = repo.listFor(sid("s1"), limit = 2, cursor = None).futureValue
    val _    = page.entries.size shouldBe 2
    page.nextCursor shouldBe None
  }

  test("paging an empty subscription yields nothing and no cursor") {
    val page = InMemoryDeadLetterRepository().listFor(sid("ghost"), limit = 10, cursor = None).futureValue
    val _    = page.entries shouldBe empty
    page.nextCursor shouldBe None
  }

  // --- Redrive claim (compare-and-swap on redriven_at) ---

  test("claimForRedrive wins on an unclaimed entry and loses on an already-claimed one") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    val _ = repo.claimForRedrive(sid("s1"), mid("m-1"), t0, force = false).futureValue shouldBe true
    repo.claimForRedrive(sid("s1"), mid("m-1"), t0.plusSeconds(1), force = false).futureValue shouldBe false
  }

  test("claimForRedrive stamps redrivenAt on the winning claim") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    val at = t0.plusSeconds(30)
    val _  = repo.claimForRedrive(sid("s1"), mid("m-1"), at, force = false).futureValue
    repo.find(sid("s1"), mid("m-1")).futureValue.flatMap(_.redrivenAt) shouldBe Some(at)
  }

  test("claimForRedrive on an absent entry is false, not an error") {
    InMemoryDeadLetterRepository().claimForRedrive(sid("s1"), mid("ghost"), t0, force = false).futureValue shouldBe false
  }

  test("releaseClaim clears redrivenAt so a retry can claim again") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    val _ = repo.claimForRedrive(sid("s1"), mid("m-1"), t0, force = false).futureValue
    repo.releaseClaim(sid("s1"), mid("m-1")).futureValue
    val _ = repo.find(sid("s1"), mid("m-1")).futureValue.flatMap(_.redrivenAt) shouldBe None
    repo.claimForRedrive(sid("s1"), mid("m-1"), t0.plusSeconds(5), force = false).futureValue shouldBe true
  }

  test("a forced claim takes an already-claimed entry and updates its timestamp") {
    val repo = InMemoryDeadLetterRepository()
    repo.record(entry("s1", "m-1")).futureValue
    val _  = repo.claimForRedrive(sid("s1"), mid("m-1"), t0, force = false).futureValue
    val at = t0.plusSeconds(99)
    val _  = repo.claimForRedrive(sid("s1"), mid("m-1"), at, force = true).futureValue shouldBe true
    repo.find(sid("s1"), mid("m-1")).futureValue.flatMap(_.redrivenAt) shouldBe Some(at)
  }

  test("a forced claim on an absent entry is still false") {
    InMemoryDeadLetterRepository().claimForRedrive(sid("s1"), mid("ghost"), t0, force = true).futureValue shouldBe false
  }
