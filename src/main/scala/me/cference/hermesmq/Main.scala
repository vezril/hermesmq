package me.cference.hermesmq

import com.typesafe.config.ConfigFactory
import me.cference.hermesmq.config.ServiceConfig
import me.cference.hermesmq.http.HttpServer
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

/** Entry point for the HermesMQ service.
  *
  * Boots a typed actor system, loads and validates configuration, and binds the
  * HTTP health server. Fails fast with a non-zero exit on invalid config or a
  * bind failure; shuts down gracefully on SIGTERM/SIGINT via CoordinatedShutdown.
  */
object Main:

  def main(args: Array[String]): Unit =
    val rawConfig = ConfigFactory.load()

    ServiceConfig.from(rawConfig) match
      case Left(error) =>
        System.err.println(s"Configuration error: ${error.message}")
        sys.exit(1)

      case Right(serviceConfig) =>
        val system = ActorSystem(Behaviors.empty[Nothing], "hermesmq", rawConfig)
        val readiness = new AtomicBoolean(false)
        import system.executionContext

        HttpServer.start(system, serviceConfig, AppInfo.Version, readiness).onComplete {
          case Success(binding) =>
            system.log.info("HermesMQ {} listening on {}", AppInfo.Version, binding.localAddress)
          case Failure(ex) =>
            system.log.error("Failed to bind HTTP server; shutting down", ex)
            system.terminate()
            sys.exit(1)
        }

        // Keep the main thread alive until the actor system terminates
        // (SIGTERM/SIGINT trigger CoordinatedShutdown, which completes this).
        Await.result(system.whenTerminated, Duration.Inf)
