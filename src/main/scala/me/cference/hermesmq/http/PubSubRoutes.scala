package me.cference.hermesmq.http

import me.cference.hermesmq.delivery.TopicSubscriptionsIndex
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.{CommandReply, PulledMessage, SubscriptionService, TopicService}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** JSON models for the pub/sub API. */
final case class PublishRequest(payload: String, attributes: Option[Map[String, String]])
final case class PublishResponse(messageId: String)
final case class CreateSubscriptionRequest(subscriptionId: String, topicId: String)
final case class PullRequest(max: Option[Int])
final case class PulledMessageJson(ackId: String, payload: String, attributes: Map[String, String], publishTime: String)
final case class PullResponse(messages: List[PulledMessageJson])
final case class AckRequest(ackIds: List[String])
final case class AckResponse(acknowledged: List[String], unknown: List[String])

object PubSubJson extends DefaultJsonProtocol:
  given RootJsonFormat[PublishRequest]           = jsonFormat2(PublishRequest.apply)
  given RootJsonFormat[PublishResponse]          = jsonFormat1(PublishResponse.apply)
  given RootJsonFormat[CreateSubscriptionRequest] = jsonFormat2(CreateSubscriptionRequest.apply)
  given RootJsonFormat[PullRequest]              = jsonFormat1(PullRequest.apply)
  given RootJsonFormat[PulledMessageJson]        = jsonFormat4(PulledMessageJson.apply)
  given RootJsonFormat[PullResponse]             = jsonFormat1(PullResponse.apply)
  given RootJsonFormat[AckRequest]               = jsonFormat1(AckRequest.apply)
  given RootJsonFormat[AckResponse]              = jsonFormat2(AckResponse.apply)

/** REST endpoints for publishing to topics and consuming from subscriptions.
  * Payloads are treated as UTF-8 text on the REST surface. Delegates to the
  * topic/subscription services; creating a subscription also updates the
  * topic→subscriptions index used by delivery fan-out.
  */
final class PubSubRoutes(
    topics: TopicService,
    subscriptions: SubscriptionService,
    index: TopicSubscriptionsIndex
)(using ExecutionContext):
  import PubSubJson.given
  import SprayJsonSupport.*

  private val DefaultPullMax = 10

  val routes: Route =
    concat(
      // Publish: POST /v1/topics/{id}/messages
      path("v1" / "topics" / Segment / "messages") { rawTopic =>
        post {
          entity(as[PublishRequest]) { req =>
            TopicId.from(rawTopic) match
              case Left(err) => complete(StatusCodes.BadRequest, err.message)
              case Right(topicId) =>
                buildMessage(req) match
                  case Left(err) => complete(StatusCodes.BadRequest, err.message)
                  case Right(message) =>
                    onComplete(topics.submit(topicId, TopicCommand.Publish(message))) {
                      case Success(CommandReply.Accepted) =>
                        complete((StatusCodes.Accepted, PublishResponse(message.id.value)))
                      case Success(CommandReply.Rejected(Rejection.TopicNotFound)) => complete(StatusCodes.NotFound)
                      case Success(CommandReply.Rejected(_))                       => complete(StatusCodes.Conflict)
                      case Failure(_)                                              => complete(StatusCodes.ServiceUnavailable)
                    }
          }
        }
      },
      pathPrefix("v1" / "subscriptions") {
        concat(
          // Create: POST /v1/subscriptions
          pathEndOrSingleSlash {
            post {
              entity(as[CreateSubscriptionRequest]) { req =>
                (SubscriptionId.from(req.subscriptionId), TopicId.from(req.topicId)) match
                  case (Right(sid), Right(tid)) =>
                    onComplete(subscriptions.submit(sid, SubscriptionCommand.CreateSubscription(sid, tid))) {
                      case Success(CommandReply.Accepted) =>
                        index.add(tid, sid)
                        complete(StatusCodes.Created)
                      case Success(CommandReply.Rejected(_)) => complete(StatusCodes.Conflict)
                      case Failure(_)                        => complete(StatusCodes.ServiceUnavailable)
                    }
                  case _ => complete(StatusCodes.BadRequest, "invalid subscriptionId or topicId")
              }
            }
          },
          // Pull: POST /v1/subscriptions/{id}/pull
          path(Segment / "pull") { rawSub =>
            post {
              entity(as[PullRequest]) { req =>
                withSubId(rawSub) { sid =>
                  onComplete(subscriptions.pull(sid, req.max.getOrElse(DefaultPullMax))) {
                    case Success(Some(msgs)) => complete(PullResponse(msgs.map(toJson)))
                    case Success(None)       => complete(StatusCodes.NotFound)
                    case Failure(_)          => complete(StatusCodes.ServiceUnavailable)
                  }
                }
              }
            }
          },
          // Ack: POST /v1/subscriptions/{id}/ack
          path(Segment / "ack") { rawSub =>
            post {
              entity(as[AckRequest]) { req =>
                withSubId(rawSub) { sid =>
                  onComplete(acknowledgeAll(sid, req.ackIds)) {
                    case Success(response) => complete(response)
                    case Failure(_)        => complete(StatusCodes.ServiceUnavailable)
                  }
                }
              }
            }
          }
        )
      }
    )

  private def withSubId(raw: String)(inner: SubscriptionId => Route): Route =
    SubscriptionId.from(raw) match
      case Right(id) => inner(id)
      case Left(err) => complete(StatusCodes.BadRequest, err.message)

  private def buildMessage(req: PublishRequest): Either[ValidationError, Message] =
    val id = MessageId.from(UUID.randomUUID().toString).toOption.get
    Message.from(id, req.payload.getBytes(UTF_8), req.attributes.getOrElse(Map.empty), Instant.now())

  private def toJson(pm: PulledMessage): PulledMessageJson =
    PulledMessageJson(
      ackId = pm.ackId.value,
      payload = new String(pm.message.payload.toArray, UTF_8),
      attributes = pm.message.attributes,
      publishTime = pm.message.publishTime.toString
    )

  /** Acknowledge each id, partitioning into acknowledged vs unknown. */
  private def acknowledgeAll(sid: SubscriptionId, ackIds: List[String]): Future[AckResponse] =
    Future
      .traverse(ackIds) { raw =>
        AckId.from(raw) match
          case Left(_) => Future.successful((raw, false))
          case Right(a) =>
            subscriptions.submit(sid, SubscriptionCommand.Acknowledge(a)).map {
              case CommandReply.Accepted    => (raw, true)
              case CommandReply.Rejected(_) => (raw, false)
            }
      }
      .map { results =>
        AckResponse(
          acknowledged = results.collect { case (r, true) => r },
          unknown = results.collect { case (r, false) => r }
        )
      }

object PubSubRoutes:
  def apply(topics: TopicService, subscriptions: SubscriptionService, index: TopicSubscriptionsIndex)(using
      ExecutionContext
  ): PubSubRoutes = new PubSubRoutes(topics, subscriptions, index)
