package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.SubscriptionCommand
import me.cference.hermesmq.persistence.SubscriptionService
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/** Periodically purges outstanding messages whose TTL has passed.
  *
  * A single cluster-wide instance ticks every `sweepInterval`, reads the durable
  * expiring-message read model, and issues `ExpireMessage` for each message past
  * its `expireTime`. Expiry is idempotent in the aggregate: a message acked (or
  * already expired) between scan and dispatch yields a no-op, so duplicate or
  * racing sweeps are safe. Structural twin of [[RedeliverySweeper]].
  */
object TtlSweeper:

  sealed trait Command
  case object Stop              extends Command
  private case object Tick      extends Command
  private case object SweepDone extends Command

  /** Run a single sweep: dispatch `ExpireMessage(now)` to the owning entity for
    * every read-model entry whose `expireTime` is at or before `now`.
    */
  def sweepOnce(
      repository: ExpiringMessageRepository,
      service: SubscriptionService,
      now: Instant
  )(using ExecutionContext): Future[Unit] =
    repository.expired(now).flatMap { messages =>
      Future
        .traverse(messages) { m =>
          service.submit(m.subscriptionId, SubscriptionCommand.ExpireMessage(m.ackId, now))
        }
        .map(_ => ())
    }

  def apply(
      repository: ExpiringMessageRepository,
      service: SubscriptionService,
      sweepInterval: FiniteDuration
  ): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      given ExecutionContext = ctx.executionContext
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(Tick, sweepInterval)
        Behaviors.receiveMessage {
          case Tick =>
            ctx.pipeToSelf(sweepOnce(repository, service, Instant.now())) {
              case Success(_)  => SweepDone
              case Failure(ex) =>
                ctx.log.warn("TTL sweep failed; will retry on next tick", ex)
                SweepDone
            }
            Behaviors.same
          case SweepDone => Behaviors.same
          case Stop      => Behaviors.stopped
        }
      }
    }
