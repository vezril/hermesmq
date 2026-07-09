package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Message TTL configuration: the global default time-to-live applied to a publish
  * that does not specify its own `ttlSeconds`. `Zero` means TTL is off by default.
  */
final case class TtlConfig(defaultTtl: FiniteDuration):
  def enabled: Boolean = defaultTtl > Duration.Zero

  /** The effective expiry for a message published at `publishTime`: the per-publish
    * `ttlSeconds` when positive, else the global default when positive, else none.
    */
  def expireAt(publishTime: java.time.Instant, ttlSeconds: Int): Option[java.time.Instant] =
    val ttl = if ttlSeconds > 0 then ttlSeconds.seconds else defaultTtl
    if ttl > Duration.Zero then Some(publishTime.plusSeconds(ttl.toSeconds)) else None

object TtlConfig:

  /** Matches the `application.conf` default (off); used where no config is supplied. */
  val Default: TtlConfig = TtlConfig(Duration.Zero)

  /** Derive a [[TtlConfig]]. Pure and total: a missing/mistyped value or a negative
    * default is returned as `Left(ConfigError)` for fail-fast startup. `0` = off.
    */
  def from(config: Config): Either[ConfigError, TtlConfig] =
    for
      ttl <- readDuration(config, "hermesmq.ttl.default")
      _   <- if ttl < Duration.Zero then Left(ConfigError(s"hermesmq.ttl.default must not be negative, was $ttl")) else Right(())
    yield TtlConfig(ttl)

  private def readDuration(config: Config, path: String): Either[ConfigError, FiniteDuration] =
    try Right(FiniteDuration(config.getDuration(path).toNanos, NANOSECONDS))
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))
