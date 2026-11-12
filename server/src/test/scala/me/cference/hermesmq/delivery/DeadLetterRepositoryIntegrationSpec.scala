package me.cference.hermesmq.delivery

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.MessageId
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import me.cference.hermesmq.persistence.PostgresIT
import me.cference.hermesmq.persistence.SchemaMigrator
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.concurrent.ExecutionContext

/** The dead-letter read model against a real PostgreSQL (Testcontainers).
  *
  * The unit spec exercises the in-memory implementation; this one exercises the
  * SQL -- BYTEA payload round-trip, the nullable `origin_topic`, JSON-encoded
  * attributes, `ON CONFLICT DO NOTHING`, the `redriven_at` update, and the
  * `dead_lettered_at DESC` ordering. Without it the DDL and every statement in
  * [[JdbcDeadLetterRepository]] would ship unverified. Tagged [[PostgresIT]].
  */
final class DeadLetterRepositoryIntegrationSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll:

  private given ExecutionContext = ExecutionContext.global

  // ScalaFutures defaults to a 150ms patience, which is fine for in-memory work
  // and far too tight for the FIRST JDBC round-trip to a fresh container --
  // connection establishment plus JIT warmup regularly exceeds it. Observed as a
  // CI flake on the suite's first test while every later test passed.
  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(15, Seconds), interval = Span(100, Millis))

  private val dockerAvailable =
    try DockerClientFactory.instance().isDockerAvailable
    catch case _: Throwable => false

  private var container: PostgreSQLContainer[?] = _
  private var dbConfig: DbConfig                = _
  private var repo: JdbcDeadLetterRepository    = _

  override def beforeAll(): Unit =
    if dockerAvailable then
      container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
      container.start()
      val config = ConfigFactory
        .parseString(
          s"""
             |hermesmq.db.host = "${container.getHost}"
             |hermesmq.db.port = ${container.getMappedPort(5432)}
             |hermesmq.db.database = "${container.getDatabaseName}"
             |hermesmq.db.user = "${container.getUsername}"
             |hermesmq.db.password = "${container.getPassword}"
             |""".stripMargin
        )
        .withFallback(ConfigFactory.parseResources("application.conf"))
        .withFallback(ConfigFactory.defaultReference())
        .resolve()
      dbConfig = DbConfig.from(config).toOption.get
      // Provision via the real migrator, so this also proves the shipped DDL
      // supports the statements the repository issues.
      val _ = SchemaMigrator.migrate(dbConfig)
      repo = JdbcDeadLetterRepository(dbConfig)

  override def afterAll(): Unit =
    Option(container).foreach(_.stop())

  private def sid(s: String) = SubscriptionId.from(s).toOption.get
  private def tid(s: String) = TopicId.from(s).toOption.get
  private def mid(s: String) = MessageId.from(s).toOption.get

  // Postgres TIMESTAMPTZ keeps microseconds; truncate so equality is meaningful.
  private val t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS)

  private def entry(sub: String, msg: String, origin: Option[String], at: Instant = t0) =
    DeadLetterEntry(
      subscriptionId = sid(sub),
      originalMessageId = mid(msg),
      originTopic = origin.map(tid),
      payload = Vector[Byte](0, 1, 2, 127, -128), // non-UTF8 bytes: BYTEA, not text
      attributes = Map("k" -> "v", "x-delivery-attempts" -> "5"),
      deliveryAttempts = 5,
      deadLetteredAt = at,
      redrivenAt = None
    )

  "The JDBC dead-letter repository" should {

    "round-trip a dead-letter including raw payload bytes and attributes" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      val e = entry("it-s1", "it-m-1", Some("it-orders"))
      repo.record(e).futureValue

      val found = repo.find(sid("it-s1"), mid("it-m-1")).futureValue
      val _     = found.map(_.originalMessageId) shouldBe Some(mid("it-m-1"))
      val _     = found.flatMap(_.originTopic) shouldBe Some(tid("it-orders"))
      val _     = found.map(_.payload) shouldBe Some(Vector[Byte](0, 1, 2, 127, -128))
      val _     = found.map(_.attributes) shouldBe Some(Map("k" -> "v", "x-delivery-attempts" -> "5"))
      val _     = found.map(_.deliveryAttempts) shouldBe Some(5)
      val _     = found.map(_.deadLetteredAt) shouldBe Some(t0)
      found.flatMap(_.redrivenAt) shouldBe None
    }

    "store a NULL origin_topic as None rather than failing" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      repo.record(entry("it-s2", "it-m-2", None)).futureValue
      repo.find(sid("it-s2"), mid("it-m-2")).futureValue.flatMap(_.originTopic) shouldBe None
    }

    "ignore a duplicate record (ON CONFLICT DO NOTHING), keeping the first write" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      repo.record(entry("it-s3", "it-m-3", Some("it-orders"))).futureValue
      repo.record(entry("it-s3", "it-m-3", Some("it-orders")).copy(deliveryAttempts = 99)).futureValue
      val listed = repo.listFor(sid("it-s3"), 100, None).futureValue.entries
      val _      = listed.size shouldBe 1
      listed.head.deliveryAttempts shouldBe 5
    }

    "stamp redriven_at and read it back" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      repo.record(entry("it-s4", "it-m-4", Some("it-orders"))).futureValue
      val at = t0.plusSeconds(90)
      val _ = repo.claimForRedrive(sid("it-s4"), mid("it-m-4"), at, force = false).futureValue shouldBe true
      repo.find(sid("it-s4"), mid("it-m-4")).futureValue.flatMap(_.redrivenAt) shouldBe Some(at)
    }

    "list a subscription's dead-letters most recent first, isolated from other subscriptions" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      repo.record(entry("it-s5", "it-old", Some("it-orders"), at = t0)).futureValue
      repo.record(entry("it-s5", "it-new", Some("it-orders"), at = t0.plusSeconds(60))).futureValue
      repo.record(entry("it-s6", "it-other", Some("it-orders"))).futureValue

      val _ = repo.listFor(sid("it-s5"), 100, None).futureValue.entries.map(_.originalMessageId) shouldBe
        List(mid("it-new"), mid("it-old"))
      repo.listFor(sid("it-s6"), 100, None).futureValue.entries.map(_.originalMessageId) shouldBe List(mid("it-other"))
    }

    "return empty / None for a subscription with no dead-letters" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      val _ = repo.listFor(sid("it-ghost"), 100, None).futureValue.entries shouldBe empty
      repo.find(sid("it-ghost"), mid("it-nope")).futureValue shouldBe None
    }

    "treat a claim on an absent row as a no-op that wins nothing" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      val _ = repo.claimForRedrive(sid("it-ghost"), mid("it-nope"), t0, force = false).futureValue shouldBe false
      repo.listFor(sid("it-ghost"), 100, None).futureValue.entries shouldBe empty
    }

    "claim exactly once when two callers race for the same entry" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      repo.record(entry("it-s7", "it-m-7", Some("it-orders"))).futureValue
      // The conditional UPDATE is the only thing serialising these.
      val both = scala.concurrent.Future
        .sequence(
          List(
            repo.claimForRedrive(sid("it-s7"), mid("it-m-7"), t0, force = false),
            repo.claimForRedrive(sid("it-s7"), mid("it-m-7"), t0, force = false)
          )
        )
        .futureValue
      both.count(identity) shouldBe 1
    }

    "release a claim so a retry can take it, and honour a forced claim" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      repo.record(entry("it-s8", "it-m-8", Some("it-orders"))).futureValue
      val _ = repo.claimForRedrive(sid("it-s8"), mid("it-m-8"), t0, force = false).futureValue shouldBe true
      repo.releaseClaim(sid("it-s8"), mid("it-m-8")).futureValue
      val _ = repo.find(sid("it-s8"), mid("it-m-8")).futureValue.flatMap(_.redrivenAt) shouldBe None
      val _ = repo.claimForRedrive(sid("it-s8"), mid("it-m-8"), t0, force = false).futureValue shouldBe true
      // Already claimed: only force gets through.
      val _ = repo.claimForRedrive(sid("it-s8"), mid("it-m-8"), t0, force = false).futureValue shouldBe false
      repo.claimForRedrive(sid("it-s8"), mid("it-m-8"), t0.plusSeconds(5), force = true).futureValue shouldBe true
    }

    "page keyset-wise, including entries that share a dead-letter time" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")
      val shared = t0.plusSeconds(500)
      repo.record(entry("it-s9", "it-p1", Some("it-orders"), at = shared)).futureValue
      repo.record(entry("it-s9", "it-p2", Some("it-orders"), at = shared)).futureValue
      repo.record(entry("it-s9", "it-p3", Some("it-orders"), at = shared.plusSeconds(1))).futureValue

      val p1 = repo.listFor(sid("it-s9"), 2, None).futureValue
      val _  = p1.entries.size shouldBe 2
      val _  = p1.nextCursor shouldBe defined
      val p2 = repo.listFor(sid("it-s9"), 2, p1.nextCursor).futureValue
      val all = (p1.entries ++ p2.entries).map(_.originalMessageId.value)
      val _   = all.size shouldBe 3
      val _   = all.distinct.size shouldBe 3
      all.head shouldBe "it-p3" // newest first
    }
  }
