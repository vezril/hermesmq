package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.TopicId

import java.util.concurrent.atomic.AtomicReference

/** In-memory, per-node counter of publishes collapsed as duplicates, per topic.
  * Best-effort and ephemeral (never journaled): a deduplicated publish emits no
  * event, so the count is derived from the publish reply and lives only in this
  * process. Thread-safe via an atomic swap of an immutable map.
  */
final class DedupCounter:

  private val state = new AtomicReference[Map[TopicId, Long]](Map.empty)

  /** Record one deduplicated publish for `topic`. */
  def increment(topic: TopicId): Unit =
    state.updateAndGet(m => m.updated(topic, m.getOrElse(topic, 0L) + 1L))
    ()

  /** The per-topic dedup totals seen by this process. */
  def counts: Map[TopicId, Long] = state.get
