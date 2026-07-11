package me.cference.hermesmq

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.auth.Authenticator
import me.cference.hermesmq.auth.TenantScope
import me.cference.hermesmq.auth.TenantScopedSubscriptionService
import me.cference.hermesmq.auth.TenantScopedTopicService
import me.cference.hermesmq.cluster.ClusterConfig
import me.cference.hermesmq.cluster.ShardedSubscriptionService
import me.cference.hermesmq.cluster.ShardedTopicService
import me.cference.hermesmq.cluster.SubscriptionSharding
import me.cference.hermesmq.cluster.TopicSharding
import me.cference.hermesmq.config.AuthConfig
import me.cference.hermesmq.config.ConsumersConfig
import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.config.DedupConfig
import me.cference.hermesmq.config.GrpcConfig
import me.cference.hermesmq.config.RedeliveryConfig
import me.cference.hermesmq.config.RetentionConfig
import me.cference.hermesmq.config.ServiceConfig
import me.cference.hermesmq.config.StreamConfig
import me.cference.hermesmq.config.TtlConfig
import me.cference.hermesmq.delivery.DeadLetterProjection
import me.cference.hermesmq.delivery.DeliveryHandler
import me.cference.hermesmq.delivery.DeliveryProjection
import me.cference.hermesmq.delivery.ExpiringMessageProjection
import me.cference.hermesmq.delivery.JdbcExpiringMessageRepository
import me.cference.hermesmq.delivery.JdbcOutstandingLeaseRepository
import me.cference.hermesmq.delivery.JdbcTopicSubscriptionsRepository
import me.cference.hermesmq.delivery.LeaseProjection
import me.cference.hermesmq.delivery.RedeliverySweeper
import me.cference.hermesmq.delivery.SubscriptionIndexProjection
import me.cference.hermesmq.delivery.TtlSweeper
import me.cference.hermesmq.grpc.GrpcServer
import me.cference.hermesmq.grpc.PubSubPowerApi
import me.cference.hermesmq.grpc.TopicAdminPowerApi
import me.cference.hermesmq.http.Auth
import me.cference.hermesmq.http.HttpServer
import me.cference.hermesmq.http.PubSubRoutes
import me.cference.hermesmq.http.Readiness
import me.cference.hermesmq.http.TopicAdminRoutes
import me.cference.hermesmq.observability.ConsumerRegistry
import me.cference.hermesmq.observability.DedupCounter
import me.cference.hermesmq.observability.JdbcSubscriptionStatsRepository
import me.cference.hermesmq.observability.JdbcTopicStatsRepository
import me.cference.hermesmq.observability.ObservabilityRoutes
import me.cference.hermesmq.observability.SubscriptionStatsProjection
import me.cference.hermesmq.observability.TopicStatsProjection
import me.cference.hermesmq.persistence.PersistenceHealth
import me.cference.hermesmq.persistence.SchemaMigrator
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.scaladsl.ShardedDaemonProcess
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.Failure
import scala.util.Success

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
        consumersConfig  <- ConsumersConfig.from(rawConfig)
      yield (serviceConfig, grpcConfig, dbConfig, redeliveryConfig, retentionConfig, authConfig, streamConfig, ttlConfig, dedupConfig, consumersConfig)

    loaded match
      case Left(error) =>
        System.err.println(s"Configuration error: ${error.message}")
        sys.exit(1)

      case Right((serviceConfig, grpcConfig, dbConfig, redeliveryConfig, retentionConfig, authConfig, streamConfig, ttlConfig, dedupConfig, consumersConfig)) =>
        // Apply the bundled schema before anything touches the database, so
        // projections and aggregates never race a missing table. Idempotent, so
        // a no-op over an already-provisioned DB. Fail fast, like a config error.
        if dbConfig.migrateOnStart then
          SchemaMigrator.migrate(dbConfig) match
            case Left(err)  => System.err.println(s"Schema migration error: ${err.message}"); sys.exit(1)
            case Right(())  => ()

        val persistenceHealth = PersistenceHealth(dbConfig)
        val readiness         = Readiness(persistenceHealthy = () => persistenceHealth.healthy())

        val root = Behaviors.setup[Nothing] { ctx =>
          given Timeout = Timeout(5.seconds)
          import ctx.executionContext

          // Cluster Sharding: one writer per id across the cluster.
          val sharding = ClusterSharding(ctx.system)
          val _ = TopicSharding.init(sharding, retentionConfig, dedupConfig)
          val _ = SubscriptionSharding.init(sharding, retentionConfig)
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

          // Shared, per-node registry of active named consumers (best-effort).
          val consumerRegistry = ConsumerRegistry(consumersConfig.activityWindow)
          val dedupCounter     = DedupCounter()

          // REST: /metrics is public; /v1 requires auth and is tenant-scoped.
          val observability = ObservabilityRoutes(subStatsRepo, topicStatsRepo, consumers = consumerRegistry, dedup = dedupCounter)
          val apiRoutes =
            observability.metricsRoute ~
              Auth.authenticate(authenticator, authConfig) { principal =>
                val scopedTopics = TenantScopedTopicService(topicService, tenantScope, principal.tenant)
                val scopedSubs   = TenantScopedSubscriptionService(subscriptionService, tenantScope, principal.tenant)
                TopicAdminRoutes(scopedTopics, principal).routes ~
                  PubSubRoutes(scopedTopics, scopedSubs, ttlConfig, consumerRegistry, dedupCounter).routes ~
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
          val pubSubGrpc     = PubSubPowerApi(topicService, subscriptionService, authenticator, tenantScope, authConfig, streamConfig, ttlConfig, consumerRegistry, dedupCounter)
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
        val _ = Await.result(system.whenTerminated, Duration.Inf)
