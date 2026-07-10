package me.cference.hermesmq.cluster

import me.cference.hermesmq.domain.SubscriptionCommand
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicCommand
import me.cference.hermesmq.domain.TopicId
import me.cference.hermesmq.persistence.*
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.util.Timeout

import java.time.Instant
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

/** [[TopicService]] backed by cluster sharding: each call is routed to the
  * sharded entity that owns the topic id, from any node.
  */
final class ShardedTopicService(sharding: ClusterSharding)(using timeout: Timeout)
    extends TopicService:

  def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
    TopicSharding.entityRef(sharding, id).ask[CommandReply](replyTo => TopicEntityCommand.Submit(command, replyTo))

  def query(id: TopicId): Future[Option[TopicSnapshot]] =
    TopicSharding.entityRef(sharding, id).ask[Option[TopicSnapshot]](replyTo => TopicEntityCommand.Query(replyTo))

/** [[SubscriptionService]] backed by cluster sharding. Pull leases with the
  * configured default ack deadline; `now` is supplied at this boundary.
  */
final class ShardedSubscriptionService(sharding: ClusterSharding, ackDeadline: FiniteDuration)(using timeout: Timeout)
    extends SubscriptionService:

  def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
    SubscriptionSharding.entityRef(sharding, id).ask[CommandReply](replyTo => SubscriptionEntityCommand.Submit(command, replyTo))

  def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
    SubscriptionSharding
      .entityRef(sharding, id)
      .ask[Option[List[PulledMessage]]](replyTo => SubscriptionEntityCommand.Pull(max, ackDeadline, Instant.now(), replyTo))
