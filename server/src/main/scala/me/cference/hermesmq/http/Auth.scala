package me.cference.hermesmq.http

import me.cference.hermesmq.auth.{Authenticator, Principal, TenantScope}
import me.cference.hermesmq.config.AuthConfig
import org.apache.pekko.http.scaladsl.model.headers.HttpChallenge
import org.apache.pekko.http.scaladsl.server.AuthenticationFailedRejection.{CredentialsMissing, CredentialsRejected}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.{AuthenticationFailedRejection, Directive1}

/** Authentication boundary for the `/v1` routes. Extracts a bearer token /
  * `X-API-Key`, authenticates it, and provides the resulting [[Principal]]. When
  * auth is disabled, every request is the configured default tenant (with admin
  * scope) — preserving today's behavior.
  */
object Auth:

  private val BearerPrefix = "Bearer "
  private val Challenge    = HttpChallenge("Bearer", "hermesmq")

  /** Scope granted to the implicit principal when auth is disabled. */
  private val DisabledScopes = Set("admin")

  def authenticate(authenticator: Authenticator, config: AuthConfig): Directive1[Principal] =
    if !config.enabled then provide(Principal(config.defaultTenant, DisabledScopes))
    else
      (optionalHeaderValueByName("Authorization") & optionalHeaderValueByName("X-API-Key")).tflatMap { case (authz, apiKey) =>
        val token = authz.filter(_.startsWith(BearerPrefix)).map(_.drop(BearerPrefix.length)).orElse(apiKey)
        token.flatMap(authenticator.authenticate) match
          case Some(principal) => provide(principal)
          case None =>
            val cause = if token.isDefined then CredentialsRejected else CredentialsMissing
            reject(AuthenticationFailedRejection(cause, Challenge))
      }

  /** True when the principal may perform topic administration. */
  def isAdmin(principal: Principal): Boolean = principal.hasScope("admin")

  /** True when a raw external id is safe (does not escape its tenant namespace). */
  def validId(rawId: String): Boolean = !rawId.contains(TenantScope.Separator)
