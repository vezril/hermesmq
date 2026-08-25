package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.SubscriptionEvent
import me.cference.hermesmq.domain.SubscriptionState
import me.cference.hermesmq.domain.TopicEvent
import me.cference.hermesmq.domain.TopicState
import org.apache.pekko.serialization.SerializerWithStringManifest
import spray.json.*

import java.nio.charset.StandardCharsets.UTF_8

import JsonFormats.given

/** Explicit JSON serializer for journaled domain events and aggregate-state
  * snapshots (spray-json). Bound to the event and state types in
  * `application.conf`; with Java serialization disabled, any unbound type fails
  * fast instead of silently using Java serialization.
  */
final class DomainEventSerializer extends SerializerWithStringManifest:

  private val TopicManifest             = "TopicEvent"
  private val SubscriptionManifest      = "SubscriptionEvent"
  private val TopicStateManifest        = "TopicState"
  private val SubscriptionStateManifest = "SubscriptionState"

  override def identifier: Int = 517_231

  override def manifest(o: AnyRef): String = o match
    case _: TopicEvent        => TopicManifest
    case _: SubscriptionEvent => SubscriptionManifest
    case _: TopicState        => TopicStateManifest
    case _: SubscriptionState => SubscriptionStateManifest
    case other                => sys.error(s"Cannot serialize ${other.getClass.getName}")

  override def toBinary(o: AnyRef): Array[Byte] = o match
    case e: TopicEvent        => e.toJson.compactPrint.getBytes(UTF_8)
    case e: SubscriptionEvent => e.toJson.compactPrint.getBytes(UTF_8)
    case s: TopicState        => s.toJson.compactPrint.getBytes(UTF_8)
    case s: SubscriptionState => s.toJson.compactPrint.getBytes(UTF_8)
    case other                => sys.error(s"Cannot serialize ${other.getClass.getName}")

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    val json = new String(bytes, UTF_8).parseJson
    manifest match
      case TopicManifest             => json.convertTo[TopicEvent]
      case SubscriptionManifest      => json.convertTo[SubscriptionEvent]
      case TopicStateManifest        => json.convertTo[TopicState]
      case SubscriptionStateManifest => json.convertTo[SubscriptionState]
      case other                     => sys.error(s"Unknown manifest: $other")
