package me.cference.hermesmq.persistence

import com.typesafe.config.{Config, ConfigFactory}
import me.cference.hermesmq.config.DbConfig
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

/** End-to-end tests for boot-time schema self-migration against a **fresh**
  * PostgreSQL (Testcontainers) with NO init script — the migrator provisions the
  * schema itself. Tagged [[PostgresIT]] and excluded from the default run.
  */
final class SchemaMigrationIntegrationSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll:

  private val dockerAvailable =
    try DockerClientFactory.instance().isDockerAvailable
    catch case _: Throwable => false

  private var container: PostgreSQLContainer[?] = _
  private var config: Config                    = _
  private var dbConfig: DbConfig                = _
  private var testKit: ActorTestKit             = _

  override def beforeAll(): Unit =
    if dockerAvailable then
      // Deliberately NO withInitScript: the database starts empty and the
      // migrator is responsible for creating the schema.
      container = new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
      container.start()
      config = ConfigFactory
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
      testKit = ActorTestKit("schema-mig-it", config)

  override def afterAll(): Unit =
    if testKit != null then testKit.shutdownTestKit()
    if container != null then container.stop()

  private def tableExists(name: String): Boolean =
    val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    try
      val rs = conn.createStatement().executeQuery(s"SELECT to_regclass('public.$name') IS NOT NULL AS present")
      rs.next() && rs.getBoolean("present")
    finally conn.close()

  "Schema self-migration against a fresh PostgreSQL" should {
    "provision the journal + read-model tables and be idempotent on re-run" taggedAs PostgresIT in {
      assume(dockerAvailable, "Docker is not available")

      tableExists("event_journal") shouldBe false // empty database to start
      SchemaMigrator.migrate(dbConfig) shouldBe Right(())
      tableExists("event_journal") shouldBe true
      tableExists("snapshot") shouldBe true
      tableExists("topic_stats") shouldBe true
      tableExists("expiring_messages") shouldBe true

      // Re-running over the now-provisioned schema is a harmless no-op.
      SchemaMigrator.migrate(dbConfig) shouldBe Right(())
      tableExists("event_journal") shouldBe true
    }

    "support create + publish end-to-end against a migrator-provisioned database" taggedAs PostgresIT in {
      assume(dockerAvailable, "Docker is not available")

      SchemaMigrator.migrate(dbConfig) shouldBe Right(())
      val topicId = TopicId.from("mig-e2e").toOption.get
      val probe   = testKit.createTestProbe[CommandReply]()
      val entity  = testKit.spawn(TopicEntity(topicId))

      entity ! TopicEntityCommand.Submit(TopicCommand.CreateTopic(topicId), probe.ref)
      probe.expectMessage(20.seconds, CommandReply.Accepted)

      val msg = Message
        .from(MessageId.from("m-1").toOption.get, "hi".getBytes, Map.empty, Instant.parse("2026-07-07T00:00:00Z"))
        .toOption
        .get
      entity ! TopicEntityCommand.Submit(TopicCommand.Publish(msg), probe.ref)
      probe.expectMessage(20.seconds, CommandReply.Published(msg.id, deduplicated = false))
    }
  }
