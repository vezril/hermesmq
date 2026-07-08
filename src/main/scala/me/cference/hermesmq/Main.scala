package me.cference.hermesmq

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.config.{DbConfig, ServiceConfig}
import me.cference.hermesmq.http.{HttpServer, Readiness, TopicAdminRoutes}
import me.cference.hermesmq.persistence.{PersistenceHealth, RegistryTopicService, TopicRegistry}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/** Entry point for the HermesMQ service.
  *
  * Loads and validates configuration, then a root behavior spawns the topic
  * registry and binds the HTTP server (health + topic admin routes). Fails fast
  * on invalid config; shuts down gracefully on SIGTERM/SIGINT.
  */
object Main:

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

          val registry = ctx.spawn(TopicRegistry(), "topic-registry")
          val service  = RegistryTopicService(registry)
          val apiRoutes = TopicAdminRoutes(service).routes

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

        // Keep the main thread alive until the actor system terminates.
        Await.result(system.whenTerminated, Duration.Inf)
