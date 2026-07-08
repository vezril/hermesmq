package me.cference.hermesmq.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.*
import org.apache.pekko.grpc.GrpcServiceException
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Tests the pub/sub gRPC handler against stub services (no socket). */
final class PubSubGrpcServiceSpec extends AnyWordSpec with Matchers:

  private val ackId = AckId.from("ack-1").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hello".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  private def await[A](f: => Future[A]): A = Await.result(f, 3.seconds)

  private def statusOfFailure(f: Future[?]): Status.Code =
    Await.result(f.failed, 3.seconds) match
      case e: GrpcServiceException => e.status.getCode
      case other                   => fail(s"expected GrpcServiceException, got $other")

  private def topics(reply: CommandReply = CommandReply.Accepted): TopicService = new TopicService:
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] = Future.successful(reply)
    def query(id: TopicId): Future[Option[TopicSnapshot]]                = Future.successful(None)

  private def subs(
      submitReply: CommandReply = CommandReply.Accepted,
      pullReply: Option[List[PulledMessage]] = Some(Nil)
  ): SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] = Future.successful(submitReply)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]]         = Future.successful(pullReply)

  private def service(t: TopicService = topics(), s: SubscriptionService = subs()) = PubSubGrpcService(t, s)

  "PubSubGrpcService" should {
    "publish a message and return a non-empty messageId" in {
      val resp = await(service().publish(PublishRequest(topicId = "orders", payload = ByteString.copyFromUtf8("hi"))))
      resp.messageId should not be empty
    }

    "map an empty payload to INVALID_ARGUMENT" in {
      statusOfFailure(service().publish(PublishRequest(topicId = "orders", payload = ByteString.EMPTY))) shouldBe
        Status.Code.INVALID_ARGUMENT
    }

    "map publish to a missing topic to NOT_FOUND" in {
      val svc = service(t = topics(CommandReply.Rejected(Rejection.TopicNotFound)))
      statusOfFailure(svc.publish(PublishRequest(topicId = "ghost", payload = ByteString.copyFromUtf8("hi")))) shouldBe
        Status.Code.NOT_FOUND
    }

    "create a subscription" in {
      await(service().createSubscription(CreateSubscriptionRequest(subscriptionId = "s1", topicId = "orders")))
      succeed
    }

    "map a duplicate subscription to ALREADY_EXISTS" in {
      val svc = service(s = subs(submitReply = CommandReply.Rejected(Rejection.SubscriptionAlreadyExists)))
      statusOfFailure(svc.createSubscription(CreateSubscriptionRequest(subscriptionId = "s1", topicId = "orders"))) shouldBe
        Status.Code.ALREADY_EXISTS
    }

    "pull leased messages with their ackIds" in {
      val svc  = service(s = subs(pullReply = Some(List(PulledMessage(ackId, message)))))
      val resp = await(svc.pull(PullRequest(subscriptionId = "s1", max = 10)))
      resp.messages.map(_.ackId) shouldBe Seq("ack-1")
      resp.messages.head.message.map(_.messageId) shouldBe Some("m-1")
      resp.messages.head.message.map(_.payload.toStringUtf8) shouldBe Some("hello")
    }

    "map pull of a missing subscription to NOT_FOUND" in {
      statusOfFailure(service(s = subs(pullReply = None)).pull(PullRequest(subscriptionId = "ghost", max = 10))) shouldBe
        Status.Code.NOT_FOUND
    }

    "acknowledge ids and report them acknowledged" in {
      val resp = await(service().ack(AckRequest(subscriptionId = "s1", ackIds = Seq("ack-1"))))
      resp.acknowledged shouldBe Seq("ack-1")
      resp.unknown shouldBe empty
    }

    "report an unknown ackId without failing the batch" in {
      val svc  = service(s = subs(submitReply = CommandReply.Rejected(Rejection.UnknownAckId(ackId))))
      val resp = await(svc.ack(AckRequest(subscriptionId = "s1", ackIds = Seq("ack-1"))))
      resp.unknown shouldBe Seq("ack-1")
    }

    "map ack on a missing subscription to NOT_FOUND" in {
      val svc = service(s = subs(pullReply = None))
      statusOfFailure(svc.ack(AckRequest(subscriptionId = "ghost", ackIds = Seq("ack-1")))) shouldBe Status.Code.NOT_FOUND
    }

    "modify ack deadlines and report modified ids" in {
      val resp = await(service().modifyAckDeadline(ModifyAckDeadlineRequest(subscriptionId = "s1", ackIds = Seq("ack-1"), ackDeadlineSeconds = 60)))
      resp.modified shouldBe Seq("ack-1")
    }

    "map modifyAckDeadline on a missing subscription to NOT_FOUND" in {
      val svc = service(s = subs(pullReply = None))
      statusOfFailure(svc.modifyAckDeadline(ModifyAckDeadlineRequest(subscriptionId = "ghost", ackIds = Seq("ack-1"), ackDeadlineSeconds = 60))) shouldBe
        Status.Code.NOT_FOUND
    }
  }
