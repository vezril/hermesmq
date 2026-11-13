package me.cference.hermesmq.config

import com.typesafe.config.Config
import me.cference.hermesmq.domain.TopicId

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Typed redelivery / ack-deadline configuration.
  *
  * @param ackDeadline         how long a pulled message stays leased before it is eligible for redelivery
  * @param maxDeliveryAttempts attempts before a message is dead-lettered; `0` = unlimited
  * @param sweepInterval       how often the sweeper scans for overdue leases
  * @param deadLetterTopic     topic exhausted messages are republished to; `None` = drop
  * @param deadLetterOnExpiry  route TTL-expired messages to the dead-letter topic instead of dropping them
  */
final case class RedeliveryConfig(
    ackDeadline: FiniteDuration,
    maxDeliveryAttempts: Int,
    sweepInterval: FiniteDuration,
    deadLetterTopic: Option[TopicId],
    // Defaults to false: TTL expiry has always dropped, and turning this on
    // changes the volume landing on the dead-letter topic, which an operator
    // should opt into rather than discover.
    deadLetterOnExpiry: Boolean = false
)

object RedeliveryConfig:

  /** Derive a [[RedeliveryConfig]] from raw config. Pure and total: missing,
    * mistyped, or non-positive values are returned as `Left(ConfigError)` so the
    * service fails fast with a clear message rather than starting misconfigured.
    */
  def from(config: Config): Either[ConfigError, RedeliveryConfig] =
    for
      ackDeadline   <- readDuration(config, "hermesmq.redelivery.ack-deadline")
      maxAttempts   <- read(config.getInt("hermesmq.redelivery.max-delivery-attempts"), "hermesmq.redelivery.max-delivery-attempts")
      sweepInterval <- readDuration(config, "hermesmq.redelivery.sweep-interval")
      topic         <- readDeadLetterTopic(config)
      dlqOnExpiry   <- read(config.getBoolean("hermesmq.redelivery.dead-letter-on-expiry"), "hermesmq.redelivery.dead-letter-on-expiry")
      _             <- requirePositive(ackDeadline, "hermesmq.redelivery.ack-deadline")
      _             <- requirePositive(sweepInterval, "hermesmq.redelivery.sweep-interval")
      _             <- requireNonNegative(maxAttempts, "hermesmq.redelivery.max-delivery-attempts")
    yield RedeliveryConfig(ackDeadline, maxAttempts, sweepInterval, topic, dlqOnExpiry)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))

  private def readDuration(config: Config, path: String): Either[ConfigError, FiniteDuration] =
    read(config.getDuration(path), path).map(d => FiniteDuration(d.toNanos, NANOSECONDS))

  private def readDeadLetterTopic(config: Config): Either[ConfigError, Option[TopicId]] =
    read(config.getString("hermesmq.redelivery.dead-letter-topic"), "hermesmq.redelivery.dead-letter-topic").flatMap { raw =>
      if raw.isBlank then Right(None)
      else TopicId.from(raw.trim).map(Some(_)).left.map(e => ConfigError(s"hermesmq.redelivery.dead-letter-topic: ${e.message}"))
    }

  private def requirePositive(d: FiniteDuration, path: String): Either[ConfigError, Unit] =
    if d > Duration.Zero then Right(()) else Left(ConfigError(s"$path must be positive, was $d"))

  private def requireNonNegative(n: Int, path: String): Either[ConfigError, Unit] =
    if n >= 0 then Right(()) else Left(ConfigError(s"$path must be >= 0, was $n"))
