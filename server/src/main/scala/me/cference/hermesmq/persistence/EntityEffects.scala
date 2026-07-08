package me.cference.hermesmq.persistence

import me.cference.hermesmq.domain.Rejection
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.persistence.typed.scaladsl.Effect

/** Shared command-handler shape for persistent aggregates: run the pure
  * `decide`, persist the resulting events and reply `Accepted` only after they
  * are durably written, or reply `Rejected` (persisting nothing).
  */
object EntityEffects:

  def persistOrReject[S, E](
      decision: Either[Rejection, List[E]],
      replyTo: ActorRef[CommandReply]
  ): Effect[E, S] =
    decision match
      case Right(events)   => Effect.persist(events).thenReply(replyTo)(_ => CommandReply.Accepted)
      case Left(rejection) => Effect.reply(replyTo)(CommandReply.Rejected(rejection))
