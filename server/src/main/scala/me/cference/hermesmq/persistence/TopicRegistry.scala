package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.TopicId
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}

import java.util.Base64

/** Single-node registry that owns one persistent [[TopicEntity]] per topic id.
  * It resolves (or spawns on demand) the child entity for a routed command,
  * guaranteeing exactly one writer per topic id. Children recover their own
  * state from the journal.
  *
  * On a multi-node deployment this seam would be replaced by cluster sharding;
  * clustering is a project non-goal for now.
  */
object TopicRegistry:

  /** Route an entity command to the topic that owns it. */
  final case class Route(topicId: TopicId, command: TopicEntityCommand)

  def apply(): Behavior[Route] =
    Behaviors.setup(ctx => running(ctx, Map.empty))

  private def running(
      ctx: ActorContext[Route],
      children: Map[TopicId, ActorRef[TopicEntityCommand]]
  ): Behavior[Route] =
    Behaviors.receiveMessage { case Route(topicId, command) =>
      children.get(topicId) match
        case Some(child) =>
          child ! command
          Behaviors.same
        case None =>
          val child = ctx.spawn(TopicEntity(topicId), childName(topicId))
          child ! command
          running(ctx, children.updated(topicId, child))
    }

  /** A valid, collision-free actor name for a topic id (base64url of the id). */
  private def childName(topicId: TopicId): String =
    "topic-" + Base64.getUrlEncoder.withoutPadding.encodeToString(topicId.value.getBytes("UTF-8"))
