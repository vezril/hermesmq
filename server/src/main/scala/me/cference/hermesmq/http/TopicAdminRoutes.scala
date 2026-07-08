package me.cference.hermesmq.http

import me.cference.hermesmq.auth.{Principal, TenantScope}
import me.cference.hermesmq.domain.{Rejection, TopicCommand, TopicId}
import me.cference.hermesmq.persistence.{CommandReply, TopicService, TopicSnapshot}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.Future
import scala.util.{Failure, Success}

/** JSON request/response models for the topic admin API. */
final case class CreateTopicRequest(topicId: String, labels: Option[Map[String, String]])
final case class UpdateTopicRequest(labels: Map[String, String])
final case class TopicResponse(topicId: String, labels: Map[String, String])

object TopicAdminJson extends DefaultJsonProtocol:
  given RootJsonFormat[CreateTopicRequest] = jsonFormat2(CreateTopicRequest.apply)
  given RootJsonFormat[UpdateTopicRequest] = jsonFormat1(UpdateTopicRequest.apply)
  given RootJsonFormat[TopicResponse]      = jsonFormat2(TopicResponse.apply)

/** REST admin endpoints for creating, reading, updating, and deleting topics.
  * Delegates to a [[TopicService]] and maps domain outcomes to HTTP statuses.
  * Write operations require the `admin` scope on the principal; the default
  * principal grants it, so single-tenant/unauthenticated use is unchanged.
  */
final class TopicAdminRoutes(service: TopicService, principal: Principal = TopicAdminRoutes.AdminPrincipal):
  import TopicAdminJson.given
  import SprayJsonSupport.*

  val routes: Route =
    pathPrefix("v1" / "topics") {
      concat(
        pathEndOrSingleSlash {
          post {
            requireAdmin {
              entity(as[CreateTopicRequest]) { req =>
                withTopicId(req.topicId) { id =>
                  completeWrite(
                    service.submit(id, TopicCommand.CreateTopic(id, req.labels.getOrElse(Map.empty))),
                    StatusCodes.Created
                  )
                }
              }
            }
          }
        },
        path(Segment) { rawId =>
          withTopicId(rawId) { id =>
            concat(
              get {
                onComplete(service.query(id)) {
                  case Success(Some(snap)) => complete(toResponse(snap))
                  case Success(None)       => complete(StatusCodes.NotFound)
                  case Failure(_)          => complete(StatusCodes.ServiceUnavailable)
                }
              },
              patch {
                requireAdmin {
                  entity(as[UpdateTopicRequest]) { req =>
                    completeWrite(service.submit(id, TopicCommand.UpdateTopic(req.labels)), StatusCodes.OK)
                  }
                }
              },
              delete {
                requireAdmin {
                  completeWrite(service.submit(id, TopicCommand.DeleteTopic), StatusCodes.NoContent)
                }
              }
            )
          }
        }
      )
    }

  /** Gate a write behind the `admin` scope; otherwise 403. */
  private def requireAdmin(inner: => Route): Route =
    if principal.hasScope("admin") then inner else complete(StatusCodes.Forbidden)

  /** Validate a raw id into a `TopicId`, rejecting the reserved separator (400). */
  private def withTopicId(raw: String)(inner: TopicId => Route): Route =
    TenantScope.validateExternalId(raw).flatMap(TopicId.from) match
      case Right(id) => inner(id)
      case Left(err) => complete(StatusCodes.BadRequest, err.message)

  /** Map a write reply to an HTTP status. */
  private def completeWrite(result: Future[CommandReply], onAccepted: StatusCode): Route =
    onComplete(result) {
      case Success(CommandReply.Accepted)                                 => complete(onAccepted)
      case Success(CommandReply.Rejected(Rejection.TopicAlreadyExists))   => complete(StatusCodes.Conflict)
      case Success(CommandReply.Rejected(Rejection.TopicNotFound))        => complete(StatusCodes.NotFound)
      case Success(CommandReply.Rejected(_))                              => complete(StatusCodes.Conflict)
      case Failure(_)                                                     => complete(StatusCodes.ServiceUnavailable)
    }

  private def toResponse(snap: TopicSnapshot): TopicResponse =
    TopicResponse(snap.topicId.value, snap.labels)

object TopicAdminRoutes:
  /** Default principal for unauthenticated/single-tenant use: admin on the default tenant. */
  val AdminPrincipal: Principal = Principal(TenantScope.DefaultTenant, Set("admin"))

  def apply(service: TopicService, principal: Principal = AdminPrincipal): TopicAdminRoutes =
    new TopicAdminRoutes(service, principal)
