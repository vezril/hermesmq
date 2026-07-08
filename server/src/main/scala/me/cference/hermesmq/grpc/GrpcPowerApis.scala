package me.cference.hermesmq.grpc

import me.cference.hermesmq.auth.{Authenticator, TenantScope, TenantScopedSubscriptionService, TenantScopedTopicService}
import me.cference.hermesmq.config.AuthConfig
import me.cference.hermesmq.persistence.{SubscriptionService, TopicService}
import org.apache.pekko.grpc.scaladsl.Metadata

import scala.concurrent.{ExecutionContext, Future}

/** Metadata-aware topic-admin gRPC handler: authenticates from call metadata,
  * enforces the `admin` scope on writes, and delegates to a per-call
  * tenant-scoped [[TopicAdminGrpcService]] so operations stay within the tenant.
  */
final class TopicAdminPowerApi(
    base: TopicService,
    authenticator: Authenticator,
    scope: TenantScope,
    config: AuthConfig
)(using ExecutionContext)
    extends TopicAdminServicePowerApi:

  def createTopic(in: CreateTopicRequest, metadata: Metadata): Future[CreateTopicResponse] =
    authed(metadata, admin = true)(_.createTopic(in))
  def getTopic(in: GetTopicRequest, metadata: Metadata): Future[GetTopicResponse] =
    authed(metadata, admin = false)(_.getTopic(in))
  def updateTopic(in: UpdateTopicRequest, metadata: Metadata): Future[UpdateTopicResponse] =
    authed(metadata, admin = true)(_.updateTopic(in))
  def deleteTopic(in: DeleteTopicRequest, metadata: Metadata): Future[DeleteTopicResponse] =
    authed(metadata, admin = true)(_.deleteTopic(in))

  private def authed[A](metadata: Metadata, admin: Boolean)(f: TopicAdminGrpcService => Future[A]): Future[A] =
    GrpcAuth.principal(metadata, authenticator, config) match
      case None                                     => Future.failed(GrpcErrors.unauthenticated)
      case Some(p) if admin && !p.hasScope("admin") => Future.failed(GrpcErrors.permissionDenied)
      case Some(p)                                  => f(new TopicAdminGrpcService(new TenantScopedTopicService(base, scope, p.tenant)))

/** Metadata-aware pub/sub gRPC handler: authenticates from call metadata and
  * delegates to a per-call tenant-scoped [[PubSubGrpcService]].
  */
final class PubSubPowerApi(
    baseTopics: TopicService,
    baseSubs: SubscriptionService,
    authenticator: Authenticator,
    scope: TenantScope,
    config: AuthConfig
)(using ExecutionContext)
    extends PubSubServicePowerApi:

  def publish(in: PublishRequest, metadata: Metadata): Future[PublishResponse] =
    authed(metadata)(_.publish(in))
  def createSubscription(in: CreateSubscriptionRequest, metadata: Metadata): Future[CreateSubscriptionResponse] =
    authed(metadata)(_.createSubscription(in))
  def pull(in: PullRequest, metadata: Metadata): Future[PullResponse] =
    authed(metadata)(_.pull(in))
  def ack(in: AckRequest, metadata: Metadata): Future[AckResponse] =
    authed(metadata)(_.ack(in))
  def modifyAckDeadline(in: ModifyAckDeadlineRequest, metadata: Metadata): Future[ModifyAckDeadlineResponse] =
    authed(metadata)(_.modifyAckDeadline(in))

  private def authed[A](metadata: Metadata)(f: PubSubGrpcService => Future[A]): Future[A] =
    GrpcAuth.principal(metadata, authenticator, config) match
      case None => Future.failed(GrpcErrors.unauthenticated)
      case Some(p) =>
        f(new PubSubGrpcService(new TenantScopedTopicService(baseTopics, scope, p.tenant), new TenantScopedSubscriptionService(baseSubs, scope, p.tenant)))
