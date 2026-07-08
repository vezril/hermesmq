package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.util.control.NonFatal

/** Typed snapshot/retention configuration for the event-sourced aggregates.
  *
  * @param snapshotEveryEvents persist a state snapshot every this many events
  * @param keepNSnapshots      number of recent snapshots to retain
  */
final case class RetentionConfig(snapshotEveryEvents: Int, keepNSnapshots: Int)

object RetentionConfig:

  /** Matches the `application.conf` defaults; used where an explicit config is
    * not supplied (e.g. entity unit tests).
    */
  val Default: RetentionConfig = RetentionConfig(snapshotEveryEvents = 100, keepNSnapshots = 2)

  /** Derive a [[RetentionConfig]] from raw config. Pure and total: missing,
    * mistyped, or non-positive values are returned as `Left(ConfigError)` so the
    * service fails fast rather than snapshotting on a nonsensical cadence.
    */
  def from(config: Config): Either[ConfigError, RetentionConfig] =
    for
      every <- read(config.getInt("hermesmq.retention.snapshot-every-events"), "hermesmq.retention.snapshot-every-events")
      keep  <- read(config.getInt("hermesmq.retention.keep-n-snapshots"), "hermesmq.retention.keep-n-snapshots")
      _     <- requirePositive(every, "hermesmq.retention.snapshot-every-events")
      _     <- requirePositive(keep, "hermesmq.retention.keep-n-snapshots")
    yield RetentionConfig(every, keep)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))

  private def requirePositive(n: Int, path: String): Either[ConfigError, Unit] =
    if n > 0 then Right(()) else Left(ConfigError(s"$path must be positive, was $n"))
