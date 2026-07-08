package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.SubscriptionCommand
import me.cference.hermesmq.persistence.SubscriptionService
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success}

/** Periodically expires overdue leases so unacknowledged messages are redelivered.
  *
  * A single cluster-wide instance ticks every `sweepInterval`, reads the durable
  * outstanding-lease read model, and issues `ExpireAckDeadline` for each lease
  * whose deadline has passed. Expiry is idempotent in the aggregate: a message
  * acked (or already expired) between scan and dispatch yields a no-op, so
  * duplicate or racing sweeps are safe.
  */
object RedeliverySweeper:

  /** Protocol: the timer emits `Tick`; each sweep reports `SweepDone`; `Stop` is
    * the daemon-process stop message that halts the sweeper (cancelling its timer).
    */
  sealed trait Command
  case object Stop              extends Command
  private case object Tick      extends Command
  private case object SweepDone extends Command

  /** Run a single sweep: dispatch `ExpireAckDeadline(now, maxAttempts)` to the
    * owning entity for every lease overdue at `now`. Returns when all dispatched
    * commands have been acknowledged.
    */
  def sweepOnce(
      repository: OutstandingLeaseRepository,
      service: SubscriptionService,
      now: Instant,
      maxAttempts: Int
  )(using ExecutionContext): Future[Unit] =
    repository.overdue(now).flatMap { leases =>
      Future
        .traverse(leases) { lease =>
          service.submit(lease.subscriptionId, SubscriptionCommand.ExpireAckDeadline(lease.ackId, now, maxAttempts))
        }
        .map(_ => ())
    }

  /** Sweeper behavior: ticks on a fixed delay and sweeps. The timer is bound to
    * the actor, so it is cancelled automatically when the actor stops (e.g. on
    * `CoordinatedShutdown` via the daemon-process stop message).
    */
  def apply(
      repository: OutstandingLeaseRepository,
      service: SubscriptionService,
      sweepInterval: FiniteDuration,
      maxAttempts: Int
  ): Behavior[Command] =
    Behaviors.setup[Command] { ctx =>
      given ExecutionContext = ctx.executionContext
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(Tick, sweepInterval)
        Behaviors.receiveMessage {
          case Tick =>
            ctx.pipeToSelf(sweepOnce(repository, service, Instant.now(), maxAttempts)) {
              case Success(_)  => SweepDone
              case Failure(ex) =>
                ctx.log.warn("Redelivery sweep failed; will retry on next tick", ex)
                SweepDone
            }
            Behaviors.same
          case SweepDone =>
            Behaviors.same
          case Stop =>
            Behaviors.stopped
        }
      }
    }
