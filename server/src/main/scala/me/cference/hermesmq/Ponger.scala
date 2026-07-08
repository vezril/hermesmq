package me.cference.hermesmq

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

/** Minimal typed actor used as a runtime smoke test: it replies [[Pong]] to
  * every [[Ping]]. Not part of the broker — it only proves the Pekko typed
  * stack is wired and behaving before real domain actors are introduced.
  */
object Ponger:

  /** Protocol accepted by a [[Ponger]] actor. */
  sealed trait Command

  /** Ask the actor to reply, sending [[Pong]] back to `replyTo`. */
  final case class Ping(replyTo: org.apache.pekko.actor.typed.ActorRef[Pong.type]) extends Command

  /** The reply sent in response to a [[Ping]]. */
  case object Pong

  def apply(): Behavior[Command] =
    Behaviors.receiveMessage { case Ping(replyTo) =>
      replyTo ! Pong
      Behaviors.same
    }
