package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.MessageId
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId
import spray.json.*

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking

/** One dead-lettered message as the operator sees it.
  *
  * `originTopic` is optional because a subscription whose index row had not been
  * projected yet still records its dead-letter -- losing the origin topic is
  * recoverable (the redrive path resolves it from the subscription), losing the
  * message is not. `redrivenAt` is stamped rather than deleting the row, so the
  * audit trail survives and a repeat redrive can be reported as such.
  */
final case class DeadLetterEntry(
    subscriptionId: SubscriptionId,
    originalMessageId: MessageId,
    originTopic: Option[TopicId],
    payload: Vector[Byte],
    attributes: Map[String, String],
    deliveryAttempts: Int,
    deadLetteredAt: Instant,
    redrivenAt: Option[Instant]
)

/** The durable, cluster-shared dead-letter read model. Written by the
  * dead-letter index projection; read by the operator browse and redrive paths.
  */
trait DeadLetterRepository:

  /** Record a dead-letter. First write wins: an at-least-once projection can
    * reprocess the same envelope, which must not mutate or duplicate the row.
    */
  def record(entry: DeadLetterEntry): Future[Unit]

  /** A subscription's dead-letters, most recently dead-lettered first. */
  def listFor(subscriptionId: SubscriptionId): Future[List[DeadLetterEntry]]

  def find(subscriptionId: SubscriptionId, messageId: MessageId): Future[Option[DeadLetterEntry]]

  /** Stamp an entry as redriven. A no-op for an entry that is not present. */
  def markRedriven(subscriptionId: SubscriptionId, messageId: MessageId, at: Instant): Future[Unit]

/** In-memory implementation for tests (and single-node fallbacks). */
final class InMemoryDeadLetterRepository(using ExecutionContext) extends DeadLetterRepository:
  private val entries = new AtomicReference(Map.empty[(SubscriptionId, MessageId), DeadLetterEntry])

  private def key(entry: DeadLetterEntry) = (entry.subscriptionId, entry.originalMessageId)

  def record(entry: DeadLetterEntry): Future[Unit] =
    Future(entries.updateAndGet { m =>
      if m.contains(key(entry)) then m else m.updated(key(entry), entry)
    }).map(_ => ())

  def listFor(subscriptionId: SubscriptionId): Future[List[DeadLetterEntry]] =
    Future(
      entries
        .get()
        .values
        .filter(_.subscriptionId == subscriptionId)
        .toList
        .sortBy(_.deadLetteredAt)(using Ordering[Instant].reverse)
    )

  def find(subscriptionId: SubscriptionId, messageId: MessageId): Future[Option[DeadLetterEntry]] =
    Future(entries.get().get((subscriptionId, messageId)))

  def markRedriven(subscriptionId: SubscriptionId, messageId: MessageId, at: Instant): Future[Unit] =
    Future(entries.updateAndGet { m =>
      m.get((subscriptionId, messageId)) match
        case Some(e) => m.updated((subscriptionId, messageId), e.copy(redrivenAt = Some(at)))
        case None    => m
    }).map(_ => ())

object InMemoryDeadLetterRepository:
  def apply()(using ExecutionContext): InMemoryDeadLetterRepository = new InMemoryDeadLetterRepository

/** PostgreSQL implementation backed by the `subscription_dead_letters` table. */
final class JdbcDeadLetterRepository(dbConfig: DbConfig)(using ExecutionContext) extends DeadLetterRepository:

  import DefaultJsonProtocol.*

  def record(entry: DeadLetterEntry): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            """INSERT INTO subscription_dead_letters
              |  (subscription_id, original_message_id, origin_topic, payload, attributes, delivery_attempts, dead_lettered_at)
              |VALUES (?, ?, ?, ?, ?, ?, ?)
              |ON CONFLICT DO NOTHING""".stripMargin
          )
          ps.setString(1, entry.subscriptionId.value)
          ps.setString(2, entry.originalMessageId.value)
          entry.originTopic match
            case Some(t) => ps.setString(3, t.value)
            case None    => ps.setNull(3, Types.VARCHAR)
          ps.setBytes(4, entry.payload.toArray)
          ps.setString(5, entry.attributes.toJson.compactPrint)
          ps.setInt(6, entry.deliveryAttempts)
          ps.setTimestamp(7, Timestamp.from(entry.deadLetteredAt))
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  def listFor(subscriptionId: SubscriptionId): Future[List[DeadLetterEntry]] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            s"SELECT $Columns FROM subscription_dead_letters WHERE subscription_id = ? ORDER BY dead_lettered_at DESC"
          )
          ps.setString(1, subscriptionId.value)
          val rs = ps.executeQuery()
          Iterator.continually(rs).takeWhile(_.next()).flatMap(readEntry).toList
        }
      }
    }

  def find(subscriptionId: SubscriptionId, messageId: MessageId): Future[Option[DeadLetterEntry]] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            s"SELECT $Columns FROM subscription_dead_letters WHERE subscription_id = ? AND original_message_id = ?"
          )
          ps.setString(1, subscriptionId.value)
          ps.setString(2, messageId.value)
          val rs = ps.executeQuery()
          Iterator.continually(rs).takeWhile(_.next()).flatMap(readEntry).nextOption()
        }
      }
    }

  def markRedriven(subscriptionId: SubscriptionId, messageId: MessageId, at: Instant): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            "UPDATE subscription_dead_letters SET redriven_at = ? WHERE subscription_id = ? AND original_message_id = ?"
          )
          ps.setTimestamp(1, Timestamp.from(at))
          ps.setString(2, subscriptionId.value)
          ps.setString(3, messageId.value)
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  private val Columns =
    "subscription_id, original_message_id, origin_topic, payload, attributes, delivery_attempts, dead_lettered_at, redriven_at"

  /** A row that cannot be parsed back into domain types is skipped rather than
    * failing the whole listing -- a browse is diagnostic, and one unreadable row
    * must not hide the rest.
    */
  private def readEntry(rs: java.sql.ResultSet): Option[DeadLetterEntry] =
    for
      sub <- SubscriptionId.from(rs.getString("subscription_id")).toOption
      msg <- MessageId.from(rs.getString("original_message_id")).toOption
    yield DeadLetterEntry(
      subscriptionId = sub,
      originalMessageId = msg,
      originTopic = Option(rs.getString("origin_topic")).flatMap(TopicId.from(_).toOption),
      payload = Option(rs.getBytes("payload")).fold(Vector.empty[Byte])(_.toVector),
      attributes = Option(rs.getString("attributes")).fold(Map.empty[String, String])(parseAttributes),
      deliveryAttempts = rs.getInt("delivery_attempts"),
      deadLetteredAt = rs.getTimestamp("dead_lettered_at").toInstant,
      redrivenAt = Option(rs.getTimestamp("redriven_at")).map(_.toInstant)
    )

  private def parseAttributes(raw: String): Map[String, String] =
    scala.util.Try(raw.parseJson.convertTo[Map[String, String]]).toOption.getOrElse(Map.empty)

  private def withConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    try f(conn)
    finally conn.close()
