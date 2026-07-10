package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Named-consumer observability configuration: how long a consumer counts as
  * active since it was last seen. `Zero` disables the consumer registry and the
  * `hermesmq_subscription_consumers` metric.
  */
final case class ConsumersConfig(activityWindow: FiniteDuration):
  def enabled: Boolean = activityWindow > Duration.Zero

object ConsumersConfig:

  /** Matches the `application.conf` default; used where no config is supplied. */
  val Default: ConsumersConfig = ConsumersConfig(60.seconds)

  /** Derive a [[ConsumersConfig]]. Pure and total: a missing/mistyped value or a
    * negative window is returned as `Left(ConfigError)` for fail-fast startup.
    */
  def from(config: Config): Either[ConfigError, ConsumersConfig] =
    for
      window <- readDuration(config, "hermesmq.consumers.activity-window")
      _      <- if window < Duration.Zero then Left(ConfigError(s"hermesmq.consumers.activity-window must not be negative, was $window")) else Right(())
    yield ConsumersConfig(window)

  private def readDuration(config: Config, path: String): Either[ConfigError, FiniteDuration] =
    try Right(FiniteDuration(config.getDuration(path).toNanos, NANOSECONDS))
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))
