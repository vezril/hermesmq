package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Producer idempotency (publish dedup) configuration: the window within which a
  * repeated idempotency key for a topic is collapsed to the original publish.
  * `Zero` disables deduplication (keys are ignored); a positive window enables it.
  */
final case class DedupConfig(window: FiniteDuration):
  def enabled: Boolean = window > Duration.Zero

object DedupConfig:

  /** Matches the `application.conf` default (off); used where no config is supplied. */
  val Default: DedupConfig = DedupConfig(Duration.Zero)

  /** Derive a [[DedupConfig]]. Pure and total: a missing/mistyped value or a negative
    * window is returned as `Left(ConfigError)` for fail-fast startup. `0` = off.
    */
  def from(config: Config): Either[ConfigError, DedupConfig] =
    for
      window <- readDuration(config, "hermesmq.dedup.window")
      _      <- if window < Duration.Zero then Left(ConfigError(s"hermesmq.dedup.window must not be negative, was $window")) else Right(())
    yield DedupConfig(window)

  private def readDuration(config: Config, path: String): Either[ConfigError, FiniteDuration] =
    try Right(FiniteDuration(config.getDuration(path).toNanos, NANOSECONDS))
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))
