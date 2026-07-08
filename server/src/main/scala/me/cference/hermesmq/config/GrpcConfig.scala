package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.util.control.NonFatal

/** Typed gRPC (HTTP/2) endpoint configuration — its own bind host/port, separate
  * from the REST server.
  */
final case class GrpcConfig(host: String, port: Int)

object GrpcConfig:

  private val PortRange = 1 to 65535

  /** Derive a [[GrpcConfig]] from raw config. Pure and total: any missing,
    * mistyped, or out-of-range value is returned as `Left(ConfigError)` so the
    * service fails fast rather than binding a bad port.
    */
  def from(config: Config): Either[ConfigError, GrpcConfig] =
    for
      host  <- read(config.getString("hermesmq.grpc.host"), "hermesmq.grpc.host")
      port  <- read(config.getInt("hermesmq.grpc.port"), "hermesmq.grpc.port")
      valid <- validatePort(port)
    yield GrpcConfig(host, valid)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))

  private def validatePort(port: Int): Either[ConfigError, Int] =
    if PortRange.contains(port) then Right(port)
    else Left(ConfigError(s"hermesmq.grpc.port must be in ${PortRange.start}..${PortRange.end}, was $port"))
