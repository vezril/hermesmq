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
  }
