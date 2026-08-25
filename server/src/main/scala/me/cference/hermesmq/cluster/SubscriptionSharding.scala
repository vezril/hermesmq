package me.cference.hermesmq.cluster

import me.cference.hermesmq.config.RetentionConfig
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.persistence.SubscriptionEntity
import me.cference.hermesmq.persistence.SubscriptionEntityCommand
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.scaladsl.Entity
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey

/** Cluster Sharding for Subscription entities — one writer per subscription id
  * across the cluster, keeping the existing `Subscription|<id>` persistence id.
  */
object SubscriptionSharding:

  val TypeKey: EntityTypeKey[SubscriptionEntityCommand] = EntityTypeKey[SubscriptionEntityCommand]("Subscription")

  def init(
      sharding: ClusterSharding,
      retention: RetentionConfig = RetentionConfig.Default
  ): ActorRef[ShardingEnvelope[SubscriptionEntityCommand]] =
    sharding.init(Entity(TypeKey)(ctx => SubscriptionEntity(SubscriptionId.from(ctx.entityId).toOption.get, retention)))

  def entityRef(sharding: ClusterSharding, subscriptionId: SubscriptionId): EntityRef[SubscriptionEntityCommand] =
    sharding.entityRefFor(TypeKey, subscriptionId.value)
