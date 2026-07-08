package me.cference.hermesmq.grpc

import me.cference.hermesmq.config.GrpcConfig
import org.apache.pekko.Done
import org.apache.pekko.actor.{ClassicActorSystemProvider, CoordinatedShutdown}
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.grpc.scaladsl.ServiceHandler
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.http.scaladsl.settings.ServerSettings

import scala.concurrent.Future

/** Binds the gRPC services to an HTTP/2 (h2c) port, alongside the REST server.
  * HTTP/2 is enabled only for this bind, so the REST endpoint stays HTTP/1.1.
  * Kept separate from [[me.cference.hermesmq.Main]] so the bind lifecycle is
  * testable on an ephemeral port.
  */
object GrpcServer:

  def start(
      system: ActorSystem[?],
      config: GrpcConfig,
      topicAdmin: TopicAdminService,
      pubSub: PubSubService
  ): Future[ServerBinding] =
    given ClassicActorSystemProvider = system
    import system.executionContext

    val handler: HttpRequest => Future[HttpResponse] =
      ServiceHandler.concatOrNotFound(
        TopicAdminServiceHandler.partial(topicAdmin),
        PubSubServiceHandler.partial(pubSub)
      )

    // Enable HTTP/2 for this bind only (cleartext h2c with protocol detection).
    val base     = ServerSettings(system.classicSystem)
    val settings = base.withPreviewServerSettings(base.previewServerSettings.withEnableHttp2(true))

    Http().newServerAt(config.host, config.port).withSettings(settings).bind(handler).map { binding =>
      system.log.info("gRPC server bound to {}", binding.localAddress)
      CoordinatedShutdown(system.classicSystem).addTask(
        CoordinatedShutdown.PhaseServiceUnbind,
        "grpc-unbind"
      ) { () =>
        binding.unbind().map(_ => Done)
      }
      binding
    }
