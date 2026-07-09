package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Typed PostgreSQL connection configuration, read from `hermesmq.db`.
  *
  * @param migrateOnStart apply the bundled schema on boot; `false` = rely on
  *                       externally-provisioned tables
  * @param migrateMaxWait how long to wait for the database to become reachable
  *                       before the boot-time migration gives up
  */
final case class DbConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String,
    migrateOnStart: Boolean = true,
    migrateMaxWait: FiniteDuration = 30.seconds
):
  /** JDBC URL derived from the connection settings. */
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"

object DbConfig:

  private val PortRange = 1 to 65535

  /** Derive a validated [[DbConfig]] from raw config. Pure and total: missing,
    * mistyped, blank, out-of-range, or negative values return `Left(ConfigError)`.
    */
  def from(config: Config): Either[ConfigError, DbConfig] =
    for
      host           <- read(config.getString("hermesmq.db.host"), "hermesmq.db.host")
      port           <- read(config.getInt("hermesmq.db.port"), "hermesmq.db.port")
      _              <- validatePort(port)
      database       <- read(config.getString("hermesmq.db.database"), "hermesmq.db.database")
      _              <- nonBlank(database, "hermesmq.db.database")
      user           <- read(config.getString("hermesmq.db.user"), "hermesmq.db.user")
      password       <- read(config.getString("hermesmq.db.password"), "hermesmq.db.password")
      migrateOnStart <- read(config.getBoolean("hermesmq.db.migrate-on-start"), "hermesmq.db.migrate-on-start")
      migrateMaxWait <- readDuration(config, "hermesmq.db.migrate-max-wait")
      _              <- nonNegative(migrateMaxWait, "hermesmq.db.migrate-max-wait")
    yield DbConfig(host, port, database, user, password, migrateOnStart, migrateMaxWait)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))

  private def readDuration(config: Config, path: String): Either[ConfigError, FiniteDuration] =
    read(FiniteDuration(config.getDuration(path).toNanos, NANOSECONDS), path)

  private def validatePort(port: Int): Either[ConfigError, Unit] =
    if PortRange.contains(port) then Right(())
    else Left(ConfigError(s"hermesmq.db.port must be in ${PortRange.start}..${PortRange.end}, was $port"))

  private def nonBlank(value: String, path: String): Either[ConfigError, Unit] =
    if value.trim.nonEmpty then Right(())
    else Left(ConfigError(s"$path must not be blank"))

  private def nonNegative(d: FiniteDuration, path: String): Either[ConfigError, Unit] =
    if d >= Duration.Zero then Right(())
    else Left(ConfigError(s"$path must not be negative, was $d"))
