package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.*
import spray.json.*

import java.time.Instant
import java.util.Base64
import scala.concurrent.duration.*

/** spray-json formats for the domain events and their value types. Used by
  * [[DomainEventSerializer]] to journal events as explicit JSON.
  */
object JsonFormats extends DefaultJsonProtocol:

  private def stringId[A](wrap: String => Either[ValidationError, A])(value: A => String): JsonFormat[A] =
    new JsonFormat[A]:
      def write(a: A): JsValue = JsString(value(a))
      def read(json: JsValue): A = json match
        case JsString(s) => wrap(s).fold(e => deserializationError(e.message), identity)
        case other       => deserializationError(s"expected string id, got $other")

  given JsonFormat[TopicId]        = stringId(TopicId.from)(_.value)
  given JsonFormat[SubscriptionId] = stringId(SubscriptionId.from)(_.value)
  given JsonFormat[MessageId]      = stringId(MessageId.from)(_.value)
  given JsonFormat[AckId]          = stringId(AckId.from)(_.value)

  /** Stored as nanoseconds; `FiniteDuration` equality is by nanos, so this
    * round-trips exactly regardless of the original unit.
    */
  given JsonFormat[AckDeadline] with
    def write(d: AckDeadline): JsValue = JsNumber(d.duration.toNanos)
    def read(json: JsValue): AckDeadline = json match
      case JsNumber(n) =>
        AckDeadline.from(Duration.fromNanos(n.toLong)).fold(e => deserializationError(e.message), identity)
      case other => deserializationError(s"expected number, got $other")

  given RootJsonFormat[Message] with
    def write(m: Message): JsValue = JsObject(
      "id"          -> m.id.toJson,
      "payload"     -> JsString(Base64.getEncoder.encodeToString(m.payload.toArray)),
      "attributes"  -> m.attributes.toJson,
      "publishTime" -> JsString(m.publishTime.toString)
    )
    def read(json: JsValue): Message =
      val o           = json.asJsObject
      val id          = o.fields("id").convertTo[MessageId]
      val payload     = Base64.getDecoder.decode(o.fields("payload").convertTo[String])
      val attributes  = o.fields("attributes").convertTo[Map[String, String]]
      val publishTime = Instant.parse(o.fields("publishTime").convertTo[String])
      Message.from(id, payload, attributes, publishTime).fold(e => deserializationError(e.message), identity)

  given RootJsonFormat[TopicEvent] with
    def write(e: TopicEvent): JsValue = e match
      case TopicEvent.TopicCreated(topicId) =>
        JsObject("type" -> JsString("TopicCreated"), "topicId" -> topicId.toJson)
      case TopicEvent.MessagePublished(message) =>
        JsObject("type" -> JsString("MessagePublished"), "message" -> message.toJson)
    def read(json: JsValue): TopicEvent =
      val o = json.asJsObject
      o.fields("type").convertTo[String] match
        case "TopicCreated"     => TopicEvent.TopicCreated(o.fields("topicId").convertTo[TopicId])
        case "MessagePublished" => TopicEvent.MessagePublished(o.fields("message").convertTo[Message])
        case other              => deserializationError(s"unknown TopicEvent type: $other")

  given RootJsonFormat[SubscriptionEvent] with
    def write(e: SubscriptionEvent): JsValue = e match
      case SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId) =>
        JsObject("type" -> JsString("SubscriptionCreated"), "subscriptionId" -> subscriptionId.toJson, "topicId" -> topicId.toJson)
      case SubscriptionEvent.MessageDelivered(ackId, messageId, deadline) =>
        JsObject("type" -> JsString("MessageDelivered"), "ackId" -> ackId.toJson, "messageId" -> messageId.toJson, "deadline" -> deadline.toJson)
      case SubscriptionEvent.MessageAcknowledged(ackId) =>
        JsObject("type" -> JsString("MessageAcknowledged"), "ackId" -> ackId.toJson)
      case SubscriptionEvent.AckDeadlineModified(ackId, deadline) =>
        JsObject("type" -> JsString("AckDeadlineModified"), "ackId" -> ackId.toJson, "deadline" -> deadline.toJson)
    def read(json: JsValue): SubscriptionEvent =
      val o = json.asJsObject
      o.fields("type").convertTo[String] match
        case "SubscriptionCreated" =>
          SubscriptionEvent.SubscriptionCreated(o.fields("subscriptionId").convertTo[SubscriptionId], o.fields("topicId").convertTo[TopicId])
        case "MessageDelivered" =>
          SubscriptionEvent.MessageDelivered(o.fields("ackId").convertTo[AckId], o.fields("messageId").convertTo[MessageId], o.fields("deadline").convertTo[AckDeadline])
        case "MessageAcknowledged" =>
          SubscriptionEvent.MessageAcknowledged(o.fields("ackId").convertTo[AckId])
        case "AckDeadlineModified" =>
          SubscriptionEvent.AckDeadlineModified(o.fields("ackId").convertTo[AckId], o.fields("deadline").convertTo[AckDeadline])
        case other => deserializationError(s"unknown SubscriptionEvent type: $other")
