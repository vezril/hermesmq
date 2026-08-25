package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.SubscriptionId

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

/** In-memory, per-node registry of recently-seen named consumers per
  * subscription. Best-effort and ephemeral (never journaled): each consume call
  * carrying a consumer id `touch`es it, and a consumer counts as active while it
  * was seen within `activityWindow`. A non-positive window disables tracking.
  *
  * Thread-safe via an atomic swap of an immutable map, so concurrent consume
  * calls and the metrics scrape never see a torn state.
  */
final class ConsumerRegistry(activityWindow: FiniteDuration):

  private val state = new AtomicReference[Map[SubscriptionId, Map[String, Instant]]](Map.empty)

  def enabled: Boolean = activityWindow > Duration.Zero

  /** Record that `consumer` consumed `subscription` at `now`. Ignores an empty
    * id, is a no-op when disabled, and prunes that subscription's stale entries.
    */
  def touch(subscription: SubscriptionId, consumer: String, now: Instant): Unit =
    if enabled && consumer.nonEmpty then
      state.updateAndGet { m =>
        val fresh = activeEntries(m.getOrElse(subscription, Map.empty), now).updated(consumer, now)
        m.updated(subscription, fresh)
      }
      ()

  /** Count of distinct consumers of `subscription` active within the window at `now`. */
  def activeCount(subscription: SubscriptionId, now: Instant): Int =
    if enabled then activeEntries(state.get.getOrElse(subscription, Map.empty), now).size else 0

  /** Active consumer counts for every subscription that currently has any. */
  def activeCountsBySubscription(now: Instant): Map[SubscriptionId, Int] =
    if !enabled then Map.empty
    else
      state.get.view
        .map((sub, consumers) => sub -> activeEntries(consumers, now).size)
        .filter((_, count) => count > 0)
        .toMap

  private def activeEntries(consumers: Map[String, Instant], now: Instant): Map[String, Instant] =
    val cutoff = now.minusNanos(activityWindow.toNanos)
    consumers.filter((_, seen) => !seen.isBefore(cutoff))
