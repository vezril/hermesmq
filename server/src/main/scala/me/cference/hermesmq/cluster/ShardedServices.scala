package me.cference.hermesmq.cluster

import me.cference.hermesmq.domain.{SubscriptionCommand, SubscriptionId, TopicCommand, TopicId}
import me.cference.hermesmq.persistence.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout

import scala.concurrent.Future

/** [[TopicService]] backed by cluster sharding: each call is routed to the
  * sharded entity that owns the topic id, from any node.
  */
final class ShardedTopicService(sharding: ClusterSharding)(using timeout: Timeout)
    extends TopicService:

  def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
    TopicSharding.entityRef(sharding, id).ask[CommandReply](replyTo => TopicEntityCommand.Submit(command, replyTo))

  def query(id: TopicId): Future[Option[TopicSnapshot]] =
    TopicSharding.entityRef(sharding, id).ask[Option[TopicSnapshot]](replyTo => TopicEntityCommand.Query(replyTo))

/** [[SubscriptionService]] backed by cluster sharding. */
final class ShardedSubscriptionService(sharding: ClusterSharding)(using timeout: Timeout)
    extends SubscriptionService:

  def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
    SubscriptionSharding.entityRef(sharding, id).ask[CommandReply](replyTo => SubscriptionEntityCommand.Submit(command, replyTo))

  def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
    SubscriptionSharding.entityRef(sharding, id).ask[Option[List[PulledMessage]]](replyTo => SubscriptionEntityCommand.Pull(max, replyTo))
