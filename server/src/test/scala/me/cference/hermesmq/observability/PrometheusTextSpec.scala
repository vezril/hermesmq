package me.cference.hermesmq.observability

import me.cference.hermesmq.domain.{SubscriptionId, TopicId}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.time.Instant

/** Tests the pure Prometheus text-exposition renderer. */
final class PrometheusTextSpec extends AnyWordSpec with Matchers:

  private val now = Instant.parse("2026-07-07T12:01:30Z")

  private def sub(id: String, backlog: Int, oldest: Option[Instant], red: Long, dead: Long) =
    SubscriptionStats(SubscriptionId.from(id).toOption.get, TopicId.from("orders").toOption.get, backlog, oldest, red, dead)

  "PrometheusText.render" should {
    "emit HELP/TYPE metadata and labelled samples for subscriptions and topics" in {
      val out = PrometheusText.render(
        subscriptions = List(sub("s1", backlog = 2, oldest = Some(Instant.parse("2026-07-07T12:00:00Z")), red = 3, dead = 1)),
        topics = List(TopicStats(TopicId.from("orders").toOption.get, publishedTotal = 5, deleted = false)),
        now = now
      )
      out should include("# TYPE hermesmq_subscription_backlog gauge")
      out should include("""hermesmq_subscription_backlog{subscription="s1"} 2""")
      out should include("""hermesmq_subscription_oldest_unacked_age_seconds{subscription="s1"} 90""")
      out should include("# TYPE hermesmq_messages_published_total counter")
      out should include("""hermesmq_messages_published_total{topic="orders"} 5""")
      out should include("""hermesmq_messages_redelivered_total{subscription="s1"} 3""")
      out should include("""hermesmq_messages_dead_lettered_total{subscription="s1"} 1""")
    }

    "escape backslash and quote in label values" in {
      val out = PrometheusText.render(List(sub("""a"b\c""", 1, Some(now), 0, 0)), Nil, now)
      out should include("""hermesmq_subscription_backlog{subscription="a\"b\\c"} 1""")
    }

    "produce a valid, sample-free exposition when there is no data" in {
      val out = PrometheusText.render(Nil, Nil, now)
      out should include("# TYPE hermesmq_subscription_backlog gauge")
      out should include("# TYPE hermesmq_messages_published_total counter")
      out should not include "hermesmq_subscription_backlog{"
      out should not include "hermesmq_messages_published_total{"
    }
  }
