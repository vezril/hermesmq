package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.{TopicCommand, TopicId}
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.util.Timeout

import scala.concurrent.Future

/** Application-facing seam for topic operations. Decouples the HTTP routes from
  * the actor/persistence machinery so routes are unit-testable with a stub.
  */
trait TopicService:
  def submit(id: TopicId, command: TopicCommand): Future[CommandReply]
  def query(id: TopicId): Future[Option[TopicSnapshot]]

/** [[TopicService]] backed by the [[TopicRegistry]], routing each call to the
  * owning entity via the ask pattern.
  */
final class RegistryTopicService(registry: ActorRef[TopicRegistry.Route])(using
    system: ActorSystem[?],
    timeout: Timeout
) extends TopicService:

  def submit(id: TopicId, command: TopicCommand): Future[CommandReply] =
    registry.ask[CommandReply](replyTo => TopicRegistry.Route(id, TopicEntityCommand.Submit(command, replyTo)))

  def query(id: TopicId): Future[Option[TopicSnapshot]] =
    registry.ask[Option[TopicSnapshot]](replyTo => TopicRegistry.Route(id, TopicEntityCommand.Query(replyTo)))
