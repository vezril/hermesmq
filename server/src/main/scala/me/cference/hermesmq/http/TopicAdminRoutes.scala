package me.cference.hermesmq.http

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
  */
final class TopicAdminRoutes(service: TopicService):
  import TopicAdminJson.given
  import SprayJsonSupport.*

  val routes: Route =
    pathPrefix("v1" / "topics") {
      concat(
        pathEndOrSingleSlash {
          post {
            entity(as[CreateTopicRequest]) { req =>
              withTopicId(req.topicId) { id =>
                completeWrite(
                  service.submit(id, TopicCommand.CreateTopic(id, req.labels.getOrElse(Map.empty))),
                  StatusCodes.Created
                )
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
                entity(as[UpdateTopicRequest]) { req =>
                  completeWrite(service.submit(id, TopicCommand.UpdateTopic(req.labels)), StatusCodes.OK)
                }
              },
              delete {
                completeWrite(service.submit(id, TopicCommand.DeleteTopic), StatusCodes.NoContent)
              }
            )
          }
        }
      )
    }

  /** Validate a raw id into a `TopicId`, or reject with 400. */
  private def withTopicId(raw: String)(inner: TopicId => Route): Route =
    TopicId.from(raw) match
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
  def apply(service: TopicService): TopicAdminRoutes = new TopicAdminRoutes(service)
