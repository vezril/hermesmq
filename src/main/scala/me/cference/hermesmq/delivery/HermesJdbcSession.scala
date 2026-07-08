package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import org.apache.pekko.japi.function.Function
import org.apache.pekko.projection.jdbc.JdbcSession

import java.sql.{Connection, DriverManager}

/** A [[JdbcSession]] for the Pekko Projection JDBC offset store, wrapping a
  * plain JDBC connection to the configured PostgreSQL database.
  */
final class HermesJdbcSession(dbConfig: DbConfig) extends JdbcSession:
  private lazy val connection: Connection =
    val c = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    c.setAutoCommit(false)
    c

  override def withConnection[R](func: Function[Connection, R]): R = func(connection)
  override def commit(): Unit                                       = connection.commit()
  override def rollback(): Unit                                     = connection.rollback()
  override def close(): Unit                                        = connection.close()
