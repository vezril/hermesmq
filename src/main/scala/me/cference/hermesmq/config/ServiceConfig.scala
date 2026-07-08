package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.util.control.NonFatal

/** Error describing why a [[ServiceConfig]] could not be derived. */
final case class ConfigError(message: String)

/** Typed HTTP service configuration. */
final case class ServiceConfig(host: String, port: Int)

object ServiceConfig:

  private val PortRange = 1 to 65535

  /** Derive a [[ServiceConfig]] from raw config. Pure and total: any missing,
    * mistyped, or out-of-range value is returned as a `Left(ConfigError)`
    * rather than thrown, so callers can fail fast with a clear message.
    */
  def from(config: Config): Either[ConfigError, ServiceConfig] =
    for
      host <- read(config.getString("hermesmq.http.host"), "hermesmq.http.host")
      port <- read(config.getInt("hermesmq.http.port"), "hermesmq.http.port")
      valid <- validatePort(port)
    yield ServiceConfig(host, valid)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))

  private def validatePort(port: Int): Either[ConfigError, Int] =
    if PortRange.contains(port) then Right(port)
    else Left(ConfigError(s"hermesmq.http.port must be in ${PortRange.start}..${PortRange.end}, was $port"))
