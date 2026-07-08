package me.cference.hermesmq

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.config.{DbConfig, ServiceConfig}
import me.cference.hermesmq.delivery.{DeliveryHandler, DeliveryProjection, TopicSubscriptionsIndex}
import me.cference.hermesmq.domain.AckDeadline
import me.cference.hermesmq.http.{HttpServer, PubSubRoutes, Readiness, TopicAdminRoutes}
import me.cference.hermesmq.persistence.*
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.projection.ProjectionBehavior
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Entry point for the HermesMQ service.
  *
  * A root behavior spawns the topic and subscription registries, the delivery
  * projection (fan-out of published messages to subscriptions), and binds the
  * HTTP server (health + topic admin + pub/sub routes).
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
          given system: ActorSystem[?] = ctx.system
          given Timeout                = Timeout(5.seconds)
          import ctx.executionContext

          val topicRegistry = ctx.spawn(TopicRegistry(), "topic-registry")
          val topicService  = RegistryTopicService(topicRegistry)

          val subscriptionRegistry = ctx.spawn(SubscriptionRegistry(), "subscription-registry")
          val subscriptionService  = RegistrySubscriptionService(subscriptionRegistry)

          val index           = TopicSubscriptionsIndex()
          val deliveryHandler = DeliveryHandler(index, subscriptionService, DefaultAckDeadline)

          // Fan-out projection: delivers published messages to subscriptions.
          ctx.spawn(ProjectionBehavior(DeliveryProjection(ctx.system, dbConfig, deliveryHandler)), "delivery-projection")

          val apiRoutes =
            TopicAdminRoutes(topicService).routes ~ PubSubRoutes(topicService, subscriptionService, index).routes

          HttpServer.start(ctx.system, serviceConfig, AppInfo.Version, readiness, apiRoutes).onComplete {
            case Success(binding) =>
              ctx.log.info("HermesMQ {} listening on {}", AppInfo.Version, binding.localAddress)
            case Failure(ex) =>
              ctx.log.error("Failed to bind HTTP server; shutting down", ex)
              ctx.system.terminate()
          }

          Behaviors.empty
        }

        val system = ActorSystem[Nothing](root, "hermesmq", rawConfig)
        Await.result(system.whenTerminated, Duration.Inf)
