package me.cference.hermesmq.grpc

import com.google.protobuf.ByteString
import me.cference.hermesmq.auth.TenantScope
import me.cference.hermesmq.config.StreamConfig
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.grpc.{Message as ProtoMessage, PulledMessage as ProtoPulledMessage}
import me.cference.hermesmq.persistence.{CommandReply, PulledMessage as DomainPulledMessage, SubscriptionService, TopicService}
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.Source

import java.time.Instant
import java.util.UUID
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

/** gRPC handler for publishing and consuming. Implements the generated
  * [[PubSubService]] trait by delegating to the same topic/subscription services
  * the REST API uses — pull leases exactly as REST pull does. Domain rejections
  * become gRPC statuses via [[GrpcErrors]].
  */
final class PubSubGrpcService(
    topics: TopicService,
    subscriptions: SubscriptionService,
    streamConfig: StreamConfig = StreamConfig.Default
)(using ExecutionContext, ActorSystem)
    extends PubSubService:

  def publish(in: PublishRequest): Future[PublishResponse] =
    (TenantScope.validateExternalId(in.topicId).flatMap(TopicId.from), buildMessage(in)) match
      case (Right(topicId), Right(message)) =>
        submitTopic(topicId, TopicCommand.Publish(message)).map(_ => PublishResponse(messageId = message.id.value))
      case (Left(err), _) => Future.failed(GrpcErrors.invalid(err))
      case (_, Left(err)) => Future.failed(GrpcErrors.invalid(err))

  def createSubscription(in: CreateSubscriptionRequest): Future[CreateSubscriptionResponse] =
    (
      TenantScope.validateExternalId(in.subscriptionId).flatMap(SubscriptionId.from),
      TenantScope.validateExternalId(in.topicId).flatMap(TopicId.from)
    ) match
      case (Right(sid), Right(tid)) =>
        subscriptions.submit(sid, SubscriptionCommand.CreateSubscription(sid, tid)).map {
          case CommandReply.Accepted            => CreateSubscriptionResponse()
          case CommandReply.Rejected(rejection) => throw GrpcErrors.rejected(rejection)
        }
      case _ => Future.failed(GrpcErrors.invalid(ValidationError("invalid subscriptionId or topicId")))

  def pull(in: PullRequest): Future[PullResponse] =
    withSubId(in.subscriptionId) { sid =>
      subscriptions.pull(sid, in.max).map {
        case Some(messages) => PullResponse(messages = messages.map(toProto))
        case None           => throw GrpcErrors.rejected(Rejection.SubscriptionNotFound)
      }
    }

  def streamMessages(in: StreamRequest): Source[ProtoPulledMessage, NotUsed] =
    TenantScope.validateExternalId(in.subscriptionId).flatMap(SubscriptionId.from) match
      case Left(err) => Source.failed(GrpcErrors.invalid(err))
      case Right(sid) =>
        val batch = if in.max > 0 then in.max else streamConfig.batchSize
        // Probe existence first: an unknown subscription fails the stream NOT_FOUND;
        // otherwise stream leased messages, mapping each to proto.
        val futureSource = subscriptions.pull(sid, 0).map {
          case None => Source.failed[ProtoPulledMessage](GrpcErrors.rejected(Rejection.SubscriptionNotFound))
          case Some(_) =>
            MessageStream.leased[DomainPulledMessage](_ => subscriptions.pull(sid, batch), batch, streamConfig.pollInterval).map(toProto)
        }
        Source.futureSource(futureSource).mapMaterializedValue(_ => NotUsed)

  def ack(in: AckRequest): Future[AckResponse] =
    withExistingSub(in.subscriptionId) { sid =>
      applyBatch(sid, in.ackIds, a => SubscriptionCommand.Acknowledge(a)).map { (applied, unknown) =>
        AckResponse(acknowledged = applied, unknown = unknown)
      }
    }

  def modifyAckDeadline(in: ModifyAckDeadlineRequest): Future[ModifyAckDeadlineResponse] =
    withExistingSub(in.subscriptionId) { sid =>
      val deadline = in.ackDeadlineSeconds.seconds
      applyBatch(sid, in.ackIds, a => SubscriptionCommand.ModifyAckDeadline(a, deadline, Instant.now())).map {
        (applied, unknown) => ModifyAckDeadlineResponse(modified = applied, unknown = unknown)
      }
    }

  /** Parse a subscription id (blank/invalid → INVALID_ARGUMENT) then run `f`. */
  private def withSubId[A](raw: String)(f: SubscriptionId => Future[A]): Future[A] =
    TenantScope.validateExternalId(raw).flatMap(SubscriptionId.from) match
      case Right(sid) => f(sid)
      case Left(err)  => Future.failed(GrpcErrors.invalid(err))

  /** Run `f` only when the subscription exists (probed with a zero-max,
    * side-effect-free pull); otherwise fail with NOT_FOUND.
    */
  private def withExistingSub[A](raw: String)(f: SubscriptionId => Future[A]): Future[A] =
    withSubId(raw) { sid =>
      subscriptions.pull(sid, 0).flatMap {
        case Some(_) => f(sid)
        case None    => Future.failed(GrpcErrors.rejected(Rejection.SubscriptionNotFound))
      }
    }

  /** Submit one command per id (skipping malformed ids), partitioning ids into
    * applied vs unknown/not-applied. Never fails the batch for an unknown id.
    */
  private def applyBatch(
      sid: SubscriptionId,
      rawIds: Seq[String],
      command: AckId => SubscriptionCommand
  ): Future[(Seq[String], Seq[String])] =
    Future
      .traverse(rawIds) { raw =>
        AckId.from(raw) match
          case Left(_) => Future.successful((raw, false))
          case Right(a) =>
            subscriptions.submit(sid, command(a)).map {
              case CommandReply.Accepted    => (raw, true)
              case CommandReply.Rejected(_) => (raw, false)
            }
      }
      .map { results =>
        (results.collect { case (r, true) => r }, results.collect { case (r, false) => r })
      }

  private def submitTopic(id: TopicId, command: TopicCommand): Future[Unit] =
    topics.submit(id, command).map {
      case CommandReply.Accepted            => ()
      case CommandReply.Rejected(rejection) => throw GrpcErrors.rejected(rejection)
    }

  private def buildMessage(in: PublishRequest): Either[ValidationError, Message] =
    val id = MessageId.from(UUID.randomUUID().toString).toOption.get
    Message.from(id, in.payload.toByteArray, in.attributes, Instant.now())

  private def toProto(pm: DomainPulledMessage): ProtoPulledMessage =
    ProtoPulledMessage(ackId = pm.ackId.value, message = Some(toProtoMessage(pm.message)))

  private def toProtoMessage(m: Message): ProtoMessage =
    ProtoMessage(
      messageId = m.id.value,
      payload = ByteString.copyFrom(m.payload.toArray),
      attributes = m.attributes,
      publishTime = m.publishTime.toString
    )
