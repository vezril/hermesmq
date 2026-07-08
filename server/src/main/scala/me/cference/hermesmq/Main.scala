package me.cference.hermesmq

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.cluster.{ClusterConfig, ShardedSubscriptionService, ShardedTopicService, SubscriptionSharding, TopicSharding}
import me.cference.hermesmq.config.{DbConfig, ServiceConfig}
import me.cference.hermesmq.delivery.{DeliveryHandler, DeliveryProjection, JdbcTopicSubscriptionsRepository, SubscriptionIndexProjection}
import me.cference.hermesmq.domain.AckDeadline
import me.cference.hermesmq.http.{HttpServer, PubSubRoutes, Readiness, TopicAdminRoutes}
import me.cference.hermesmq.persistence.PersistenceHealth
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, ShardedDaemonProcess}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Entry point for the HermesMQ service.
  *
  * Runs as a Pekko cluster: Topic/Subscription entities are sharded (one writer
  * per id cluster-wide), and the delivery projection runs as a single
  * cluster-wide instance. A single node forms a one-node cluster.
  */
object Main:

  private val DefaultAckDeadline = AckDeadline.from(30.seconds).toOption.get

  def main(args: Array[String]): Unit =
    val rawConfig = ConfigFactory.load()

    val loaded =
      for
        serviceConfig <- ServiceConfig.from(rawConfig)
        dbConfig      <- DbConfig.from(rawConfig)
      yield (serviceConfig, dbConfig)

    loaded match
      case Left(error) =>
        System.err.println(s"Configuration error: ${error.message}")
        sys.exit(1)

      case Right((serviceConfig, dbConfig)) =>
        val persistenceHealth = PersistenceHealth(dbConfig)
        val readiness         = Readiness(persistenceHealthy = () => persistenceHealth.healthy())

        val root = Behaviors.setup[Nothing] { ctx =>
          given Timeout = Timeout(5.seconds)
          import ctx.executionContext

          // Cluster Sharding: one writer per id across the cluster.
          val sharding = ClusterSharding(ctx.system)
          TopicSharding.init(sharding)
          SubscriptionSharding.init(sharding)
          val topicService        = ShardedTopicService(sharding)
          val subscriptionService = ShardedSubscriptionService(sharding)

          // Durable, cluster-shared topic→subscriptions read model.
          val subscriptionsRepo = JdbcTopicSubscriptionsRepository(dbConfig)

          // Two single cluster-wide projections: maintain the index, and fan out.
          ShardedDaemonProcess(ctx.system).init(
            name = "subscription-index-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(SubscriptionIndexProjection(ctx.system, dbConfig, subscriptionsRepo)),
            stopMessage = ProjectionBehavior.Stop
          )
          val deliveryHandler = DeliveryHandler(subscriptionsRepo, subscriptionService, DefaultAckDeadline)
          ShardedDaemonProcess(ctx.system).init(
            name = "delivery-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(DeliveryProjection(ctx.system, dbConfig, deliveryHandler)),
            stopMessage = ProjectionBehavior.Stop
          )

          val apiRoutes =
            TopicAdminRoutes(topicService).routes ~ PubSubRoutes(topicService, subscriptionService).routes

          HttpServer.start(ctx.system, serviceConfig, AppInfo.Version, readiness, apiRoutes).onComplete {
            case Success(binding) =>
              ctx.log.info("HermesMQ {} listening on {}", AppInfo.Version, binding.localAddress)
            case Failure(ex) =>
              ctx.log.error("Failed to bind HTTP server; shutting down", ex)
              ctx.system.terminate()
          }

          Behaviors.empty
        }

        // Activate the cluster provider (the base config keeps the default so
        // tests don't cluster); the system name must match the seed addresses.
        val system = ActorSystem[Nothing](root, "hermesmq", ClusterConfig.activate(rawConfig))
        Await.result(system.whenTerminated, Duration.Inf)
