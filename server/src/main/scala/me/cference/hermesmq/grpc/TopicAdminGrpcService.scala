package me.cference.hermesmq.grpc

import me.cference.hermesmq.domain.{Rejection, TopicCommand, TopicId}
import me.cference.hermesmq.persistence.{CommandReply, TopicService, TopicSnapshot}

import scala.concurrent.{ExecutionContext, Future}

/** gRPC handler for topic administration. Implements the generated
  * [[TopicAdminService]] trait by delegating to the same [[TopicService]] the REST
  * admin API uses; domain rejections become gRPC statuses via [[GrpcErrors]].
  */
final class TopicAdminGrpcService(topics: TopicService)(using ExecutionContext) extends TopicAdminService:

  def createTopic(in: CreateTopicRequest): Future[CreateTopicResponse] =
    withTopicId(in.topicId) { id =>
      submit(id, TopicCommand.CreateTopic(id, in.labels)).map(_ => CreateTopicResponse())
    }

  def getTopic(in: GetTopicRequest): Future[GetTopicResponse] =
    withTopicId(in.topicId) { id =>
      topics.query(id).map {
        case Some(snap) => GetTopicResponse(topic = Some(toProto(snap)))
        case None       => throw GrpcErrors.rejected(Rejection.TopicNotFound)
      }
    }

  def updateTopic(in: UpdateTopicRequest): Future[UpdateTopicResponse] =
    withTopicId(in.topicId) { id =>
      submit(id, TopicCommand.UpdateTopic(in.labels)).map { _ =>
        UpdateTopicResponse(topic = Some(Topic(topicId = id.value, labels = in.labels)))
      }
    }

  def deleteTopic(in: DeleteTopicRequest): Future[DeleteTopicResponse] =
    withTopicId(in.topicId) { id =>
      submit(id, TopicCommand.DeleteTopic).map(_ => DeleteTopicResponse())
    }

  /** Parse a topic id (blank/invalid → INVALID_ARGUMENT) then run `f`. */
  private def withTopicId[A](raw: String)(f: TopicId => Future[A]): Future[A] =
    TopicId.from(raw) match
      case Right(id) => f(id)
      case Left(err) => Future.failed(GrpcErrors.invalid(err))

  /** Submit a write command, failing the RPC with the mapped status on rejection. */
  private def submit(id: TopicId, command: TopicCommand): Future[Unit] =
    topics.submit(id, command).map {
      case CommandReply.Accepted            => ()
      case CommandReply.Rejected(rejection) => throw GrpcErrors.rejected(rejection)
    }

  private def toProto(snap: TopicSnapshot): Topic =
    Topic(topicId = snap.topicId.value, labels = snap.labels)
