package me.cference.hermesmq.persistence

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.domain.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

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
  }
