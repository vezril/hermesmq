package me.cference.hermesmq.persistence

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.config.{DedupConfig, RetentionConfig}
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.sql.DriverManager
import java.time.Instant
import scala.concurrent.duration.*

/** End-to-end persistence against a real PostgreSQL (Testcontainers). Tagged
  * [[PostgresIT]] and excluded from the default `sbt test` run, so CI needs no
  * database. Run with Docker available:
  *   sbt "testOnly *PostgresPersistenceIntegrationSpec"
  */
final class PostgresPersistenceIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val dockerAvailable =
    try DockerClientFactory.instance().isDockerAvailable
    catch case _: Throwable => false

  private var container: PostgreSQLContainer[?] = _
  private var testKit: ActorTestKit             = _

  override def beforeAll(): Unit =
    if dockerAvailable then
      container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
      container.withInitScript("schema/postgres.sql")
      container.start()
      // Merge overrides with the UNRESOLVED application.conf, then resolve — so
      // the JDBC url substitution picks up the container's host/port. (Using
      // ConfigFactory.load() here would pre-resolve the url with the defaults.)
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
      testKit = ActorTestKit("pgit", config)

  override def afterAll(): Unit =
    if testKit != null then testKit.shutdownTestKit()
    if container != null then container.stop()

  "Persistence against PostgreSQL" should {
    "durably persist an event and recover state across an entity restart" taggedAs PostgresIT in {
      assume(dockerAvailable, "Docker is not available")

      val subId   = SubscriptionId.from("sub-it").toOption.get
      val topicId = TopicId.from("orders").toOption.get
      val probe   = testKit.createTestProbe[CommandReply]()

      // First incarnation: create the subscription (writes to Postgres).
      val first = testKit.spawn(SubscriptionEntity(subId))
      first ! SubscriptionEntityCommand.Submit(SubscriptionCommand.CreateSubscription(subId, topicId), probe.ref)
      probe.expectMessage(20.seconds,CommandReply.Accepted)
      testKit.stop(first)

      // Second incarnation recovers from the journal: creating again is rejected,
      // proving the create event was durably persisted and replayed from Postgres.
      val second = testKit.spawn(SubscriptionEntity(subId))
      second ! SubscriptionEntityCommand.Submit(SubscriptionCommand.CreateSubscription(subId, topicId), probe.ref)
      probe.expectMessage(20.seconds,CommandReply.Rejected(Rejection.SubscriptionAlreadyExists))
    }

    "deduplicate a repeated idempotency key across a topic-entity restart within the window" taggedAs PostgresIT in {
      assume(dockerAvailable, "Docker is not available")

      val topicId = TopicId.from("dedup-it").toOption.get
      val probe   = testKit.createTestProbe[CommandReply]()
      val t0      = Instant.parse("2026-07-07T00:00:00Z")
      def keyed(id: String, at: Instant) =
        Message.from(MessageId.from(id).toOption.get, "x".getBytes, Map.empty, at, idempotencyKey = Some("abc")).toOption.get

      // First incarnation: create the topic and publish a keyed message.
      val first    = testKit.spawn(TopicEntity(topicId, dedup = DedupConfig(1.hour)))
      first ! TopicEntityCommand.Submit(TopicCommand.CreateTopic(topicId), probe.ref)
      probe.expectMessage(20.seconds, CommandReply.Accepted)
      val original = keyed("m-1", t0)
      first ! TopicEntityCommand.Submit(TopicCommand.Publish(original), probe.ref)
      probe.expectMessage(20.seconds, CommandReply.Published(original.id, deduplicated = false))
      testKit.stop(first)

      // Second incarnation rebuilds the seen-set from the journal, so a retry with
      // the same key within the window is deduplicated to the original id.
      val second = testKit.spawn(TopicEntity(topicId, dedup = DedupConfig(1.hour)))
      second ! TopicEntityCommand.Submit(TopicCommand.Publish(keyed("m-2", t0.plusSeconds(60))), probe.ref)
      probe.expectMessage(20.seconds, CommandReply.Published(original.id, deduplicated = true))
    }

    "lease, expire, redeliver, and finally dead-letter across attempts, surviving restarts" taggedAs PostgresIT in {
      assume(dockerAvailable, "Docker is not available")

      val subId    = SubscriptionId.from("sub-redeliver").toOption.get
      val topicId  = TopicId.from("orders").toOption.get
      val ackId    = AckId.from("ack-redeliver").toOption.get
      val message  = Message.from(MessageId.from("m-redeliver").toOption.get, "body".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z")).toOption.get
      val deadline = 30.seconds
      val t0       = Instant.parse("2026-07-07T12:00:00Z")
      val maxAtt   = 3

      val reply = testKit.createTestProbe[CommandReply]()
      val pull  = testKit.createTestProbe[Option[List[PulledMessage]]]()
      def submit(entity: org.apache.pekko.actor.typed.ActorRef[SubscriptionEntityCommand], cmd: SubscriptionCommand): Unit =
        entity ! SubscriptionEntityCommand.Submit(cmd, reply.ref)
        reply.expectMessage(20.seconds, CommandReply.Accepted)
      def doPull(entity: org.apache.pekko.actor.typed.ActorRef[SubscriptionEntityCommand], now: Instant): Option[List[PulledMessage]] =
        entity ! SubscriptionEntityCommand.Pull(10, deadline, now, pull.ref)
        pull.receiveMessage(20.seconds)

      // Publish (record delivery) → the message is AVAILABLE.
      val e1 = testKit.spawn(SubscriptionEntity(subId))
      submit(e1, SubscriptionCommand.CreateSubscription(subId, topicId))
      submit(e1, SubscriptionCommand.RecordDelivery(ackId, message))

      // Pull leases it; a second pull within the deadline sees nothing.
      doPull(e1, t0) shouldBe Some(List(PulledMessage(ackId, message)))
      doPull(e1, t0.plusSeconds(1)) shouldBe Some(Nil)

      // No ack: the sweep expires the overdue lease (attempt 1 < max) → redeliverable.
      submit(e1, SubscriptionCommand.ExpireAckDeadline(ackId, t0.plusSeconds(31), maxAtt))
      doPull(e1, t0.plusSeconds(31)) shouldBe Some(List(PulledMessage(ackId, message)))

      // Restart mid-flight: the attempt count and lease survive recovery.
      testKit.stop(e1)
      val e2 = testKit.spawn(SubscriptionEntity(subId))
      submit(e2, SubscriptionCommand.ExpireAckDeadline(ackId, t0.plusSeconds(62), maxAtt)) // attempt 2 < max
      doPull(e2, t0.plusSeconds(62)) shouldBe Some(List(PulledMessage(ackId, message)))

      // Third expiry reaches the limit → dead-lettered and removed; stays gone.
      submit(e2, SubscriptionCommand.ExpireAckDeadline(ackId, t0.plusSeconds(200), maxAtt)) // attempt 3 == max
      doPull(e2, t0.plusSeconds(300)) shouldBe Some(Nil)

      testKit.stop(e2)
      val e3 = testKit.spawn(SubscriptionEntity(subId))
      doPull(e3, t0.plusSeconds(400)) shouldBe Some(Nil) // dead-lettered message did not come back
    }

    "snapshot and retain: bound the journal and recover state across a restart" taggedAs PostgresIT in {
      assume(dockerAvailable, "Docker is not available")

      val subId   = SubscriptionId.from("sub-snapshot-it").toOption.get
      val topicId = TopicId.from("orders").toOption.get
      val msg     = Message.from(MessageId.from("m-snap").toOption.get, "body".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z")).toOption.get
      val reply   = testKit.createTestProbe[CommandReply]()
      val pull    = testKit.createTestProbe[Option[List[PulledMessage]]]()
      // Aggressive retention so deletion is easy to observe against the real journal.
      val retention = RetentionConfig(snapshotEveryEvents = 2, keepNSnapshots = 1)
      val pid       = SubscriptionEntity.persistenceId(subId).id
      val delivered = 12

      val e1 = testKit.spawn(SubscriptionEntity(subId, retention))
      e1 ! SubscriptionEntityCommand.Submit(SubscriptionCommand.CreateSubscription(subId, topicId), reply.ref)
      reply.expectMessage(20.seconds, CommandReply.Accepted)
      (1 to delivered).foreach { i =>
        val ackId = AckId.from(s"ack-$i").toOption.get
        e1 ! SubscriptionEntityCommand.Submit(SubscriptionCommand.RecordDelivery(ackId, msg), reply.ref)
        reply.expectMessage(20.seconds, CommandReply.Accepted)
      }

      // The journal is bounded: old events were deleted on snapshot, so far fewer
      // than the (1 create + `delivered`) events persisted remain in Postgres.
      journalRowCount(pid) should be < (delivered + 1)

      // Restart: the entity recovers its outstanding set from snapshot + surviving events.
      testKit.stop(e1)
      val e2 = testKit.spawn(SubscriptionEntity(subId, retention))
      e2 ! SubscriptionEntityCommand.Pull(delivered + 10, 30.seconds, Instant.parse("2026-07-07T12:00:00Z"), pull.ref)
      pull.receiveMessage(20.seconds).getOrElse(Nil).size shouldBe delivered
    }

    "expire a TTL'd message: purge it from outstanding while a no-ttl message survives" taggedAs PostgresIT in {
      assume(dockerAvailable, "Docker is not available")

      val subId   = SubscriptionId.from("sub-ttl-it").toOption.get
      val topicId = TopicId.from("orders").toOption.get
      val t0      = Instant.parse("2026-07-07T12:00:00Z")
      val ttlMsg  = Message.from(MessageId.from("m-ttl").toOption.get, "ttl".getBytes, Map.empty, t0, expireTime = Some(t0.plusSeconds(30))).toOption.get
      val plainMsg = Message.from(MessageId.from("m-plain").toOption.get, "plain".getBytes, Map.empty, t0).toOption.get
      val ackTtl   = AckId.from("ack-ttl").toOption.get
      val ackPlain = AckId.from("ack-plain").toOption.get
      val reply    = testKit.createTestProbe[CommandReply]()
      val pull     = testKit.createTestProbe[Option[List[PulledMessage]]]()

      def submit(e: org.apache.pekko.actor.typed.ActorRef[SubscriptionEntityCommand], c: SubscriptionCommand): Unit =
        e ! SubscriptionEntityCommand.Submit(c, reply.ref); reply.expectMessage(20.seconds, CommandReply.Accepted)

      val e1 = testKit.spawn(SubscriptionEntity(subId))
      submit(e1, SubscriptionCommand.CreateSubscription(subId, topicId))
      submit(e1, SubscriptionCommand.RecordDelivery(ackTtl, ttlMsg))
      submit(e1, SubscriptionCommand.RecordDelivery(ackPlain, plainMsg))

      // Expire the TTL'd message (as the sweeper would), past its expireTime.
      submit(e1, SubscriptionCommand.ExpireMessage(ackTtl, t0.plusSeconds(31)))

      // Pull after expiry returns only the surviving no-ttl message; the expired one is gone.
      e1 ! SubscriptionEntityCommand.Pull(10, 30.seconds, t0.plusSeconds(31), pull.ref)
      pull.receiveMessage(20.seconds) shouldBe Some(List(PulledMessage(ackPlain, plainMsg)))

      // Purge survives restart: the expired message is gone, the surviving one recovers.
      testKit.stop(e1)
      val e2 = testKit.spawn(SubscriptionEntity(subId))
      e2 ! SubscriptionEntityCommand.Submit(SubscriptionCommand.Acknowledge(ackTtl), reply.ref)
      reply.expectMessage(20.seconds, CommandReply.Rejected(Rejection.UnknownAckId(ackTtl))) // expired → gone
      e2 ! SubscriptionEntityCommand.Submit(SubscriptionCommand.Acknowledge(ackPlain), reply.ref)
      reply.expectMessage(20.seconds, CommandReply.Accepted) // survived and recovered
    }
  }

  /** Count journaled events for a persistence id directly in Postgres. */
  private def journalRowCount(persistenceId: String): Int =
    val conn = DriverManager.getConnection(container.getJdbcUrl, container.getUsername, container.getPassword)
    try
      val ps = conn.prepareStatement("SELECT count(*) FROM event_journal WHERE persistence_id = ?")
      ps.setString(1, persistenceId)
      val rs = ps.executeQuery()
      rs.next()
      rs.getInt(1)
    finally conn.close()
