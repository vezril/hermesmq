package me.cference.hermesmq.http

import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.HttpResponse
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat

/** Liveness status payload returned by `GET /health`. */
final case class HealthStatus(status: String, service: String, version: String)

object HealthJsonProtocol extends DefaultJsonProtocol:
  given RootJsonFormat[HealthStatus] = jsonFormat3(HealthStatus.apply)

/** HTTP routes for health checks.
  *
  *   - `/health` is liveness — reports the process is up and never depends on an
  *     external system (there is no persistence in this feature).
  *   - `/health/ready` is readiness — reports `200` only once the service is
  *     ready to serve, `503` otherwise. During shutdown the readiness flag is
  *     cleared first so an orchestrator drains traffic before the process exits.
  *
  * @param version   the running service version (from sbt-dynver at boot)
  * @param readiness a thread-safe probe of current readiness
  */
final class HealthRoutes(version: String, readiness: () => Boolean):
  import HealthJsonProtocol.given
  import SprayJsonSupport.*

  private val liveness = HealthStatus(status = "UP", service = "hermesmq", version = version)

  val routes: Route =
    concat(
      path("health") {
        concat(
          get {
            complete(liveness)
          },
          // Lightweight probes may use HEAD; reply 200 with an empty body.
          head {
            complete(HttpResponse(StatusCodes.OK))
          }
        )
      },
      path("health" / "ready") {
        get {
          if readiness() then complete(StatusCodes.OK)
          else complete(HttpResponse(StatusCodes.ServiceUnavailable))
        }
      }
    )

object HealthRoutes:
  def apply(version: String, readiness: () => Boolean): HealthRoutes =
    new HealthRoutes(version, readiness)
