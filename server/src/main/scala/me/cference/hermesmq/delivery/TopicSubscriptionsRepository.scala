package me.cference.hermesmq.delivery

import me.cference.hermesmq.config.DbConfig
import me.cference.hermesmq.domain.SubscriptionId
import me.cference.hermesmq.domain.TopicId

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.blocking

/** The durable, cluster-shared topic→subscriptions read model. Reads are used by
  * delivery fan-out; writes are applied by the subscription-index projection.
  */
trait TopicSubscriptionsRepository:
  def add(topicId: TopicId, subscriptionId: SubscriptionId): Future[Unit]
  def remove(topicId: TopicId, subscriptionId: SubscriptionId): Future[Unit]
  def subscriptionsFor(topicId: TopicId): Future[Set[SubscriptionId]]

  /** The reverse lookup: which topic a subscription belongs to.
    *
    * Dead-letter routing records the origin topic on the envelope, and redrive
    * republishes to it; this read model is the only durable, cluster-shared
    * place that mapping lives. `None` for a subscription the index has not seen
    * (or that has been removed) -- callers treat it as "unresolved" rather than
    * an error, since losing a dead-letter is worse than losing its origin topic.
    */
  def topicFor(subscriptionId: SubscriptionId): Future[Option[TopicId]]

/** In-memory implementation for tests (and single-node fallbacks). */
final class InMemoryTopicSubscriptionsRepository(using ExecutionContext) extends TopicSubscriptionsRepository:
  private val byTopic = new AtomicReference(Map.empty[TopicId, Set[SubscriptionId]])

  def add(topicId: TopicId, subscriptionId: SubscriptionId): Future[Unit] =
    Future(byTopic.updateAndGet(m => m.updated(topicId, m.getOrElse(topicId, Set.empty) + subscriptionId))).map(_ => ())

  def remove(topicId: TopicId, subscriptionId: SubscriptionId): Future[Unit] =
    Future(byTopic.updateAndGet { m =>
      val left = m.getOrElse(topicId, Set.empty) - subscriptionId
      // Drop the key rather than leave an empty set, so a topic with no
      // subscribers reads the same whether it lost them or never had any.
      if left.isEmpty then m.removed(topicId) else m.updated(topicId, left)
    }).map(_ => ())

  def subscriptionsFor(topicId: TopicId): Future[Set[SubscriptionId]] =
    Future(byTopic.get().getOrElse(topicId, Set.empty))

  def topicFor(subscriptionId: SubscriptionId): Future[Option[TopicId]] =
    Future(byTopic.get().collectFirst { case (topic, subs) if subs.contains(subscriptionId) => topic })

object InMemoryTopicSubscriptionsRepository:
  def apply()(using ExecutionContext): InMemoryTopicSubscriptionsRepository = new InMemoryTopicSubscriptionsRepository

/** PostgreSQL implementation backed by the `topic_subscriptions` table. */
final class JdbcTopicSubscriptionsRepository(dbConfig: DbConfig)(using ExecutionContext)
    extends TopicSubscriptionsRepository:

  def add(topicId: TopicId, subscriptionId: SubscriptionId): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            "INSERT INTO topic_subscriptions (topic_id, subscription_id) VALUES (?, ?) ON CONFLICT DO NOTHING"
          )
          ps.setString(1, topicId.value)
          ps.setString(2, subscriptionId.value)
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  def remove(topicId: TopicId, subscriptionId: SubscriptionId): Future[Unit] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement(
            "DELETE FROM topic_subscriptions WHERE topic_id = ? AND subscription_id = ?"
          )
          ps.setString(1, topicId.value)
          ps.setString(2, subscriptionId.value)
          ps.executeUpdate()
        }
      }
    }.map(_ => ())

  def subscriptionsFor(topicId: TopicId): Future[Set[SubscriptionId]] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement("SELECT subscription_id FROM topic_subscriptions WHERE topic_id = ?")
          ps.setString(1, topicId.value)
          val rs = ps.executeQuery()
          Iterator.continually(rs).takeWhile(_.next()).flatMap(r => SubscriptionId.from(r.getString(1)).toOption).toSet
        }
      }
    }

  def topicFor(subscriptionId: SubscriptionId): Future[Option[TopicId]] =
    Future {
      blocking {
        withConnection { conn =>
          val ps = conn.prepareStatement("SELECT topic_id FROM topic_subscriptions WHERE subscription_id = ?")
          ps.setString(1, subscriptionId.value)
          val rs = ps.executeQuery()
          Iterator.continually(rs).takeWhile(_.next()).flatMap(r => TopicId.from(r.getString(1)).toOption).nextOption()
        }
      }
    }

  private def withConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    try f(conn)
    finally conn.close()
