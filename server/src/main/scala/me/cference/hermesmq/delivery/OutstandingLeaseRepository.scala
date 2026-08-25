package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.AckId
import me.cference.hermesmq.domain.SubscriptionId

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking

/** One currently-leased message and the instant its ack deadline expires. */
final case class OutstandingLease(subscriptionId: SubscriptionId, ackId: AckId, deadline: Instant)

/** Durable, cluster-shared read model of currently-LEASED messages and their ack
  * deadlines. The redelivery sweeper queries [[overdue]] to find leases to expire
  * without scanning the entities; the lease projection keeps it in sync.
  */
trait OutstandingLeaseRepository:
  def leased(subscriptionId: SubscriptionId, ackId: AckId, deadline: Instant): Future[Unit]
  def cleared(subscriptionId: SubscriptionId, ackId: AckId): Future[Unit]
  def overdue(now: Instant): Future[List[OutstandingLease]]

/** In-memory implementation for tests (and single-node fallbacks). */
final class InMemoryOutstandingLeaseRepository(using ExecutionContext) extends OutstandingLeaseRepository:
  private val leases = new AtomicReference(Map.empty[(SubscriptionId, AckId), Instant])

  def leased(subscriptionId: SubscriptionId, ackId: AckId, deadline: Instant): Future[Unit] =
    Future(leases.updateAndGet(_.updated((subscriptionId, ackId), deadline))).map(_ => ())

  def cleared(subscriptionId: SubscriptionId, ackId: AckId): Future[Unit] =
    Future(leases.updateAndGet(_.removed((subscriptionId, ackId)))).map(_ => ())

  def overdue(now: Instant): Future[List[OutstandingLease]] =
    Future {
      leases
        .get()
        .collect { case ((sub, ack), deadline) if !deadline.isAfter(now) => OutstandingLease(sub, ack, deadline) }
        .toList
    }

object InMemoryOutstandingLeaseRepository:
  def apply()(using ExecutionContext): InMemoryOutstandingLeaseRepository = new InMemoryOutstandingLeaseRepository

/** PostgreSQL implementation backed by the `outstanding_leases` table. */
final class JdbcOutstandingLeaseRepository(dbConfig: DbConfig)(using ExecutionContext) extends OutstandingLeaseRepository:

  def leased(subscriptionId: SubscriptionId, ackId: AckId, deadline: Instant): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            """INSERT INTO outstanding_leases (subscription_id, ack_id, deadline) VALUES (?, ?, ?)
              |ON CONFLICT (subscription_id, ack_id) DO UPDATE SET deadline = EXCLUDED.deadline""".stripMargin
          )
          ps.setString(1, subscriptionId.value)
          ps.setString(2, ackId.value)
          ps.setTimestamp(3, Timestamp.from(deadline))
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  def cleared(subscriptionId: SubscriptionId, ackId: AckId): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement("DELETE FROM outstanding_leases WHERE subscription_id = ? AND ack_id = ?")
          ps.setString(1, subscriptionId.value)
          ps.setString(2, ackId.value)
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  def overdue(now: Instant): Future[List[OutstandingLease]] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            "SELECT subscription_id, ack_id, deadline FROM outstanding_leases WHERE deadline <= ?"
          )
          ps.setTimestamp(1, Timestamp.from(now))
          val rs = ps.executeQuery()
          Iterator
            .continually(rs)
            .takeWhile(_.next())
            .flatMap { r =>
              for
                sub <- SubscriptionId.from(r.getString(1)).toOption
                ack <- AckId.from(r.getString(2)).toOption
              yield OutstandingLease(sub, ack, r.getTimestamp(3).toInstant)
            }
            .toList
        }
      }
    }

  private def withConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    try f(conn)
    finally conn.close()
