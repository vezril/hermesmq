package me.cference.hermesmq.grpc

import com.google.protobuf.ByteString
import io.grpc.Status
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.observability.ConsumerRegistry
import me.cference.hermesmq.persistence.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.slf4j.MDC

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Tests the pub/sub gRPC handler against stub services (no socket). */
final class PubSubGrpcServiceSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers with Eventually:

  private given org.apache.pekko.actor.ActorSystem = system.classicSystem
  private given scala.concurrent.ExecutionContext  = system.executionContext

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
    // Publish replies Published; a default Accepted becomes Published(id,false),
    // while an explicit Rejected still flows through.
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] = Future.successful((command, reply) match
      case (TopicCommand.Publish(m), CommandReply.Accepted) => CommandReply.Published(m.id, deduplicated = false)
      case _                                                => reply)
    def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)

  private def subs(
      submitReply: CommandReply = CommandReply.Accepted,
      pullReply: Option[List[PulledMessage]] = Some(Nil)
  ): SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] = Future.successful(submitReply)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]]         = Future.successful(pullReply)

  private def service(t: TopicService = topics(), s: SubscriptionService = subs()) = PubSubGrpcService(t, s)

  /** Records acknowledged ids; pull returns a fixed reply. */
  private final class CapturingSubs(pullReply: Option[List[PulledMessage]]) extends SubscriptionService:
    val acked = new ConcurrentLinkedQueue[String]()
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
      command match
        case SubscriptionCommand.Acknowledge(a) => acked.add(a.value)
        case _                                  => ()
      Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] = Future.successful(pullReply)

  private def start(sub: String, max: Int) = ConsumeRequest().withStart(ConsumeStart(subscriptionId = sub, max = max))
  private def ackReq(ids: String*)          = ConsumeRequest().withAck(ConsumeAck(ackIds = ids))

  "PubSubGrpcService publish TTL" should {
    "set expireTime from ttlSeconds" in {
      @volatile var published: Option[Message] = None
      val capturing = new TopicService:
        def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
          command match
            case TopicCommand.Publish(m) => published = Some(m); Future.successful(CommandReply.Published(m.id, deduplicated = false))
            case _                       => Future.successful(CommandReply.Accepted)
        def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)
      val _ = await(PubSubGrpcService(capturing, subs()).publish(PublishRequest(topicId = "orders", payload = ByteString.copyFromUtf8("hi"), ttlSeconds = 60)))
      published.flatMap(_.expireTime).isDefined shouldBe true
    }
  }

  "PubSubGrpcService.consume" should {
    "open on ConsumeStart, stream leased messages, and apply inbound acks" in {
      val subsvc = CapturingSubs(Some(List(PulledMessage(ackId, message))))
      val in     = Source(List(start("s1", 1), ackReq("ack-1")))
      val got    = Await.result(PubSubGrpcService(topics(), subsvc).consume(in).take(2).runWith(Sink.seq), 3.seconds)
      val _ = got.map(_.ackId) shouldBe Seq("ack-1", "ack-1")
      eventually(timeout(3.seconds))(subsvc.acked.asScala should contain("ack-1"))
    }

    "not fail the stream on an unknown ackId" in {
      val subsvc = CapturingSubs(Some(List(PulledMessage(ackId, message))))
      val in     = Source(List(start("s1", 1), ackReq("nope")))
      // Stream still yields messages despite the unknown ack.
      Await.result(PubSubGrpcService(topics(), subsvc).consume(in).take(1).runWith(Sink.seq), 3.seconds) should not be empty
    }

    "fail INVALID_ARGUMENT when the first message is not a ConsumeStart" in {
      val in = Source.single(ackReq("ack-1"))
      statusOfFailure(service().consume(in).runWith(Sink.ignore)) shouldBe Status.Code.INVALID_ARGUMENT
    }

    "fail NOT_FOUND for an unknown subscription" in {
      val svc = service(s = subs(pullReply = None))
      statusOfFailure(svc.consume(Source.single(start("ghost", 1))).runWith(Sink.ignore)) shouldBe Status.Code.NOT_FOUND
    }
  }

  "PubSubGrpcService.streamMessages" should {
    "stream the subscription's leased messages" in {
      val svc = service(s = subs(pullReply = Some(List(PulledMessage(ackId, message)))))
      val got = Await.result(
        svc.streamMessages(StreamRequest(subscriptionId = "s1", max = 1)).take(2).runWith(org.apache.pekko.stream.scaladsl.Sink.seq),
        3.seconds
      )
      val _ = got.map(_.ackId) shouldBe Seq("ack-1", "ack-1")
      got.head.message.map(_.payload.toStringUtf8) shouldBe Some("hello")
    }

    "fail the stream NOT_FOUND for an unknown subscription" in {
      val svc = service(s = subs(pullReply = None))
      statusOfFailure(svc.streamMessages(StreamRequest(subscriptionId = "ghost", max = 10)).runWith(org.apache.pekko.stream.scaladsl.Sink.ignore)) shouldBe
        Status.Code.NOT_FOUND
    }
  }

  "PubSubGrpcService" should {
    "publish a message and return a non-empty messageId" in {
      val resp = await(service().publish(PublishRequest(topicId = "orders", payload = ByteString.copyFromUtf8("hi"))))
      val _ = resp.messageId should not be empty
      resp.deduplicated shouldBe false
    }

    "forward the idempotency_key into the published message" in {
      @volatile var published: Option[Message] = None
      val capturing = new TopicService:
        def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
          command match
            case TopicCommand.Publish(m) => published = Some(m); Future.successful(CommandReply.Published(m.id, deduplicated = false))
            case _                       => Future.successful(CommandReply.Accepted)
        def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(None)
      val _ = await(PubSubGrpcService(capturing, subs()).publish(
        PublishRequest(topicId = "orders", payload = ByteString.copyFromUtf8("hi"), idempotencyKey = "abc")
      ))
      published.flatMap(_.idempotencyKey) shouldBe Some("abc")
    }

    "return deduplicated=true and the original messageId when the aggregate reports a duplicate" in {
      val original = MessageId.from("orig-1").toOption.get
      val svc      = service(t = topics(CommandReply.Published(original, deduplicated = true)))
      val resp = await(svc.publish(
        PublishRequest(topicId = "orders", payload = ByteString.copyFromUtf8("hi"), idempotencyKey = "abc")
      ))
      val _ = resp.messageId shouldBe "orig-1"
      resp.deduplicated shouldBe true
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
      val _ = await(service().createSubscription(CreateSubscriptionRequest(subscriptionId = "s1", topicId = "orders")))
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
      val _ = resp.messages.map(_.ackId) shouldBe Seq("ack-1")
      val _ = resp.messages.head.message.map(_.messageId) shouldBe Some("m-1")
      resp.messages.head.message.map(_.payload.toStringUtf8) shouldBe Some("hello")
    }

    "record a named consumer as active on pull" in {
      val reg = ConsumerRegistry(1.minute)
      val svc = PubSubGrpcService(topics(), subs(), consumers = reg)
      val _ = await(svc.pull(PullRequest(subscriptionId = "orders", max = 1, consumerId = "worker-3")))
      reg.activeCount(SubscriptionId.from("orders").toOption.get, Instant.now()) shouldBe 1
    }

    "not record a consumer when the id is empty (anonymous pull)" in {
      val reg = ConsumerRegistry(1.minute)
      val svc = PubSubGrpcService(topics(), subs(), consumers = reg)
      val _ = await(svc.pull(PullRequest(subscriptionId = "orders", max = 1, consumerId = "")))
      reg.activeCount(SubscriptionId.from("orders").toOption.get, Instant.now()) shouldBe 0
    }

    "record a named consumer when a stream is opened" in {
      val reg = ConsumerRegistry(1.minute)
      val svc = PubSubGrpcService(topics(), subs(), consumers = reg)
      val _   = svc.streamMessages(StreamRequest(subscriptionId = "orders", consumerId = "stream-1")) // touch at open
      reg.activeCount(SubscriptionId.from("orders").toOption.get, Instant.now()) shouldBe 1
    }

    "set the consumer MDC during a pull and clear it afterwards" in {
      @volatile var during: Option[String] = None
      val capturing = new SubscriptionService:
        def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] = Future.successful(CommandReply.Accepted)
        def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
          during = Option(MDC.get("consumer"))
          Future.successful(Some(Nil))
      val svc = PubSubGrpcService(topics(), capturing, consumers = ConsumerRegistry(1.minute))
      val _ = await(svc.pull(PullRequest(subscriptionId = "orders", max = 1, consumerId = "worker-3")))
      val _ = during shouldBe Some("worker-3")
      Option(MDC.get("consumer")) shouldBe None
    }

    "map pull of a missing subscription to NOT_FOUND" in {
      statusOfFailure(service(s = subs(pullReply = None)).pull(PullRequest(subscriptionId = "ghost", max = 10))) shouldBe
        Status.Code.NOT_FOUND
    }

    "acknowledge ids and report them acknowledged" in {
      val resp = await(service().ack(AckRequest(subscriptionId = "s1", ackIds = Seq("ack-1"))))
      val _ = resp.acknowledged shouldBe Seq("ack-1")
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
