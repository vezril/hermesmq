package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.TopicId

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

/** In-memory, per-node registry of recently-seen named producers per topic.
  * Best-effort and ephemeral (never journaled): each publish carrying a producer
  * id `touch`es it, and a producer counts as active while it was seen within
  * `activityWindow`. A non-positive window disables tracking.
  *
  * Thread-safe via an atomic swap of an immutable map, so concurrent publishes
  * and the metrics scrape never see a torn state. (The producer twin of
  * [[ConsumerRegistry]], keyed by topic rather than subscription.)
  */
final class ProducerRegistry(activityWindow: FiniteDuration):

  private val state = new AtomicReference[Map[TopicId, Map[String, Instant]]](Map.empty)

  def enabled: Boolean = activityWindow > Duration.Zero

  /** Record that `producer` published to `topic` at `now`. Ignores an empty id,
    * is a no-op when disabled, and prunes that topic's stale entries.
    */
  def touch(topic: TopicId, producer: String, now: Instant): Unit =
    if enabled && producer.nonEmpty then
      state.updateAndGet { m =>
        val fresh = activeEntries(m.getOrElse(topic, Map.empty), now).updated(producer, now)
        m.updated(topic, fresh)
      }
      ()

  /** Count of distinct producers of `topic` active within the window at `now`. */
  def activeCount(topic: TopicId, now: Instant): Int =
    if enabled then activeEntries(state.get.getOrElse(topic, Map.empty), now).size else 0

  /** Active producer counts for every topic that currently has any. */
  def activeCountsByTopic(now: Instant): Map[TopicId, Int] =
    if !enabled then Map.empty
    else
      state.get.view
        .map((topic, producers) => topic -> activeEntries(producers, now).size)
        .filter((_, count) => count > 0)
        .toMap

  private def activeEntries(producers: Map[String, Instant], now: Instant): Map[String, Instant] =
    val cutoff = now.minusNanos(activityWindow.toNanos)
    producers.filter((_, seen) => !seen.isBefore(cutoff))
