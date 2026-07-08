package me.cference.hermesmq.grpc

import me.cference.hermesmq.auth.{Authenticator, Principal}
import me.cference.hermesmq.config.AuthConfig
import org.apache.pekko.grpc.scaladsl.Metadata

/** Authentication for the gRPC surface: resolves a [[Principal]] from call
  * metadata (`authorization: Bearer …` or `x-api-key`). When auth is disabled,
  * every call is the configured default tenant with admin scope.
  */
object GrpcAuth:

  private val BearerPrefix = "Bearer "

  def principal(metadata: Metadata, authenticator: Authenticator, config: AuthConfig): Option[Principal] =
    if !config.enabled then Some(Principal(config.defaultTenant, Set("admin")))
    else
      val token = metadata
        .getText("authorization")
        .filter(_.startsWith(BearerPrefix))
        .map(_.drop(BearerPrefix.length))
        .orElse(metadata.getText("x-api-key"))
      token.flatMap(authenticator.authenticate)
