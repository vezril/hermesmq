package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.{SubscriptionEvent, TopicEvent}
import org.apache.pekko.serialization.SerializerWithStringManifest
import spray.json.*

import java.nio.charset.StandardCharsets.UTF_8

import JsonFormats.given

/** Explicit JSON serializer for journaled domain events (spray-json). Bound to
  * the event types in `application.conf`; with Java serialization disabled, any
  * unbound type fails fast instead of silently using Java serialization.
  */
final class DomainEventSerializer extends SerializerWithStringManifest:

  private val TopicManifest        = "TopicEvent"
  private val SubscriptionManifest = "SubscriptionEvent"

  override def identifier: Int = 517_231

  override def manifest(o: AnyRef): String = o match
    case _: TopicEvent        => TopicManifest
    case _: SubscriptionEvent => SubscriptionManifest
    case other                => throw IllegalArgumentException(s"Cannot serialize ${other.getClass.getName}")

  override def toBinary(o: AnyRef): Array[Byte] = o match
    case e: TopicEvent        => e.toJson.compactPrint.getBytes(UTF_8)
    case e: SubscriptionEvent => e.toJson.compactPrint.getBytes(UTF_8)
    case other                => throw IllegalArgumentException(s"Cannot serialize ${other.getClass.getName}")

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    val json = new String(bytes, UTF_8).parseJson
    manifest match
      case TopicManifest        => json.convertTo[TopicEvent]
      case SubscriptionManifest => json.convertTo[SubscriptionEvent]
      case other                => throw IllegalArgumentException(s"Unknown manifest: $other")
