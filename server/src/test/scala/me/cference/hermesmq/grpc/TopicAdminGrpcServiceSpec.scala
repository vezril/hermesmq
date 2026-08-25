package me.cference.hermesmq.grpc

import io.grpc.Status
import me.cference.hermesmq.domain.Rejection
import me.cference.hermesmq.domain.TopicCommand
import me.cference.hermesmq.domain.TopicId
import me.cference.hermesmq.persistence.CommandReply
import me.cference.hermesmq.persistence.TopicService
import me.cference.hermesmq.persistence.TopicSnapshot
import org.apache.pekko.grpc.GrpcServiceException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Tests the topic-admin gRPC handler against stub services (no socket). */
final class TopicAdminGrpcServiceSpec extends AnyWordSpec with Matchers:

  private def await[A](f: => Future[A]): A = Await.result(f, 3.seconds)

  private def statusOfFailure(f: Future[?]): Status.Code =
    Await.result(f.failed, 3.seconds) match
      case e: GrpcServiceException => e.status.getCode
      case other                   => fail(s"expected GrpcServiceException, got $other")

  private def topics(reply: CommandReply = CommandReply.Accepted, snap: Option[TopicSnapshot] = None): TopicService =
    new TopicService:
      def submit(id: TopicId, command: TopicCommand): Future[CommandReply]  = Future.successful(reply)
      def query(id: TopicId): Future[Option[TopicSnapshot]]                 = Future.successful(snap)

  private def service(t: TopicService) = TopicAdminGrpcService(t)

  "TopicAdminGrpcService" should {
    "create a topic and return an empty response" in {
      val _ = await(service(topics()).createTopic(CreateTopicRequest(topicId = "orders", labels = Map("team" -> "pay"))))
      succeed
    }

    "map a duplicate create to ALREADY_EXISTS" in {
      val svc = service(topics(reply = CommandReply.Rejected(Rejection.TopicAlreadyExists)))
      statusOfFailure(svc.createTopic(CreateTopicRequest(topicId = "orders"))) shouldBe Status.Code.ALREADY_EXISTS
    }

    "map a blank topic id to INVALID_ARGUMENT" in {
      statusOfFailure(service(topics()).createTopic(CreateTopicRequest(topicId = ""))) shouldBe Status.Code.INVALID_ARGUMENT
    }

    "return a topic with its labels on get" in {
      val snap = TopicSnapshot(TopicId.from("orders").toOption.get, Map("team" -> "pay"))
      val resp = await(service(topics(snap = Some(snap))).getTopic(GetTopicRequest(topicId = "orders")))
      val _ = resp.topic.map(_.topicId) shouldBe Some("orders")
      resp.topic.map(_.labels) shouldBe Some(Map("team" -> "pay"))
    }

    "map get of a missing topic to NOT_FOUND" in {
      statusOfFailure(service(topics(snap = None)).getTopic(GetTopicRequest(topicId = "ghost"))) shouldBe Status.Code.NOT_FOUND
    }

    "update a topic and echo the new labels" in {
      val resp = await(service(topics()).updateTopic(UpdateTopicRequest(topicId = "orders", labels = Map("team" -> "core"))))
      resp.topic.map(_.labels) shouldBe Some(Map("team" -> "core"))
    }

    "map update of a missing topic to NOT_FOUND" in {
      val svc = service(topics(reply = CommandReply.Rejected(Rejection.TopicNotFound)))
      statusOfFailure(svc.updateTopic(UpdateTopicRequest(topicId = "ghost"))) shouldBe Status.Code.NOT_FOUND
    }

    "delete a topic and return an empty response" in {
      val _ = await(service(topics()).deleteTopic(DeleteTopicRequest(topicId = "orders")))
      succeed
    }

    "map delete of a missing topic to NOT_FOUND" in {
      val svc = service(topics(reply = CommandReply.Rejected(Rejection.TopicNotFound)))
      statusOfFailure(svc.deleteTopic(DeleteTopicRequest(topicId = "ghost"))) shouldBe Status.Code.NOT_FOUND
    }
  }
