package me.cference.hermesmq.observability

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.projection.ProjectionBehavior
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

import java.time.Instant
import scala.concurrent.Await
import scala.concurrent.duration.*

/** End-to-end observability against a real PostgreSQL (Testcontainers): drive the
  * entities, run the stats projections, and assert the read models reflect
  * throughput, backlog, redelivery, and dead-letter. Tagged [[PostgresIT]].
  */
final class ObservabilityIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with Eventually:

  private val dockerAvailable =
    try DockerClientFactory.instance().isDockerAvailable
    catch case _: Throwable => false

  private var container: PostgreSQLContainer[?] = _
  private var testKit: ActorTestKit             = _
  private var dbConfig: DbConfig                = _

  override def beforeAll(): Unit =
    if dockerAvailable then
      container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
      container.withInitScript("schema/postgres.sql")
      container.start()
      val config = ConfigFactory
        .parseString(
          s"""hermesmq.db.host = "${container.getHost}"
             |hermesmq.db.port = ${container.getMappedPort(5432)}
             |hermesmq.db.database = "${container.getDatabaseName}"
             |hermesmq.db.user = "${container.getUsername}"
             |hermesmq.db.password = "${container.getPassword}"
             |""".stripMargin
        )
        .withFallback(ConfigFactory.parseResources("application.conf"))
        .withFallback(ConfigFactory.defaultReference())
        .resolve()
      testKit = ActorTestKit("obs-it", config)
      dbConfig = DbConfig.from(config).toOption.get

  override def afterAll(): Unit =
    Option(testKit).foreach(_.shutdownTestKit())
    Option(container).foreach(_.stop())

  "The observability pipeline" should {
    "reflect throughput, backlog, redelivery and dead-letter in the read models" taggedAs PostgresIT in {
      val _ = assume(dockerAvailable, "Docker is not available")

      val system                                     = testKit.system
      given scala.concurrent.ExecutionContext        = system.executionContext

      // Run the two stats projections.
      val _ = testKit.spawn(ProjectionBehavior(TopicStatsProjection(system, dbConfig)))
      val _ = testKit.spawn(ProjectionBehavior(SubscriptionStatsProjection(system, dbConfig)))

      val topicId = TopicId.from("orders").toOption.get
      val subId   = SubscriptionId.from("s-obs").toOption.get
      val reply   = testKit.createTestProbe[CommandReply]()
      val pull    = testKit.createTestProbe[Option[List[PulledMessage]]]()
      def msg(i: Int) = Message.from(MessageId.from(s"m-$i").toOption.get, s"b$i".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z")).toOption.get
      def ack(i: Int) = AckId.from(s"ack-$i").toOption.get
      val t0 = Instant.parse("2026-07-07T12:00:00Z")

      def topicSubmit(e: ActorRef[TopicEntityCommand], c: TopicCommand): Unit =
        e ! TopicEntityCommand.Submit(c, reply.ref); val _ = reply.expectMessage(20.seconds, CommandReply.Accepted)
      def subSubmit(e: ActorRef[SubscriptionEntityCommand], c: SubscriptionCommand): Unit =
        e ! SubscriptionEntityCommand.Submit(c, reply.ref); val _ = reply.expectMessage(20.seconds, CommandReply.Accepted)

      // Topic: create + publish 3.
      val te = testKit.spawn(TopicEntity(topicId))
      topicSubmit(te, TopicCommand.CreateTopic(topicId))
      (1 to 3).foreach(i => topicSubmit(te, TopicCommand.Publish(msg(i))))

      // Subscription: create, deliver 4, ack #1, lease the rest, then redeliver #2 and dead-letter #3.
      val se = testKit.spawn(SubscriptionEntity(subId))
      subSubmit(se, SubscriptionCommand.CreateSubscription(subId, topicId))
      (1 to 4).foreach(i => subSubmit(se, SubscriptionCommand.RecordDelivery(ack(i), msg(i))))
      subSubmit(se, SubscriptionCommand.Acknowledge(ack(1)))                 // backlog 4 → 3
      se ! SubscriptionEntityCommand.Pull(10, 30.seconds, t0, pull.ref); val _ = pull.receiveMessage(20.seconds) // lease 2,3,4
      subSubmit(se, SubscriptionCommand.ExpireAckDeadline(ack(2), t0.plusSeconds(31), maxAttempts = 5))  // redelivery
      subSubmit(se, SubscriptionCommand.ExpireAckDeadline(ack(3), t0.plusSeconds(31), maxAttempts = 1))  // dead-letter → backlog 3 → 2

      val subRepo   = JdbcSubscriptionStatsRepository(dbConfig)
      val topicRepo = JdbcTopicStatsRepository(dbConfig)

      eventually(timeout(Span(20, Seconds)), interval(Span(1, Seconds))) {
        val ts = Await.result(topicRepo.list(), 5.seconds).find(_.topicId == topicId).getOrElse(fail("topic stats missing"))
        val _ = ts.publishedTotal shouldBe 3

        val ss = Await.result(subRepo.list(), 5.seconds).find(_.subscriptionId == subId).getOrElse(fail("subscription stats missing"))
        val _ = ss.backlog shouldBe 2
        val _ = ss.redeliveredTotal shouldBe 1
        ss.deadLetteredTotal shouldBe 1
      }
    }
  }
