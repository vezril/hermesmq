package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.{SubscriptionCommand, SubscriptionId}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout

import scala.concurrent.Future

/** Application-facing seam for subscription operations, so the HTTP routes are
  * unit-testable with a stub.
  */
trait SubscriptionService:
  def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply]
  def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]]

/** [[SubscriptionService]] backed by the [[SubscriptionRegistry]] via ask. */
final class RegistrySubscriptionService(registry: ActorRef[SubscriptionRegistry.Route])(using
    system: ActorSystem[?],
    timeout: Timeout
) extends SubscriptionService:

  def submit(id: SubscriptionId, command: SubscriptionCommand): Future[CommandReply] =
    registry.ask[CommandReply](replyTo => SubscriptionRegistry.Route(id, SubscriptionEntityCommand.Submit(command, replyTo)))

  def pull(id: SubscriptionId, max: Int): Future[Option[List[PulledMessage]]] =
    registry.ask[Option[List[PulledMessage]]](replyTo => SubscriptionRegistry.Route(id, SubscriptionEntityCommand.Pull(max, replyTo)))
