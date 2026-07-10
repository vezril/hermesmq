package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.SubscriptionCommand
import me.cference.hermesmq.domain.SubscriptionId

import scala.concurrent.Future

/** Application-facing seam for subscription operations, so the HTTP routes are
  * unit-testable with a stub. The production implementation
  * ([[me.cference.hermesmq.cluster.ShardedSubscriptionService]]) routes each
  * call to the owning sharded entity.
  */
trait SubscriptionService:
  def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply]
  def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]]
