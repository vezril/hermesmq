package me.cference.hermesmq.http

import me.cference.hermesmq.auth.Principal
import me.cference.hermesmq.auth.TenantScope
import me.cference.hermesmq.delivery.DeadLetterEntry
import me.cference.hermesmq.delivery.DeadLetterRepository
import me.cference.hermesmq.delivery.TopicSubscriptionsRepository
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.SubscriptionService
import me.cference.hermesmq.persistence.TopicService
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import spray.json.DefaultJsonProtocol
import spray.json.JsArray
import spray.json.RootJsonFormat
import spray.json.enrichAny

import java.nio.charset.StandardCharsets.UTF_8
import java.time.Instant
import java.util.UUID
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

/** JSON models for the dead-letter operator API. */
final case class DeadLetterJson(
    originalMessageId: String,
    payload: String,
    attributes: Map[String, String],
    deliveryAttempts: Int,
    deadLetteredAt: String,
    originTopic: Option[String] = None,
    redrivenAt: Option[String] = None
)

/** Either an explicit list of original message ids, or `all`. */
final case class RedriveRequest(messageIds: Option[List[String]] = None, all: Option[Boolean] = None)

final case class RedriveFailureJson(messageId: String, reason: String)

final case class RedriveResponse(
    redriven: List[String],
    alreadyRedriven: List[String],
    unknown: List[String],
    failed: List[RedriveFailureJson]
)

object DeadLetterJsonProtocol extends DefaultJsonProtocol:
  given RootJsonFormat[DeadLetterJson]      = jsonFormat7(DeadLetterJson.apply)
  given RootJsonFormat[RedriveRequest]      = jsonFormat2(RedriveRequest.apply)
  given RootJsonFormat[RedriveFailureJson]  = jsonFormat2(RedriveFailureJson.apply)
  given RootJsonFormat[RedriveResponse]     = jsonFormat4(RedriveResponse.apply)

/** REST endpoints for inspecting and redriving dead-lettered messages
  * (dead-letter-operations).
  *
  * Browse reads the dead-letter read model and never leases, so it is safe to
  * poll from an operator console. Redrive republishes to the origin topic and
  * requires the `admin` scope, matching topic administration.
  *
  * Ids on the wire are external (tenant-unqualified); the read model and the
  * topic index both hold internal, tenant-qualified ids, so this layer qualifies
  * on the way in and unqualifies on the way out. Republishing deliberately uses
  * the **unscoped** topic service with the internal topic id: the origin topic
  * recorded in the read model is already qualified, and routing it through a
  * tenant-scoped service would qualify it twice.
  */
final class DeadLetterRoutes(
    deadLetters: DeadLetterRepository,
    subscriptions: SubscriptionService,
    topics: TopicService,
    subscriptionIndex: TopicSubscriptionsRepository,
    principal: Principal,
    tenantScope: TenantScope
)(using ExecutionContext):
  import DeadLetterJsonProtocol.given
  import SprayJsonSupport.*

  val routes: Route =
    pathPrefix("v1" / "subscriptions") {
      concat(
        // Browse: GET /v1/subscriptions/{id}/dead-letters
        path(Segment / "dead-letters") { rawSub =>
          get {
            withSubscription(rawSub) { (_, internal) =>
              onComplete(deadLetters.listFor(internal)) {
                // Explicit JsArray, matching the admin listings: a bare List has
                // two ambiguous spray-json writers.
                case Success(entries) => complete(JsArray(entries.map(e => toJson(e).toJson).toVector))
                case Failure(_)       => complete(StatusCodes.ServiceUnavailable)
              }
            }
          }
        },
        // Redrive: POST /v1/subscriptions/{id}/dead-letters/redrive
        path(Segment / "dead-letters" / "redrive") { rawSub =>
          post {
            entity(as[RedriveRequest]) { req =>
              if !Auth.isAdmin(principal) then complete(StatusCodes.Forbidden)
              else
                withSubscription(rawSub) { (_, internal) =>
                  req.messageIds.filter(_.nonEmpty).map(Right(_)).orElse(
                    Option.when(req.all.contains(true))(Left(()))
                  ) match
                    case None =>
                      complete(StatusCodes.BadRequest, "specify either messageIds or all=true")
                    case Some(selector) =>
                      onComplete(redrive(internal, selector)) {
                        case Success(response) => complete(response)
                        case Failure(_)        => complete(StatusCodes.ServiceUnavailable)
                      }
                }
            }
          }
        }
      )
    }

  /** Validate and qualify the subscription id, and confirm it exists.
    *
    * Existence is probed with a zero-max pull -- side-effect-free and already the
    * mechanism `modifyAckDeadline` uses -- so an unknown subscription is a `404`
    * rather than an empty success that hides a typo.
    */
  private def withSubscription(raw: String)(inner: (SubscriptionId, SubscriptionId) => Route): Route =
    TenantScope.validateExternalId(raw).flatMap(SubscriptionId.from) match
      case Left(err) => complete(StatusCodes.BadRequest, err.message)
      case Right(external) =>
        SubscriptionId.from(tenantScope.qualify(principal.tenant, external.value)) match
          case Left(err) => complete(StatusCodes.BadRequest, err.message)
          case Right(internal) =>
            onComplete(subscriptions.pull(external, 0)) {
              case Success(Some(_)) => inner(external, internal)
              case Success(None)    => complete(StatusCodes.NotFound)
              case Failure(_)       => complete(StatusCodes.ServiceUnavailable)
            }

  /** `Left(())` redrives every dead-letter of the subscription; `Right(ids)` only
    * the named ones. Each id is reported independently so one failure -- a
    * deleted origin topic, say -- does not sink the rest of the batch.
    */
  private def redrive(internal: SubscriptionId, selector: Either[Unit, List[String]]): Future[RedriveResponse] =
    val selected: Future[(List[DeadLetterEntry], List[String])] = selector match
      case Left(_) => deadLetters.listFor(internal).map(_ -> Nil)
      case Right(ids) =>
        Future
          .traverse(ids) { raw =>
            MessageId.from(raw) match
              case Left(_)    => Future.successful(Left(raw))
              case Right(mid) => deadLetters.find(internal, mid).map(_.toRight(raw))
          }
          .map { results =>
            val found   = results.collect { case Right(e) => e }
            val unknown = results.collect { case Left(raw) => raw }
            (found, unknown)
          }

    selected.flatMap { case (entries, unknown) =>
      val (already, pending) = entries.partition(_.redrivenAt.isDefined)
      Future.traverse(pending)(redriveOne(internal, _)).map { outcomes =>
        RedriveResponse(
          redriven = outcomes.collect { case Right(id) => id },
          alreadyRedriven = already.map(_.originalMessageId.value),
          unknown = unknown,
          failed = outcomes.collect { case Left(failure) => failure }
        )
      }
    }

  /** Republish one dead-letter to its origin topic.
    *
    * The idempotency key is derived from the subscription and original message
    * id, so a retried or double-clicked redrive is collapsed by the Topic
    * aggregate's dedup rather than fanning out a second copy. NOTE: that dedup is
    * bounded by `HERMESMQ_DEDUP_WINDOW`, which is `0s` (off) by default -- with
    * dedup disabled the `redrivenAt` marker below is the only guard, and it is
    * eventually consistent. See the capability's design notes.
    */
  private def redriveOne(internal: SubscriptionId, entry: DeadLetterEntry): Future[Either[RedriveFailureJson, String]] =
    val externalMsgId = entry.originalMessageId.value
    def fail(reason: String) = Left(RedriveFailureJson(externalMsgId, reason))

    originTopicOf(internal, entry).flatMap {
      case None => Future.successful(fail("origin topic could not be resolved"))
      case Some(topic) =>
        rebuild(internal, entry) match
          case Left(err) => Future.successful(fail(err.message))
          case Right(message) =>
            topics.submit(topic, TopicCommand.Publish(message)).flatMap {
              case CommandReply.Published(_, _) | CommandReply.Accepted =>
                deadLetters
                  .markRedriven(internal, entry.originalMessageId, Instant.now())
                  .map(_ => Right(externalMsgId))
              case CommandReply.Rejected(err) =>
                Future.successful(fail(s"origin topic rejected the republish: $err"))
            }.recover { case e => fail(Option(e.getMessage).getOrElse("republish failed")) }
    }

  /** The entry's recorded origin topic, falling back to the subscription index
    * for dead-letters written before `x-original-topic` existed.
    */
  private def originTopicOf(internal: SubscriptionId, entry: DeadLetterEntry): Future[Option[TopicId]] =
    entry.originTopic match
      case Some(t) => Future.successful(Some(t))
      case None    => subscriptionIndex.topicFor(internal)

  /** A fresh message carrying the dead-letter's payload and attributes. A publish
    * mints a new id, so the original id survives only as the idempotency key and
    * in the dead-letter attributes.
    */
  private def rebuild(internal: SubscriptionId, entry: DeadLetterEntry): Either[ValidationError, Message] =
    for
      id <- MessageId.from(UUID.randomUUID().toString)
      msg <- Message.from(
        id,
        entry.payload.toArray,
        entry.attributes,
        Instant.now(),
        idempotencyKey = Some(s"redrive:${internal.value}:${entry.originalMessageId.value}")
      )
    yield msg

  private def toJson(entry: DeadLetterEntry): DeadLetterJson =
    DeadLetterJson(
      originalMessageId = entry.originalMessageId.value,
      payload = new String(entry.payload.toArray, UTF_8),
      attributes = entry.attributes,
      deliveryAttempts = entry.deliveryAttempts,
      deadLetteredAt = entry.deadLetteredAt.toString,
      originTopic = entry.originTopic.map(t => tenantScope.unqualify(principal.tenant, t.value)),
      redrivenAt = entry.redrivenAt.map(_.toString)
    )
