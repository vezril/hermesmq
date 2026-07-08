package me.cference.hermesmq.observability

import java.time.Instant

/** Renders the stats read models as Prometheus text exposition (version 0.0.4),
  * dependency-light and pure so it is directly testable. `now` is supplied at the
  * boundary so age gauges are deterministic.
  */
object PrometheusText:

  /** One metric family: HELP/TYPE metadata followed by zero or more samples,
    * each `(labelName, labelValue, value)`.
    */
  private def block(name: String, help: String, metricType: String, samples: List[(String, String, Long)]): String =
    val header = s"# HELP $name $help\n# TYPE $name $metricType\n"
    val lines  = samples.map { (labelName, labelValue, value) => s"""$name{$labelName="${escape(labelValue)}"} $value\n""" }
    header + lines.mkString

  /** Escape a label value per the Prometheus text format: backslash, double
    * quote, and newline.
    */
  private def escape(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

  def render(subscriptions: List[SubscriptionStats], topics: List[TopicStats], now: Instant): String =
    List(
      block(
        "hermesmq_subscription_backlog",
        "Outstanding (unacknowledged) messages per subscription.",
        "gauge",
        subscriptions.map(s => ("subscription", s.subscriptionId.value, s.backlog.toLong))
      ),
      block(
        "hermesmq_subscription_oldest_unacked_age_seconds",
        "Age in seconds of the oldest unacknowledged message per subscription.",
        "gauge",
        subscriptions.map(s => ("subscription", s.subscriptionId.value, s.oldestUnackedAgeSeconds(now)))
      ),
      block(
        "hermesmq_messages_published_total",
        "Total messages published per topic.",
        "counter",
        topics.map(t => ("topic", t.topicId.value, t.publishedTotal))
      ),
      block(
        "hermesmq_messages_redelivered_total",
        "Total redeliveries (ack-deadline expiries) per subscription.",
        "counter",
        subscriptions.map(s => ("subscription", s.subscriptionId.value, s.redeliveredTotal))
      ),
      block(
        "hermesmq_messages_dead_lettered_total",
        "Total dead-lettered messages per subscription.",
        "counter",
        subscriptions.map(s => ("subscription", s.subscriptionId.value, s.deadLetteredTotal))
      )
    ).mkString
