package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.{AckId, SubscriptionId}

import java.sql.{Connection, DriverManager, Timestamp}
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, blocking}

/** One outstanding message that carries a TTL, and the instant it expires. */
final case class ExpiringMessage(subscriptionId: SubscriptionId, ackId: AckId, expireTime: Instant)

/** Durable, cluster-shared read model of outstanding messages that have an
  * `expireTime`. The TTL sweeper queries [[expired]] to find messages to purge
  * without scanning the entities; the projection keeps it in sync. Messages with
  * no TTL never appear here.
  */
trait ExpiringMessageRepository:
  def add(subscriptionId: SubscriptionId, ackId: AckId, expireTime: Instant): Future[Unit]
  def removed(subscriptionId: SubscriptionId, ackId: AckId): Future[Unit]
  def expired(now: Instant): Future[List[ExpiringMessage]]

/** In-memory implementation for tests (and single-node fallbacks). */
final class InMemoryExpiringMessageRepository(using ExecutionContext) extends ExpiringMessageRepository:
  private val entries = new AtomicReference(Map.empty[(SubscriptionId, AckId), Instant])

  def add(subscriptionId: SubscriptionId, ackId: AckId, expireTime: Instant): Future[Unit] =
    Future(entries.updateAndGet(_.updated((subscriptionId, ackId), expireTime))).map(_ => ())

  def removed(subscriptionId: SubscriptionId, ackId: AckId): Future[Unit] =
    Future(entries.updateAndGet(_.removed((subscriptionId, ackId)))).map(_ => ())

  def expired(now: Instant): Future[List[ExpiringMessage]] =
    Future {
      entries
        .get()
        .collect { case ((sub, ack), t) if !t.isAfter(now) => ExpiringMessage(sub, ack, t) }
        .toList
    }

object InMemoryExpiringMessageRepository:
  def apply()(using ExecutionContext): InMemoryExpiringMessageRepository = new InMemoryExpiringMessageRepository

/** PostgreSQL implementation backed by the `expiring_messages` table. */
final class JdbcExpiringMessageRepository(dbConfig: DbConfig)(using ExecutionContext) extends ExpiringMessageRepository:

  def add(subscriptionId: SubscriptionId, ackId: AckId, expireTime: Instant): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            """INSERT INTO expiring_messages (subscription_id, ack_id, expire_time) VALUES (?, ?, ?)
              |ON CONFLICT (subscription_id, ack_id) DO UPDATE SET expire_time = EXCLUDED.expire_time""".stripMargin
          )
          ps.setString(1, subscriptionId.value); ps.setString(2, ackId.value); ps.setTimestamp(3, Timestamp.from(expireTime))
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  def removed(subscriptionId: SubscriptionId, ackId: AckId): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement("DELETE FROM expiring_messages WHERE subscription_id = ? AND ack_id = ?")
          ps.setString(1, subscriptionId.value); ps.setString(2, ackId.value); ps.executeUpdate()
        }
      }
    }.map(_ => ())

  def expired(now: Instant): Future[List[ExpiringMessage]] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement("SELECT subscription_id, ack_id, expire_time FROM expiring_messages WHERE expire_time <= ?")
          ps.setTimestamp(1, Timestamp.from(now))
          val rs      = ps.executeQuery()
          val builder = List.newBuilder[ExpiringMessage]
          while rs.next() do
            for
              sub <- SubscriptionId.from(rs.getString(1)).toOption
              ack <- AckId.from(rs.getString(2)).toOption
            do builder += ExpiringMessage(sub, ack, rs.getTimestamp(3).toInstant)
          builder.result()
        }
      }
    }

  private def withConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    try f(conn)
    finally conn.close()
