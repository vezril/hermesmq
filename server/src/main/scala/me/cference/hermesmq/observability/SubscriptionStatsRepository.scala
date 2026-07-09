package me.cference.hermesmq.observability

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.{AckId, SubscriptionEvent, SubscriptionId, TopicId}

import java.sql.{Connection, DriverManager, Timestamp}
import java.time.{Duration, Instant}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future, blocking}

/** Observability snapshot for one subscription. `oldestUnackedAt` is the delivery
  * (publish) time of the oldest outstanding message, or `None` when the backlog
  * is empty; callers derive the age as `now - oldestUnackedAt`.
  */
final case class SubscriptionStats(
    subscriptionId: SubscriptionId,
    topicId: TopicId,
    backlog: Int,
    oldestUnackedAt: Option[Instant],
    redeliveredTotal: Long,
    deadLetteredTotal: Long
):
  def oldestUnackedAgeSeconds(now: Instant): Long =
    oldestUnackedAt.fold(0L)(at => math.max(0L, Duration.between(at, now).getSeconds))

/** The write operations a stats projection performs, storage-agnostic so the
  * fold logic can be tested in memory and applied transactionally over JDBC.
  */
trait SubscriptionStatsSink:
  def registerSubscription(subscriptionId: SubscriptionId, topicId: TopicId): Unit
  def delivered(subscriptionId: SubscriptionId, ackId: AckId, deliveredAt: Instant): Unit
  def removed(subscriptionId: SubscriptionId, ackId: AckId): Unit
  def redelivered(subscriptionId: SubscriptionId): Unit
  def deadLettered(subscriptionId: SubscriptionId): Unit

/** Pure fold of a subscription event onto a [[SubscriptionStatsSink]]. Backlog
  * grows on delivery and shrinks on ack/dead-letter; redelivery (ack-deadline
  * expiry) bumps the redelivery count without touching the backlog.
  */
object SubscriptionStatsFold:
  def apply(sink: SubscriptionStatsSink, subscriptionId: SubscriptionId, event: SubscriptionEvent): Unit =
    event match
      case SubscriptionEvent.SubscriptionCreated(_, topicId)     => sink.registerSubscription(subscriptionId, topicId)
      case SubscriptionEvent.MessageDelivered(ackId, message)    => sink.delivered(subscriptionId, ackId, message.publishTime)
      case SubscriptionEvent.MessageAcknowledged(ackId)          => sink.removed(subscriptionId, ackId)
      case SubscriptionEvent.AckDeadlineExpired(_, _)            => sink.redelivered(subscriptionId)
      case SubscriptionEvent.MessageDeadLettered(ackId, _, _)    =>
        sink.deadLettered(subscriptionId); sink.removed(subscriptionId, ackId)
      case SubscriptionEvent.MessageExpired(ackId)               => sink.removed(subscriptionId, ackId)
      case _ => () // MessageLeased / AckDeadlineModified don't change these stats

/** Read side of the subscription stats read model, serving the admin listing and
  * metrics endpoints off the hot delivery path.
  */
trait SubscriptionStatsRepository:
  def list(): Future[List[SubscriptionStats]]

/** In-memory subscription stats: both the projection sink (for tests and
  * single-node fallbacks) and the read side.
  */
final class InMemorySubscriptionStatsRepository(using ExecutionContext)
    extends SubscriptionStatsRepository
    with SubscriptionStatsSink:

  private val topics   = new AtomicReference(Map.empty[SubscriptionId, TopicId])
  private val backlog  = new AtomicReference(Map.empty[(SubscriptionId, AckId), Instant])
  private val counters = new AtomicReference(Map.empty[SubscriptionId, (Long, Long)]) // (redelivered, deadLettered)

  def registerSubscription(subscriptionId: SubscriptionId, topicId: TopicId): Unit =
    topics.updateAndGet(_.updated(subscriptionId, topicId))
    counters.updateAndGet(m => if m.contains(subscriptionId) then m else m.updated(subscriptionId, (0L, 0L)))

  def delivered(subscriptionId: SubscriptionId, ackId: AckId, deliveredAt: Instant): Unit =
    backlog.updateAndGet(_.updated((subscriptionId, ackId), deliveredAt))

  def removed(subscriptionId: SubscriptionId, ackId: AckId): Unit =
    backlog.updateAndGet(_.removed((subscriptionId, ackId)))

  def redelivered(subscriptionId: SubscriptionId): Unit =
    counters.updateAndGet { m =>
      val (r, d) = m.getOrElse(subscriptionId, (0L, 0L)); m.updated(subscriptionId, (r + 1, d))
    }

  def deadLettered(subscriptionId: SubscriptionId): Unit =
    counters.updateAndGet { m =>
      val (r, d) = m.getOrElse(subscriptionId, (0L, 0L)); m.updated(subscriptionId, (r, d + 1))
    }

  def list(): Future[List[SubscriptionStats]] =
    Future {
      val bl = backlog.get()
      topics.get().toList.map { (sub, topic) =>
        val times      = bl.collect { case ((s, _), t) if s == sub => t }
        val (red, dead) = counters.get().getOrElse(sub, (0L, 0L))
        SubscriptionStats(sub, topic, times.size, times.minOption, red, dead)
      }
    }

object InMemorySubscriptionStatsRepository:
  def apply()(using ExecutionContext): InMemorySubscriptionStatsRepository = new InMemorySubscriptionStatsRepository

/** A [[SubscriptionStatsSink]] that writes through a supplied JDBC connection, so
  * the projection can apply it in the same transaction as its offset advance.
  */
final class JdbcSubscriptionStatsSink(conn: Connection) extends SubscriptionStatsSink:

  def registerSubscription(subscriptionId: SubscriptionId, topicId: TopicId): Unit =
    val ps = conn.prepareStatement(
      """INSERT INTO subscription_stats (subscription_id, topic_id, redelivered_total, dead_lettered_total)
        |VALUES (?, ?, 0, 0) ON CONFLICT (subscription_id) DO UPDATE SET topic_id = EXCLUDED.topic_id""".stripMargin
    )
    ps.setString(1, subscriptionId.value); ps.setString(2, topicId.value); ps.executeUpdate()

  def delivered(subscriptionId: SubscriptionId, ackId: AckId, deliveredAt: Instant): Unit =
    val ps = conn.prepareStatement(
      """INSERT INTO subscription_backlog (subscription_id, ack_id, delivered_at) VALUES (?, ?, ?)
        |ON CONFLICT (subscription_id, ack_id) DO NOTHING""".stripMargin
    )
    ps.setString(1, subscriptionId.value); ps.setString(2, ackId.value); ps.setTimestamp(3, Timestamp.from(deliveredAt))
    ps.executeUpdate()

  def removed(subscriptionId: SubscriptionId, ackId: AckId): Unit =
    val ps = conn.prepareStatement("DELETE FROM subscription_backlog WHERE subscription_id = ? AND ack_id = ?")
    ps.setString(1, subscriptionId.value); ps.setString(2, ackId.value); ps.executeUpdate()

  def redelivered(subscriptionId: SubscriptionId): Unit = bump("redelivered_total", subscriptionId)
  def deadLettered(subscriptionId: SubscriptionId): Unit = bump("dead_lettered_total", subscriptionId)

  private def bump(column: String, subscriptionId: SubscriptionId): Unit =
    val ps = conn.prepareStatement(
      s"""INSERT INTO subscription_stats (subscription_id, topic_id, redelivered_total, dead_lettered_total)
         |VALUES (?, '', 0, 0) ON CONFLICT (subscription_id) DO UPDATE SET $column = subscription_stats.$column + 1""".stripMargin
    )
    ps.setString(1, subscriptionId.value); ps.executeUpdate()

/** Read side backed by PostgreSQL. */
final class JdbcSubscriptionStatsRepository(dbConfig: DbConfig)(using ExecutionContext) extends SubscriptionStatsRepository:

  def list(): Future[List[SubscriptionStats]] =
    Future {
      blocking {
        val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
        try
          val rs = conn
            .prepareStatement(
              """SELECT s.subscription_id, s.topic_id, s.redelivered_total, s.dead_lettered_total,
                |       count(b.ack_id) AS backlog, min(b.delivered_at) AS oldest
                |FROM subscription_stats s
                |LEFT JOIN subscription_backlog b ON b.subscription_id = s.subscription_id
                |GROUP BY s.subscription_id, s.topic_id, s.redelivered_total, s.dead_lettered_total""".stripMargin
            )
            .executeQuery()
          val builder = List.newBuilder[SubscriptionStats]
          while rs.next() do
            for
              sub   <- SubscriptionId.from(rs.getString("subscription_id")).toOption
              topic <- TopicId.from(rs.getString("topic_id")).toOption
            do
              val oldest = Option(rs.getTimestamp("oldest")).map(_.toInstant)
              builder += SubscriptionStats(
                sub,
                topic,
                rs.getInt("backlog"),
                oldest,
                rs.getLong("redelivered_total"),
                rs.getLong("dead_lettered_total")
              )
          builder.result()
        finally conn.close()
      }
    }
