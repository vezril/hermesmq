package me.cference.hermesmq.auth

import me.cference.hermesmq.domain.ValidationError

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

/** A tenant identifier: non-blank and free of the reserved namespace separator,
  * so tenant prefixes are unambiguous.
  */
opaque type TenantId = String
object TenantId:
  def from(raw: String): Either[ValidationError, TenantId] =
    if raw.trim.isEmpty then Left(ValidationError("tenant must not be blank"))
    else if raw.contains(TenantScope.Separator) then Left(ValidationError(s"tenant must not contain '${TenantScope.Separator}'"))
    else Right(raw)
  extension (t: TenantId) def value: String = t

/** The authenticated caller: its tenant and coarse scopes. */
final case class Principal(tenant: TenantId, scopes: Set[String]):
  def hasScope(scope: String): Boolean = scopes.contains(scope)

/** A configured API key: the salted-hash material and the principal it grants.
  * `salt` and `hash` are Base64; `hash = base64(SHA-256(saltBytes ++ tokenBytes))`.
  */
final case class AuthKey(tenant: TenantId, salt: String, hash: String, scopes: Set[String])

/** Validates presented tokens against configured keys using a constant-time
  * salted-hash comparison. Tokens are never stored or logged.
  */
final class Authenticator(keys: List[AuthKey]):

  def authenticate(token: String): Option[Principal] =
    Option(token)
      .filter(_.trim.nonEmpty)
      .flatMap(t => keys.collectFirst { case key if matches(key, t) => Principal(key.tenant, key.scopes) })

  private def matches(key: AuthKey, token: String): Boolean =
    try MessageDigest.isEqual(Authenticator.digest(key.salt, token), Base64.getDecoder.decode(key.hash))
    catch case _: IllegalArgumentException => false // malformed stored hash

object Authenticator:

  /** Base64 salted hash of a token, for building keys (config/docs/tests). */
  def computeHash(saltBase64: String, token: String): String =
    Base64.getEncoder.encodeToString(digest(saltBase64, token))

  private def digest(saltBase64: String, token: String): Array[Byte] =
    val md = MessageDigest.getInstance("SHA-256")
    md.update(Base64.getDecoder.decode(saltBase64))
    md.update(token.getBytes(UTF_8))
    md.digest()
