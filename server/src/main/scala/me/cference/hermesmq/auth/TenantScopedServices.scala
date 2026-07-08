package me.cference.hermesmq.auth

import me.cference.hermesmq.domain.{SubscriptionCommand, SubscriptionId, TopicCommand, TopicId}
import me.cference.hermesmq.persistence.*

import scala.concurrent.{ExecutionContext, Future}

/** A [[TopicService]] that transparently qualifies every id by tenant, so a
  * tenant's calls only ever touch its own entities/read models. Ids embedded in
  * commands are qualified too; a queried snapshot is unqualified back to the
  * caller's external id.
  */
final class TenantScopedTopicService(underlying: TopicService, scope: TenantScope, tenant: TenantId)(using ExecutionContext)
    extends TopicService:

  private def q(id: TopicId): TopicId  = TopicId.from(scope.qualify(tenant, id.value)).toOption.get
  private def uq(id: TopicId): TopicId = TopicId.from(scope.unqualify(tenant, id.value)).toOption.get

  def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
    val qualified = command match
      case TopicCommand.CreateTopic(tid, labels) => TopicCommand.CreateTopic(q(tid), labels)
      case other                                 => other
    underlying.submit(q(id), qualified)

  def query(id: TopicId): Future[Option[TopicSnapshot]] =
    underlying.query(q(id)).map(_.map(snap => snap.copy(topicId = uq(snap.topicId))))

/** A [[SubscriptionService]] that qualifies every id by tenant. `CreateSubscription`
  * qualifies both the subscription and its topic, so delivery fan-out stays within
  * the tenant. Pulled `ackId`s round-trip opaquely.
  */
final class TenantScopedSubscriptionService(underlying: SubscriptionService, scope: TenantScope, tenant: TenantId)
    extends SubscriptionService:

  private def qSub(id: SubscriptionId): SubscriptionId = SubscriptionId.from(scope.qualify(tenant, id.value)).toOption.get
  private def qTopic(id: TopicId): TopicId             = TopicId.from(scope.qualify(tenant, id.value)).toOption.get

  def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
    val qualified = command match
      case SubscriptionCommand.CreateSubscription(sid, tid) => SubscriptionCommand.CreateSubscription(qSub(sid), qTopic(tid))
      case other                                            => other
    underlying.submit(qSub(id), qualified)

  def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
    underlying.pull(qSub(id), max)
