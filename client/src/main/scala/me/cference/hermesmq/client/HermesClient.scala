package me.cference.hermesmq.client

import me.cference.hermesmq.domain.AckId
import me.cference.hermesmq.domain.MessageId
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.marshalling.Marshal
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** A topic as seen by a client. */
final case class TopicInfo(topicId: TopicId, labels: Map[String, String])

/** A message received when pulling from a subscription (payload is UTF-8 text). */
final case class ReceivedMessage(ackId: AckId, payload: String, attributes: Map[String, String], publishTime: String)

/** Raised when the broker returns an unexpected/error status. */
final class HermesClientException(val status: StatusCode, val body: String)
    extends RuntimeException(s"HermesMQ request failed with $status: $body")

private[client] object HermesClientJson extends DefaultJsonProtocol:
  final case class CreateTopicBody(topicId: String, labels: Map[String, String])
  final case class UpdateTopicBody(labels: Map[String, String])
  final case class TopicResponse(topicId: String, labels: Map[String, String])
  final case class PublishBody(payload: String, attributes: Map[String, String])
  final case class PublishResponse(messageId: String)
  final case class CreateSubscriptionBody(subscriptionId: String, topicId: String)
  final case class PullBody(max: Int)
  final case class PulledMessageJson(ackId: String, payload: String, attributes: Map[String, String], publishTime: String)
  final case class PullResponse(messages: List[PulledMessageJson])
  final case class AckBody(ackIds: List[String])

  given RootJsonFormat[CreateTopicBody]        = jsonFormat2(CreateTopicBody.apply)
  given RootJsonFormat[UpdateTopicBody]        = jsonFormat1(UpdateTopicBody.apply)
  given RootJsonFormat[TopicResponse]          = jsonFormat2(TopicResponse.apply)
  given RootJsonFormat[PublishBody]            = jsonFormat2(PublishBody.apply)
  given RootJsonFormat[PublishResponse]        = jsonFormat1(PublishResponse.apply)
  given RootJsonFormat[CreateSubscriptionBody] = jsonFormat2(CreateSubscriptionBody.apply)
  given RootJsonFormat[PullBody]               = jsonFormat1(PullBody.apply)
  given RootJsonFormat[PulledMessageJson]      = jsonFormat4(PulledMessageJson.apply)
  given RootJsonFormat[PullResponse]           = jsonFormat1(PullResponse.apply)
  given RootJsonFormat[AckBody]                = jsonFormat1(AckBody.apply)

/** A typed, async Scala client for the HermesMQ REST API. The caller owns the
  * `ActorSystem`. Methods return a `Future` that fails with
  * [[HermesClientException]] on error statuses; a not-found on a read is modeled
  * as an empty result rather than a failure.
  */
final class HermesClient(baseUri: String)(using system: ActorSystem[?]):
  import HermesClientJson.given
  import HermesClientJson.*
  import SprayJsonSupport.*

  private given ExecutionContext = system.executionContext
  private val http               = Http()(system)
  private val base               = baseUri.stripSuffix("/")

  def createTopic(topicId: TopicId, labels: Map[String, String] = Map.empty): Future[Unit] =
    for
      entity <- Marshal(CreateTopicBody(topicId.value, labels)).to[RequestEntity]
      resp   <- http.singleRequest(HttpRequest(HttpMethods.POST, s"$base/v1/topics", entity = entity))
      _      <- expect(resp, StatusCodes.Created)
    yield ()

  def getTopic(topicId: TopicId): Future[Option[TopicInfo]] =
    http.singleRequest(HttpRequest(HttpMethods.GET, s"$base/v1/topics/${topicId.value}")).flatMap { resp =>
      resp.status match
        case StatusCodes.OK =>
          Unmarshal(resp.entity).to[TopicResponse].map(r => Some(TopicInfo(TopicId.from(r.topicId).toOption.get, r.labels)))
        case StatusCodes.NotFound =>
          val _ = resp.discardEntityBytes(system); Future.successful(None)
        case _ => fail(resp)
    }

  def updateTopic(topicId: TopicId, labels: Map[String, String]): Future[Unit] =
    for
      entity <- Marshal(UpdateTopicBody(labels)).to[RequestEntity]
      resp   <- http.singleRequest(HttpRequest(HttpMethods.PATCH, s"$base/v1/topics/${topicId.value}", entity = entity))
      _      <- expect(resp, StatusCodes.OK)
    yield ()

  def deleteTopic(topicId: TopicId): Future[Unit] =
    http
      .singleRequest(HttpRequest(HttpMethods.DELETE, s"$base/v1/topics/${topicId.value}"))
      .flatMap(expect(_, StatusCodes.NoContent))

  def publish(topicId: TopicId, payload: String, attributes: Map[String, String] = Map.empty): Future[MessageId] =
    for
      entity <- Marshal(PublishBody(payload, attributes)).to[RequestEntity]
      resp   <- http.singleRequest(HttpRequest(HttpMethods.POST, s"$base/v1/topics/${topicId.value}/messages", entity = entity))
      messageId <- resp.status match
        case StatusCodes.Accepted | StatusCodes.Created =>
          Unmarshal(resp.entity).to[PublishResponse].map(r => MessageId.from(r.messageId).toOption.get)
        case _ => fail(resp)
    yield messageId

  def createSubscription(subscriptionId: SubscriptionId, topicId: TopicId): Future[Unit] =
    for
      entity <- Marshal(CreateSubscriptionBody(subscriptionId.value, topicId.value)).to[RequestEntity]
      resp   <- http.singleRequest(HttpRequest(HttpMethods.POST, s"$base/v1/subscriptions", entity = entity))
      _      <- expect(resp, StatusCodes.Created)
    yield ()

  def pull(subscriptionId: SubscriptionId, max: Int = 10): Future[List[ReceivedMessage]] =
    for
      entity <- Marshal(PullBody(max)).to[RequestEntity]
      resp   <- http.singleRequest(HttpRequest(HttpMethods.POST, s"$base/v1/subscriptions/${subscriptionId.value}/pull", entity = entity))
      messages <- resp.status match
        case StatusCodes.OK =>
          Unmarshal(resp.entity).to[PullResponse].map(_.messages.map(toReceived))
        case _ => fail(resp)
    yield messages

  def ack(subscriptionId: SubscriptionId, ackIds: List[AckId]): Future[Unit] =
    for
      entity <- Marshal(AckBody(ackIds.map(_.value))).to[RequestEntity]
      resp   <- http.singleRequest(HttpRequest(HttpMethods.POST, s"$base/v1/subscriptions/${subscriptionId.value}/ack", entity = entity))
      _      <- expect(resp, StatusCodes.OK)
    yield ()

  private def toReceived(m: PulledMessageJson): ReceivedMessage =
    ReceivedMessage(AckId.from(m.ackId).toOption.get, m.payload, m.attributes, m.publishTime)

  /** Succeed (discarding the body) when the status matches, otherwise fail. */
  private def expect(resp: HttpResponse, ok: StatusCode): Future[Unit] =
    if resp.status == ok then
      val _ = resp.discardEntityBytes(system)
      Future.unit
    else fail(resp)

  private def fail[T](resp: HttpResponse): Future[T] =
    Unmarshal(resp.entity).to[String].flatMap(body => Future.failed(new HermesClientException(resp.status, body)))

object HermesClient:
  def apply(baseUri: String)(using system: ActorSystem[?]): HermesClient = new HermesClient(baseUri)
