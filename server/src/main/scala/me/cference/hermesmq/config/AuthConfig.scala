package me.cference.hermesmq.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import me.cference.hermesmq.auth.AuthKey
import me.cference.hermesmq.auth.TenantId

import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

/** Typed authentication configuration.
  *
  * @param enabled       when false, requests are served as [[defaultTenant]] with no credential
  * @param defaultTenant tenant used in disabled mode (unqualified ids, journal-compatible)
  * @param keys          configured API keys (salted-hash material + principal)
  */
final case class AuthConfig(enabled: Boolean, defaultTenant: TenantId, keys: List[AuthKey])

object AuthConfig:

  /** Derive an [[AuthConfig]]. Pure and total: missing/mistyped values, a blank
    * tenant, a malformed key, or enabling auth with no keys are returned as
    * `Left(ConfigError)` so the service fails fast rather than starting insecure.
    */
  def from(config: Config): Either[ConfigError, AuthConfig] =
    for
      enabled       <- read(config.getBoolean("hermesmq.auth.enabled"), "hermesmq.auth.enabled")
      defaultRaw    <- read(config.getString("hermesmq.auth.default-tenant"), "hermesmq.auth.default-tenant")
      defaultTenant <- TenantId.from(defaultRaw).left.map(e => ConfigError(s"hermesmq.auth.default-tenant: ${e.message}"))
      keys          <- parseKeys(config)
      _             <- if enabled && keys.isEmpty then Left(ConfigError("hermesmq.auth.enabled is true but no keys are configured")) else Right(())
    yield AuthConfig(enabled, defaultTenant, keys)

  private def parseKeys(config: Config): Either[ConfigError, List[AuthKey]] =
    read(config.getConfigList("hermesmq.auth.keys").asScala.toList, "hermesmq.auth.keys").flatMap { entries =>
      entries.zipWithIndex.foldRight(Right(Nil): Either[ConfigError, List[AuthKey]]) { case ((entry, i), acc) =>
        for
          rest   <- acc
          key    <- parseKey(entry, i)
        yield key :: rest
      }
    }

  private def parseKey(entry: Config, index: Int): Either[ConfigError, AuthKey] =
    for
      tenantRaw <- read(entry.getString("tenant"), s"hermesmq.auth.keys[$index].tenant")
      tenant    <- TenantId.from(tenantRaw).left.map(e => ConfigError(s"hermesmq.auth.keys[$index].tenant: ${e.message}"))
      salt      <- read(entry.getString("salt"), s"hermesmq.auth.keys[$index].salt")
      hash      <- read(entry.getString("hash"), s"hermesmq.auth.keys[$index].hash")
      scopes    <- Right(if entry.hasPath("scopes") then entry.getStringList("scopes").asScala.toSet else Set.empty[String])
    yield AuthKey(tenant, salt, hash, scopes)

  private def read[A](value: => A, path: String): Either[ConfigError, A] =
    try Right(value)
    catch
      case e: ConfigException => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))
      case NonFatal(e)        => Left(ConfigError(s"Invalid or missing config at '$path': ${e.getMessage}"))
