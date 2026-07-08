package me.cference.hermesmq.grpc

import io.grpc.Status
import me.cference.hermesmq.auth.{AuthKey, Authenticator, TenantId, TenantScope}
import me.cference.hermesmq.config.AuthConfig
import me.cference.hermesmq.domain.*
import me.cference.hermesmq.persistence.*
import org.apache.pekko.grpc.GrpcServiceException
import org.apache.pekko.grpc.scaladsl.{Metadata, MetadataBuilder}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.util.Base64
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.*

/** Tests gRPC authentication + authorization via call metadata on the power APIs. */
final class GrpcAuthSpec extends AnyWordSpec with Matchers:

  private val salt      = Base64.getEncoder.encodeToString("salt".getBytes)
  private val adminTok  = "admin-token"
  private val dataTok   = "data-token"
  private val adminKey  = AuthKey(TenantId.from("acme").toOption.get, salt, Authenticator.computeHash(salt, adminTok), Set("admin"))
  private val dataKey   = AuthKey(TenantId.from("acme").toOption.get, salt, Authenticator.computeHash(salt, dataTok), Set.empty)
  private val auth      = Authenticator(List(adminKey, dataKey))
  private val scope     = new TenantScope(TenantScope.DefaultTenant)
  private val config    = AuthConfig(enabled = true, TenantScope.DefaultTenant, List(adminKey, dataKey))

  private def topicStub: TopicService = new TopicService:
    def submit(id: TopicId, command: TopicCommand): Future[CommandReply] = Future.successful(CommandReply.Accepted)
    def query(id: TopicId): Future[Option[TopicSnapshot]]                = Future.successful(Some(TopicSnapshot(id, Map.empty)))
  private def subStub: SubscriptionService = new SubscriptionService:
    def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] = Future.successful(CommandReply.Accepted)
    def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]]         = Future.successful(Some(Nil))

  private val topicApi = TopicAdminPowerApi(topicStub, auth, scope, config)
  private val pubApi   = PubSubPowerApi(topicStub, subStub, auth, scope, config)

  private def bearer(token: String): Metadata = new MetadataBuilder().addText("authorization", s"Bearer $token").build()
  private def noAuth: Metadata                = MetadataBuilder.empty

  private def statusOfFailure(f: Future[?]): Status.Code =
    Await.result(f.failed, 3.seconds) match
      case e: GrpcServiceException => e.status.getCode
      case other                   => fail(s"expected GrpcServiceException, got $other")

  "gRPC authentication" should {
    "allow an admin token to create a topic" in {
      Await.result(topicApi.createTopic(CreateTopicRequest(topicId = "orders"), bearer(adminTok)), 3.seconds)
      succeed
    }

    "reject a missing credential with UNAUTHENTICATED" in {
      statusOfFailure(topicApi.getTopic(GetTopicRequest(topicId = "orders"), noAuth)) shouldBe Status.Code.UNAUTHENTICATED
    }

    "reject an invalid token with UNAUTHENTICATED" in {
      statusOfFailure(pubApi.pull(PullRequest(subscriptionId = "s1", max = 10), bearer("nope"))) shouldBe Status.Code.UNAUTHENTICATED
    }

    "deny topic administration to a non-admin token with PERMISSION_DENIED" in {
      statusOfFailure(topicApi.createTopic(CreateTopicRequest(topicId = "orders"), bearer(dataTok))) shouldBe Status.Code.PERMISSION_DENIED
    }

    "allow a non-admin token to pull (data-plane)" in {
      Await.result(pubApi.pull(PullRequest(subscriptionId = "s1", max = 10), bearer(dataTok)), 3.seconds)
      succeed
    }
  }
