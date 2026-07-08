package me.cference.hermesmq.grpc

import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import me.cference.hermesmq.auth.{Authenticator, TenantScope}
import me.cference.hermesmq.config.{AuthConfig, GrpcConfig}
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.*
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.grpc.GrpcClientSettings
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.time.Instant
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/** End-to-end gRPC over HTTP/2: binds the real handler on an ephemeral port and
  * drives it with the generated client stub, proving codegen + wiring + status
  * propagation. Backed by stub services (real semantics are covered by the
  * handler/domain unit tests).
  */
final class GrpcApiIntegrationSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers:

  private given scala.concurrent.ExecutionContext = system.executionContext
  private given org.apache.pekko.actor.ActorSystem = system.classicSystem

  private val ackId = AckId.from("ack-1").toOption.get
  private val message = Message
    .from(MessageId.from("m-1").toOption.get, "hello".getBytes, Map("k" -> "v"), Instant.parse("2026-07-07T00:00:00Z"))
    .toOption
    .get

  // Topic id "dup" is rejected as already-existing; everything else is accepted.
  private val topicSvc: TopicService = new TopicService:
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
      if id.value == "dup" then Future.successful(CommandReply.Rejected(Rejection.TopicAlreadyExists))
      else Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]] = Future.successful(Some(TopicSnapshot(id, Map("team" -> "pay"))))

  private val subSvc: SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] = Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
      Future.successful(Some(if max == 0 then Nil else List(PulledMessage(ackId, message))))

  private var binding: ServerBinding             = _
  private var topics: TopicAdminServiceClient    = _
  private var pubsub: PubSubServiceClient        = _

  override protected def beforeAll(): Unit =
    super.beforeAll()
    // Auth disabled → default tenant, unqualified ids (this suite tests wiring, not auth).
    val authConfig    = AuthConfig(enabled = false, TenantScope.DefaultTenant, Nil)
    val authenticator = Authenticator(Nil)
    val scope         = new TenantScope(TenantScope.DefaultTenant)
    binding = Await.result(
      GrpcServer.start(
        system,
        GrpcConfig("127.0.0.1", 0),
        TopicAdminPowerApi(topicSvc, authenticator, scope, authConfig),
        PubSubPowerApi(topicSvc, subSvc, authenticator, scope, authConfig)
      ),
      10.seconds
    )
    val settings = GrpcClientSettings.connectToServiceAt("127.0.0.1", binding.localAddress.getPort)(system).withTls(false)
    topics = TopicAdminServiceClient(settings)(system)
    pubsub = PubSubServiceClient(settings)(system)

  override protected def afterAll(): Unit =
    if topics != null then topics.close()
    if pubsub != null then pubsub.close()
    if binding != null then Await.ready(binding.unbind(), 5.seconds)
    super.afterAll()

  private def await[A](f: => Future[A]): A = Await.result(f, 10.seconds)

  private def statusOfFailure(f: Future[?]): Status.Code =
    Await.result(f.failed, 10.seconds) match
      case e: StatusRuntimeException => e.getStatus.getCode
      case other                     => fail(s"expected StatusRuntimeException, got $other")

  "The gRPC API over HTTP/2" should {
    "round-trip create topic → publish → subscribe → pull → ack via the generated client" in {
      await(topics.createTopic(CreateTopicRequest(topicId = "orders", labels = Map("team" -> "pay"))))
      val published = await(pubsub.publish(PublishRequest(topicId = "orders", payload = ByteString.copyFromUtf8("hi"))))
      published.messageId should not be empty

      await(pubsub.createSubscription(CreateSubscriptionRequest(subscriptionId = "s1", topicId = "orders")))

      val pulled = await(pubsub.pull(PullRequest(subscriptionId = "s1", max = 10)))
      pulled.messages.map(_.ackId) shouldBe Seq("ack-1")
      pulled.messages.head.message.map(_.payload.toStringUtf8) shouldBe Some("hello")

      val acked = await(pubsub.ack(AckRequest(subscriptionId = "s1", ackIds = Seq("ack-1"))))
      acked.acknowledged shouldBe Seq("ack-1")
    }

    "propagate a rejection to the client as the mapped gRPC status" in {
      statusOfFailure(topics.createTopic(CreateTopicRequest(topicId = "dup"))) shouldBe Status.Code.ALREADY_EXISTS
    }

    "read a topic back over the wire" in {
      val resp = await(topics.getTopic(GetTopicRequest(topicId = "orders")))
      resp.topic.map(_.topicId) shouldBe Some("orders")
    }

    "stream leased messages to the generated client" in {
      val got = Await.result(
        pubsub.streamMessages(StreamRequest(subscriptionId = "s1", max = 1)).take(2).runWith(org.apache.pekko.stream.scaladsl.Sink.seq),
        10.seconds
      )
      got.map(_.ackId) shouldBe Seq("ack-1", "ack-1")
      got.head.message.map(_.payload.toStringUtf8) shouldBe Some("hello")
    }
  }
