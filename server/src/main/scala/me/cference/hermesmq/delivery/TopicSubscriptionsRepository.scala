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
  def subscriptionsFor(topicId: TopicId): Future[Set[SubscriptionId]]

/** In-memory implementation for tests (and single-node fallbacks). */
final class InMemoryTopicSubscriptionsRepository(using ExecutionContext) extends TopicSubscriptionsRepository:
  private val byTopic = new AtomicReference(Map.empty[TopicId, Set[SubscriptionId]])

  def add(topicId: TopicId, subscriptionId: SubscriptionId): Future[Unit] =
    Future(byTopic.updateAndGet(m => m.updated(topicId, m.getOrElse(topicId, Set.empty) + subscriptionId))).map(_ => ())

  def subscriptionsFor(topicId: TopicId): Future[Set[SubscriptionId]] =
    Future(byTopic.get().getOrElse(topicId, Set.empty))

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

  private def withConnection[A](f: Connection => A): A =
    val conn = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password)
    try f(conn)
    finally conn.close()
