package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Named-producer observability configuration: how long a producer counts as
  * active since it was last seen. `Zero` disables the producer registry and the
  * `hermesmq_topic_producers` metric.
  */
final case class ProducersConfig(activityWindow: FiniteDuration):
  def enabled: Boolean = activityWindow > Duration.Zero

object ProducersConfig:

  /** Matches the `application.conf` default; used where no config is supplied. */
  val Default: ProducersConfig = ProducersConfig(60.seconds)

  /** Derive a [[ProducersConfig]]. Pure and total: a missing/mistyped value or a
    * negative window is returned as `Left(ConfigError)` for fail-fast startup.
    */
  def from(config: Config): Either[ConfigError, ProducersConfig] =
    for
      window <- readDuration(config, "hermesmq.producers.activity-window")
      _      <- if window < Duration.Zero then Left(ConfigError(s"hermesmq.producers.activity-window must not be negative, was $window")) else Right(())
    yield ProducersConfig(window)

  private def readDuration(config: Config, path: String): Either[ConfigError, FiniteDuration] =
    try Right(FiniteDuration(config.getDuration(path).toNanos, NANOSECONDS))
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))
