package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.{TopicCommand, TopicId}

import scala.concurrent.Future

/** Application-facing seam for topic operations. Decouples the HTTP routes from
  * the sharding machinery so routes are unit-testable with a stub. The
  * production implementation ([[me.cference.hermesmq.cluster.ShardedTopicService]])
  * routes each call to the owning sharded entity.
  */
trait TopicService:
  def submit(id: TopicId, command: TopicCommand): Future[CommandReply]
  def query(id: TopicId): Future[Option[TopicSnapshot]]
