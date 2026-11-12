package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.SubscriptionId

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking

/** Durable record that a subscription's back-delivery has been performed.
  *
  * This marker -- not the idempotency of delivery -- is what makes back-delivery
  * at-most-once. `Subscription.decide` no-ops a `RecordDelivery` whose `ackId` is
  * already outstanding, but an acknowledged message is *removed* from
  * outstanding, so a consumer that drains its backfill leaves nothing for a
  * repeat to collide with. Without this table, an at-least-once projection
  * reprocessing `SubscriptionCreated` would re-deliver the whole backfill.
  */
trait BackfillMarkerRepository:

  /** True when this subscription's back-delivery has already completed. */
  def isComplete(subscriptionId: SubscriptionId): Future[Boolean]

  /** Record completion. Writing twice is harmless -- the first write stands, so a
    * concurrent or repeated completion does not disturb the recorded count.
    */
  def markComplete(subscriptionId: SubscriptionId, at: Instant, deliveredCount: Int): Future[Unit]

/** In-memory implementation for tests (and single-node fallbacks). */
final class InMemoryBackfillMarkerRepository(using ExecutionContext) extends BackfillMarkerRepository:
  private val marks = new AtomicReference(Map.empty[SubscriptionId, (Instant, Int)])

  def isComplete(subscriptionId: SubscriptionId): Future[Boolean] =
    Future(marks.get().contains(subscriptionId))

  def markComplete(subscriptionId: SubscriptionId, at: Instant, deliveredCount: Int): Future[Unit] =
    Future(marks.updateAndGet { m =>
      if m.contains(subscriptionId) then m else m.updated(subscriptionId, (at, deliveredCount))
    }).map(_ => ())

object InMemoryBackfillMarkerRepository:
  def apply()(using ExecutionContext): InMemoryBackfillMarkerRepository = new InMemoryBackfillMarkerRepository

/** PostgreSQL implementation backed by the `subscription_backfills` table. */
final class JdbcBackfillMarkerRepository(dbConfig: DbConfig)(using ExecutionContext) extends BackfillMarkerRepository:

  def isComplete(subscriptionId: SubscriptionId): Future[Boolean] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement("SELECT 1 FROM subscription_backfills WHERE subscription_id = ?")
          ps.setString(1, subscriptionId.value)
          ps.executeQuery().next()
        }
      }
    }

  def markComplete(subscriptionId: SubscriptionId, at: Instant, deliveredCount: Int): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            """INSERT INTO subscription_backfills (subscription_id, completed_at, delivered_count)
              |VALUES (?, ?, ?) ON CONFLICT DO NOTHING""".stripMargin
          )
          ps.setString(1, subscriptionId.value)
          ps.setTimestamp(2, Timestamp.from(at))
          ps.setInt(3, deliveredCount)
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  private def withConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    try f(conn)
    finally conn.close()
