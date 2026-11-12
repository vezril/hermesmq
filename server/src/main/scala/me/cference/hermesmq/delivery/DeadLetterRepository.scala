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

/** Position in a descending walk of a subscription's dead-letters.
  *
  * Carries the message id as well as the timestamp because `deadLetteredAt` is
  * the journal envelope's time, so entries from one batch can share it -- a
  * timestamp-only cursor would drop everything tied with the page boundary.
  */
final case class DeadLetterCursor(deadLetteredAt: Instant, originalMessageId: MessageId)

/** One page of dead-letters, newest first. `nextCursor` is present only when
  * more entries remain, so an absent cursor means the walk is done.
  */
final case class DeadLetterPage(entries: List[DeadLetterEntry], nextCursor: Option[DeadLetterCursor])

/** The durable, cluster-shared dead-letter read model. Written by the
  * dead-letter index projection; read by the operator browse and redrive paths.
  */
trait DeadLetterRepository:

  /** Record a dead-letter. First write wins: an at-least-once projection can
    * reprocess the same envelope, which must not mutate or duplicate the row.
    */
  def record(entry: DeadLetterEntry): Future[Unit]

  /** One page of a subscription's dead-letters, most recently dead-lettered
    * first. Keyset-paginated on `(deadLetteredAt DESC, originalMessageId DESC)`:
    * `cursor` is the last position seen, `None` starts from the newest. Keyset
    * rather than offset because dead-letters arrive precisely while an operator
    * is paging through them, and an offset walk would then skip or repeat rows.
    */
  def listFor(subscriptionId: SubscriptionId, limit: Int, cursor: Option[DeadLetterCursor]): Future[DeadLetterPage]

  def find(subscriptionId: SubscriptionId, messageId: MessageId): Future[Option[DeadLetterEntry]]

  /** Atomically claim an entry for redrive, returning whether this caller won it.
    *
    * This is the redrive idempotency guarantee: the claim is a compare-and-swap
    * on `redrivenAt`, so exactly one of several concurrent redrives of the same
    * entry may proceed to publish. It deliberately does NOT depend on the
    * publish-dedup window (`HERMESMQ_DEDUP_WINDOW`), which defaults to off.
    *
    * `force` claims regardless of an existing stamp -- the escape hatch for an
    * entry claimed by a caller that then died before publishing. False for an
    * entry that does not exist.
    */
  def claimForRedrive(subscriptionId: SubscriptionId, messageId: MessageId, at: Instant, force: Boolean): Future[Boolean]

  /** Release a claim, so a failed publish can be retried rather than leaving the
    * entry permanently reported as already redriven.
    */
  def releaseClaim(subscriptionId: SubscriptionId, messageId: MessageId): Future[Unit]

/** In-memory implementation for tests (and single-node fallbacks). */
final class InMemoryDeadLetterRepository(using ExecutionContext) extends DeadLetterRepository:
  private val entries = new AtomicReference(Map.empty[(SubscriptionId, MessageId), DeadLetterEntry])

  private def key(entry: DeadLetterEntry) = (entry.subscriptionId, entry.originalMessageId)

  def record(entry: DeadLetterEntry): Future[Unit] =
    Future(entries.updateAndGet { m =>
      if m.contains(key(entry)) then m else m.updated(key(entry), entry)
    }).map(_ => ())

  def listFor(subscriptionId: SubscriptionId, limit: Int, cursor: Option[DeadLetterCursor]): Future[DeadLetterPage] =
    Future {
      val ordered = entries
        .get()
        .values
        .filter(_.subscriptionId == subscriptionId)
        .toList
        // Mirror the SQL ordering exactly, message id breaking a shared timestamp.
        .sortBy(e => (e.deadLetteredAt, e.originalMessageId.value))(using
          Ordering.Tuple2(Ordering[Instant], Ordering[String]).reverse
        )
      val after = cursor.fold(ordered) { c =>
        ordered.dropWhile(e =>
          (e.deadLetteredAt, e.originalMessageId.value) != ((c.deadLetteredAt, c.originalMessageId.value))
        ) match
          case Nil      => Nil // a cursor for an entry that has since gone
          case _ :: rest => rest
      }
      // Read one past the page to decide whether a cursor is warranted, so the
      // last page reports None rather than a cursor onto nothing.
      val page = after.take(limit)
      val more = after.sizeIs > limit
      DeadLetterPage(page, Option.when(more && page.nonEmpty)(cursorOf(page.last)))
    }

  def find(subscriptionId: SubscriptionId, messageId: MessageId): Future[Option[DeadLetterEntry]] =
    Future(entries.get().get((subscriptionId, messageId)))

  def claimForRedrive(
      subscriptionId: SubscriptionId,
      messageId: MessageId,
      at: Instant,
      force: Boolean
  ): Future[Boolean] =
    Future {
      val key = (subscriptionId, messageId)
      // getAndUpdate + recheck keeps the decision and the write in one atomic
      // step, matching the conditional UPDATE the JDBC implementation issues.
      val before = entries.getAndUpdate { m =>
        m.get(key) match
          case Some(e) if force || e.redrivenAt.isEmpty => m.updated(key, e.copy(redrivenAt = Some(at)))
          case _                                        => m
      }
      before.get(key).exists(e => force || e.redrivenAt.isEmpty)
    }

  def releaseClaim(subscriptionId: SubscriptionId, messageId: MessageId): Future[Unit] =
    Future(entries.updateAndGet { m =>
      val key = (subscriptionId, messageId)
      m.get(key).fold(m)(e => m.updated(key, e.copy(redrivenAt = None)))
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

  def listFor(subscriptionId: SubscriptionId, limit: Int, cursor: Option[DeadLetterCursor]): Future[DeadLetterPage] =
    Future {
      blocking {
        withConnection { conn =>
          // Row-value comparison is the keyset predicate: strictly "after" the
          // cursor in (time, id) descending order, which the
          // subscription_dead_letters_sub_idx index already orders by.
          val keyset =
            if cursor.isDefined then " AND (dead_lettered_at, original_message_id) < (?, ?)" else ""
          val ps = conn.prepareStatement(
            s"""SELECT $Columns FROM subscription_dead_letters
               | WHERE subscription_id = ?$keyset
               | ORDER BY dead_lettered_at DESC, original_message_id DESC
               | LIMIT ?""".stripMargin
          )
          ps.setString(1, subscriptionId.value)
          val limitIndex = cursor.fold(2) { c =>
            ps.setTimestamp(2, Timestamp.from(c.deadLetteredAt))
            ps.setString(3, c.originalMessageId.value)
            4
          }
          // Fetch one extra row to tell "more remain" from "exactly a full page".
          ps.setInt(limitIndex, limit + 1)
          val rs      = ps.executeQuery()
          val fetched = Iterator.continually(rs).takeWhile(_.next()).flatMap(readEntry).toList
          val page    = fetched.take(limit)
          DeadLetterPage(page, Option.when(fetched.sizeIs > limit && page.nonEmpty)(cursorOf(page.last)))
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

  /** The conditional UPDATE *is* the claim: the affected-row count decides the
    * winner, so two concurrent redrives of one entry cannot both proceed. Row
    * locking makes this atomic without an explicit transaction.
    */
  def claimForRedrive(
      subscriptionId: SubscriptionId,
      messageId: MessageId,
      at: Instant,
      force: Boolean
  ): Future[Boolean] =
    Future {
      blocking {
        withConnection { conn =>
          val guard = if force then "" else " AND redriven_at IS NULL"
          val ps = conn.prepareStatement(
            s"""UPDATE subscription_dead_letters SET redriven_at = ?
               | WHERE subscription_id = ? AND original_message_id = ?$guard""".stripMargin
          )
          ps.setTimestamp(1, Timestamp.from(at))
          ps.setString(2, subscriptionId.value)
          ps.setString(3, messageId.value)
          ps.executeUpdate() == 1
        }
      }
    }

  def releaseClaim(subscriptionId: SubscriptionId, messageId: MessageId): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            "UPDATE subscription_dead_letters SET redriven_at = NULL WHERE subscription_id = ? AND original_message_id = ?"
          )
          ps.setString(1, subscriptionId.value)
          ps.setString(2, messageId.value)
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

private[delivery] def cursorOf(entry: DeadLetterEntry): DeadLetterCursor =
  DeadLetterCursor(entry.deadLetteredAt, entry.originalMessageId)
