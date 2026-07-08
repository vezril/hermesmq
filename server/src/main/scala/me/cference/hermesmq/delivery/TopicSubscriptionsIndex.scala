package me.cference.hermesmq.delivery

import me.cference.hermesmq.domain.{SubscriptionId, TopicId}

import java.util.concurrent.atomic.AtomicReference

/** In-memory index of which subscriptions are bound to each topic, used by the
  * delivery fan-out. Single-node and rebuildable from `SubscriptionCreated`
  * events on startup. Thread-safe (delivery reads while creations write).
  */
final class TopicSubscriptionsIndex:
  private val byTopic = new AtomicReference(Map.empty[TopicId, Set[SubscriptionId]])

  /** Record that `subscriptionId` is bound to `topicId`. */
  def add(topicId: TopicId, subscriptionId: SubscriptionId): Unit =
    byTopic.updateAndGet { current =>
      current.updated(topicId, current.getOrElse(topicId, Set.empty) + subscriptionId)
    }

  /** The subscriptions currently bound to `topicId` (empty if none). */
  def subscriptionsFor(topicId: TopicId): Set[SubscriptionId] =
    byTopic.get().getOrElse(topicId, Set.empty)

object TopicSubscriptionsIndex:
  def apply(): TopicSubscriptionsIndex = new TopicSubscriptionsIndex
