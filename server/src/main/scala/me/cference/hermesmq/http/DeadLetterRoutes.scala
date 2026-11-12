package me.cference.hermesmq.http

import me.cference.hermesmq.auth.Principal
import me.cference.hermesmq.auth.TenantScope
import me.cference.hermesmq.delivery.DeadLetterCursor
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
import spray.json.RootJsonFormat

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

/** A page of dead-letters plus, when more remain, the cursor for the next one. */
final case class DeadLetterPageJson(deadLetters: List[DeadLetterJson], nextCursor: Option[String] = None)

/** Either an explicit list of original message ids, or `all`.
  *
  * `force` republishes entries already marked redriven -- the escape hatch for a
  * claim taken by a caller that died before publishing.
  */
final case class RedriveRequest(
    messageIds: Option[List[String]] = None,
    all: Option[Boolean] = None,
    force: Option[Boolean] = None
)

final case class RedriveFailureJson(messageId: String, reason: String)

final case class RedriveResponse(
    redriven: List[String],
    alreadyRedriven: List[String],
    unknown: List[String],
    failed: List[RedriveFailureJson]
)

object DeadLetterJsonProtocol extends DefaultJsonProtocol:
  given RootJsonFormat[DeadLetterJson]      = jsonFormat7(DeadLetterJson.apply)
  given RootJsonFormat[DeadLetterPageJson]  = jsonFormat2(DeadLetterPageJson.apply)
  given RootJsonFormat[RedriveRequest]      = jsonFormat3(RedriveRequest.apply)
  given RootJsonFormat[RedriveFailureJson]  = jsonFormat2(RedriveFailureJson.apply)
  given RootJsonFormat[RedriveResponse]     = jsonFormat4(RedriveResponse.apply)

/** What happened to one id in a redrive request. */
private[http] enum RedriveOutcome:
  case Redriven(messageId: String)
  case AlreadyRedriven(messageId: String)
  case RedriveFailed(failure: RedriveFailureJson)

/** Opaque cursor codec. Opaque on purpose: a client that could construct a
  * cursor would be coupled to the sort key, which then could never change.
  */
private[http] object DeadLetterCursorCodec:
  import java.util.Base64

  def encode(cursor: DeadLetterCursor): String =
    val raw = s"${cursor.deadLetteredAt.toEpochMilli}:${cursor.originalMessageId.value}"
    Base64.getUrlEncoder.withoutPadding.encodeToString(raw.getBytes(UTF_8))

  /** `None` in, `Right(None)` out -- no cursor means "start from the newest".
    * Anything undecodable is a `Left`, surfaced as a 400 rather than a silent
    * restart of the walk.
    */
  def decode(raw: Option[String]): Either[String, Option[DeadLetterCursor]] =
    raw.filter(_.nonEmpty) match
      case None => Right(None)
      case Some(value) =>
        val decoded =
          for
            text <- scala.util.Try(new String(Base64.getUrlDecoder.decode(value), UTF_8)).toOption
            (millis, id) <- text.split(':').toList match
              case millis :: rest if rest.nonEmpty => Some((millis, rest.mkString(":")))
              case _                               => None
            at  <- scala.util.Try(Instant.ofEpochMilli(millis.toLong)).toOption
            mid <- MessageId.from(id).toOption
          yield DeadLetterCursor(at, mid)
        decoded.map(c => Right(Some(c))).getOrElse(Left("invalid cursor"))

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
  import RedriveOutcome.*
  import SprayJsonSupport.*

  private val DefaultPageSize = 50
  private val MaxPageSize     = 500

  val routes: Route =
    pathPrefix("v1" / "subscriptions") {
      concat(
        // Browse: GET /v1/subscriptions/{id}/dead-letters
        path(Segment / "dead-letters") { rawSub =>
          get {
            parameters("limit".as[Int].optional, "cursor".optional) { (rawLimit, rawCursor) =>
              withSubscription(rawSub) { (_, internal) =>
                // An over-large limit is a client being optimistic, not malformed,
                // so clamp rather than reject. A bad cursor IS rejected: silently
                // restarting the walk would hide the error from a paging client.
                val limit = rawLimit.getOrElse(DefaultPageSize).max(1).min(MaxPageSize)
                DeadLetterCursorCodec.decode(rawCursor) match
                  case Left(err) => complete(StatusCodes.BadRequest, err)
                  case Right(cursor) =>
                    onComplete(deadLetters.listFor(internal, limit, cursor)) {
                      case Success(page) =>
                        complete(
                          DeadLetterPageJson(
                            deadLetters = page.entries.map(toJson),
                            nextCursor = page.nextCursor.map(DeadLetterCursorCodec.encode)
                          )
                        )
                      case Failure(_) => complete(StatusCodes.ServiceUnavailable)
                    }
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
                      onComplete(redrive(internal, selector, req.force.contains(true))) {
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
  private def redrive(
      internal: SubscriptionId,
      selector: Either[Unit, List[String]],
      force: Boolean
  ): Future[RedriveResponse] =
    val selected: Future[(List[DeadLetterEntry], List[String])] = selector match
      case Left(_) => allFor(internal).map(_ -> Nil)
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
      // No up-front partition on redrivenAt: that was a read-then-write race, so
      // the claim below is what decides who publishes. A loser of the claim is
      // reported as already redriven.
      Future.traverse(entries)(redriveOne(internal, _, force)).map { outcomes =>
        RedriveResponse(
          redriven = outcomes.collect { case Redriven(id) => id },
          alreadyRedriven = outcomes.collect { case AlreadyRedriven(id) => id },
          unknown = unknown,
          failed = outcomes.collect { case RedriveFailed(failure) => failure }
        )
      }
    }

  /** Every dead-letter of a subscription, walked a page at a time.
    *
    * `all` means all: redrive is an operator action whose response reports each
    * id, so truncating it silently would be worse than the walk. The page size is
    * the browse maximum, so this issues bounded queries rather than one unbounded
    * scan.
    */
  private def allFor(internal: SubscriptionId): Future[List[DeadLetterEntry]] =
    def walk(cursor: Option[DeadLetterCursor], acc: List[DeadLetterEntry]): Future[List[DeadLetterEntry]] =
      deadLetters.listFor(internal, MaxPageSize, cursor).flatMap { page =>
        val gathered = acc ++ page.entries
        page.nextCursor match
          case Some(next) => walk(Some(next), gathered)
          case None       => Future.successful(gathered)
      }
    walk(None, Nil)

  /** Republish one dead-letter to its origin topic, behind an atomic claim.
    *
    * Claim first, publish second. The orderings are not equally bad: publishing
    * before marking risks a DUPLICATE message if two callers race, which is
    * unbounded harm in a broker; claiming first risks a STUCK entry if this
    * process dies before publishing, which is visible in the response and
    * recoverable with `force`. A failed publish releases the claim so a plain
    * retry works.
    *
    * This is what makes redrive idempotency unconditional -- it no longer depends
    * on `HERMESMQ_DEDUP_WINDOW`, which is off by default. The idempotency key is
    * still passed to the Topic aggregate as a second line of defence.
    */
  private def redriveOne(internal: SubscriptionId, entry: DeadLetterEntry, force: Boolean): Future[RedriveOutcome] =
    val externalMsgId = entry.originalMessageId.value
    def fail(reason: String): RedriveOutcome = RedriveFailed(RedriveFailureJson(externalMsgId, reason))

    deadLetters.claimForRedrive(internal, entry.originalMessageId, Instant.now(), force).flatMap {
      case false => Future.successful(AlreadyRedriven(externalMsgId))
      case true =>
        // Release the claim on any non-success, so the entry stays redrivable.
        def releasing(outcome: RedriveOutcome): Future[RedriveOutcome] =
          deadLetters.releaseClaim(internal, entry.originalMessageId).map(_ => outcome)

        originTopicOf(internal, entry).flatMap {
          case None => releasing(fail("origin topic could not be resolved"))
          case Some(topic) =>
            rebuild(internal, entry) match
              case Left(err) => releasing(fail(err.message))
              case Right(message) =>
                topics
                  .submit(topic, TopicCommand.Publish(message))
                  .flatMap {
                    case CommandReply.Published(_, _) | CommandReply.Accepted =>
                      Future.successful(Redriven(externalMsgId))
                    case CommandReply.Rejected(err) =>
                      releasing(fail(s"origin topic rejected the republish: $err"))
                  }
                  .recoverWith { case e =>
                    releasing(fail(Option(e.getMessage).getOrElse("republish failed")))
                  }
        }
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
