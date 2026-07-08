package me.cference.hermesmq.auth

import me.cference.hermesmq.domain.ValidationError

/** Transparent per-tenant namespacing of external resource ids. The internal id
  * is the external id qualified by tenant, so tenants sharing an external id get
  * isolated resources. The configured **default tenant** uses an empty prefix —
  * its ids stay unqualified, preserving existing single-tenant journals.
  *
  * @param defaultTenant the tenant whose ids are left unqualified (compatibility)
  */
final class TenantScope(defaultTenant: TenantId):

  private def prefix(tenant: TenantId): String = s"${tenant.value}${TenantScope.Separator}"

  /** Qualify an external id to its tenant-scoped internal id. */
  def qualify(tenant: TenantId, externalId: String): String =
    if tenant == defaultTenant then externalId else prefix(tenant) + externalId

  /** True when an internal id belongs to `tenant` (owns its namespace). The
    * default tenant owns exactly the unqualified ids.
    */
  def belongsTo(tenant: TenantId, internalId: String): Boolean =
    if tenant == defaultTenant then !internalId.contains(TenantScope.Separator)
    else internalId.startsWith(prefix(tenant))

  /** Strip a tenant's prefix from an internal id it owns, yielding the external
    * id; returns the id unchanged if it does not belong to the tenant.
    */
  def unqualify(tenant: TenantId, internalId: String): String =
    if tenant == defaultTenant then internalId
    else if internalId.startsWith(prefix(tenant)) then internalId.drop(prefix(tenant).length)
    else internalId

object TenantScope:

  /** Reserved separator between tenant and external id. External ids and tenant
    * ids may not contain it, so a tenant cannot forge another's namespace.
    */
  val Separator: Char = '~'

  /** A sensible default tenant name used when the config does not specify one. */
  val DefaultTenant: TenantId = TenantId.from("default").toOption.getOrElse(throw new IllegalStateException("default tenant"))

  /** Validate an external id supplied by a caller: it must not contain the
    * reserved separator (which would let it escape its tenant namespace).
    */
  def validateExternalId(externalId: String): Either[ValidationError, String] =
    if externalId.contains(Separator) then Left(ValidationError(s"id must not contain '$Separator'"))
    else Right(externalId)
