package me.cference.hermesmq.config

import com.typesafe.config.Config

import scala.util.control.NonFatal

/** Typed PostgreSQL connection configuration, read from `hermesmq.db`. */
final case class DbConfig(
    host: String,
    port: Int,
    database: String,
    user: String,
    password: String
):
  /** JDBC URL derived from the connection settings. */
  def jdbcUrl: String = s"jdbc:postgresql://$host:$port/$database"

object DbConfig:

  private val PortRange = 1 to 65535

  /** Derive a validated [[DbConfig]] from raw config. Pure and total: missing,
    * mistyped, blank, or out-of-range values return `Left(ConfigError)`.
    */
  def from(config: Config): Either[ConfigError, DbConfig] =
    for
      host     <- read(config.getString("hermesmq.db.host"), "hermesmq.db.host")
      port     <- read(config.getInt("hermesmq.db.port"), "hermesmq.db.port")
      _        <- validatePort(port)
      database <- read(config.getString("hermesmq.db.database"), "hermesmq.db.database")
      _        <- nonBlank(database, "hermesmq.db.database")
      user     <- read(config.getString("hermesmq.db.user"), "hermesmq.db.user")
      password <- read(config.getString("hermesmq.db.password"), "hermesmq.db.password")
    yield DbConfig(host, port, database, user, password)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch case NonFatal(e) => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))

  private def validatePort(port: Int): Either[ConfigError, Unit] =
    if PortRange.contains(port) then Right(())
    else Left(ConfigError(s"hermesmq.db.port must be in ${PortRange.start}..${PortRange.end}, was $port"))

  private def nonBlank(value: String, path: String): Either[ConfigError, Unit] =
    if value.trim.nonEmpty then Right(())
    else Left(ConfigError(s"$path must not be blank"))
