package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.SubscriptionId
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}

import java.util.Base64

/** Single-node registry that owns one persistent [[SubscriptionEntity]] per
  * subscription id (get-or-spawn), guaranteeing exactly one writer per id.
  */
object SubscriptionRegistry:

  final case class Route(subscriptionId: SubscriptionId, command: SubscriptionEntityCommand)

  def apply(): Behavior[Route] =
    Behaviors.setup(ctx => running(ctx, Map.empty))

  private def running(
      ctx: ActorContext[Route],
      children: Map[SubscriptionId, ActorRef[SubscriptionEntityCommand]]
  ): Behavior[Route] =
    Behaviors.receiveMessage { case Route(subscriptionId, command) =>
      children.get(subscriptionId) match
        case Some(child) =>
          child ! command
          Behaviors.same
        case None =>
          val child = ctx.spawn(SubscriptionEntity(subscriptionId), childName(subscriptionId))
          child ! command
          running(ctx, children.updated(subscriptionId, child))
    }

  private def childName(subscriptionId: SubscriptionId): String =
    "sub-" + Base64.getUrlEncoder.withoutPadding.encodeToString(subscriptionId.value.getBytes("UTF-8"))
