package me.cference.hermesmq.observability

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.TopicEvent
import me.cference.hermesmq.domain.TopicId

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking

/** Observability snapshot for one topic: how many messages it has published, and
  * whether it has been (soft) deleted.
  */
final case class TopicStats(topicId: TopicId, publishedTotal: Long, deleted: Boolean)

/** The write operations the topic stats projection performs, storage-agnostic. */
trait TopicStatsSink:
  def registerTopic(topicId: TopicId): Unit
  def published(topicId: TopicId): Unit
  def deleted(topicId: TopicId): Unit

/** Pure fold of a topic event onto a [[TopicStatsSink]]. A topic is registered on
  * creation (so it lists with a zero count), incremented on publish, and marked
  * deleted on deletion.
  */
object TopicStatsFold:
  def apply(sink: TopicStatsSink, topicId: TopicId, event: TopicEvent): Unit =
    event match
      case _: TopicEvent.TopicCreated     => sink.registerTopic(topicId)
      case _: TopicEvent.MessagePublished => sink.published(topicId)
      case _: TopicEvent.TopicDeleted     => sink.deleted(topicId)
      case _                              => () // TopicLabelsUpdated doesn't change these stats

/** Read side of the topic stats read model. */
trait TopicStatsRepository:
  def list(): Future[List[TopicStats]]

/** In-memory topic stats: both the projection sink and the read side. */
final class InMemoryTopicStatsRepository(using ExecutionContext) extends TopicStatsRepository with TopicStatsSink:

  private val state = new AtomicReference(Map.empty[TopicId, (Long, Boolean)]) // (publishedTotal, deleted)

  def registerTopic(topicId: TopicId): Unit =
    val _ = state.updateAndGet(m => if m.contains(topicId) then m else m.updated(topicId, (0L, false)))

  def published(topicId: TopicId): Unit =
    val _ = state.updateAndGet { m =>
      val (n, del) = m.getOrElse(topicId, (0L, false)); m.updated(topicId, (n + 1, del))
    }

  def deleted(topicId: TopicId): Unit =
    val _ = state.updateAndGet { m =>
      val (n, _) = m.getOrElse(topicId, (0L, false)); m.updated(topicId, (n, true))
    }

  def list(): Future[List[TopicStats]] =
    Future(state.get().toList.map { case (id, (n, del)) => TopicStats(id, n, del) })

object InMemoryTopicStatsRepository:
  def apply()(using ExecutionContext): InMemoryTopicStatsRepository = new InMemoryTopicStatsRepository

/** A [[TopicStatsSink]] writing through a supplied JDBC connection (same
  * transaction as the projection's offset advance).
  */
final class JdbcTopicStatsSink(conn: Connection) extends TopicStatsSink:

  def registerTopic(topicId: TopicId): Unit =
    val ps = conn.prepareStatement(
      """INSERT INTO topic_stats (topic_id, published_total, deleted) VALUES (?, 0, false)
        |ON CONFLICT (topic_id) DO NOTHING""".stripMargin
    )
    ps.setString(1, topicId.value); val _ = ps.executeUpdate()

  def published(topicId: TopicId): Unit =
    val ps = conn.prepareStatement(
      """INSERT INTO topic_stats (topic_id, published_total, deleted) VALUES (?, 1, false)
        |ON CONFLICT (topic_id) DO UPDATE SET published_total = topic_stats.published_total + 1""".stripMargin
    )
    ps.setString(1, topicId.value); val _ = ps.executeUpdate()

  def deleted(topicId: TopicId): Unit =
    val ps = conn.prepareStatement(
      """INSERT INTO topic_stats (topic_id, published_total, deleted) VALUES (?, 0, true)
        |ON CONFLICT (topic_id) DO UPDATE SET deleted = true""".stripMargin
    )
    ps.setString(1, topicId.value); val _ = ps.executeUpdate()

/** Read side backed by PostgreSQL. */
final class JdbcTopicStatsRepository(dbConfig: DbConfig)(using ExecutionContext) extends TopicStatsRepository:

  def list(): Future[List[TopicStats]] =
    Future {
      blocking {
        val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
        try
          val rs = conn.prepareStatement("SELECT topic_id, published_total, deleted FROM topic_stats").executeQuery()
          Iterator
            .continually(rs)
            .takeWhile(_.next())
            .flatMap { r =>
              TopicId
                .from(r.getString("topic_id"))
                .toOption
                .map(id => TopicStats(id, r.getLong("published_total"), r.getBoolean("deleted")))
            }
            .toList
        finally conn.close()
      }
    }
