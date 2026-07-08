package me.cference.hermesmq

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.cluster.{ClusterConfig, ShardedSubscriptionService, ShardedTopicService, SubscriptionSharding, TopicSharding}
import me.cference.hermesmq.config.{DbConfig, GrpcConfig, RedeliveryConfig, RetentionConfig, ServiceConfig}
import me.cference.hermesmq.delivery.{DeadLetterProjection, DeliveryHandler, DeliveryProjection, JdbcOutstandingLeaseRepository, JdbcTopicSubscriptionsRepository, LeaseProjection, RedeliverySweeper, SubscriptionIndexProjection}
import me.cference.hermesmq.grpc.{GrpcServer, PubSubGrpcService, TopicAdminGrpcService}
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

  def main(args: Array[String]): Unit =
    val rawConfig = ConfigFactory.load()

    val loaded =
      for
        serviceConfig    <- ServiceConfig.from(rawConfig)
        grpcConfig       <- GrpcConfig.from(rawConfig)
        dbConfig         <- DbConfig.from(rawConfig)
        redeliveryConfig <- RedeliveryConfig.from(rawConfig)
        retentionConfig  <- RetentionConfig.from(rawConfig)
      yield (serviceConfig, grpcConfig, dbConfig, redeliveryConfig, retentionConfig)

    loaded match
      case Left(error) =>
        System.err.println(s"Configuration error: ${error.message}")
        sys.exit(1)

      case Right((serviceConfig, grpcConfig, dbConfig, redeliveryConfig, retentionConfig)) =>
        val persistenceHealth = PersistenceHealth(dbConfig)
        val readiness         = Readiness(persistenceHealthy = () => persistenceHealth.healthy())

        val root = Behaviors.setup[Nothing] { ctx =>
          given Timeout = Timeout(5.seconds)
          import ctx.executionContext

          // Cluster Sharding: one writer per id across the cluster.
          val sharding = ClusterSharding(ctx.system)
          TopicSharding.init(sharding, retentionConfig)
          SubscriptionSharding.init(sharding, retentionConfig)
          val topicService        = ShardedTopicService(sharding)
          val subscriptionService = ShardedSubscriptionService(sharding, redeliveryConfig.ackDeadline)

          // Durable, cluster-shared read models.
          val subscriptionsRepo = JdbcTopicSubscriptionsRepository(dbConfig)
          val leaseRepo         = JdbcOutstandingLeaseRepository(dbConfig)

          // Single cluster-wide projections: maintain the index, fan out deliveries,
          // track outstanding leases, and route dead-lettered messages.
          ShardedDaemonProcess(ctx.system).init(
            name = "subscription-index-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(SubscriptionIndexProjection(ctx.system, dbConfig, subscriptionsRepo)),
            stopMessage = ProjectionBehavior.Stop
          )
          val deliveryHandler = DeliveryHandler(subscriptionsRepo, subscriptionService)
          ShardedDaemonProcess(ctx.system).init(
            name = "delivery-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(DeliveryProjection(ctx.system, dbConfig, deliveryHandler)),
            stopMessage = ProjectionBehavior.Stop
          )
          ShardedDaemonProcess(ctx.system).init(
            name = "lease-index-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(LeaseProjection(ctx.system, dbConfig, leaseRepo)),
            stopMessage = ProjectionBehavior.Stop
          )
          ShardedDaemonProcess(ctx.system).init(
            name = "dead-letter-projection",
            numberOfInstances = 1,
            behaviorFactory =
              (_: Int) => ProjectionBehavior(DeadLetterProjection(ctx.system, dbConfig, topicService, redeliveryConfig.deadLetterTopic)),
            stopMessage = ProjectionBehavior.Stop
          )

          // Single cluster-wide sweeper: expires overdue leases so unacked
          // messages are redelivered (or dead-lettered at the attempt limit).
          ShardedDaemonProcess(ctx.system).init(
            name = "redelivery-sweeper",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) =>
              RedeliverySweeper(leaseRepo, subscriptionService, redeliveryConfig.sweepInterval, redeliveryConfig.maxDeliveryAttempts),
            stopMessage = RedeliverySweeper.Stop
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

          // gRPC endpoint (HTTP/2), served alongside REST over the same services.
          val topicAdminGrpc = TopicAdminGrpcService(topicService)
          val pubSubGrpc     = PubSubGrpcService(topicService, subscriptionService)
          GrpcServer.start(ctx.system, grpcConfig, topicAdminGrpc, pubSubGrpc).onComplete {
            case Success(binding) =>
              ctx.log.info("HermesMQ gRPC listening on {}", binding.localAddress)
            case Failure(ex) =>
              ctx.log.error("Failed to bind gRPC server; shutting down", ex)
              ctx.system.terminate()
          }

          Behaviors.empty
        }

        // Activate the cluster provider (the base config keeps the default so
        // tests don't cluster); the system name must match the seed addresses.
        val system = ActorSystem[Nothing](root, "hermesmq", ClusterConfig.activate(rawConfig))
        Await.result(system.whenTerminated, Duration.Inf)
