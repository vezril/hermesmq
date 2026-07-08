package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Configuration for server-streaming consume.
  *
  * @param pollInterval how often an idle stream re-checks for new messages
  * @param batchSize    max messages leased per step
  */
final case class StreamConfig(pollInterval: FiniteDuration, batchSize: Int)

object StreamConfig:

  /** Matches the `application.conf` defaults; used where an explicit config is not supplied. */
  val Default: StreamConfig = StreamConfig(1.second, 100)

  /** Derive a [[StreamConfig]]. Pure and total: missing/mistyped or non-positive
    * values are returned as `Left(ConfigError)` for fail-fast startup.
    */
  def from(config: Config): Either[ConfigError, StreamConfig] =
    for
      poll  <- readDuration(config, "hermesmq.grpc.stream.poll-interval")
      batch <- read(config.getInt("hermesmq.grpc.stream.batch-size"), "hermesmq.grpc.stream.batch-size")
      _     <- requirePositive(poll, "hermesmq.grpc.stream.poll-interval")
      _     <- requirePositiveInt(batch, "hermesmq.grpc.stream.batch-size")
    yield StreamConfig(poll, batch)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))

  private def readDuration(config: Config, path: String): Either[ConfigError, FiniteDuration] =
    read(config.getDuration(path), path).map(d => FiniteDuration(d.toNanos, NANOSECONDS))

  private def requirePositive(d: FiniteDuration, path: String): Either[ConfigError, Unit] =
    if d > Duration.Zero then Right(()) else Left(ConfigError(s"$path must be positive, was $d"))

  private def requirePositiveInt(n: Int, path: String): Either[ConfigError, Unit] =
    if n > 0 then Right(()) else Left(ConfigError(s"$path must be positive, was $n"))
