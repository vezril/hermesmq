package me.cference.hermesmq.cluster

import me.cference.hermesmq.config.DedupConfig
import me.cference.hermesmq.config.RetentionConfig
import me.cference.hermesmq.domain.TopicId
import me.cference.hermesmq.persistence.TopicEntity
import me.cference.hermesmq.persistence.TopicEntityCommand
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.cluster.sharding.typed.ShardingEnvelope
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.cluster.sharding.typed.scaladsl.Entity
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityRef
import org.apache.pekko.cluster.sharding.typed.scaladsl.EntityTypeKey

/** Cluster Sharding for Topic entities. Each topic id is a sharded entity, so
  * exactly one writer exists per id across the cluster. The entity keeps its
  * existing `Topic|<id>` persistence id, so journals stay compatible.
  */
object TopicSharding:

  val TypeKey: EntityTypeKey[TopicEntityCommand] = EntityTypeKey[TopicEntityCommand]("Topic")

  def init(
      sharding: ClusterSharding,
      retention: RetentionConfig = RetentionConfig.Default,
      dedup: DedupConfig = DedupConfig.Default
  ): ActorRef[ShardingEnvelope[TopicEntityCommand]] =
    sharding.init(Entity(TypeKey)(ctx => TopicEntity(TopicId.from(ctx.entityId).toOption.get, retention, dedup)))

  def entityRef(sharding: ClusterSharding, topicId: TopicId): EntityRef[TopicEntityCommand] =
    sharding.entityRefFor(TypeKey, topicId.value)
