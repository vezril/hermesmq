package me.cference.hermesmq.persistence

import me.cference.hermesmq.config.DbConfig

import java.sql.DriverManager

/** Cheap, cached reachability probe for the PostgreSQL backend, used to gate
  * readiness. Validates a short-lived JDBC connection and caches the result for
  * a short TTL so readiness probes don't hammer the database.
  */
final class PersistenceHealth(dbConfig: DbConfig, ttlMillis: Long = 5000L):

  @volatile private var cached: Boolean = false
  @volatile private var lastCheck: Long = 0L

  /** True if the database was reachable on the last probe (within the TTL). */
  def healthy(): Boolean =
    val now = System.currentTimeMillis()
    if now - lastCheck > ttlMillis then
      cached = probe()
      lastCheck = now
    cached

  private def probe(): Boolean =
    try
      val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
      try conn.isValid(2)
      finally conn.close()
    catch case _: Throwable => false
