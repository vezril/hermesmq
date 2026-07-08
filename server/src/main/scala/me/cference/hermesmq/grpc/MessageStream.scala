package me.cference.hermesmq.grpc

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.pattern.after
import org.apache.pekko.stream.scaladsl.Source

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration

/** Turns a leasing `pull` into a demand-driven, backpressured stream. Because
  * `Source.unfoldAsync` only invokes its step when downstream demands, leasing is
  * paced by consumer demand — a slow consumer stops new leases. An empty source
  * re-checks on `pollInterval` rather than busy-looping; a `None` pull (the
  * subscription is gone) completes the stream.
  */
object MessageStream:

  /** @param pull         lease up to `batch` elements (`None` = source gone → complete)
    * @param batch        max elements leased per step
    * @param pollInterval how long to wait before re-checking an empty source
    */
  def leased[A](
      pull: Int => Future[Option[List[A]]],
      batch: Int,
      pollInterval: FiniteDuration
  )(using system: ActorSystem, ec: ExecutionContext): Source[A, NotUsed] =
    Source
      .unfoldAsync(()) { _ =>
        pull(batch).flatMap {
          case None       => Future.successful(None)
          case Some(Nil)  => after(pollInterval, system.scheduler)(Future.successful(Some(((), List.empty[A]))))
          case Some(msgs) => Future.successful(Some(((), msgs)))
        }
      }
      .mapConcat(identity)
