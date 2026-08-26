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
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import spray.json.DefaultJsonProtocol
import spray.json.RootJsonFormat

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/** A topic as seen by a client. */
final case class TopicInfo(topicId: TopicId, labels: Map[String, String])

/** A row of the topic stats listing (`GET /v1/topics`). */
final case class TopicStats(topicId: TopicId, publishedTotal: Long, deleted: Boolean)

/** A row of the subscription stats listing (`GET /v1/subscriptions`). */
final case class SubscriptionStats(
    subscriptionId: SubscriptionId,
    topicId: TopicId,
    backlog: Int,
    oldestUnackedAgeSeconds: Long,
    redeliveredTotal: Long,
    deadLetteredTotal: Long
)

/** The result of a publish: the assigned (or original, when deduplicated) id. */
final case class PublishResult(messageId: MessageId, deduplicated: Boolean)

/** A message received when pulling from a subscription (payload is UTF-8 text).
  * `correlationId` is the request-tracing id the producer set on publish,
  * delivered verbatim by the broker (None when the producer set none).
  */
final case class ReceivedMessage(
    ackId: AckId,
    payload: String,
    attributes: Map[String, String],
    publishTime: String,
    correlationId: Option[String] = None
)

/** Which ack ids the broker acknowledged vs no longer knew. */
final case class AckResult(acknowledged: List[String], unknown: List[String])

/** Which ack ids had their deadline modified vs were unknown. */
final case class ModifyAckDeadlineResult(modified: List[String], unknown: List[String])

/** Broker liveness as reported by `GET /health`. */
final case class HealthInfo(status: String, service: String, version: String)

/** Raised when the broker returns an unexpected/error status. */
final class HermesClientException(val status: StatusCode, val body: String)
    extends RuntimeException(s"HermesMQ request failed with $status: $body")

private[client] object HermesClientJson extends DefaultJsonProtocol:
  final case class CreateTopicBody(topicId: String, labels: Map[String, String])
  final case class UpdateTopicBody(labels: Map[String, String])
  final case class TopicResponse(topicId: String, labels: Map[String, String])
  final case class TopicStatsJson(topicId: String, publishedTotal: Long, deleted: Boolean)
  final case class PublishBody(
      payload: String,
      attributes: Map[String, String],
      ttlSeconds: Option[Int],
      idempotencyKey: Option[String],
      producerId: Option[String]
  )
  final case class PublishResponse(messageId: String, deduplicated: Option[Boolean])
  final case class CreateSubscriptionBody(subscriptionId: String, topicId: String)
  final case class SubscriptionStatsJson(
      subscriptionId: String,
      topicId: String,
      backlog: Int,
      oldestUnackedAgeSeconds: Long,
      redeliveredTotal: Long,
      deadLetteredTotal: Long
  )
  final case class PullBody(max: Int, consumerId: Option[String])
  final case class PulledMessageJson(
      ackId: String,
      payload: String,
      attributes: Map[String, String],
      publishTime: String,
      correlationId: Option[String]
  )
  final case class PullResponse(messages: List[PulledMessageJson])
  final case class AckBody(ackIds: List[String])
  final case class AckResponseJson(acknowledged: List[String], unknown: List[String])
  final case class ModifyAckDeadlineBody(ackIds: List[String], ackDeadlineSeconds: Int)
  final case class ModifyAckDeadlineResponseJson(modified: List[String], unknown: List[String])
  final case class HealthJson(status: String, service: String, version: String)

  given RootJsonFormat[CreateTopicBody]               = jsonFormat2(CreateTopicBody.apply)
  given RootJsonFormat[UpdateTopicBody]               = jsonFormat1(UpdateTopicBody.apply)
  given RootJsonFormat[TopicResponse]                 = jsonFormat2(TopicResponse.apply)
  given RootJsonFormat[TopicStatsJson]                = jsonFormat3(TopicStatsJson.apply)
  given RootJsonFormat[PublishBody]                   = jsonFormat5(PublishBody.apply)
  given RootJsonFormat[PublishResponse]               = jsonFormat2(PublishResponse.apply)
  given RootJsonFormat[CreateSubscriptionBody]        = jsonFormat2(CreateSubscriptionBody.apply)
  given RootJsonFormat[SubscriptionStatsJson]         = jsonFormat6(SubscriptionStatsJson.apply)
  given RootJsonFormat[PullBody]                      = jsonFormat2(PullBody.apply)
  given RootJsonFormat[PulledMessageJson]             = jsonFormat5(PulledMessageJson.apply)
  given RootJsonFormat[PullResponse]                  = jsonFormat1(PullResponse.apply)
  given RootJsonFormat[AckBody]                       = jsonFormat1(AckBody.apply)
  given RootJsonFormat[AckResponseJson]               = jsonFormat2(AckResponseJson.apply)
  given RootJsonFormat[ModifyAckDeadlineBody]         = jsonFormat2(ModifyAckDeadlineBody.apply)
  given RootJsonFormat[ModifyAckDeadlineResponseJson] = jsonFormat2(ModifyAckDeadlineResponseJson.apply)
  given RootJsonFormat[HealthJson]                    = jsonFormat3(HealthJson.apply)

/** A typed, async Scala client for the HermesMQ REST API. The caller owns the
  * `ActorSystem`. Methods return a `Future` that fails with
  * [[HermesClientException]] on error statuses; a not-found on a read is modeled
  * as an empty result rather than a failure. Optional `token`/`apiKey` are sent
  * as `Authorization: Bearer` / `X-API-Key` on every request (both omitted by
  * default, matching an open broker).
  */
final class HermesClient(baseUri: String, token: Option[String] = None, apiKey: Option[String] = None)(using
    system: ActorSystem[?]
):
  import HermesClientJson.given
  import HermesClientJson.*
  import SprayJsonSupport.*

  private given ExecutionContext = system.executionContext
  private val http               = Http()(system)
  private val base               = baseUri.stripSuffix("/")

  private val authHeaders: List[HttpHeader] =
    token.map(t => RawHeader("Authorization", s"Bearer $t")).toList ++
      apiKey.map(k => RawHeader("X-API-Key", k)).toList

  /** Re-label the entity as JSON before unmarshalling: success is decided by the
    * HTTP status alone, so a 2xx whose body is JSON under an unexpected
    * content-type header must still parse rather than surface as a transport
    * failure (that misread turns delivered publishes into retries/dupes).
    */
  private def asJson(entity: ResponseEntity): ResponseEntity =
    entity.withContentType(ContentTypes.`application/json`)

  private def request(method: HttpMethod, uri: String, entity: RequestEntity = HttpEntity.Empty): Future[HttpResponse] =
    http.singleRequest(HttpRequest(method, uri, headers = authHeaders, entity = entity))

  def createTopic(topicId: TopicId, labels: Map[String, String] = Map.empty): Future[Unit] =
    for
      entity <- Marshal(CreateTopicBody(topicId.value, labels)).to[RequestEntity]
      resp   <- request(HttpMethods.POST, s"$base/v1/topics", entity)
      _      <- expect(resp, StatusCodes.Created)
    yield ()

  def getTopic(topicId: TopicId): Future[Option[TopicInfo]] =
    request(HttpMethods.GET, s"$base/v1/topics/${topicId.value}").flatMap { resp =>
      resp.status match
        case StatusCodes.OK =>
          Unmarshal(asJson(resp.entity)).to[TopicResponse].map(r => Some(TopicInfo(TopicId.from(r.topicId).toOption.get, r.labels)))
        case StatusCodes.NotFound =>
          val _ = resp.discardEntityBytes(system); Future.successful(None)
        case _ => fail(resp)
    }

  def updateTopic(topicId: TopicId, labels: Map[String, String]): Future[Unit] =
    for
      entity <- Marshal(UpdateTopicBody(labels)).to[RequestEntity]
      resp   <- request(HttpMethods.PATCH, s"$base/v1/topics/${topicId.value}", entity)
      _      <- expect(resp, StatusCodes.OK)
    yield ()

  def deleteTopic(topicId: TopicId): Future[Unit] =
    request(HttpMethods.DELETE, s"$base/v1/topics/${topicId.value}").flatMap(expect(_, StatusCodes.NoContent))

  def listTopics(): Future[List[TopicStats]] =
    request(HttpMethods.GET, s"$base/v1/topics").flatMap { resp =>
      resp.status match
        case StatusCodes.OK =>
          Unmarshal(asJson(resp.entity))
            .to[List[TopicStatsJson]]
            .map(_.map(t => TopicStats(TopicId.from(t.topicId).toOption.get, t.publishedTotal, t.deleted)))
        case _ => fail(resp)
    }

  def publish(
      topicId: TopicId,
      payload: String,
      attributes: Map[String, String] = Map.empty,
      ttlSeconds: Option[Int] = None,
      idempotencyKey: Option[String] = None,
      producerId: Option[String] = None,
      correlationId: Option[String] = None
  ): Future[PublishResult] =
    // The REST publish adopts the X-Correlation-Id header as the message's
    // correlation id (request-tracing); delivered verbatim to consumers.
    val correlationHeaders = correlationId.filter(_.nonEmpty).map(c => RawHeader("X-Correlation-Id", c)).toList
    for
      entity <- Marshal(PublishBody(payload, attributes, ttlSeconds, idempotencyKey, producerId)).to[RequestEntity]
      resp <- http.singleRequest(
        HttpRequest(
          HttpMethods.POST,
          s"$base/v1/topics/${topicId.value}/messages",
          headers = authHeaders ++ correlationHeaders,
          entity = entity
        )
      )
      result <- resp.status match
        case StatusCodes.Accepted | StatusCodes.Created =>
          Unmarshal(asJson(resp.entity))
            .to[PublishResponse]
            .map(r => PublishResult(MessageId.from(r.messageId).toOption.get, r.deduplicated.getOrElse(false)))
        case _ => fail(resp)
    yield result

  def createSubscription(subscriptionId: SubscriptionId, topicId: TopicId): Future[Unit] =
    for
      entity <- Marshal(CreateSubscriptionBody(subscriptionId.value, topicId.value)).to[RequestEntity]
      resp   <- request(HttpMethods.POST, s"$base/v1/subscriptions", entity)
      _      <- expect(resp, StatusCodes.Created)
    yield ()

  def deleteSubscription(subscriptionId: SubscriptionId): Future[Unit] =
    request(HttpMethods.DELETE, s"$base/v1/subscriptions/${subscriptionId.value}")
      .flatMap(expect(_, StatusCodes.NoContent))

  def listSubscriptions(): Future[List[SubscriptionStats]] =
    request(HttpMethods.GET, s"$base/v1/subscriptions").flatMap { resp =>
      resp.status match
        case StatusCodes.OK =>
          Unmarshal(asJson(resp.entity))
            .to[List[SubscriptionStatsJson]]
            .map(_.map { s =>
              SubscriptionStats(
                SubscriptionId.from(s.subscriptionId).toOption.get,
                TopicId.from(s.topicId).toOption.get,
                s.backlog,
                s.oldestUnackedAgeSeconds,
                s.redeliveredTotal,
                s.deadLetteredTotal
              )
            })
        case _ => fail(resp)
    }

  def pull(subscriptionId: SubscriptionId, max: Int = 10, consumerId: Option[String] = None): Future[List[ReceivedMessage]] =
    for
      entity <- Marshal(PullBody(max, consumerId)).to[RequestEntity]
      resp   <- request(HttpMethods.POST, s"$base/v1/subscriptions/${subscriptionId.value}/pull", entity)
      messages <- resp.status match
        case StatusCodes.OK =>
          Unmarshal(asJson(resp.entity)).to[PullResponse].map(_.messages.map(toReceived))
        case _ => fail(resp)
    yield messages

  def ack(subscriptionId: SubscriptionId, ackIds: List[AckId]): Future[AckResult] =
    for
      entity <- Marshal(AckBody(ackIds.map(_.value))).to[RequestEntity]
      resp   <- request(HttpMethods.POST, s"$base/v1/subscriptions/${subscriptionId.value}/ack", entity)
      result <- resp.status match
        case StatusCodes.OK =>
          Unmarshal(asJson(resp.entity)).to[AckResponseJson].map(r => AckResult(r.acknowledged, r.unknown))
        case _ => fail(resp)
    yield result

  def modifyAckDeadline(
      subscriptionId: SubscriptionId,
      ackIds: List[AckId],
      ackDeadlineSeconds: Int
  ): Future[ModifyAckDeadlineResult] =
    for
      entity <- Marshal(ModifyAckDeadlineBody(ackIds.map(_.value), ackDeadlineSeconds)).to[RequestEntity]
      resp   <- request(HttpMethods.POST, s"$base/v1/subscriptions/${subscriptionId.value}/modifyAckDeadline", entity)
      result <- resp.status match
        case StatusCodes.OK =>
          Unmarshal(asJson(resp.entity)).to[ModifyAckDeadlineResponseJson].map(r => ModifyAckDeadlineResult(r.modified, r.unknown))
        case _ => fail(resp)
    yield result

  def health(): Future[HealthInfo] =
    request(HttpMethods.GET, s"$base/health").flatMap { resp =>
      resp.status match
        case StatusCodes.OK =>
          Unmarshal(asJson(resp.entity)).to[HealthJson].map(h => HealthInfo(h.status, h.service, h.version))
        case _ => fail(resp)
    }

  private def toReceived(m: PulledMessageJson): ReceivedMessage =
    ReceivedMessage(AckId.from(m.ackId).toOption.get, m.payload, m.attributes, m.publishTime, m.correlationId)

  /** Succeed (discarding the body) when the status matches, otherwise fail. */
  private def expect(resp: HttpResponse, ok: StatusCode): Future[Unit] =
    if resp.status == ok then
      val _ = resp.discardEntityBytes(system)
      Future.unit
    else fail(resp)

  private def fail[T](resp: HttpResponse): Future[T] =
    Unmarshal(resp.entity).to[String].flatMap(body => Future.failed(new HermesClientException(resp.status, body)))

object HermesClient:
  def apply(baseUri: String, token: Option[String] = None, apiKey: Option[String] = None)(using
      system: ActorSystem[?]
  ): HermesClient =
    new HermesClient(baseUri, token, apiKey)
