package me.cference.hermesmq.http

import me.cference.hermesmq.config.ServiceConfig
import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route

import scala.concurrent.Future

/** Binds the health routes to an HTTP port and wires readiness + graceful
  * unbind. Kept separate from [[Main]] so the bind/serve/unbind lifecycle is
  * testable on an ephemeral port without a full process.
  */
object HttpServer:

  /** Start the HTTP server.
    *
    * On a successful bind the `readiness` flag is flipped to `true`. A
    * CoordinatedShutdown task (service-unbind phase) flips it back to `false`
    * and unbinds the server, so probes report "not ready" before the port is
    * released during shutdown.
    */
  def start(
      system: ActorSystem[?],
      config: ServiceConfig,
      version: String,
      readiness: Readiness,
      apiRoutes: Route = reject
  ): Future[ServerBinding] =
    given classicSystem: org.apache.pekko.actor.ActorSystem = system.classicSystem
    import system.executionContext

    val routes = HealthRoutes(version, () => readiness.isReady).routes ~ apiRoutes

    val bindingF = Http().newServerAt(config.host, config.port).bind(routes)

    bindingF.map { binding =>
      readiness.markBound()
      system.log.info("HTTP server bound to {}", binding.localAddress)

      CoordinatedShutdown(classicSystem).addTask(
        CoordinatedShutdown.PhaseServiceUnbind,
        "health-http-unbind"
      ) { () =>
        readiness.markUnbound()
        binding.unbind().map(_ => Done)
      }
      binding
    }
