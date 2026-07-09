package me.cference.hermesmq

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.cluster.{ClusterConfig, ShardedSubscriptionService, ShardedTopicService, SubscriptionSharding, TopicSharding}
import me.cference.hermesmq.auth.{Authenticator, TenantScope, TenantScopedSubscriptionService, TenantScopedTopicService}
import me.cference.hermesmq.config.{AuthConfig, DbConfig, DedupConfig, GrpcConfig, RedeliveryConfig, RetentionConfig, ServiceConfig, StreamConfig, TtlConfig}
import me.cference.hermesmq.delivery.{DeadLetterProjection, DeliveryHandler, DeliveryProjection, ExpiringMessageProjection, JdbcExpiringMessageRepository, JdbcOutstandingLeaseRepository, JdbcTopicSubscriptionsRepository, LeaseProjection, RedeliverySweeper, SubscriptionIndexProjection, TtlSweeper}
import me.cference.hermesmq.grpc.{GrpcServer, PubSubPowerApi, TopicAdminPowerApi}
import me.cference.hermesmq.http.{Auth, HttpServer, PubSubRoutes, Readiness, TopicAdminRoutes}
import me.cference.hermesmq.observability.{JdbcSubscriptionStatsRepository, JdbcTopicStatsRepository, ObservabilityRoutes, SubscriptionStatsProjection, TopicStatsProjection}
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
        authConfig       <- AuthConfig.from(rawConfig)
        streamConfig     <- StreamConfig.from(rawConfig)
        ttlConfig        <- TtlConfig.from(rawConfig)
        dedupConfig      <- DedupConfig.from(rawConfig)
      yield (serviceConfig, grpcConfig, dbConfig, redeliveryConfig, retentionConfig, authConfig, streamConfig, ttlConfig, dedupConfig)

    loaded match
      case Left(error) =>
        System.err.println(s"Configuration error: ${error.message}")
        sys.exit(1)

      case Right((serviceConfig, grpcConfig, dbConfig, redeliveryConfig, retentionConfig, authConfig, streamConfig, ttlConfig, dedupConfig)) =>
        val persistenceHealth = PersistenceHealth(dbConfig)
        val readiness         = Readiness(persistenceHealthy = () => persistenceHealth.healthy())

        val root = Behaviors.setup[Nothing] { ctx =>
          given Timeout = Timeout(5.seconds)
          import ctx.executionContext

          // Cluster Sharding: one writer per id across the cluster.
          val sharding = ClusterSharding(ctx.system)
          TopicSharding.init(sharding, retentionConfig, dedupConfig)
          SubscriptionSharding.init(sharding, retentionConfig)
          val topicService        = ShardedTopicService(sharding)
          val subscriptionService = ShardedSubscriptionService(sharding, redeliveryConfig.ackDeadline)

          // Durable, cluster-shared read models.
          val subscriptionsRepo = JdbcTopicSubscriptionsRepository(dbConfig)
          val leaseRepo         = JdbcOutstandingLeaseRepository(dbConfig)
          val subStatsRepo      = JdbcSubscriptionStatsRepository(dbConfig)
          val topicStatsRepo    = JdbcTopicStatsRepository(dbConfig)
          val expiringRepo      = JdbcExpiringMessageRepository(dbConfig)

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

          // TTL: track outstanding messages that carry an expireTime, and a single
          // cluster-wide sweeper that purges them once past their TTL.
          ShardedDaemonProcess(ctx.system).init(
            name = "expiry-index-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(ExpiringMessageProjection(ctx.system, dbConfig, expiringRepo)),
            stopMessage = ProjectionBehavior.Stop
          )
          ShardedDaemonProcess(ctx.system).init(
            name = "ttl-sweeper",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => TtlSweeper(expiringRepo, subscriptionService, redeliveryConfig.sweepInterval),
            stopMessage = TtlSweeper.Stop
          )

          // Single cluster-wide observability projections (exactly-once so counters
          // are not double-counted on replay): per-subscription and per-topic stats.
          ShardedDaemonProcess(ctx.system).init(
            name = "subscription-stats-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(SubscriptionStatsProjection(ctx.system, dbConfig)),
            stopMessage = ProjectionBehavior.Stop
          )
          ShardedDaemonProcess(ctx.system).init(
            name = "topic-stats-projection",
            numberOfInstances = 1,
            behaviorFactory = (_: Int) => ProjectionBehavior(TopicStatsProjection(ctx.system, dbConfig)),
            stopMessage = ProjectionBehavior.Stop
          )

          // Authentication + multi-tenancy boundary.
          val authenticator = Authenticator(authConfig.keys)
          val tenantScope   = new TenantScope(authConfig.defaultTenant)

          // REST: /metrics is public; /v1 requires auth and is tenant-scoped.
          val observability = ObservabilityRoutes(subStatsRepo, topicStatsRepo)
          val apiRoutes =
            observability.metricsRoute ~
              Auth.authenticate(authenticator, authConfig) { principal =>
                val scopedTopics = TenantScopedTopicService(topicService, tenantScope, principal.tenant)
                val scopedSubs   = TenantScopedSubscriptionService(subscriptionService, tenantScope, principal.tenant)
                TopicAdminRoutes(scopedTopics, principal).routes ~
                  PubSubRoutes(scopedTopics, scopedSubs, ttlConfig).routes ~
                  observability.listings(principal, tenantScope)
              }

          HttpServer.start(ctx.system, serviceConfig, AppInfo.Version, readiness, apiRoutes).onComplete {
            case Success(binding) =>
              ctx.log.info("HermesMQ {} listening on {}", AppInfo.Version, binding.localAddress)
            case Failure(ex) =>
              ctx.log.error("Failed to bind HTTP server; shutting down", ex)
              ctx.system.terminate()
          }

          // gRPC endpoint (HTTP/2): metadata-aware power APIs authenticate + tenant-scope.
          given org.apache.pekko.actor.ActorSystem = ctx.system.classicSystem
          val topicAdminGrpc = TopicAdminPowerApi(topicService, authenticator, tenantScope, authConfig)
          val pubSubGrpc     = PubSubPowerApi(topicService, subscriptionService, authenticator, tenantScope, authConfig, streamConfig, ttlConfig)
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
