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

  given JsonFormat[Instant] with
    def write(i: Instant): JsValue = JsString(i.toString)
    def read(json: JsValue): Instant = json match
      case JsString(s) => Instant.parse(s)
      case other       => deserializationError(s"expected ISO instant, got $other")

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

  /** Labels default to empty when absent, so events journaled before topics had
    * labels (v0.2.0) still deserialize.
    */
  private def readLabels(o: JsObject): Map[String, String] =
    o.fields.get("labels").map(_.convertTo[Map[String, String]]).getOrElse(Map.empty)

  given RootJsonFormat[TopicEvent] with
    def write(e: TopicEvent): JsValue = e match
      case TopicEvent.TopicCreated(topicId, labels) =>
        JsObject("type" -> JsString("TopicCreated"), "topicId" -> topicId.toJson, "labels" -> labels.toJson)
      case TopicEvent.MessagePublished(message) =>
        JsObject("type" -> JsString("MessagePublished"), "message" -> message.toJson)
      case TopicEvent.TopicDeleted(topicId) =>
        JsObject("type" -> JsString("TopicDeleted"), "topicId" -> topicId.toJson)
      case TopicEvent.TopicLabelsUpdated(topicId, labels) =>
        JsObject("type" -> JsString("TopicLabelsUpdated"), "topicId" -> topicId.toJson, "labels" -> labels.toJson)
    def read(json: JsValue): TopicEvent =
      val o = json.asJsObject
      o.fields("type").convertTo[String] match
        case "TopicCreated"       => TopicEvent.TopicCreated(o.fields("topicId").convertTo[TopicId], readLabels(o))
        case "MessagePublished"   => TopicEvent.MessagePublished(o.fields("message").convertTo[Message])
        case "TopicDeleted"       => TopicEvent.TopicDeleted(o.fields("topicId").convertTo[TopicId])
        case "TopicLabelsUpdated" => TopicEvent.TopicLabelsUpdated(o.fields("topicId").convertTo[TopicId], readLabels(o))
        case other                => deserializationError(s"unknown TopicEvent type: $other")

  given RootJsonFormat[SubscriptionEvent] with
    def write(e: SubscriptionEvent): JsValue = e match
      case SubscriptionEvent.SubscriptionCreated(subscriptionId, topicId) =>
        JsObject("type" -> JsString("SubscriptionCreated"), "subscriptionId" -> subscriptionId.toJson, "topicId" -> topicId.toJson)
      case SubscriptionEvent.MessageDelivered(ackId, message) =>
        JsObject("type" -> JsString("MessageDelivered"), "ackId" -> ackId.toJson, "message" -> message.toJson)
      case SubscriptionEvent.MessageLeased(ackIds, deadline) =>
        JsObject("type" -> JsString("MessageLeased"), "ackIds" -> ackIds.toJson, "deadline" -> deadline.toJson)
      case SubscriptionEvent.MessageAcknowledged(ackId) =>
        JsObject("type" -> JsString("MessageAcknowledged"), "ackId" -> ackId.toJson)
      case SubscriptionEvent.AckDeadlineModified(ackId, deadline) =>
        JsObject("type" -> JsString("AckDeadlineModified"), "ackId" -> ackId.toJson, "deadline" -> deadline.toJson)
      case SubscriptionEvent.AckDeadlineExpired(ackId, attempt) =>
        JsObject("type" -> JsString("AckDeadlineExpired"), "ackId" -> ackId.toJson, "attempt" -> attempt.toJson)
      case SubscriptionEvent.MessageDeadLettered(ackId, message, attempt) =>
        JsObject("type" -> JsString("MessageDeadLettered"), "ackId" -> ackId.toJson, "message" -> message.toJson, "attempt" -> attempt.toJson)
    def read(json: JsValue): SubscriptionEvent =
      val o = json.asJsObject
      o.fields("type").convertTo[String] match
        case "SubscriptionCreated" =>
          SubscriptionEvent.SubscriptionCreated(o.fields("subscriptionId").convertTo[SubscriptionId], o.fields("topicId").convertTo[TopicId])
        case "MessageDelivered" =>
          SubscriptionEvent.MessageDelivered(o.fields("ackId").convertTo[AckId], o.fields("message").convertTo[Message])
        case "MessageLeased" =>
          SubscriptionEvent.MessageLeased(o.fields("ackIds").convertTo[List[AckId]], o.fields("deadline").convertTo[Instant])
        case "MessageAcknowledged" =>
          SubscriptionEvent.MessageAcknowledged(o.fields("ackId").convertTo[AckId])
        case "AckDeadlineModified" =>
          SubscriptionEvent.AckDeadlineModified(o.fields("ackId").convertTo[AckId], o.fields("deadline").convertTo[Instant])
        case "AckDeadlineExpired" =>
          SubscriptionEvent.AckDeadlineExpired(o.fields("ackId").convertTo[AckId], o.fields("attempt").convertTo[Int])
        case "MessageDeadLettered" =>
          SubscriptionEvent.MessageDeadLettered(
            o.fields("ackId").convertTo[AckId],
            o.fields("message").convertTo[Message],
            o.fields("attempt").convertTo[Int]
          )
        case other => deserializationError(s"unknown SubscriptionEvent type: $other")

  // --- Aggregate state (snapshot) formats -----------------------------------
  // Reads are tolerant of absent fields so a state shape can evolve without
  // orphaning existing snapshots.

  given RootJsonFormat[LeaseState] with
    def write(l: LeaseState): JsValue = l match
      case LeaseState.Available        => JsObject("type" -> JsString("Available"))
      case LeaseState.Leased(deadline) => JsObject("type" -> JsString("Leased"), "deadline" -> deadline.toJson)
    def read(json: JsValue): LeaseState =
      val o = json.asJsObject
      o.fields("type").convertTo[String] match
        case "Available" => LeaseState.Available
        case "Leased"    => LeaseState.Leased(o.fields("deadline").convertTo[Instant])
        case other       => deserializationError(s"unknown LeaseState type: $other")

  given RootJsonFormat[Outstanding] with
    def write(o: Outstanding): JsValue =
      JsObject("message" -> o.message.toJson, "lease" -> o.lease.toJson, "attempts" -> JsNumber(o.attempts))
    def read(json: JsValue): Outstanding =
      val o = json.asJsObject
      Outstanding(
        o.fields("message").convertTo[Message],
        o.fields.getOrElse("lease", JsObject("type" -> JsString("Available"))).convertTo[LeaseState],
        o.fields.getOrElse("attempts", JsNumber(0)).convertTo[Int]
      )

  given RootJsonFormat[TopicState] with
    def write(s: TopicState): JsValue =
      JsObject("topicId" -> s.topicId.toJson, "labels" -> s.labels.toJson, "deleted" -> JsBoolean(s.deleted))
    def read(json: JsValue): TopicState =
      val o = json.asJsObject
      TopicState(
        o.fields.getOrElse("topicId", JsNull).convertTo[Option[TopicId]],
        o.fields.getOrElse("labels", JsObject()).convertTo[Map[String, String]],
        o.fields.getOrElse("deleted", JsBoolean(false)).convertTo[Boolean]
      )

  given RootJsonFormat[SubscriptionState] with
    def write(s: SubscriptionState): JsValue =
      JsObject(
        "subscriptionId" -> s.subscriptionId.toJson,
        "topicId"        -> s.topicId.toJson,
        "outstanding"    -> s.outstanding.toJson
      )
    def read(json: JsValue): SubscriptionState =
      val o = json.asJsObject
      SubscriptionState(
        o.fields.getOrElse("subscriptionId", JsNull).convertTo[Option[SubscriptionId]],
        o.fields.getOrElse("topicId", JsNull).convertTo[Option[TopicId]],
        o.fields.getOrElse("outstanding", JsObject()).convertTo[Map[AckId, Outstanding]]
      )
